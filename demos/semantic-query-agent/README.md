# Semantic query Agent with saved results

This optional example connects an Oracle Select AI Agent to the app's callable
glossary query package. It saves fetched SQL results before the Agent finishes its
answer, so an integrated APEX client can recover them if the model response fails.
It is not a complete APEX application export or an automatic installer.

## Deployment inputs and prerequisites

Use your intended application schema, its own configured query profile and an Agent
profile authorized for the selected provider. No account, provider, model, Wallet,
business objects or credentials are supplied. The schema needs the callable
`DBC_AI_QUERY` package, prepared glossary storage, appropriate data permissions,
supported Oracle AI Agent packages and APEX for recovery. AI calls incur provider
usage and can transmit the authorized question and context.

`definitions({owner, agentProfile})` in `definition.mjs` constructs Tool, Agent, Task
and Team attributes. It accepts validated unquoted uppercase identifiers. The
`{{QUERY_PROFILE}}` placeholder in `query-tool.sql` is a separate deployment input:
validate it as an identifier before substitution. Never interpolate arbitrary user
text into the SQL source. The `DBC_` object names identify this example, not a DB user.

## Installation and APEX integration

Review existing objects and back up anything you intend to replace. Installation
requires explicit authorization; DDL commits and partial installations can remain.

1. Install `result-store.sql` once in the intended schema, then install
   `query-tool.sql` with the validated query profile. These scripts use `CREATE`,
   not automatic destructive replacement. They do not install grants or a scheduler.
2. Register the four definitions from `definition.mjs` through your supported Agent
   management workflow. Review existing names first. `usage.sql` contains invocation
   examples; direct calls are alternatives, not additional steps after a Team run.
3. In your authenticated APEX request handler, validate ownership of the conversation
   server-side. Call `DBC_AGENT_RESULT_STORE.begin_request` with the request ID and
   trusted conversation before `RUN_TEAM`, in the same DB session. Always call
   `end_request` on success and exception paths. Do not trust a submitted conversation
   ID as proof of ownership or expose these integration calls anonymously.
4. Add an authenticated, authorized AJAX callback named `DBC_GET_SAVED_QUERY_RESULT`
   using `apex-result-callback.sql`. Apply your application's session and access rules.
5. Load `apex-result-recovery.js` after the existing chat script. Its adapter expects
   page items `P1_CONV_ID` and `P1_AGENT_PROFILE`, process `EXECUTE_PROMPT`, and request
   ID parameter `x10`; adapt these together with the server handler if your app differs.
   The example panel uses Korean labels and prefers the `#chat-container` element.

The JavaScript wraps the existing APEX process call. It does not supply the server
request-handler wiring or replace conversation authorization. Validate both parts
in a non-production application before enabling the example.

## Results and limits

SQL is generated once and that same statement is executed. The tool requires glossary
grounding; ontology is off by default and needs explicitly selected approved sources.
It does not retry a failed or uncertain AI request. One Team invocation can still
involve multiple paid model calls for orchestration and SQL generation.

The stored result contains at most 1,000 fetched rows subject to payload-size limits.
The model receives at most five preview rows, also subject to character budgets;
five rows is not a full-result guarantee. `more`, preview truncation and recovery
availability are separate signals. Never calculate whole-dataset totals from a
partial preview. SQL success is not proof that the business rules are correct.

Numbers are encoded as decimal strings and null stays JSON null. The recovery panel
renders literal text and paginates saved rows by 25. Its refresh reads saved data,
not another SQL execution or Agent call. A standalone call without trusted APEX
context returns a preview with recovery unavailable.

Recovery reads are scoped to DB session user, APEX application/session/user and
conversation. Access expires after seven days; expired rows are physically deleted
on the next `begin_request`, not by a background job. A shared DB account does not
isolate business-data access between end users: use trusted identity and appropriate
database/application authorization. The owning schema can access its storage directly.

## Validation

Run `node --test demos/semantic-query-agent/*.test.mjs` for local contract and mocked
client tests. These do not install PL/SQL or verify a deployment's business totals.
Before use, test result recovery after model failure, cross-session/conversation
denial, expired records, zero rows, partial results and tool errors in your own app.
