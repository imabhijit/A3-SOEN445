package ClientFromA1;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class Client {
    private Socket socket;
    private PrintWriter sender;
    private BufferedReader receiver;
    private Boolean outputToFile;
    private String filePath;

    private Client() {
        //do not allow creating of TCP client without any params;
    }

    public void setOutputToFile(Boolean outputToFile) {
        this.outputToFile = outputToFile;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Client(Socket socket, PrintWriter sender, BufferedReader receiver) {
        this.socket = socket;
        this.sender = sender;
        this.receiver = receiver;
    }

    public Client(String host, int port){
        try{
            this.socket = new Socket(host, port);
            this.sender = new PrintWriter(socket.getOutputStream(), true);
            this.receiver = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendRequest(RequestType requestType, String endpoint, String host, String header, String data, boolean verbose) throws IOException {
        sender.println(requestType+" "+endpoint+" HTTP/1.0");
        sender.println("Host: "+host);
        sender.println(header);

        if(requestType == RequestType.GET) sendGetRequest();
        if(requestType == RequestType.POST) sendPostRequest(header, data);

        printResponse(verbose);
        socket.close();
    }

    private void sendGetRequest() {
        sender.println("Connection: Close");
        sender.println();
    }

    private void sendPostRequest(String header, String data) {
        sender.println("Content-Length: "+data.length());
        sender.println();
        sender.println(data);
        sender.println("Connection: Close");
        sender.println();
    }

    private void printResponse(boolean verbose) throws IOException {
        StringBuilder sb = new StringBuilder();
        char character = (char) receiver.read();

        if(verbose == false){
            while(character != '{') {
                character = (char) receiver.read();
            }
        }

        sb.append(character);
        String line = receiver.readLine();
        while (line != null) {
            sb.append(line+"\n");
            line = receiver.readLine();
        }
        receiver.close();
        if(outputToFile){
            PrintWriter pw = new PrintWriter(filePath);
            pw.println(sb.toString());
            pw.close();
        }else{
            System.out.println(sb.toString());
        }

    }
}
