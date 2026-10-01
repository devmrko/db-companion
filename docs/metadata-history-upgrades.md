# Metadata history code versions

The schema owns one shared `DBC_METADATA_AUDIT` package. Each tracked table or view has its own BEFORE/AFTER `DBC_MH_*` trigger pair. Package and trigger versions are identified separately by their known source, not by a comment marker alone.

| Version | Shared package | Trigger pair |
| --- | --- | --- |
| v1 | Table metadata snapshots | Table metadata events |
| v2 | Adds materialized-view comment snapshots | Adds column-comment event matching |
| v3 | Adds regular-view comment snapshots | Adds view event matching |

The v1/v2 definitions remain in source to recognize previous installations. New installations and upgrades use v3; the application does not install old versions or offer downgrades. Unknown/custom source is not automatically overwritten. A source version is not proof that the object is valid or history collection is enabled.

## Inspect and update

Open a table/view, then **Installation check**. The dialog summarizes the shared package and selected object's triggers separately. Verified completed components show **Ready**; incomplete components retain their next action and blockers. Version explanations, internal trigger names and raw diagnostics are collapsed under **Inspection details**. A current version alone does not establish readiness: validity and compatibility must also be confirmed.

On the table/view page, a healthy installed and enabled configuration shows only **History collection is on**, without a setup guide. This also applies to read-only accounts; management controls remain disabled without the required privileges. Installation check remains available on demand. Missing, invalid or unconfirmed installations do not use the completed state.

When management controls are disabled, the table/view page shows a concise status and a collapsed **Setup instructions** disclosure. Confirmed missing installation/privileges use **History collection not set up · Installation privileges needed**. The exact privilege reason, next action and administrator instructions remain available inside the disclosure; missing privileges never auto-expand it. Failed or unconfirmed checks retain their error text above the disclosure. An authorized administrator who only needs a code update is not told to log in again. Existing state checks supply this guidance; opening a table does not add a schema-wide dependency inspection.

**Open installation check** leads directly to the existing read-only inspection. **Copy setup instructions** copies the selected schema/object and administrator steps so they can be retained for a manual account switch. If clipboard access is unavailable, a selectable text field is shown. Use the same database/Wallet after switching accounts. This guide does not save credentials, perform automatic login, elevate privileges, or run setup actions automatically.

1. **Update shared package → v3** replaces only the known v1/v2 package body. The selected object's triggers may be missing. All tracking in that schema and every dependent trigger must be OFF; dependent objects must be fully visible and compatible shared assets must be valid. Unrecognized extra dependencies block the update. Updating another owner's package needs `CREATE ANY PROCEDURE`; updating one's own needs `CREATE PROCEDURE` or `CREATE ANY PROCEDURE`. A trigger-management privilege is not required for this package-only action. The action does not grant privileges, create/replace triggers, or enable history.
2. **Update this table/view's triggers → v3** requires the current shared package and an intact OFF installation. It replaces only known old triggers for the selected object and leaves them OFF. Normal trigger-management privileges still apply.
3. If triggers are absent, close the dialog and use **Install and enable triggers** after the package update. This is a separate explicit action with its own privileges. Existing installer ownership is retained.

The server repeats checks before writing and verifies the resulting source. DDL may persist even if subsequent verification fails; no automatic retries or rollbacks of DDL are claimed. Other sessions/external DBA activity are not globally locked: use an agreed maintenance window and refresh installation status after changes.

GET inspection never changes DB state. Real database upgrades require explicit operator action. Existing history rows, package specification, and business data are not replaced by a package-body update.
