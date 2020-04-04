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
import java.util.Set;

import static java.nio.channels.SelectionKey.OP_READ;

public class UDPClient {

    private static final Logger logger = LoggerFactory.getLogger(UDPClient.class);

    private final int DATA = 0;
    private final int SYN = 1;
    private final int SYN_ACK = 2;
    private final int ACK = 3;
    private final int NAK = 4;

    private static long startTime = 0;
    private static long endTime = 0;
    private static long estimatedRTT = 0;
    private static long sampleRTT = 0;
    private static long devRTT = 0;
    private static long timeoutInterval = 0;

    private static void runClient(SocketAddress routerAddr, ArrayList<Packet> packetList) throws IOException {
        try(DatagramChannel channel = DatagramChannel.open()){

            for(Packet p: packetList){
                channel.send(p.toBuffer(), routerAddr);
                // start timer
                long startTime = System.currentTimeMillis();
                logger.info("Sending \"{}\" to router at {}", p.getPayload().toString(), routerAddr);

                // Try to receive a packet within timeout.
                channel.configureBlocking(false);
                Selector selector = Selector.open();
                channel.register(selector, OP_READ);
                logger.info("Waiting for the response");
                selector.select(5000);

                Set<SelectionKey> keys = selector.selectedKeys();
                if(keys.isEmpty()){
                    logger.error("No response after timeout");
                    return;
                }

                // We just want a single response.
                ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
                SocketAddress router = channel.receive(buf);
                long endTime = System.currentTimeMillis();
                buf.flip();
                Packet resp = Packet.fromBuffer(buf);
                logger.info("Packet: {}", resp);
                logger.info("Router: {}", router);
                String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
                logger.info("Payload: {}",  payload);

                updateRTT();
                keys.clear();

            }


        }
    }

    public static void updateRTT(){
        sampleRTT = endTime - startTime;
        estimatedRTT = (long) (( 0.875 * estimatedRTT) + (0.125 * sampleRTT));
        devRTT = (long) ((0.75 * devRTT) + 0.25 * (Math.abs(sampleRTT - estimatedRTT)));
        timeoutInterval = estimatedRTT + 4 * devRTT;
    }

    public static ArrayList<Packet> buildPackets(String data, InetSocketAddress serverAddr) throws IOException {
        // payload of each packet should be between 0 and 1013 bytes
        ArrayList<Packet> arrayOfPackets = new ArrayList<>();
        byte[] dataInBytes = data.getBytes();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(dataInBytes);
        byte[] buffer = new byte[5];
        byte[] payload;
        int len;
        int ctr = 0;

        // Should we add syn here?

        while((len = byteArrayInputStream.read(buffer)) > 0){
            if(len < buffer.length){
                // adds the last element of the array
                payload = Arrays.copyOfRange(dataInBytes, ctr, ctr + len + 1);
            }else{
                payload = Arrays.copyOfRange(dataInBytes, ctr, ctr + len);
            }

            Packet p = new Packet.Builder()
                    .setType(0) // TODO: make dynamic
                    .setSequenceNumber(1L) // TODO: make dynamic
                    .setPortNumber(serverAddr.getPort())
                    .setPeerAddress(serverAddr.getAddress())
                    .setPayload(payload)
                    .create();

            arrayOfPackets.add(p);
            ctr = ctr + len;
        }
        return arrayOfPackets;
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

        ArrayList<Packet> packetList = buildPackets("Hello World",serverAddress);
        runClient(routerAddress, packetList);
    }
}

