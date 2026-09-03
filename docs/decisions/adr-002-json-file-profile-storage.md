# ADR 002 — JSON-file-per-profile storage

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** [author]

## Context

Project profiles hold per-project configuration: base URL, sandbox / design instance IDs, service name, facade endpoint path (with typo-preserved query param names), local dataModel JSON path. Each developer creates one or more profiles and switches between them. Profiles must persist across app restarts.

Two storage patterns were considered: (a) an embedded database (H2), (b) plain JSON files on disk.

## Decision

Store each project profile as a separate JSON file under `%USERPROFILE%\.devbridge\profiles\<uuid>.json`. Load all files into an in-memory `ConcurrentHashMap` on startup. Write back to disk on save/delete.

## Alternatives considered

- **H2 embedded database** — Standard, transactional, queryable. Rejected because profiles are read-heavy, small in count (5–20 per developer), and benefit from being human-readable and shareable as files. No relational structure is needed.
- **A single `profiles.json` file** — Simpler I/O than one-file-per-profile. Rejected because concurrent editing between the tool and a manual edit (dev opens the file in Notepad) is safer with one file per profile — no risk of overwriting other profiles.
- **JSON file per profile (chosen)** — Human-inspectable, git-shareable (with credentials excluded), atomic per-profile write, simple to reason about.

## Consequences

- **Positive:** Developers can share a profile file directly with a teammate (e.g., over chat). Debugging is trivial — open the file in a text editor. No SQL migrations to maintain as the profile schema evolves (just handle missing fields on load).
- **Negative:** No transactional guarantees across multiple profiles. Concurrent writes to the same profile (unlikely — single-user tool) would need file-locking.
- **Neutral:** If we later add query history and saved queries, we'll evaluate storage separately — H2 is likely the right fit for those, since they're higher-write and benefit from queries like "queries run in last 7 days."

## References

- `com.devbridge.profile.ProfileRepository`
- Related ADRs: none
