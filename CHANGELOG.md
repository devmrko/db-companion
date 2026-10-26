# Changelog

## Unreleased

No additional changes recorded.

## 0.1.0-alpha.19

- Configure missing settings directly in each profile. Display unset model,
  Credential, object_list and supported options without copying another profile.
- Open an empty object picker for an absent object_list and require explicit
  selection for unset boolean settings. Display provider-specific OCI/Azure fields.
- Preserve owner checks, optimistic concurrency, history and unrelated-attribute
  readback checks when adding a setting. Unknown missing attributes remain blocked.

## 0.1.0-alpha.18

Prepared on 2026-10-26, integrating all alpha.17 changes.

- Add version/API-gated native Select AI Semantic Context configuration, source
  creation, profile source selection and callback Function links. Custom global
  callback settings are generated from the selected Function.
- Add the glossary native callback and shared lookup integration for the callable
  Agent package. Recognize exact shipped legacy code before an explicit upgrade.
- Add ADMIN user selection and reviewed DBMS_AI_SEM EXECUTE grants.
- Validate callback JSON with explicit input/result errors and automatic escaping.
  Verify glossary definitions in ordinary Select AI SHOWPROMPT without Agent Tools.
- Keep native source labels separate from existing semantic deployment versions.

## 0.1.0-alpha.17

Prepared on 2026-10-16. Browser-driven database setup and management; not production-certified.

- Extend Functions > AI code explanation with inputs/outputs, implemented validation,
  ordered processing steps, exceptions and side effects, requesting references to
  numbered source sections in the current UI language.
- Add business-purpose input after the initial explanation. Review updated transmission
  and give fresh consent to generate a separate context-aware explanation; retain the
  initial code explanation for comparison. Context is temporary and does not update
  routine code, comments or glossary definitions.
- Read routine source and arguments afresh and reject changes before the AI call.
  Enforce bounded context/payloads and the existing one-use consent ticket. No routine
  execution or automatic AI retries.

- Add ADMIN account setup and scoped privilege buttons, actionable ADMIN guidance,
  SCOTT demo installation, RDF prerequisite setup, and session-expiry guidance.
- Add credential change/recovery previews, function repair, and profile restoration
  within an open editor, with identity and concurrent-change checks.
- Fix RDF snapshot readback and relationship candidates for ordinary schema owners.

- Add owner-scoped scheduler creation, editing, one-off execution, enable/disable
  and previous-settings recovery, with disabled defaults and explicit confirmation.
- Add DDS role settings, OCI group mapping previews and data-grant management;
  restrict unsupported policies and keep connection/authentication management separate.
- Add ADMIN-only fixed-provider outbound HTTP ACL lookup, preview and consented
  addition, with single-use tickets, state checks and readback. Explain ORA-24247
  and link to the required ADMIN action.
- Fix initial ORDS schema registration transaction handling and safe Oracle error
  details; support the nullable VPD edition catalog column without enabling editioned policies.
- Warn before saving a duplicate glossary term while preserving intentional duplicates.

Validation: Java 1,444 tests passed; JavaScript 699 passed, one optional test skipped.
Build, PMD and publication checks passed. Isolated browser checks covered setup,
metadata capture, supported configuration recovery, JSON export, unpublished ORDS,
VPD, local DDS operations, audit collection and scheduler execution/state changes.
AI and Feedback calls were attempted but failed; successful paid AI output and
Agent execution are not verified. Outbound ACL lookup/preview were exercised but
actual application awaits approval. Credential secret changes, OCI group-mapping
writes and public ORDS endpoint invocation remain unverified. See [validation](docs/validation.md).

## 0.1.0-alpha.16

Prepared on 2026-10-14. Deployment numbering and setup feedback fixes; not production-certified.

- Show automatically assigned numeric deployment versions (v1, v2, ...) separately
  from descriptions. Preserve UUID references and snapshot hashes; operational
  rollback leaves version numbers unchanged.
- Add explicit missing-storage SQL preview and consented creation on Deployment
  versions. Enforce owner scope, privilege checks, CSRF and expiring single-use
  tickets. Never install on read or replace an existing table.
- Show progress, errors and empty operational-history messages. Remove the
  misleading fixed history count. Log safe error category/Oracle code for diagnosis.
- Batch glossary revision enrichment during initial snapshot capture instead of
  repeatedly checking the archive per term. Retain physical metadata validation.

Validation: Java 1,388 tests passed; JavaScript 676 passed, one optional test skipped.
Build, PMD and publication checks passed. Real Oracle DDL, number allocation/concurrency
and signed-in browser behavior remain unverified (browser connector failed to load).
No existing business definitions or profiles are changed by deployment. Initial
creation performance is improved in code; the reported runtime failure was not reproduced.

## 0.1.0-alpha.15

Prepared on 2026-10-14. Deployment-version UI separation; not production-certified.

- Add a dedicated Deployment versions page and sidebar menu. Show version name,
  operational status, creator and creation time, with view, revise and promote actions.
- Keep only the version selector and management link on Ontology Query and Select
  AI Test. Explicitly distinguish the retained test selection from operational
  deployment and label live definitions as an unreleased working copy.
- Show deployment members and item revisions before the expandable JSON details.
  Clear stale editor state and reuse shared responsive layout classes.
- Initial deployment setup no longer changes an existing test selection.

Validation: Java 1,385 tests passed; JavaScript 675 passed, one optional test skipped.
Build, PMD and publication checks passed. Tests cover authentication, CSRF, four-language
template rendering and separation of query selectors from management actions.
Signed-in browser layout and real Oracle persistence remain unverified; no DB objects,
definitions, profiles or deployment records were changed during this UI update.

## 0.1.0-alpha.14

Prepared on 2026-10-14. Semantic deployment versioning alpha; not production-certified.

- Save immutable owner-local deployment versions containing active glossary terms,
  saved ontology and its embedded table/column metadata. Record item revisions,
  snapshot hashes and parent deployment IDs; do not copy business data.
- Initialize an operational version explicitly, create revised deployments from
  existing item revisions, and separately promote or roll back the operational
  pointer. Preserve original versions and record actor, time and switch reason.
- Select deployment versions in Ontology Query and Select AI Test. Persist the
  test selection per DB account, invalidate old evidence on switching, and reject
  missing or stale version references instead of silently using current definitions.
- Unify change-history presentation with changed by, changed at and change details.
- Reuse shared UI layout and provide Korean, English, Japanese and Chinese labels.

Validation: Java 1,385 tests passed; JavaScript 674 passed, one optional test skipped.
Build, PMD and publication checks passed. Actual Oracle persistence, signed-in UI
interaction and paid AI generation with deployment versions remain unverified.
Profiles, Feedback, physical metadata and business data remain live. Frozen glossary
search uses registered term/alias matching, not the live Oracle Text index. External
APEX/Agent consumers and profile A/B comparison do not support deployment versions
in this release. See [design and limits](docs/semantic-release-design.md).

## 0.1.0-alpha.13

Prepared on 2026-10-14. Advisory evaluation update; not a production-certified release.

- Add advisory LLM review of saved problem-question attempts, followed by a
  separate human verdict and required reason. Preview evidence and obtain
  one-call transmission consent; do not execute SQL or change business rules.
- Preserve criteria fingerprints, original evidence, reviewer profile/version,
  timing and human decisions in owner-local evaluation history. Support retrying
  a failed save without repeating the AI call and include evaluations in confirmed
  question deletion. Batch evaluation and accuracy dashboards remain future work.
- Design: [Question evaluation and human decisions](docs/problem-evaluation-design.md).

Validation: Java 1,374 tests passed; JavaScript 665 passed with one optional test
skipped. Quality, packaging and publication checks passed. New tests cover
consent, stale evidence/profile settings, human verdict requirements, save retry,
duplicate prevention, session ownership, CSRF and late UI responses. Actual
Oracle evaluation persistence and paid AI review remain unverified.

## 0.1.0-alpha.12

Prepared on 2026-10-14. UI consistency update; not a production-certified release.

- Reuse shared card headings, section spacing, field, checkbox, empty-state and
  graph-container classes. Align single-line controls and buttons at 38px.
- Fix creation-dialog headings, table action wrapping, dashboard build details,
  history guidance spacing, workbench controls and profile-editor loading layout.
- Show relationship labels on selection and fit the initial graph to its canvas;
  retain the complete relationship list and evidence details.

Validation: Java 1,363 tests passed; JavaScript 663 passed with one optional test
skipped. Quality and publication checks passed. Desktop browser checks covered
dashboard, profile creation layout, VPD controls, DDS empty state and relationship
rendering. No database writes or paid AI calls were made. Mobile layouts and all
permission-dependent paths were not revalidated in this update.

## 0.1.0-alpha.11

Prepared on 2026-10-13. Eleventh public alpha; not a production-certified release.

- Add owner-local Credential creation (username/password or API key) and Function
  creation with reusable echo and installed-glossary templates. Functions accept
  a CLOB and return a CLOB with invoker rights; creation does not execute them.
- Preview code, require consent, consume short-lived session-bound plans once,
  refuse replacement, and display database readback and compilation errors.
  Credential secrets are excluded from previews, history and stored plans.
- Classify creation failures with a sanitized Java runtime exception and common
  HTTP handler: validation, duplicate, permissions, references, timeout and DB
  failure. Distinguish preflight rejection from possibly-created objects and
  never automatically retry an uncertain write.

Validation: Java 1,363 tests passed; JavaScript 656 passed with one optional test
skipped. PMD and publication checks passed. Browser-only creation on a separate
test database covered Credential, both Function templates, Profile, Tool, Task,
Agent and Team, plus duplicate rejection, visible INVALID compilation errors,
permission denial before DDL, and desktop/mobile layouts. External credential
authentication and paid AI execution were not tested. Test credentials and
database-specific artifacts are excluded from publication.

## 0.1.0-alpha.10

Prepared on 2026-10-13. Tenth public alpha; not a production-certified release.

- Check effective Oracle Text setup privileges before issuing a preview and
  again before executing setup DDL. Accounts without `CTX_DDL` execution access
  receive an actionable permission message before any Text object is created.
- Document fresh-account Oracle Text prerequisites without automatically granting
  privileges or depending on a particular database account.

Validation: Java 1,354 tests passed; JavaScript 654 passed with one optional
runtime integration test skipped in the standard suite. PMD and publication
checks passed. A separately provisioned Autonomous 26ai database confirmed
fresh-account installation, phrase/alias search, input rejection, account
isolation, and desktop/mobile UI operation. Synthetic quoted and HTML-like
definitions retained their text through save, JSON export and import preview.
Live permission checks covered both permitted and restricted accounts, with no
AI calls. Private connection details and test artifacts are not published.

## 0.1.0-alpha.9

Prepared on 2026-10-13. Ninth public alpha; not a production-certified release.

- Replace the generated PL/SQL-in-CLOB search implementation with named package
  functions and a short entry function. Only bounded owner-local SELECT text is
  dynamic; search values remain bound.
- Show the actual SEARCH calling order, package specification, package body and
  entry function separately. Installation previews show only planned DDL.
- Permit explicit migration of an exact app-generated v1 function; block foreign
  sources and changed previews. Preserve recognized installed components and
  expose compilation or partial-installation errors without automatic cleanup.
- Preserve the response contract, overlap rules and search limits. Existing AI
  packages are not silently migrated; no grants or dictionary changes occur.

Validation: Java 1,353 and JavaScript 666 tests passed, with no PMD violations or
publication findings. Authorized Oracle checks separately confirmed eight
equivalent search responses before/after migration, compilation readiness and
unchanged existing AI package source. Live UI checks confirmed separate code
sections and dictionary search without AI calls.

## 0.1.0-alpha.8

Prepared on 2026-10-13. Eighth public alpha; not a production-certified release.

### Added

- Owner-local glossary search function wizard: prerequisite checks, generated
  code, expiring single-use creation consent and question-to-JSON search tests.
  No profile or AI call is required. Existing objects are never replaced.
- Common SQL/PLSQL code display with syntax highlighting, line numbers,
  source search, wrapping and original-source copying.

### Improved

- Shared generated glossary helpers preserve the existing callable AI package
  while exposing a separate search-only module.
- Responsive RDF editing, busy-control guards, stale analysis-response rejection
  and stable export filenames. Import previews validate RDF source mappings
  before selected saves.

### Validation and scope

- Java 1,352 and JavaScript 664 tests passed; PMD and publication checks passed.
- Synthetic UI checks cover creation consent, four languages and mobile layout.
  Authorized Oracle validation separately confirmed compilation, search-only
  JSON results, SQL invocation and input rejection without paid AI calls.
- No account-specific credentials, source records or configuration are published.
  No automatic installation, privilege grants or existing package replacement.

## 0.1.0-alpha.7

Prepared on 2026-10-13. Seventh public alpha; not a production-certified release.

### Added

- Optional business-meaning RDF and fully qualified table/column mapping RDF in
  glossary editing, revision history, Turtle export and portable JSON format v2.
  Format v1 remains supported and does not silently erase existing RDF mappings.
- Display-only SQL AST comparison with saved source/column mappings. This does
  not certify aggregations, date ranges, filters, joins or business correctness.
- Build version/time display and login-scoped feature-access observations.

### Improved

- Distinct RDF search intent and genuine missing-input clarification contracts.
  AI-free dictionary searches use the RDF source-selection workflow as well.
- Assistant readiness guidance, empty-list presentation, glossary character
  budgets and rejection of over-limit paste without silent truncation.
- Internal Text/RDF/vector object filtering and SQL-candidate exclusions.
- Permission-aware DDS tabs and audit actions, folded diagnostics, two-week
  audit defaults and separate read/install/status/pause SQL examples.
- Configured SQL timeout guidance and preservation of reviewed translations.

### Scope

- No automatic database installation, permission grants, source-table changes,
  profile changes or relationship approval. New RDF mappings are user declarations.
- RDF extensions share the existing term/history transaction; history readiness
  remains mandatory for saves. Non-local mappings are rejected before saving.
- Synthetic file-upload, RDF/JSON, SQL-reference, HTTP and lifecycle tests are
  separate from real-database and paid-AI verification.

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
