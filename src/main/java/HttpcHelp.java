public class HttpcHelp {

    public void printHelp(){
        System.out.println("httpc is a curl-like application but supports HTTP protocol only.");
        System.out.println("Usage:");
        System.out.println("\thttpc command [arguments]");
        System.out.println("The commands are:");
        System.out.println("\tget executes a HTTP GET request and prints the response.");
        System.out.println("\tpost executes a HTTP POST request and prints the response.");
        System.out.println("\thelp prints this screen.");
        System.out.println("Use \"httpc help [command]\" for more information about a command.");
    }

    public void printHelpGet(){
//        System.out.println("httpc help get");
        System.out.println("usage: httpc get [-v] [-h key:value] URL");
        System.out.println("Get executes a HTTP GET request for a given URL.");
        System.out.println("\t-v Prints the detail of the response such as protocol, status, and headers.");
        System.out.println("\t-h key:value Associates headers to HTTP Request with the format 'key:value'.");
    }

    public void printHelpPost(){
        System.out.println("usage: httpc post [-v] [-h key:value] [-d inline-data] [-f file] URL");
        System.out.println("Post executes a HTTP POST request for a given URL with inline data or from file.");
        System.out.println("\t-v Prints the detail of the response such as protocol, status, and headers.");
        System.out.println("\t-h key:value Associates headers to HTTP Request with the format 'key:value'.");
        System.out.println("\t-d string Associates an inline data to the body HTTP POST request.");
        System.out.println("\t-f file Associates the content of a file to the body HTTP POST request.");
        System.out.println("Either [-d] or [-f] can be used but not both.");
    }

    public void printHelpError(){
        System.out.println("The commands are:");
        System.out.println("\tget executes a HTTP GET request and prints the response.");
        System.out.println("\tpost executes a HTTP POST request and prints the response.");
        System.out.println("\thelp prints this screen.");
        System.out.println("Use \"httpc help [command]\" for more information about a command.");
    }

}
