# Conversational setup workspace

Describe a setup task in one conversation and review the corresponding configuration beside it. The conversation remains visible while the workspace changes from target selection to editing, comparison and recorded results.

## Contract

- Reuse existing owner-scoped profile, glossary, semantic-source and history services. Do not add a second configuration store, model framework or arbitrary SQL executor.
- Ask for a missing target in the conversation. Session model selection is separate from the profile being edited.
- Recognized setup commands open a real editor: profile options, glossary registration/editing, or attaching an existing semantic source. Ambiguous requests go to the existing opt-in AI analysis, with reviewed payload and cost consent.
- The command dispatcher is a bounded UI shortcut, not a general language model. Unsupported requests must not imply that they were executed.
- Each write requires a visible comparison and an explicit Apply action. Editing, cancelling, changing targets or starting another request invalidates the local pending change. Reuse server version checks; never retry an uncertain write automatically.
- Show actual readback and available history after execution. A history lookup failure must not change a verified save into a failed save, or imply a missing history record.
- Keep conversation state in page memory only; no localStorage, cookies or server-side transcript store. Refresh starts a new conversation. Do not weaken CSP or embed configuration pages in frames.

## First supported tasks

1. Enable/disable conversation, comments or constraints; edit registered profile attributes through the existing attribute editor contract.
2. Register or edit a business term through successive questions, review the draft, save with the existing atomic glossary/history transaction, and inspect history.
3. Select an existing enabled native semantic source and append it to the selected profile without replacing other sources; review before saving.
4. Read profile history and configuration readiness. Other requests use the existing optional service-Tool assistant. This does not implement arbitrary multi-step autonomous configuration or source/callback creation.

## Acceptance

Opening the dedicated route presents a usable chat immediately. A request made before target selection survives the clarification. The workspace changes without page navigation and previous messages stay visible. Real API results populate the editor. No write or paid model call occurs merely by opening or chatting through clarification. Apply is single-use and tied to the displayed draft; errors and uncertain results cannot be retried implicitly. Keyboard, narrow-screen layout, untrusted text rendering and cancellation are tested.
