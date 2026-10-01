package com.dbcompanion;

import com.dbcompanion.common.db.CallableAiSql;
import com.dbcompanion.model.CallableAi.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CallableAiTest {
    @Test void scriptsContainNativeSearchIndependentOptionsAndInvokerRights(){
        var scripts=CallableAiSql.statements("DEMO_APP");assertThat(scripts).hasSize(2);
        assertThat(scripts.getFirst()).startsWith("CREATE PACKAGE \"DEMO_APP\".\"DBC_AI_QUERY\" AUTHID CURRENT_USER");
        String body=scripts.get(1);
        assertThat(body).contains("CTX_DOC.POLICY_TOKENS","CTX_DOC.POLICY_HIGHLIGHT","CONTAINS(SEARCH_TEXT",":question_bind",":query_bind",":result_bind",
            "IF p_use_glossary=1 THEN terms:=glossary", "IF p_use_ontology=1 THEN ont:=ontology", "IF p_mode<>'CONTEXT'", "action=>'showsql'", "OPEN rc FOR p_sql", "DBMS_SQL.TO_CURSOR_NUMBER");
        assertThat(body).doesNotContain("{{PHRASE_BLOCK}}","{{PACKAGE}}","action=>'runsql'","CREATE OR REPLACE","EXECUTE IMMEDIATE generated_sql"," :bind_value ");
        assertThat(body.indexOf("glossary(question_text)")).isLessThan(body.indexOf("DBMS_CLOUD_AI.GENERATE"));
        assertThat(body).contains("state<>'APPROVED'","Latest revision first");
    }
    @Test void nativeOntologyUsesLatestApprovalsScopedTablesAndSafeColumns(){
        var body=CallableAiSql.resource("body.sql");
        assertThat(body).contains("ORDER BY REVISION DESC FETCH FIRST 1 ROW ONLY","ATTRIBUTE_NAME='object_list'", "current business rows",
            "targetDocumentId","targetRevision","sourceRevision","safe_columns(doc)","columns_present", "'SENSITIVE'", "approvedValueMappings");
        assertThat(body).doesNotContain("WHERE STATE='APPROVED'", "'DRAFT'", "AUTONOMOUS_TRANSACTION", "GRANT ");
    }
    @Test void boundedRequestsAndOntologyAreIndependent(){
        var request=new Request("질문","PROFILE",false,true,List.of("T"),"CONTEXT",200);
        assertThat(request.glossary()).isFalse();assertThat(request.ontology()).isTrue();
        assertThat(new Request("질문","PROFILE",true,false,List.of("T"),"SQL",200).tables()).isEmpty();
        assertThatThrownBy(()->new Request("q","P",true,true,List.of(),"CONTEXT",200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Request("q","P",true,true,List.of("T","T"),"CONTEXT",200)).isInstanceOf(IllegalArgumentException.class);
        for(String mode:List.of("RUNSQL","DROP",""))assertThatThrownBy(()->new Request("q","P",false,false,List.of(),mode,200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Request("q","P",false,false,List.of(),"QUERY",1001)).isInstanceOf(IllegalArgumentException.class);
    }
}
