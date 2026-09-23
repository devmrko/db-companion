package com.dbcompanion.common.db;

import java.net.URI;
import java.util.*;

/** Parses only connection-address fields. Never returns a raw descriptor or authentication fields. */
public final class LinkEndpoint {
    private LinkEndpoint() {}
    public record Info(List<String> endpoints,String service,String alias,String status) {
        public Info { endpoints=List.copyOf(endpoints); }
    }
    private record Part(String key,String value,List<Part> children) {}
    public static Info parse(String raw){
        if(raw==null||raw.isBlank())return new Info(List.of(),"","","UNAVAILABLE");
        String value=raw.strip();if(value.length()>32000)return new Info(List.of(),"","","UNRECOGNIZED");
        if(value.startsWith("("))return descriptor(value);
        if(value.matches("[A-Za-z0-9_$#.-]+"))return new Info(List.of(),"",value,"ALIAS");
        try{
            String uri=value.startsWith("//")?"tcp:"+value:value.contains("://")?value:"tcp://"+value;
            URI parsed=URI.create(uri);String host=parsed.getHost(),scheme=parsed.getScheme();
            if(!Set.of("tcp","tcps").contains(scheme.toLowerCase(Locale.ROOT))||!safeHost(host))return unavailable();
            String service=parsed.getPath();if(service!=null&&service.startsWith("/"))service=service.substring(1);
            if(!safeService(service))service="";
            int port=parsed.getPort();if(port>65535||port==0)return unavailable();
            return new Info(List.of(scheme.toLowerCase(Locale.ROOT)+"://"+host+(port<0?"":":"+port)),service,"","PARSED");
        }catch(IllegalArgumentException ex){return unavailable();}
    }
    private static Info unavailable(){return new Info(List.of(),"","","UNRECOGNIZED");}
    private static Info descriptor(String value){
        try{
            var parser=new Parser(value);Part root=parser.part(0);parser.space();if(parser.pos!=value.length())return unavailable();
            var endpoints=new LinkedHashSet<String>();var services=new LinkedHashSet<String>();
            inspect(root,endpoints,services);
            return new Info(List.copyOf(endpoints),String.join(" · ",services),"",endpoints.isEmpty()?"UNAVAILABLE":"PARSED");
        }catch(IllegalArgumentException ex){return unavailable();}
    }
    private static void inspect(Part node,Set<String> endpoints,Set<String> services){
        if(node.key().equals("ADDRESS")){
            String host=scalar(node,"HOST"),port=scalar(node,"PORT"),protocol=scalar(node,"PROTOCOL");
            if(!safeHost(host))return;
            if(!host.startsWith("[")&&host.contains(":"))host="["+host+"]";
            boolean validPort=port!=null&&port.matches("[0-9]{1,5}")&&Integer.parseInt(port)>0&&Integer.parseInt(port)<=65535;
            String prefix=protocol!=null&&Set.of("TCP","TCPS").contains(protocol.toUpperCase(Locale.ROOT))?protocol.toLowerCase(Locale.ROOT)+"://":"";
            endpoints.add(prefix+host+(validPort?":"+port:""));return;
        }
        if(node.key().equals("CONNECT_DATA")){
            for(String key:List.of("SERVICE_NAME","SID")){String value=scalar(node,key);if(safeService(value))services.add(value);}return;
        }
        // Do not traverse PASSWORD/SECURITY or arbitrary nested values as connection addresses.
        if(Set.of("DESCRIPTION","DESCRIPTION_LIST","ADDRESS_LIST").contains(node.key()))for(Part child:node.children())inspect(child,endpoints,services);
    }
    private static String scalar(Part node,String key){
        return node.children().stream().filter(p->p.key().equals(key)&&p.children().isEmpty()).map(Part::value).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static boolean safeHost(String host){return host!=null&&host.length()<=255&&host.matches("[A-Za-z0-9_.:%\\[\\]-]+");}
    private static boolean safeService(String value){return value!=null&&!value.isEmpty()&&value.length()<=256&&value.matches("[A-Za-z0-9_$#.-]+");}
    private static final class Parser {
        private final String text;private int pos;
        private Parser(String text){this.text=text;}
        private void space(){while(pos<text.length()&&Character.isWhitespace(text.charAt(pos)))pos++;}
        private void take(char c){space();if(pos>=text.length()||text.charAt(pos++)!=c)throw new IllegalArgumentException("Invalid descriptor");}
        private Part part(int depth){
            if(depth>32)throw new IllegalArgumentException("Descriptor nesting limit");take('(');space();int start=pos;
            while(pos<text.length()&&(Character.isLetterOrDigit(text.charAt(pos))||text.charAt(pos)=='_'))pos++;
            String key=text.substring(start,pos).toUpperCase(Locale.ROOT);if(key.isEmpty())throw new IllegalArgumentException("Missing key");take('=');space();
            var children=new ArrayList<Part>();String value=null;
            if(pos<text.length()&&text.charAt(pos)=='('){
                while(pos<text.length()&&text.charAt(pos)=='('){children.add(part(depth+1));space();}
            }else if(pos<text.length()&&(text.charAt(pos)=='\''||text.charAt(pos)=='"')){
                char quote=text.charAt(pos++);var out=new StringBuilder();boolean closed=false;
                while(pos<text.length()){
                    char c=text.charAt(pos++);
                    if(c=='\\'&&pos<text.length()){out.append(text.charAt(pos++));continue;}
                    if(c==quote){if(pos<text.length()&&text.charAt(pos)==quote){out.append(c);pos++;}else{closed=true;break;}}
                    else out.append(c);
                }
                if(!closed)throw new IllegalArgumentException("Unclosed value");value=out.toString();
            }else{
                start=pos;while(pos<text.length()&&text.charAt(pos)!=')'){if(text.charAt(pos)=='(')throw new IllegalArgumentException("Unexpected nested value");pos++;}
                value=text.substring(start,pos).strip();
            }
            take(')');return new Part(key,value,List.copyOf(children));
        }
    }
}
