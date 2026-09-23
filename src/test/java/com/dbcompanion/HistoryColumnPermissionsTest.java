package com.dbcompanion;

import com.dbcompanion.common.db.HistoryPermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HistoryColumnPermissionsTest {
    @Test void columnLookupRequiresOwnerOrDirectReadPrivileges() {
        assertThat(HistoryPermissions.canReadTargetColumns("APP", "APP", Set.of(), Set.of())).isTrue();
        assertThat(HistoryPermissions.canReadTargetColumns("APP", "INSTALLER", Set.of("SELECT ANY TABLE"), Set.of())).isTrue();
        assertThat(HistoryPermissions.canReadTargetColumns("APP", "INSTALLER", Set.of(), Set.of("READ"))).isTrue();
        assertThat(HistoryPermissions.canReadTargetColumns("APP", "INSTALLER", Set.of("EXECUTE ANY PROCEDURE"), Set.of())).isFalse();
        assertThat(HistoryPermissions.canReadTargetColumns("APP", "INSTALLER", null, Set.of())).isFalse();
    }
}
