# DB Companion low-touch development

## Autonomous work

- Continue with reasonable defaults for low-risk, reversible implementation choices within the assigned task.
- Inspect existing code before editing. Use apply_patch for source edits and preserve unrelated changes.
- Run the relevant Java and JavaScript tests, build, PMD checks, and publication checks. Diagnose and repair in-scope failures before reporting.
- Deliver the requested working feature and tests, not only a design, preview button, mock, or placeholder. Clearly list genuinely blocked or incomplete requirements.
- Do not stop for routine progress confirmation. Report concise milestones and evidence.

## Boundaries

- Keep changes inside this worktree and the explicitly assigned component. Do not launch more workers.
- Do not commit, push, merge, publish, deploy, or create a release unless the user explicitly requests it after this policy is applied.
- Ask before destructive actions, production changes, paid AI requests, external notifications, or changes to actual database schemas, grants, credentials, profiles, feedback, comments, and annotations.
- Existing, explicitly authorized local credentials may be read in memory only for the assigned login/read-only verification. Never print, expose in process arguments, transmit to an unrelated destination, capture, or commit credentials, cookies, keys, Wallet contents, or customer records.
- Use the assigned isolated localhost test port. Do not stop or restart the user's existing application.
- Preserve the enforced sandbox and managed policies. Request the supported approval path for boundary crossings; do not bypass denials or automate approval dialogs.
- A change in public API, data model, security boundary, or task scope that has not already been decided requires clarification. Ordinary details within the approved plan do not.
- Keep customer-specific plans, logs, and screenshots in private ignored storage; public tests use synthetic examples.

## Completion evidence

Report changed files, checks actually run and their results, login/UI verification scope, untested external or write paths, and remaining risks. Do not equate code generation, a mocked success, or a syntactically valid SQL statement with real database or business correctness.

<!-- BEGIN vector-knowledge-memory-policy -->
## Durable knowledge references

- Use the installed `vector-knowledge` skill / `vector_knowledge` MCP for personal and project memory. Follow the current user's storage authorization; this file grants none.
- Keep required project/build/test/security instructions here. Store durable decisions, preferences and verified work history in the knowledge DB; local memory should contain short knowledge/index references, not duplicate bodies.
- Reuse the confirmed project scope/index; do not infer that similarly named projects are identical. Read exact content/history before revising an existing fact; confirm READY before replacing a local record with its reference.
- Preserve original source/design/ADR documents as evidence. If the service is unavailable or migration is incomplete, retain existing unique local knowledge and report the gap; do not create a new Markdown memory mirror.
<!-- END vector-knowledge-memory-policy -->
