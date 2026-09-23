package com.dbcompanion.repository;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.AiCreation.*;
import java.io.StringReader;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AiCreationRepository {
    private final JdbcTemplate read,write;
    private final ProfileHistoryRepository profiles;
    private final ProfileEditRepository profileChoices;
    private final AgentCatalogRepository catalog;
    private final TeamEditRepository teams;
    private final AgentObjectEditRepository objects;
    private final AiFeedbackRepository feedback;
    public AiCreationRepository(JdbcTemplate jdbc,ProfileHistoryRepository profiles,ProfileEditRepository profileChoices,
            AgentCatalogRepository catalog,TeamEditRepository teams,AgentObjectEditRepository objects,AiFeedbackRepository feedback){
        this.read=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));read.setQueryTimeout(10);
        this.write=new JdbcTemplate(jdbc.getDataSource());write.setQueryTimeout(60);
        this.profiles=profiles;this.profileChoices=profileChoices;this.catalog=catalog;this.teams=teams;this.objects=objects;this.feedback=feedback;
    }
    public Map<String,Object> snapshot(String schema,Kind kind,String name){
        if(kind==Kind.PROFILE)return profiles.currentSnapshot(schema,name,true);
        var scope=new AgentCatalog.Scope(schema,true);var type=AgentCatalog.Kind.valueOf(kind.name());var items=catalog.items(scope,type,List.of(name));
        if(items.isEmpty())return Map.of("exists",false);
        var item=items.getFirst();var fields=new LinkedHashMap<String,Object>();fields.put("ID",item.id());fields.put("NAME",item.name());fields.put("STATUS",item.status());fields.put("DESCRIPTION",item.description());fields.put("CREATED",item.created());fields.put("MODIFIED",item.modified());
        var attrs=new TreeMap<String,String>();catalog.attributes(scope,type,items).forEach(a->attrs.put(a.name(),a.value()));
        return Map.of("exists",true,"object",fields,"attributes",attrs);
    }
    public List<String> credentials(){return profileChoices.credentials();}
    public Map<String,List<String>> references(Kind kind){
        if(kind==Kind.PROFILE)return Map.of();var refs=new TreeMap<String,List<String>>();
        refs.put("profile_name",objects.profiles());
        for(var k:List.of(AgentCatalog.Kind.AGENT,AgentCatalog.Kind.TASK,AgentCatalog.Kind.TOOL))refs.put(k.name(),teams.choices(k));return refs;
    }
    public String owner(String schema,Kind kind){
        String owner=kind==Kind.PROFILE?profiles.packageOwner(schema):teams.packageOwner(schema);
        String pkg=kind==Kind.PROFILE?"DBMS_CLOUD_AI":"DBMS_CLOUD_AI_AGENT";
        var args=read.query("SELECT SUBPROGRAM_ID,ARGUMENT_NAME,DATA_TYPE,IN_OUT FROM SYS.ALL_ARGUMENTS WHERE OWNER=? AND PACKAGE_NAME=? AND OBJECT_NAME=? AND DATA_LEVEL=0",
                (r,i)->new Argument(r.getInt(1),r.getString(2),r.getString(3),r.getString(4)),owner,pkg,"CREATE_"+kind.name());
        if(!compatible(kind,args))throw AiCreation.error(409,"apiUnsupported");return owner;
    }
    public record Argument(int subprogram,String name,String type,String mode) {}
    public String feedbackOwner(String schema){
        String owner=profiles.packageOwner(schema);
        var args=read.query("SELECT SUBPROGRAM_ID,ARGUMENT_NAME,DATA_TYPE,IN_OUT FROM SYS.ALL_ARGUMENTS WHERE OWNER=? AND PACKAGE_NAME='DBMS_CLOUD_AI' AND OBJECT_NAME='FEEDBACK' AND DATA_LEVEL=0",(r,i)->new Argument(r.getInt(1),r.getString(2),r.getString(3),r.getString(4)),owner);
        if(!feedbackCompatible(args))throw AiCreation.error(409,"feedbackApiUnsupported");return owner;
    }
    public static boolean feedbackCompatible(List<Argument> args){return signature(args,Map.of("PROFILE_NAME","VARCHAR2","SQL_TEXT","CLOB","FEEDBACK_TYPE","VARCHAR2","RESPONSE","CLOB","FEEDBACK_CONTENT","CLOB","OPERATION","VARCHAR2"));}
    public static boolean compatible(Kind kind,List<Argument> args){
        var expected=Map.of(kind.name()+"_NAME","VARCHAR2","ATTRIBUTES","CLOB","STATUS","VARCHAR2","DESCRIPTION","CLOB");
        return signature(args,expected);
    }
    private static boolean signature(List<Argument> args,Map<String,String> expected){var groups=new HashMap<Integer,List<Argument>>();args.forEach(a->groups.computeIfAbsent(a.subprogram(),k->new ArrayList<>()).add(a));return groups.values().stream().anyMatch(g->g.size()==expected.size()&&g.stream().allMatch(a->"IN".equals(a.mode())&&Objects.equals(expected.get(a.name()),a.type()))&&g.stream().map(Argument::name).distinct().count()==expected.size());}
    public void validateReferences(Input in,JsonMapper json){
        var n=json.readTree(in.attributes());
        if(in.kind()==Kind.PROFILE){if(n.has("credential_name")&&(!n.get("credential_name").isString()||!profileChoices.credentialAvailable(n.get("credential_name").stringValue())))throw AiCreation.error(409,"credentialUnavailable");return;}
        var attrs=n.properties().stream().map(e->new AgentCatalog.Attribute(in.name(),e.getKey(),AiCreation.value(e.getValue()),null)).toList();
        var component=new AgentCatalog.Component(in.name(),null,attrs);
        if(in.kind()==Kind.TEAM){teams.validateReferences(new TeamEdit.Target(in.schema(),in.name(),"agents"),component,AiCreation.value(n.get("agents")),json);return;}
        var kind=AgentCatalog.Kind.valueOf(in.kind().name());
        for(var e:n.properties())if(!e.getKey().equals("supervisor"))objects.validateReferences(new AgentObjectEdit.Target(in.schema(),kind,in.name(),e.getKey()),component,AiCreation.value(e.getValue()),json);
    }
    public static String createSql(String owner,Kind kind){
        String pkg=kind==Kind.PROFILE?"DBMS_CLOUD_AI":"DBMS_CLOUD_AI_AGENT";
        return "DECLARE a CLOB := ?; d CLOB := ?; BEGIN "+ProfileHistorySql.object(owner,pkg)+".CREATE_"+kind.name()+"("+kind.name().toLowerCase(Locale.ROOT)+"_name => ?, attributes => a, status => ?, description => d); END;";
    }
    public void create(String owner,Input in){write.update(createSql(owner,in.kind()),s->{s.setClob(1,new StringReader(in.attributes()),in.attributes().length());s.setClob(2,new StringReader(in.description()),in.description().length());s.setString(3,in.name());s.setString(4,in.status());});}
    public List<Feedback> feedback(String schema,String name){
        if(!feedback.tableExists(schema,name))return List.of();
        var batch=new AiCreation.FeedbackBatch();
        return read.query(feedbackSelectSql(schema,name),(r,i)->{
            if(r.getLong(1)>AiCreation.MAX_BYTES)throw AiCreation.error(413,"copyLimit");
            var row=new Feedback(r.getString(2),bounded(r.getCharacterStream(3)));batch.add(row);return row;
        });
    }
    public static String feedbackSelectSql(String schema,String name){
        return "SELECT DBMS_LOB.GETLENGTH(CONTENT),CONTENT,JSON_SERIALIZE(ATTRIBUTES RETURNING CLOB) FROM "
                +ProfileHistorySql.object(schema,AiFeedback.tableName(name))+" ORDER BY ROWID";
    }
    public List<Feedback> feedbackKey(String schema,String profile,String sqlText){
        if(!feedback.tableExists(schema,profile))return List.of();
        return read.query("SELECT CONTENT,JSON_SERIALIZE(ATTRIBUTES RETURNING CLOB) FROM "+ProfileHistorySql.object(schema,AiFeedback.tableName(profile))
                +" WHERE DBMS_LOB.COMPARE(JSON_VALUE(ATTRIBUTES,'$.sql_text' RETURNING CLOB ERROR ON ERROR NULL ON EMPTY),?)=0 FETCH FIRST 3 ROWS ONLY",
                s->s.setClob(1,new StringReader(sqlText),sqlText.length()),(r,i)->new Feedback(bounded(r.getCharacterStream(1)),bounded(r.getCharacterStream(2))));
    }
    private static String bounded(java.io.Reader reader) throws java.sql.SQLException {
        if(reader==null)throw AiCreation.error(409,"copyUnsupported");
        try(reader){var text=new StringBuilder();char[] buf=new char[4096];int n;while((n=reader.read(buf))!=-1){text.append(buf,0,n);if(text.length()>AiCreation.MAX_BYTES)throw AiCreation.error(413,"copyLimit");}return text.toString();}catch(java.io.IOException ex){throw new java.sql.SQLException("Feedback CLOB read failed",ex);}
    }
    public static String feedbackSql(String owner){return "DECLARE q CLOB := ?; r CLOB := ?; c CLOB := ?; BEGIN "+ProfileHistorySql.object(owner,"DBMS_CLOUD_AI")+".FEEDBACK(profile_name => ?, sql_text => q, feedback_type => 'negative', response => r, feedback_content => c, operation => 'add'); END;";}
    public void addFeedback(String owner,String profile,Feedback row,JsonMapper json){
        var n=AiCreation.feedback(row,json);write.update(feedbackSql(owner),s->{
            String q=n.get("sql_text").stringValue(),r=n.get("response").stringValue(),c=n.hasNonNull("feedback_content")?n.get("feedback_content").stringValue():null;
            s.setClob(1,new StringReader(q),q.length());s.setClob(2,new StringReader(r),r.length());
            if(c==null)s.setNull(3,java.sql.Types.CLOB);else s.setClob(3,new StringReader(c),c.length());s.setString(4,profile);
        });
    }
}
