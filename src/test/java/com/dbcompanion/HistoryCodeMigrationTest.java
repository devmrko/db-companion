package com.dbcompanion;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.MetadataHistoryService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class HistoryCodeMigrationTest {
    final Target target = new Target("APP", "T", null);
    final ViewMetadataServiceTest fixture = new ViewMetadataServiceTest();
    final List<String> writes = new ArrayList<>();
    String body = HistorySql.auditBodyV2(target).sql(), login = "APP";
    Set<String> privileges = Set.of("CREATE PROCEDURE");
    List<String> blockers = List.of();
    boolean validationFails, verificationFails;
    int sharedChecks;
    final JdbcTemplate jdbc = new JdbcTemplate() {
        @Override public <T> T queryForObject(String sql, Class<T> type) { return type.cast(login); }
        @Override public <T> List<T> queryForList(String sql, Class<T> type) { return privileges.stream().map(type::cast).toList(); }
    };
    final MetadataHistoryRepository repository = new MetadataHistoryRepository(jdbc) {
        @Override public String source(HistorySql.Asset asset) { return body; }
        @Override public void validateSharedAuditAssets(Target t) {
            sharedChecks++;
            if(validationFails)throw new MetadataEditException(409,"Invalid shared assets","invalid shared assets");
        }
        @Override public List<String> auditUpgradeBlockers(Target t) { return blockers; }
        @Override public void execute(String sql) { writes.add(sql); body = HistorySql.auditBody(target,false).sql(); }
        @Override public void validateAsset(Target t, HistorySql.Asset asset) {
            if(verificationFails)throw new MetadataEditException(409,"Verification failed","verification failed");
            assertThat(HistorySql.sourceMatches(asset,body)).isTrue();
        }
        @Override public State state(Target t) { return new State(false,false,true,"uninstalled","B","A",null,false,""); }
    };
    final HistoryReadinessRepository readiness = new HistoryReadinessRepository(jdbc,repository) {
        @Override public Preparation prepare(Target t, boolean enabling, boolean validate) {
            // State refresh after the package update; selected table still has no triggers.
            return new Preparation(new Configuration(true,false,false,null),login,
                    List.of(HistorySql.trigger(t,login,true),HistorySql.trigger(t,login,false)),null,
                    new HistoryPermissions.Decision(false,List.of()),false,List.of(),false);
        }
    };
    final MetadataHistoryService service = new MetadataHistoryService(fixture.source,fixture.metadata,repository,readiness);

    @Test void v1AndV2UpdateOnlySharedBodyEvenWhenSelectedTableHasNoTriggersOrTriggerPrivilege() {
        for(boolean v1:List.of(true,false))try(var session=new OntologyReadCacheTest().session()) {
            writes.clear(); sharedChecks=0; body=(v1?HistorySql.auditBody(target,true):HistorySql.auditBodyV2(target)).sql();
            var old=repository.state(target);session.metadata().rememberHistoryState("APP","OTHER_TABLE",old);
            var state=service.upgradeAudit(session,target);
            assertThat(state.installed()).isFalse();assertThat(state.enabled()).isFalse();
            assertThat(writes).containsExactly(HistorySql.replaceAuditBody(target));
            assertThat(sharedChecks).isEqualTo(2);
            assertThat(session.metadata().historyState("APP","OTHER_TABLE",false,()->old.withAccess(true,"fresh")).canManage()).isTrue();
        }
    }
    @Test void newerMissingAndModifiedBodiesAreNeverOverwritten() {
        for(String source:List.of(HistorySql.auditBody(target,false).sql(),"",HistorySql.auditBodyV2(target).sql().replace("v_count <> 1","v_count < 0"))) {
            body=source;
            try(var session=new OntologyReadCacheTest().session()) {
                assertThatThrownBy(()->service.upgradeAudit(session,target)).isInstanceOf(MetadataEditException.class);
            }
        }
        assertThat(writes).isEmpty();
    }
    @Test void onTrackingActiveOrAdditionalDependenciesAndInvalidAssetsBlockBeforeAnyDdl() {
        for(String reason:List.of("history ON","dependent trigger enabled","additional dependency","dependency visibility unavailable")) {
            blockers=List.of(reason);
            assertThat(readiness.prepareAuditUpgrade(target).allowed()).isFalse();
            try(var session=new OntologyReadCacheTest().session()){assertThatThrownBy(()->service.upgradeAudit(session,target)).isInstanceOf(MetadataEditException.class);}
        }
        blockers=List.of();validationFails=true;
        assertThat(readiness.prepareAuditUpgrade(target).blockers()).contains("invalid shared assets");assertThat(writes).isEmpty();
    }
    @Test void crossSchemaUpdateRequiresCreateAnyProcedureButNotTriggerPrivileges() {
        login="ADMIN";
        assertThat(readiness.prepareAuditUpgrade(target).allowed()).isFalse();
        privileges=Set.of("CREATE ANY PROCEDURE");
        assertThat(readiness.prepareAuditUpgrade(target).allowed()).isTrue();
        privileges=Set.of("ADMINISTER DATABASE TRIGGER","CREATE ANY TRIGGER");
        assertThat(readiness.prepareAuditUpgrade(target).allowed()).isFalse();
    }
    @Test void verificationFailureReportsUncertaintyWithoutRetryOrTriggerMutation() {
        verificationFails=true;
        try(var session=new OntologyReadCacheTest().session()) {
            assertThatThrownBy(()->service.upgradeAudit(session,target)).isInstanceOfSatisfying(MetadataEditException.class,
                    e->assertThat(e.userMessage()).contains("ON/OFF"));
        }
        assertThat(writes).containsExactly(HistorySql.replaceAuditBody(target));
    }
    @Test void changedPreflightNeverWrites() {
        var changing=new HistoryReadinessRepository(jdbc,repository) {
            int reads;
            @Override public AuditUpgrade prepareAuditUpgrade(Target t) {
                return new AuditUpgrade(++reads==1?HistorySql.CodeVersion.V1:HistorySql.CodeVersion.V2,true,true,List.of());
            }
        };
        try(var session=new OntologyReadCacheTest().session()) {
            var s=new MetadataHistoryService(fixture.source,fixture.metadata,repository,changing);
            assertThatThrownBy(()->s.upgradeAudit(session,target)).isInstanceOf(MetadataEditException.class);
        }
        assertThat(writes).isEmpty();
    }
    @Test void wrongSelectedSchemaNeverReachesPreflight() {
        try(var session=new OntologyReadCacheTest().session()) {
            assertThatThrownBy(()->service.upgradeAudit(session,new Target("OTHER","T",null))).isInstanceOf(MetadataEditException.class);
        }
        assertThat(sharedChecks).isZero();assertThat(writes).isEmpty();
    }
    @Test void sharedAssetValidationNeverRequiresSelectedTriggers() {
        var checked=new ArrayList<String>();
        var r=new MetadataHistoryRepository(jdbc) {
            @Override public void validateKnownAuditBody(Target t,boolean valid){assertThat(valid).isTrue();checked.add("PACKAGE BODY");}
            @Override public void validateAsset(Target t,HistorySql.Asset asset){checked.add(asset.type());}
        };
        r.validateSharedAuditAssets(target);
        assertThat(checked).containsExactly("TABLE","TABLE","INDEX","PACKAGE","PACKAGE BODY");
    }
    @Test void allCodeVersionsAreIdentifiedByFullSourceNotJustAMarker() {
        for(var version:List.of(HistorySql.CodeVersion.V1,HistorySql.CodeVersion.V2,HistorySql.CodeVersion.V3)) {
            var audit=switch(version){case V1->HistorySql.auditBody(target,true);case V2->HistorySql.auditBodyV2(target);default->HistorySql.auditBody(target,false);};
            assertThat(HistorySql.auditVersion(target,audit.sql())).isEqualTo(version);
            assertThat(HistorySql.auditVersion(target,audit.sql().replace("v_count <> 1","v_count < 0"))).isEqualTo(HistorySql.CodeVersion.UNKNOWN);
            for(boolean before:List.of(true,false)) {
                var trigger=switch(version){case V1->HistorySql.legacyTrigger(target,"APP",before);case V2->HistorySql.tableTriggerV2(target,"APP",before);default->HistorySql.trigger(target,"APP",before);};
                assertThat(HistorySql.triggerCodeVersion(target,"APP",before,trigger.sql())).isEqualTo(version);
            }
        }
        assertThat(HistorySql.auditVersion(target,"")).isEqualTo(HistorySql.CodeVersion.MISSING);
        assertThat(HistorySql.triggerCodeVersion(target,"APP",true,"")).isEqualTo(HistorySql.CodeVersion.MISSING);
    }
    @Test void dependencyCatalogFailureIsBlockedWithoutAnyWrite() {
        var r=new MetadataHistoryRepository(jdbc) {
            @Override public String source(HistorySql.Asset asset){return body;}
            @Override public void validateSharedAuditAssets(Target t){}
            @Override public List<String> auditUpgradeBlockers(Target t){throw new org.springframework.dao.PermissionDeniedDataAccessException("denied",null);}
        };
        var plan=new HistoryReadinessRepository(jdbc,r).prepareAuditUpgrade(target);
        assertThat(plan.allowed()).isFalse();assertThat(plan.blockers()).isNotEmpty();assertThat(writes).isEmpty();
    }
    @Test void triggerOnlyUpdateSupportsBothOldVersionsAndDoesNotReplaceThePackage() {
        for(boolean v1:List.of(true,false)) {
            var triggers=new HashMap<String,String>();var sqls=new ArrayList<String>();
            for(boolean before:List.of(true,false))triggers.put(HistorySql.triggerName(target,before),(v1?HistorySql.legacyTrigger(target,"APP",before):HistorySql.tableTriggerV2(target,"APP",before)).sql());
            var triggerJdbc=new JdbcTemplate() {
                @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args){return List.of(type.cast("DISABLED"));}
            };
            var r=new MetadataHistoryRepository(triggerJdbc) {
                @Override public String source(HistorySql.Asset asset){return triggers.get(asset.name());}
                @Override public void requireValid(String owner,HistorySql.Asset asset){}
                @Override public Configuration configuration(Target t){return new Configuration(true,false,false,"APP");}
                @Override public State state(Target t){return new State(true,false,true,"OFF","B","A","APP",true,"");}
                @Override public void execute(String sql){
                    sqls.add(sql);
                    for(boolean before:List.of(true,false))if(sql.equals(HistorySql.replaceTrigger(target,"APP",before)))triggers.put(HistorySql.triggerName(target,before),HistorySql.trigger(target,"APP",before).sql());
                }
            };
            var ready=new HistoryReadinessRepository(triggerJdbc,r) {
                @Override public Preparation prepare(Target t,boolean enabling,boolean validate){return new Preparation(r.configuration(t),"APP",List.of(),null,new HistoryPermissions.Decision(true,List.of()),false,r.legacyTriggers(t,"APP"),false);}
            };
            try(var session=new OntologyReadCacheTest().session()) {
                assertThat(new MetadataHistoryService(fixture.source,fixture.metadata,r,ready).upgradeCode(session,target).enabled()).isFalse();
            }
            assertThat(sqls).containsExactly(HistorySql.replaceTrigger(target,"APP",false),HistorySql.replaceTrigger(target,"APP",true));
        }
    }
}
