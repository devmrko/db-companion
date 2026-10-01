# VPD table policy management

The authenticated database owner may register, replace, delete, enable and disable VPD policies on its own tables and views. Other accessible schemas remain read-only. Existing valid standalone/package functions with exactly two IN VARCHAR2 arguments and a VARCHAR2 return are selectable; the application never creates or invokes policy function source directly.

Reads use SYS.ALL_OBJECTS, ALL_TAB_PRIVS, SESSION_ROLES, ALL_TAB_COLUMNS, ALL_POLICIES, ALL_SEC_RELEVANT_COLS and ALL_POLICY_ATTRIBUTES with bound owner/object filters. Table and view identity, including object type, participates in the snapshot fingerprint. Missing dictionary access is distinct from an empty result. Metadata is bounded and incomplete results cannot authorize changes. DBMS_RLS EXECUTE grants are checked against the login, PUBLIC and enabled roles; Oracle remains the final privilege authority.

The object selector defaults to every table/view visible in the selected schema. Filters combine type, name, ownership and object grants. Direct grants, enabled-role grants and PUBLIC grants are included; grants made by the login to somebody else are not treated as received grants. SELECT/READ and DML filters include owned objects. ANY system privileges and column-level grants are explicitly not folded into these filters. This is a metadata filter, not proof of effective row access, view updatability or VPD-management permission. Refresh reloads objects and grants as well as the selected policy catalog; an empty filtered list clears prior policy details and change authorization.

- [Oracle DBMS_RLS reference](https://docs.oracle.com/en/database/oracle/oracle-database/26/arpls/DBMS_RLS.html): policies support views as well as tables.
- [Oracle ALL_TAB_PRIVS reference](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_TAB_PRIVS.html): visible object grants and grantees.

Supported changes are deliberately restricted to unquoted uppercase identifiers and local, non-inherited SYS_DEFAULT policies. All policy fields and auxiliary metadata are read and fingerprinted. Unknown fields, unsupported policy types, grouped policies, and namespace/attribute associations prevent changing that policy. Existing relevant columns and ALL_ROWS settings are preserved when editing; callers submit the complete desired supported definition. Existing unsupported policies are always displayed.

Every write requires a server-generated, single-use, five-minute SQL preview, target text confirmation and security-impact consent. The server retains the complete operation and recovery SQL in memory, rechecks owner, grants, table identity, all policy metadata and function identity before executing, and never accepts client SQL. Changing form values invalidates the browser preview. CSRF protection uses the application's existing security filter.

Policy function names in both the list and selected-policy panel open a read-only source dialog. The target is derived from each policy's PF_OWNER/PACKAGE/FUNCTION metadata, including policies the edit form cannot preserve. It reuses `/db/functions/detail`, the existing source-read permissions, source size limit and session cache; it does not execute the function or invoke AI. Identifiers are quoted separately and returned definitions must exactly match the expected owner/object/member/type. Package functions show the full package specification/body with search and declaration navigation. Closing the dialog cancels in-flight requests and prevents late results replacing another function's source. Missing or inaccessible source is reported in the dialog without changing policy-edit restrictions.

Oracle DBMS_RLS.ALTER_POLICY changes context associations, not arbitrary function/statement/type properties. Replacement therefore explicitly performs DROP_POLICY then ADD_POLICY. These calls commit and are not atomic: concurrent sessions may observe a security gap and failure may leave the policy absent. Replacement requires separate gap consent. Preview includes the original ADD_POLICY recovery statement; no automatic restoration or retry occurs. Any exception after an invocation starts consumes the token, reports CHECK_REQUIRED, and blocks further writes in that login session. Read-only refresh remains available. Successful calls are followed by metadata verification; inability to verify is not reported as success.

Fingerprint checks reduce stale actions; Oracle does not provide a compare-and-swap policy API, so an external administrator can still race the final check. Schedule replacement in an approved maintenance window with application traffic stopped. Recovery must be reviewed and performed by a database administrator after checking the actual current state.

References checked 2026-09-24:

- https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_RLS.html
- https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_POLICIES.html
- https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_SEC_RELEVANT_COLS.html
- https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_POLICY_ATTRIBUTES.html

Verification uses synthetic Java/Javascript tests and Thymeleaf rendering. Production policy writes are outside the authorized verification scope.

## Verification completed

- Java: 23 passing tests across VpdManagementTest, VpdManagementTemplateTest, DeepDataSecurityTest and FunctionTemplateTest. VPD tests cover original recovery settings, unsupported fields/groups/context, input injection, owner restrictions, revoked privileges, stale metadata/functions, expiring and consumed confirmations, failed-preview invalidation, partial DROP/ADD failure, blocked retry and post-call verification failure. Four locales render and the real CSRF filter rejects a tokenless mutation.
- JavaScript: 19 passing tests across vpd-management.test.mjs and sql-help.test.mjs, including consent, stale previews, expiry and operation-specific SQL help.
- Maven package and the quality-profile PMD check pass. Publication scan: 640 files, zero findings.
- Isolated localhost browser verification: actual authenticated page, policy list and function catalog return HTTP 200; absent DBMS_RLS EXECUTE permission produces a read-only warning and disabled registration control. Active navigation and policy/replacement SQL help work, with no horizontal overflow or script errors. VPD mutation requests: zero. No customer rows, passwords, cookies or screenshots were saved.
- Actual Oracle ADD/DROP/ENABLE calls, user-specific row access and policy function semantics remain untested. The connected account lacks the required visible EXECUTE grant. Catalog fields beyond the supported shape (including EDITION_NAME on newer databases) remain visible but conservatively prevent modifying existing policies. This is a deliberate unsupported-state guard, not a statement that the policy is invalid.
