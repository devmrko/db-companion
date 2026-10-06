-- Run as the intended application/database principal, not ADMIN.
-- Recovery prerequisite: install result-store.sql once before query-tool.sql.
-- APEX integration must begin/end a trusted request context around RUN_TEAM.
-- Result recovery retains at most 1,000 fetched rows, not all matching DB rows.
-- Access is bound to DB user, APEX application/session/user and conversation.
-- Access expires after seven days. Expired records are purged on the next
-- begin_request; no scheduler or new grants are installed.
-- The team/function do not grant access to another user's data or evidence stores.
-- Default: required glossary, ontology OFF. One team invocation can make several
-- paid orchestration calls plus a showsql call; never retry an uncertain run.

BEGIN
  DBMS_CLOUD_AI_AGENT.SET_TEAM('DBC_SEMANTIC_QUERY_TEAM');
END;
/
SELECT DBMS_CLOUD_AI_AGENT.RUN_TEAM(
  team_name => 'DBC_SEMANTIC_QUERY_TEAM',
  user_prompt => :original_question
) AS response
FROM dual;
BEGIN
  DBMS_CLOUD_AI_AGENT.CLEAR_TEAM;
END;
/

-- Direct function/tool calls are alternatives, NOT subsequent workflow steps.
-- Each QUERY call below generates SQL and executes it (AI cost).
-- SELECT DBC_SEMANTIC_QUERY(:original_question, 0, '[]') FROM dual;
-- SELECT DBC_SEMANTIC_QUERY(:original_question, 1, :approved_tables_json) FROM dual;
-- Ontology ON requires 1-10 explicitly selected approved tables in the fixed
-- query profile. Missing/draft/out-of-scope definitions return ERROR, no fallback.

-- Read-only registration/history checks (no AI).
SELECT agent_team_name, status FROM user_ai_agent_teams
WHERE agent_team_name='DBC_SEMANTIC_QUERY_TEAM';
SELECT team_exec_id, team_name, state, start_date, end_date
FROM user_ai_agent_team_history
WHERE team_name='DBC_SEMANTIC_QUERY_TEAM'
ORDER BY start_date DESC;

-- Agent JSON: status, actor, profile, evidence, result preview (up to five rows
-- and a size budget), fetchedRowCount/previewRowCount/previewTruncated, timing,
-- warnings and recovery SAVED/UNAVAILABLE. Full fetched rows and SQL are in the
-- authenticated APEX recovery panel, NOT duplicated into the model prompt.
-- Direct calls without APEX context still return a preview with recovery
-- UNAVAILABLE; they do not acquire access to another user's saved results.
-- NUMBER values remain decimal strings; NULL stays JSON null.
-- More=true means output exceeds 1000 rows; it is NOT a complete result/chart.
-- A framework success envelope can contain an inner status=ERROR: inspect both.
-- CURRENT_USER governs data/evidence access; a shared DB login is not end-user
-- identity. Configure trusted identity/VPD/DDS before enabling multi-user use.
