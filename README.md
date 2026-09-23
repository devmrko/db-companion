# DB Companion

**Version: 0.1.0-alpha.1** · Java 21+ · Spring Boot · MIT

A self-hosted workbench for inspecting Oracle database metadata, Select AI configuration,
feedback, execution information, and governed business definitions.

This is an independent personal project by Joungmin Ko. It is not an official Oracle
product, is not affiliated with or endorsed by Oracle or any customer, and carries no
vendor support commitment. Product names identify the systems with which it works.

## Features

- Wallet selection at login and database connections scoped to the login session.
- Table filtering by schema, name, or Select AI profile object list.
- Table/column comments, annotations, constraints, and optional change-history controls.
- Select AI profile and Agent/Team/Task/Tool inspection and supported editing workflows.
- Feedback search, details, supported authoring, and optional history tracking.
- SQL generation and review with profile settings, related metadata, feedback, and
  generated-SQL table references shown together.
- Read-only result execution after explicit review, with SQL/object/row limits.
- Ontology definitions, versions, reviewed relationships, and scoped AI suggestions.
- Read-only Deep Data Security, scheduler, credential metadata, and infrastructure views.
- Korean, English, Japanese, and Simplified Chinese interfaces.

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

```sh
./shutdown.sh
./restart.sh
```

The scripts select the built `target/db-manage-companion-*.jar`, copy it to `.run/app.jar`,
record the PID and process identity, and use graceful shutdown. They do not kill an
unrelated process occupying the port. Logs are written to `logs/app.log`; protect these
files as potentially sensitive. Set `JAVA_HOME` to choose a JDK and `PORT` for another port.

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
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.1.jar node --test src/test/js/app-runtime.test.mjs
```

Translation sources are public development assets under `tools/i18n`, not private
project documentation. Regenerate the bundled resources with `node scripts/i18n-bundles.cjs`.

## Safety and limitations

- Start locally. The default address is `127.0.0.1`; internet deployment requires a
  separate security and deployment review, including HTTPS and access controls.
- Use least-privilege database accounts. Do not grant broad administrator privileges
  merely to enable a screen. Unsupported/unauthorized features can remain unavailable.
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
