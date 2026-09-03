# ADR 003 — All-REST architecture (no direct DB access)

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** [author]

## Context

The platform runs cloud-hosted. Every environment (sandbox, design, staging, production) is auth-gated by a single sign-on. Developers access these environments via the platform's browser-based Studio. The developer's laptop has no local platform install, no local database, and no direct network path to any environment's database — the DB is only reachable from inside the platform's application server.

Initial design ideas assumed the tool could use JDBC directly (write to local, read from source). This assumption was invalidated once the deployment model was clarified: "local" env in the developer's mental model is a per-developer sandbox hosted on a server, not a laptop-side database.

## Decision

All communication with any platform environment goes through the platform's REST APIs over HTTPS. The tool holds no JDBC connections to any platform environment. JDBC is used only for the tool's own state (embedded H2 for query history — planned).

## Alternatives considered

- **Direct JDBC to source and target** — Simpler code, faster bulk operations. Rejected: no network path from developer laptop to any platform environment's database.
- **Hybrid — REST to read, JDBC to write** — Would require developer machines to have some local DB access. Not possible in the current infrastructure.
- **All-REST (chosen)** — Only viable option given infrastructure constraints. Adds an auth surface and per-entity POST volume for Module 3, but is architecturally forced.

## Consequences

- **Positive:** No credentials to store beyond the platform's bearer token. Uses the platform's own REST layer, so cascading, validation, and business logic are enforced by the platform itself.
- **Negative:** Module 3 bulk imports may generate tens of thousands of REST calls (target: 1000 apps × ~30-80 entities each). Token expiry during long runs is a real failure mode. Fetching an app tree and re-posting it entity-by-entity is more code than a single JDBC insert would have been.
- **Neutral:** The dataModel JSON becomes the source of truth for entity structure and relationships (see ADR 002 for its persistence model). Bulk operations will need retry logic and progress persistence so long runs can resume after auth failure.

## References

- `com.devbridge.fawb.FawbClient` (planned)
- Related ADRs: [ADR-002 — JSON-file-per-profile storage](adr-002-json-file-profile-storage.md)
