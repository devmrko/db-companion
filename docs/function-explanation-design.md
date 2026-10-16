# Function code explanation and business context

## Scope

Extend the existing Functions > AI explanation workflow. No routine execution,
test invocation, code editing, DDL, COMMENT, glossary update or persistent storage.
Reuse the selected AI Assistant and explicit external-transmission/cost consent.

1. Read the chosen routine's current visible source and argument metadata.
2. Preview the exact source, numbered per source section, plus argument metadata.
3. On explicit consent, explain purpose, inputs, outputs, validation, ordered
   processing steps, exceptions, side effects and unknown dependencies in the UI language.
4. Keep the code explanation visible. The user may add up to 4,000 characters of
   business purpose, expected use and constraints. Preview this supplemental context
   with the code, then require fresh consent before another paid explanation call.
5. Show the business-context explanation separately. Context is user-supplied intent,
   not proof that the code implements it. Explain contradictions instead of hiding them.

## Safety and lifetime

Use the existing CSRF-protected preview and one-use, ten-minute AI request token.
Generation accepts only token/consent; editable source, profile and action never come
from that request. Context and source are untrusted data, not model instructions.
The total payload is bounded at 64,000 characters; never truncate silently. Wrapped,
unavailable or ambiguous source is rejected. Package source scope is explicit.
Read current metadata afresh at preview and verify unchanged source/arguments before
the model call. Do not infer behavior of unseen dependencies or dynamic SQL.
Explanation is static analysis, not execution evidence or a correctness guarantee.

Each context edit invalidates the local preview and consent. While a request is in
flight, disable editing/reconfiguration. Closing clears results and context from the
UI; server preview data expires with the existing session/ticket lifecycle. No browser
storage or application database persistence. DB/provider retention is separate.

## Verification

Synthetic tests cover bounded/escaped inputs, numbered source, unknown dependencies,
language selection, context/code separation, source-change rejection, one-use consent,
preview-only reads, plaintext rendering and preservation of the initial explanation.
No actual AI call or database routine execution is part of automated verification.

Verified on 2026-10-14: Java 1,395 tests passed; JavaScript 679 passed and one optional
test skipped. Build, PMD, four-language template rendering and publication checks
passed. UI flow tests exercise consent invalidation, preserving the initial explanation
when refinement fails, and clearing temporary content on close. Actual paid AI output
and signed-in browser layout have not been verified. This change is not deployed.
