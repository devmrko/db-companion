package com.dbcompanion;

import com.dbcompanion.model.DdsManagement;
import com.dbcompanion.service.DdsRuntimeProbeService;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import oracle.jdbc.EndUserSecurityContext;
import oracle.jdbc.OracleConnection;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DdsRuntimeProbeTest {
    static final String TOKEN="eyJhbGciOiJSUzI1NiJ9."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":4102444800}".getBytes(java.nio.charset.StandardCharsets.UTF_8))+".c3ludGhldGlj";
    static DdsRuntimeProbeService.Request request(){return new DdsRuntimeProbeService.Request("APP_POOL","SyntheticOnly9!",TOKEN,"NAME",
            new DdsRuntimeProbeService.Principal("PERSON_A",""),new DdsRuntimeProbeService.Principal("PERSON_B",""),"T","","","",true);}
    @Test void rejectsAdminAndSameIdentityAndRedactsSecrets(){
        var r=request();DdsRuntimeProbeService.validate(r);
        assertThat(r.toString()).doesNotContain(TOKEN,r.poolPassword());
        assertThatThrownBy(()->DdsRuntimeProbeService.validate(new DdsRuntimeProbeService.Request("ADMIN",r.poolPassword(),TOKEN,"NAME",r.a(),r.b(),"T","","","",true))).hasMessage("runtimePoolUser");
        assertThatThrownBy(()->DdsRuntimeProbeService.validate(new DdsRuntimeProbeService.Request("APP_POOL",r.poolPassword(),TOKEN,"NAME",r.a(),r.a(),"T","","","",true))).hasMessage("runtimeDifferentUsers");
    }
    @Test void verifiesAbaAndClearsBeforeNextIdentity()throws Exception{
        var f=new Fixture();var result=DdsRuntimeProbeService.observe(f.connection,request(),"\"ADMIN\".\"T\"");
        assertThat(result.get("contextRestored")).isEqualTo(true);
        assertThat(f.users).containsExactly("PERSON_A","PERSON_B","PERSON_A");
        assertThat(f.queries).isEqualTo(3);assertThat(f.clears).isEqualTo(4);assertThat(f.current).isNull();
    }
    @Test void identityMismatchDoesNotReadAndCleanupFailureStopsReuse()throws Exception{
        var f=new Fixture();f.mismatch=true;var result=DdsRuntimeProbeService.observe(f.connection,request(),"\"ADMIN\".\"T\"");
        assertThat(result.toString()).contains("IDENTITY_MISMATCH");assertThat(f.queries).isZero();assertThat(f.users).hasSize(1);
        var broken=new Fixture();broken.failClear=true;
        assertThatThrownBy(()->DdsRuntimeProbeService.observe(broken.connection,request(),"\"ADMIN\".\"T\"")).isInstanceOf(SQLException.class);
        assertThat(broken.users).hasSize(1);
    }
    @Test void applicationMappingIsBoundedAndVerified(){
        var d=new DdsManagement.Draft("ADMIN","APPLICATION","CREATE","TEST_APP","","","","","",false,"IAM_OAUTH_CLIENT_ID=synthetic-id");
        var before=new DdsManagement.State(List.of(),List.of(),List.of(),Set.of("CREATE APPLICATION IDENTITY"));
        assertThat(DdsManagement.sql(d,before,"ADMIN")).isEqualTo("CREATE APPLICATION IDENTITY \"TEST_APP\" MAPPED TO 'IAM_OAUTH_CLIENT_ID=synthetic-id'");
        var after=new DdsManagement.State(List.of(),List.of(),List.of(),before.privileges(),List.of(Map.of("APPLICATION_NAME","TEST_APP","MAPPED_TO","iam_oauth_client_id=synthetic-id")));
        DdsManagement.verify(d,after);
        assertThatThrownBy(()->DdsManagement.sql(d,after,"ADMIN")).hasMessage("exists");
        assertThatThrownBy(()->DdsManagement.applicationMapping("IAM_OAUTH_CLIENT_ID=x'; DROP USER X")).isInstanceOf(DdsManagement.Failure.class);
    }
    @Test void memoryIdsAreBoundAndResultDoesNotClaimRecall()throws Exception{
        var f=new Fixture();var r=request();
        var memory=new DdsRuntimeProbeService.Request(r.poolUser(),r.poolPassword(),r.databaseToken(),r.mode(),r.a(),r.b(),"","MEMORY_TEST","A' OR 1=1 --","B",true);
        DdsRuntimeProbeService.validate(memory);
        var result=DdsRuntimeProbeService.observe(f.connection,memory,"\"ADMIN\".\"MEMORY_TEST_MEMORY\"");
        assertThat(f.bindings).containsExactly("A' OR 1=1 --","B","A' OR 1=1 --","B","A' OR 1=1 --","B");
        assertThat(f.sql).noneMatch(s->s.contains("OR 1=1"));
        assertThat(result).containsEntry("memoryScope","RECORD_ACCESS_ONLY");
    }
    @Test void deniedReadStopsAndClearsWithoutExposingDriverMessage()throws Exception{
        var f=new Fixture();f.denyRead=true;var result=DdsRuntimeProbeService.observe(f.connection,request(),"\"ADMIN\".\"T\"");
        assertThat(result.toString()).contains("ORA-00942","REJECTED_OR_FAILED").doesNotContain("private driver text");
        assertThat(f.users).hasSize(1);assertThat(f.current).isNull();
    }
    @Test void missingOauthSetupStopsBeforeOpeningRuntimePool(){
        try(var f=new DdsIdentityProbeTest.Fixture()){
            var management=new com.dbcompanion.service.DdsManagementService(null,null){
                @Override public com.dbcompanion.model.DdsIdentity.State identities(com.dbcompanion.common.db.PoolSession session){return DdsIdentityTest.state(true,false);}
                @Override public Map<String,String> runtimeReadiness(com.dbcompanion.common.db.PoolSession session){return Map.of("provider","OCI_IAM","oauthConfig","MISSING","signingCredential","MISSING");}
            };
            var service=new DdsRuntimeProbeService(management,f.factory);
            assertThatThrownBy(()->service.probe(f.session,request())).hasMessage("runtimeOAuthMissing");assertThat(f.opens).isZero();
        }
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> c,java.lang.reflect.InvocationHandler h){return (T)Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},h);}
    static class Fixture {
        String current;boolean mismatch,failClear,denyRead;int clears,queries;List<String> users=new ArrayList<>(),bindings=new ArrayList<>(),sql=new ArrayList<>();
        ResultSet rows(String value,long number){boolean[] first={true};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next"->{boolean n=first[0];first[0]=false;yield n;}case "getString"->value;case "getLong"->number;default->null;
        });}
        final Statement statement=proxy(Statement.class,(p,m,a)->switch(m.getName()){
            case "execute"->false;case "executeQuery"->rows(current==null?null:mismatch?"OTHER":current,0);default->null;
        });
        final PreparedStatement query=proxy(PreparedStatement.class,(p,m,a)->{
            if(m.getName().equals("setString"))bindings.add((String)a[1]);
            if(m.getName().equals("executeQuery")){queries++;if(denyRead)throw new SQLException("private driver text","",942);return rows("",current.equals("PERSON_A")?1:2);}return null;
        });
        final OracleConnection connection=proxy(OracleConnection.class,(p,m,a)->switch(m.getName()){
            case "unwrap"->p;case "createStatement"->statement;case "prepareStatement"->{sql.add((String)a[0]);yield query;}
            case "setEndUserSecurityContext"->{current=((EndUserSecurityContext)a[0]).endUserName().orElseThrow();users.add(current);yield null;}
            case "clearEndUserSecurityContext"->{if(failClear&&clears>0)throw new SQLException("cleanup failed");clears++;current=null;yield null;}
            default->null;
        });
    }
}
