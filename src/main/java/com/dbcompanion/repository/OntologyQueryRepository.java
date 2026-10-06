package com.dbcompanion.repository;

import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.*;
import com.dbcompanion.model.Ontology.*;
import com.dbcompanion.service.*;
import java.io.StringReader;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OntologyQueryRepository {
    private final JdbcTemplate jdbc;private final JsonMapper json;
    public OntologyQueryRepository(JdbcTemplate template,JsonMapper json){jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(15);this.json=json;}
    public void profileScope(String profile,String schema,List<Entry> entries){
        var values=jdbc.queryForList("SELECT ATTRIBUTE_VALUE FROM USER_CLOUD_AI_PROFILE_ATTRIBUTES WHERE PROFILE_NAME=? AND ATTRIBUTE_NAME='object_list'",String.class,profile);
        if(values.size()!=1)throw new Failure(409,"query.profileScope");
        if(!covers(values.getFirst(),schema,entries.stream().map(e->e.document().source().table()).toList(),json))throw new Failure(409,"query.profileScope");
    }
    public static boolean covers(String value,String schema,List<String> names,JsonMapper json){
        try{var items=json.readTree(value);if(items==null||!items.isArray())return false;var allowed=new HashSet<String>();boolean whole=false;
            for(var item:items){if(!item.isObject()||!item.path("owner").isString())return false;var owner=SqlObjectName.parts(item.path("owner").asString());if(owner.size()!=1)return false;
                if(!owner.getFirst().equals(schema))continue;if(!item.has("name")){whole=true;continue;}if(!item.path("name").isString())return false;var name=SqlObjectName.parts(item.path("name").asString());if(name.size()!=1)return false;allowed.add(name.getFirst());}
            return !names.isEmpty()&&(whole||allowed.containsAll(names));
        }catch(RuntimeException ex){return false;}
    }
    /** Local tables and validated projection-only views. Rechecked before generation and execution. */
    public void localTables(List<Entry> entries){
        var checked=new HashSet<LocalViewSql.Base>();for(var e:entries)localSource(new LocalViewSql.Base(e.document().source().schema(),e.document().source().table()),new HashSet<>(),checked,0);
    }
    /** Shared with bounded sampling; never bypasses view text or dependency validation. */
    public static void verifySampleSource(JdbcTemplate jdbc,String schema,String table){
        var verifier=new OntologyQueryRepository(jdbc,null);
        verifier.jdbc.setQueryTimeout(jdbc.getQueryTimeout());
        verifier.localSource(new LocalViewSql.Base(schema,table),new HashSet<>(),new HashSet<>(),0);
        verifier.ordinarySampleBase(new LocalViewSql.Base(schema,table),0);
    }
    private void ordinarySampleBase(LocalViewSql.Base object,int depth){
        if(depth>8)throw new Failure(422,"wizard.localOnly");
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL AND STATUS='VALID'",String.class,object.owner(),object.name());
        if(types.equals(List.of("VIEW"))){
            var text=jdbc.query("SELECT TEXT_LENGTH,TEXT FROM SYS.ALL_VIEWS WHERE OWNER=? AND VIEW_NAME=?",(r,n)->{if(r.getInt(1)>20000)throw new Failure(422,"wizard.localOnly");return r.getString(2);},object.owner(),object.name());
            if(text.size()!=1)throw new Failure(422,"wizard.localOnly");ordinarySampleBase(LocalViewSql.base(object.owner(),text.getFirst()),depth+1);
        }else{
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TABLES t WHERE t.OWNER=? AND t.TABLE_NAME=? AND t.TEMPORARY='N' AND t.NESTED='NO' AND t.SECONDARY='N' AND NOT EXISTS (SELECT 1 FROM SYS.ALL_MVIEWS m WHERE m.OWNER=t.OWNER AND m.MVIEW_NAME=t.TABLE_NAME)",Long.class,object.owner(),object.name());
            if(!types.equals(List.of("TABLE"))||count==null||count!=1)throw new Failure(422,"wizard.localOnly");
        }
    }
    private void localSource(LocalViewSql.Base object,Set<LocalViewSql.Base> visiting,Set<LocalViewSql.Base> checked,int depth){
        if(checked.contains(object))return;
        if(depth>8||checked.size()+visiting.size()>=32||!visiting.add(object))throw new Failure(409,"query.localTables");
        var types=jdbc.queryForList("SELECT OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL AND STATUS='VALID'",String.class,object.owner(),object.name());
        if(types.equals(List.of("TABLE"))){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_EXTERNAL_TABLES WHERE OWNER=? AND TABLE_NAME=?",Integer.class,object.owner(),object.name())!=0)throw new Failure(409,"query.localTables");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? AND VIRTUAL_COLUMN='YES'",Integer.class,object.owner(),object.name())!=0)throw new Failure(409,"query.localTables");
        }else if(types.equals(List.of("VIEW"))){
            var definitions=jdbc.query("SELECT TEXT_LENGTH,TEXT FROM SYS.ALL_VIEWS WHERE OWNER=? AND VIEW_NAME=?",(r,n)->{if(r.getInt(1)>20000)throw new Failure(409,"query.localTables");return r.getString(2);},object.owner(),object.name());
            if(definitions.size()!=1)throw new Failure(409,"query.localTables");
            var base=LocalViewSql.base(object.owner(),definitions.getFirst());
            var dependencies=jdbc.query("SELECT REFERENCED_OWNER,REFERENCED_NAME,REFERENCED_TYPE,REFERENCED_LINK_NAME FROM SYS.ALL_DEPENDENCIES WHERE OWNER=? AND NAME=? AND TYPE='VIEW'",(r,n)->{
                if(r.getString(4)!=null||!Set.of("TABLE","VIEW").contains(r.getString(3)))throw new Failure(409,"query.localTables");return new LocalViewSql.Base(r.getString(1),r.getString(2));},object.owner(),object.name());
            if(dependencies.size()!=1||!dependencies.getFirst().equals(base))throw new Failure(409,"query.localTables");
            localSource(base,visiting,checked,depth+1);
        }else throw new Failure(409,"query.localTables");
        visiting.remove(object);checked.add(object);
    }
    public static String generationSql(String owner){String api=ProfileHistorySql.object(owner,"DBMS_CLOUD_AI");
        return "BEGIN IF "+api+".GET_CONVERSATION_ID IS NOT NULL THEN RAISE_APPLICATION_ERROR(-20051, 'Active conversation is not allowed'); END IF; ? := "+api+".GENERATE(prompt => ?, profile_name => ?, action => 'showsql', attributes => ?); END;";
    }
    public String showsql(String owner,String profile,String prompt,String attributes){
        return jdbc.execute((ConnectionCallback<String>)c->{try(var call=c.prepareCall(generationSql(owner));var p=new StringReader(prompt);var a=new StringReader(attributes)){
            call.setQueryTimeout(90);call.registerOutParameter(1,java.sql.Types.CLOB);call.setCharacterStream(2,p,prompt.length());call.setString(3,profile);call.setCharacterStream(4,a,attributes.length());call.execute();var result=call.getClob(1);
            if(result==null)throw new Failure(422,"query.sqlBlocked");try{if(result.length()>20000)throw new Failure(422,"query.sqlBlocked");return result.getSubString(1,(int)result.length());}finally{result.free();}
        }});
    }
    public OntologyInquiry.Rows execute(OntologyInquiry.Execution value,String actor){
        return execute(value.search().id(),value.draft().sql(),value.draft().hash(),actor);
    }
    public OntologyInquiry.Rows execute(String id,String sql,String hash,String actor){
        return execute(id,sql,hash,actor,15);
    }
    /** Statement budget supplied by the calling workflow; result limits stay unchanged. */
    public OntologyInquiry.Rows execute(String id,String sql,String hash,String actor,int timeoutSeconds){
        return execute(id,sql,hash,actor,timeoutSeconds,()->{});
    }
    /** Measures the JDBC execute/fetch boundary, not Oracle execution-plan operator times. */
    public OntologyInquiry.Rows execute(String id,String sql,String hash,String actor,int timeoutSeconds,Runnable reading){
        com.dbcompanion.common.config.SelectAiExecutionSettings.validate(timeoutSeconds);
        return jdbc.execute((ConnectionCallback<OntologyInquiry.Rows>)c->{try(var stmt=c.prepareStatement(sql)){
            stmt.setQueryTimeout(timeoutSeconds);stmt.setMaxRows(201);stmt.setFetchSize(50);try(var rs=stmt.executeQuery()){
                reading.run();
                var metadata=rs.getMetaData();if(metadata.getColumnCount()>40)throw new Failure(413,"query.resultLimit");var columns=new ArrayList<String>();
                for(int i=1;i<=metadata.getColumnCount();i++){if(!ReviewedSql.scalar(metadata.getColumnTypeName(i)))throw new Failure(422,"query.resultType");columns.add(metadata.getColumnLabel(i));}
                var rows=new ArrayList<List<OntologyInquiry.Cell>>();boolean more=false;int size=0;
                while(rs.next()){if(rows.size()==200){more=true;break;}var row=new ArrayList<OntologyInquiry.Cell>();
                    for(int i=1;i<=columns.size();i++){String str=rs.getString(i);boolean clipped=str!=null&&str.length()>2000;if(clipped)str=str.substring(0,2000);size+=str==null?0:str.length();if(size>1_000_000)throw new Failure(413,"query.resultLimit");row.add(new OntologyInquiry.Cell(str,clipped));}rows.add(row);}
                return new OntologyInquiry.Rows(id,sql,hash,actor,Instant.now().toString(),columns,rows,more);
            }
        }});
    }
}
