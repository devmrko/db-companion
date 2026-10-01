package com.dbcompanion.common.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Portable, invoker-rights DB implementation. No customer rules or app callback. */
public final class CallableAiSql {
    public static final String NAME="DBC_AI_QUERY";
    public static final String VERSION="DB Companion callable AI v1";
    private CallableAiSql() {}
    public static String resource(String name){
        try(var in=CallableAiSql.class.getResourceAsStream("/db/callable/"+name)){
            if(in==null)throw new IllegalStateException("Missing callable SQL");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException ex){throw new IllegalStateException(ex);}
    }
    public static List<String> statements(String owner){
        String phrase=BusinessGlossarySql.phraseSearch("DBC_CALLER_SCHEMA")
            .replace("\"DBC_CALLER_SCHEMA\".\"DBC_BUSINESS_TERM\"","{{GLOSSARY_TABLE}}")
            .replaceFirst("\\?",":question_bind").replaceFirst("\\?",":query_bind").replaceFirst("\\?",":result_bind");
        // Bind placeholders are positional inside the dynamic anonymous block.
        String escaped=phrase.replace("'","''");
        return List.of(resource("spec.sql").replace("{{PACKAGE}}",ProfileHistorySql.object(owner,NAME)),
            resource("body.sql").replace("{{PACKAGE}}",ProfileHistorySql.object(owner,NAME)).replace("{{PHRASE_BLOCK}}",escaped));
    }
    public static String script(String owner){return String.join("\n/\n\n",statements(owner))+"\n/\n";}
}
