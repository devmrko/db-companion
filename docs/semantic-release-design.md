# Semantic release design

## Agreed design — deployment versions, not branches

The existing item histories remain authoritative. A deployment version freezes the
complete query-enabled semantic set at save time, including each item's revision.
There are no development/production copies of dictionary tables and no merge engine.
Table/column metadata is the snapshot embedded in each saved ontology revision, not
an independent restoration of physical DB comments. Disabled terms are excluded
from the query-enabled set; their ordinary edit history remains untouched.

On first use of the deployment-version workflow, an explicit CSRF-protected setup
POST automatically captures the initial set and designates it as operational in one
transaction. GET, login and search never write. If an operational pointer already
exists, initialization returns that version without creating another. A concurrent
initialization must roll back on pointer conflict, not leave a partial deployment.
Missing storage or empty ontology requires setup; neither triggers automatic DDL.

Editing a deployment version starts from its immutable membership. The administrator
can select a historical revision for each term or ontology source, add items, or
remove items. The server resolves those references from existing version histories,
not from client-supplied definitions. Unchanged members retain the parent's frozen
content even when the current source changes. Save produces a new release with a
parent ID; it does not update the original or switch the operational pointer.
To change definition text, use the existing term/ontology editor, then choose that
new item revision in the deployment editor. Activation state is never overridden.

Normal use resolves the operational pointer; admin testing can explicitly select a
different saved deployment or live development definitions. Each request/session
pins the selected release. Promotion/rollback is a separate audited action. The
supported consumers in this phase are DB Companion Ontology Query and Select AI
Test; external APEX/Agent/standalone PL/SQL consumers do not yet use this pointer.

## Scope and contract

A release is an immutable owner-local snapshot of enabled business dictionary terms,
saved ontology entries, and the table/column metadata contained in those entries.
It is not a database restore, a copy of business data, or a frozen model environment.
Profiles, Feedback, credentials, permissions, physical schema and data remain live.
The UI must identify these boundaries before users select or promote a release.

## Storage and lifecycle

Reuse the validated owner-local DBC_APP_RECORD store. No automatic DDL. Store releases
and deployment events as separate typed JSON records. Publication creates a new
release; there is no update/delete endpoint. Hash the serialized snapshot and verify
it on read. Enforce bounded size and reject incomplete snapshots rather than truncate.
Use a fixed owner-local deployment pointer row, optimistic expected revision and a
transactional deployment event. A stale browser cannot overwrite a newer deployment.
Rollback selects a previously saved release; it does not overwrite dictionary or DB
comments. Actor and timestamps originate in the authenticated DB session.

## Query binding

Select a release explicitly for testing, or resolve the currently deployed release.
Persist the selection per authenticated DB account, not per browser. On login resolve
the saved explicit release first, then the deployed default; an explicit LIVE choice
uses development definitions. Selection updates use optimistic revision checks.
Users sharing a DB login necessarily share this preference. Editing a definition does
not publish it or change this preference. APEX/SSO identities are outside this phase.
Explicit development/live mode preserves the existing editing workflow. Once a
selection is resolved, pin it for that session's in-flight search and generation;
changing it clears glossary, ontology and AI-test preview/evidence tickets. Never
change a running request's inputs. Record release ID and SHA-256 in transmitted
evidence so existing stored question/response evidence can retain provenance.
No automatic fall-back to latest definitions if a saved release fails validation.

Frozen glossary search initially uses registered exact phrases/aliases, explicitly
labelled in the UI, not the mutable Oracle Text index. RDF retrieval uses the frozen
entries and existing relation validation. Current physical columns/keys and profile
object scope remain mandatory execution checks, even for old releases. SQL generation
must disable live comments/annotations and restrict its object list to frozen sources.
Unsupported generation paths must reject a release instead of silently mixing versions.

## UI

Shared semantic-version panel in Select AI Test and Ontology Query: deployed default,
explicit release, development/live selection; snapshot contents; create from current
saved definitions; promote/rollback with expected pointer and explicit confirmation;
deployment history using Changed by / Changed at / Change details. Creation, selection
and deployment are separate actions. Switching production does not mutate live sources.

## Verification

Test immutable snapshot/hash validation, owner/database isolation, bounds, concurrent
deployment compare-and-set, transaction rollback, missing releases, preview invalidation,
no-live-definition fallback, request attributes, shared UI/i18n and old-mode regression.
Automated tests do not establish DB runtime compatibility or answer correctness.
Actual DB writes and paid AI validation require separate user approval.

## Implementation limits and rollout

- The management panel is shared by Ontology Query and Select AI Test. It is not a
  separate branch manager. Revision numbers reference existing item histories.
- A complete query-enabled snapshot is bounded at 2,000 active terms, 100 saved
  ontology sources and the existing archive JSON limit. Exceeding a limit fails the
  whole save, not a partial release. At least one saved ontology source is required.
- The version list and deployment log show the latest 101 records. This is a list
  limit, not a retention policy; saved releases are not deleted.
- Test A/B profile comparison does not support frozen releases yet and is rejected
  in that mode. Test each chosen release separately. No automatic accuracy score.
- Selecting DEFAULT before initial deployment fails with setup guidance instead of
  silently using latest definitions. LIVE is an explicit development choice.
- Server tests use synthetic fixtures. Real Oracle transaction/privilege behavior,
  signed-in browser interaction and paid model generation require separate checks.
