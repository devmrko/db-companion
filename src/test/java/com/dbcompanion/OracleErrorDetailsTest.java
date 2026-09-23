package com.dbcompanion;

import com.dbcompanion.common.db.OracleErrorDetails;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OracleErrorDetailsTest {
    @Test void retainsOracleCauseAndLocationWithoutTheSpringSqlWrapper() {
        var sql = new SQLException("""
                ORA-04088: error during execution of trigger 'ADMIN.DBC_MH_B'
                ORA-20081: Duplicate metadata identity
                ORA-06512: at "APP.DBC_METADATA_AUDIT", line 16
                https://docs.oracle.com/error-help/db/ora-04088/
                """, "72000", 4088);
        var error = new IllegalStateException("StatementCallback; SQL [COMMENT ON TABLE T IS 'private value']", sql);
        assertThat(OracleErrorDetails.forDisplay(error)).isEqualTo("""
                ORA-04088: error during execution of trigger 'ADMIN.DBC_MH_B'
                ORA-20081: Duplicate metadata identity
                ORA-06512: at "APP.DBC_METADATA_AUDIT", line 16""");
    }

    @Test void includesChainedDriverErrorsOnceAndToleratesCauseCycles() {
        var first = new SQLException("ORA-04088: trigger failed");
        var next = new SQLException("ORA-04088: trigger failed\nPLS-00201: identifier must be declared");
        first.setNextException(next);
        next.initCause(first);
        assertThat(OracleErrorDetails.forDisplay(first))
                .isEqualTo("ORA-04088: trigger failed\nPLS-00201: identifier must be declared");
    }

    @Test void ignoresNonOracleMessagesAndHandlesMissingDriverMessages() {
        assertThat(OracleErrorDetails.forDisplay(null)).isEmpty();
        assertThat(OracleErrorDetails.forDisplay(new SQLException())).isEmpty();
        assertThat(OracleErrorDetails.forDisplay(new SQLException("SQL: COMMENT ON TABLE T IS 'private'"))).isEmpty();
        assertThat(OracleErrorDetails.forDisplay(new IllegalStateException("ORA-99999: not a driver error"))).isEmpty();
    }

    @Test void boundsErrorOutput() {
        var many = java.util.stream.IntStream.range(0, 100).mapToObj(n -> "ORA-06512: line " + n)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(OracleErrorDetails.forDisplay(new SQLException(many)).lines().count()).isEqualTo(32);
        assertThat(OracleErrorDetails.forDisplay(new SQLException("ORA-20000: " + "x".repeat(5000))))
                .hasSize(1025).endsWith("…");
    }
}
