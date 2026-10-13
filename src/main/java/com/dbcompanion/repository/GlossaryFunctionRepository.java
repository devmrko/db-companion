package com.dbcompanion.repository;

import com.dbcompanion.common.db.GlossaryFunctionSql;
import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.GlossaryFunction.Status;
import com.dbcompanion.model.GlossaryFunction.Code;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Types;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class GlossaryFunctionRepository {
    private final JdbcTemplate jdbc;
    private final BusinessGlossaryRepository glossary;
    private final JsonMapper json;
    public GlossaryFunctionRepository(JdbcTemplate jdbc,BusinessGlossaryRepository glossary,JsonMapper json){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(30);this.glossary=glossary;this.json=json;
    }
    public Status status(String owner){
        var dictionary=glossary.tableStatus(owner);
        var text=dictionary.equals("READY")?glossary.textStatus(owner):"MISSING";
        boolean canCreate=jdbc.queryForObject("SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE='CREATE PROCEDURE'",Integer.class)>0;
        var scripts=GlossaryFunctionSql.statements(owner);
        var codes=new ArrayList<Code>();
        var states=new ArrayList<String>();
        var errors=new ArrayList<String>();
        var snapshots=new ArrayList<String>();
        for(int i=0;i<scripts.size();i++){
            String name=i<2?GlossaryFunctionSql.API:GlossaryFunctionSql.NAME;
            String type=i==0?"PACKAGE":i==1?"PACKAGE BODY":"FUNCTION";
            var objects=jdbc.queryForList("SELECT OBJECT_TYPE||':'||STATUS FROM USER_OBJECTS WHERE OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL ORDER BY OBJECT_TYPE",String.class,name);
            String actual=String.join("",jdbc.queryForList("SELECT TEXT FROM USER_SOURCE WHERE NAME=? AND TYPE=? ORDER BY LINE",String.class,name,type));
            String expected=storedSource(owner,name,scripts.get(i));
            String state="CONFLICT";
            boolean wrongType=objects.stream().anyMatch(o->iTypeConflict(name,o));
            if(!wrongType){
                if(objects.stream().noneMatch(o->o.startsWith(type+":")))state="MISSING";
                else if(normalize(actual).equals(normalize(expected)))state=objects.contains(type+":VALID")?"READY":"INVALID";
                else if(i==2&&normalize(actual).equals(normalize(storedSource(owner,name,GlossaryFunctionSql.legacyCreate(owner)))))state="LEGACY";
            }
            states.add(state);
            snapshots.add(name+":"+type+":"+objects+":"+actual);
            errors.addAll(jdbc.query("SELECT LINE,POSITION,TEXT FROM USER_ERRORS WHERE NAME=? AND TYPE=? ORDER BY SEQUENCE",
                (r,n)->name+" "+type+" "+r.getInt(1)+":"+r.getInt(2)+" "+r.getString(3),name,type));
            String action=state.equals("MISSING")?"CREATE":state.equals("LEGACY")?"REPLACE_V1":"KEEP";
            String sql=action.equals("REPLACE_V1")?scripts.get(i).replaceFirst("CREATE FUNCTION","CREATE OR REPLACE FUNCTION"):scripts.get(i);
            codes.add(new Code(name,type,action,sql));
        }
        String state=moduleState(states);
        String sql=String.join("\n/\n\n",codes.stream().filter(c->!c.action().equals("KEEP")).map(Code::sql).toList());
        return new Status(owner,dictionary,text,state,canCreate,errors,sql,GlossaryFunctionSql.example(owner),List.copyOf(codes),
            com.dbcompanion.service.OntologyQueryService.hash(String.join("\n",snapshots)));
    }
    private static boolean iTypeConflict(String name,String object){
        return name.equals(GlossaryFunctionSql.NAME)?!object.startsWith("FUNCTION:"):
            !object.startsWith("PACKAGE:")&&!object.startsWith("PACKAGE BODY:");
    }
    public static String moduleState(List<String> states){
        if(states.contains("CONFLICT")||states.get(0).equals("MISSING")&&!states.get(1).equals("MISSING"))return "CONFLICT";
        if(states.contains("INVALID"))return "INVALID";
        if(states.stream().allMatch(s->s.equals("READY")))return "READY";
        if(states.stream().allMatch(s->s.equals("MISSING")))return "MISSING";
        return states.get(2).equals("LEGACY")?"LEGACY":"INCOMPLETE";
    }
    public static String storedSource(String owner,String name,String sql){
        return sql.replaceFirst("^CREATE (?:OR REPLACE )?","")
            .replaceFirst(java.util.regex.Pattern.quote(ProfileHistorySql.object(owner,name)),name);
    }
    public static String normalize(String source){
        return source.replace("\r\n","\n").strip()
            .replaceFirst("(?i)^(FUNCTION|PACKAGE BODY|PACKAGE)\\s+\"(DBC_GLOSSARY_SEARCH|DBC_GLOSSARY_API)\"","$1 $2");
    }
    public Status install(String owner){
        var plan=status(owner);
        if(!plan.installable())throw new IllegalArgumentException("Creation conditions changed; inspect the status again");
        for(var code:plan.codes()){
            if(code.action().equals("KEEP"))continue;
            var current=status(owner);
            var next=current.codes().stream().filter(c->c.name().equals(code.name())&&c.type().equals(code.type())).findFirst().orElseThrow();
            if(!current.installable()||!next.action().equals(code.action())||!next.sql().equals(code.sql()))
                throw new IllegalArgumentException("Stored code changed; preview again");
            // CREATE rejects races. Only our exact v1 wrapper may be replaced.
            jdbc.execute(code.sql());
        }
        return status(owner);
    }
    public Object search(String owner,String question){
        var status=status(owner);
        if(!status.function().equals("READY")||!status.dictionary().equals("READY")||!status.text().equals("READY"))
            throw new IllegalArgumentException("Search module or dictionary is not ready");
        return jdbc.execute((ConnectionCallback<Object>)connection->{
            try(var call=connection.prepareCall("BEGIN ? := "+ProfileHistorySql.object(owner,GlossaryFunctionSql.NAME)+"(?); END;")){
                call.setQueryTimeout(30);call.registerOutParameter(1,Types.CLOB);
                call.setClob(2,new StringReader(question));call.execute();
                Clob output=call.getClob(1);
                if(output==null)throw new IllegalArgumentException("The search returned no JSON");
                try{
                    if(output.length()>100000)throw new IllegalArgumentException("Search evidence exceeded the limit");
                    return json.readTree(output.getSubString(1,(int)output.length()));
                }finally{output.free();}
            }
        });
    }
}
