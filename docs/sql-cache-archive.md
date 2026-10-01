# Scheduled Select AI call SQL archive

AI execution history → Select AI SQL → Archived SQL reads `DBC_SQL_CACHE_ARCHIVE` in the login owner's schema. Registration previews SQL, then explicitly creates the table and `DBC_SQL_CACHE_CAPTURE` Scheduler job and enables it. Merely opening the screen never installs objects or grants privileges. Default interval: 60 seconds; configurable range: 10–3600 seconds. Pause, resume, interval changes and asynchronous run requests use the same preview/confirmation flow. Existing conflicting objects are never overwritten. DDL auto-commits: refresh after failures and inspect partial objects.

## Source and permissions

- `SYS.V_$SQL`, restricted to `PARSING_SCHEMA_NAME = SESSION_USER` **and Select AI call text candidates** (`SELECT AI ...` or `DBMS_CLOUD_AI.GENERATE(...)`). This restriction is in the collector's source query before MERGE, not merely a UI filter. Ordinary business and metadata queries are not collected. This is the parsing schema, not an authenticated application end-user identity.
- The job reads the local instance. This is not a `GV$SQL` all-instance RAC collector.
- Collector SQL is in `src/main/resources/sql/sql-cache-archive-collect.sql` and displayed by **SQL ?**. Registration previews the actual DDL and Scheduler calls too.
- The owner needs a direct READ/SELECT privilege on `SYS.V_$SQL`, CREATE TABLE, CREATE JOB, and storage quota. No automatic grants. Role-only access in an interactive session may not suffice for Scheduler execution; check the latest job status/error and Scheduler run history.
- Existing archive reads do not require access to `V$SQL`. The archive is not a substitute for the SQL-mapping view.

## Identity and statistics

ADMIN logins also see **Collector privileges**: select an existing non-Oracle-maintained user, inspect direct grants, preview only missing prerequisites, then confirm. Allowed grants are fixed to `READ ON SYS.V_$SQL`, `CREATE TABLE`, and `CREATE JOB`; existing READ or SELECT is sufficient for the source privilege. No role, ANY, delegation, quota or object-creation changes are made here. V$SQL permission itself is broader than Select AI; the capture query restricts what is stored. Authorization checks use the logged-in identity and the real DB session user, not the selected schema. One-use expiring previews are session/connection/target-snapshot bound and SQL is rebuilt server-side. Grants auto-commit; after partial failure inspect current grants before trying again. Users still register their own collector table/job separately.

The primary key hashes instance, container, SQL ID, child number/address, first-load time and last-load time. MERGE inserts new cursor lifetimes and updates existing observations. Repeated polls do not append the same cursor or add its cumulative execution count again. Reloads and different child cursors remain distinguishable. Counts describe observed cursor statistics, not a complete event stream. First/last observation is separate from last-active time. Date filters use last observation in UTC.

## Retention and limitations

Oracle does not promise a fixed cache lifetime. Polling cannot guarantee capturing SQL that leaves memory before the next run. The job is asynchronous and continues when the application exits. Pausing disables future runs; it does not cancel an active run. No automatic archive purge is performed; the owner must manage storage/retention. The job logs its runs and exposes the latest status/error. Capture failure does not become a successful empty result.

SQL text can contain sensitive literals. No bind values, returned AI responses, result rows or mapped-SQL pairs are collected. In particular, a bound `DBMS_CLOUD_AI.GENERATE` PL/SQL block is not the original bound prompt or the generated SQL response. Separately executed ordinary SELECT statements cannot reliably be identified as AI-generated and are not collected. The source filter ignores ordinary quoted strings, common paired q-quotes and comments before matching, but is a text heuristic, not a complete SQL/PLSQL parser or proof that a branch executed. Dynamic calls, wrappers and unusual quoting can be missed. Collector/management statements carrying the reserved `DBC_SQL_ARCHIVE_V1` marker are excluded.

An existing job with a different collector definition is reported as CONFLICT, not silently upgraded or overwritten. Existing archive rows are not deleted by a collector change.

Sources: [V$SQL](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/V-SQL.html), [ADB Scheduler](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/scheduler-predefined-job-classes.html), [shared-pool aging](https://docs.oracle.com/database/121/TGDBA/tune_shared_pool.htm).
