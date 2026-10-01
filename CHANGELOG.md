# Changelog

## 0.1.0-alpha.3

Prepared on 2026-10-01. Third public alpha; not a stable or production-certified release.

### Added

- ORDS schema/module/template/handler management, source previews and explicit REST
  endpoint testing against separately configured trusted HTTPS destinations.
- VPD policy management with table/view and privilege filters, unsupported-policy
  read-only inspection, and policy-function source dialogs.
- SQL-cache archiving and DDS unified audit history with explicit Oracle Scheduler
  installation, archive queries, retention controls and privilege setup workflows.
- Callable glossary/optional-ontology query package setup, context preview and testing.
- Native Oracle RDF metadata collection, relationship candidate persistence and
  metadata property graph previews/creation without copying business rows.
- Optional post-execution AI explanation/review using captured SQL and evidence;
  result cell values are excluded and numerical correctness remains unverified.

### Improved

- Profile-scoped metadata imports, internal/app-storage filters, existing metadata
  recapture, and preservation of business definitions and historical versions.
- Durable relationship discovery plans and per-call candidates, resumable unattempted
  calls, failure diagnostics and explicit consent for remaining-call execution.
- Metadata audit package upgrade/setup guidance and profile/object-scoped history
  access management, separating record collection from viewing/management permission.
- Responsive management screens and setup/help/status wording.

### Scope

- Existing customer data, private documents, Wallets, credentials and demonstration
  DB accounts/jobs are not included. Actual database setup remains separately authorized.
- AI review is advisory, not an independent verification of returned values.
- See [validation notes](docs/validation.md) for reproducible checks and limitations.

## 0.1.0-alpha.2

Prepared on 2026-09-28. Second public alpha; not a stable or production-certified release.

Historical commit: `6c68248` (233 changed files; 11,918 insertions and 508 deletions).
The original short commit message is preserved; the module details below document
that published change without rewriting its commit or tag.

### Added

- Independent business dictionary with aliases, Oracle Text phrase/morphological search,
  automatic prompt attachments and atomic before/after history for app-managed edits.
- Problem questions with selected execution snapshots, profile A/B comparison, optional
  AI comparison and explicit batch SQL generation without automatic SQL execution.
- Profile readiness checks, credential/profile setup guidance and operation-specific
  SQL/PLSQL help, including ontology evidence, tokenization, FK lookup and query examples.
- Explicit question-analysis language/policy configuration and relationship routes that
  show both covered and unmatched concepts when only part of a question is connected.
- Select AI request progress, per-stage timings and separately configurable SQL and
  generation timeouts, with original Oracle errors and no automatic AI retry.

### Improved

- View metadata editing and comment history alongside tables.
- Database-authorized query execution instead of the application SQL/function allowlist;
  SQL review, database privileges, read-only transactions and runtime limits still apply.
- Glossary setup/history status, SQL reference inspection, responsive forms and help dialogs.

### Implementation areas

- `BusinessGlossary*`, `OracleGlossaryStore` and phrase-search SQL provide the
  independent dictionary, search attachments and app-managed version history.
- `QuestionAnalysis*`, `QuestionLanguage`, `OntologyInquiry` and `OntologyPaths`
  provide language-specific Oracle Text analysis and partial relationship coverage.
- `ProblemQuestion*`, `SelectAiBatch*` and `SelectAiComparison` provide selected
  problem snapshots, profile comparison and explicitly approved batch generation.
- `SelectAiProgress`, `SelectAiTestService`, execution settings and Oracle error
  details separate generation/query timing and retain original failure context.
- Metadata repositories/services add view support and app-managed comment restoration;
  setup and SQL-help modules expose required privileges and operation examples.
- The public validation grew from 772 to 1,016 Java tests and from 348 to 482
  regular JavaScript tests. These are regression checks, not business-answer certification.

### Scope

- Business definitions, credentials, Wallets, customer data and private project documents
  are not included. Database setup remains an explicit, separately authorized operation.
- Generated SQL is not a verified business answer. A/B comparison does not yet accept
  glossary/ontology evidence, and batch SQL generation is not an automatic correctness test.
- See [validation notes](docs/validation.md) for checks and unverified external paths.

## 0.1.0-alpha.1

Initial public-source candidate, prepared on 2026-09-23.

Historical commit: `90775c3` (547 added files; 59,250 inserted lines). This was the
initial public import, not a claim that all functionality was created in one change.

- Database metadata, Select AI, Feedback, history, ontology, and security-inspection UI.
- Multiple Wallet selection and PID-aware startup/shutdown/restart scripts.
- Separated internal records from distributable code and public documentation.
- Sanitized environment-specific examples and isolated customer diagnostic code.
- Added reproducible PMD validation and publication checks.

### Initial module coverage

- Login-scoped Oracle pools, Wallet/service selection, schema navigation and four
  UI languages; passwords and Wallet contents are not public source artifacts.
- Metadata inspection/editing for comments, annotations, constraints and indexes;
  optional audit/history packages and explicit setup checks.
- Select AI profiles, generation/review, Feedback and Team/Agent/Task/Tool workflows,
  saved conversations, execution inspection and SQL history sources.
- Ontology definitions, versioning, RDF representation, scoped relationship analysis,
  query evidence and graph previews, plus vector search configuration/inspection.
- Scheduler, external-source, credential, function and Deep Data Security inspection.
- PID-aware local lifecycle scripts, source-publication safeguards and regression suites:
  772 Java tests, 348 regular JavaScript tests, and a separate opt-in runtime test.

The release is an alpha. See `docs/validation.md` for results and remaining limitations.
