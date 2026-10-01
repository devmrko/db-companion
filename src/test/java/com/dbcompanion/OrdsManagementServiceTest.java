package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.OrdsManagement.*;
import com.dbcompanion.repository.OrdsManagementRepository;
import com.dbcompanion.service.OrdsManagementService;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.dbcompanion.OrdsManagementTest.*;

/** Synthetic JDBC transactions only: never opens a real database connection. */
class OrdsManagementServiceTest {
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    static Object empty(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    static final class Fixture implements AutoCloseable {
        Snapshot before=snapshot(catalog()),after=before;
        int calls,commits,rollbacks,borrows;boolean executed,fail;
        final List<String> sql=new ArrayList<>();final List<Map<Integer,String>> bound=new ArrayList<>();final List<Integer> isolation=new ArrayList<>();
        final SessionDataSource source=new SessionDataSource(){@Override public Connection getConnection(){borrows++;return connection();}};
        final OrdsManagementRepository repository=new OrdsManagementRepository(new JdbcTemplate(source)){
            @Override public Snapshot snapshot(){return executed?after:before;}
        };
        final OrdsManagementService service=new OrdsManagementService(source,repository);
        final PoolSession session=new PoolSession(new HikariDataSource(),"SYNTHETIC",()->{});
        Fixture(){session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP","OTHER")));}
        Connection connection(){return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->true;case "getTransactionIsolation"->Connection.TRANSACTION_READ_COMMITTED;
            case "setTransactionIsolation"->{isolation.add((Integer)a[0]);yield null;}
            case "commit"->{commits++;yield null;}case "rollback"->{rollbacks++;yield null;}
            case "prepareCall"->call((String)a[0]);case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);default->empty(m.getReturnType());
        });}
        CallableStatement call(String text){
            sql.add(text);var values=new TreeMap<Integer,String>();bound.add(values);
            return proxy(CallableStatement.class,(p,m,a)->switch(m.getName()){
                case "setString"->{values.put((Integer)a[0],(String)a[1]);yield null;}
                case "setCharacterStream"->{var reader=(java.io.Reader)a[1];var out=new StringBuilder();char[] chunk=new char[1024];int n;while((n=reader.read(chunk))!=-1)out.append(chunk,0,n);values.put((Integer)a[0],out.toString());yield null;}
                case "execute"->{calls++;if(fail)throw new SQLException("synthetic execution failure","99999",1031);executed=true;yield false;}
                default->empty(m.getReturnType());
            });
        }
        Preview preview(Kind kind,Action action,Map<String,String> values){return service.preview(session,input(before,kind,action,values));}
        void apply(Preview p){assertThat(service.apply(session,new Apply("APP",p.token(),p.confirmation()))).containsEntry("status","APPLIED");}
        @Override public void close(){session.close();}
    }
    @Test void createUpdateDeleteEveryLevelThroughTheRealTransactionAndBindingPath(){
        for(Kind kind:Kind.values())for(Action action:List.of(Action.CREATE,Action.UPDATE,Action.DELETE))try(var f=new Fixture()){
            var before=catalog();
            // Create into absent targets; template redefinition is supported only without handlers.
            if(kind==Kind.TEMPLATE||kind==Kind.MODULE||kind==Kind.SCHEMA){before.get("PARAMETERS").clear();before.get("HANDLERS").clear();}
            if(kind==Kind.MODULE||kind==Kind.SCHEMA)before.get("TEMPLATES").clear();
            if(kind==Kind.SCHEMA)before.get("MODULES").clear();
            String table=switch(kind){case SCHEMA->"SCHEMAS";case MODULE->"MODULES";case TEMPLATE->"TEMPLATES";case HANDLER->"HANDLERS";};
            if(action==Action.CREATE){before.get(table).clear();if(kind==Kind.HANDLER)before.get("PARAMETERS").clear();}
            f.before=snapshot(before);var changed=copy(before);Map<String,String> values=action==Action.DELETE?Map.of():values(kind);
            if(action==Action.DELETE){changed.get(table).clear();if(kind==Kind.HANDLER)changed.get("PARAMETERS").clear();}
            else{
                if(changed.get(table).isEmpty())changed.get(table).add(new TreeMap<>(catalog().get(table).getFirst()));
                var row=changed.get(table).getFirst();
                switch(kind){
                    case SCHEMA->{row.put("STATUS","DISABLED");row.put("AUTO_REST_AUTH","ENABLED");}
                    case MODULE->row.put("URI_PREFIX","/changed/");
                    case TEMPLATE->{row.put("COMMENTS",null);row.put("ETAG_QUERY",null);}
                    case HANDLER->row.put("SOURCE",values.get("source"));
                }
            }
            f.after=snapshot(changed);var plan=f.preview(kind,action,values);int commits=f.commits;
            assertThat(f.calls).isZero();f.apply(plan);
            assertThat(f.calls).isEqualTo(plan.statements().size());assertThat(f.commits).isEqualTo(commits+1);assertThat(f.rollbacks).isZero();assertThat(f.isolation).contains(Connection.TRANSACTION_SERIALIZABLE);
            for(int i=0;i<f.sql.size();i++)assertThat(f.bound.get(i).size()).isEqualTo((int)f.sql.get(i).chars().filter(c->c=='?').count());
        }
    }
    @Test void handlerCLOBSourceAndExistingParametersAreBoundWithoutExecutingSource(){try(var f=new Fixture()){
        String source="select '"+"x".repeat(6000)+"' from dual";var values=new HashMap<>(values(Kind.HANDLER));values.put("source",source);
        var changed=copy(f.before.tables());changed.get("HANDLERS").getFirst().put("SOURCE",source);f.after=snapshot(changed);
        var plan=f.preview(Kind.HANDLER,Action.UPDATE,values);f.apply(plan);
        assertThat(f.sql).hasSize(2).allSatisfy(s->assertThat(s).doesNotContain(source));assertThat(f.bound.getFirst()).containsValue(source);assertThat(f.bound.getLast()).containsValue("X-Value");
    }}
    @Test void stalePreviewCrossSessionTamperingAndMissingConfirmationNeverWrite(){try(var f=new Fixture();var other=new Fixture()){
        var p=f.preview(Kind.MODULE,Action.PUBLISH,Map.of("status","PUBLISHED"));
        assertThatThrownBy(()->other.service.apply(other.session,new Apply("APP",p.token(),p.confirmation()))).hasMessage("expired");assertThat(other.borrows).isZero();
        assertThatThrownBy(()->f.service.apply(f.session,new Apply("APP",p.token(),"wrong"))).hasMessage("confirmationRequired");assertThat(f.calls).isZero();
        assertThatThrownBy(()->f.service.apply(f.session,new Apply("APP",p.token(),p.confirmation()))).hasMessage("expired");
        var stale=f.preview(Kind.MODULE,Action.PUBLISH,Map.of("status","PUBLISHED"));var data=copy(f.before.tables());data.get("MODULES").getFirst().put("COMMENTS","external");f.before=snapshot(data);
        assertThatThrownBy(()->f.service.apply(f.session,new Apply("APP",stale.token(),stale.confirmation()))).hasMessage("stale");assertThat(f.calls).isZero();assertThat(f.rollbacks).isEqualTo(1);
    }}
    @Test void failedWriteOrReadbackRollsBackAndTokenCannotRetry(){for(boolean jdbcFailure:List.of(true,false))try(var f=new Fixture()){
        f.fail=jdbcFailure;var p=f.preview(Kind.HANDLER,Action.UPDATE,values(Kind.HANDLER));
        assertThatThrownBy(()->f.service.apply(f.session,new Apply("APP",p.token(),p.confirmation()))).isInstanceOf(Failure.class);
        assertThat(f.rollbacks).isEqualTo(1);int calls=f.calls;
        assertThatThrownBy(()->f.service.apply(f.session,new Apply("APP",p.token(),p.confirmation()))).hasMessage("expired");assertThat(f.calls).isEqualTo(calls);
    }}
    @Test void otherOwnerAndStaleSchemaAreRejectedWithoutBorrowingConnection(){try(var f=new Fixture()){
        assertThatThrownBy(()->f.service.list(f.session,"OTHER")).hasMessage("stale");f.session.metadata().selectSchema("OTHER");
        assertThatThrownBy(()->f.service.list(f.session,"OTHER")).hasMessage("ownSchema");assertThat(f.borrows).isZero();
    }}
    static Map<String,List<Map<String,String>>> copy(Map<String,List<Map<String,String>>> data){var copy=new TreeMap<String,List<Map<String,String>>>();data.forEach((key,rows)->{var list=new ArrayList<Map<String,String>>();rows.forEach(row->list.add(new TreeMap<>(row)));copy.put(key,list);});return copy;}
}
