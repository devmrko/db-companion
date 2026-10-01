package com.dbcompanion.service;

import com.dbcompanion.model.OrdsManagement.Failure;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Destinations are configured by the application operator, never by a request. */
@Component
public final class OrdsApiTargets {
    private final Map<String,URI> targets;
    public OrdsApiTargets(@Value("${app.ords.test-endpoints:}") String json){
        var result=new HashMap<String,URI>();
        if(json!=null&&!json.isBlank())try{
            if(json.length()>65536)throw new IllegalArgumentException();
            var node=new JsonMapper().readTree(json);
            if(!node.isObject()||node.size()>32)throw new IllegalArgumentException();
            for(var entry:node.properties()){
                if(entry.getKey().isBlank()||!entry.getValue().isString())throw new IllegalArgumentException();
                URI uri=validate(entry.getValue().stringValue());result.put(entry.getKey(),uri);
            }
        }catch(RuntimeException ex){throw new IllegalArgumentException("Invalid ORDS_TEST_ENDPOINTS configuration");}
        targets=Map.copyOf(result);
    }
    public URI resolve(String database){var uri=targets.get(database);if(uri==null)throw new Failure(409,"test.endpointMissing");return uri;}
    public static URI validate(String value){
        URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
            ||uri.getPort()==0||uri.getPort()>65535||!uri.getRawPath().endsWith("/")||!uri.getRawPath().matches("/[A-Za-z0-9_~/.-]*")
            ||!uri.normalize().equals(uri)||uri.getHost().equalsIgnoreCase("localhost")||uri.getHost().contains(":")
            ||uri.getHost().matches("[0-9.]+")||uri.getHost().endsWith(".localhost"))throw new IllegalArgumentException();
        return uri;
    }
}
