package ServerFromA2;

import clientSide.RequestType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class Httpfs {
    private static ServerSocket serverSocket;
    private static String httpVersion;
    private static String filePath;
    private static String pathToMainDirectory = "src/documents";
    private static String requestSpecification = "";
    private static boolean debugging;
    private static String headers = "";
    private static String data = "";
    private static RequestType requestType;
    private static int port = 8080;


    private static ServerSocket initializeServer(int portNumber) {
        if (portNumber < 0 && portNumber > 65535) {
            System.out.println("Invalid port number, starting server on default port: 8080");
            portNumber = 8080;
        }
        try {
            serverSocket = new ServerSocket(portNumber);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return serverSocket;
    }

    private static String RequestToString(BufferedReader requestReader) throws IOException {
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

    public static void main(String[] args) throws IOException {

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("help")) {
                System.out.println("usage: httpfs [-v] [-p PORT] [-d PATH-TO-DIR]\n" +
                        "-v Prints debugging messages.\n" +
                        "-p Specifies the port number that the server will listen and serve at.\n" +
                        "\tDefault is 8080.\n" +
                        "-d Specifies the directory that the server will use to read/write requested files." +
                        "Default is the current directory when launching the application.");
                System.exit(0);
            } else {
                for (int i = 0; i < args.length; i++) {
                    parseCommand(i, args);
                }
            }
        }

        ServerSocket serverSocket = initializeServer(port);
        System.out.println("Waiting for client on port: " + port + "...");

        Socket client = null;
        BufferedReader requestReader = null;
        PrintWriter responseWriter = null;

        while (true) {
            try {
                client = serverSocket.accept();
                requestReader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                responseWriter = new PrintWriter(client.getOutputStream());
                String request = RequestToString(requestReader);

                String response = createResponse(request);
                System.out.println(response);
                responseWriter.print(response);
                responseWriter.flush();
                client.close();

                resetVars();

            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e){
                e.printStackTrace();
                System.out.println("Something went wrong, try again");
                responseWriter.print(requestSpecification + httpVersion + " " + Status.BAD_REQUEST.toString() + "\r\n" + headers + "\r\n");
                client.close();
                continue;
            }
        }
    }

    private static void resetVars() {
        serverSocket = null;
        httpVersion = "";
        filePath = "";
        requestSpecification = "";
        headers = "";
        data = "";
        requestType = null;
        port = 8080;
    }

    private static void parseCommand(int i, String[] arr) {
        if (arr[i].equalsIgnoreCase("-v")) {
            debugging = true;
        } else if (arr[i].equalsIgnoreCase("-p")) {
            port = Integer.valueOf(arr[i + 1]);
        } else if (arr[i].equalsIgnoreCase("-d")) {
            pathToMainDirectory = arr[i + 1];
        }
    }
}
