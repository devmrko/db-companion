# ORDS management

The `/db/ords` menu manages the authenticated Oracle user's ORDS registration, modules, URI templates and HTTP handlers. It uses `ORDS_METADATA.ORDS` and the five `USER_ORDS_*` metadata views for schemas, modules, templates, handlers and parameters. Selecting another schema blocks all operations, including for administrators.

New modules default to `NOT_PUBLISHED`; new schema registration defaults to REST disabled and metadata catalog authentication enabled. Enabling schema REST access or publishing a module can expose existing routes. Metadata catalog authentication is **not** handler authorization. This feature neither creates nor modifies privileges, roles, OAuth clients or credentials, and makes no HTTP calls to published handlers.

Before a write, the server creates a five-minute, session-bound, single-use preview containing original metadata, desired values, affected descendants and the exact bound PL/SQL calls. The user must type the complete target/action confirmation. Input changes invalidate the browser preview. Applying consumes the server token even when an error occurs. There is no automatic retry.

The apply transaction uses serializable isolation, re-reads the metadata revision, rebuilds the operation from the stored input and checks installed package procedures. It reads the result before commit and checks the selected values, unchanged fields, siblings and parameters. A mismatch requests rollback. Network or commit failures can still leave an uncertain outcome; inspect the actual state before another attempt. No write-path guarantee is claimed without testing the particular installed ORDS implementation.

## Supported edits

- Schema: register, enable/disable, BASE_PATH/BASE_URL mapping, metadata catalog authentication, and remove all ORDS metadata using `DROP_REST_FOR_SCHEMA`. This is **not** `DROP USER`; it does not delete database tables. Deletion may also affect auto-REST, security and OAuth relationships not inventoried by these five views. A complete ORDS export should be reviewed before deletion.
- Module: create, delete, publish/unpublish and edit. A populated module or a module with PRE_HOOK/ORIGINS_ALLOWED uses `RENAME_MODULE` and `PUBLISH_MODULE`, preserving its children and policies. Pagination/comments changes are blocked in that case. Empty modules without those policies can be redefined.
- Template: create, delete, and edit when it has no handlers. A populated template is not redefined because Oracle documents destructive replacement of handlers. Delete explicitly includes the descendant handlers and parameters.
- Handler: GET SQL representations and POST/PUT/DELETE PL/SQL blocks; create, edit, delete. Existing parameters are copied through `DEFINE_PARAMETER` in the same transaction and verified. Source and ETag query text are bound data, never executed or explained by this application. MLE handlers/environments are unsupported for editing. Supported method and response-type combinations are validated before preview.

Missing installed procedures block the affected operation. `DELETE_HANDLER`/`DELETE_TEMPLATE` are documented in ORDS 25.3 and later and may be absent in earlier installations. Unavailable metadata, insufficient privileges, no detected API and a valid empty catalog are different states. A view exceeding 2,000 rows or an aggregate two-megabyte metadata budget blocks editing instead of silently truncating the snapshot. Unknown columns are retained in snapshots and unchanged-field checks.

SQL help is copy-only and uses synthetic placeholders. The preview displays actual bound values only to the authenticated session; those values are not logged or stored on disk by this feature.

## Sources and verification

- [Oracle ORDS 25.3 package reference](https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/25.3/orddg/ORDS-reference.html): procedure signatures, replacement semantics, source types, schema authorization and deletion effects.
- [Oracle ORDS Java API introduction](https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/21.4/ordjv/doc-files/getting-started.html): `USER_ORDS_SCHEMAS` read-only inspection.

Development verification uses synthetic metadata/JDBC fixtures and rendered templates. Production ORDS writes and endpoint invocation are intentionally outside that verification. Read-only installation inspection can establish available APIs and catalog shape, but does not establish write correctness.
