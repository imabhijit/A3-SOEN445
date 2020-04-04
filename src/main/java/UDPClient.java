import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
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

    private static final int DATA = 0;
    private static final int SYN = 1;
    private static final int SYN_ACK = 2;
    private static final int ACK = 3;
    private static final int NAK = 4;
    private static final int DATA_CHUNK_SIZE = 1013; //1013

    private static long startTime = 0;
    private static long endTime = 0;
    private static long estimatedRTT = 1000;
    private static long sampleRTT = 0;
    private static long devRTT = 0;
    private static long timeoutInterval = 10000;
    private static int windowHead = 0;
    private static int windowEnd = 0;
    private static int numberOfPackets = 0;
    private static long sequenceNumber = 0;
    private static ArrayList<Boolean> ackList;
    private static ArrayList<Boolean> sentList;


    private static void runClient(SocketAddress routerAddr, ArrayList<Packet> packetList, Packet syn, Packet ack) throws IOException {
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

    public static ArrayList<Packet> buildPackets(String data, InetSocketAddress serverAddr, int packetType) throws IOException {
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

    private static Packet makePacket(InetSocketAddress serverAddr, int packetType, byte[] payload) {
        return new Packet.Builder()
                .setType(packetType)
                .setSequenceNumber(sequenceNumber++)
                .setPortNumber(serverAddr.getPort())
                .setPeerAddress(serverAddr.getAddress())
                .setPayload(payload)
                .create();
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.accepts("router-host", "Router hostname")
                .withOptionalArg()
                .defaultsTo("localhost");

        parser.accepts("router-port", "Router port number")
                .withOptionalArg()
                .defaultsTo("3000");

        parser.accepts("server-host", "EchoServer hostname")
                .withOptionalArg()
                .defaultsTo("localhost");

        parser.accepts("server-port", "EchoServer listening port")
                .withOptionalArg()
                .defaultsTo("8007");

        OptionSet opts = parser.parse(args);

        // Router address
        String routerHost = (String) opts.valueOf("router-host");
        int routerPort = Integer.parseInt((String) opts.valueOf("router-port"));

        // Server address
        String serverHost = (String) opts.valueOf("server-host");
        int serverPort = Integer.parseInt((String) opts.valueOf("server-port"));

        SocketAddress routerAddress = new InetSocketAddress(routerHost, routerPort);
        InetSocketAddress serverAddress = new InetSocketAddress(serverHost, serverPort);

        ArrayList<Packet> packetList = buildPackets("Hello World123456789", serverAddress, DATA);
        //handshake packets
        Packet syn = makePacket(serverAddress, SYN, ("SYN").getBytes());
        Packet ack = makePacket(serverAddress, ACK, String.valueOf(numberOfPackets).getBytes());
        runClient(routerAddress, packetList, syn, ack);
    }
}

