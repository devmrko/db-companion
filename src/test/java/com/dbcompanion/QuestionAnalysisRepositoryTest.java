package com.dbcompanion;

import com.dbcompanion.common.db.QuestionAnalysisSql;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.QuestionLanguage;
import com.dbcompanion.model.BusinessGlossary.Token;
import com.dbcompanion.repository.QuestionAnalysisRepository;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class QuestionAnalysisRepositoryTest {
    final List<String> queries=new ArrayList<>();
    final Map<String,String> policies=new HashMap<>();
    final Map<Integer,Object> tokenBindings=new HashMap<>();
    String preparedSql,output="[{\"token\":\"사용자\",\"offset\":1,\"length\":3}]";
    boolean physical;
    Clob clob;
    final AbstractDataSource source=new AbstractDataSource(){
        @Override public Connection getConnection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "prepareStatement" -> statement((String)a[0]);
            case "prepareCall" -> {preparedSql=(String)a[0];yield call();}
            default -> empty(m.getReturnType());
        });}
        @Override public Connection getConnection(String u,String p){throw new AssertionError("Unexpected login");}
    };
    final QuestionAnalysisRepository repository=new QuestionAnalysisRepository(new JdbcTemplate(source),new JsonMapper());
    @Test void standalonePolicyNeedsNoDictionaryObjects(){
        policies.put(QuestionAnalysisSql.POLICY,"KOREAN_MORPH_LEXER");
        assertThat(repository.policy("APP")).isEqualTo(QuestionAnalysisSql.POLICY);
        assertThat(queries).hasSize(3).allMatch(s->!s.contains("DBC_BUSINESS_TERM")&&!s.contains("DBC_BT_CTX")&&!s.contains("ALL_TAB_COLUMNS"));
    }
    @Test void existingPolicyIsCompatibleEvenWithoutAnIndexOrTable(){
        policies.put("DBC_BT_KO_POLICY","KOREAN_MORPH_LEXER");
        assertThat(repository.policy("APP")).isEqualTo("DBC_BT_KO_POLICY");
        assertThat(queries).hasSize(4);
    }
    @Test void languagePoliciesNeverFallBackToAnUnrelatedLanguage(){
        policies.put("DBC_BT_KO_POLICY","KOREAN_MORPH_LEXER");
        for(var language:List.of(QuestionLanguage.EN,QuestionLanguage.JA,QuestionLanguage.ZH)){
            assertThat(repository.policy("APP",language)).isEmpty();
            policies.put(language.policy(),language.lexer());
            assertThat(repository.policy("APP",language)).isEqualTo(language.policy());
            repository.tokens(language.policy(),"Original question");
            assertThat(tokenBindings).containsEntry(1,language.policy()).containsEntry(2,"Original question");
        }
    }
    @Test void missingIsDistinctFromInvalidPolicyAndPhysicalIndexesAreNotPolicies(){
        assertThat(repository.policy("APP")).isEmpty();
        policies.put(QuestionAnalysisSql.POLICY,"BASIC_LEXER");policies.put("DBC_BT_KO_POLICY","KOREAN_MORPH_LEXER");
        assertThatThrownBy(()->repository.policy("APP")).isInstanceOf(AiAssistant.Failure.class);
        policies.put(QuestionAnalysisSql.POLICY,"KOREAN_MORPH_LEXER");physical=true;
        assertThatThrownBy(()->repository.policy("APP")).isInstanceOf(AiAssistant.Failure.class);
    }
    @Test void nativeTokenCallBindsQuestionAndPolicyAndFreesOutput(){
        String question="사용자와 권역은? ' OR 1=1 --";
        assertThat(repository.tokens(QuestionAnalysisSql.POLICY,question)).containsExactly(new Token("사용자",1,3));
        assertThat(tokenBindings).containsEntry(1,QuestionAnalysisSql.POLICY).containsEntry(2,question);
        assertThat(preparedSql).isEqualTo(QuestionAnalysisSql.tokens()).doesNotContain(question,"CREATE_","DROP_");
        assertThatThrownBy(()->clob.length()).isInstanceOf(SQLException.class);
    }
    @Test void unexpectedPolicyAndMalformedOutputAreRejected(){
        assertThatThrownBy(()->repository.tokens("OTHER.POLICY","q")).isInstanceOf(AiAssistant.Failure.class);
        assertThat(preparedSql).isNull();output="{}";
        assertThatThrownBy(()->repository.tokens(QuestionAnalysisSql.POLICY,"q")).isInstanceOf(AiAssistant.Failure.class);
        assertThatThrownBy(()->clob.length()).isInstanceOf(SQLException.class);
    }
    private PreparedStatement statement(String sql){
        queries.add(sql);var args=new HashMap<Integer,Object>();
        return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
            case "setString", "setObject" -> {args.put((Integer)a[0],a[1]);yield null;}
            case "executeQuery" -> {
                String name=(String)args.get(1);List<?> values;
                if(sql.contains("CTX_USER_INDEXES"))values=policies.containsKey(name)?List.of(name):List.of();
                else if(sql.contains("CTX_USER_INDEX_OBJECTS"))values=policies.containsKey(name)?List.of(policies.get(name)):List.of();
                else if(sql.contains("SYS.ALL_INDEXES")){assertThat(args.get(1)).isEqualTo("APP");values=List.of(physical?1:0);}
                else throw new AssertionError(sql);
                yield results(values);
            }
            default -> empty(m.getReturnType());
        });
    }
    private ResultSet results(List<?> rows){
        int[] index={-1};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next" -> ++index[0]<rows.size();
            case "getString" -> String.valueOf(rows.get(index[0]));
            case "getInt" -> ((Number)rows.get(index[0])).intValue();
            case "getMetaData" -> proxy(ResultSetMetaData.class,(x,f,b)->f.getName().equals("getColumnCount")?1:empty(f.getReturnType()));
            default -> empty(m.getReturnType());
        });
    }
    private CallableStatement call(){return proxy(CallableStatement.class,(p,m,a)->switch(m.getName()){
        case "setString" -> {tokenBindings.put((Integer)a[0],a[1]);yield null;}
        case "getClob" -> {assertThat(a[0]).isEqualTo(3);clob=new SerialClob(output.toCharArray());yield clob;}
        default -> empty(m.getReturnType());
    });}
    static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
}
