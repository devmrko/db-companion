# Release validation

## Memory recall and access boundaries

Checked on 2026-11-19 using two independent web sessions authenticated as the
same DB account, two disposable profiles/stores and synthetic preferences.

- One seed chat extracted three preferences. Three paraphrased questions in fresh
  conversations recalled their exact values. An unrecorded preference returned
  `UNKNOWN`. The second session recalled a preference from the shared store;
  the separate store returned `UNKNOWN` without any of the seeded values.
  Seven chat requests used seven different conversation IDs; no generation was
  retried. Each successful chat also followed the normal memory-extraction path.
- The second session could browse and read details from the first session's
  store. A record ID from that store returned 404 when requested through the
  other store. Store scoping prevents that cross-store ID lookup; it does not
  prevent a person sharing the DB account from selecting the original store.
- The application currently authorizes by DB owner, not a separately authenticated
  human identity. Different web sessions using one DB account are not isolated
  users. These checks do not prove isolation between different DB accounts or
  provide a general recall-quality benchmark. Multi-user authentication/access
  policy remains a separate design decision.
- A regression test checks stale-schema rejection (409) and foreign-owner list
  and detail rejection (403) before repository reads. The affected Java suite
  passed 13 tests; package, PMD, publication and whitespace checks passed. These
  synthetic owner tests are not a live second-account test.
- Temporary profiles, stores, conversations and store tables were confirmed
  absent after cleanup. A preliminary overlength test name was rejected before
  creation and corrected; no model generation occurred in that attempt.

## Follow-up after alpha.20

Checked on 2026-11-19 using isolated local application and disposable profiles.

- AI setup: actual model recommendations for `comments=true` and `constraints=true`
  each passed preview, consent, existing-editor application and DB readback.
  Missing consent and reused apply tickets were rejected; the temporary profile
  was removed. This verifies supported configuration changes, not answer quality.
- Business checks: three aggregate questions (issue, severity and a bounded month)
  were generated once per native Semantic Source condition. Separate temporary
  profiles had no memory link or app-added glossary evidence. SHOWPROMPT confirmed
  the selected glossary definition was absent/present in the off/on conditions.
  All six manually reviewed SELECT statements matched independent aggregates in
  both directions in a read-only transaction (8, 4 and 1 result groups). Both
  conditions succeeded; these examples do not demonstrate an accuracy improvement
  or generalize to joins, complex business rules or other datasets. Profiles removed.
- Agent Engine: the live demo tool body matched the reviewed read-only source.
  Actual execution passed stock-use, procurement and budget-review branches with
  expected outputs and execution traces; unconfirmed execution was rejected.
  This did not invoke an LLM or verify arbitrary tools, external writes or every
  workflow type. API execution is not reported as browser-driven execution.
- Agent Memory: an initial test request missing required boolean fields was
  rejected before creation. With the request corrected, store creation returned
  an uncertain result; extraction and recall were not attempted. Temporary store,
  profile and associated conversations were cleaned up. The service previously
  hid the Oracle error code; safe-code reporting was added and covered by a
  synthetic exception test. At that stage the cause was unknown and the uncertain
  creation was not retried automatically. Subsequent diagnosis identified the
  native 16-character store-name restriction. After correcting the name, creation
  succeeded but readback rejected the owner-qualified profile name. Both cases
  are now handled explicitly; other owners are still rejected.
- Existing Memory store type filters and empty searches passed read-only API
  checks. Every type returned zero rows, so record detail lookup was not exercised.
  All profiles, stores and conversations created for this follow-up were confirmed
  absent after cleanup.
- After those fixes, a disposable store passed creation readback and profile
  linking. A synthetic report-title preference was extracted from a real chat,
  found through search and record detail, and recalled in a separate conversation
  without repeating the title in its question. Temporary profiles, stores,
  conversations and store tables were confirmed absent after cleanup. This is one
  preference example, not a general recall-quality benchmark. These are API/DB
  checks, not browser-driven creation or recall.
- Chrome confirmed the new setup action catalogue in transmission preview,
  disabled analysis before consent and invalidation after editing the question.
  No model call or DB setting change was made during this browser check. Memory's
  empty-state screen was also confirmed. Agent Engine's registered workflow
  selection loaded its matching archived original into the canvas; browser
  execution was not repeated.
- Automated checks: Java 1,507 passed; JavaScript 724 passed with one optional
  skip. Full suites ran before the final safe-error change; the affected Java
  tests and setup-assistant JavaScript flow were rerun afterward. Package, PMD,
  publication and whitespace checks passed.
- Memory fixes: Java report totals are 1,509 passing tests after the affected
  suite was rerun; unaffected full-suite results were retained. Package and PMD
  passed. The relevant Memory-policy/profile-editor JavaScript tests passed (10).
- Chrome confirmed the updated Memory-name help and browser validity: 17 letters
  and a dollar-sign name were invalid; 16 letters were valid. Inputs were cleared
  without submitting creation or changing the existing profile. The fix was
  deployed to the local 8080 application after backing up its previous JAR.

## 0.1.0-alpha.20

Prepared on 2026-11-19. This release helps users diagnose setup gaps, review
consented changes and revisit stored test evidence. It is not production certification.

- Java: 1,505 passed, no failures/errors/skips. JavaScript: 724 passed, one optional
  case skipped. Executable JAR, PMD, publication and whitespace checks passed.
- Signed-in browser checks covered purpose/profile selection, setup assistant
  preview and consent behavior, saved evidence and recovery links, and profile
  version match/change/unverified displays. Misleading comparison titles and
  diagnostic-stage wording were corrected and checked again in Chrome.
- An approved disposable profile was analyzed once by AI; conversation history
  was enabled through the existing editing service, read back and diagnosed.
  Reusing the apply ticket was rejected. The profile was removed after verification.
- Selected-glossary A/B SQL for one aggregate question matched an independent
  read-only aggregation: ten groups, no differences. Existing native knowledge
  remained in both arms; this does not establish general accuracy improvement.
- Recovery record API storage/link isolation and browser memo/retest selection
  were checked. Stored-setting comparison used synthetic snapshots in the real DB.
  Profile-version checks passed synthetic outcomes through the actual save service,
  then verified attribute/status changes, disabled-profile execution rejection,
  and manual-record uncertainty. These tests made no additional AI calls and their
  synthetic SQL text was not executed. Temporary records/profiles were removed.
- A simulated lookup failure was rendered through the real template; an actual
  DB privilege failure was not induced. The deployed setup pages were checked
  through read-only login/API requests for all three purposes.

Profile-version equality covers the profile ID, status and stored attributes.
It does not compare Credential secrets, linked knowledge/Feedback contents, table
data or per-call overrides; changing and reverting settings can yield the same
version. Equality and linked recovery notes do not establish execution success.

Remaining work: broader business-answer comparisons, a complete native knowledge
on/off comparison, more supported AI configuration changes, and user outcome
measurements. The web update notification/install flow is deferred. Agent Memory,
Agent Engine and ontology changes included from earlier commits passed automated
checks, but this release's setup verification does not certify all their live write
or external execution paths. Unrelated uncommitted workspace changes are excluded.

## 0.1.0-alpha.19

- Java: 1,456 tests passed; no failures, errors or skips. Maven quality verify,
  executable JAR and PMD passed.
- JavaScript: 703 passed with the optional lifecycle case skipped in the full run;
  the real alpha.19 JAR lifecycle suite was run separately.
- Live UI: an existing profile without object_list opened an empty object picker;
  an unset boolean required explicit selection; model/Credential/object_list and
  additional instructions were editable on the selected profile. No settings were
  saved during these browser checks and no paid AI calls were made.
- Synthetic tests cover adding missing attributes, concurrent-change rejection,
  unrelated-attribute preservation and display-only missing rows. A real DB save of
  a previously absent attribute through this revised editor was not exercised.
- Publication guard: 1,104 files, no findings; whitespace check passed.

## 0.1.0-alpha.18

Candidate: **0.1.0-alpha.18** / tag **v0.1.0-alpha.18**, 2026-10-26.
Integrated on top of the complete alpha.17 release history.

- Java tests: 1,454 passed, no failures, errors or skips.
- JavaScript tests: 704 passed, including the opt-in real-JAR lifecycle test.
- Maven quality verify, executable JAR, PMD and whitespace checks passed.
- Publication guard: 1,102 files checked with no findings before release notes.
- Live database validation on 23.26.4.1.0 confirmed native callback creation,
  escaped JSON, explicit errors, and CLOB results up to 1,048,576 characters.
  This is an observed size, not a claimed Oracle maximum.
- A configured profile retained its other stored attributes after attaching the
  source. SHOWPROMPT included glossary definitions with app evidence injection
  disabled and no Agent Tool calls. No paid answer generation was performed.

The app baseline is a tested version, not Oracle's official minimum. Other DB
versions, external MCP configurations and production business answers are not
certified. The ADMIN grant UI was checked through lookup/preview and existing-grant
state; the actual grant used SQLcl during the authorized database verification.
Private captures, credentials and customer data are excluded from this release.

## 0.1.0-alpha.17

Candidate: **0.1.0-alpha.17** / tag **v0.1.0-alpha.17**. Prepared on 2026-10-16.

- Java unit, integration and embedded HTTP tests: 1,444 passed.
- JavaScript application tests: 699 passed; one optional real-JAR lifecycle test skipped.
- Maven quality verify (executable JAR and PMD), publication guard and whitespace checks passed.
- The tests use synthetic fixtures. Maven cache writes and process-inspection tests
  required approved execution outside the restrictive sandbox.

Actual isolated browser checks covered fresh storage/account setup, targeted grants,
object/configuration creation and supported recovery, glossary/JSON/RDF, ontology
deployment switching, audit collection, unpublished ORDS configuration, VPD, local
DDS operations and scheduler execution/state changes. These observations do not
certify other database versions, accounts or business results.

Select AI returned an outbound ACL denial; Feedback also failed. Successful paid AI
output and Agent execution remain unverified. The new outbound ACL screen was
checked through lookup, preview and invalid-input rejection; its actual grant
application awaits approval. Credential secret changes, OCI group-mapping writes
and public ORDS endpoint calls remain unverified. No private captures or credentials
are included. Publishing this version does not restart applications or change DB permissions.

```sh
mvn -Pquality verify
node --test src/test/js/*.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

## 0.1.0-alpha.7

Candidate: **0.1.0-alpha.7** / tag **v0.1.0-alpha.7**. Checked on 2026-10-13.

- Java unit, integration and embedded HTTP checks: 1,345 passed.
- PMD: no violations. Publication guard and whitespace checks passed.
- JavaScript application tests: 650 passed, including the real-JAR lifecycle
  test on isolated ports. The complete run including the optional Agent example
  passed 661 tests, with no failures or skipped tests.
- Synthetic multipart HTTP coverage includes TXT, DOCX, text PDF, invalid formats
  and over-limit uploads. No external AI call or database write is performed by
  these tests. RDF output is parsed by an independent Turtle parser.
- Browser checks used synthetic responses for glossary RDF editing/export;
  this is not a claim of live Oracle glossary persistence or AI correctness.

Run with JDK 21+ and Maven:

```sh
mvn -Pquality clean verify
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.7.jar node --test src/test/js/*.test.mjs demos/semantic-query-agent/*.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

Real-account read/write and paid-provider checks require separately approved
verification. Operational logs, deployment paths and customer evidence are not
included in the public repository. SQL/RDF comparison is display-only and does
not certify date filters, aggregation rules or join multiplicity.

## 0.1.0-alpha.6

Candidate: **0.1.0-alpha.6** / tag **v0.1.0-alpha.6**. Checked on 2026-10-08.

| Check | Result |
| --- | --- |
| `mvn -Pquality clean verify` | Passed; executable alpha.6 JAR built |
| Java unit, integration and embedded HTTP tests | 1,338 passed, 0 failures/errors/skips |
| PMD | 0 violations |
| Complete JS suite including Agent example and real-app runtime test | 658 passed, 0 failures/skips |
| Publication guard | 944 files, 0 findings |
| `git diff --check` | Passed |

Environment: macOS, JDK 25.0.2 compiling for Java 21, Maven 3.9.16, Node 26.
Runtime tests used temporary ports/directories with process-inspection access,
not the user's running application.

```sh
mvn -Pquality clean verify
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.6.jar node --test src/test/js/*.test.mjs demos/semantic-query-agent/*.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

Synthetic workflow tests cover explicit upload, AI consent, proposal selection,
comparison, return navigation, save completion, AI failure without automatic retry,
and accessible tab navigation. Browser checks verified the initial document and
JSON tabs and responsive layout at desktop and 390px widths. Browser file upload
was blocked by extension permissions; it is not claimed as a new end-to-end live
verification. No paid AI requests or live database writes were made for this
release. No customer source documents, account configuration or screenshots are
included. Publishing the source/tag does not redeploy or restart the application.

## 0.1.0-alpha.5

Candidate: **0.1.0-alpha.5** / tag **v0.1.0-alpha.5**. Checked on 2026-10-08
in the glossary feature worktree after integrating the published alpha.4 source.
Publishing source and a version tag does not deploy the application.

| Check | Result |
| --- | --- |
| `mvn -Pquality verify` | Passed; executable alpha.5 JAR built |
| Java unit, integration and embedded HTTP tests | 1,338 passed, 0 failures/errors/skips |
| PMD | 0 violations |
| Complete JS suite including Agent example and real-app runtime test | 655 passed, 0 failures/skips |
| Publication guard | 942 files, 0 findings |
| `git diff --check` | Passed |

Environment: macOS, JDK 25.0.2 compiling for Java 21, Maven 3.9.16, Node 26.
The initial merged build detected duplicate glossary translation keys; duplicates
were removed and all Java checks rerun. Sandboxed process inspection blocked the
first JS lifecycle run; the unchanged full suite passed with process access and
the built alpha.5 JAR. Runtime tests used temporary ports and directories.

```sh
mvn -Pquality verify
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.5.jar node --test src/test/js/*.test.mjs demos/semantic-query-agent/*.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

Browser checks used isolated synthetic responses, not customer records: upload,
keyword search, consent, AI proposal rendering, source quotes, review and save UI.
The shared stylesheet/template and import controls were visually checked at normal
desktop width and 390px. These checks do not establish live Oracle embedding,
paid AI response quality or real glossary-write correctness. No live DB objects,
profiles, credentials or the existing application process were changed.

## 0.1.0-alpha.4

Candidate: **0.1.0-alpha.4** / tag **v0.1.0-alpha.4**. Checked on 2026-10-06
in an isolated publication worktree based on alpha.3. Publishing source and a
version tag does not deploy the application or install database objects.

| Check | Result |
| --- | --- |
| `mvn -o -Pquality clean verify` | Passed; executable alpha.4 JAR built |
| Java unit, integration and embedded HTTP tests | 1,327 passed, 0 failures/errors/skips |
| PMD | 0 violations |
| Complete JS suite including the optional Agent example and real-app runtime test | 651 passed, 0 failures/skips |
| Publication guard | 931 files, 0 findings |
| `git diff --check` | Passed |

Environment: macOS, JDK 21.0.12.1, Maven 3.9.16 and Node 26.0.0. Three lifecycle
checks initially failed because sandboxed process inspection was unavailable; the
unchanged full JS suite then passed with process access and the built JAR.
Runtime checks used temporary directories and ports, not the existing application.

```sh
mvn -Pquality clean verify
# Set JAVA_HOME to the intended test JDK before running the next command.
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.4.jar node --test src/test/js/*.test.mjs demos/semantic-query-agent/*.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

These integration checks did not call a paid AI provider, log into a live database,
install the optional APEX/PLSQL example, send reports or change operational jobs.
The Agent example has contract and mocked-client tests, not a portable live-install
certification. Business totals, DB privileges and app-specific APEX wiring need
separate validation. Publication scanning is heuristic; account-specific configuration,
customer data and private recording artifacts are deliberately excluded.

## 0.1.0-alpha.3

Candidate: **0.1.0-alpha.3** / tag **v0.1.0-alpha.3**. Checked on 2026-10-01
from an isolated copy of the integrated public source, preserving existing work.

| Check | Result |
| --- | --- |
| `mvn -Pquality clean verify` | Passed; executable alpha.3 JAR built |
| Java unit / integration / embedded HTTP tests | 1,242 tests, 0 failures, 0 errors, 0 skipped |
| PMD Maven plugin 3.28.0 | Passed; 0 violations |
| Complete JS suite with opt-in runtime test enabled | 599 passed, 0 failed, 0 skipped |
| Publication guard | 860 candidate files, 0 findings |

Environment: macOS, JDK 25.0.2 compiling for Java 21, Maven 3.9.16, Node 26.
An initial sandboxed JS run could not inspect processes for two lifecycle tests;
the full suite was rerun with process access and the real alpha.3 JAR, without
changing the tests. Runtime checks used temporary directories/ports; the existing
8080 app and database sessions were not restarted.

```sh
mvn -Pquality clean verify
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.3.jar node --test src/test/js/*.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

Set `JAVA_HOME` before the runtime test. The release checks made no database changes
or paid AI calls. Prior feature-specific DB/UI checks do not validate all production
environments. Help-catalog content review is a separate audit; a passing test suite
does not certify every SQL example, database privilege configuration or generated
business answer. Publication scanning is heuristic and not a complete security audit.
The version tag identifies the final integrated, tested tree; intermediate feature
commits are not separately released or certified.

## 0.1.0-alpha.2

Candidate: **0.1.0-alpha.2** / tag **v0.1.0-alpha.2**. Checked on 2026-09-28
from an isolated copy of all public-source publication candidates.

| Check | Result |
| --- | --- |
| `mvn -Pquality clean verify` | Passed; executable alpha.2 JAR built |
| Java unit / integration / embedded HTTP tests | 1,016 tests, 0 failures, 0 errors, 0 skipped |
| PMD Maven plugin 3.28.0 | 0 violations; 0 processing errors |
| `node --test src/test/js/*.test.mjs` | 482 passed, 0 failed; 1 opt-in runtime test skipped |
| Opt-in `app-runtime.test.mjs` with the alpha.2 JAR | 3 passed, 0 failed, 0 skipped (includes that opt-in test) |
| Publication guard | 671 candidate files, 0 findings |

Environment: macOS, JDK 25.0.2 compiling for Java 21, Maven 3.9.16, Node 26.0.0.
The runtime suite used temporary directories/ports and did not restart the existing app.
Test schema/object names are generic examples; customer records, Wallets, credentials,
private validation artifacts and environment configuration are excluded.

Reproduce:

```sh
mvn -Pquality clean verify
node --test src/test/js/*.test.mjs
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.2.jar node --test src/test/js/app-runtime.test.mjs
node scripts/publication-check.cjs --working-tree
git diff --check
```

This release check did not log into a database, create or alter DB objects, or call a
paid AI provider. Prior feature-specific UI/DB checks are not a complete live-system
validation of this release. SQL examples, language-specific Oracle Text setup and
provider behavior still require validation in the target database and account.
Java 21 runtime and other operating systems were not separately exercised. Publication
scanning is heuristic, not a complete secret/security audit or a production-readiness
certification. A successful build does not establish generated SQL's business correctness.

## 0.1.0-alpha.1 (historical)

Candidate: **0.1.0-alpha.1** / tag **v0.1.0-alpha.1**. Checked on 2026-09-23.

### Results

| Check | Result |
| --- | --- |
| `mvn -Pquality verify` | Passed; executable JAR built locally |
| Java unit / integration / embedded HTTP tests | 772 tests, 0 failures, 0 errors, 0 skipped |
| PMD Maven plugin 3.28.0 / PMD 7.17.0 | 0 violations; no report processing errors |
| `node --test src/test/js/*.test.mjs` | 348 passed, 0 failed; 1 opt-in runtime test skipped |
| Opt-in `app-runtime.test.mjs` with built JAR | 3 passed, 0 failed, 0 skipped (includes the opt-in test above) |
| Source publication guard | 0 findings in the prepared source tree; rerun against staged files before publication |

PMD checks production Java using the plugin's default rules plus `category/java/security.xml`. No violation suppression or failing-priority relaxation was added. Initial findings were addressed through equivalent condition simplification, removal of redundant imports/qualifiers, and an unused parameter cleanup that preserves the package-owner preflight call.

Tests ran on macOS with JDK 25.0.2, compiling for Java 21, Maven 3.9.16 and Node 26. The Java 21 runtime and other operating systems were not separately exercised in this validation. Existing deprecation / unchecked-compilation warnings remain; a successful PMD run is not a claim of warning-free compilation.

The runtime test creates its own temporary directory and port. It verifies PID ownership checks, duplicate startup protection, readiness, restart and shutdown without using an existing application process or database session. It requires OS process-list access; sandbox denial is not an application assertion failure and must not be hidden by weakening the test.

### Reproduce

```sh
mvn -Pquality clean verify
node --test src/test/js/*.test.mjs
RUNTIME_TEST_JAR=target/db-manage-companion-0.1.0-alpha.1.jar node --test src/test/js/app-runtime.test.mjs
node scripts/publication-check.cjs
```

Set `JAVA_HOME` to a suitable JDK if Java is not available on the command path.

### Scope and limitations

- No live customer database login, DDL, Select AI generation, or billable model call was performed for this public-source validation.
- Historical PoC evidence and customer-specific diagnostic utilities are intentionally excluded. Their associated private tests are not counted as public tests. A public regression test checks that the removed controllers are absent even with a diagnostic profile enabled.
- Test fixtures use anonymized schema, object and environment identifiers. Derived trigger-name hashes were regenerated for the anonymized fixture identifiers.
- Publication scanning is heuristic, reports filenames rather than secret values, and is not a full secret audit, dependency vulnerability scan, penetration test or production-readiness certification.
- Neither local verification nor an alpha tag means a GitHub release has been published.
