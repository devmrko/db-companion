package com.dbcompanion.model;

import java.util.List;
import java.util.Map;

public final class OrdsApiTest {
    private OrdsApiTest() {}
    public record Selection(String schema,String module,String pattern,String method,String revision) {}
    public record Prepared(String token,String method,String baseUrl,String path,List<String> parameters,String expiresAt,int timeoutSeconds,int responseLimit) {}
    public record Pair(String name,String value) {}
    public record Auth(String type,String username,String secret) {
        @Override public String toString(){return "Auth[redacted]";}
    }
    public record Request(String token,Map<String,String> parameters,List<Pair> query,List<Pair> headers,String body,Auth auth,int timeoutSeconds,boolean confirmed) {
        @Override public String toString(){return "OrdsApiTest.Request[redacted]";}
    }
    public record Result(int status,long elapsedMillis,Map<String,List<String>> headers,String body,boolean truncated,boolean binary,String error) {}
}
