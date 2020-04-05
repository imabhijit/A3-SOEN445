import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.ArrayList;


public class Httpc {
    static String host;
    static RequestType requestType;
    static String endpoint = "";
    static String header = "";
    static String data = "";
    static boolean verbose = false;
    static HttpcHelp helpWriter = new HttpcHelp();
    static int urlIndex = 1;
    static boolean outputToFile = false;
    static String filepath = "";

    public static void main(String[] args) {

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("help")) {
                if (args.length > 1) {
                    if (args[1].equalsIgnoreCase("get")) {
                        helpWriter.printHelpGet();
                    } else if (args[1].equalsIgnoreCase("post")) {
                        helpWriter.printHelpPost();
                    } else {
                        helpWriter.printHelpError();
                    }
                } else {
                    helpWriter.printHelp();
                }
            } else if (args[0].equalsIgnoreCase("get")) {
                requestType = RequestType.GET;
                processRequest(args);
                parseAndSendRequest(args);
            } else if (args[0].equalsIgnoreCase("post")) {
                requestType = RequestType.POST;
                processRequest(args);
                parseAndSendRequest(args);
            }
        }
    }

    public static void parseAndSendRequest(String[] args){
        try {
            URL url = new URL(args[urlIndex]);
            host = url.getHost();
            endpoint = url.getFile();
            UDPClient client = new UDPClient(host, (url.getPort()==-1) ? 80: url.getPort());
            client.setOutputToFile(outputToFile);
            client.setFilePath(filepath);
            client.sendRequest(requestType, endpoint, host, header, data, verbose);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void processRequest(String[] args) {
        urlIndex = 1;
        if (args.length > urlIndex) {
            if (args[urlIndex].equalsIgnoreCase("-v")) {
                verbose = true;
                urlIndex += 1;
            }
            if (args[urlIndex].equalsIgnoreCase("-h")) {
                if (args.length > urlIndex+1) {
                    header = args[urlIndex+1];
                    urlIndex += 2;
                } else {
                    invalidSyntax(requestType);
                }
            }
            if (args[urlIndex].equalsIgnoreCase("-d")) {
                if (args.length > urlIndex+1) {
                    data = args[urlIndex+1];
                    urlIndex += 2;
                } else {
                    invalidSyntax(requestType);
                }
            } else if(args[urlIndex].equalsIgnoreCase("-f")){
                if (args.length > urlIndex+1) {
                    data = convertFileToString(args[urlIndex+1]);
                    urlIndex += 2;
                } else {
                    invalidSyntax(requestType);
                }
            }
            if(urlIndex+1 < args.length && args[urlIndex + 1].equalsIgnoreCase("-o")){
                outputToFile = true;
                filepath = args[urlIndex + 2];
            }
        } else {
            invalidSyntax(requestType);
        }
    }

    public static void invalidSyntax(RequestType requestType) {
        System.out.println("Invalid Syntax!");
        if (requestType == RequestType.GET) helpWriter.printHelpGet();
        else if (requestType == RequestType.POST) helpWriter.printHelpPost();
        else helpWriter.printHelpError();

        System.exit(0);
    }

    public static String convertFileToString(String filepath){
        BufferedReader bufferedReader;
        StringBuilder str = new StringBuilder();
        try {
            bufferedReader = new BufferedReader(new FileReader(filepath));
            String line = bufferedReader.readLine();
            while (line != null) {
                str.append(line);
                line = bufferedReader.readLine();
            }
            bufferedReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return str.toString();
    }

}
