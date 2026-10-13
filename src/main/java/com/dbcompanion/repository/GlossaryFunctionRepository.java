package com.dbcompanion.repository;

import com.dbcompanion.common.db.GlossaryFunctionSql;
import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.model.GlossaryFunction.Status;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Types;
import java.util.List;
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
        var types=jdbc.queryForList("SELECT OBJECT_TYPE||':'||STATUS FROM USER_OBJECTS WHERE OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL",String.class,GlossaryFunctionSql.NAME);
        String state="MISSING";
        List<String> errors=List.of();
        if(!types.isEmpty()){
            state="CONFLICT";
            if(types.size()==1&&types.getFirst().startsWith("FUNCTION:")){
                errors=jdbc.query("SELECT LINE,POSITION,TEXT FROM USER_ERRORS WHERE NAME=? AND TYPE='FUNCTION' ORDER BY SEQUENCE",
                    (r,n)->r.getInt(1)+":"+r.getInt(2)+" "+r.getString(3),GlossaryFunctionSql.NAME);
                String actual=String.join("",jdbc.queryForList("SELECT TEXT FROM USER_SOURCE WHERE NAME=? AND TYPE='FUNCTION' ORDER BY LINE",String.class,GlossaryFunctionSql.NAME));
                String expected=GlossaryFunctionSql.create(owner).replaceFirst("^CREATE FUNCTION "+java.util.regex.Pattern.quote(ProfileHistorySql.object(owner,GlossaryFunctionSql.NAME)),"FUNCTION "+GlossaryFunctionSql.NAME);
                if(normalize(actual).equals(normalize(expected)))state=types.getFirst().equals("FUNCTION:VALID")?"READY":"INVALID";
            }
        }
        return new Status(owner,dictionary,text,state,canCreate,errors,GlossaryFunctionSql.create(owner),GlossaryFunctionSql.example(owner));
    }
    public static String normalize(String source){
        return source.replace("\r\n","\n").strip()
            .replaceFirst("(?i)^FUNCTION\\s+\"DBC_GLOSSARY_SEARCH\"","FUNCTION DBC_GLOSSARY_SEARCH");
    }
    public Status install(String owner){
        if(!status(owner).installable())throw new IllegalArgumentException("Creation conditions changed; inspect the status again");
        // Never replace an existing object. A race is rejected by Oracle CREATE.
        jdbc.execute(GlossaryFunctionSql.create(owner));
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
