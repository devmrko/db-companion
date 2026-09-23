package com.dbcompanion.common.db;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Fail-closed presentation: paths can be credentials (for example OCI PAR URLs). */
public final class ExternalAddress {
    private ExternalAddress() {}
    private static final String MASK="[redacted]";
    private static final Pattern CONNECT=Pattern.compile("(?i)\\(\\s*(HOST|PORT|SERVICE_NAME|SID)\\s*=\\s*([A-Za-z0-9_.:$-]+)\\s*\\)");
    public static String location(String raw){
        if(raw==null||raw.isBlank())return "";
        String value=raw.strip();
        if(value.contains("://"))return endpoint(value);
        // Only uncomplicated local filenames are displayed; no parameters or path credentials.
        if(!value.matches("[A-Za-z0-9_./*?% -]+"))return MASK;
        String name=value.substring(value.lastIndexOf('/')+1);
        if(name.isBlank())return MASK;
        return (value.contains("/")?"…/":"")+name;
    }
    private static String endpoint(String value){
        try{
            URI uri=URI.create(value);String scheme=uri.getScheme(),host=uri.getHost();
            if(scheme==null||host==null||!Set.of("http","https","oci","s3","azure","abfs","abfss","hdfs","gs").contains(scheme.toLowerCase(Locale.ROOT)))return MASK;
            return scheme.toLowerCase(Locale.ROOT)+"://"+host+(uri.getPort()<0?"":":"+uri.getPort())+"/"+MASK;
        }catch(IllegalArgumentException ex){return MASK;}
    }
    public static String connection(String raw){
        if(raw==null||raw.isBlank())return "";
        String value=raw.strip();
        if(value.contains("://"))return endpoint(value);
        if(value.startsWith("(")){
            var fields=new LinkedHashSet<String>();var matcher=CONNECT.matcher(value);
            while(matcher.find())fields.add(matcher.group(1).toUpperCase(Locale.ROOT)+"="+matcher.group(2));
            return fields.isEmpty()?MASK:String.join(" · ",fields);
        }
        // Easy-connect host/port/service or a TNS alias, but never user/password@host or parameters.
        return value.matches("[A-Za-z0-9_.:$/-]+")?value:MASK;
    }
}
