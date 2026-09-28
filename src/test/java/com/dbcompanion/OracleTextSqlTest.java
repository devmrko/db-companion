package com.dbcompanion;

import com.dbcompanion.common.db.OracleTextSql;
import com.dbcompanion.model.Ontology;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OracleTextSqlTest {
    @Test void copyOnlyPlanDeclaresApprovedSourceIdentityLexerContextAndThesaurus(){
        var plan=OracleTextSql.installPlan("APP");String ddl=String.join("\n",plan.ddl());
        assertThat(ddl).contains("TERM_ID","SOURCE_OWNER","SOURCE_TABLE","SOURCE_REVISION","PROFILE_REF","SOURCE_KIND","SOURCE_ID","TERM_TEXT","DEFINITION_TEXT","ALIASES_JSON","KOREAN_MORPH_LEXER","COMPONENT_WORD","CTXSYS.CONTEXT","FORMAT_VERSION","DBC_GLT_V1").doesNotContain("<schema>","...");
        assertThat(plan.warnings()).anyMatch(v->v.contains("implicitly commits")).anyMatch(v->v.contains("not EXECUTE"));
        assertThat(plan.requiredPrivileges()).anyMatch(v->v.contains("CREATE ANY INDEX is only needed for another schema"));
        assertThat(plan.thesaurus()).matches("DBC_GLT_[A-F0-9]{22}");
    }
    @Test void sqlclStatementsUseSemicolonsAndPlsqlBlocksUseASlash(){
        var ddl=OracleTextSql.installPlan("APP").ddl();
        assertThat(ddl.get(0)).endsWith(");");assertThat(ddl.get(1)).endsWith("END;\n/");
        assertThat(ddl.get(2)).endsWith(";");assertThat(ddl).hasSize(3);
        assertThat(OracleTextSql.jdbcStatement(ddl.get(0))).doesNotEndWith(";");
        assertThat(OracleTextSql.jdbcStatement(ddl.get(1))).endsWith("END;").doesNotEndWith("/");
        assertThat(OracleTextSql.jdbcStatement(ddl.get(2))).doesNotEndWith(";");
    }
    @Test void containsLiteralEscapesReservedSyntaxClosingBraceAndBackslashSeparatelyFromSqlBinding(){
        assertThat(OracleTextSql.escapeContainsTerm("OR*}\\")).isEqualTo("{OR*}}\\\\}");
        var query=OracleTextSql.containsQuery("APP","APP.PROFILE",Map.of("T2",2,"T1",1),"OR*}\\",20);
        assertThat(query.sql()).contains("SOURCE_OWNER=?","PROFILE_REF=?","SOURCE_TABLE=? AND SOURCE_REVISION=?","CONTAINS(SEARCH_TEXT, ?, 1)","FETCH FIRST ?").doesNotContain("OR*");
        assertThat(query.bindings()).containsExactly("APP","APP.PROFILE","T1",1,"T2",2,"{OR*}}\\\\}",20);
        assertThatThrownBy(()->OracleTextSql.escapeContainsTerm("가".repeat(257))).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->OracleTextSql.escapeContainsTerm("bad\nterm")).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->OracleTextSql.containsQuery("APP","P",Map.of("T",1),"term",101)).isInstanceOf(Ontology.Failure.class);
        assertThatThrownBy(()->OracleTextSql.containsQuery("APP","P",Map.of(),"term",20)).isInstanceOf(Ontology.Failure.class);
    }
    @Test void synonymFactoryUsesUnqualifiedOwnerBoundThesaurusAndEscapesPhraseLiterals(){
        var script=OracleTextSql.thesaurusSynonyms("APP","O'Brien",List.of("OR", "a}b", "O'Brien"));
        assertThat(script).contains("CTX_THES.CREATE_PHRASE","CTX_THES.CREATE_RELATION","'O''Brien'","'OR'","'a}b'").doesNotContain("APP.");
        assertThatThrownBy(()->OracleTextSql.thesaurusSynonyms("APP","term",List.of("term"))).isInstanceOf(Ontology.Failure.class);
    }
}
