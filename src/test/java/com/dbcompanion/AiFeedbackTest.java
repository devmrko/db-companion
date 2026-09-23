package com.dbcompanion;

import com.dbcompanion.model.AiFeedback;
import com.dbcompanion.model.AiFeedback.*;
import com.dbcompanion.repository.AiFeedbackRepository;
import com.dbcompanion.service.AiFeedbackService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiFeedbackTest {
    private Query query(String search, String type, int page) { return new Query("APP", "MY_PROFILE", search, type, page); }
    @Test void filterLimitsAndLiteralContainsValues() {
        assertThat(query("  한글 %_  ", "negative", 2).search()).isEqualTo("한글 %_");
        assertThat(query("", "", 1000).offset()).isEqualTo(9990);
        for (int page : List.of(0, -1, 1001)) assertThatThrownBy(() -> query("", "", page)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("a".repeat(501), "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("", "POSITIVE", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("", "P", "", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("APP", "P\0", "", "", 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void initialEmptyProfileIsValidButCannotBuildATable() {
        assertThat(new Query("", "", "", "", 1).profile()).isEmpty();
        for (String profile : List.of("", " ", "a".repeat(128)))
            assertThatThrownBy(() -> AiFeedback.tableName(profile)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AiFeedback.tableName("P$1")).isEqualTo("P$1_FEEDBACK_VECINDEX$VECTAB");
    }
    @Test void identifiersAreQuotedAndValuesBoundWithNoEmbeddingOrFullBodyInList() {
        var sql = AiFeedbackRepository.listStatement(new Query("A\"B", "P\"Q", "x' OR 1=1 --", "negative", 2));
        assertThat(sql.sql()).contains("\"A\"\"B\".\"P\"\"Q_FEEDBACK_VECINDEX$VECTAB\"", "INSTR(UPPER(f.CONTENT), UPPER(?))",
                "DBMS_LOB.SUBSTR(f.CONTENT, 501, 1)", "ORDER BY f.ROWID OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY")
                .doesNotContain("x' OR", "$.response", "EMBEDDING", "COUNT(*)", "LIKE");
        assertThat(sql.args()).containsExactly("x' OR 1=1 --", "x' OR 1=1 --", "negative", 10);
        assertThat(AiFeedbackRepository.listStatement(query("", "", 1)).args()).containsExactly(0);
    }
    @Test void detailUsesSelectedTableAndValidatedBoundRowIdWithoutClobTruncation() {
        var sql = AiFeedbackRepository.detailStatement(query("", "", 1), "AAABBBCCC000001abc");
        assertThat(sql.sql()).contains("CHARTOROWID(?)", "JSON_SERIALIZE(f.ATTRIBUTES RETURNING CLOB)",
                "'$.response' RETURNING CLOB", "'$.feedback_content' RETURNING CLOB").doesNotContain("SUBSTR", "EMBEDDING");
        assertThat(sql.args()).containsExactly("AAABBBCCC000001abc");
        for (String id : List.of("", "abc", "' OR 1=1 --", "x".repeat(19)))
            assertThatThrownBy(() -> AiFeedbackRepository.detailStatement(query("", "", 1), id)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ownAndAdminScopeFollowExistingProfilePolicyAndRejectStaleSchema() {
        assertThat(AiFeedbackService.ownScope("APP", "APP", query("", "", 1))).isTrue();
        assertThat(AiFeedbackService.ownScope("ADMIN", "APP", query("", "", 1))).isFalse();
        assertThatThrownBy(() -> AiFeedbackService.ownScope("OTHER", "APP", query("", "", 1)))
                .hasMessageContaining("Only ADMIN");
        assertThatThrownBy(() -> AiFeedbackService.ownScope("APP", "OTHER", query("", "", 1)))
                .hasMessageContaining("schema changed");
    }
    @Test void pagesTakeTenAndPreviewKeepsUnicodeBoundaries() {
        var rows = IntStream.range(0, 11).mapToObj(i -> new Item("" + i, "Q", "positive", "")).toList();
        var page = Page.of(rows, 2);
        assertThat(page.items()).hasSize(10); assertThat(page.hasNext()).isTrue();
        assertThat(Page.of(rows.subList(0, 10), 1).hasNext()).isFalse();
        assertThat(Page.of(List.of(), 1).items()).isEmpty();
        assertThat(AiFeedback.preview("한😀".repeat(251))).isEqualTo("한😀".repeat(250) + "…");
        assertThat(AiFeedback.preview(null)).isEmpty();
        assertThat(AiFeedback.typeLabel(null)).isEqualTo("—");
        assertThat(AiFeedback.typeLabel("custom")).isEqualTo("custom");
    }
}
