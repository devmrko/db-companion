package com.dbcompanion;

import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MetadataSqlTest {
    final Target table = new Target("APP", "COUNTRY", null);
    @Test void commentsQuoteIdentifiersAndValues() {
        assertThat(MetadataSql.comment(new Target("A\"B", "T", "C"), "한국 O'Reilly"))
                .isEqualTo("COMMENT ON COLUMN \"A\"\"B\".\"T\".\"C\" IS '한국 O''Reilly'");
        assertThat(MetadataSql.comment(table, "country")).startsWith("COMMENT ON TABLE \"APP\".\"COUNTRY\"");
    }
    @Test void annotationsOnlyGenerateFixedAddOrReplace() {
        assertThat(MetadataSql.annotation(table, "DISPLAY", "국가", true))
                .isEqualTo("ALTER TABLE \"APP\".\"COUNTRY\" ANNOTATIONS (ADD \"DISPLAY\" '국가')");
        assertThat(MetadataSql.annotation(new Target("APP", "T", "C"), "A", "", false))
                .isEqualTo("ALTER TABLE \"APP\".\"T\" MODIFY \"C\" ANNOTATIONS (REPLACE \"A\")");
    }
    @Test void emptyCommentsAndOversizeUtf8CannotBecomeDdl() {
        assertThatThrownBy(() -> MetadataSql.comment(table, " ")).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(() -> MetadataSql.comment(table, "가".repeat(1334))).isInstanceOf(MetadataEditException.class);
        assertThat(MetadataSql.comment(table, "가".repeat(1333))).contains("가");
        assertThatThrownBy(() -> MetadataSql.annotation(table, "A", "\0", true)).isInstanceOf(MetadataEditException.class);
    }
    @Test void annotationNamesPreserveExplicitCaseAndRejectInvalidInput() {
        assertThat(MetadataSql.newAnnotationName("display")).isEqualTo("DISPLAY");
        assertThat(MetadataSql.newAnnotationName("\"Display name\"")).isEqualTo("Display name");
        assertThatThrownBy(() -> MetadataSql.newAnnotationName("\"unfinished")).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(() -> MetadataSql.newAnnotationName("가".repeat(342))).isInstanceOf(MetadataEditException.class);
    }
    @Test void commentVersionIgnoresUnusedNameAndNormalizesOracleEmptyString() {
        var v = new Value(true, "", false);
        assertThat(MetadataSql.version(table, "comment", null, v))
                .isEqualTo(MetadataSql.version(table, "comment", "", new Value(true, null, false)));
    }
    @Test void versionDetectsValueTargetPresenceAndInheritanceChanges() {
        var value = new Value(true, "before", false);
        var version = MetadataSql.version(table, "annotation", "A", value);
        MetadataSql.verifyVersion(table, "annotation", "A", value, version);
        for (var changed : java.util.List.of(new Value(true, "after", false), new Value(false, "before", false), new Value(true, "before", true)))
            assertThatThrownBy(() -> MetadataSql.verifyVersion(table, "annotation", "A", changed, version)).isInstanceOf(MetadataEditException.class);
        assertThatThrownBy(() -> MetadataSql.verifyVersion(new Target("APP", "OTHER", null), "annotation", "A", value, version))
                .isInstanceOf(MetadataEditException.class);
    }
}
