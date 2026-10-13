# Callable Select AI pipeline

`Functions → Callable AI function` creates and tests `DBC_AI_QUERY`. The glossary and Select AI test screens link to this page. Nothing is installed at application startup. Existing Select AI test requests are unchanged.

The generated PL/SQL is standalone: Oracle Text matching, definition retrieval, prompt composition, `DBMS_CLOUD_AI.GENERATE(action=>'showsql')`, and optional query execution happen inside Oracle. It does not call DB Companion or an application endpoint. Business rules stay in the dictionary, ontology, comments and profile Feedback, not in Java or the package source.

## Search-only module

**Functions → Create glossary search function** previews and creates one
`DBC_GLOSSARY_SEARCH(p_question CLOB) RETURN CLOB AUTHID CURRENT_USER` function.
It returns `formatVersion`, `question`, `schema`, `glossary` (tokens, matching
targets, definitions and SQL criteria), and `aiCalled:false`. No profile is
required; it does not generate or execute business SQL.

```sql
SELECT DBC_GLOSSARY_SEARCH(:question) AS EVIDENCE_JSON FROM DUAL;
```

The function is a short entry point calling `DBC_GLOSSARY_API.SEARCH`. The package
body separates validation, tokenization, candidate retrieval, question matching,
overlap removal, definition retrieval and response construction into named
functions. SEARCH shows only their calling order. There is no generated anonymous
PL/SQL block hidden inside a CLOB string. Only short owner-local SELECT statements
are dynamic; values remain bound. The overlap query is ordinary static SQL.

The response contract and phrase-matching behavior remain compatible with v1.
The existing AI package retains its original implementation and is not implicitly
upgraded to depend on this package. Prompt/SQL/execution remain in that pipeline.

The wizard checks the owner-local dictionary, Oracle Text setup, session
`CREATE PROCEDURE` privilege, object-name availability and stored source.
Installation requires a five-minute, single-use preview and explicit DDL consent.
The wizard separately shows the actual SEARCH flow, entry function, package
specification, package body and planned DDL. Stored-source fingerprints invalidate
changed previews. An exact, app-generated v1 function can be explicitly replaced
with the v2 entry function; unrelated objects are blocked. Exact installed package
parts are kept, and missing parts can be completed after fresh confirmation.
DDL failure may leave partial objects; no automatic cleanup or retry occurs.
Compilation errors remain visible. Search tests require all three valid,
source-matching components. Back up source before a deliberate v1 migration;
fingerprint checks are not a database-wide DDL lock.
The limits and Oracle invocation-chain rules below still apply.

SQL/PLSQL blocks use the common read-only source UI: line numbers, syntax
highlighting, source search, optional wrapping and copying the original code.
These display controls never execute code or modify its text.

## Options and modes

| Argument | Meaning |
| --- | --- |
| `p_question` | Original question, at most 8,000 characters |
| `p_profile` | Enabled Select AI profile of the invoking DB user |
| `p_use_glossary` | `1`: native Oracle Text phrase matching; `0`: no dictionary lookup |
| `p_use_ontology` | `1`: attach selected approved ontology definitions; `0`: no ontology lookup |
| `p_tables` | JSON array of 1–10 exact table/view names when ontology is enabled |
| `p_mode` | `CONTEXT` (default), `SQL`, or `QUERY` |
| `p_max_rows` | Query result limit, 1–1,000 (default 200) |

`CONTEXT` returns tokens, matched terms and revisions, selected ontology definitions/relations, the composed prompt, and phase timings. No AI call or business query occurs. `SQL` adds exactly one AI generation. `QUERY` executes that same generated SQL once; it does not call `runsql` and regenerate SQL. No automatic retry occurs. SQL/QUERY test buttons require confirmation of the scope and potential AI cost.

The response is a JSON CLOB. Query output is an ordered column list plus arrays of cells, so duplicate column labels do not overwrite values. NUMBER cells are decimal strings to preserve Oracle precision through JavaScript/MCP clients. DATE/TIMESTAMP cells use explicit ISO-style formatting; database NULL is JSON null. Results support VARCHAR2/CHAR, NUMBER, DATE and TIMESTAMP; other types fail explicitly. There are at most 40 columns and 1,000,000 serialized result characters. `more=true` means additional rows exist; it does not indicate incomplete source data or make a missing value zero.

## Evidence and dependencies

Glossary mode requires the existing owner-local `DBC_BUSINESS_TERM`, `DBC_BT_CTX` and `DBC_BT_KO_POLICY`. The package generator embeds the very same `db/glossary/phrase-search.sql` used by the app. Full phrase matches and longest overlapping spans are retained; generic token OR results are not directly attached as definitions. At most 30 definitions and 64 unique tokens are permitted. Korean lexer configuration is reused, not auto-installed or silently replaced by the UI language.

Ontology mode reads `DBC_ONTOLOGY_CATALOG`, the source of saved ontology/RDF definitions. It does **not** run SPARQL or reproduce the ontology inquiry screen's automatic three-hop path finder. Select the tables explicitly. Each table must be inside the caller's profile object list. The latest revision must be APPROVED; an older approved revision is not substituted for a newer draft. Definitions are projected without author/profile/sampling diagnostics. Sensitive/private-named columns and associated mappings/relationships are excluded.

Only relationships between selected tables are included: approved links whose source/target revisions and target document ID still match, and saved enabled/validated foreign keys. These are saved metadata, not a fresh verification of database constraints or row cardinality. Stale/out-of-scope links are omitted. For intentionally revised but semantically unchanged documents, refresh/review the links before using this initial strict revision check. No inferred relationship is asserted. The selected ontology definitions can be inspected in the context response before AI use.

Disabled sources have no runtime lookup. Both can be off, glossary-only, ontology-only, or both on. Missing/invalid enabled dependencies fail instead of being treated as an empty search. Per-source evidence is limited to 40,000 characters and the entire prompt to 64,000; excess data is not silently truncated. Each call resolves current evidence anew; the preview is diagnostic, not a pinned snapshot for later calls. Inspect the actual response's `prompt`, definition revisions and evidence to compare runs.

## Installation and identity

Review the generated SQL in the app. The package spec uses `AUTHID CURRENT_USER`. No table, profile, credential, ontology, dictionary entry, scheduler job or data grant is created. A missing package can be created; a recognized spec without a body can be completed. A different existing package is never overwritten. Inspect USER_ERRORS for compilation errors and use database tools for deliberate source upgrades. DDL may partially apply and is not rolled back automatically.

In ADMIN sessions the page offers a local-user selector, a direct privilege check, and a preview/confirmation button for exactly:

```sql
GRANT CREATE PROCEDURE TO "<TARGET_USER>";
```

This privilege permits creation of PL/SQL objects in that user's schema, not only this package. It is not `CREATE ANY PROCEDURE` and adds no data access. Other required direct grants (such as Oracle Text/Select AI API access) remain the administrator's responsibility and are not granted automatically. No PUBLIC grants are made.

Invoker-rights calls use the invoking DB principal's profiles and owner-local evidence stores. The invocation chain and Oracle INHERIT PRIVILEGES/EXECUTE rules still apply when calling across users or from another definer-rights routine. A shared MCP credential does **not** identify each end user for VPD/DDS. Configure trusted application context/identity mapping separately; never solve it by using a broad ADMIN definer-rights wrapper.

QUERY uses Oracle's native `OPEN ... FOR` SELECT cursor rather than a Java SQL grammar filter or unchecked `DBMS_SQL.PARSE`. This is not a security sandbox: SELECT expressions, views, and functions may have side effects. Use a suitably restricted DB account and VPD/DDS; enforce SQL scope in Oracle/profile settings as appropriate. Row limits do not cap query execution work. App calls use the configured Select AI generation/query time budgets; direct external calls need their own timeout/cancellation policy.

## External calls

Create a parameterized custom SQL tool in **OCI Database Tools MCP**. Prefer fixed profile/options/table scope with just a question parameter. Bind parameters; do not interpolate end-user text into SQL.

```sql
-- Evidence only: no AI invocation.
SELECT DBC_AI_QUERY.ASK(
  p_question => :question, p_profile => :profile,
  p_use_glossary => 1, p_use_ontology => 0,
  p_mode => 'CONTEXT') AS RESPONSE
FROM DUAL;

-- Ontology tables are an explicit JSON array, for example ["ORDERS","CUSTOMERS"].
SELECT DBC_AI_QUERY.ASK(
  p_question => :question, p_profile => :profile,
  p_use_glossary => 1, p_use_ontology => 1,
  p_tables => :table_names_json, p_mode => 'SQL') AS RESPONSE
FROM DUAL;
```

For Select AI Agent, register a custom PL/SQL tool targeting the package (or a small invoker-rights adapter fixing profile/options). Its instruction describes when to invoke it; the deterministic retrieval order stays inside the package, not in an agent instruction. An agent may call a tool repeatedly, so do not equate one conversation with one paid call. Neither Agent nor MCP registration is performed by this screen. Their actual registration, authorization and paid end-to-end behavior need separate environment validation.

Oracle references: [Database Tools MCP toolsets](https://docs.oracle.com/en-us/iaas/database-tools/doc/database-tools-mcp-toolsets.html), [Select AI Agent package](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-ai-agent-package.html), [native OPEN FOR](https://docs.oracle.com/en/database/oracle/oracle-database/26/lnpls/OPEN-FOR-statement.html).
