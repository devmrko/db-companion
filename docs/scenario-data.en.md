# Scenario data generation

Engineers can hand a chosen industry, date range and approximate size to a local agent
to prepare fictional tables and data. The app generates a prompt and password-free
configuration only. Your local Claude Code or Codex and SQLcl perform the execution;
this app feature makes no AI calls or database writes.

[한국어](scenario-data.md) · [First-use guide](GETTING-STARTED.md)

## Generate the files

1. Sign in and open **Scenario data generation**, next to the SCOTT demo.
2. Select a wallet registered through `ORACLE_WALLET_PATH` or `ORACLE_WALLET_PATHS`
   and an alias from its `tnsnames.ora`. This reuses the login wallet catalog.
3. Select e-commerce, logistics, hospital, school, public sector or a custom description.
   Enter approximate total rows (1–10 million), inclusive dates (1900–2100), an existing
   dedicated scenario username and a table prefix. The scenario account may differ from
   the app login account. Prefixes are 2–16 letters/digits/underscores, beginning with a
   letter and ending with an underscore; identifiers are converted to uppercase.
4. Generate, review and copy/download both files. Changing input invalidates the output.
   Never put passwords, customer data or real identities in the description.

Save `scenario-prompt.md` and `scenario-config.json` in the checkout root. The configuration
includes the selected absolute wallet path, but no password or wallet contents. Generated
files and `scenario-results/` are Git-ignored; do not share local configuration publicly.

## Prepare and run locally

Python 3 and SQLcl must be available. Run `sql -V`; if missing, the agent must explain
installation for the current OS and obtain confirmation first. See the
[Oracle SQLcl guide](https://docs.oracle.com/en/database/oracle/sql-developer-command-line/25.4/sqcug/startup-sqlcl-settings.html).
Do not let the agent install software automatically.

Manually create the private file outside the repository; skip `cp` if it already exists:

```sh
mkdir -p ~/.dbcompanion
chmod 700 ~/.dbcompanion
(umask 077; cp scripts/scenario/scenario.env.example ~/.dbcompanion/scenario.env)
chmod 600 ~/.dbcompanion/scenario.env
```

Edit it yourself with `USERNAME`, `PASSWORD`, `WALLET_PATH`, `TNS_ALIAS`. Values use literal
`KEY=value` syntax without surrounding quotes or shell expansion. Double quotes/control
characters in passwords are unsupported. Use an uppercase, unquoted scenario username;
SYS, SYSTEM, ADMIN and PUBLIC are refused. Account, wallet path and alias must match the JSON.
The file must be a regular file owned by you with mode 600 or 400; symbolic/hard links and
files inside the repository and macOS extended ACLs are refused. Do not give the agent permission to read the
file or wallet contents, and never provide the password in chat.

Ask Claude Code or Codex:

> Read scenario-prompt.md and scenario-config.json and prepare the scenario data.
> Do not read secrets or wallet contents; use only the prescribed connection wrapper.
> Ask before installing software or dropping existing prefix tables.

After reviewing the SQL, the agent runs it in this form (replace `DEMO_` with your prefix):

```sh
sh scripts/scenario/sqlcl.sh scenario-config.json scenario-results/DEMO_/run.sql
```

The wrapper validates the target, passes credentials through standard input (never process
arguments), checks the connected schema and redacts the password from captured output.
SQLcl `-R 3` restricts HOST, SPOOL and external script commands.
It writes `sqlcl-output.log`, overwritten on each invocation. The agent saves SQL scripts,
business terms, answer key and summary separately. SQLcl nonzero exit codes and recognized
ORA-/SP2- errors fail the invocation; the wrapper does not automatically retry.

macOS/Linux are supported. On Windows use SQLcl/Python and private Linux files inside WSL.
There is no native Windows/PowerShell wrapper. The Python wrapper also refuses native
Windows execution because Windows ACL validation is not implemented.

## Administrator preparation

An administrator must provision a dedicated account separately and review this example
for an existing account. Neither the app nor generated prompt creates users or grants:

```sql
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO SCENARIO_USER;
ALTER USER SCENARIO_USER QUOTA 100M ON USERS;
```

`CREATE SESSION` permits login; `CREATE TABLE` permits own tables. `CREATE VIEW` and
`CREATE SEQUENCE` permit own views/sequences when needed; omit them for this table-only
prompt. A bounded quota limits storage. Replace the username, tablespace and quota after
review. Do not grant DBA, ANY privileges, UNLIMITED TABLESPACE or privileges to PUBLIC;
do not reuse an account with excessive existing privileges.

## Results and limits

The prompt requires PK/FK relationships, table/column comments, business definitions,
database-generated fictional data, counts, orphan/uniqueness/date checks and around ten
validation questions with reference SQL and calculated answers. The answer key consists
of reference values calculated by SQL, not independently verified business truth.

The wrapper is credential transport, not a SQL sandbox. Review SQL and exact-prefix drop
lists, use a least-privilege account and trust the SQLcl installation and its global startup
scripts. Execution clears SQLPATH and uses a fresh working directory, but does not guarantee
isolation from installation-wide SQLcl settings. DDL commits implicitly and cannot be undone
by rollback. Stop for administrator help when permissions/quota are missing.

Tests cover synthetic Wallet/TNS inputs, generation, validation, translations, private-file
permissions and fake SQLcl stdin/output. Actual SQLcl connections, Oracle DDL/DML and business
correctness are outside the validation scope of this change.
