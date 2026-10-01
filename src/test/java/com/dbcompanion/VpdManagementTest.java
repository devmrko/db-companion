package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.model.VpdManagement.*;
import com.dbcompanion.repository.VpdManagementRepository;
import com.dbcompanion.service.VpdManagementService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class VpdManagementTest {
    static Draft draft(){return new Draft("APP","T","P","APP","SECURITY_PKG.PREDICATE",List.of("SELECT"),false,true,PolicyType.DYNAMIC,false,List.of(),false);}
    static Map<String,String> row(Draft d){
        var r=new LinkedHashMap<String,String>();r.put("OBJECT_OWNER",d.schema());r.put("OBJECT_NAME",d.table());r.put("POLICY_GROUP","SYS_DEFAULT");r.put("POLICY_NAME",d.policy());r.put("PF_OWNER",d.functionSchema());
        var parts=d.function().split("\\.");r.put("PACKAGE",parts.length==2?parts[0]:"");r.put("FUNCTION",parts[parts.length-1]);
        var fields=List.of("SEL","INS","UPD","DEL","IDX");for(int i=0;i<fields.size();i++)r.put(fields.get(i),d.statements().contains(VpdManagement.STATEMENTS.get(i))?"YES":"NO");
        r.put("CHK_OPTION",d.updateCheck()?"YES":"NO");r.put("ENABLE",d.enabled()?"YES":"NO");r.put("STATIC_POLICY","NO");r.put("POLICY_TYPE",d.policyType().name());r.put("LONG_PREDICATE",d.longPredicate()?"YES":"NO");r.put("COMMON","NO");r.put("INHERITED","NO");return r;
    }
    static Snapshot snapshot(Draft d){
        var relevant=new ArrayList<Map<String,String>>();
        if(d!=null)for(String column:d.columns())relevant.add(Map.of("OBJECT_OWNER",d.schema(),"OBJECT_NAME",d.table(),"POLICY_GROUP","SYS_DEFAULT","POLICY_NAME",d.policy(),"SEC_REL_COLUMN",column,"COLUMN_OPTION",d.allRows()?"ALL_ROWS":"NONE","COMMON","NO","INHERITED","NO"));
        return new Snapshot(List.of(Map.of("OBJECT_ID","42","LAST_DDL_TIME","2026-09-24 00:00:00","STATUS","VALID")),d==null?List.of():List.of(row(d)),
            List.of(Map.of("COLUMN_NAME","ID","COLUMN_ID","1","DATA_TYPE","NUMBER"),Map.of("COLUMN_NAME","SECRET","COLUMN_ID","2","DATA_TYPE","VARCHAR2")),relevant,List.of());
    }
    static Snapshot policyRow(Map<String,String> row){var base=snapshot(draft());return new Snapshot(base.objects(),List.of(row),base.columns(),base.relevant(),base.attributes());}
    static class FakeRepository extends VpdManagementRepository {
        Snapshot current=VpdManagementTest.snapshot(null),next;boolean allowed=true,failDuringWrite,failAfterWrite;int writes;String functionIdentity="function-v1";List<Command> executed=List.of();
        FakeRepository(SessionDataSource source){super(new JdbcTemplate(source));}
        @Override public Snapshot snapshot(String owner,String table){if(failAfterWrite&&writes>0)throw new IllegalStateException("read unavailable");return current;}
        @Override public boolean canManage(){return allowed;}
        @Override public List<Function> functions(String owner){return List.of(new Function(owner,"SECURITY_PKG.PREDICATE",functionIdentity));}
        @Override public void execute(List<Command> commands){writes++;executed=commands;if(failDuringWrite){current=VpdManagementTest.snapshot(null);throw new IllegalStateException("simulated failure after committed DROP");}current=next;}
    }
    static class Harness implements AutoCloseable {
        final SessionDataSource source=new SessionDataSource();final FakeRepository repository=new FakeRepository(source);
        final VpdManagementService service=new VpdManagementService(source,repository);
        final HikariDataSource pool=new HikariDataSource();final PoolSession session=new PoolSession(pool,"TEST",()->{});
        Harness(){session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","TEST","TEST"),List.of("APP","OTHER")));}
        Preview preview(Action action,Draft draft){return service.preview(session,action,draft,repository.current.fingerprint());}
        Receipt run(Preview p){return service.execute(session,p.token(),p.target(),true,true);}
        @Override public void close(){session.close();}
    }
    @Test void supportedPolicyRoundTripsAllSecurityRelevantSettingsAndRecoverySql(){
        var original=new Draft("APP","T","P","APP","SECURITY_PKG.PREDICATE",List.of("SELECT"),false,false,PolicyType.SHARED_STATIC,true,List.of("SECRET","ID"),true).validated();
        var policy=VpdManagementRepository.policies(snapshot(original)).getFirst();assertThat(policy.editable()).isTrue();assertThat(policy.definition()).isEqualTo(original);
        String recovery=VpdManagementRepository.add(policy.definition()).preview();
        assertThat(recovery).contains("function_schema => 'APP'","policy_function => 'SECURITY_PKG.PREDICATE'","statement_types => 'SELECT'","update_check => FALSE","enable => FALSE","policy_type => SYS.DBMS_RLS.SHARED_STATIC","long_predicate => TRUE","sec_relevant_cols => 'ID,SECRET'","sec_relevant_cols_opt => SYS.DBMS_RLS.ALL_ROWS");
    }
    @Test void namespacesGroupsInheritanceUnknownFieldsMissingFlagsAndQuotedNamesBlockEditing(){
        for(var change:List.of(Map.entry("POLICY_GROUP","CUSTOM"),Map.entry("COMMON","YES"),Map.entry("INHERITED","YES"),Map.entry("EDITION_NAME",""),Map.entry("FUTURE_FIELD",""),Map.entry("POLICY_TYPE","NEW_TYPE"),Map.entry("ENABLE",""),Map.entry("POLICY_NAME","Mixed Policy"))){
            var row=row(draft());row.put(change.getKey(),change.getValue());var policy=VpdManagementRepository.policies(policyRow(row)).getFirst();assertThat(policy.editable()).as(change.toString()).isFalse();assertThat(policy.properties()).containsEntry(change.getKey(),change.getValue());
        }
        var row=row(draft());row.remove("COMMON");assertThat(VpdManagementRepository.policies(policyRow(row)).getFirst().editable()).isFalse();
        var s=snapshot(draft());var attr=Map.of("POLICY_GROUP","SYS_DEFAULT","POLICY_NAME","P","NAMESPACE","CTX","ATTRIBUTE","TENANT");
        var withContext=new Snapshot(s.objects(),s.policies(),s.columns(),s.relevant(),List.of(attr));
        assertThat(VpdManagementRepository.policies(withContext).getFirst().editable()).isFalse();assertThat(VpdManagementRepository.policies(withContext).getFirst().attributes()).containsExactly(attr);
    }
    @Test void malformedInputCannotReachSqlAndOracleOptionConstraintsAreChecked(){
        for(String value:List.of("APP'; END; --","Mixed","A.B","A B","A\nB","A".repeat(129),""))assertThatThrownBy(()->VpdManagement.identifier(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Draft("APP","T","P","APP","PKG.F;DELETE",List.of("SELECT"),false,true,PolicyType.DYNAMIC,false,List.of(),false).validated()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Draft("APP","T","P","APP","F",List.of("INSERT"),false,true,PolicyType.DYNAMIC,false,List.of(),false).validated()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Draft("APP","T","P","APP","F",List.of("SELECT","UPDATE"),true,true,PolicyType.DYNAMIC,false,List.of("ID"),true).validated()).isInstanceOf(IllegalArgumentException.class);
        assertThat(VpdManagementRepository.add(draft()).sql()).contains("object_schema => ?","policy_function => ?").doesNotContain("SECURITY_PKG.PREDICATE","'APP'");
        assertThat(new Command("BEGIN X(?); END;",List.of("a'b")).preview()).contains("'a''b'");
    }
    @Test void confirmedAddIsVerifiedAndTokenCannotBeReused(){
        try(var h=new Harness()){
            h.repository.next=snapshot(draft());var preview=h.preview(Action.ADD,draft());assertThat(preview.gap()).isFalse();assertThat(h.run(preview).status()).isEqualTo("VERIFIED");assertThat(h.repository.writes).isEqualTo(1);
            assertThatThrownBy(()->h.run(preview)).isInstanceOf(Failure.class).hasMessage("vpd.stale");assertThat(h.repository.writes).isEqualTo(1);assertThat(h.pool.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void replaceRequiresSeparateGapConsentAndTargetThenConsumesRejectedToken(){
        try(var h=new Harness()){
            h.repository.current=snapshot(draft());var p=h.preview(Action.REPLACE,draft());assertThat(p.gap()).isTrue();assertThat(p.sql()).contains("DROP_POLICY","ADD_POLICY");
            assertThatThrownBy(()->h.service.execute(h.session,p.token(),p.target(),true,false)).hasMessage("vpd.confirmRequired");
            assertThatThrownBy(()->h.run(p)).hasMessage("vpd.stale");assertThat(h.repository.writes).isZero();
            final var wrongTarget=h.preview(Action.DELETE,draft());
            assertThatThrownBy(()->h.service.execute(h.session,wrongTarget.token(),"APP.OTHER.P",true,true)).hasMessage("vpd.confirmRequired");assertThat(h.repository.writes).isZero();
        }
    }
    @Test void staleTablePolicyFunctionAndRevokedPrivilegesRejectBeforeAnyWrite(){
        try(var h=new Harness()){
            var p=h.preview(Action.ADD,draft());h.repository.current=snapshot(draft());final var policyChanged=p;assertThatThrownBy(()->h.run(policyChanged)).hasMessage("vpd.stale");
            h.repository.current=snapshot(null);p=h.preview(Action.ADD,draft());h.repository.functionIdentity="function-v2";final var fnChanged=p;assertThatThrownBy(()->h.run(fnChanged)).hasMessage("vpd.stale");
            p=h.preview(Action.ADD,draft());h.repository.allowed=false;final var revoked=p;assertThatThrownBy(()->h.run(revoked)).hasMessage("vpd.executeRequired");assertThat(h.repository.writes).isZero();
            h.repository.allowed=true;h.session.metadata().selectSchema("OTHER");assertThatThrownBy(()->h.preview(Action.ADD,draft())).hasMessage("vpd.stale");
        }
    }
    @Test void partialReplacementReturnsOriginalRecoveryAndBlocksFurtherWritesWithoutRetry(){
        try(var h=new Harness()){
            var original=new Draft("APP","T","P","APP","SECURITY_PKG.PREDICATE",List.of("SELECT","UPDATE"),true,false,PolicyType.CONTEXT_SENSITIVE,true,List.of("SECRET"),false).validated();
            h.repository.current=snapshot(original);var p=h.preview(Action.REPLACE,draft());h.repository.failDuringWrite=true;
            var result=h.run(p);assertThat(result.status()).isEqualTo("CHECK_REQUIRED");assertThat(result.recoverySql()).isEqualTo(VpdManagementRepository.add(original).preview());
            assertThat(h.repository.executed).hasSize(2);assertThat(h.repository.writes).isEqualTo(1);
            assertThatThrownBy(()->h.preview(Action.ADD,draft())).hasMessage("vpd.blocked");
            var list=h.service.list(h.session,"APP","T");assertThat(list.status()).isEqualTo("AVAILABLE");assertThat(list.writable()).isFalse();assertThat(list.reason()).isEqualTo("vpd.blocked");
        }
    }
    @Test void deleteAndToggleHaveExactRecoveryAndMetadataVerification(){
        try(var h=new Harness()){
            h.repository.current=snapshot(draft());h.repository.next=snapshot(draft().withEnabled(false));var toggle=h.preview(Action.DISABLE,draft());assertThat(toggle.sql()).contains("ENABLE_POLICY","enable => FALSE");assertThat(toggle.recoverySql()).contains("enable => TRUE");assertThat(h.run(toggle).status()).isEqualTo("VERIFIED");
            h.repository.next=snapshot(null);var deletion=h.preview(Action.DELETE,draft());assertThat(deletion.recoverySql()).contains("enable => FALSE");assertThat(h.run(deletion).status()).isEqualTo("VERIFIED");
        }
    }
    @Test void successfulCallWithWrongOrUnreadablePostStateIsNeverClaimedSuccessful(){
        for(boolean unreadable:List.of(false,true))try(var h=new Harness()){
            h.repository.next=snapshot(null);h.repository.failAfterWrite=unreadable;var p=h.preview(Action.ADD,draft());assertThat(h.run(p).status()).isEqualTo("CHECK_REQUIRED");assertThat(h.repository.writes).isEqualTo(1);
        }
    }
    @Test void crossOwnerDeniedAndMissingExecuteGrantPreservesReadablePolicies(){
        try(var h=new Harness()){
            h.repository.current=snapshot(draft());h.repository.allowed=false;var result=h.service.list(h.session,"APP","T");assertThat(result.status()).isEqualTo("AVAILABLE");assertThat(result.policies()).hasSize(1);assertThat(result.reason()).isEqualTo("vpd.executeRequired");
            h.session.metadata().selectSchema("OTHER");var other=new Draft("OTHER","T","P","APP","F",List.of("SELECT"),false,true,PolicyType.DYNAMIC,false,List.of(),false);
            assertThatThrownBy(()->h.preview(Action.ADD,other)).hasMessage("vpd.ownerOnly");assertThat(h.repository.writes).isZero();
        }
    }
    @Test void fingerprintCoversTableIdentityColumnMetadataAndAuxiliaryContext(){
        var s=snapshot(draft());var variants=List.of(new Snapshot(List.of(Map.of("OBJECT_ID","43")),s.policies(),s.columns(),s.relevant(),s.attributes()),
            new Snapshot(s.objects(),s.policies(),List.of(Map.of("COLUMN_NAME","OTHER")),s.relevant(),s.attributes()),
            new Snapshot(s.objects(),s.policies(),s.columns(),s.relevant(),List.of(Map.of("NAMESPACE","CTX"))));
        for(var variant:variants)assertThat(variant.fingerprint()).isNotEqualTo(s.fingerprint());
        assertThat(s.fingerprint()).isEqualTo(snapshot(draft()).fingerprint());
    }
    @Test void expiredPreviewCannotExecute(){
        var view=new Preview("token",Action.ADD,"APP.T.P","sql","recovery",false,"2026-09-24T00:00:00Z");
        var pending=new Pending(view,draft(),"fingerprint","function",Instant.parse(view.expiresAt()),List.of());
        assertThatThrownBy(()->VpdManagementService.validateConfirmation(pending,"token","APP.T.P",true,false,Instant.parse(view.expiresAt()))).hasMessage("vpd.stale");
    }
    @Test void viewPolicyPreviewUsesObjectNameAndRejectsTypeChangesBeforeExecution(){
        try(var h=new Harness()){
            var base=snapshot(null);h.repository.current=new Snapshot(List.of(Map.of("OBJECT_ID","42","OBJECT_TYPE","VIEW")),base.policies(),base.columns(),base.relevant(),base.attributes());
            var preview=h.preview(Action.ADD,draft());assertThat(preview.sql()).contains("DBMS_RLS.ADD_POLICY","object_name => 'T'");
            h.repository.current=new Snapshot(List.of(Map.of("OBJECT_ID","42","OBJECT_TYPE","TABLE")),base.policies(),base.columns(),base.relevant(),base.attributes());
            assertThatThrownBy(()->h.run(preview)).hasMessage("vpd.stale");assertThat(h.repository.writes).isZero();
        }
    }
    @Test void unsuccessfulNewPreviewDiscardsEarlierServerAuthorization(){
        try(var h=new Harness()){
            var first=h.preview(Action.ADD,draft());h.repository.allowed=false;
            assertThatThrownBy(()->h.preview(Action.ADD,draft())).hasMessage("vpd.executeRequired");h.repository.allowed=true;
            assertThatThrownBy(()->h.run(first)).hasMessage("vpd.stale");assertThat(h.repository.writes).isZero();
        }
    }
}
