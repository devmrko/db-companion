package com.dbcompanion;

import com.dbcompanion.model.OrdsApiTest.*;
import com.dbcompanion.service.OrdsApiTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.dbcompanion.OrdsApiTestTest.*;

/** Transport only, synthetic loopback HTTP fixture; production targets require configured HTTPS. */
class OrdsApiTransportTest {
    @Test void httpErrorsRedirectsHeadersAndBodiesAreReturnedWithoutFollowingOrForwardingCookies() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var transport=new OrdsApiTransport();
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","/target");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/target",e->{calls.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        server.createContext("/error",e->{assertThat(e.getRequestHeaders().getFirst("Cookie")).isNull();assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");byte[] body="{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.getResponseHeaders().add("Set-Cookie","private=value");e.sendResponseHeaders(401,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();var redirect=transport.execute(URI.create(base+"/redirect"),"GET",empty("t"));assertThat(redirect.status()).isEqualTo(302);assertThat(calls.get()).isZero();
            var response=transport.execute(URI.create(base+"/error"),"GET",request("t",Map.of(),List.of(),new Auth("BEARER","","synthetic-token"),"",false));assertThat(response.status()).isEqualTo(401);assertThat(response.error()).isEmpty();assertThat(response.body()).contains("denied");assertThat(response.headers().get("set-cookie")).containsExactly("[redacted]");
        }finally{server.stop(0);}
    }
    @Test void largeBodiesAreExplicitlyTruncatedAndSlowResponsesTimeOut() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newCachedThreadPool();server.setExecutor(executor);var calls=new AtomicInteger();
        server.createContext("/large",e->{byte[] body="x".repeat(OrdsApiTransport.RESPONSE_LIMIT+20).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","text/plain");e.sendResponseHeaders(200,body.length);try{e.getResponseBody().write(body);}finally{e.close();}});
        server.createContext("/slow",e->{calls.incrementAndGet();try{Thread.sleep(1700);e.sendResponseHeaders(200,-1);}catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{e.close();}});server.start();
        try{var transport=new OrdsApiTransport();String base="http://127.0.0.1:"+server.getAddress().getPort();var large=transport.execute(URI.create(base+"/large"),"GET",empty("t"));assertThat(large.status()).isEqualTo(200);assertThat(large.truncated()).isTrue();assertThat(large.body()).hasSize(OrdsApiTransport.RESPONSE_LIMIT);
            var slow=transport.execute(URI.create(base+"/slow"),"GET",new Request("t",Map.of(),List.of(),List.of(),"",new Auth("NONE","",""),1,false));assertThat(slow.status()).isZero();assertThat(slow.error()).isEqualTo("test.timeout");assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);executor.shutdownNow();}
    }
}
