# DB Companion — Screen Guide

DB Companion is a web-based workbench for Oracle Database and Select AI administration. It brings metadata editing and change history, business definitions, AI configuration, query testing, and operational inspection into a single interface.

This guide describes the purpose of each screen. Feature availability depends on the database version, installed packages, and the connected user's privileges. It is a feature overview, not an installation guide or a guarantee that every operation is available in every environment.

## Sign-in and navigation

Select a configured Oracle Wallet and a TNS service, then sign in with a database account. The connection screen identifies the database and user in use.

Where available, the schema selector changes the browsing context; it does not change the authenticated user or grant additional privileges. SQL help is available on many screens to explain the underlying database operations.

## Workspace

### Connection information

Check the current database, authenticated user, selected schema, TNS service, connection method, and session policy. Use this screen to confirm the target environment before inspecting or changing database objects.

## AI data management

### Business dictionary

Maintain business terms, aliases, definitions, status, and versions independently of ontology definitions. Search for terms relevant to a question using exact matches, registered aliases, and configured Oracle Text analysis.

Application-managed change history records dictionary edits. Dictionary lookup itself does not call an LLM; saving terms does not automatically make them validated business definitions.

### Ontology

Capture table and view metadata and organize it into versioned business definitions. Review AI-assisted drafts, manage approval status, and explore structures through ER diagrams and relationship views.

Related tools support relationship review, Oracle RDF integration, and property graph creation. Metadata graphs describe database objects and their relationships; business-row graphs represent rows and their connections. These are different uses of a graph, and generated relationships still require review.

### Ontology query

Find terms, tables, and relationship paths relevant to a question. Review the evidence and select a path before requesting an AI explanation or SQL generation.

Saved queries retain question context, evidence versions, and graph references. This is not a general-purpose natural-language-to-SPARQL engine or an archive of all returned business data.

### AI assistant settings

Choose the Select AI profile used for supporting tasks such as source-code explanations and AI reviews. This session-level selection is separate from the profile used to generate SQL on the Select AI test screen.

Selecting a profile does not modify its database definition or immediately invoke the model.

### Vector search

Discover tables and views with VECTOR columns, inspect their structure, and test similarity search. Configure the embedding provider, model, and search options as supported by the connected environment.

The query embedding must be compatible with the stored vectors. Inspecting metadata is separate from generating embeddings, which may involve an external provider and usage charges.

### Tables, views, and columns

Browse object definitions, columns, comments, annotations, constraints, and indexes. Edit supported comments and annotations, and inspect their change history.

History controls expose installation status, collection settings, and delegated history access. Supported operations depend on the object type and privileges—for example, ordinary view annotations are read-only. History collection requires the relevant database objects and permissions; it is not automatically enabled for every object.

### Select AI profiles

Inspect and manage Select AI profiles, including provider and model settings, attributes, and eligible database objects. Supported creation, editing, and cloning workflows help configure profiles without assembling each package call manually.

Use change history to review configuration changes. Profile ownership and database privileges determine which operations are available.

### Select AI test

Generate SQL or conversational responses from a question and inspect the prompt, profile settings, and supporting metadata. Optionally include business dictionary definitions and ontology evidence.

The screen separates SQL generation from confirmed execution and includes profile A/B comparison. AI review can examine the executed SQL and the definitions used at generation time; it does not establish numerical correctness. Result-cell values are excluded from the result-review payload.

### Problem questions

Record questions that produced questionable responses, together with the problem description, expected behavior, optional reference SQL, and review status. Compare saved attempts to understand the effect of changes.

Batch SQL generation supports testing selected questions with one profile. Registering a problem does not invoke AI or modify Feedback, and generated SQL is not automatically executed.

### Select AI Feedback

Search and inspect profile-specific Feedback. Add or edit supported negative Feedback entries to maintain corrective SQL examples and explanations used by Select AI.

Saving an example stores Feedback rather than executing that SQL. Editing is restricted for unsupported entry types, and Feedback changes can influence subsequent generation for the profile.

### Select AI Agent

Explore the connections between Teams, Agents, Tasks, and Tools. Open their details, inspect attributes, and use supported creation, cloning, editing, and change-history workflows.

Team and task detail screens make the configured dependencies visible. Configuration management is distinct from execution: running a tool-enabled agent can have effects such as sending notifications or calling external services.

### AI execution history

Inspect saved conversations, Agent executions, and Select AI SQL information through separate views. Filter records and open details to investigate previous activity.

Related screens expose SQL history sources and SQL-cache archival configuration. Cached SQL, database conversation records, and archived records have different coverage and retention; an empty result does not prove that no execution occurred.

## Database management

### Scheduler

Inspect Oracle Scheduler jobs, status, schedules, recent runs, and related source code. Use the screen to understand what is scheduled and investigate recorded execution results.

This is a read-only inspection screen, not a general-purpose job creation or execution console. Other application features may separately configure their own collection jobs.

### ORDS management

Manage supported REST definitions in the authenticated user's schema: schema registration, modules, URI templates, and HTTP handlers. Inspect handler SQL or PL/SQL and review proposed changes before applying them.

The API test view is separate from definition editing. Publishing, disabling, deleting, or calling an endpoint can affect availability or data. This screen does not cover every ORDS administration function, such as full OAuth and role administration.

### External data sources

Inspect database links, external tables, network ACLs, and mounted catalogs. Supported catalog operations allow an existing database link to be registered and its metadata explored.

Metadata visibility or an enabled status does not prove remote connectivity. Catalog registration is a database change; ordinary metadata inspection is not a query of remote business rows.

### Credential

Inspect accessible credential names, owners, status, and non-secret metadata. Use the screen to check the database-side credential configuration associated with integrations.

This is an inspection screen, not a password-retrieval interface or a general-purpose secrets vault.

### VPD policies

Browse Virtual Private Database policies attached to tables and views, including their associated policy functions. Supported changes are available for owned objects when the required privileges can be verified.

Policy changes can affect data visibility. Inspecting a policy definition is not the same as testing the effective access of every user.

### Deep Data Security

Inspect DDS roles, role grants, Data Grants, and application mappings. Follow the configuration to understand how data-access rules and external identities are associated.

The screen displays configuration rather than calculating every user's effective permissions. Use audit records to investigate recorded activity and security conditions.

### Audit records

Search Oracle audit records and archived DDS activity by time, user, object, and outcome. Open record details to inspect recorded security conditions, including RLS_INFO where available.

DDS archival controls support database-side collection through Oracle Scheduler, independently of the application's browser session. Archival does not automatically enable source auditing, recover already-expired records, or provide tamper-proof storage.

### Functions

Browse function metadata and source code, then optionally request an AI explanation using the selected assistant profile. Viewing a function or requesting an explanation does not execute the function.

#### Callable AI function

The related screen exposes setup and testing for `DBC_AI_QUERY.ASK`: retrieve question context, generate SQL, or query results through a database package that can also be called outside the web application.

Start with context-only mode when checking definitions without an AI request. Package execution uses its own database permission and execution boundaries; do not assume that the web test screen's SQL restrictions also apply to external callers.

## Typical workflows

### Investigate and improve a generated query

1. Check the profile's eligible objects and their comments or annotations.
2. Review relevant dictionary definitions and ontology relationships.
3. Generate and inspect SQL in Select AI test; compare profiles when useful.
4. Record a questionable response in Problem questions.
5. Adjust the relevant metadata, profile, or Feedback, then compare another attempt.
6. Validate the actual result against an independently established business answer.

### Investigate data-access behavior

1. Confirm the database user and selected schema.
2. Inspect the relevant VPD or DDS configuration.
3. Search audit records for the time, user, and objects involved.
4. Distinguish source records from archived records and check their coverage.

## Operational notes

- Database changes require appropriate privileges and, where applicable, supporting packages or history repositories.
- Oracle DDL can commit changes and leave partially applied work after a failure. Check the resulting state before retrying.
- AI requests may transmit questions, SQL, definitions, or explicitly selected samples to a configured provider and incur charges. Review the proposed payload before proceeding.
- AI-generated SQL, relationship suggestions, and reviews require human validation.
- Session state, application-managed history, database caches, and audit records are separate sources of information with different retention behavior.

This guide describes the interface reviewed in October 2026. Consult the documentation for your deployed release for exact availability and setup requirements.
