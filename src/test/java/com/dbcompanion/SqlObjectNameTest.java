package com.dbcompanion;

import com.dbcompanion.service.SqlObjectName;
import com.dbcompanion.common.db.SessionDataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SqlObjectNameTest {
    @Test void acceptsLocalStandaloneAndPackageNames() {
        assertThat(SqlObjectName.parts("fetch_data")).containsExactly("FETCH_DATA");
        assertThat(SqlObjectName.parts(" app . pkg . fn ")).containsExactly("APP", "PKG", "FN");
        assertThat(SqlObjectName.parts("\"Mixed.Schema\".\"Fn\"\"Name\""))
                .containsExactly("Mixed.Schema", "Fn\"Name");
        assertThat(SqlObjectName.parts("스키마.함수")).containsExactly("스키마", "함수");
    }

    @Test void rejectsSqlRemoteNamesAndEmptyParts() {
        for (String name : new String[]{"", " ", "APP.", ".FN", "APP..FN", "FN()", "FN@REMOTE",
                "FN;DROP TABLE X", "A.B.C.D", "\"\"", "APP. /*comment*/ FN"}) {
            assertThatThrownBy(() -> SqlObjectName.parts(name)).as(name).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void schemaSqlAlwaysQuotesOneIdentifierWithoutChangingCase() {
        assertThat(SessionDataSource.schemaSql("MixedSchema")).isEqualTo("ALTER SESSION SET CURRENT_SCHEMA = \"MixedSchema\"");
        assertThat(SessionDataSource.schemaSql("X\"; DROP TABLE T; --"))
                .isEqualTo("ALTER SESSION SET CURRENT_SCHEMA = \"X\"\"; DROP TABLE T; --\"");
        assertThatThrownBy(() -> SessionDataSource.schemaSql(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionDataSource.schemaSql("x\0y")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unboundDataSourceDoesNotAttemptConnection() {
        assertThatThrownBy(() -> new SessionDataSource().getConnection())
                .isInstanceOf(java.sql.SQLException.class).hasMessage("No authenticated database session");
    }
}
