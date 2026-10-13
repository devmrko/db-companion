package com.dbcompanion.common.db;

/** A portable search-only module. No profile, AI request, business SQL or writes. */
public final class GlossaryFunctionSql {
    public static final String NAME="DBC_GLOSSARY_SEARCH";
    private GlossaryFunctionSql() {}
    public static String create(String owner){
        return """
            CREATE FUNCTION %s(p_question CLOB)
              RETURN CLOB AUTHID CURRENT_USER AS
              -- DB Companion glossary search v1
              result JSON_OBJECT_T:=JSON_OBJECT_T();
              question_text VARCHAR2(32767);
            %s
            BEGIN
              IF p_question IS NULL OR DBMS_LOB.GETLENGTH(p_question)>8000 THEN
                RAISE_APPLICATION_ERROR(-20060,'Question must contain 1 to 8000 characters');
              END IF;
              question_text:=TRIM(DBMS_LOB.SUBSTR(p_question,8000,1));
              IF question_text IS NULL THEN
                RAISE_APPLICATION_ERROR(-20060,'Question must not be blank');
              END IF;
              result.put('formatVersion',1);
              result.put('question',question_text);
              result.put('schema',SYS_CONTEXT('USERENV','CURRENT_USER'));
              result.put('glossary',glossary(question_text));
              result.put('aiCalled',FALSE);
              RETURN result.to_clob();
            END DBC_GLOSSARY_SEARCH;
            """.formatted(ProfileHistorySql.object(owner,NAME),
                CallableAiSql.resource("glossary-functions.sql").stripTrailing())
                .replace("{{PHRASE_BLOCK}}",CallableAiSql.phraseBlock());
    }
    public static String example(String owner){
        return "SELECT "+ProfileHistorySql.object(owner,NAME)+"(:question) AS EVIDENCE_JSON FROM DUAL";
    }
}
