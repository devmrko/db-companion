# DB Companion

**Version: 0.1.0-alpha.3** · Java 21+ · Spring Boot · MIT

A self-hosted workbench for inspecting Oracle database metadata, Select AI configuration,
feedback, execution information, and governed business definitions.

This is an independent personal project by Joungmin Ko. It is not an official Oracle
product, is not affiliated with or endorsed by Oracle or any customer, and carries no
vendor support commitment. Product names identify the systems with which it works.

## Features

- Wallet selection at login and database connections scoped to the login session.
- Table/view filtering by schema, name, or Select AI profile object list.
- Table/view/column comments, annotations, constraints, and optional change-history controls.
- Select AI profile and Agent/Team/Task/Tool inspection and supported editing workflows.
- Feedback search, details, supported authoring, and optional history tracking.
- SQL generation and review with profile settings, related metadata, feedback, and
  generated-SQL table references shown together.
- DB-authorized query execution after explicit review, with a read-only transaction, timeout and result-size limits. Oracle, not an application SQL/function allowlist, validates syntax and privileges.
- Live activity indicator and per-stage server timings for single-profile Select AI
  requests and query execution. The last eight timing records remain in the login
  session, including the failed stage; status polling makes no database or AI calls.
- Ontology definitions, versions, reviewed relationships, and scoped AI suggestions.
- Ontology relationship search using explicit question-analysis languages and Oracle Text
  tokens, including partially matching paths with matched/unmatched concepts displayed.
- Problem-question registration, selected attempt snapshots, and profile A/B comparisons
  of SQL, options, metadata, Feedback and separately requested SHOWPROMPT output.
- Explicit batch SQL-generation plans and selected result saving; batches do not execute SQL.
- Profile readiness checks and per-operation SQL/PLSQL help, including credential setup
  guidance and the distinction between saved ontology evidence and live data queries.
- Read-only Deep Data Security, scheduler, credential metadata, and infrastructure views.
- ORDS module/template/handler management and explicit REST endpoint testing with
  connection-specific trusted destinations and separate HTTP authentication.
- VPD policy inspection, supported changes, table/view permission filters, and policy-function source viewing.
- Explicit SQL-cache and DDS audit archiving with Oracle Scheduler, source/archive
  queries and separately authorized setup and privilege workflows.
- Callable glossary/optional-ontology query package setup and testing for external callers.
- Metadata import filters, profile-scoped refresh, persistent relationship discovery,
  native Oracle RDF collection, and metadata property graph creation.
- Profile/object-scoped metadata history setup and separate history access management.
- Optional post-execution AI explanation/review of captured question, SQL and evidence.
  Result cell values are not sent; the review does not certify numerical correctness.
- Korean, English, Japanese, and Simplified Chinese interfaces.
- Business glossary app saves retain before/after snapshots, actor and time in
  `DBC_APP_RECORD` and expose per-term change history. Saving a term and its history
  is atomic. Prepare the shared storage explicitly when missing; existing ready
  storage is reused. Earlier changes and direct SQL edits are not retroactively
  captured, and version numbers alone do not imply historical snapshots exist.

## Requirements

- JDK 21 or later and Maven 3.9+.
- Node.js 22+ for JavaScript tests and translation-bundle maintenance.
- A reachable, appropriately configured Oracle database and an extracted Wallet.
- Database permissions for each feature you use. Feature availability depends on the
  database version, installed packages, grants, and enabled services.
- Select AI credentials/profiles and an authorized provider for AI features. AI calls
  may send approved context to an external provider and may incur charges.

No database credentials, Wallet, customer dataset, or private documentation are included.

## Build and run

```sh
cp .env.example .env
# Edit .env with your own extracted Wallet path(s).
mvn clean verify
node --test src/test/js/*.test.mjs
./startup.sh
```

Open `http://127.0.0.1:8080/login`, select a Wallet and TNS service, and log in with a
database account. Enter the database password in the login form, not in Git or the
example configuration. Do not source `.env` in your shell; the application reads it.

Example configuration:

```properties
ORACLE_WALLET_PATHS=["/absolute/path/to/Wallet_DB1","/absolute/path/to/Wallet_DB2"]
```

`ORACLE_WALLET_PATH=/absolute/path/to/Wallet_DB1` is also supported. Set one form.
Restart the app after changing the configured Wallet list. Session state is not
persistent, so a restart requires a new database login.

Select AI test queries default to a 300-second SQL execution timeout. Set
`SELECT_AI_SQL_TIMEOUT_SECONDS` in `.env` or the process environment (1–3600 seconds)
and restart to change it. The SQL review dialog shows the effective value. Invalid
values fail startup rather than silently enabling unlimited queries. The execution
network wait allowance is SQL timeout + 30 seconds and its transaction budget is
SQL timeout + 60 seconds; the connection's previous network timeout is restored
after execution. These are different JDBC limits, not one end-to-end deadline.
Select AI test generation (SQL, chat, SHOWPROMPT, AI review and A/B comparison)
has a separate 300-second limit configured by `SELECT_AI_GENERATE_TIMEOUT_SECONDS`
(1–3600 seconds, restart required). Its network wait allowance is generation
timeout + 30 seconds and its transaction budget is generation timeout + 60 seconds.
The previous network timeout is restored on success or failure; failed AI requests
are not retried automatically. Other screens, SQL text, profile options and
result-size limits are unchanged. Longer allowances do not optimize SQL or
guarantee a provider response or rule out DB-side cancellation.

```sh
./shutdown.sh
./restart.sh
```

The scripts select the built `target/db-manage-companion-*.jar`, copy it to `.run/app.jar`,
record the PID and process identity, and use graceful shutdown. They do not kill an
unrelated process occupying the port. Logs are written to `logs/app.log`; protect these
files as potentially sensitive. Set `JAVA_HOME` to choose a JDK and `PORT` for another port.

## Business dictionary

The **Business dictionary** menu manages definitions independently of ontology approval and Select AI profiles. Nothing is installed automatically. If the owner-local `DBC_BUSINESS_TERM` table is missing, review the displayed DDL and confirm its creation. Oracle Text setup is a separate, explicit operation; partial or incompatible installations are reported rather than overwritten.

Register a canonical term, aliases, definition and optional aggregation/filter criteria. SQL criteria are reference text and are never executed. Terms can be edited or disabled, with optimistic revision checks. `SEARCH_TEXT` combines the term, aliases and definition; its optional CONTEXT index and `KOREAN_MORPH_LEXER` policy support Korean morphological search.

In **Select AI test**, enable **Use business dictionary** and search the current question/profile. Matched definitions are attached automatically (up to 30); inspect the registered expressions, morphological tokens, actual Text query and matched definitions before generation. The transmission preview includes the definitions, which are also recorded with the result. An oversized result is blocked rather than silently attaching only part of it. The original question and profile object restrictions are preserved; this does not enable profile annotations or alter profile settings. Search alone makes no LLM call. A/B comparison does not yet accept dictionary or ontology evidence.

Exact/alias search works without Oracle Text. Morphological search supplements original-phrase matching because tokenization may split proper names or retain request words; it is not an intent parser. The initial implementation supports up to 2,000 active terms, 64 distinct query tokens and 30 displayed candidates. Search failures are distinguished from no matches; oversized scopes are not silently truncated into purportedly complete results.

Oracle Text phrase matching accepts inflected forms of registered terms and aliases, while preserving punctuation boundaries and preferring the longest overlapping phrase. A shared word in a definition is not sufficient for attachment. The original question, including negation, is retained: retrieving a definition does not decide whether its SQL condition should be applied or negated.

## Quality checks

```sh
mvn -Pquality verify
node --test src/test/js/*.test.mjs
node scripts/publication-check.cjs
```

The quality profile runs Maven PMD 3.28.0 with its default Java rules and the PMD security
ruleset against production Java sources. Violations fail the check. PMD does not prove
that SQL, access-control policies, AI output, or database-specific behavior is correct.
See [validation notes](docs/validation.md) for the release's actual results and limits.

An optional runtime integration suite starts and stops isolated app instances on test
ports (never your already running app):

```sh
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.3.jar node --test src/test/js/app-runtime.test.mjs
```

Translation sources are public development assets under `tools/i18n`, not private
project documentation. Regenerate the bundled resources with `node scripts/i18n-bundles.cjs`.

## Safety and limitations

- Start locally. The default address is `127.0.0.1`; internet deployment requires a
  separate security and deployment review, including HTTPS and access controls.
- Use least-privilege database accounts. Do not grant broad administrator privileges
  merely to enable a screen. Unsupported/unauthorized features can remain unavailable.
- Query execution is not a SQL sandbox. A read-only transaction does not prevent a
  called function from using an independent transaction or making external calls;
  database grants and review of the SQL remain necessary.
- Metadata edits, profile edits, and history installation can change database objects.
  Review the exact target, generated SQL, and required grants before confirming.
- Deep Data Security pages inspect configured policies; they do not create policies or
  prove the effective access of every end user. Application identity propagation requires
  separate configuration and verification.
- A reconstructed SHOWPROMPT is not a historical copy of the actual transmitted request.
  A SQL table reference is not evidence of which context an LLM internally used.
- Generated SQL and AI explanations require review. A syntax-valid query can still be
  semantically incorrect. No benchmark score or business-result guarantee is provided.
- Customer-specific diagnostic routes are not part of the public distribution.
- This is an alpha release, not a production-readiness certification. Review
  [security guidance](SECURITY.md) before connecting a database.

## License

Original project code is licensed under the [MIT License](LICENSE).
Dependencies retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
Private project records and customer material are not distributed or relicensed here.
