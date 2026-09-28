package com.dbcompanion;

import com.dbcompanion.common.db.BusinessGlossarySql;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.repository.BusinessGlossaryRepository;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Synthetic JDBC contract: native matching is exercised separately against Oracle. */
class BusinessGlossaryPhraseRepositoryTest {
    private static final String ID="11111111-1111-1111-1111-111111111111";
    private final Map<Integer,Object> bindings=new HashMap<>();
    private String preparedSql;
    private String output="[{\"expression\":\"BUSINESS ACTIVE\",\"kind\":\"TERM\",\"termIds\":[\""+ID+"\"]}]";
    private Clob result;
    private int connections;
    private final AbstractDataSource source=new AbstractDataSource(){
        @Override public Connection getConnection(){
            connections++;
            return proxy(Connection.class,(p,m,a)->{
                if(m.getName().equals("prepareCall")){preparedSql=(String)a[0];return statement();}
                return empty(m.getReturnType());
            });
        }
        @Override public Connection getConnection(String username,String password){throw new AssertionError("Unexpected authentication");}
    };
    private final BusinessGlossaryRepository repository=new BusinessGlossaryRepository(new JdbcTemplate(source),new JsonMapper());

    @Test void questionAndCandidateExpressionAreBoundAndOnlyDbTargetsAreDecoded() throws Exception {
        String question="show Business Active; ' OR 1=1 --";
        var targets=repository.phraseTargets("APP",question,"{BUSINESS} OR {ACTIVE}");
        assertThat(targets).containsExactly(new BusinessGlossary.Target("BUSINESS ACTIVE","TERM",List.of(ID)));
        assertThat(bindings).containsEntry(1,question).containsEntry(2,"{BUSINESS} OR {ACTIVE}");
        assertThat(preparedSql).contains("\"APP\".\"DBC_BUSINESS_TERM\"","CTX_DOC.POLICY_HIGHLIGHT").doesNotContain(question);
        assertThatThrownBy(()->result.length()).isInstanceOf(SQLException.class);
    }
    @Test void emptyTokenQueryDoesNotIssueANativeTextQuery(){
        assertThat(repository.phraseTargets("APP","nothing","")).isEmpty();assertThat(connections).isZero();
    }
    @Test void noNativeMatchIsAnEmptyResultNotAJavaLanguageFallback(){
        output="[]";assertThat(repository.phraseTargets("APP","Business Active","{ACTIVE}")).isEmpty();
    }
    @Test void nativeMorphologicalMatchDoesNotRewriteTheOriginalQuestion(){
        String question="이탈하지 않은 사용자";
        output="[{\"expression\":\"이탈 사용자\",\"kind\":\"TERM\",\"termIds\":[\""+ID+"\"]}]";
        assertThat(repository.phraseTargets("APP",question,"{이탈} OR {사용자}"))
                .containsExactly(new BusinessGlossary.Target("이탈 사용자","TERM",List.of(ID)));
        assertThat(bindings).containsEntry(1,question).containsEntry(2,"{이탈} OR {사용자}");
    }
    @Test void nativePhraseAcceptancePreservesPunctuationBoundariesWithoutLiteralEquality(){
        String sql=BusinessGlossarySql.phraseSearch("APP");
        assertThat(sql).contains("CTX_DOC.POLICY_HIGHLIGHT",
                "NVL(REGEXP_REPLACE(j.LABEL,'[[:alnum:][:space:]]',''),' ')",
                "NVL(REGEXP_REPLACE(j.MATCHED_TEXT,'[[:alnum:][:space:]]',''),' ')");
        assertThat(sql).doesNotContain("UPPER(REGEXP_REPLACE(TRIM(j.MATCHED_TEXT)");
    }
    @Test void malformedNativePayloadIsRejectedAndClobFreed(){
        output="[{\"expression\":\"ACTIVE\",\"kind\":\"TEXT\",\"termIds\":[\""+ID+"\"]}]";
        assertThatThrownBy(()->repository.phraseTargets("APP","Active","{ACTIVE}")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->result.length()).isInstanceOf(SQLException.class);
    }
    @Test void matchingAssetUsesCurrentDictionaryAndContainsNoInstallationOrNewRuleStore(){
        String sql=BusinessGlossarySql.phraseSearch("APP");
        assertThat(sql).contains("CONTAINS(SEARCH_TEXT,candidate_query,1)","TERM_TEXT AS LABEL","t.ALIASES_JSON",
                "SUBSTR2(question_text", "longer.START_POS<=m.START_POS", "longer.END_POS>=m.END_POS",
                "longer.END_POS-longer.START_POS>m.END_POS-m.START_POS", "active_count>"+BusinessGlossary.MAX_TERMS,
                "hits.get_size()>512", "RAISE;", "ENABLED_YN='Y'");
        assertThat(sql).doesNotContain("PROBE", "CTXRULE", "CREATE ", "ALTER ", "UPDATE ", "INSERT ",
                "DEFINITION_TEXT", "SQL_CRITERIA", "FETCH FIRST 31", "EXECUTE IMMEDIATE", "COMMIT");
        assertThat(BusinessGlossarySql.phraseSearch("APP\";DROP TABLE T"))
                .contains("\"APP\"\";DROP TABLE T\".\"DBC_BUSINESS_TERM\"");
    }
    private CallableStatement statement(){
        return proxy(CallableStatement.class,(p,m,a)->switch(m.getName()){
            case "setString" -> {bindings.put((Integer)a[0],a[1]);yield null;}
            case "setQueryTimeout" -> {assertThat(a[0]).isEqualTo(30);yield null;}
            case "registerOutParameter" -> {assertThat(a[0]).isEqualTo(3);assertThat(a[1]).isEqualTo(Types.CLOB);yield null;}
            case "execute" -> false;
            case "getClob" -> {assertThat(a[0]).isEqualTo(3);result=new SerialClob(output.toCharArray());yield result;}
            default -> empty(m.getReturnType());
        });
    }
    private static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
}
