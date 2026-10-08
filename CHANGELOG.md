# Changelog

## 0.1.0-alpha.6

Prepared on 2026-10-08. Sixth public alpha; not a production-certified release.

### Improved

- Four-step document enrichment: upload, review AI input, review term candidates,
  then compare and save. Only the current step's controls are visible.
- Separate keyboard-accessible tabs for document enrichment and JSON transfer.
- Optional source-chunk selection, search and embedding moved into advanced
  settings. Missing local-model notices stay inside embedding settings.
- AI consent appears after payload preparation; save consent appears only after
  comparison. Changing selections invalidates pending review, and completed saves
  show a confirmation instead of another save action.
- Responsive step indicators and translated guidance in all four UI languages.

### Validation and scope

- Added synthetic workflow and keyboard-navigation regression tests, including
  consent gates, failed AI responses and prevention of automatic retries.
- No database schema, account, profile or paid-provider changes. Publication does
  not restart the running application; live business correctness remains unverified.

## 0.1.0-alpha.5

Prepared on 2026-10-08. Fifth public alpha; not a production-certified release.

### Added

- Document-assisted glossary enrichment from bounded TXT, Markdown, DOCX and text
  PDF uploads, source chunks and a session-local keyword index.
- Optional DB-local ONNX embedding and semantic search, with explicit consent.
- AI Assistant summary and business-concept / detailed-rule proposals with checked
  source quotations, payload consent and one-use requests without automatic retries.
- Candidate review, existing-term comparison and selected saves through the glossary
  history transaction. New candidates are inactive by default.

### Improved

- Separate document and JSON-transfer cards, consistent file-input styling,
  spaced consent/save controls and readable import-comparison cards.

### Scope

- No account-specific configuration, source documents, credentials or customer data.
- No automatic model installation, persistent vector index, OCR or database setup.
- Source citation matching and successful SQL execution do not certify business
  correctness. Live Oracle/paid AI and deployment require separate verification.

## 0.1.0-alpha.4

Prepared on 2026-10-06. Fourth public alpha; not a stable or production-certified
release. Database objects are not installed automatically.

### Added

- A shared glossary-to-RDF question workflow for Ontology query and Select AI test,
  scoped source/rule selection, SQL generation, separate review and execution.
- An interactive question-evidence graph using locally bundled Cytoscape. Dictionary
  source references, selected joins, reference candidates and independent aggregates
  have distinct meanings; inspecting a graph never approves or executes a relation.
- Business glossary JSON export/import with validation and explicit application.
- Bounded column statistics and inspected local-view support in ontology workflows.
- Permission-aware AI SQL history and up to three possible generated-SQL matches.
  Candidate matches are evidence for investigation, not proven call/result mappings.
- An optional account-neutral semantic-query Agent example with bounded model output,
  saved SQL/results and authenticated APEX recovery integration components.
- First-use guides in four languages, a screen guide and expanded SQL help references.

### Improved

- Property graph listing/querying and JDBC JSON normalization, while preserving
  candidate/approved/stale relationship distinctions.
- AI Assistant and Select AI profile output-token editing and inherited defaults.
- RDF search context size, question/selection invalidation, evidence summaries and
  source-specific SQL-history help and layout.
- Callable query numeric handling for Oracle NUMBER, BINARY_FLOAT and BINARY_DOUBLE.
  Decimal strings preserve numeric precision; non-finite values are rejected.
- Long glossary criteria, value sampling and result/error diagnostics.

### Publication scope

- Schema, profile and credentials remain deployment inputs. Customer records,
  recording artifacts, operational jobs, recipients and webhook secrets are excluded.
- Reporting and threshold-alert guidance describes an integration pattern, not a
  preinstalled reporting service. Document chunking/embedding and Graph Server
  embedding are not included in this change.
- Successful execution and AI explanation do not certify business correctness.

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
