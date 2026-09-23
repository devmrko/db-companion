package com.dbcompanion.repository;

import com.dbcompanion.model.AiExecutionHistory;
import com.dbcompanion.model.AiExecutionHistory.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiExecutionHistoryRepository {
    public static final String VIEW = "USER_CLOUD_AI_CONVERSATION_PROMPTS";
    private static final String DATE_FORMAT = "YYYY-MM-DD HH24:MI:SS TZH:TZM";
    private static final DateTimeFormatter BOUND_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
    private final JdbcTemplate jdbc;

    public AiExecutionHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
    }

    public record Statement(String sql, List<Object> args) {}

    /** The view and columns are fixed; filters and pagination are bound values. */
    public static Statement listStatement(Query query) {
        String sql = "SELECT CONVERSATION_PROMPT_ID, TO_CHAR(CREATED, '" + DATE_FORMAT + "') CREATED_TEXT, "
                + "PROFILE_NAME, PROMPT_ACTION, DBMS_LOB.SUBSTR(PROMPT, 500, 1) PROMPT_PREVIEW FROM " + VIEW
                + " WHERE CREATED >= TO_TIMESTAMP_TZ(?, 'YYYY-MM-DD\"T\"HH24:MI:SSTZH:TZM')"
                + " AND CREATED < TO_TIMESTAMP_TZ(?, 'YYYY-MM-DD\"T\"HH24:MI:SSTZH:TZM')";
        var args = new ArrayList<Object>();
        args.add(query.from().atStartOfDay(AiExecutionHistory.ZONE).withZoneSameInstant(ZoneOffset.UTC).format(BOUND_TIME));
        args.add(query.to().plusDays(1).atStartOfDay(AiExecutionHistory.ZONE).withZoneSameInstant(ZoneOffset.UTC).format(BOUND_TIME));
        if (!query.profile().isEmpty()) { sql += " AND INSTR(UPPER(PROFILE_NAME), UPPER(?)) > 0"; args.add(query.profile()); }
        if (!query.question().isEmpty()) { sql += " AND INSTR(UPPER(PROMPT), UPPER(?)) > 0"; args.add(query.question()); }
        sql += " ORDER BY CREATED DESC, CONVERSATION_PROMPT_ID DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY";
        args.add(query.offset());
        return new Statement(sql, List.copyOf(args));
    }

    public Page page(Query query) {
        var statement = listStatement(query);
        var rows = jdbc.query(statement.sql(), (row, index) -> new Item(row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), row.getString(5)), statement.args().toArray());
        return Page.of(rows, query.page());
    }

    public static String detailSql() {
        return "SELECT CONVERSATION_PROMPT_ID, CONVERSATION_ID, CONVERSATION_TITLE, PROFILE_NAME, PROMPT_ACTION, "
                + "TO_CHAR(CREATED, '" + DATE_FORMAT + "') CREATED_TEXT, TO_CHAR(MODIFIED, '" + DATE_FORMAT + "') MODIFIED_TEXT, "
                + "PROMPT, PROMPT_RESPONSE, CLIENT_IDENTIFIER, CLIENT_IP, SID, SERIAL# FROM " + VIEW
                + " WHERE CONVERSATION_PROMPT_ID = ? FETCH FIRST 1 ROW ONLY";
    }

    public Detail detail(String id) {
        var rows = jdbc.query(detailSql(), (row, index) -> readDetail(row), AiExecutionHistory.requireId(id));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Detail readDetail(ResultSet row) throws SQLException {
        return new Detail(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getString(6), row.getString(7), row.getString(8), row.getString(9),
                row.getString(10), row.getString(11), row.getString(12), row.getString(13));
    }
}
