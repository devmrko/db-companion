package com.dbcompanion;

import com.dbcompanion.common.db.HistoryPermissions;
import com.dbcompanion.common.db.HistoryPermissions.Access;
import com.dbcompanion.common.db.HistorySql;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.repository.MetadataHistoryRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryPermissionsTest {
    private final Target target = new Target("APP", "T", null);
    private static final String DATABASE_TRIGGER = "ADMINISTER DATABASE TRIGGER";

    @Test void adminNameAloneGrantsNothing() {
        var access = new Access("ADMIN", Set.of(), Set.of(), Set.of(), false, null);
        assertThat(HistoryPermissions.evaluate(target, "ADMIN", access, HistorySql.assets(target, "ADMIN"), true, true).allowed()).isFalse();
    }
    @Test void ownerCanInstallWithDirectPrivilegeWithoutAnyPrivileges() {
        var access = new Access("APP", Set.of(DATABASE_TRIGGER, "CREATE TABLE", "CREATE PROCEDURE", "CREATE TRIGGER"), Set.of(DATABASE_TRIGGER), Set.of(), true, null);
        assertThat(HistoryPermissions.evaluate(target, "APP", access, HistorySql.assets(target, "APP"), false, true).missing()).isEmpty();
    }
    @Test void roleOnlyDatabaseTriggerPrivilegeDoesNotPassOwnerCheck() {
        var access = new Access("APP", Set.of(DATABASE_TRIGGER), Set.of(), Set.of(), true, null);
        assertThat(HistoryPermissions.evaluate(target, "APP", access, List.of(), false, true).missing()).contains("APP 직접 권한: ADMINISTER DATABASE TRIGGER");
    }
    @Test void anotherInstallerUsesOwnTriggersAndReusesExistingBaseAssets() {
        var access = new Access("INSTALLER", Set.of(DATABASE_TRIGGER, "CREATE TRIGGER"), Set.of(DATABASE_TRIGGER), Set.of("INSERT", "UPDATE", "ALTER"), true, null);
        var triggers = HistorySql.assets(target, "INSTALLER").stream().filter(a -> a.type().equals("TRIGGER")).toList();
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, triggers, true, true).missing()).isEmpty();
    }
    @Test void crossSchemaCallRequiresDirectExecute() {
        var access = new Access("INSTALLER", Set.of(DATABASE_TRIGGER), Set.of(DATABASE_TRIGGER), Set.of("INSERT", "UPDATE"), false, null);
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, true).missing()).contains("INSTALLER 직접 권한: APP.DBC_METADATA_AUDIT EXECUTE");
    }
    @Test void offNeedsNoCreateInsertOrOwnerDirectQuery() {
        var access = new Access("OPERATOR", Set.of(DATABASE_TRIGGER, "ALTER ANY TRIGGER"), null, Set.of("UPDATE"), false, "unavailable");
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, HistorySql.assets(target, "INSTALLER"), true, false).missing()).isEmpty();
    }
    @Test void tableOwnerCannotControlAnotherUsersTriggersWithoutPrivilege() {
        var access = new Access("APP", Set.of(DATABASE_TRIGGER), Set.of(DATABASE_TRIGGER), Set.of(), true, null);
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, false).missing()).containsExactly("ALTER ANY TRIGGER");
    }
    @Test void unobservableDirectPrivilegesFailClosedOnEnable() {
        var access = new Access("OPERATOR", Set.of(DATABASE_TRIGGER, "ALTER ANY TRIGGER"), null, Set.of("INSERT", "UPDATE"), true, "ORA-00942");
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, true).missing()).contains("트리거 소유자 직접 권한 조회 불가: ORA-00942");
    }
    @Test void ownerResolutionRetainsRecordedOwnerAndAdoptsSingleLegacyOwner() {
        assertThat(MetadataHistoryRepository.resolveOwner("INSTALLER", List.of())).isEqualTo("INSTALLER");
        assertThat(MetadataHistoryRepository.resolveOwner("INSTALLER", List.of("INSTALLER"))).isEqualTo("INSTALLER");
        assertThat(MetadataHistoryRepository.resolveOwner(null, List.of("APP"))).isEqualTo("APP");
        assertThat(MetadataHistoryRepository.resolveOwner(null, List.of())).isNull();
        assertThatThrownBy(() -> MetadataHistoryRepository.resolveOwner("INSTALLER", List.of("APP")))
                .hasMessage("Conflicting history trigger owners");
        assertThatThrownBy(() -> MetadataHistoryRepository.resolveOwner(null, List.of("ONE", "TWO")))
                .hasMessage("Conflicting history trigger owners");
    }
    @Test void trackingUpgradeAcceptsOnlyKnownLegacyOrOwnerColumnLayout() {
        var legacy = List.of("TABLE_NAME:VARCHAR2:128:N:NO", "ENABLED:CHAR:1:N:NO", "CHANGED_AT:TIMESTAMP(6) WITH TIME ZONE:0:N:NO", "CHANGED_BY:VARCHAR2:128:N:NO");
        var upgraded = new ArrayList<>(legacy); upgraded.add("TRIGGER_OWNER:VARCHAR2:128:Y:NO");
        assertThat(MetadataHistoryRepository.compatibleColumns("DBC_METADATA_TRACKING", legacy)).isTrue();
        assertThat(MetadataHistoryRepository.compatibleColumns("DBC_METADATA_TRACKING", upgraded)).isTrue();
        upgraded.set(4, "TRIGGER_OWNER:VARCHAR2:128:N:NO");
        assertThat(MetadataHistoryRepository.compatibleColumns("DBC_METADATA_TRACKING", upgraded)).isFalse();
        assertThat(MetadataHistoryRepository.compatibleColumns("OTHER", legacy)).isFalse();
    }
    @Test void crossSchemaRecompileNeedsAlterAnyProcedureOnlyWhenRequired() {
        var privileges = new HashSet<>(Set.of(DATABASE_TRIGGER));
        var access = new Access("INSTALLER", privileges, Set.of(DATABASE_TRIGGER), Set.of("INSERT", "UPDATE"), true, null);
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, true, true).missing()).containsExactly("ALTER ANY PROCEDURE");
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, true, false).allowed()).isTrue();
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, false, true).allowed()).isTrue();
        privileges.add("ALTER ANY PROCEDURE");
        assertThat(HistoryPermissions.evaluate(target, "INSTALLER", access, List.of(), false, true, true).allowed()).isTrue();
    }
    @Test void ownerRecompileDoesNotRequireAnyPrivilege() {
        var access = new Access("APP", Set.of(DATABASE_TRIGGER), Set.of(DATABASE_TRIGGER), Set.of(), true, null);
        assertThat(HistoryPermissions.evaluate(target, "APP", access, List.of(), false, true, true).allowed()).isTrue();
    }
}
