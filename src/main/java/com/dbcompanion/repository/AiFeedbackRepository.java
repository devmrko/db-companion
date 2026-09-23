package com.dbcompanion.repository;

import com.dbcompanion.common.db.MetadataSql;
import com.dbcompanion.model.AiFeedback;
import com.dbcompanion.model.AiFeedback.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiFeedbackRepository {
    private final JdbcTemplate jdbc;
    private static final String TYPE = "JSON_VALUE(f.ATTRIBUTES, '$.feedback_type')";
    private static final String FEEDBACK = "JSON_VALUE(f.ATTRIBUTES, '$.feedback_content' RETURNING CLOB ERROR ON ERROR NULL ON EMPTY)";
    public AiFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
    }
    public boolean tableExists(String schema, String profile) {
        return !jdbc.queryForList("SELECT TABLE_NAME FROM SYS.ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?",
                String.class, schema, AiFeedback.tableName(profile)).isEmpty();
    }
    private static String table(Query query) {
        return MetadataSql.identifier(query.schema()) + "." + MetadataSql.identifier(AiFeedback.tableName(query.profile()));
    }
    public record Statement(String sql, List<Object> args) {}
    public static Statement listStatement(Query query) {
        String sql = "SELECT ROWIDTOCHAR(f.ROWID), DBMS_LOB.SUBSTR(f.CONTENT, 501, 1), " + TYPE
                + ", DBMS_LOB.SUBSTR(" + FEEDBACK + ", 501, 1) FROM " + table(query) + " f WHERE 1=1";
        var args = new ArrayList<Object>();
        if (!query.search().isEmpty()) {
            sql += " AND (INSTR(UPPER(f.CONTENT), UPPER(?)) > 0 OR INSTR(UPPER(" + FEEDBACK + "), UPPER(?)) > 0)";
            args.add(query.search()); args.add(query.search());
        }
        if (!query.type().isEmpty()) { sql += " AND " + TYPE + " = ?"; args.add(query.type()); }
        sql += " ORDER BY f.ROWID OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY";
        args.add(query.offset());
        return new Statement(sql, List.copyOf(args));
    }
    public Page page(Query query) {
        var statement = listStatement(query);
        return Page.of(jdbc.query(statement.sql(), (r, i) -> new Item(r.getString(1), AiFeedback.preview(r.getString(2)),
                r.getString(3), AiFeedback.preview(r.getString(4))), statement.args().toArray()), query.page());
    }
    public static Statement detailStatement(Query query, String id) {
        return new Statement("SELECT f.CONTENT, " + TYPE
                + ", JSON_VALUE(f.ATTRIBUTES, '$.response' RETURNING CLOB ERROR ON ERROR NULL ON EMPTY), " + FEEDBACK
                + ", JSON_VALUE(f.ATTRIBUTES, '$.sql_id'), JSON_VALUE(f.ATTRIBUTES, '$.sql_text' RETURNING CLOB ERROR ON ERROR NULL ON EMPTY), "
                + "JSON_SERIALIZE(f.ATTRIBUTES RETURNING CLOB) FROM " + table(query)
                + " f WHERE f.ROWID = CHARTOROWID(?)", List.of(AiFeedback.rowId(id)));
    }
    public Detail detail(Query query, String id) {
        var statement = detailStatement(query, id);
        var rows = jdbc.query(statement.sql(), (r, i) -> new Detail(r.getString(1), r.getString(2), r.getString(3),
                r.getString(4), r.getString(5), r.getString(6), r.getString(7)), statement.args().toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
