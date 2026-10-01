package com.dbcompanion.service;

import com.dbcompanion.model.OrdsApiTest.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.service.OrdsApiRoute.require;

@Component
public class OrdsApiTransport {
    public static final int RESPONSE_LIMIT=1_048_576;
    public static final int BODY_LIMIT=262_144;
    private static final Set<String> FORBIDDEN=Set.of("host","authorization","proxy-authorization","cookie","connection","content-length","transfer-encoding","expect","upgrade","te","trailer","accept-encoding");
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    public static boolean readMethod(String method){return Set.of("GET","HEAD","OPTIONS").contains(method);}
    public static Map<String,String> headers(Request input){
        require(input.headers()!=null&&input.headers().size()<=32,"test.requestInvalid");var result=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
        result.put("Accept","application/json");result.put("Accept-Encoding","identity");
        var seen=new HashSet<String>();
        for(Pair pair:input.headers()){
            require(pair!=null&&pair.name()!=null&&pair.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")&&pair.value()!=null&&pair.value().length()<=8192
                &&pair.value().chars().noneMatch(Character::isISOControl),"test.requestInvalid");
            String name=pair.name().toLowerCase(Locale.ROOT);require(!FORBIDDEN.contains(name)&&seen.add(name),"test.headerInvalid");result.put(pair.name(),pair.value());
        }
        Auth auth=input.auth();require(auth!=null&&Set.of("NONE","BEARER","BASIC").contains(Objects.toString(auth.type(),"")),"test.requestInvalid");
        if(!"NONE".equals(auth.type())){
            String secret=auth.secret();require(secret!=null&&!secret.isBlank()&&secret.length()<=8192&&secret.chars().noneMatch(Character::isISOControl),"test.authInvalid");
            if("BEARER".equals(auth.type())){require(secret.matches("[A-Za-z0-9._~+/-]+=*"),"test.authInvalid");result.put("Authorization","Bearer "+secret);}
            else{String username=auth.username();require(username!=null&&!username.isBlank()&&username.length()<=256&&!username.contains(":")&&username.chars().noneMatch(Character::isISOControl),"test.authInvalid");result.put("Authorization","Basic "+Base64.getEncoder().encodeToString((username+":"+secret).getBytes(StandardCharsets.UTF_8)));}
        }
        require(result.entrySet().stream().mapToInt(e->e.getKey().length()+e.getValue().length()).sum()<=32768,"test.requestInvalid");return result;
    }
    public static byte[] body(String method,Request input){
        require(input.timeoutSeconds()>=1&&input.timeoutSeconds()<=120,"test.requestInvalid");
        require(readMethod(method)||input.confirmed(),"test.confirmRequired");String body=Objects.toString(input.body(),"");
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);require(bytes.length<=BODY_LIMIT&&(!readMethod(method)||body.isEmpty()),"test.requestInvalid");
        if(!body.isBlank())try{JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(body);}catch(RuntimeException ex){throw new com.dbcompanion.model.OrdsManagement.Failure(400,"test.jsonInvalid");}
        return bytes;
    }
    public Result execute(URI uri,String method,Request input){
        byte[] body=body(method,input);var headers=headers(input);
        if(body.length>0)headers.putIfAbsent("Content-Type","application/json; charset=utf-8");
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(input.timeoutSeconds())).method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        try{headers.forEach(builder::header);}catch(IllegalArgumentException ex){throw new com.dbcompanion.model.OrdsManagement.Failure(400,"test.headerInvalid");}
        long start=System.nanoTime();CompletableFuture<HttpResponse<Payload>> pending=client.sendAsync(builder.build(),info->new LimitedBody(RESPONSE_LIMIT));
        try{
            var response=pending.get(input.timeoutSeconds(),TimeUnit.SECONDS);var data=response.body();var visible=new TreeMap<String,List<String>>();
            response.headers().map().forEach((key,values)->visible.put(key,sensitive(key)?List.of("[redacted]"):values));
            String content=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT),encoding=response.headers().firstValue("Content-Encoding").orElse("identity");
            boolean binary=!encoding.equalsIgnoreCase("identity")||!content.isEmpty()&&!content.startsWith("text/")&&!content.contains("json")&&!content.contains("xml")&&!content.contains("javascript");
            return new Result(response.statusCode(),elapsed(start),visible,binary?"":new String(data.bytes(),charset(content)),data.truncated(),binary,"");
        }catch(InterruptedException ex){Thread.currentThread().interrupt();return failure(start,"test.interrupted");}
        catch(TimeoutException ex){return failure(start,"test.timeout");}
        catch(ExecutionException ex){return failure(start,ex.getCause() instanceof HttpTimeoutException?"test.timeout":"test.network");}
        finally{if(!pending.isDone())pending.cancel(true);}
    }
    private static boolean sensitive(String key){return key.equalsIgnoreCase("set-cookie")||key.equalsIgnoreCase("authorization")||key.toLowerCase(Locale.ROOT).matches(".*(token|secret|api-key|apikey).*");}
    private static Charset charset(String type){var matcher=java.util.regex.Pattern.compile("charset=\"?([a-z0-9_-]+)").matcher(type);if(matcher.find())try{return Charset.forName(matcher.group(1));}catch(IllegalArgumentException ignored){return StandardCharsets.UTF_8;}return StandardCharsets.UTF_8;}
    private static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    private static Result failure(long start,String code){return new Result(0,elapsed(start),Map.of(),"",false,false,code);}
    record Payload(byte[] bytes,boolean truncated) {}
    static final class LimitedBody implements HttpResponse.BodySubscriber<Payload> {
        private final int limit;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final CompletableFuture<Payload> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        @Override public CompletionStage<Payload> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> chunks){
            for(ByteBuffer chunk:chunks){int count=Math.min(chunk.remaining(),limit+1-bytes.size());byte[] part=new byte[count];chunk.get(part);bytes.writeBytes(part);if(bytes.size()>limit){subscription.cancel();result.complete(new Payload(Arrays.copyOf(bytes.toByteArray(),limit),true));return;}}
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(new Payload(bytes.toByteArray(),false));}
    }
}
