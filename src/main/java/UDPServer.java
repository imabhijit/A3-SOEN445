import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

public class UDPServer {

    private static final Logger logger = LoggerFactory.getLogger(UDPServer.class);
    private static final int DATA = 0;
    private static final int SYN = 1;
    private static final int SYN_ACK = 2;
    private static final int ACK = 3;
    private static final int NAK = 4;

    private static int windowHead = 0;
    private static int windowEnd = 0;
    private static int numberOfPackets = 0;
    private static long sequenceNumber = 0;

    private void listenAndServe(int port) throws IOException {

        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.bind(new InetSocketAddress(port));
            logger.info("EchoServer is listening at {}", channel.getLocalAddress());
            ByteBuffer buf = ByteBuffer
                    .allocate(Packet.MAX_LEN)
                    .order(ByteOrder.BIG_ENDIAN);

            for (; ; ) {
                buf.clear();
                SocketAddress router = channel.receive(buf);

                int responseType = 0;
                Packet receivedPacket = receivePacket(channel, buf, router);
                String payload = new String(receivedPacket.getPayload(), StandardCharsets.UTF_8);
                switch (receivedPacket.getType()){
                    case SYN:
                        responseType = SYN_ACK;
                        payload = "SYN_ACK";
                        break;
                    case ACK:
                        numberOfPackets = Integer.valueOf(payload);
                        break;
                    case DATA:
                        responseType = ACK;
                        break;
                    default:
                        responseType = NAK;
                        break;
                }
                // Send the response to the router not the client.
                // The peer address of the packet is the address of the client already.
                // We can use toBuilder to copy properties of the current packet.
                // This demonstrate how to create a new packet from an existing packet.
                if(receivedPacket.getType() != ACK) {
                    Packet resp = makeResponsePacket(responseType, payload, receivedPacket);
                    channel.send(resp.toBuffer(), router);
                }

            }
        }
    }

    private Packet makeResponsePacket(int responseType, String payload, Packet packet) {
         return packet.toBuilder()
                .setPayload(payload.getBytes())
                .setType(responseType)
                .setSequenceNumber(packet.getSequenceNumber())
                .create();
    }

    private static Packet receivePacket(DatagramChannel channel, ByteBuffer buf, SocketAddress router) throws IOException {
        //change buffer to be readable
        buf.flip();
        //read from buffer and create packet
        Packet resp = Packet.fromBuffer(buf);
        buf.flip();
        logger.info("RECEIVED PACKET----------------------");
        logger.info("Packet: {}", resp);
        logger.info("Router: {}", router);
        String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
        logger.info("Payload: {}", payload);
        return resp;
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.acceptsAll(asList("port", "p"), "Listening port")
                .withOptionalArg()
                .defaultsTo("8007");

        OptionSet opts = parser.parse(args);
        int port = Integer.parseInt((String) opts.valueOf("port"));
        UDPServer server = new UDPServer();
        server.listenAndServe(port);
    }
}