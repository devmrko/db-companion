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

## Boundaries and next stage

This is a local direct-logon check, not IAM token propagation or switching
authenticated end users on a shared application connection pool. Those paths
must supply verified identity/context, clear it on connection reuse and verify
the effective identity before protected operations. Creating users and mappings
alone does not implement that runtime path.

Memory extraction, search and recall must separately enforce the same end-user
policy. The current administrative Memory browser is not a personal-memory
application. A shared DB account or a separate store name is not an end-user
authorization boundary.

References: [Local authentication setup](https://docs.oracle.com/en/database/oracle/oracle-database/26/ddscg/configure-database-local-end-user-authentication.html),
[Create end user](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-end-user.html),
[Grant data role](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/grant-data-role.html),
[End-user context views](https://docs.oracle.com/en/database/oracle/oracle-database/26/ddscg/end-user-security-context-views.html).
