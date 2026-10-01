# Persistent relationship discovery

Relationship discovery saves validated equality mappings as `CANDIDATE` links in
versioned ontology documents and RDF. Candidate storage is not approval: confirmed
query paths and property graph creation still use only approved mappings or
validated foreign keys. Table/column definitions and their approval state are
preserved. Existing ontology versions remain available.

`DBC_APP_RECORD` stores `ONTOLOGY_DISCOVERY_PLAN` and
`ONTOLOGY_DISCOVERY_CALL` records. It must be explicitly installed and ready before
AI execution. The app does not create a store, grant privileges, or change the AI
profile implicitly. Record ownership follows the logged-in schema.

## Execution and recovery

- Preview is read-only. Consent stores the immutable plan, its original document
  references, definition hashes, profile, and call count.
- The default authorization covers at most 12 sequential calls. An explicit
  “all remaining calls” choice authorizes the remainder of that plan.
- Each call is durably claimed with a deterministic unique record ID **before**
  the paid request. Unknown or failed calls are not automatically retried.
- Valid mappings from a partially invalid response are saved; per-item validation
  issues and the bounded original response remain in the receipt. Duplicate
  mappings are not repeatedly added to ontology documents.
- All candidate revisions for a call and its success receipt commit in one
  transaction. A failed save retains the received response when possible and stops
  further calls. Explicit save recovery does not call AI again.
- A response failure with a durable receipt does not prevent the next authorized
  call. Storage/transport uncertainty or changed definitions stop execution.
- After logout/restart, load a saved analysis. Historical ontology versions rebuild
  the original input plan; current definition hashes must still match. Unattempted
  indices can continue with new consent. A pending receipt is treated as unknown,
  not successful or safe to replay.

Candidates with extra filters, date/snapshot selection, or deduplication notes can
be retained when the column equality mapping is valid. Conditions are descriptive,
never executable SQL. Sensitive/missing/incompatible columns remain excluded.

The full selected table-pair plan is covered, but AI discovery is not a guarantee
of every real business relationship. A completed plan with failed or partial
receipts is displayed as incomplete, not as a successful exhaustive analysis.
An old application's unsaved session candidates are not automatically migrated by
a restart. Preserve or review them before deployment.

## Verification boundaries

Synthetic tests cover mixed valid/invalid mappings, persisted RDF round trips,
restart restoration, full-run failure continuation, consent, one-use claims,
missing storage, and save-only recovery. Live AI correctness and actual Oracle
write behavior require separately authorized verification. No provider calls or
business-data reads are needed for the synthetic tests.
