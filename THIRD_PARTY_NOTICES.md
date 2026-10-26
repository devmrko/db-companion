# Third-party dependencies

The MIT license at the repository root applies to DB Companion's own code, not to its dependencies or third-party trademarks. This initial release distributes source only: downloaded libraries, wallets, JARs and build outputs are not committed or attached as release binaries.

Direct dependency declarations and their Maven POM license metadata were reviewed for the initial candidate on 2026-09-23. This is not an exhaustive transitive dependency inventory or a legal certification.

| Component | Version declared | Upstream license / notice |
| --- | --- | --- |
| Spring Boot and Spring modules | Boot 4.1.1, managed modules | Apache License 2.0; preserve upstream notices |
| Oracle JDBC (`ojdbc11`) and `oraclepki` | 23.26.3.0.0 | [Oracle Free Use Terms and Conditions](https://www.oracle.com/downloads/licenses/oracle-free-license.html), as declared by both artifact POMs; not MIT |
| JSqlParser | 5.3 | POM lists Apache License 2.0 and LGPL 2.1; retain upstream license files |
| Eclipse RDF4J | 5.3.2 | Eclipse Distribution License 1.0, as declared by the parent POM |
| Bootstrap | 5.3.8 | [Bootstrap MIT license](https://getbootstrap.com/docs/5.3/about/license/); the WebJar packaging POM separately declares Apache License 2.0 |
| Cytoscape.js | 3.34.1 | MIT, as declared by the WebJar POM |
| Apache PDFBox | 3.0.8 | Apache License 2.0; retain upstream LICENSE and NOTICE. Used only for text extraction, not OCR. |

Other libraries are resolved transitively by Maven. Use `mvn dependency:tree` to inspect the resolved dependency graph. PMD is a build-time tool, not part of the application runtime.

Before distributing a packaged application, review the complete resolved dependency set and include the required license texts and notices. In particular, a local Spring Boot executable JAR contains third-party libraries; it must not be described as entirely MIT-licensed.
