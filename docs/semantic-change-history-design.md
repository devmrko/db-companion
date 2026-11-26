# Semantic Source and generated callback change history

Source owners need to inspect which definition or generated callback changed, who requested it, and whether database readback confirmed the result. Profile-link edits and glossary-term revisions already have separate histories; they do not cover these changes.

## Scope and storage

Reuse the existing `DBC_APP_RECORD` schema, ownership checks and bounded JSON storage. Add a distinct `SEMANTIC_CHANGE` record type; no new table or migration of existing records is needed. Installation remains an explicit, reviewed action. Changes require ready history storage before any native call.

Capture SOURCE creation and supported source edits with actual before/after configuration, actor, request ID and verified/check-required outcome. For generated callback installation/upgrades, keep only component name, recognized version/state, source hash and outcome; the code itself stays versioned in Git. Do not duplicate full function sources in database history. Only the existing recognized-code installer can replace code. Credential contents, tokens, unrelated functions and business rows are excluded.

## Execution contract

1. Check authenticated owner, selected schema, target, current fingerprint and native API support.
2. Commit the before snapshot through a separate transaction before the native call/DDL.
3. Recheck the fingerprint immediately before execution. Never replace an unknown function or retry an uncertain call.
4. Requery the database and commit the after snapshot. Native error, readback failure or partial DDL is check-required; history persistence alone is not database success. If follow-up history fails, preserve the committed pending record and report uncertainty.
5. Read-only history listing/detail uses the same owner scope after relogin. Do not backfill events that were never captured.

## Interface

Use one shared history panel on Semantic Context and glossary-function pages: storage status and reviewed installation, bounded recent-event list, then one selected event's before/after detail. Source rows can select their history. Supported native edits must use a preview token, fingerprint check and explicit confirmation; expose only operations whose real API contract has been verified. No delete/recreate workaround for editing.

## Validation

Test missing-storage abort before DDL, own-schema boundary, stale preview rejection, durable before snapshot despite native failure, uncertain readback, history-write failure, bounded payloads, escaping and relogin lookup. Verify real source create/edit and recognized generated-function installation in the isolated rehearsal ADB, then repeat NL2SQL. Public tests use synthetic names. The APEX third-party chart error is a separate sample compatibility fix and must not be mixed into Companion history storage.
