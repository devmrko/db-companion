# ORDS management layout

The management page presents schema status as a compact summary, with modules and URI templates in separate responsive panels. A selected handler shows its HTTP method, source type, comments and read-only SQL/PLSQL source. Complete metadata remains available in collapsed disclosures; it is never silently discarded.

The displayed path combines the registered BASE_PATH schema, module URI prefix and selected template. It is not a complete endpoint URL: the application's host and port are not the ORDS host. Path placeholders are preserved. The page does not make automatic REST requests.

Registration, preview, exact confirmation, mutation and permission behavior are unchanged. All metadata and source are rendered as text, never HTML. Existing source-viewer search, wrapping and copying are reused. CSS is scoped to the ORDS page, including mobile layout and expanded metadata.
# Contextual guidance

The overview shows navigation and current state. Publication defaults, metadata authentication and handler source behavior are available as collapsible field help. Preview shows publication, REST enable/disable and deletion effects only for the requested transition. Unrelated edits do not display deletion warnings. The exact confirmation, preview expiry, concurrency checks and no-retry behavior are unchanged.
