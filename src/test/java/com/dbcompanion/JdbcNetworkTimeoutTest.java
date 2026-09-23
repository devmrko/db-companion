package com.dbcompanion;

import com.dbcompanion.common.db.JdbcNetworkTimeout;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class JdbcNetworkTimeoutTest {
    int timeout=15_000;
    boolean failRestore,failSet;
    final List<Integer> changes=new ArrayList<>();
    final Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
        case "getNetworkTimeout" -> timeout;
        case "setNetworkTimeout" -> {int next=(int)a[1];if(failSet||failRestore&&next==15_000)throw new SQLException("test timeout failure");timeout=next;changes.add(next);yield null;}
        case "equals" -> p==a[0];case "hashCode" -> System.identityHashCode(p);
        default -> m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
    });
    final AbstractDataSource source=new AbstractDataSource(){@Override public Connection getConnection(){throw new AssertionError("Must reuse transaction connection");}@Override public Connection getConnection(String u,String p){throw new AssertionError();}};
    void bound(Runnable action){TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(connection));try{action.run();}finally{TransactionSynchronizationManager.unbindResource(source);}}
    @Test void appliesAndRestoresOnSuccess(){bound(()->assertThat(JdbcNetworkTimeout.execute(source,40_000,()->{assertThat(timeout).isEqualTo(40_000);return "ok";})).isEqualTo("ok"));assertThat(changes).containsExactly(40_000,15_000);}
    @Test void restoresOnActionFailure(){var failure=new IllegalStateException("query failed");bound(()->assertThatThrownBy(()->JdbcNetworkTimeout.execute(source,40_000,()->{throw failure;})).isSameAs(failure));assertThat(timeout).isEqualTo(15_000);}
    @Test void preservesQueryFailureIfRestorationAlsoFails(){failRestore=true;var failure=new IllegalStateException("query failed");bound(()->assertThatThrownBy(()->JdbcNetworkTimeout.execute(source,40_000,()->{throw failure;})).isSameAs(failure));assertThat(failure.getSuppressed()).hasSize(1);}
    @Test void restorationFailureCannotLookLikeSuccess(){failRestore=true;bound(()->assertThatThrownBy(()->JdbcNetworkTimeout.execute(source,40_000,()->"ok")).isInstanceOf(DataAccessResourceFailureException.class));}
    @Test void setFailureDoesNotRunQuery(){failSet=true;bound(()->assertThatThrownBy(()->JdbcNetworkTimeout.execute(source,40_000,()->{throw new AssertionError("Must not run query");})).isInstanceOf(DataAccessResourceFailureException.class));}
    @Test void unlimitedOrLongerTimeoutIsNotShortened(){for(int original:List.of(0,60_000)){timeout=original;bound(()->JdbcNetworkTimeout.execute(source,40_000,()->{assertThat(timeout).isEqualTo(original);return true;}));}assertThat(changes).isEmpty();}
    @Test void scopeRequiresTransaction(){assertThatThrownBy(()->JdbcNetworkTimeout.execute(source,40_000,()->true)).isInstanceOf(IllegalStateException.class);}
}
