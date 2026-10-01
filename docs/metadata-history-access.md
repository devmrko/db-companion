# Metadata history: bulk setup and scoped access

The table/view list uses a selected Select AI profile's current `object_list` as a
bulk selection, not as another history store. Object details support the same
operations for one object. All changers are captured by the existing audit
package, including their actual Oracle `SESSION_USER`; access rules never filter
which users' changes are collected.

## Administrator workflow

1. Log in to the correct connection as the recorded trigger owner with the
   required Oracle management privileges. Selecting an administrator schema is
   not authorization.
2. Select the target schema and profile in Tables / Views / Columns; open
   **Bulk history & access**. Alternatively open it from one object's details.
3. Review the resolved objects and current access rules. Select objects and an
   operation: install/enable collection, disable collection, allow read, allow
   read plus ON/OFF management, deny, or remove an override.
4. For access operations, select specific existing DB users or explicitly select
   `PUBLIC`. Confirm the scope and apply. Refresh the preview before another
   operation. Unknown code and mixed trigger owners require separate review.

An individual rule, including DENY, takes precedence over PUBLIC. Removing an
individual override restores the PUBLIC rule, or no delegated access if none
exists. An object's settings are shared across profiles: disabling collection
through one profile also affects every other profile containing that object.

The snapshot expires after ten minutes and is bound to the login session. It is
one-use, including after failure. The server rechecks the profile, selected scope,
users, privileges, and installed code. A batch stops on its first failure and
reports later objects as not run. DDL and GRANT can commit independently; there is
no claim of atomic rollback and no automatic retry. Up to 50 objects are supported;
cross-schema profiles are rejected rather than silently applying a subset.

## Restricted DB delegation

The trigger-owning administrator installs two tables, `DBC_MH_TARGETS` and
`DBC_MH_ACCESS`, plus the definer-rights package `DBC_METADATA_ACCESS` in their own
schema. No administrator name, wallet, application schema, or business object is
hardcoded. Existing audit packages and history data are not replaced.

Target enrollment requires exact known audit/trigger source and valid objects.
The gateway stores the inspected sources as baselines. It exposes only:

- `inspect(schema, object)`: current collection status and caller capabilities;
- `entries(schema, object, page)`: authorized history, at most 11 rows per call;
- `set_enabled(schema, object, 'Y'/'N')`: ON/OFF of enrolled, verified existing
  collectors. It cannot install collectors, replace code, or grant privileges.

The gateway checks `SESSION_USER` on every call; it does not trust a caller's
selected schema. Code changes invalidate the baseline until the administrator
reviews and re-enrolls it. Object owners can inspect their own collector state
without delegated management rights. Read-only delegates cannot toggle it.

An allowed user receives EXECUTE on this fixed package and, where needed, READ on
the target schema's `DBC_METADATA_TRACKING` to discover the recorded installer.
That READ exposes schema-wide tracking metadata, not history contents or business
data. No broad `CREATE ANY TRIGGER`, `ALTER ANY TRIGGER`, or
`ADMINISTER DATABASE TRIGGER` privilege is granted to delegates. Existing business
object visibility is still needed to navigate the app's object pages.

DENY revokes the effective delegated capability, not every underlying Oracle
grant. Package EXECUTE and discovery READ can remain for other objects. Object
ownership and pre-existing independent privileges cannot be revoked by an app ACL;
their holders may still query their own history directly in SQL. The gateway owner
retains administrative access. This is not a replacement for Oracle authorization.

## Cross-account status correction

An empty ordinary user's `ALL_TRIGGERS` result does not prove that an
administrator-owned database trigger is missing. Enrolled targets use the
installer's restricted gateway to verify actual trigger status and source. The
metadata-edit health guard uses that same result. No enabled tracking flag alone
is accepted as proof of health. Unenrolled targets retain the existing inspection
path. Display state expires after 30 seconds; writes always recheck the DB.

Oracle references: [ALL_TRIGGERS](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/ALL_TRIGGERS.html),
[CREATE TRIGGER](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-TRIGGER.html).
