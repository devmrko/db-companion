package com.dbcompanion.service;

import com.dbcompanion.model.OrdsApiTest.*;
import com.dbcompanion.model.OrdsManagement.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Resolves only the selected, registered handler; raw URLs never arrive from the browser. */
public final class OrdsApiRoute {
    private OrdsApiRoute() {}
    public record Route(String method,String path,List<String> parameters) {}
    private static Map<String,String> one(List<Map<String,String>> rows,String field,String value){
        var found=rows.stream().filter(r->Objects.equals(r.get(field),value)).toList();
        require(found.size()==1,"test.handlerMissing");return found.getFirst();
    }
    public static Route resolve(Selection selection,Snapshot snapshot){
        require("AVAILABLE".equals(snapshot.status()),snapshot.status());
        require(Objects.equals(selection.revision(),snapshot.revision()),"stale");
        require(snapshot.rows("SCHEMAS").size()==1,"test.handlerMissing");var schema=snapshot.rows("SCHEMAS").getFirst();
        var module=one(snapshot.rows("MODULES"),"NAME",selection.module());
        var template=one(snapshot.rows("TEMPLATES").stream().filter(r->Objects.equals(r.get("MODULE_ID"),module.get("ID"))).toList(),"URI_TEMPLATE",selection.pattern());
        var handler=one(snapshot.rows("HANDLERS").stream().filter(r->Objects.equals(r.get("TEMPLATE_ID"),template.get("ID"))).toList(),"METHOD",selection.method());
        require("ENABLED".equals(schema.get("STATUS"))&&"PUBLISHED".equals(module.get("STATUS")),"test.notPublished");
        require("BASE_PATH".equals(schema.get("TYPE")),"test.routeUnsupported");
        String method=handler.get("METHOD");require(Set.of("GET","HEAD","OPTIONS","POST","PUT","PATCH","DELETE").contains(method),"test.routeUnsupported");
        String root=Objects.toString(schema.get("PATTERN"),"");require(root.matches("[A-Za-z0-9][A-Za-z0-9._~-]*")&&!Set.of(".","..").contains(root),"test.routeUnsupported");
        String prefix=Objects.toString(module.get("URI_PREFIX"),""),pattern=Objects.toString(template.get("URI_TEMPLATE"),"");
        require(!prefix.isEmpty()&&prefix.endsWith("/")&&!pattern.isEmpty(),"test.routeUnsupported");
        String path=root+"/"+prefix.replaceFirst("^/","")+pattern.replaceFirst("^/","");
        var names=new ArrayList<String>();
        for(String part:path.split("/",-1)){
            if(part.startsWith(":")){require(part.matches(":[A-Za-z][A-Za-z0-9_]*")&&!names.contains(part.substring(1)),"test.routeUnsupported");names.add(part.substring(1));}
            else require(part.matches("[\\p{L}\\p{N} _~.-]*")&&!Set.of(".","..").contains(part),"test.routeUnsupported");
        }
        require(!path.contains("//")&&path.length()<=6000,"test.routeUnsupported");return new Route(method,path,List.copyOf(names));
    }
    public static URI uri(URI base,Route route,Map<String,String> values,List<Pair> query){
        require(values!=null&&values.keySet().equals(new HashSet<>(route.parameters())),"test.pathInvalid");
        var parts=new ArrayList<String>();
        for(String segment:route.path().split("/",-1)){
            String value=segment.startsWith(":")?values.get(segment.substring(1)):segment;
            require(value!=null&&value.length()<=2000&&(!segment.startsWith(":")||!value.isBlank())&&!Set.of(".","..").contains(value)
                    &&value.chars().noneMatch(c->Character.isISOControl(c)||"/\\%?#;".indexOf(c)>=0),"test.pathInvalid");parts.add(encode(value));
        }
        require(query!=null&&query.size()<=32,"test.requestInvalid");var pairs=new ArrayList<String>();
        for(Pair pair:query){require(pair!=null&&pair.name()!=null&&!pair.name().isBlank()&&pair.name().length()<=256&&pair.value()!=null&&pair.value().length()<=4000,"test.requestInvalid");pairs.add(encode(pair.name())+"="+encode(pair.value()));}
        String address=base.toASCIIString()+String.join("/",parts)+(pairs.isEmpty()?"":"?"+String.join("&",pairs));require(address.length()<=16384,"test.requestInvalid");
        URI uri=URI.create(address);require(Objects.equals(base.getRawAuthority(),uri.getRawAuthority())&&uri.getRawPath().startsWith(base.getRawPath())&&uri.normalize().equals(uri),"test.pathInvalid");return uri;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    static void require(boolean condition,String code){if(!condition)throw new Failure(400,code);}
}
