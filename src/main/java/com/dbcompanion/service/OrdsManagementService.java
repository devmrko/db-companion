package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.OrdsManagement;
import com.dbcompanion.model.OrdsManagement.*;
import com.dbcompanion.repository.OrdsManagementRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrdsManagementService {
    private record Pending(Preview preview, Instant expires) {}
    private final Map<PoolSession,Pending> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private final SessionDataSource source;
    private final OrdsManagementRepository repository;
    private final TransactionTemplate read,write;
    public OrdsManagementService(SessionDataSource source,OrdsManagementRepository repository) {
        this.source=source;this.repository=repository;
        var manager=new DataSourceTransactionManager(source);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(30);
        write=new TransactionTemplate(manager);write.setTimeout(60);write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    private void scope(PoolSession session,String schema) {
        var metadata=session.metadata();
        OrdsManagement.require(Objects.equals(schema,metadata.selectedSchema()),"stale");
        OrdsManagement.require(Objects.equals(schema,metadata.info().username()),"ownSchema");
    }
    private <T>T transaction(PoolSession session,boolean mutation,Supplier<T> action) {
        source.bind(session.pool(),session.metadata().info().username());
        try{return (mutation?write:read).execute(status->action.get());}finally{source.clear();}
    }
    public Snapshot list(PoolSession session,String schema) {
        synchronized(session){scope(session,schema);return transaction(session,false,repository::snapshot);}
    }
    public Preview preview(PoolSession session,Input input) {
        synchronized(session) {
            pending.remove(session);validate(input);scope(session,input.schema());
            Snapshot snapshot=transaction(session,false,repository::snapshot);
            var preview=plan(input,snapshot);
            pending.put(session,new Pending(preview,Instant.now().plusSeconds(300)));return preview;
        }
    }
    public Map<String,String> apply(PoolSession session,Apply apply) {
        synchronized(session) {
            scope(session,apply.schema());
            Pending saved=pending.remove(session); // single attempt, including ambiguous network/commit failures
            OrdsManagement.require(saved!=null&&saved.expires().isAfter(Instant.now())&&Objects.equals(saved.preview().token(),apply.token()),"expired");
            Preview preview=saved.preview();
            OrdsManagement.require(Objects.equals(preview.confirmation(),apply.confirmation()),"confirmationRequired");
            try {
                transaction(session,true,()->{
                    Snapshot current=repository.snapshot();
                    OrdsManagement.require(current.status().equals("AVAILABLE"),current.status());
                    OrdsManagement.require(current.revision().equals(preview.revision()),"stale");
                    // Rebuild checks against fresh data and installed APIs, not client-supplied SQL.
                    Preview checked=plan(preview.input(),current);
                    repository.execute(checked.statements());
                    verify(preview.input(),current,repository.snapshot());
                    return null;
                });
                return Map.of("status","APPLIED");
            } catch(Failure ex) {throw ex;}
            catch(RuntimeException ex) {throw new Failure(503,OrdsManagementRepository.errorKey(ex));}
        }
    }
    public static void validate(Input input) {
        if(input==null||input.kind()==null||input.action()==null||input.values()==null||input.values().size()>16)throw new Failure(400,"invalid");
        OrdsManagement.field(Map.of("schema",Objects.toString(input.schema(),"")),"schema",true,128);
        if(input.revision()==null||!input.revision().matches("[a-f0-9]{64}"))throw new Failure(400,"invalid");
        for(var entry:input.values().entrySet())OrdsManagement.field(input.values(),entry.getKey(),false,200_000);
        if(input.kind()!=Kind.SCHEMA)text(input.module(),255);
        if(input.kind()==Kind.TEMPLATE||input.kind()==Kind.HANDLER)text(input.pattern(),4000);
        if(input.kind()==Kind.HANDLER)choice(input.method(),Set.of("GET","POST","PUT","DELETE"));
        if(input.action()==Action.PUBLISH&&input.kind()!=Kind.MODULE)throw new Failure(400,"invalid");
        Set<String> allowed=input.action()==Action.DELETE?Set.of():input.action()==Action.PUBLISH?Set.of("status"):switch(input.kind()){
            case SCHEMA->Set.of("enabled","mappingType","mappingPattern","autoRestAuth");
            case MODULE->Set.of("basePath","itemsPerPage","status","comments");
            case TEMPLATE->Set.of("priority","etagType","etagQuery","comments");
            case HANDLER->Set.of("sourceType","source","itemsPerPage","mimesAllowed","comments");};
        if(!allowed.containsAll(input.values().keySet()))throw new Failure(400,"invalid");
    }
    private static void text(String value,int max){OrdsManagement.field(Map.of("v",Objects.toString(value,"")),"v",true,max);}
    private static void choice(String value,Set<String> values){if(value==null||!values.contains(value))throw new Failure(400,"invalid");}
    private static String number(Map<String,String> values,String field,boolean required) {
        String value=OrdsManagement.field(values,field,required,9);
        if(!value.isEmpty()&&!value.matches("0|[1-9][0-9]{0,6}"))throw new Failure(400,"invalid");return value.isEmpty()?null:value;
    }
    private static Map<String,String> one(List<Map<String,String>> rows,String field,String value) {
        return rows.stream().filter(row->Objects.equals(row.get(field),value)).findFirst().orElse(Map.of());
    }
    private static List<Map<String,String>> children(List<Map<String,String>> rows,String key,String id){return id==null?List.of():rows.stream().filter(row->Objects.equals(row.get(key),id)).toList();}
    private static Statement statement(String api,String args,String... values){return new Statement(api,"BEGIN ORDS_METADATA.ORDS."+api+"("+args+"); END;",Collections.unmodifiableList(Arrays.asList(values)));}
    public static Preview plan(Input input,Snapshot snapshot) {
        validate(input);OrdsManagement.require(snapshot.status().equals("AVAILABLE"),snapshot.status());
        OrdsManagement.require(snapshot.revision().equals(input.revision()),"stale");
        var module=one(snapshot.rows("MODULES"),"NAME",input.module());
        var templates=children(snapshot.rows("TEMPLATES"),"MODULE_ID",module.get("ID"));
        var template=one(templates,"URI_TEMPLATE",input.pattern());
        var handlers=children(snapshot.rows("HANDLERS"),"TEMPLATE_ID",template.get("ID"));
        var handler=one(handlers,"METHOD",input.method());
        Map<String,String> original=switch(input.kind()) {
            case SCHEMA->snapshot.rows("SCHEMAS").isEmpty()?Map.of():snapshot.rows("SCHEMAS").getFirst();
            case MODULE->module;case TEMPLATE->template;case HANDLER->handler;
        };
        OrdsManagement.require(input.action()==Action.CREATE?original.isEmpty():!original.isEmpty(),input.action()==Action.CREATE?"exists":"missing");
        if(input.kind()==Kind.TEMPLATE||input.kind()==Kind.HANDLER)OrdsManagement.require(!module.isEmpty(),"missing");
        if(input.kind()==Kind.HANDLER)OrdsManagement.require(!template.isEmpty(),"missing");
        var statements=new ArrayList<Statement>();var proposed=new TreeMap<String,String>(input.values());
        switch(input.kind()) {
            case SCHEMA->schema(input,statements);
            case MODULE->module(input,module,templates,statements);
            case TEMPLATE->template(input,handlers,statements);
            case HANDLER->handler(input,snapshot,handler,statements);
        }
        for(Statement sql:statements)OrdsManagement.require(snapshot.apis().contains(sql.api()),"UNSUPPORTED");
        Map<String,Integer> affected=impact(input,snapshot,module,template,handler);
        String confirmation=input.action()+" "+input.schema()+" / "+input.kind()+" / "+switch(input.kind()){
            case SCHEMA->input.schema();case MODULE->input.module();case TEMPLATE->input.module()+" / "+input.pattern();case HANDLER->input.module()+" / "+input.pattern()+" / "+input.method();};
        return new Preview(UUID.randomUUID().toString(),confirmation,snapshot.revision(),input,original,proposed,affected,List.copyOf(statements),input.action()==Action.DELETE);
    }
    private static void schema(Input in,List<Statement> out) {
        if(in.action()==Action.DELETE){out.add(new Statement("DROP_REST_FOR_SCHEMA","BEGIN ORDS_METADATA.ORDS.DROP_REST_FOR_SCHEMA; END;",List.of()));return;}
        var v=in.values();String enabled=OrdsManagement.field(v,"enabled",true,5);choice(enabled,Set.of("true","false"));
        String auth=OrdsManagement.field(v,"autoRestAuth",true,5);choice(auth,Set.of("true","false"));
        String type=OrdsManagement.field(v,"mappingType",true,10);choice(type,Set.of("BASE_PATH","BASE_URL"));
        String pattern=OrdsManagement.field(v,"mappingPattern",true,2000);
        if(type.equals("BASE_PATH")&&!pattern.matches("[A-Za-z0-9][A-Za-z0-9._~-]*"))throw new Failure(400,"invalid");
        if(type.equals("BASE_URL")&&!pattern.matches("https?://[^\\s?#]+"))throw new Failure(400,"invalid");
        out.add(statement("ENABLE_SCHEMA","p_enabled => (? = 'true'), p_schema => ?, p_url_mapping_type => ?, p_url_mapping_pattern => ?, p_auto_rest_auth => (? = 'true')",enabled,in.schema(),type,pattern,auth));
    }
    private static void module(Input in,Map<String,String> original,List<Map<String,String>> children,List<Statement> out) {
        if(in.action()==Action.DELETE){out.add(statement("DELETE_MODULE","p_module_name => ?",in.module()));return;}
        var v=in.values();String status=OrdsManagement.field(v,"status",true,20);choice(status,Set.of("NOT_PUBLISHED","PUBLISHED"));
        if(in.action()==Action.PUBLISH){out.add(statement("PUBLISH_MODULE","p_module_name => ?, p_status => ?",in.module(),status));return;}
        String path=OrdsManagement.field(v,"basePath",true,2000),page=number(v,"itemsPerPage",true),comments=OrdsManagement.field(v,"comments",false,4000);
        if(!path.endsWith("/")||path.contains("?")||path.contains("#")||path.contains("://")||path.chars().anyMatch(Character::isWhitespace))throw new Failure(400,"invalid");
        if(in.action()==Action.UPDATE&&(!children.isEmpty()||present(original,"PRE_HOOK")||present(original,"ORIGINS_ALLOWED"))) {
            // RENAME_MODULE preserves templates, handlers, parameters and privilege associations.
            OrdsManagement.require(Objects.equals(original.get("ITEMS_PER_PAGE"),page)&&Objects.equals(Objects.toString(original.get("COMMENTS"),""),comments),"moduleChildren");
            out.add(statement("RENAME_MODULE","p_module_name => ?, p_new_base_path => ?",in.module(),path));
            out.add(statement("PUBLISH_MODULE","p_module_name => ?, p_status => ?",in.module(),status));
        } else out.add(statement("DEFINE_MODULE","p_module_name => ?, p_base_path => ?, p_items_per_page => TO_NUMBER(?), p_status => ?, p_comments => ?",in.module(),path,page,status,comments));
    }
    private static void template(Input in,List<Map<String,String>> handlers,List<Statement> out) {
        if(in.action()==Action.DELETE){out.add(statement("DELETE_TEMPLATE","p_module_name => ?, p_uri_template => ?",in.module(),in.pattern()));return;}
        OrdsManagement.require(handlers.isEmpty(),"templateChildren");
        var v=in.values();String etag=OrdsManagement.field(v,"etagType",true,10);choice(etag,Set.of("HASH","NONE","QUERY"));
        String query=OrdsManagement.field(v,"etagQuery",etag.equals("QUERY"),4000);
        if(!etag.equals("QUERY")&&!query.isEmpty())throw new Failure(400,"invalid");
        String priority=number(v,"priority",true);if(Integer.parseInt(priority)>9)throw new Failure(400,"invalid");
        out.add(statement("DEFINE_TEMPLATE","p_module_name => ?, p_pattern => ?, p_priority => TO_NUMBER(?), p_etag_type => ?, p_etag_query => ?, p_comments => ?",in.module(),in.pattern(),priority,etag,query,OrdsManagement.field(v,"comments",false,4000)));
    }
    private static void handler(Input in,Snapshot snapshot,Map<String,String> old,List<Statement> out) {
        if(in.action()==Action.DELETE){out.add(statement("DELETE_HANDLER","p_module_name => ?, p_uri_template => ?, p_method => ?",in.module(),in.pattern(),in.method()));return;}
        var v=in.values();String type=OrdsManagement.field(v,"sourceType",true,80);
        choice(type,Set.of("json/collection","json/item","plsql/block","resource/lob","json/query","json/query;type=single","csv/query","json/feed"));
        if(in.method().equals("GET"))OrdsManagement.require(!type.equals("plsql/block"),"sourceType");
        else OrdsManagement.require(type.equals("plsql/block"),"sourceType");
        OrdsManagement.require(!present(old,"MLE_ENV_NAME"),"UNSUPPORTED");
        out.add(statement("DEFINE_HANDLER","p_module_name => ?, p_pattern => ?, p_method => ?, p_source_type => ?, p_source => ?, p_items_per_page => TO_NUMBER(?), p_mimes_allowed => ?, p_comments => ?",in.module(),in.pattern(),in.method(),type,OrdsManagement.field(v,"source",true,200_000),number(v,"itemsPerPage",false),OrdsManagement.field(v,"mimesAllowed",false,4000),OrdsManagement.field(v,"comments",false,4000)));
        for(var parameter:children(snapshot.rows("PARAMETERS"),"HANDLER_ID",old.get("ID"))){
            for(String field:List.of("NAME","BIND_VARIABLE_NAME","SOURCE_TYPE","PARAM_TYPE","ACCESS_METHOD","COMMENTS"))OrdsManagement.require(parameter.containsKey(field),"UNSUPPORTED");
            out.add(statement("DEFINE_PARAMETER","p_module_name => ?, p_pattern => ?, p_method => ?, p_name => ?, p_bind_variable_name => ?, p_source_type => ?, p_param_type => ?, p_access_method => ?, p_comments => ?",in.module(),in.pattern(),in.method(),parameter.get("NAME"),parameter.get("BIND_VARIABLE_NAME"),parameter.get("SOURCE_TYPE"),parameter.get("PARAM_TYPE"),parameter.get("ACCESS_METHOD"),parameter.get("COMMENTS")));
        }
    }
    private static Map<String,Integer> impact(Input in,Snapshot snapshot,Map<String,String> module,Map<String,String> template,Map<String,String> handler) {
        var templates=in.kind()==Kind.SCHEMA?snapshot.rows("TEMPLATES"):in.kind()==Kind.MODULE?children(snapshot.rows("TEMPLATES"),"MODULE_ID",module.get("ID")):template.isEmpty()?List.<Map<String,String>>of():List.of(template);
        Set<String> ids=new HashSet<>();templates.forEach(row->ids.add(row.get("ID")));
        var handlers=in.kind()==Kind.HANDLER?(handler.isEmpty()?List.<Map<String,String>>of():List.of(handler)):snapshot.rows("HANDLERS").stream().filter(row->ids.contains(row.get("TEMPLATE_ID"))).toList();
        Set<String> handlerIds=new HashSet<>();handlers.forEach(row->handlerIds.add(row.get("ID")));
        return Map.of("modules",in.kind()==Kind.SCHEMA?snapshot.rows("MODULES").size():module.isEmpty()?0:1,"templates",templates.size(),"handlers",handlers.size(),"parameters",(int)snapshot.rows("PARAMETERS").stream().filter(row->handlerIds.contains(row.get("HANDLER_ID"))).count());
    }
    private static boolean present(Map<String,String> row,String key){return row.get(key)!=null&&!row.get(key).isEmpty();}
    private static final Map<String,String> FIELDS=Map.ofEntries(Map.entry("mappingType","TYPE"),Map.entry("mappingPattern","PATTERN"),Map.entry("basePath","URI_PREFIX"),Map.entry("itemsPerPage","ITEMS_PER_PAGE"),Map.entry("status","STATUS"),Map.entry("comments","COMMENTS"),Map.entry("priority","PRIORITY"),Map.entry("etagType","ETAG_TYPE"),Map.entry("etagQuery","ETAG_QUERY"),Map.entry("sourceType","SOURCE_TYPE"),Map.entry("source","SOURCE"),Map.entry("mimesAllowed","MIMES_ALLOWED"),Map.entry("enabled","STATUS"),Map.entry("autoRestAuth","AUTO_REST_AUTH"));
    private static Map<String,String> stable(Map<String,String> row){
        var copy=new TreeMap<>(row);for(String field:List.of("ID","SCHEMA_ID","MODULE_ID","TEMPLATE_ID","HANDLER_ID","CREATED_ON","UPDATED_ON","CREATED_BY","UPDATED_BY"))copy.remove(field);return copy;
    }
    private static List<String> key(Snapshot snapshot,String table,Map<String,String> row){
        if(table.equals("SCHEMAS"))return List.of("SCHEMA");
        if(table.equals("MODULES"))return List.of("MODULE",Objects.toString(row.get("NAME"),""));
        if(table.equals("TEMPLATES")){var module=one(snapshot.rows("MODULES"),"ID",row.get("MODULE_ID"));return List.of("TEMPLATE",Objects.toString(module.get("NAME"),""),Objects.toString(row.get("URI_TEMPLATE"),""));}
        if(table.equals("HANDLERS")){var template=one(snapshot.rows("TEMPLATES"),"ID",row.get("TEMPLATE_ID"));var key=new ArrayList<>(key(snapshot,"TEMPLATES",template));key.set(0,"HANDLER");key.add(Objects.toString(row.get("METHOD"),""));return key;}
        var handler=one(snapshot.rows("HANDLERS"),"ID",row.get("HANDLER_ID"));var key=new ArrayList<>(key(snapshot,"HANDLERS",handler));key.set(0,"PARAMETER");key.add(Objects.toString(row.get("NAME"),""));return key;
    }
    private static Map<List<String>,Map<String,String>> logical(Snapshot snapshot){
        var result=new HashMap<List<String>,Map<String,String>>();snapshot.tables().forEach((table,rows)->rows.forEach(row->result.put(key(snapshot,table,row),stable(row))));return result;
    }
    private static List<String> target(Input input){return switch(input.kind()){
        case SCHEMA->List.of("SCHEMA");case MODULE->List.of("MODULE",input.module());case TEMPLATE->List.of("TEMPLATE",input.module(),input.pattern());case HANDLER->List.of("HANDLER",input.module(),input.pattern(),input.method());};}
    private static boolean deleted(Input input,List<String> key){
        if(input.kind()==Kind.SCHEMA)return true;
        if(key.size()<2||!key.get(1).equals(input.module()))return false;
        if(input.kind()==Kind.MODULE)return true;
        if(key.size()<3||!key.get(2).equals(input.pattern()))return false;
        return input.kind()==Kind.TEMPLATE||key.size()>=4&&key.get(3).equals(input.method());
    }
    public static void verify(Input input,Snapshot before,Snapshot after){
        OrdsManagement.require(after.status().equals("AVAILABLE"),"verifyFailed");
        var original=logical(before);var actual=logical(after);var key=target(input);
        if(input.action()==Action.DELETE){original.keySet().removeIf(k->deleted(input,k));OrdsManagement.require(original.equals(actual),"verifyFailed");return;}
        var result=actual.get(key);OrdsManagement.require(result!=null,"verifyFailed");
        var unchanged=new TreeMap<>(original.getOrDefault(key,Map.of()));
        for(var field:input.values().entrySet()){
            String column=FIELDS.get(field.getKey());if(column==null)throw new Failure(400,"invalid");
            String value=field.getValue();if(field.getKey().equals("enabled")||field.getKey().equals("autoRestAuth"))value=value.equals("true")?"ENABLED":"DISABLED";
            String stored=Objects.toString(result.get(column),"");value=Objects.toString(value,"");
            if(column.equals("URI_PREFIX")){stored=stored.replaceFirst("^/","");value=value.replaceFirst("^/","");}
            OrdsManagement.require(Objects.equals(stored,value),"verifyFailed");unchanged.remove(column);
        }
        // Unknown policy fields and handler parameters must survive. Audit IDs can change.
        unchanged.forEach((column,value)->OrdsManagement.require(Objects.equals(result.get(column),value),"verifyFailed"));
        original.remove(key);actual.remove(key);OrdsManagement.require(original.equals(actual),"verifyFailed");
    }
}
