package com.chk.agentbrowser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Local wire-format tests only. None of these fixtures is evidence of a Tor connection. */
@RunWith(AndroidJUnit4.class)
public final class TorHttpFramingTest {
    @Test public void hostnameIsSentToConnectWithoutLocalDnsAndChunkedBodyIsDecoded()throws Exception{
        AtomicReference<String> connect=new AtomicReference<>(),request=new AtomicReference<>();
        TorHttp.Reply reply=fixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n",100,connect,request,false);
        assertEquals("CONNECT deliberately-unresolvable.invalid:80 HTTP/1.0",connect.get());assertEquals("GET /proof?q=1 HTTP/1.1",request.get());assertEquals("abcde",reply.body);assertEquals(200,reply.code);
    }
    @Test public void truncatedAndOversizedBodiesCannotBeCompleted()throws Exception{
        for(String response:new String[]{"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort","HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nfffffff\r\n"}){
            try{fixture(response,50,new AtomicReference<>(),new AtomicReference<>(),false);fail("Accepted truncated or unbounded body");}catch(IOException expected){}
        }
    }
    @Test public void httpsNeverFallsBackToPlaintextWhenTlsFails()throws Exception{
        try{fixture("plaintext is not TLS",100,new AtomicReference<>(),new AtomicReference<>(),true);fail("TLS failure accepted");}catch(javax.net.ssl.SSLException expected){}
    }
    private TorHttp.Reply fixture(String response,int max,AtomicReference<String> connect,AtomicReference<String> request,boolean tls)throws Exception{
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){server.setSoTimeout(5000);AtomicReference<Throwable> error=new AtomicReference<>();
            Thread peer=new Thread(()->{try(Socket s=server.accept()){s.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));connect.set(in.readLine());String line;while((line=in.readLine())!=null&&!line.isEmpty()){}OutputStream out=s.getOutputStream();out.write("HTTP/1.0 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();if(!tls){request.set(in.readLine());while((line=in.readLine())!=null&&!line.isEmpty()){} }out.write(response.getBytes(StandardCharsets.US_ASCII));out.flush();}catch(Throwable e){error.set(e);}});peer.start();
            try{return TorHttp.get((tls?"https":"http")+"://deliberately-unresolvable.invalid/proof?q=1",server.getLocalPort(),max);}finally{peer.join(6000);if(error.get()!=null)throw new AssertionError(error.get());}
        }
    }
}
