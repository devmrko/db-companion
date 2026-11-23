# First use of DB Companion

[한국어](GETTING-STARTED.ko.md) · [简体中文](GETTING-STARTED.zh-CN.md) · [日本語](GETTING-STARTED.ja.md) · [Screen guide](SCREEN-GUIDE.md)

Follow **sign in → check readiness → browse metadata → generate, review and run SQL**.
First obtain the [prerequisites and administrator request](../README.md#before-you-start).
History installation is not required for your first query.

## 1. Confirm your connection

Choose the correct Wallet and TNS service, then sign in with your Oracle account. On **Connection**, check the database, authenticated user and selected schema. The schema selector changes browsing context, not login or privileges. Give the installer the same database/Wallet and target schema/object, never your password.

## 2. Check readiness

The dashboard shows **Complete / Missing / Unknown** per item. Open **Readiness details** and select the intended profile; the summary prefers the first enabled profile. Complete means configuration checked, not successful execution. Follow setup links for missing items; check access, connectivity and feature support for unknown items. See the [readiness guide](setup-readiness.md) for the inspection scope.

## 3. Browse metadata

Open **Tables, views & columns**, choose an accessible schema and object, and inspect its columns and constraints. Seeing the expected object and columns confirms metadata browsing. For an empty list or access error, check the schema and permissions. Editing comments or installing history is unnecessary. Accessible metadata remains usable even when AI setup is incomplete.

## 4. Generate, review and run SQL

In **Select AI test**, select the profile checked in Readiness and enter a question. Review the external payload and possible cost before generating SQL. Inspect eligible objects, context and SQL before explicitly running an approved read query. Compare results with a trusted business answer: AI review or successful execution alone does not validate it. Business-data changes are not an onboarding step. See the [screen guide](SCREEN-GUIDE.md) and **SQL ?**.

## Optional features

For fictional practice data, open **Scenario data generation**, select industry, size and
dates, and download files for local Claude Code/Codex. The app does not write to the DB.
Follow [account preparation and local execution](scenario-data.en.md) before running them.

Request history and collection setup separately when needed. `ADMIN` is not universally required.

### Roles and order

| Who | First action |
| --- | --- |
| Ordinary user | Confirm connection, browse accessible definitions and use a prepared Select AI profile. Request missing history setup. |
| Recorded trigger owner or privileged installer | Inspect and install/update history code per table/view, enable collection, then grant object-specific history access. Existing installations use the recorded trigger owner. |
| App store or collector owner | Prepare that feature's storage, direct grants and job as needed. Profile owners manage profiles; SQL-cache and DDS archives have their own owners. `ADMIN` does not create every store. |

Installation and grants may commit separately. Review the preview and permissions before applying.

### Set up metadata change history

This history covers table/view/column comments and supported annotations, not business rows:

1. Sign in to the **same database** as the recorded trigger owner, or a privileged installer for a new target. Select its schema under **Tables, views & columns**. Selecting a schema does not authorize setup.
2. Open the table/view, or use a profile's `object_list` in **Bulk history & access** to preview several. Profiles group targets, not history stores. Check each object; handle other schemas separately.
3. Open **Check installation**. Check the shared package, object triggers, validity and privileges. Update known old code when indicated; missing triggers need **Install & enable trigger**. Review unknown code manually. **Ready** code alone does not mean collection is on.
4. Turn on **Track changes**. In **Bulk history & access**, give named, existing users **Allow history read** or **Allow read and collection ON/OFF** for selected objects. ON/OFF cannot install or replace code. Review scope before applying; avoid default **All users (PUBLIC)** access.
5. Have the user reconnect or refresh and check **Change history**. Collection records actual DB changers; access controls viewing and ON/OFF, not whose changes are captured. Settings are shared across profiles.

For ownership, partial DDL and delegation, see [history access](metadata-history-access.md) and [code updates](metadata-history-upgrades.md).

### Know which history you are viewing

| Source | What to expect |
| --- | --- |
| Metadata change history | Database triggers record supported comments/annotations when installed and enabled. A missing collector differs from an enabled collector with no later changes. |
| App-saved histories | Explicitly saved execution/problem-question material and supported configuration edits have their own app storage and scope. Session-only state is not a persistent database record; external edits may not appear retroactively. |
| SQL-cache archive | The login owner explicitly registers a table and Oracle Scheduler job for selected Select AI call candidates. Polling `V$SQL` can miss evicted SQL; observed cursor statistics are not a complete execution audit. No automatic purge is promised. See [SQL-cache archive](sql-cache-archive.md). |
| Oracle audit / DDS archive | Source audit records and optional DDS archived records have separate coverage and setup. Archiving does not enable source auditing or recover expired records. Database Scheduler jobs continue after app logout. |

Retention depends on each source and its configured policy; do not infer a universal number of days from an empty screen.

For separate package execution, see [callable AI](callable-ai.md).

## If something looks wrong

| Symptom | Check |
| --- | --- |
| Disabled control / administrator setup required | Open **Setup instructions** and **Check installation**; ask the recorded installer to check privileges and target state. |
| **MISSING** | Confirm the selected object and inspect package/triggers; installation is a separate, privileged action. |
| **READY** but no history | Check **Track changes** is on, access for your login, and whether a supported change occurred after collection began. |
| `ORA-00942` / access check | The object or view may be absent, unsupported or inaccessible; ask an administrator to check direct grants and the original ORA code. |
| Unexpected records or controls | Recheck Wallet, TNS service, database, authenticated user and target schema. A schema selector does not change the login. |
