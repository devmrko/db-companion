package com.dbcompanion;

import com.dbcompanion.common.db.HistoryPermissions;
import com.dbcompanion.common.db.HistorySql;
import com.dbcompanion.common.i18n.UiNotice;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory.*;
import com.dbcompanion.repository.HistoryReadinessRepository;
import com.dbcompanion.service.MetadataHistoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class HistorySetupGuideTest {
    final HistoryCodeMigrationTest f = new HistoryCodeMigrationTest();
    boolean allowed, audit, legacy, fail;
    int inspections;
    State state = new State(false,false,true,"not installed","B","A",null,false,"");
    State read() {
        var repository = new com.dbcompanion.repository.MetadataHistoryRepository(new JdbcTemplate()) {
            @Override public State state(Target target) { return state; }
        };
        var readiness = new HistoryReadinessRepository(new JdbcTemplate(),repository) {
            @Override public Preparation prepare(Target target,boolean enabling,boolean validate) {
                inspections++;
                if(fail)throw new org.springframework.dao.PermissionDeniedDataAccessException("synthetic denied",null);
                return new Preparation(new Configuration(true,false,state.enabled(),null),"APP",List.of(),null,
                    new HistoryPermissions.Decision(allowed,allowed?List.of():List.of(UiNotice.raw("ADMINISTER DATABASE TRIGGER"))),
                    false,legacy?List.of(HistorySql.legacyTrigger(target,"APP",true)):List.of(),audit);
            }
            @Override public AuditUpgrade prepareAuditUpgrade(Target target) { throw new AssertionError("No extra schema-wide inspection on page load"); }
        };
        try(var session=new OntologyReadCacheTest().session()) {
            return new MetadataHistoryService(f.fixture.source,f.fixture.metadata,repository,readiness).state(session,f.target,true);
        }
    }
    @Test void oldPackageDoesNotHideMissingPrivileges() {
        audit=true;
        var result=read();
        assertThat(result.setup()).isEqualTo(new Setup(SetupStep.AUDIT_UPDATE,SetupAccess.REQUIRED));
        assertThat(result.canManage()).isFalse();
        assertThat(result.managementMessage()).contains("패키지","ADMINISTER DATABASE TRIGGER");
        assertThat(inspections).isEqualTo(1);
    }
    @Test void adminWithOldPackageNeedsAnUpdateNotAnotherAdministratorLogin() {
        allowed=true;audit=true;
        var result=read();
        assertThat(result.setup()).isEqualTo(new Setup(SetupStep.AUDIT_UPDATE,SetupAccess.ALLOWED));
        assertThat(result.canManage()).isFalse();
    }
    @Test void unknownPermissionStateIsNotClaimedAsConfirmedMissingPrivilege() {
        fail=true;
        assertThat(read().setup()).isEqualTo(new Setup(SetupStep.INSPECT,SetupAccess.UNCONFIRMED));
    }
    @Test void knownLegacyTriggerHasItsOwnNextStepAndKeepsThePrivilegeReason() {
        legacy=true;
        var result=read();
        assertThat(result.setup().nextStep()).isEqualTo(SetupStep.TRIGGER_UPDATE);
        assertThat(result.managementMessage()).contains("트리거","ADMINISTER DATABASE TRIGGER");
    }
    @Test void newOffAndActiveInstallationsHaveDistinctGuidance() {
        allowed=true;
        assertThat(read().setup().nextStep()).isEqualTo(SetupStep.INSTALL);
        state=new State(true,false,true,"OFF","B","A","APP",false,"");
        assertThat(read().setup().nextStep()).isEqualTo(SetupStep.ENABLE);
        state=new State(true,true,true,"ON","B","A","APP",false,"");allowed=false;
        var active=read();assertThat(active.enabled()).isTrue();
        assertThat(active.setup()).isEqualTo(new Setup(SetupStep.NONE,SetupAccess.REQUIRED));
    }
    @Test void structuredGuidanceIsSerializedWithoutNoticeInternalsAndSurvivesAccessUpdates() {
        audit=true;
        var result=read().withAccess(false,UiNotice.raw("reason"));
        var json=new JsonMapper().valueToTree(result);
        assertThat(json.path("setup").path("nextStep").asString()).isEqualTo("AUDIT_UPDATE");
        assertThat(json.has("managementNotice")).isFalse();assertThat(json.has("messageNotice")).isFalse();
    }
}
