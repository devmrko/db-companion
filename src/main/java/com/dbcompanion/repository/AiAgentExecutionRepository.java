package com.dbcompanion.repository;

import com.dbcompanion.model.AiAgentExecution;
import com.dbcompanion.model.AiAgentExecution.*;
import com.dbcompanion.model.AiExecutionHistory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/** Reads fixed USER history views. No AI calls, writes, or inferred relationships. */
@Repository
public class AiAgentExecutionRepository {
    public static final String TEAMS = "USER_AI_AGENT_TEAM_HISTORY";
    public static final String TASKS = "USER_AI_AGENT_TASK_HISTORY";
    private static final String TIME = "YYYY-MM-DD HH24:MI:SS";
    private final JdbcTemplate jdbc;
    public AiAgentExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
    }
    public record Statement(String sql, List<Object> args) {}
    public static final class QueryFailure extends RuntimeException {
        private final String view;
        public QueryFailure(String view, RuntimeException cause) { super("Agent history query failed", cause); this.view = view; }
        public String view() { return view; }
    }
    public static final class AmbiguousTask extends RuntimeException {
        public AmbiguousTask() { super("Multiple history rows have the same team execution ID and task order"); }
    }
    public static <T> T unique(List<T> rows) {
        if (rows.size() > 1) throw new AmbiguousTask();
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private <T> T at(String view, java.util.function.Supplier<T> work) {
        try { return work.get(); }
        catch (AmbiguousTask ex) { throw ex; }
        catch (RuntimeException ex) { throw new QueryFailure(view, ex); }
    }
    private AgentViewColumns describe(String view) {
        // Caller uses constants only; zero history rows and no CLOB values are loaded here.
        return jdbc.query("SELECT * FROM " + view + " WHERE 1=0", (ResultSetExtractor<AgentViewColumns>) rows -> {
            var metadata = rows.getMetaData(); var names = new ArrayList<String>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) names.add(metadata.getColumnLabel(i));
            return new AgentViewColumns(names);
        });
    }
    private static String time(String column) { return "TO_CHAR(" + column + ", '" + TIME + "')"; }
    private static String runProjection(AgentViewColumns c) {
        String start = c.required("START_DATE"), end = c.required("END_DATE");
        return c.required("TEAM_EXEC_ID") + ", " + c.required("TEAM_NAME") + ", " + c.required("STATE", "STATUS")
                + ", " + time(start) + ", " + time(end)
                + ", ROUND((CAST(" + end + " AS DATE) - CAST(" + start + " AS DATE)) * 86400, 3)";
    }
    public static Statement listStatement(Query query, AgentViewColumns c) {
        String sql = "SELECT " + runProjection(c) + " FROM " + TEAMS
                + " WHERE " + c.required("START_DATE") + " >= TO_TIMESTAMP(?, 'YYYY-MM-DD')"
                + " AND " + c.required("START_DATE") + " < TO_TIMESTAMP(?, 'YYYY-MM-DD')";
        var args = new ArrayList<Object>(); args.add(query.from().toString()); args.add(query.to().plusDays(1).toString());
        if (!query.team().isEmpty()) { sql += " AND INSTR(UPPER(" + c.required("TEAM_NAME") + "), UPPER(?)) > 0"; args.add(query.team()); }
        if (!query.state().isEmpty()) { sql += " AND " + c.required("STATE", "STATUS") + " = ?"; args.add(query.state()); }
        sql += " ORDER BY " + c.required("START_DATE") + " DESC, " + c.required("TEAM_EXEC_ID") + " DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY";
        args.add(query.offset()); return new Statement(sql, List.copyOf(args));
    }
    public Page<Run> page(Query query) {
        Page<Run> page = at(TEAMS, () -> {
            var statement = listStatement(query, describe(TEAMS));
            return Page.of(jdbc.query(statement.sql(), (r, i) -> run(r), statement.args().toArray()), query.page());
        });
        if (page.items().isEmpty()) return page;
        return at(TASKS, () -> {
            var statement = previewsStatement(page.items().stream().map(Run::id).toList(), describe(TASKS));
            var previews = new java.util.HashMap<String, InputPreview>();
            jdbc.query(statement.sql(), (org.springframework.jdbc.core.RowCallbackHandler) r ->
                    previews.put(r.getString(1), InputPreview.of(r.getString(2), r.getInt(3) != 1)), statement.args().toArray());
            return new Page<>(page.items().stream().map(run -> run.withPreview(previews.getOrDefault(run.id(), InputPreview.of(null, false)))).toList(),
                    page.number(), page.hasNext());
        });
    }
    /** At most the ten visible run IDs; one bounded batch rather than one read per row. */
    public static Statement previewsStatement(List<String> ids, AgentViewColumns c) {
        if (ids == null || ids.isEmpty() || ids.size() > AiAgentExecution.PAGE_SIZE) throw new IllegalArgumentException("Invalid preview batch");
        ids.forEach(AiAgentExecution::requireRunId);
        String run = c.required("TEAM_EXEC_ID"), order = c.required("TASK_ORDER");
        String sql = "SELECT RUN_ID, CASE WHEN OCCURRENCES = 1 AND TASK_POSITION IS NOT NULL THEN "
                + "DBMS_LOB.SUBSTR(INPUT_VALUE, 501, 1) END, CASE WHEN TASK_POSITION IS NULL THEN 0 ELSE OCCURRENCES END FROM ("
                + "SELECT " + run + " RUN_ID, " + order + " TASK_POSITION, " + c.required("INPUT") + " INPUT_VALUE, "
                + "COUNT(*) OVER (PARTITION BY " + run + ", " + order + ") OCCURRENCES, "
                + "ROW_NUMBER() OVER (PARTITION BY " + run + " ORDER BY " + order + " NULLS FIRST, "
                + c.required("START_DATE") + ", " + c.required("TASK_NAME") + ", " + c.required("AGENT_NAME") + ") POSITION_IN_RUN "
                + "FROM " + TASKS + " WHERE " + run + " IN (" + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                + ")) WHERE POSITION_IN_RUN = 1";
        return new Statement(sql, List.copyOf(ids));
    }
    private Run run(ResultSet r) throws SQLException {
        return new Run(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), AiAgentExecution.duration(r.getBigDecimal(6)));
    }
    public Run run(String id) {
        return at(TEAMS, () -> {
            var c = describe(TEAMS);
            var rows = jdbc.query("SELECT " + runProjection(c) + " FROM " + TEAMS + " WHERE " + c.required("TEAM_EXEC_ID") + " = ? FETCH FIRST 2 ROWS ONLY",
                    (r, i) -> run(r), AiAgentExecution.requireRunId(id));
            return unique(rows);
        });
    }
    private static String taskProjection(AgentViewColumns c) {
        return c.required("TASK_ORDER") + ", " + c.required("TASK_NAME") + ", " + c.required("AGENT_NAME") + ", "
                + c.required("STATE", "STATUS") + ", " + time(c.required("START_DATE")) + ", " + time(c.required("END_DATE"));
    }
    public static Statement tasksStatement(String id, int page, AgentViewColumns c) {
        return new Statement("SELECT " + taskProjection(c) + " FROM " + TASKS + " WHERE " + c.required("TEAM_EXEC_ID")
                + " = ? ORDER BY " + c.required("TASK_ORDER") + ", " + c.required("START_DATE") + ", "
                + c.required("TASK_NAME") + ", " + c.required("AGENT_NAME") + " OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",
                List.of(AiAgentExecution.requireRunId(id), (AiAgentExecution.requirePage(page) - 1) * 10));
    }
    public Page<Task> tasks(String id, int page) {
        return at(TASKS, () -> {
            var s = tasksStatement(id, page, describe(TASKS));
            return Page.of(jdbc.query(s.sql(), (r, i) -> {
                var value = r.getBigDecimal(1);
                if (value == null) throw new IllegalStateException("Task order is NULL; cannot identify task occurrence");
                long order = AiAgentExecution.requireOrder(value.longValueExact());
                return new Task(order, r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6));
            }, s.args().toArray()), page);
        });
    }
    public static String conversationProjection(AgentViewColumns c) {
        String column = c.optional("CONVERSATION_PARAMS", "CONVERSATION_PARAM", "COVERSATION_PARAM");
        return column == null ? "NULL" : "JSON_VALUE(" + column + ", '$.conversation_id' RETURNING VARCHAR2(128) NULL ON ERROR)";
    }
    public static Statement taskStatement(String id, long order, AgentViewColumns c, boolean full) {
        String projection = full ? taskProjection(c) + ", " + c.required("INPUT") + ", " + c.required("RESULT") + ", " : "";
        return new Statement("SELECT " + projection + conversationProjection(c) + " FROM " + TASKS
                + " WHERE " + c.required("TEAM_EXEC_ID") + " = ? AND " + c.required("TASK_ORDER") + " = ? FETCH FIRST 2 ROWS ONLY",
                List.of(AiAgentExecution.requireRunId(id), AiAgentExecution.requireOrder(order)));
    }
    public TaskDetail task(String id, long order) {
        return at(TASKS, () -> {
            var s = taskStatement(id, order, describe(TASKS), true);
            var rows = jdbc.query(s.sql(), (r, i) -> new TaskDetail(id, r.getLong(1), r.getString(2), r.getString(3),
                    r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9)), s.args().toArray());
            return unique(rows);
        });
    }
    public Conversations conversations(String id, long order, int page) {
        AiAgentExecution.requirePage(page);
        var ids = at(TASKS, () -> {
            var s = taskStatement(id, order, describe(TASKS), false);
            return jdbc.query(s.sql(), (r, i) -> r.getString(1), s.args().toArray());
        });
        if (ids.size() > 1) throw new AmbiguousTask();
        if (ids.isEmpty()) return null;
        String conversation = ids.getFirst();
        if (conversation == null || conversation.isBlank()) return new Conversations(null, Page.of(List.of(), page));
        return at(AiExecutionHistoryRepository.VIEW, () -> {
            var s = conversationsStatement(conversation, page);
            var prompts = jdbc.query(s.sql(), (r, i) -> new AiExecutionHistory.Item(r.getString(1), r.getString(2),
                    r.getString(3), r.getString(4), r.getString(5)), s.args().toArray());
            return new Conversations(conversation, Page.of(prompts, page));
        });
    }
    public static Statement conversationsStatement(String conversation, int page) {
        if (conversation == null || conversation.isBlank() || conversation.length() > 128) throw new IllegalArgumentException("Invalid conversation ID");
        return new Statement("SELECT CONVERSATION_PROMPT_ID, " + time("CREATED") + ", PROFILE_NAME, PROMPT_ACTION, "
                + "DBMS_LOB.SUBSTR(PROMPT, 500, 1) FROM " + AiExecutionHistoryRepository.VIEW
                + " WHERE CONVERSATION_ID = ? ORDER BY CREATED, CONVERSATION_PROMPT_ID OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",
                List.of(conversation, (AiAgentExecution.requirePage(page) - 1) * 10));
    }
}
