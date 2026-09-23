package com.dbcompanion;

import com.dbcompanion.model.AiMappedSql.*;
import com.dbcompanion.model.AiMappedSql;
import com.dbcompanion.repository.AiMappedSqlRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Production contracts, no fake DB or login. */
class AiMappedSqlTest {
    private static final String SQL_ID = "123456789abcd";
    private Key key() { return new Key(SQL_ID, "987654321abcd", "42", "3", "2026-09-17 12:34:56"); }
    @Test void filtersAreBoundAndMemoryListDoesNotPretendToHaveOwnerOrAiProfile() {
        String question = "한글 %_' OR 1=1 --";
        var statement = AiMappedSqlRepository.listStatement(new Query(SQL_ID, question, 2));
        assertThat(statement.args()).containsExactly(SQL_ID, question, 10);
        assertThat(statement.sql()).contains("SYS.V_$MAPPED_SQL", "REGEXP_LIKE(SQL_TEXT", "SQL_ID = ?", "INSTR(UPPER(SQL_FULLTEXT), UPPER(?))", "FETCH NEXT 11 ROWS ONLY", "NULLS LAST")
                .doesNotContain(question, SQL_ID, "MAPPED_SQL_FULLTEXT", "COUNT(*)", "JOIN", "PARSING_USER", "OWNER", "PROFILE_NAME");
        assertThat(statement.sql().chars().filter(c -> c == '?').count()).isEqualTo(statement.args().size());
        assertThat(AiMappedSqlRepository.listStatement(Query.parse("", "", "1")).args()).containsExactly(0);
    }
    @Test void invalidFiltersAndPagesAreRejectedBeforeQuery() {
        for (String id : List.of("X", "123456789ABCD", "x' OR 1=1--", "a".repeat(14)))
            assertThatThrownBy(() -> new Query(id, "", 1)).isInstanceOf(IllegalArgumentException.class);
        for (int page : new int[]{-1, 0, 1001, Integer.MAX_VALUE})
            assertThatThrownBy(() -> new Query("", "", page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("", "x".repeat(501), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Query.parse("", "", "1 OR 1=1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Query("", " abc ", 1000).offset()).isEqualTo(9990);
    }
    @Test void compositeKeyRoundTripsIncludingNullValuesAndRejectsAmbiguityOrInjection() {
        assertThat(Key.parse(key().token())).isEqualTo(key());
        var empty = new Key(SQL_ID, "-", "-", "-", "-");
        assertThat(Key.parse(empty.token())).isEqualTo(empty);
        for (String token : List.of("", "abcd'", "a".repeat(181), "x", key().token() + "="))
            assertThatThrownBy(() -> Key.parse(token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Key.parse(encode(SQL_ID + "|987654321abcd|42|3|2026-02-30 12:34:56"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Key.parse(encode(SQL_ID + "|987654321abcd|42|3|2026-09-17 12:34:56|extra"))).isInstanceOf(IllegalArgumentException.class);
        for (String number : List.of("-1", "001", "9999999999999999999", "3 OR 1=1", ""))
            assertThatThrownBy(() -> new Key(SQL_ID, "-", number, "3", "-")).isInstanceOf(IllegalArgumentException.class);
    }
    private String encode(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    @Test void detailUsesCompleteBoundIdentityFullClobsAndAtMostTwoRows() {
        var statement = AiMappedSqlRepository.detailStatement(key());
        assertThat(statement.sql()).contains("SQL_FULLTEXT, MAPPED_SQL_FULLTEXT", "FETCH FIRST 2 ROWS ONLY", "REGEXP_LIKE(SQL_TEXT", "SQL_ID = ?", "NVL(CON_ID, -1) = ?")
                .doesNotContain("SUBSTR", key().sqlId(), key().translated());
        assertThat(statement.args()).containsExactly(SQL_ID, "987654321abcd", 42L, 3L, "2026-09-17 12:34:56");
        assertThat(AiMappedSqlRepository.detailStatement(new Key(SQL_ID, "-", "-", "-", "-")).args()).containsExactly(SQL_ID, "-", -1L, -1L, "-");
        assertThat(statement.sql().chars().filter(c -> c == '?').count()).isEqualTo(statement.args().size());
    }
    @Test void disappearedAndDuplicateMappingsNeverReturnAnUnrelatedRecord() {
        var detail = new Detail(SQL_ID, null, null, null, null, null, "0", null, null, "original", "mapped");
        assertThat(AiMappedSqlRepository.unique(List.of())).isNull();
        assertThat(AiMappedSqlRepository.unique(List.of(detail))).isSameAs(detail);
        assertThatThrownBy(() -> AiMappedSqlRepository.unique(List.of(detail, detail))).hasMessageContaining("same key");
    }
    @Test void tenthAndEleventhRowsDriveNextButtonWithoutCountingEverything() {
        var rows = IntStream.range(0, 11).mapToObj(i -> new Item("id" + i, "time", SQL_ID, "AI", "question")).toList();
        assertThat(Page.of(rows, 1).items()).hasSize(10); assertThat(Page.of(rows, 1).hasNext()).isTrue();
        assertThat(Page.of(rows.subList(0, 10), 2).hasNext()).isFalse();
        assertThat(Page.of(List.of(), 3).items()).isEmpty();
    }
    @Test void serviceKeepsLoginPoolAndDbGrantsWithoutImplicitWrites() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/dbcompanion/service/AiMappedSqlService.java"));
        assertThat(service).contains("read.setReadOnly(true)", "read.setTimeout(10)", "source.bind(session.pool()", "source.clear()", "synchronized (session)")
                .doesNotContain("new Hikari", "accessibleSchemas", ".login(");
        String repository = Files.readString(Path.of("src/main/java/com/dbcompanion/repository/AiMappedSqlRepository.java"));
        assertThat(repository).doesNotContain("jdbc.update", "jdbc.execute", "GENERATE(", "CREATE ", "GRANT ");
    }
    @Test void accessFailuresBranchByLoginUserWithoutInferringThatTheViewIsMissing() {
        for (int code : new int[]{942, 1031}) {
            assertThat(com.dbcompanion.controller.AiMappedSqlController.failureSummary("ADMIN", code, "list"))
                    .startsWith("ADMIN 계정에서도").contains("제공 여부와 권한");
            assertThat(com.dbcompanion.controller.AiMappedSqlController.failureSummary("DEMO_APP", code, "list"))
                    .startsWith("이 로그인 계정으로").contains("ADMIN에서 뷰와 조회 권한");
        }
        assertThat(com.dbcompanion.controller.AiMappedSqlController.failureSummary("ADMIN", 1013, "detail"))
                .contains("상세").doesNotContain("권한");
        assertThat(com.dbcompanion.controller.AiMappedSqlController.failureSummary("DEMO_APP", 904, "list"))
                .contains("목록").doesNotContain("권한");
    }
    @Test void accessStatusDistinguishesDeniedMissingOrHiddenAndUnrelatedErrors() {
        assertThat(Access.failure(1031)).isEqualTo(Access.REQUIRED);
        assertThat(Access.failure(942)).isEqualTo(Access.CHECK).isNotEqualTo(Access.REQUIRED);
        for (int code : new int[]{0, 904, 1013, 17002})
            assertThat(Access.failure(code)).isEqualTo(Access.UNKNOWN);
        assertThat(Access.NOT_CHECKED.getLabel()).isEqualTo("미확인");
        assertThat(Access.AVAILABLE.getLabel()).isEqualTo("조회 가능");
    }
    @Test void grantExampleUsesOnlyTheLoginUserOrAnExplicitAdminPlaceholder() {
        assertThat(AiMappedSql.readGrantExample("DEMO_APP"))
                .isEqualTo("GRANT READ ON SYS.V_$MAPPED_SQL TO \"DEMO_APP\";");
        assertThat(AiMappedSql.readGrantExample("ADMIN"))
                .isEqualTo("GRANT READ ON SYS.V_$MAPPED_SQL TO \"DB_USER\";");
        assertThat(AiMappedSql.readGrantExample("Mixed\"Case"))
                .isEqualTo("GRANT READ ON SYS.V_$MAPPED_SQL TO \"Mixed\"\"Case\";");
        assertThatThrownBy(() -> AiMappedSql.readGrantExample("bad\0name")).isInstanceOf(RuntimeException.class);
    }
    @Test void accessDetectionReusesOneRealListRequestAndNeverGrantsOrChecksByAdminName() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/dbcompanion/controller/AiMappedSqlController.java"));
        assertThat(controller).contains("\"sqlAccess\", Access.NOT_CHECKED", "\"sqlAccess\", Access.failure(failure.code())",
                "AiMappedSql.readGrantExample(session.metadata().info().username())")
                .doesNotContain("GRANT ", "DBA_TAB_PRIVS", "USER_TAB_PRIVS", "service.access(");
        assertThat(controller.split("service\\.page\\(session, query\\)", -1)).hasSize(2);
        assertThat(controller.indexOf("service.page(session, query)"))
                .isLessThan(controller.indexOf("\"sqlAccess\", Access.AVAILABLE"));
    }
}
