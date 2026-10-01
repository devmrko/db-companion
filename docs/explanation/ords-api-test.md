# ORDS API test

## Design

Each registered HTTP handler opens one test panel. The server combines a configured connection-specific ORDS base URL with freshly read schema, module and template metadata. No client-supplied destination URL is accepted. `ORDS_TEST_ENDPOINTS` is a JSON map from the database name shown on the connection page to a trusted HTTPS ORDS base URL (ending with `/`, usually `/ords/`). Only application operators should edit this allowlist. Private deployments may register their own trusted HTTPS endpoint; loopback and cloud-metadata targets must never be registered.

The panel supports named path variables, query/header name-value pairs, None/Bearer/Basic authentication, and JSON request bodies for POST/PUT/DELETE. Authentication is independent of database login. Credentials remain in the open form only: no local storage, session storage, saved history or request logging. Closing/changing handlers clears the form. A curl copy excludes authentication and custom headers/body/query values, using placeholders so arbitrary secrets are not exported.

Preparation reads metadata only and issues a short-lived, session-bound, single-use token. Execution rechecks current schema and metadata revision, fixes the method and path from the prepared handler, and requires explicit confirmation for non-GET requests. Editing the form invalidates displayed results. One request is active at a time. No application retry or redirect following. HTTP error responses remain HTTP results, distinct from timeout/TLS/network failures; a failed response does not imply rollback.

Transport: Java HTTP client with default TLS certificate/hostname verification, no cookie store or authenticator, explicit request Authorization only, and redirects disabled. Request and response size/time limits keep the test panel bounded. Responses are rendered as text/JSON, never executable HTML; sensitive response headers are redacted. This is a request tester, not automatic business-result validation or a load-testing tool.

## Configuration

```properties
ORDS_TEST_ENDPOINTS={"EXAMPLE_DB":"https://example.invalid/ords/"}
```

Changing the allowlist requires application restart. DB schema registration and endpoint API privileges are not changed by this feature. BASE_URL schema mappings and wildcard/compound route patterns are shown as unsupported until their routing can be represented without guessing; named `:id` path segments and literal paths are supported.

## Validation

Unit tests cover endpoint allowlisting, metadata scope, publication, path traversal/encoding, request/response limits, credentials, redirect behavior, confirmation, expiry and replay. Transport tests use synthetic in-memory/local fixtures. Live verification uses only pre-existing synthetic GET endpoints; no actual mutation handler is invoked.

References: [ORDS route patterns](https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/26.1/orddg/extending-ords-functionality-plugins.html), [Java HTTP client](https://docs.oracle.com/en/java/javase/26/docs/api/java.net.http/java/net/http/HttpClient.html).
