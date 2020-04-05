import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static java.nio.channels.SelectionKey.OP_READ;

public class UDPClient {

    private static final Logger logger = LoggerFactory.getLogger(UDPClient.class);

    protected static final int DATA = 0;
    protected static final int SYN = 1;
    protected static final int SYN_ACK = 2;
    protected static final int ACK = 3;
    protected static final int NAK = 4;
    protected static final int DATA_CHUNK_SIZE = 1013; //1013

    private static long startTime = 0;
    private static long endTime = 0;
    private static long estimatedRTT = 1000;
    private static long sampleRTT = 0;
    private static long devRTT = 0;
    private static long timeoutInterval = 10000;
    private static int windowHead = 0;
    private static int windowEnd = 0;
    protected static int numberOfPackets = 0;
    private static long sequenceNumber = 0;
    private static ArrayList<Boolean> ackList;
    private static ArrayList<Boolean> sentList;


    protected static void runClient(SocketAddress routerAddr, ArrayList<Packet> packetList, Packet syn, Packet ack) throws IOException {
        try (DatagramChannel channel = DatagramChannel.open()) {
            ackList = new ArrayList<>(Arrays.asList(new Boolean[numberOfPackets]));
            sentList = new ArrayList<>(Arrays.asList(new Boolean[numberOfPackets]));
            Collections.fill(ackList, Boolean.FALSE);
            Collections.fill(sentList, Boolean.FALSE);

            channel.configureBlocking(false);
            Selector selector = Selector.open();
            channel.register(selector, OP_READ);

            doThreeWayHandshake(routerAddr, channel, syn, ack, selector);

            //send data packets
            while (ackList.contains(false)) {
                //send new packets in window
                sendWindow(routerAddr, packetList, channel, ackList, false);

                // Try to receive a packet within timeout.
                logger.info("Waiting for the response - {}ms", timeoutInterval);
                selector.select(timeoutInterval);

                Set<SelectionKey> keys = selector.selectedKeys();
                if (keys.isEmpty()) {
                    logger.error("No response after timeout. Sending un-ACKed packets");
                    //send list of packets that were not ACK
                    sendWindow(routerAddr, packetList, channel, ackList, true);
                    continue;
                }

                //receive packet when available in channel (asynchronous)
                Packet response = receivePacket(channel);
                if (response.getType() == NAK) {
                    sendPacket(routerAddr, channel, packetList.get((int) response.getSequenceNumber()));
                }
                if (response.getType() == ACK) {
                    ackList.set((int) response.getSequenceNumber(), true);
                    while (windowEnd < ackList.size() && ackList.get(windowHead) == true) {
                        windowHead += 1;
                        windowEnd += 1;
                    }
                }

                updateRTT();
                keys.clear();
            }


        }
    }

    private static void doThreeWayHandshake(SocketAddress routerAddr, DatagramChannel channel, Packet syn, Packet ack, Selector selector) throws IOException {
        while(true){
            //send SYN
            sendPacket(routerAddr, channel, syn);
            logger.info("Waiting for the SYN_ACK");
            selector.select(timeoutInterval);
            Set<SelectionKey> keys = selector.selectedKeys();
            if (keys.isEmpty()) {
                logger.error("No response after timeout. Sending SYN again.");
                continue;
            }
            Packet response = receivePacket(channel);
            if (response.getType() == NAK) {
                sendPacket(routerAddr, channel, syn);
            }
            if (response.getType() == SYN_ACK) {
                sendPacket(routerAddr, channel, ack);
                break;
            }
        }
    }

    private static void sendWindow(SocketAddress routerAddr, ArrayList<Packet> packetList, DatagramChannel channel, ArrayList<Boolean> ackList, boolean wasPreviouslySent) throws IOException {
        for (int i = windowHead; i < windowEnd; i++) {
            if (!ackList.get(i) && sentList.get(i) == wasPreviouslySent) {
                sendPacket(routerAddr, channel, packetList.get(i));
                sentList.set(i, true);
            }
        }
    }

    private static Packet receivePacket(DatagramChannel channel) throws IOException {
        // We just want a single response.
        ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
        //write to the buffer
        SocketAddress router = channel.receive(buf);
        endTime = System.currentTimeMillis();
        //change buffer to be readable
        buf.flip();
        //read from buffer and create packet
        Packet resp = Packet.fromBuffer(buf);
        logger.info("RECEIVED PACKET----------------------");
        logger.info("Packet: {}", resp);
        logger.info("Router: {}", router);
        String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
        logger.info("Payload: {}", payload);

        return resp;
    }

    private static void sendPacket(SocketAddress routerAddr, DatagramChannel channel, Packet p) throws IOException {
        channel.send(p.toBuffer(), routerAddr);
        // start timer
        startTime = System.currentTimeMillis();
        logger.info("Sending \"{}\" to router at {}", new String(p.getPayload(), StandardCharsets.UTF_8), routerAddr);
    }

    public static void updateRTT() {
        sampleRTT = endTime - startTime;
        estimatedRTT = (long) ((0.875 * estimatedRTT) + (0.125 * sampleRTT));
        devRTT = (long) ((0.75 * devRTT) + 0.25 * (Math.abs(sampleRTT - estimatedRTT)));
        timeoutInterval = estimatedRTT + 4 * devRTT;
    }

    protected static ArrayList<Packet> buildPackets(String data, InetSocketAddress serverAddr, int packetType) throws IOException {
        // payload of each packet should be between 0 and 1013 bytes
        ArrayList<Packet> arrayOfPackets = new ArrayList<>();
        byte[] dataInBytes = data.getBytes();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(dataInBytes);
        byte[] buffer = new byte[DATA_CHUNK_SIZE];
        byte[] payload;
        int len;
        int ctr = 0;

        while ((len = byteArrayInputStream.read(buffer)) > 0) {
            if (len < buffer.length) {
                // adds the last element of the array
                payload = Arrays.copyOfRange(dataInBytes, ctr, ctr + len + 1);
            } else {
                payload = Arrays.copyOfRange(dataInBytes, ctr, ctr + len);
            }

            Packet p = makePacket(serverAddr, packetType, payload);

            arrayOfPackets.add(p);
            ctr = ctr + len;
        }
        numberOfPackets = arrayOfPackets.size();
        windowEnd = (numberOfPackets>1) ? (numberOfPackets / 2) : 1;
        return arrayOfPackets;
    }

    protected static Packet makePacket(InetSocketAddress serverAddr, int packetType, byte[] payload) {
        return new Packet.Builder()
                .setType(packetType)
                .setSequenceNumber(sequenceNumber++)
                .setPortNumber(serverAddr.getPort())
                .setPeerAddress(serverAddr.getAddress())
                .setPayload(payload)
                .create();
    }

    private Socket socket;
    private static String sender = "";
    private static String receiver;
    private Boolean outputToFile;
    private String filePath;

    private UDPClient() {
        //do not allow creating of TCP client without any params;
    }

    public void setOutputToFile(Boolean outputToFile) {
        this.outputToFile = outputToFile;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public UDPClient(Socket socket, String sender, String receiver) {
        this.socket = socket;
        this.sender = sender;
        this.receiver = receiver;
    }

    public UDPClient(String host, int port){
        try{
            this.socket = new Socket(host, port);
            this.sender = "";
            this.receiver = "";
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getSender(){
        return sender;
    }

    public String getReceiver(){
        return this.receiver;
    }

    public void sendRequest(RequestType requestType, String endpoint, String host, String header, String data, boolean verbose) throws IOException {
        sender = sender.concat(requestType+" "+endpoint+" HTTP/1.0" + "\n");
        sender = sender.concat("Host: "+host + "\n");
        sender = sender.concat(header + "\n");

        if(requestType == RequestType.GET) sendGetRequest();
//        if(requestType == RequestType.POST) sendPostRequest(header, data);

//        printResponse(verbose);
//        socket.close();
    }

    private void sendGetRequest() throws IOException {
        sender = sender.concat("Connection: Close");
        sender = sender.concat("\n");
        sendRequestToRouter();
    }

    public static void sendRequestToRouter() throws IOException {
        //        3. Client is running at port 41830 (uses an ephemeral port) at the host 192.168.2.125
        SocketAddress routerAddress = new InetSocketAddress("localhost", 3000);
        InetSocketAddress serverAddress = new InetSocketAddress("localhost", 8007);
        ArrayList<Packet> packetList = UDPClient.buildPackets(getSender(), serverAddress, DATA);
        //handshake packets
        Packet syn = makePacket(serverAddress, SYN, ("SYN").getBytes());
        Packet ack = makePacket(serverAddress, ACK, String.valueOf(numberOfPackets).getBytes());
        UDPClient.runClient(routerAddress, packetList, syn, ack);
    }
}

