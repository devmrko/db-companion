# Conversational setup workspace

Open **Setup assistant** at the lower right of any authenticated screen. The modeless window keeps the underlying page interactive. Closing and reopening preserves the conversation and pending task within the current document; page reload or navigation starts again.

## Global modeless shell

The common app shell mounts one launcher. The first open fetches the known server-rendered workbench component and mounts the existing workflow once. No iframe, backdrop, focus trap or new configuration API. Close hides the same DOM rather than destroying it. Target context comes from the current profile page, then the explicit target picker. A task opens beside conversation on desktop; on narrow screens the header switches between task and conversation. The dedicated route remains compatible, while its menu entry opens the same modeless window.

Dashboard readiness keeps static help, the loaded summary and actions inside the card's padded content area. Status labels distinguish configuration evidence from execution success. Loaded content must not escape that area or duplicate navigation.

## Contract

- Reuse existing owner-scoped profile, glossary, semantic-source and history services. Do not add a second configuration store, model framework or arbitrary SQL executor.
- Ask for a missing target in the conversation. Session model selection is separate from the profile being edited.
- Every free-text message goes to the selected LLM with the current phase, safe draft fields, last workspace prompt and recent server-held dialogue. It can select registered in-process read services using their input/output contracts and reference the app menu catalogue. The old command classifier is not the default chat path.
- Choose the conversation model and transmission/usage consent once when starting. A message can use up to seven model calls including service exchanges. This is separate from consent for profile changes or SELECT generation/execution. Unsupported operations are explained with a registered menu link.
- Each write requires a visible comparison and an explicit Apply action. Editing, cancelling, changing targets or starting another request invalidates the local pending change. Reuse server version checks; never retry an uncertain write automatically.
- Show actual readback and available history after execution. A history lookup failure must not change a verified save into a failed save, or imply a missing history record.
- Draft/workspace state stays in page memory. The last twelve dialogue messages stay in login-session server memory, bound to the helper profile and version; there is no persistent transcript store. Refresh resets page state and consent; logout expires server state. Do not weaken CSP or embed configuration pages in frames.

## First supported tasks

1. Enable/disable conversation, comments or constraints; edit registered profile attributes through the existing attribute editor contract.
2. Register or edit a business term through successive questions, review the draft, save with the existing atomic glossary/history transaction, and inspect history.
3. Select an existing enabled native semantic source and append it to the selected profile without replacing other sources; review before saving.
4. Read profile history and configuration readiness. Registered model-selected reads cover profiles, creation candidates, safe profile attributes, semantic sources and glossary readiness. Other menus are described as references, not arbitrary autonomous executors. Source/callback creation still uses existing management screens.

The creation workflow exposes the current draft phase to the LLM. Recommendations do not edit the draft until the user asks or accepts; edits preserve unspecified fields. Table names are bound to the login schema and verified against actual metadata. [Conversation design](../llm-conversation-design.md).

## Acceptance

Opening the dedicated route presents a usable chat immediately. A request made before target selection survives the clarification. The workspace changes without page navigation and previous messages stay visible. Real API results populate the editor. No write or paid model call occurs merely by opening or chatting through clarification. Apply is single-use and tied to the displayed draft; errors and uncertain results cannot be retried implicitly. Keyboard, narrow-screen layout, untrusted text rendering and cancellation are tested.
