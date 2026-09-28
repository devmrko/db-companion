package com.dbcompanion.common.db;

import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** Fixed, explicit setup. Nothing here executes on application startup. */
public final class BusinessGlossarySql {
    private BusinessGlossarySql() {}
    public static final String TABLE="DBC_BUSINESS_TERM", LEXER="DBC_BT_KO_LEXER", POLICY="DBC_BT_KO_POLICY", INDEX="DBC_BT_CTX";
    public static String table(String owner){return ProfileHistorySql.object(owner,TABLE);}
    public static String phraseSearch(String owner){
        String qualified=table(owner);
        try{return new ClassPathResource("db/glossary/phrase-search.sql").getContentAsString(StandardCharsets.UTF_8).replace("{{TABLE}}",qualified);}
        catch(java.io.IOException ex){throw new IllegalStateException("Dictionary phrase SQL asset is missing",ex);}
    }
    public static String create(String owner){return "CREATE TABLE "+table(owner)+"""
        (TERM_ID VARCHAR2(36 CHAR) CONSTRAINT DBC_BT_PK PRIMARY KEY,
         REVISION NUMBER(10) NOT NULL,
         FORMAT_VERSION NUMBER(1) DEFAULT 1 NOT NULL CONSTRAINT DBC_BT_V1 CHECK (FORMAT_VERSION=1),
         TERM_TEXT VARCHAR2(256 CHAR) NOT NULL,
         ALIASES_JSON CLOB NOT NULL CONSTRAINT DBC_BT_JSON CHECK (ALIASES_JSON IS JSON),
         DEFINITION_TEXT CLOB NOT NULL,
         SQL_CRITERIA CLOB NOT NULL,
         ENABLED_YN VARCHAR2(1 CHAR) NOT NULL CONSTRAINT DBC_BT_ENABLED CHECK (ENABLED_YN IN ('Y','N')),
         SEARCH_TEXT CLOB NOT NULL,
         UPDATED_AT TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL)
        """;}
    public static List<String> textSetup(String owner){return List.of("""
        BEGIN
          CTX_DDL.CREATE_PREFERENCE('DBC_BT_KO_LEXER','KOREAN_MORPH_LEXER');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','MORPHEME','TRUE');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','VERB_ADJECTIVE','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','NUMBER','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','STOP_DIC','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','ONE_CHAR_WORD','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','COMPOSITE','COMPONENT_WORD');
          CTX_DDL.SET_ATTRIBUTE('DBC_BT_KO_LEXER','ENGLISH','TRUE');
          CTX_DDL.CREATE_POLICY('DBC_BT_KO_POLICY',lexer=>'DBC_BT_KO_LEXER',stoplist=>'CTXSYS.EMPTY_STOPLIST');
        END;
        ""","CREATE INDEX "+ProfileHistorySql.object(owner,INDEX)+" ON "+table(owner)+"(SEARCH_TEXT) INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('LEXER DBC_BT_KO_LEXER STOPLIST CTXSYS.EMPTY_STOPLIST SYNC (ON COMMIT)')");}
}
