# Changelog

## 0.1.0-alpha.2

Prepared on 2026-09-28. Second public alpha; not a stable or production-certified release.

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

### Scope

- Business definitions, credentials, Wallets, customer data and private project documents
  are not included. Database setup remains an explicit, separately authorized operation.
- Generated SQL is not a verified business answer. A/B comparison does not yet accept
  glossary/ontology evidence, and batch SQL generation is not an automatic correctness test.
- See [validation notes](docs/validation.md) for checks and unverified external paths.

## 0.1.0-alpha.1

Initial public-source candidate, prepared on 2026-09-23.

- Database metadata, Select AI, Feedback, history, ontology, and security-inspection UI.
- Multiple Wallet selection and PID-aware startup/shutdown/restart scripts.
- Separated internal records from distributable code and public documentation.
- Sanitized environment-specific examples and isolated customer diagnostic code.
- Added reproducible PMD validation and publication checks.

The release is an alpha. See `docs/validation.md` for results and remaining limitations.
