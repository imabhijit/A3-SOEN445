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

    private static long startTime = 0;
    private static long endTime = 0;
    private static long estimatedRTT = 0;
    private static long sampleRTT = 0;
    private static long devRTT = 0;
    private static long timeoutInterval = 10000;
    private static int windowHead = 0;
    private static int windowEnd = 0;
    private static int numberOfPackets = 0;
    private static long sequenceNumber = 0;


    private static void runClient(SocketAddress routerAddr, ArrayList<Packet> packetList) throws IOException {
        try (DatagramChannel channel = DatagramChannel.open()) {
            ArrayList<Boolean> ackList = new ArrayList<>(Arrays.asList(new Boolean[numberOfPackets]));
            Collections.fill(ackList, Boolean.FALSE);

            while (ackList.contains(false)) {
                //TODO: implement handshake
                //send window
                if(!ackList.get(windowHead)) {
                    sendWindow(routerAddr, packetList, channel, ackList);
                }

                // Try to receive a packet within timeout.
                channel.configureBlocking(false);
                Selector selector = Selector.open();
                channel.register(selector, OP_READ);
                logger.info("Waiting for the response");
                selector.select(timeoutInterval);

                //receive packet
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

                Set<SelectionKey> keys = selector.selectedKeys();
                if (keys.isEmpty()) {
                    logger.error("No response after timeout");
                    //send list of packets that were not ACK
                    sendWindow(routerAddr, packetList, channel, ackList);
                }

                updateRTT();
                keys.clear();
            }


        }
    }

    private static void sendWindow(SocketAddress routerAddr, ArrayList<Packet> packetList, DatagramChannel channel, ArrayList<Boolean> ackList) throws IOException {
        for (int i = windowHead; i < windowEnd; i++) {
            if(!ackList.get(i)) {
                sendPacket(routerAddr, channel, packetList.get(i));
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

    //TODO: implement handshake
    public static boolean threeWayHandshake(InetSocketAddress serverAddr, SocketAddress routerAddr, DatagramChannel channel) throws IOException {
        //send SYN
        Packet syn = makePacket(serverAddr, SYN, new byte[]{});
        sendPacket(routerAddr, channel, syn);
        //Receive SYN_ACK

        //send ACK + payload: numberPackets
        Packet ack = makePacket(serverAddr, ACK, String.valueOf(numberOfPackets).getBytes());
        return true;
    }

    public static ArrayList<Packet> buildPackets(String data, InetSocketAddress serverAddr, int packetType) throws IOException {
        // payload of each packet should be between 0 and 1013 bytes
        ArrayList<Packet> arrayOfPackets = new ArrayList<>();
        byte[] dataInBytes = data.getBytes();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(dataInBytes);
        byte[] buffer = new byte[5];
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
        windowEnd = numberOfPackets / 2;
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
        runClient(routerAddress, packetList);
    }
}

