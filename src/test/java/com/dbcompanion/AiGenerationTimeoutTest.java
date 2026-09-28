package com.dbcompanion;

import com.dbcompanion.model.SelectAiTest.Action;
import com.dbcompanion.repository.AiAssistantRepository;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.assertj.core.api.Assertions.*;

/** Exercises real repository bindings without a provider call. */
class AiGenerationTimeoutTest {
    private int timeout,executions,clobFrees,closed;
    private String sql,profile;
    private boolean fail;
    private final Clob clob=(Clob)Proxy.newProxyInstance(Clob.class.getClassLoader(),new Class[]{Clob.class},(p,m,a)->switch(m.getName()){
        case "length"->18L;
        case "getSubString"->"SELECT 1 FROM DUAL";
        case "free"->{clobFrees++;yield null;}
        default->throw new UnsupportedOperationException(m.getName());
    });
    private final CallableStatement call=(CallableStatement)Proxy.newProxyInstance(CallableStatement.class.getClassLoader(),new Class[]{CallableStatement.class},(p,m,a)->switch(m.getName()){
        case "setQueryTimeout"->{timeout=(int)a[0];yield null;}
        case "registerOutParameter"->{assertThat(a[0]).isEqualTo(1);assertThat(a[1]).isEqualTo(java.sql.Types.CLOB);yield null;}
        case "setCharacterStream"->{assertThat(a[0]).isEqualTo(2);yield null;}
        case "setString"->{assertThat(a[0]).isEqualTo(3);profile=(String)a[1];yield null;}
        case "execute"->{executions++;if(fail)throw new SQLException("ORA-18730: Socket read timed out","08006",18730);yield false;}
        case "getClob"->clob;
        case "close"->{closed++;yield null;}
        default->throw new UnsupportedOperationException(m.getName());
    });
    private final Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
        case "prepareCall"->{sql=(String)a[0];yield call;}
        case "close"->null;
        case "isClosed"->false;
        default->throw new UnsupportedOperationException(m.getName());
    });
    private final AiAssistantRepository repository=new AiAssistantRepository(new JdbcTemplate(new AbstractDataSource(){
        @Override public Connection getConnection(){return connection;}
        @Override public Connection getConnection(String user,String password){return connection;}
    }));
    @Test void explicitBudgetPreservesActionAndBindingsAndReleasesResponse(){
        for(Action action:Action.values()){
            assertThat(repository.generate("CLOUD","PROFILE","synthetic question",action,600)).isEqualTo("SELECT 1 FROM DUAL");
            assertThat(timeout).isEqualTo(600);assertThat(profile).isEqualTo("PROFILE");
            assertThat(sql).isEqualTo(AiAssistantRepository.generateSql("CLOUD",action));
        }
        assertThat(executions).isEqualTo(3);assertThat(clobFrees).isEqualTo(3);assertThat(closed).isEqualTo(3);
    }
    @Test void otherScreensKeepExistingDefaultBudget(){
        repository.generate("CLOUD","PROFILE","question",Action.SQL);
        assertThat(timeout).isEqualTo(90);
    }
    @Test void explainUsesConfiguredBudget(){
        repository.explain("CLOUD","PROFILE","question",300);
        assertThat(timeout).isEqualTo(300);
        assertThat(sql).isEqualTo(AiAssistantRepository.generateSql("CLOUD",Action.CHAT));
    }
    @Test void invalidBudgetNeverExecutes(){
        assertThatThrownBy(()->repository.generate("CLOUD","PROFILE","question",Action.SQL,0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(executions).isZero();
    }
    @Test void networkFailureIsNotRetried(){
        fail=true;
        assertThatThrownBy(()->repository.generate("CLOUD","PROFILE","question",Action.SQL,300)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(executions).isEqualTo(1);assertThat(closed).isEqualTo(1);assertThat(clobFrees).isZero();
    }
}
