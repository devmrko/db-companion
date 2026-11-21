# DDS end users and access checks

Use **Deep Data Security → End users and roles** to create local DDS users,
assign or revoke local data roles, and check access using their own credentials.
This lets an administrator compare actual access with the intended policy before
connecting an application or an AI workflow.

## Supported paths

| Path | Configuration | Verification |
| --- | --- | --- |
| Local end user | Create with password authentication or without direct authentication; grant/revoke a local data role | Separate password-authenticated connection, actual end-user identity, active roles when readable, visible table row count |
| OCI IAM group | Use the existing DDS editor to map a data role to `IAM_OAUTH_GROUP` or `IAM_GROUP_NAME` | Stored mapping readback; this does not validate IAM membership or an actual token |

IAM user creation, group membership and application registration remain in IAM.
An IAM user receives a mapped data role through the token's group/role claims;
the local role-assignment form cannot grant an externally mapped role directly.
Users without password authentication need an independently configured trusted
application path and cannot use this page's direct-logon check.

## Create and assign

1. Log in as `ADMIN`, keeping its own schema selected, and load management state.
2. Open **Create user or change roles**, choose local-user creation and preview.
3. Review the SQL and target. For password authentication, enter the new password
   only at application time, enter the exact confirmation and give consent.
4. Select the created user, choose a local data role, preview and confirm its grant.
   Revoke uses the same process. Refresh the list after an uncertain result;
   do not repeat a failed or uncertain application automatically.

Required database privileges include `CREATE END USER`, `GRANT ANY DATA ROLE`
and access to the relevant DDS dictionary views. The feature retains the existing
ADMIN-only management boundary; it does not grant these privileges automatically.
User creation does not create a schema or grant table access.

## Prepare a direct-logon test

Local direct logon needs a standard database role carrying `CREATE SESSION`,
granted to a local data role assigned to the end user. An administrator must
prepare this explicitly, for example with **new synthetic test names**:

```sql
CREATE ROLE DEMO_CONNECT;
GRANT CREATE SESSION TO DEMO_CONNECT;
CREATE DATA ROLE DEMO_LOGIN;
GRANT DEMO_CONNECT TO DEMO_LOGIN;
GRANT DATA ROLE DEMO_LOGIN TO DEMO_PERSON;
```

Configure separate data roles and SELECT data grants for the test table using
the existing DDS editor. Do not give the runtime users broad direct table grants
that bypass the policy you intend to test.

Select user A and open **Check the selected user's actual access**. Enter that
user's password, select an owned table and consent to the read. Repeat for B,
then compare with known synthetic rows and the intended policies. The endpoint
opens and closes a separate connection through the existing wallet/pool factory.
It starts a read-only transaction, checks `ORA_END_USER_CONTEXT.username`, and
stops before the table query if the identity differs. It never retries using
ADMIN. Passwords are not included in previews, pending plans or response bodies.

Role metadata can be unavailable independently of the table query. The UI labels
that state as unverified. A rejected query is not automatically classified as
successful policy enforcement, and zero rows or a successful count alone does
not prove isolation. Counts do not establish which individual rows were visible;
use independent expected-result checks when validating a policy.

## IAM and shared-connection checks

In **DDS create/change**, choose **OCI Application Identity** and enter the
OAuth client ID of an existing IAM application. Preview and confirm the SQL.
Creation and replacement use the same expiring plan, concurrency check and
database readback as other DDS changes. IAM application registration and group
membership remain external operations.

Open **IAM, shared connection and Memory A/B checks** to supply a dedicated
password-authenticated pool account, its password and a database-access OAuth
token. The pool account requires `CREATE SESSION` and
`CREATE END USER SECURITY CONTEXT`. ADMIN, SYS and SYSTEM are rejected. The
database must trust the token issuer and audience; creating the application
mapping alone does not configure that trust.

For OCI IAM, the database OAuth configuration uses the database resource's
**Application ID**, while `CREATE APPLICATION IDENTITY` uses the calling
application's **OAuth Client ID**. These are different identifiers. Compare
`IDENTITY_PROVIDER_OAUTH_CONFIG.app_id` with the database token's
`resource_app_id`, and `domain_url` with `tenant_iss` exactly, including an
explicit `:443` if present. Decode tokens locally only; do not send them to an
online decoder. Decoded claims are diagnostic inputs, not signature verification.

Choose local user names or two IAM end-user tokens, and enter the exact expected
database identity for A and B. The application borrows one connection from a
temporary pool and runs A, B, then A again. Before reading an owned table it
checks the effective identity. Each step rolls back, clears the context and
checks that the original identity is restored. Identity mismatch, query failure
or cleanup failure stops the experiment; no automatic retry or ADMIN fallback
is performed. This checks sequential reuse of one borrowed connection, not
concurrent requests or release/reborrow behavior of every connection-pool type.

For **Memory test-record access**, supply an existing native `plsql-001` store
and two synthetic record IDs with independently known ownership. The result
reports visible counts for each ID without returning contents. Compare A/B/A
with the expected access matrix; a row count alone does not establish ownership
or prove the policy is correct. These reads do not call a model.

Tokens and the pool password are used only for that request, cleared from the
form on submission, and excluded from response bodies and pending plans. Do not
paste them into chat, save them in browser storage or include them in reports.

## Boundaries and next stage

The local direct-logon check and the administrator-run token experiment are
separate paths. They do not add IAM login to the management application or
change its ordinary request connections into a multi-user runtime.

Memory extraction, search and recall must separately enforce the same end-user
policy. The record-access experiment does not validate those native API paths.
The current administrative Memory browser is not a personal-memory
application. A shared DB account or a separate store name is not an end-user
authorization boundary.

References: [Local authentication setup](https://docs.oracle.com/en/database/oracle/oracle-database/26/ddscg/configure-database-local-end-user-authentication.html),
[Create end user](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-end-user.html),
[Grant data role](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/grant-data-role.html),
[End-user context views](https://docs.oracle.com/en/database/oracle/oracle-database/26/ddscg/end-user-security-context-views.html).
