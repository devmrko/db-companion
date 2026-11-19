package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.service.*;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DdsIdentityProbeTest {
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    static final class Fixture implements AutoCloseable {
        String identity="PERSON",connectedUser;int opens,queries,closes,rollbacks;boolean reject,rolesDenied;
        final List<String> statements=new ArrayList<>();
        ResultSet result(String value,long count){boolean[] first={true};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next" -> {boolean next=first[0];first[0]=false;yield next;}
            case "getString" -> value;case "getLong" -> count;default -> null;
        });}
        final Statement statement=proxy(Statement.class,(p,m,a)->{
            if(m.getName().equals("execute")){statements.add((String)a[0]);return false;}
            if(m.getName().equals("executeQuery")){
                String sql=(String)a[0];statements.add(sql);
                if(sql.contains("ORA_END_USER_CONTEXT"))return result(identity,0);
                if(sql.contains("ROLE_NAME")){if(rolesDenied)throw new SQLException("ORA-00942: private", "",942);return result("R",0);}
                queries++;if(reject)throw new SQLException("ORA-00942: private", "",942);return result("",0);
            }return null;
        });
        final Connection connection=proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "createStatement" -> statement;case "rollback" -> {rollbacks++;yield null;}
            case "close" -> {closes++;yield null;}default -> null;
        });
        final OraclePoolFactory factory=new OraclePoolFactory(null){
            @Override public PoolSession open(String user,String password,String alias,String wallet){
                opens++;connectedUser=user;return new PoolSession(new HikariDataSource(){@Override public Connection getConnection(){return connection;}},alias,()->{});
            }
        };
        final DdsManagementService management=new DdsManagementService(null,null){
            @Override public DdsIdentity.State identities(PoolSession s){return DdsIdentityTest.state(true,false);}
            @Override public DdsManagement.State state(PoolSession s){return DdsManagementTest.state(List.of());}
        };
        final DdsIdentityProbeService service=new DdsIdentityProbeService(management,factory);
        final PoolSession session=new PoolSession(new HikariDataSource(),"TEST",()->{});
        Fixture(){session.initialize(new DatabaseSession(new DatabaseInfo("ADMIN","ADMIN","TEST","TEST"),List.of("ADMIN")));}
        DdsIdentityProbeService.Request input(boolean consent){return new DdsIdentityProbeService.Request("PERSON","Synthetic_Only9!","T",consent);}
        @Override public void close(){session.close();}
    }
    @Test void mismatchedIdentityStopsBeforeTableAccessAndClosesConnection(){try(var f=new Fixture()){
        f.identity="ADMIN";assertThatThrownBy(()->f.service.probe(f.session,f.input(true))).hasMessage("contextMissing");
        assertThat(f.connectedUser).isEqualTo("PERSON");assertThat(f.queries).isZero();assertThat(f.opens).isEqualTo(1);
        assertThat(f.closes).isEqualTo(1);assertThat(f.rollbacks).isEqualTo(1);
    }}
    @Test void noConsentCannotOpenConnectionAndZeroRowsAreNotAnIsolationSuccess(){try(var f=new Fixture()){
        assertThatThrownBy(()->f.service.probe(f.session,f.input(false))).hasMessage("confirm");assertThat(f.opens).isZero();
        var r=f.service.probe(f.session,f.input(true));assertThat(r).containsEntry("visibleRows",0L).containsEntry("queryStatus","READ_COMPLETED");
        assertThat(r).doesNotContainKey("isolated");assertThat(f.statements.getFirst()).isEqualTo("SET TRANSACTION READ ONLY");
        assertThat(f.closes).isEqualTo(1);
    }}
    @Test void rejectedQueryAndUnavailableRolesAreExplicitWithoutFallback(){try(var f=new Fixture()){
        f.reject=true;f.rolesDenied=true;var r=f.service.probe(f.session,f.input(true));
        assertThat(r).containsEntry("queryStatus","REJECTED_OR_FAILED").containsEntry("queryCode","ORA-00942");
        assertThat(r.get("rolesStatus")).isEqualTo("UNVERIFIED ORA-00942");assertThat(r.toString()).doesNotContain("private");
        assertThat(f.opens).isEqualTo(1);assertThat(f.rollbacks).isEqualTo(1);assertThat(f.closes).isEqualTo(1);
    }}
}
