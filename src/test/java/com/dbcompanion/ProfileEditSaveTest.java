package com.dbcompanion;

import com.dbcompanion.service.ProfileEditService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure outcome/production-source contracts; not simulated DB success or failure injection. */
class ProfileEditSaveTest {
    @Test void successRequiresBothReadbackAndCommittedHistoryWithoutCallErrors() {
        for(boolean matches:new boolean[]{false,true}) for(boolean archived:new boolean[]{false,true}) {
            var result=ProfileEditService.result(matches,archived,null,"state",null);
            assertThat(result.verified()).isEqualTo(matches&&archived);
            if(!result.verified())assertThat(result.message()).contains("입력은 유지됩니다.");
        }
    }
    @Test void apiErrorCannotBecomeSuccessEvenIfReadbackMatches() {
        var error=new RuntimeException(new SQLException("ORA-20046: Invalid attribute\nquery with a private value", "99999",20046));
        var result=ProfileEditService.result(true,true,error,"state",null);
        assertThat(result.verified()).isFalse();
        assertThat(result.message()).contains("속성 저장 호출: ORA-20046: Invalid attribute").doesNotContain("private value");
    }
    @Test void afterArchiveFailurePreservesASeparateFailureOutcome() {
        var error=new RuntimeException(new SQLException("ORA-01653: unable to extend table", "72000",1653));
        var result=ProfileEditService.result(true,false,null,"DB 저장값 확인 / 이력 보관 실패",error);
        assertThat(result.verified()).isFalse();
        assertThat(result.message()).contains("DB 저장값 확인", "후속 확인: ORA-01653", "재저장 전에");
    }
    @Test void actualServiceCommitsBackupBeforeWritingAndUsesNoAuditPrivilegesOrInstall() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/dbcompanion/service/ProfileEditService.java"));
        int original=source.indexOf("Before before=write.execute");
        int originalArchive=source.indexOf("archive.beforeEdit",original);
        int committed=source.indexOf("if (before.unchanged())",originalArchive);
        int recheck=source.indexOf("ProfileEditPolicy.verifyVersion",committed);
        int attempted=source.indexOf("attempted.set(true)",recheck);
        int save=source.indexOf("repository.save",attempted);
        int readback=source.indexOf("after=read.execute",save);
        int afterArchive=source.indexOf("archive.afterEdit",readback);
        assertThat(original).isGreaterThan(0);assertThat(originalArchive).isGreaterThan(original);
        assertThat(committed).isGreaterThan(originalArchive);assertThat(recheck).isGreaterThan(committed);
        assertThat(attempted).isGreaterThan(recheck);assertThat(save).isGreaterThan(attempted);
        assertThat(readback).isGreaterThan(save);assertThat(afterArchive).isGreaterThan(readback);
        assertThat(source).contains("if (!attempted.get()) throw ex", "source.clear()", "archive.requireArchive", "session.metadata().info().username()");
        assertThat(source).doesNotContain("auditReadable(","policyExists(","toggle(","archive.execute(","GRANT ","CREATE TABLE");
    }
    @Test void repositoryAlwaysBindsAllThreeValuesAndDoesNotLogThem() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/dbcompanion/repository/ProfileEditRepository.java"));
        assertThat(source).contains("statement.setString(1,target.profile())", "statement.setString(2,target.attribute())", "statement.setString(3,value)")
                .doesNotContain("Logger","System.out","target.profile()+","target.attribute()+");
    }
}
