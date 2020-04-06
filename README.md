# A3-SOEN445
Assignment 3 for networking class, making a UDP client and server.

Our HTTP Client and HTTP File Manager from Assignments 1 and 2 that originally used
the TCP transport protocol have been modified to use UDP and implement the Selective-Repeat
ARQ technique. 

To complete this modification, in the HTTP Client 
1. Streams providing the connection between the HTTP Client and HTTP File Manager have been removed. 
    - It has been replaced by a String (i.e. data) that will be sent to the router.
2. Ability to build packets has been added.
    - The HTTP message is broken down into packets of 1024 bytes. 
    - 3 additional other "default" packets (syn, ack, fin) are created.
3. Ability to send request to router has been added.
    - A list of the packets that make up the HTTP message is passed to a method that will handle the
    transport of these packets and ensure that they are sent, following the Selective-Repeat ARQ logic.
    - The number of packets will also be passed.  
4. Selective-Repeat ARQ logic has been added
    - This has been added to ensure that the transported packets have been received. 
    - The three way handshake is performed.
        1. The client sends a SYN packet
        2. The client waits for SYN-ACK from server
            1. If the client receives SYN-ACK from server, the client sends an ACK
            2. If the client receives NAK from server, restart at step 1. 
            3. If the client does not receive anything from server and the timeout interval runs out, restart at step 1.    
        3. The three way handshake is done.
        
    - Once that is complete, the data packets are going to being sent.
    - The window size corresponds to numberOfPackets / 2 (or 1 if there is only 1 packet)
        1. All the packets in the window are sent
        2. The client waits for a response
        3. If the client receives ACK from server,
            1. If the packet is at the head of the window, this will shift the window and will concatenate response payload with other data (to eventually build it once all packets have been received) 
            2. If the client receives NAK from server, client sends the corresponding packet again.
            3. If the client does not receive anything from server and the timeout interval runs out, client sends the corresponding packet again.
            
            [Note: the timeout interval is calculated dynamically, explained below]
           
5. A timeout interval is added
    - Initially, estimatedRTT = 1000ms, sampleRTT = 0ms, devRTT = 0ms and timeoutInterval = 10000ms
    and calculated using the following: 
        - sampleRTT = timeAckReceived - timePacketSent
        - estimatedRTT = ((0.875 * estimatedRTT) + (0.125 * sampleRTT))
        - devRTT = ((0.75 * devRTT) + 0.25 * (Math.abs(sampleRTT - estimatedRTT)))
        - timeoutInterval = estimatedRTT + 4 * devRTT


### HOW TO USE (TESTS)

To execute a *GET* Request, the user must enter get 
httpc command [arguments]
The commands are:
get executes a HTTP GET request and prints the response.
                                                        post executes a HTTP POST request and prints the response.
                                                        help prints this screen.
                                                       Use "httpc help [command]" for more information about a comman

"get httpc is a curl-like application but supports HTTP protocol only.
                                                 d."
GET REQUEST: "


get http://localhost:8007/

get http://localhost:8007/..

get http://localhost:8087/hello.txt

get http://localhost:8007/myWebsite.html

get http://localhost:8007/images/fun.jpg

post -v -d "Hello world!" -H "Content-Length:12" http://localhost:8007/bob2.txt

post -v -d "Hello world!" -H "Content-Length:12" http://localhost:8007/hey/my/name/is/bob.txt

@forbidden
http://localhost:8007/hey/my/name/is/bob.txt
bob.txt -> properties->deny
http://localhost:8007/hey/my/name/is/bob.txt

@badRequest
post -v -d "Hello world!" -H "Content-Length:12" http://localhost:8007/