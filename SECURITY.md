# Security

This alpha project manages database connections and can perform explicitly requested
metadata/configuration changes. Use it locally with a dedicated least-privilege account.
The presence of CSRF controls, tests, or static-analysis checks is not a security audit.

Do not commit credentials, `.env`, Wallet files, private keys, database extracts, generated
prompts, or customer screenshots. Treat application logs and diagnostic reports as
sensitive. Review provider data-processing policies before any AI call.

Review grants and DDL before enabling history triggers or editing database metadata.
DDL can commit independently and an unsuccessful multi-step operation may require manual
reconciliation. Back up important definitions before changes. Do not use a production
database for experimental tests.

If you find a vulnerability, do not put passwords, exploits against live systems, or
customer information in a public issue. Use GitHub private vulnerability reporting if it
is enabled on the repository. Otherwise open a minimal issue requesting a private contact
without disclosing the vulnerability itself. No response-time or support SLA is promised.

Before exposing the application outside localhost, review TLS, reverse-proxy trust,
network access, account permissions, session isolation, logging, provider egress, and the
deployment's threat model. No hosted multi-tenant service is provided by this repository.
