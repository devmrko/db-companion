# First use of DB Companion

[한국어](GETTING-STARTED.ko.md) · [简体中文](GETTING-STARTED.zh-CN.md) · [日本語](GETTING-STARTED.ja.md) · [Screen guide](SCREEN-GUIDE.md)

Some history and collection features need privileged setup before ordinary users can see records. Identify the target owner and collector installer; `ADMIN` is not universally required.

## 1. Confirm your connection

Choose the correct Wallet and TNS service, then sign in with your Oracle account. On **Connection**, check the database, authenticated user and selected schema. The schema selector changes browsing context, not login or privileges. Give the installer the same database/Wallet and target schema/object, never your password.

## 2. Agree on the order

| Who | First action |
| --- | --- |
| Ordinary user | Confirm connection, browse accessible definitions and use a prepared Select AI profile. Request missing history setup. |
| Recorded trigger owner or privileged installer | Inspect and install/update history code per table/view, enable collection, then grant object-specific history access. Existing installations use the recorded trigger owner. |
| App store or collector owner | Prepare that feature's storage, direct grants and job as needed. Profile owners manage profiles; SQL-cache and DDS archives have their own owners. `ADMIN` does not create every store. |

Installation and grants may commit separately. Review the preview and permissions before applying.

## 3. Set up metadata change history, if needed

This history covers table/view/column comments and supported annotations, not business rows:

1. Sign in to the **same database** as the recorded trigger owner, or a privileged installer for a new target. Select its schema under **Tables, views & columns**. Selecting a schema does not authorize setup.
2. Open the table/view, or use a profile's `object_list` in **Bulk history & access** to preview several. Profiles group targets, not history stores. Check each object; handle other schemas separately.
3. Open **Check installation**. Check the shared package, object triggers, validity and privileges. Update known old code when indicated; missing triggers need **Install & enable trigger**. Review unknown code manually. **Ready** code alone does not mean collection is on.
4. Turn on **Track changes**. In **Bulk history & access**, give named, existing users **Allow history read** or **Allow read and collection ON/OFF** for selected objects. ON/OFF cannot install or replace code. Review scope before applying; avoid default **All users (PUBLIC)** access.
5. Have the user reconnect or refresh and check **Change history**. Collection records actual DB changers; access controls viewing and ON/OFF, not whose changes are captured. Settings are shared across profiles.

For ownership, partial DDL and delegation, see [history access](metadata-history-access.md) and [code updates](metadata-history-upgrades.md).

## 4. Know which history you are viewing

| Source | What to expect |
| --- | --- |
| Metadata change history | Database triggers record supported comments/annotations when installed and enabled. A missing collector differs from an enabled collector with no later changes. |
| App-saved histories | Explicitly saved execution/problem-question material and supported configuration edits have their own app storage and scope. Session-only state is not a persistent database record; external edits may not appear retroactively. |
| SQL-cache archive | The login owner explicitly registers a table and Oracle Scheduler job for selected Select AI call candidates. Polling `V$SQL` can miss evicted SQL; observed cursor statistics are not a complete execution audit. No automatic purge is promised. See [SQL-cache archive](sql-cache-archive.md). |
| Oracle audit / DDS archive | Source audit records and optional DDS archived records have separate coverage and setup. Archiving does not enable source auditing or recover expired records. Database Scheduler jobs continue after app logout. |

Retention depends on each source and its configured policy; do not infer a universal number of days from an empty screen.

## 5. Make a first useful query

Confirm the connection, select a prepared profile and inspect relevant business definitions. In **Select AI test**, generate SQL; review eligible objects, context and SQL before explicitly querying. Compare results with a trusted business answer: AI review does not validate numbers. AI requests may send questions, SQL and selected context to a provider and incur charges; check payload and cost first. Business-data changes are not an onboarding step. See the [screen guide](SCREEN-GUIDE.md), **SQL ?** and separate [callable AI](callable-ai.md) workflow.

## If something looks wrong

| Symptom | Check |
| --- | --- |
| Disabled control / administrator setup required | Open **Setup instructions** and **Check installation**; ask the recorded installer to check privileges and target state. |
| **MISSING** | Confirm the selected object and inspect package/triggers; installation is a separate, privileged action. |
| **READY** but no history | Check **Track changes** is on, access for your login, and whether a supported change occurred after collection began. |
| `ORA-00942` / access check | The object or view may be absent, unsupported or inaccessible; ask an administrator to check direct grants and the original ORA code. |
| Unexpected records or controls | Recheck Wallet, TNS service, database, authenticated user and target schema. A schema selector does not change the login. |
