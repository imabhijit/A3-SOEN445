import ClientFromA1.RequestType;
import ServerFromA2.Status;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

import static java.util.Arrays.asList;

public class UDPServer {

    private static final Logger logger = LoggerFactory.getLogger(UDPServer.class);
    private static final int DATA = 0;
    private static final int SYN = 1;
    private static final int SYN_ACK = 2;
    private static final int ACK = 3;
    private static final int NAK = 4;
    private static final int FIN = 5;

    private static int windowHead = 0;
    private static int windowEnd = 0;
    private static int numberOfPackets = 0;
    private static long sequenceNumber = 0;
    private static ArrayList<String> payloadList;

    private static String httpVersion;
    private static String filePath;
    private static String pathToMainDirectory = "java/documents";
    private static String requestSpecification = "";
    private static boolean debugging;
    private static String headers = "";
    private static String data = "";
    private static RequestType requestType;

    private void listenAndServe(int port) throws IOException {

        try (DatagramChannel channel = DatagramChannel.open()) {
            payloadList = new ArrayList<>();
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
                int requestType = receivedPacket.getType();
                switch (requestType){
                    case SYN:
                        responseType = SYN_ACK;
                        payload = "SYN_ACK";
                        break;
                    case ACK:
                        numberOfPackets = Integer.valueOf(payload);
                        break;
                    case DATA:
                        responseType = ACK;
                        payloadList.set((int) receivedPacket.getSequenceNumber(), payload);
                        break;
                    case FIN:
                        serveResource();
                        break;
                    default:
                        responseType = NAK;
                        break;
                }
                // Send the response to the router not the client.
                // The peer address of the packet is the address of the client already.
                // We can use toBuilder to copy properties of the current packet.
                // This demonstrate how to create a new packet from an existing packet.
                if(requestType != ACK && requestType != FIN) {
                    Packet resp = makeResponsePacket(responseType, payload, receivedPacket);
                    channel.send(resp.toBuffer(), router);
                }
            }
        }
    }

    private void serveResource() {
        String requestedData = packetPayloadsToString();
        String resourceData = getResource(requestedData);
        //TODO: break into packets and send it to client
    }

    private String packetPayloadsToString() {
        StringBuilder sb = new StringBuilder();
        for(String payload: payloadList){
            sb.append(payload);
        }
        return sb.toString();
    }

    private String getResource(String requestedData) {
        StringBuilder responseWriter = null;
        String resource = "";
        try {
            BufferedReader requestReader = new BufferedReader(new StringReader(requestedData));
            responseWriter = new StringBuilder();
            String request = parseRequest(requestReader);
            resource = createResponse(request);
            System.out.println(resource);

            resetVars();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e){
            e.printStackTrace();
            System.out.println("Something went wrong, try again");
            responseWriter.append(requestSpecification + httpVersion + " " + Status.BAD_REQUEST.toString() + "\r\n" + headers + "\r\n");
        }
        return resource;
    }

    private static String parseRequest(BufferedReader requestReader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line = requestReader.readLine();
        requestType = line.split(" ")[0].equalsIgnoreCase("GET") ? RequestType.GET : RequestType.POST;
        filePath = line.split(" ")[1];
        httpVersion = line.split(" ")[2];
        int count = 0;
        while (line != null) {
            sb.append(line + "\n");
            line = requestReader.readLine();

            if (line.isEmpty()) break;

            if (line.contains("Content-Length")) {
                count = Integer.valueOf(line.split(":")[1]);
            }

            if (!line.contains("Content-")) {
                headers = headers + line + "\r\n";
            }
        }

        for (int i = 0; i < count; i++) {
            data = data + (char) requestReader.read();
        }

        requestSpecification = (debugging) ? sb.toString() + "\r\n" + data + "\r\n" : "";
        return sb.toString();
    }

    public static String createResponse(String requestString) {
        if (requestType == RequestType.GET) {
            return getResponse();
        } else if (requestType == RequestType.POST) {
            return postResponse(requestString);
        } else
            return requestSpecification + httpVersion + " " + Status.BAD_REQUEST.toString() + "\r\n" + headers + "\r\n";
    }

    public static String postResponse(String requestString) {
        String status = Status.OK.toString();
        String contentType = "text/html";
        if (filePath.equals("/") || filePath.equals("/..")) {
            status = Status.BAD_REQUEST.toString();
        } else {
            Path path = Paths.get(pathToMainDirectory + filePath);
            try {
                Files.createDirectories(path.getParent());
                if(!Files.isWritable(path.getParent())){
                    return requestSpecification + httpVersion + " " + Status.FORBIDDEN.toString() + "\r\n" + headers + "\r\n";
                }
                contentType = Files.probeContentType(path);
                if (Files.notExists(path)) {
                    status = Status.CREATED.toString();
                }
                Files.write(path, data.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                status = Status.BAD_REQUEST.toString();
            }
        }
        return requestSpecification + httpVersion + " " + status + "\r\n" + headers + "Content-Length: " + data.length() + "\r\nContent-Type: " + contentType + "\r\n\r\n" + data;
    }

    public static String getResponse() {
        String body = "Home Directory";
        String contentType = "text/html";
        String contentDisposition = "inline";
        if (!filePath.equals("/") && !filePath.equals("/..")) {
            Path path = Paths.get(pathToMainDirectory + filePath);
            try {
                if(Files.notExists(path) || Files.isDirectory(path)) throw new IOException();
                if(!Files.isReadable(path)){
                    return requestSpecification + httpVersion + " " + Status.FORBIDDEN.toString() + "\r\n" + headers + "\r\n";
                }
                contentType = Files.probeContentType(path);
                if (!contentType.equals("text/html") && !contentType.equals("text/plain")) {
                    contentDisposition = "attachment";
                }
                body = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return requestSpecification + httpVersion + " " + Status.NOT_FOUND.toString() + "\r\n" + headers + "Content-Length: " + body.length() + "\r\nContent-Type: " + contentType + "\r\n\r\n" + "404 Not Found.";
            }
        }
        return requestSpecification + httpVersion + " " + Status.OK.toString() + "\r\n" + headers + "Content-Length: " + body.length() + "\r\nContent-Type: " + contentType + "\r\nContent-Disposition: " + contentDisposition + "\r\n\r\n" + body;
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

    private static void resetVars() {
        httpVersion = "";
        filePath = "";
        requestSpecification = "";
        headers = "";
        data = "";
        requestType = null;
    }
}