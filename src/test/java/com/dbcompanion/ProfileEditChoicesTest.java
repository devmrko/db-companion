package com.dbcompanion;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Source contracts, not simulated Oracle query results. */
class ProfileEditChoicesTest {
    @Test void credentialsAreOwnerScopedEnabledNamesOnlyAndBoundAtSave() throws Exception {
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/ProfileEditRepository.java"));
        assertThat(repository).contains("SELECT CREDENTIAL_NAME FROM SYS.USER_CREDENTIALS WHERE ENABLED = 'TRUE'", "AND CREDENTIAL_NAME = ?")
                .doesNotContain("DBA_CREDENTIALS","PASSWORD","PRIVATE_KEY","SELECT *");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/ProfileEditService.java"));
        assertThat(service.indexOf("requireCredential(target,request.value())")).isLessThan(service.indexOf("archive.beforeEdit"));
        int recheck=service.indexOf("requireCredential(target,request.value())",service.indexOf("if (before.unchanged())"));
        assertThat(recheck).isGreaterThan(0).isLessThan(service.indexOf("attempted.set(true)"));
    }
    @Test void objectsAreLazySchemaScopedBoundedAndDoNotReadColumns() throws Exception {
        String repository=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/ProfileEditRepository.java"));
        assertThat(repository).contains("OWNER = ?", "INSTR(UPPER(OBJECT_NAME), UPPER(?))", "FETCH FIRST 101 ROWS ONLY", "rows.size() > 100")
                .doesNotContain("ALL_TAB_COLUMNS","ALL_COL_COMMENTS");
        String service=Files.readString(Path.of("src/main/java/com/dbcompanion/service/ProfileEditService.java"));
        assertThat(service).contains("session.metadata().schemas().contains(owner)","ProfileEditPolicy.scope(","source.clear()");
    }
}
