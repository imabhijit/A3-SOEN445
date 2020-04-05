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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.util.Arrays.asList;

public class UDPServer {

    private static final Logger logger = LoggerFactory.getLogger(UDPServer.class);
    private static final int DATA = 0;
    private static final int SYN = 1;
    private static final int SYN_ACK = 2;
    private static final int ACK = 3;
    private static final int NAK = 4;
    private static final int FIN = 5;

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
    private static HashMap<Integer, String> payloadMap;

    private static String httpVersion;
    private static String filePath;
    private static String pathToMainDirectory = "src/main/java/documents";
    private static String requestSpecification = "";
    private static boolean debugging;
    private static String headers = "";
    private static String data = "";
    private static RequestType requestType;
    InetSocketAddress clientAddr;

    private void listenAndServe(int port) throws IOException {

        try (DatagramChannel channel = DatagramChannel.open()) {
            payloadMap = new HashMap<>();
            channel.bind(new InetSocketAddress(port));
            logger.info("EchoServer is listening at {}", channel.getLocalAddress());
            ByteBuffer buf = ByteBuffer
                    .allocate(Packet.MAX_LEN)
                    .order(ByteOrder.BIG_ENDIAN);

            for (; ; ) {
                buf.clear();
                SocketAddress router = channel.receive(buf);
                int responseType = 0;
                buf.flip();
                Packet receivedPacket = receivePacket(buf, router);
                clientAddr = new InetSocketAddress("localhost", receivedPacket.getPeerPort());
                String payload = new String(receivedPacket.getPayload(), StandardCharsets.UTF_8);
                int requestType = receivedPacket.getType();
                switch (requestType){
                    case SYN:
                        responseType = SYN_ACK;
                        payload = "SYN_ACK";
                        break;
                    case ACK:
                        break;
                    case DATA:
                        responseType = ACK;
                        payloadMap.put((int) receivedPacket.getSequenceNumber(), payload);
                        break;
                    case FIN:
                        serveResource(channel, buf);
                        channel.socket().close();
                        channel.close();
                        channel.disconnect();
                        return;
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
                    buf.flip();
                    channel.send(resp.toBuffer(), router);
                }
            }
        }
    }

    private void serveResource(DatagramChannel channel, ByteBuffer buf) throws IOException {
        SocketAddress routerAddress = new InetSocketAddress("localhost", 3000);
        String requestedData = packetPayloadsToString();
        String resourceData = getResource(requestedData);
        ArrayList<Packet> packetList = buildPackets(resourceData, clientAddr, DATA);
        Packet fin = makePacket(clientAddr, FIN, ("FIN").getBytes());
        sendToClient(routerAddress, packetList, fin, channel, buf);
    }

    //////////////////////////////////////////////////////////////////////////////
    protected static ArrayList<Packet> buildPackets(String data, InetSocketAddress clientAddr, int packetType) throws IOException {
        // payload of each packet should be between 0 and 1013 bytes
        ArrayList<Packet> arrayOfPackets = new ArrayList<>();
        byte[] dataInBytes = data.getBytes();
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(dataInBytes);
        byte[] buffer = new byte[DATA_CHUNK_SIZE];
        byte[] payload;
        int len;
        int ctr = 0;

        while ((len = byteArrayInputStream.read(buffer)) > 0) {
            payload = Arrays.copyOfRange(dataInBytes, ctr, ctr + len);

            Packet p = makePacket(clientAddr, packetType, payload);

            arrayOfPackets.add(p);
            ctr = ctr + len;
        }
        numberOfPackets = arrayOfPackets.size();
        windowEnd = (numberOfPackets > 1) ? (numberOfPackets / 2) : 1;
        return arrayOfPackets;
    }

    protected static Packet makePacket(InetSocketAddress clientAddr, int packetType, byte[] payload) {
        return new Packet.Builder()
                .setType(packetType)
                .setSequenceNumber(sequenceNumber++)
                .setPortNumber(clientAddr.getPort())
                .setPeerAddress(clientAddr.getAddress())
                .setPayload(payload)
                .create();
    }

    private static void sendWindow(SocketAddress routerAddr, ArrayList<Packet> packetList, DatagramChannel channel, ArrayList<Boolean> ackList, boolean wasPreviouslySent) throws IOException {
        for (int i = windowHead; i < windowEnd; i++) {
            if (!ackList.get(i) && sentList.get(i) == wasPreviouslySent) {
                sendPacket(routerAddr, channel, packetList.get(i));
                sentList.set(i, true);
            }
        }
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

    protected static void sendToClient(SocketAddress routerAddr, ArrayList<Packet> packetList, Packet fin, DatagramChannel channel, ByteBuffer buf) throws IOException {
            ackList = new ArrayList<>(Arrays.asList(new Boolean[numberOfPackets]));
            sentList = new ArrayList<>(Arrays.asList(new Boolean[numberOfPackets]));
            Collections.fill(ackList, Boolean.FALSE);
            Collections.fill(sentList, Boolean.FALSE);

            //send data packets
            while (ackList.contains(false)) {
                //send new packets in window
                sendWindow(routerAddr, packetList, channel, ackList, false);
                channel.configureBlocking(false);
                Selector selector = Selector.open();
                channel.register(selector, OP_READ);
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
                Packet response = receiveClientPacket(channel, buf);
                buf.flip();
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
            buf.flip();
            sendPacket(routerAddr, channel, fin);
    }

    public static Packet receiveClientPacket(DatagramChannel channel, ByteBuffer buf) throws IOException {
        buf.clear();
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

    ////////////////////////////////////////////////////////////////////////////////////////////////////

    private String packetPayloadsToString() {
        StringBuilder sb = new StringBuilder();
        for(int i=0; i<payloadMap.size(); i++){
            sb.append(payloadMap.get(i));
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

    private static Packet receivePacket(ByteBuffer buf, SocketAddress router) throws IOException {
        //read from buffer and create packet
        Packet resp = Packet.fromBuffer(buf);
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
        while(true) {
            server.listenAndServe(port);
        }
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