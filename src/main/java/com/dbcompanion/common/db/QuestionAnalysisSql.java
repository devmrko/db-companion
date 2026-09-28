package com.dbcompanion.common.db;

import com.dbcompanion.model.QuestionLanguage;

/** Owner-local, index-free Oracle Text document analysis. Setup is never executed by a search. */
public final class QuestionAnalysisSql {
    private QuestionAnalysisSql() {}
    public static final String LEXER="DBC_QA_KO_LEXER", POLICY="DBC_QA_KO_POLICY";
    public static String setup(){return """
        BEGIN
          CTX_DDL.CREATE_PREFERENCE('DBC_QA_KO_LEXER','KOREAN_MORPH_LEXER');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','MORPHEME','TRUE');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','VERB_ADJECTIVE','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','NUMBER','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','STOP_DIC','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','ONE_CHAR_WORD','FALSE');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','COMPOSITE','COMPONENT_WORD');
          CTX_DDL.SET_ATTRIBUTE('DBC_QA_KO_LEXER','ENGLISH','TRUE');
          CTX_DDL.CREATE_POLICY('DBC_QA_KO_POLICY',lexer=>'DBC_QA_KO_LEXER',stoplist=>'CTXSYS.EMPTY_STOPLIST');
        END;
        """;}
    public static String setup(QuestionLanguage language){
        if(language==QuestionLanguage.KO)return setup();
        return "BEGIN\n  CTX_DDL.CREATE_PREFERENCE('"+language.preference()+"','"+language.lexer()+"');\n"
            +"  CTX_DDL.CREATE_POLICY('"+language.policy()+"',lexer=>'"+language.preference()+"',stoplist=>'CTXSYS.EMPTY_STOPLIST');\nEND;\n";
    }
    /** Copy-only SQL. Refuse pre-existing/partial objects instead of overwriting them. */
    public static String script(QuestionLanguage language){
        String guard="DECLARE n NUMBER;\nBEGIN\n"
            +"  SELECT COUNT(*) INTO n FROM CTXSYS.CTX_USER_PREFERENCES WHERE PRE_NAME='"+language.preference()+"';\n"
            +"  IF n<>0 THEN RAISE_APPLICATION_ERROR(-20001,'Lexer already exists: inspect configuration first'); END IF;\n"
            +"  SELECT COUNT(*) INTO n FROM CTXSYS.CTX_USER_INDEXES WHERE IDX_NAME='"+language.policy()+"';\n"
            +"  IF n<>0 THEN RAISE_APPLICATION_ERROR(-20001,'Policy already exists: inspect configuration first'); END IF;\n";
        return guard+setup(language).replaceFirst("^BEGIN\\s*","")+"/\n";
    }
    public static String tokens(){return """
        DECLARE t CTX_DOC.TOKEN_TAB; a JSON_ARRAY_T:=JSON_ARRAY_T(); o JSON_OBJECT_T; n BINARY_INTEGER;
        BEGIN
          CTX_DOC.POLICY_TOKENS(policy_name=>?,document=>?,restab=>t,format=>'TEXT');
          IF t.COUNT>256 THEN RAISE_APPLICATION_ERROR(-20001,'Token limit'); END IF;
          n:=t.FIRST;
          WHILE n IS NOT NULL LOOP
            o:=JSON_OBJECT_T();o.put('token',t(n).token);o.put('offset',t(n).offset);o.put('length',t(n).length);a.append(o);n:=t.NEXT(n);
          END LOOP;
          ?:=a.to_clob();
        END;
        """;}
}
