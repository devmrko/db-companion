package com.dbcompanion;

import com.dbcompanion.model.VectorSearch.Table;
import com.dbcompanion.repository.VectorSearchRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.assertj.core.api.Assertions.*;

class VectorTableCatalogTest {
    private final List<Object> bindings=new ArrayList<>();
    private List<String[]> rows=List.of();
    private String sql;
    private int timeout;
    private int queryCount;
    private SQLException failure;

    private static <T>T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    private PreparedStatement statement() {
        int[] index={-1};
        var results=proxy(ResultSet.class,(p,m,a)->switch(m.getName()) {
            case "next" -> ++index[0]<rows.size();
            case "getString" -> rows.get(index[0])[(int)a[0]-1];
            case "close" -> null;
            default -> throw new AssertionError(m.getName());
        });
        return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()) {
            case "setQueryTimeout" -> { timeout=(int)a[0];yield null; }
            case "setString" -> { bindings.add(a[1]);yield null; }
            case "executeQuery" -> { queryCount++;if(failure!=null)throw failure;yield results; }
            case "getWarnings","close" -> null;
            default -> throw new AssertionError(m.getName());
        });
    }
    private VectorSearchRepository repository() {
        var source=new AbstractDataSource() {
            @Override public Connection getConnection() {
                return proxy(Connection.class,(p,m,a)->switch(m.getName()) {
                    case "prepareStatement" -> { sql=(String)a[0];yield statement(); }
                    case "close" -> null;
                    default -> throw new AssertionError(m.getName());
                });
            }
            @Override public Connection getConnection(String user,String password) { return getConnection(); }
        };
        return new VectorSearchRepository(new JdbcTemplate(source));
    }
    @Test void ownSchemaUsesOwnedTablesWithoutAllDictionaryExpansion() {
        rows=List.of(new String[]{"DOCS","EMBED_A","documents"},new String[]{"DOCS","EMBED_B","documents"},new String[]{"NOTES","EMBED",null});
        assertThat(repository().tables("APP","APP")).containsExactly(
                new Table("DOCS","documents",List.of("EMBED_A","EMBED_B")),new Table("NOTES",null,List.of("EMBED")));
        assertThat(sql).contains("SYS.USER_TAB_COLUMNS", "JOIN SYS.USER_TABLES", "LEFT JOIN SYS.USER_TAB_COMMENTS", "x.TABLE_TYPE='TABLE'", "c.DATA_TYPE='VECTOR'", "ORDER BY c.TABLE_NAME, c.COLUMN_ID")
                .doesNotContain("ALL_","DBA_","OWNER=?");
        assertThat(bindings).isEmpty();assertThat(timeout).isEqualTo(10);assertThat(queryCount).isEqualTo(1);
    }
    @Test void otherSchemaKeepsAccessibleDictionaryAndBoundOwner() {
        String schema="Other' Owner";
        assertThat(repository().tables(schema,"APP")).isEmpty();
        assertThat(sql).contains("SYS.ALL_TAB_COLUMNS", "JOIN SYS.ALL_TABLES", "LEFT JOIN SYS.ALL_TAB_COMMENTS", "t.OWNER=c.OWNER", "x.OWNER=t.OWNER", "c.OWNER=?", "c.DATA_TYPE='VECTOR'")
                .doesNotContain("DBA_","SYS.USER_",schema);
        assertThat(bindings).containsExactly(schema);assertThat(timeout).isEqualTo(10);
    }
    @Test void quotedSchemaCaseIsNotCollapsedIntoLoginScope() {
        repository().tables("App","APP");
        assertThat(sql).contains("SYS.ALL_TAB_COLUMNS");assertThat(bindings).containsExactly("App");
    }
    @Test void quotedLoginUsesOwnDictionaryWhenExactNamesMatch() {
        repository().tables("Mixed Owner","Mixed Owner");
        assertThat(sql).contains("SYS.USER_TAB_COLUMNS");assertThat(bindings).isEmpty();
    }
    @Test void missingLoginCannotSelectOwnedDictionary() {
        repository().tables("APP",null);
        assertThat(sql).contains("SYS.ALL_TAB_COLUMNS");assertThat(bindings).containsExactly("APP");
    }
    @Test void emptyOwnedCatalogReturnsEmptyList() {
        assertThat(repository().tables("APP","APP")).isEmpty();assertThat(queryCount).isEqualTo(1);
    }
    @Test void queryFailureIsNotHiddenOrRetriedWithBroaderPrivileges() {
        failure=new SQLException("cancelled","72000",1013);
        assertThatThrownBy(()->repository().tables("APP","APP")).hasRootCause(failure);
        assertThat(queryCount).isEqualTo(1);assertThat(sql).contains("SYS.USER_TAB_COLUMNS");
    }
}
