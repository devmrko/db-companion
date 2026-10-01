# Release validation

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
