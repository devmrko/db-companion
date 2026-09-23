package com.dbcompanion.repository;

import com.dbcompanion.common.exception.AppException;
import com.dbcompanion.model.AiMappedSql.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiMappedSqlRepository {
    // Explicit system owner prevents CURRENT_SCHEMA objects from shadowing the documented view.
    public static final String VIEW = "SYS.V_$MAPPED_SQL";
    private static final String TIME = "TO_CHAR(TRANSLATION_TIMESTAMP, 'YYYY-MM-DD HH24:MI:SS')";
    private static final String AI_ONLY = "REGEXP_LIKE(SQL_TEXT, '^[[:space:]]*select[[:space:]]+ai([[:space:]]|$)', 'i')";
    private final JdbcTemplate jdbc;
    public AiMappedSqlRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
    }
    public record Statement(String sql, List<Object> args) {}

    public static Statement listStatement(Query query) {
        String sql = "SELECT SQL_ID, MAPPED_SQL_ID, SQL_TRANSLATION_PROFILE_ID, CON_ID, " + TIME + " TRANSLATED, "
                + "TRANSLATION_METHOD, SUBSTR(SQL_TEXT, 1, 500) PREVIEW FROM " + VIEW + " WHERE " + AI_ONLY;
        var args = new ArrayList<Object>();
        if (!query.sqlId().isEmpty()) { sql += " AND SQL_ID = ?"; args.add(query.sqlId()); }
        if (!query.question().isEmpty()) { sql += " AND INSTR(UPPER(SQL_FULLTEXT), UPPER(?)) > 0"; args.add(query.question()); }
        sql += " ORDER BY TRANSLATION_TIMESTAMP DESC NULLS LAST, SQL_ID, MAPPED_SQL_ID, CON_ID, SQL_TRANSLATION_PROFILE_ID"
                + " OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY";
        args.add(query.offset());
        return new Statement(sql, List.copyOf(args));
    }
    public Page page(Query query) {
        var statement = listStatement(query);
        return Page.of(jdbc.query(statement.sql(), (row, index) -> new Item(key(row).token(), row.getString(5),
                row.getString(1), row.getString(6), row.getString(7)), statement.args().toArray()), query.page());
    }
    private Key key(ResultSet row) throws SQLException {
        return new Key(row.getString(1), nullable(row.getString(2)), nullable(row.getString(3)),
                nullable(row.getString(4)), nullable(row.getString(5)));
    }
    private String nullable(String value) { return value == null ? "-" : value; }

    public static Statement detailStatement(Key key) {
        String sql = "SELECT SQL_ID, MAPPED_SQL_ID, SQL_TRANSLATION_PROFILE_ID, CON_ID, " + TIME + " TRANSLATED, "
                + "TRANSLATION_METHOD, USE_COUNT, TRANSLATION_CPU_TIME, TRANSLATION_ELAPSED_TIME, SQL_FULLTEXT, MAPPED_SQL_FULLTEXT"
                + " FROM " + VIEW + " WHERE " + AI_ONLY + " AND SQL_ID = ? AND NVL(MAPPED_SQL_ID, '-') = ?"
                + " AND NVL(SQL_TRANSLATION_PROFILE_ID, -1) = ? AND NVL(CON_ID, -1) = ? AND NVL(" + TIME + ", '-') = ?"
                + " FETCH FIRST 2 ROWS ONLY";
        return new Statement(sql, List.of(key.sqlId(), key.mappedId(), Key.number(key.profileId()), Key.number(key.conId()), key.translated()));
    }
    public Detail detail(Key key) {
        var statement = detailStatement(key);
        var rows = jdbc.query(statement.sql(), (r, i) -> new Detail(r.getString(1), r.getString(2), r.getString(3),
                r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9),
                r.getString(10), r.getString(11)), statement.args().toArray());
        return unique(rows);
    }
    public static Detail unique(List<Detail> rows) {
        if (rows.size() > 1) throw new AppException(AppException.Code.SQL_MAPPING_AMBIGUOUS);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
