package com.dbcompanion;

import com.dbcompanion.common.db.OracleErrorDetails;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OracleErrorDetailsTest {
    @Test void preservesOriginalExecutionErrorWhenRollbackOverridesIt() {
        var original = new SQLException("ORA-18730: Interrupted IO error.: Socket read timed out", "08006", 18730);
        var wrapped = new org.springframework.dao.RecoverableDataAccessException("SQL [private query]", original);
        var rollback = new org.springframework.transaction.TransactionSystemException("Could not roll back", new SQLException("ORA-17008: Closed connection", "08003", 17008));
        rollback.initApplicationException(wrapped);
        assertThat(OracleErrorDetails.forDisplay(rollback)).startsWith("ORA-18730:").contains("ORA-17008:").doesNotContain("private query");
        assertThat(OracleErrorDetails.firstCode(rollback)).isEqualTo(18730);
        assertThat(com.dbcompanion.repository.CredentialCatalogRepository.error(rollback)).isEqualTo("ORA-18730");
    }

    @Test void readsSuppressedDriverFailuresAndHandlesExceptionGraphCycles() {
        var original = new SQLException("ORA-01013: user requested cancel", "72000", 1013);
        var rollback = new org.springframework.transaction.TransactionSystemException("rollback failed");
        rollback.initApplicationException(original);
        original.addSuppressed(rollback);
        original.addSuppressed(new SQLException("ORA-17008: Closed connection", "08003", 17008));
        assertThat(OracleErrorDetails.forDisplay(rollback)).isEqualTo("ORA-01013: user requested cancel\nORA-17008: Closed connection");
        assertThat(OracleErrorDetails.firstCode(null)).isZero();
    }

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

    @Test void explainsNestedOracleRowsWithoutPromotingAWrapperToFact() {
        var nested = new SQLException("ORA-20004: object_list validation failed\nORA-00904: \"X\": invalid identifier");
        var summary = com.dbcompanion.common.db.AiErrorExplanation.explain("AI request", nested);
        assertThat(summary.stage()).isEqualTo("AI request");
        assertThat(summary.original()).contains("ORA-20004", "ORA-00904");
        assertThat(summary.confirmed()).containsExactly("Oracle returned the displayed code(s)");
        assertThat(summary.possible()).anyMatch(value -> value.contains("not prove an external object"));
        assertThat(summary.display()).contains("Failed stage: AI request", "Oracle original:", "Possible cause (not confirmed)");
    }
}
