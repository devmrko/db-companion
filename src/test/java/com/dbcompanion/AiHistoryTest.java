package com.dbcompanion;

import com.dbcompanion.common.db.AiHistorySql;
import com.dbcompanion.common.db.ProfileHistorySql;
import com.dbcompanion.repository.AgentObjectHistoryRepository;
import com.dbcompanion.repository.TeamHistoryRepository;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiHistoryTest {
    @Test void fiveKindsShareStorageAndPreserveActorAndJson() {
        String sql=AiHistorySql.create("A\"B");
        assertThat(sql).startsWith("CREATE TABLE \"A\"\"B\".\"DBC_AI_HISTORY\"")
                .contains("'PROFILE','TEAM','AGENT','TASK','TOOL'","ACTOR VARCHAR2(128 CHAR) NOT NULL","EVENT_AT VARCHAR2(64 CHAR) NOT NULL",
                        "BEFORE_JSON CLOB","AFTER_JSON CLOB","PAYLOAD_JSON CLOB","UNIQUE (OBJECT_TYPE,SOURCE_KEY,ITEM_NO)","UNIQUE (LEGACY_TABLE,LEGACY_SEQ)");
        assertThat(TeamHistoryRepository.createSql("A")).isEqualTo(AgentObjectHistoryRepository.createSql("A"));
        assertThat(ProfileHistorySql.tables("A")).containsOnlyKeys(ProfileHistorySql.CONFIG);
    }
    @Test void legacyRequestsAndObservationsAreNotInventedBeforeAfterChanges() {
        var map=AiHistorySql.mapping("DBC_PROFILE_HISTORY");
        assertThat(map).containsEntry("OBJECT_TYPE","'PROFILE'").containsEntry("ACTOR","s.ACTOR").containsEntry("EVENT_AT","s.EVENT_AT")
                .containsEntry("ENTRY_KIND","s.KIND").containsEntry("BEFORE_JSON","TO_CLOB(NULL)")
                .containsEntry("AFTER_JSON","CASE WHEN s.KIND='SNAPSHOT' THEN s.PAYLOAD END")
                .containsEntry("PAYLOAD_JSON","CASE WHEN s.KIND<>'SNAPSHOT' THEN s.PAYLOAD END")
                .containsEntry("SOURCE_KEY","s.SOURCE_KEY").containsEntry("ITEM_NO","s.ITEM_NO");
    }
    @Test void migrationPreservesKeysAndFullClobsWithoutTruncationOrDeletingSources() {
        for(String legacy:AiHistorySql.LEGACY) {
            String copy=AiHistorySql.copy("S",legacy),check=AiHistorySql.differences("S",legacy);
            assertThat(copy).contains("INSERT INTO \"S\".\"DBC_AI_HISTORY\"","NOT EXISTS","h.LEGACY_SEQ=s.SEQ","ORDER BY s.SEQ")
                    .doesNotContain("SUBSTR","DELETE","UPDATE","DROP","TRUNCATE");
            assertThat(check).contains("LEFT JOIN","h.SEQ IS NULL","SYS.DBMS_LOB.COMPARE","DECODE(h.ACTOR,s.ACTOR,0,1)=1","IS NULL")
                    .doesNotContain("SUBSTR","STANDARD_HASH");
        }
        assertThatThrownBy(()->AiHistorySql.copy("S","UNRELATED")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void migrationValidatesAndLocksBeforeCopyAndPublishesOnlyAfterVerification() throws Exception {
        String code=read("repository/AiHistoryRepository");
        int preflight=code.indexOf("LEGACY.stream().filter"),create=code.indexOf("jdbc.execute(create"),
                lock=code.indexOf("IN EXCLUSIVE MODE NOWAIT"),copy=code.indexOf("jdbc.update(copy"),verify=code.indexOf("differences(schema,name)"),
                count=code.indexOf("Objects.equals(original,copied)"),publish=code.indexOf("+MARKER+\"'\"");
        assertThat(preflight).isPositive();assertThat(create).isGreaterThan(preflight);assertThat(lock).isGreaterThan(create);
        assertThat(copy).isGreaterThan(lock);assertThat(verify).isGreaterThan(copy);assertThat(count).isGreaterThan(verify);assertThat(publish).isGreaterThan(count);
        assertThat(code).contains("IN SHARE MODE NOWAIT","Set.of(MARKER,PENDING)","if(MARKER.equals(state))return", "setCharacterStream", "OUTCOME='BEFORE_SAVED'")
                .doesNotContain("DROP TABLE","TRUNCATE TABLE","DELETE FROM");
    }
    @Test void everyEditorPassesLoginIdentityAndLegacyReadersStayAvailable() throws Exception {
        for(String service:new String[]{"TeamEditService","AgentObjectEditService","ProfileEditService"}) {
            String code=read("service/"+service);
            assertThat(code).contains("session.metadata().info().username()","source.clear()");
            assertThat(code).doesNotContain("target.schema(),json.writeValueAsString(current)");
        }
        for(String repository:new String[]{"TeamHistoryRepository","AgentObjectHistoryRepository"})
            assertThat(read("repository/"+repository)).contains("common.legacy(schema,LEGACY)","seq.startsWith(\"L:\")","new HistoryPage(ready,");
        assertThat(read("repository/ProfileHistoryRepository")).contains("\"LEGACY\"","OBJECT_TYPE='PROFILE'","h.SOURCE_KEY=","common.before","common.after");
    }
    private static String read(String suffix)throws Exception{return Files.readString(Path.of("src/main/java/com/dbcompanion/"+suffix+".java"));}
}
