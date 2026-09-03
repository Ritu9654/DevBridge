# DevBridge — Changelog

All notable changes to this project are recorded here, most recent first.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), grouped by version. Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each entry is grouped by category: **Added**, **Changed**, **Deprecated**, **Removed**, **Fixed**, **Security**.

> **How to update.** Every non-trivial change belongs here — add to the `[Unreleased]` section as work happens. When you cut a release, rename `[Unreleased]` to the version + date and open a new `[Unreleased]` at the top.

---

## [Unreleased]

Work in progress toward v0.1.0. See [PROJECT.md § 5](PROJECT.md#5-progress-tracker--milestones) for the live phase tracker.

### Planned next
- Auth token holder + paste-token modal
- FAWB REST client (HTTPS wrapper with bearer + 401 detection)
- Test-connection button on profile cards
- DataModel JSON loader

---

## [0.1.0-SNAPSHOT] — Bundled parent+child JSON insert path (CASE_DATUM finally works) — 2026-08-31

### Root cause we're accepting we can't fix at the CSV layer
Diagnostic proved (see `DiagnoseController` 6-variant probe): WaveMaker's CSV `/import` parser recognises `\"` as escaped quote, NOT the RFC 4180 `""` doubling — AND it cannot correctly handle a comma inside a quoted field regardless of escape strategy. Multi-key JSON (which is what real CASE_DATUM's `datum` is) contains internal commas as part of the JSON syntax; those break the parser every time. This is a WaveMaker CSV limitation, not fixable by tweaking escape rules.

### Fix: bundle the child under its parent via a JSON endpoint
Investigated FAWB source — found `POST /services/caseheader/caseHeader` (`CaseheaderController.java:102`), which accepts `com.fico.ps.casemanager.CaseHeader` (the ObjectNode-variant, not the auto-generated CRUD entity). Because the entity's `datum` field is a Jackson `ObjectNode`, the deserialiser reads request-body JSON directly into memory — bypasses the XSS filter that corrupts `datum` on the standard REST create path, and bypasses the CSV parser entirely.

Confirmed via source read:
- Client-supplied UUID is respected — `@PrePersist` only generates when `id` is null (`CaseHeader.java:274-286`).
- Nested `data: [CaseDatum]` cascades on save (`CaseHeader.java:650-657` — `@OneToMany @Cascade(ALL)`).
- `process=false` skips PLOR / rule-engine calls; persistence still runs (`BusinessContextDispatcher.postsave` line 103).
- Trade-off: `@CreationTimestamp` / `@GeneratorType(LoggedUserGenerator, INSERT)` overwrite `createdOn` / `createdBy` — accepted by user.

### Fix: quote escape aligned with parser
Also changed `csvEscape` from `""` to `\"` — the diagnostic proved this is what WaveMaker actually recognises. Strict improvement for every table whose CSV happens to contain internal quotes but no internal commas (LONGTEXT text fields with quoted words, etc.).

### Added
- **`profile.nestedInsertConfig`** — optional per-project config: endpoint URL, parent table, child table, nested field name, optional query params. See `NestedInsertConfig` Javadoc. When set, the parent's insert changes path (JSON POST); the child table is skipped from standalone processing.
- **`AppNestedInsertService`** — builds the parent+children JSON body (using dataModel `fieldName` for camelCase keys, parsing large-text source strings into nested JSON objects for ObjectNode target fields), POSTs, populates id-map with new UUIDs.
- **Routing update** in `AppSqlExecutorService.execute()` — if `nestedInsertConfig` is set on the profile, the configured parent table goes through `AppNestedInsertService`; child table is skipped. Zero impact when the field is null (existing behaviour).

### Rollback still works
The journal walks in reverse and SQL-DELETEs by PK. Doesn't care that rows landed via a JSON endpoint instead of INSERT.

### CaseManager profile config to enable this
Add to your CaseManager profile JSON:
```json
"nestedInsertConfig": {
  "endpointUrl": "/services/caseheader/caseHeader",
  "parentTable": "CASE_HEADER",
  "childTable": "CASE_DATUM",
  "nestedFieldName": "data",
  "queryParams": { "process": "false" }
}
```

---

## [0.1.0-SNAPSHOT] — DiagnoseController: characterise CASE_DATUM CSV failure — 2026-08-31

### Added
- `DiagnoseController` at `/api/diagnose/case-datum-size`. Sends synthetic single-row CASE_DATUM CSVs at progressively larger `datum` sizes (default 1 KB → 500 KB), each with a valid-but-nonexistent `case_header_id`. FAWB's response tells us:
  - `foreign key constraint fails` → CSV parsed cleanly at this size; the payload is fine.
  - `row conversion for column …` → CSV parser choked at this size; this is the failure mode we've been fighting.
- Payload uses only ASCII `'a'` padding inside a `{"pad":"…"}` JSON blob — isolates SIZE as the only variable (no backslashes, no nested quotes).
- Returns a JSON report with per-size classification and a summary line like "Largest datum where CSV parsed cleanly: X bytes; smallest failing: Y bytes."

### Why
Every "row conversion for column IS_ACTIVE" fix I've tried on the real import (value format, missing column, backslash escaping, chunking) has landed the same-looking error. This endpoint decouples the size question from the content question so we get a clean signal on one variable before touching main import code again.

### How to run
`POST /api/diagnose/case-datum-size` — optional body `{"targetEnv":"sandbox","sizes":[1000,10000,100000,500000]}`. Sizes default to 1KB → 500KB. Requires an active profile with sandbox base URL and dbServiceName.

---

## [0.1.0-SNAPSHOT] — Chunk CSV imports by row size — 2026-08-31

### Changed
- **CSV import now splits into size-bounded chunks.** Previously we packed every row of a table into one CSV file and posted it. For CASE_DATUM, row 2's datum field alone is 472 KB. When batched with other rows in one CSV, WaveMaker's parser was misbehaving in a way that surfaced as "row conversion for column IS_ACTIVE" — every attempted fix at the value/column/escaping level failed to resolve it, so the remaining structural hypothesis was that a huge single row poisons the parser for the whole file.
- New `MAX_CSV_CHUNK_BYTES = 100 KB` in `AppCsvImportService`. Rows batch until adding another would exceed the limit; oversized single rows go alone in their own chunk. `planChunks()` computes the ranges; each chunk becomes a separate multipart POST with filename `<table>-chunk<N>.csv`.

### Partial-success handling
- If chunk N fails, chunks 1..N-1 already landed in target. `importRows` returns `assignedPks` for just those N-1 chunks. `AppSqlExecutorService.executeTableViaCsvImport` now journals those rows BEFORE checking `res.ok()`, so the rollback path deletes them cleanly. Failure message notes how many rows are stranded.

### Test decisively
- If chunking works, size was the issue and we're past CASE_DATUM.
- If chunking doesn't work, we know the problem isn't per-CSV size — it's something we haven't identified yet, and the individual row 2 CSV upload will fail on its own (which is at least a cleaner failure to diagnose).

---

## [0.1.0-SNAPSHOT] — CSV backslash escaping (WaveMaker uses OpenCSV backslash-aware parser) — 2026-08-31

### Fixed
- CASE_DATUM CSV import STILL failing "row conversion for column IS_ACTIVE" even with all previous fixes (auto-detected routing, correct boolean format, complete column set, byte-clean CSV). Read the actual byte content of the failing CSV file and found **1316 backslashes** in the datum field — source stores nested JSON with backslash-escaped quotes like `{"rawIDVResponse": "{\"code\":\"2302\",...}"}`.
- WaveMaker's `wmGenericDao.importData` uses OpenCSV's default `CSVParser`, which is NOT strict RFC 4180: it treats `\` as an escape character in addition to the doubled-quote mechanism. Inside a quoted CSV field, `\""` is parsed as `\`-escaped `"` (consuming ONE quote), then the next `"` closes the field. Every character after that gets read as a NEW field, misaligning every subsequent column. WaveMaker reports the conversion failure against the LAST column it tried to convert (`IS_ACTIVE`) — the actual problem is way upstream in the datum column.

### Change
`csvEscape` in `AppCsvImportService` now escapes backslashes to `\\` in addition to doubling quotes. Order matters: backslash escaping runs FIRST so the backslashes we insert when doubling quotes don't get double-escaped. Under OpenCSV backslash-aware parsing, `\\` decodes to a single `\` — round-trip clean. Under strict RFC 4180 the `\\` would round-trip as double-backslash, but if a target parser were strict-RFC we wouldn't be seeing this bug in the first place.

### Investigation shortcut for future me
When "row conversion for column X" persists despite the value at X being clearly correct, look for **structural CSV parsing issues UPSTREAM of X** — most commonly, a large text column with content that breaks the parser's quote-handling. Check for backslashes.

---

## [0.1.0-SNAPSHOT] — Fix executeSQLs multi-row parser (was silently dropping all rows) — 2026-08-31

### Fixed
- The `information_schema` CHECK-constraint query was working end-to-end — FAWB was returning a full list including CASE_DATUM. But my parser was silently dropping every row because I got the wire format wrong: I assumed the response was ONE envelope containing a stringified JSON array of rows (`[{sql, response:"[{row1}, {row2}, ...]"}]`), but FAWB actually returns ONE ENVELOPE PER ROW (`[{sql:null, response:"{row1}"}, {sql:null, response:"{row2}"}, ...]`). Discovered by reading the actual response body from `sql-diagnostic.log`.
- Fixed `SchemaMetadataService.parseTableNames` to iterate every envelope and treat each `response` field as either a single-row JSON object OR an array (supporting the alternate shape if FAWB ever changes).

### Impact
CHECK detection now works. On the next run the log should show:
`"CHECK-constraint detection via TABLE_CONSTRAINTS found N table(s): [AUDIT_CASE, AUDIT_CONFIG, CASE_ACTIVITY_DATUM, CASE_DATUM, ...]"`
and then intersected with large-text tables — CASE_DATUM (and any others with both a CHECK and a LONGTEXT column) → routed to CSV path automatically.

### Investigation trail
1. User's run failed with `CONSTRAINT 'CASE_DATUM.datum' failed` — CSV-only detection said zero tables.
2. Grepped `sql-diagnostic.log` for `information_schema` — found the query DID run and DID return CASE_DATUM.
3. Compared the raw response bytes to what my parser expected — one-envelope-per-row, not one-envelope-with-array.
4. Fixed parser.

### Lesson
Parsers assumed from convention beat parsers based on actual wire samples — I should have read the diagnostic log the first time I added detection, not the third.

---

## [0.1.0-SNAPSHOT] — Robust CHECK-constraint detection (3 strategies + logging) — 2026-08-31

### Fixed
- Auto-detection silently returned zero tables on the CaseManager sandbox even though CASE_DATUM has a documented CHECK on `datum` (evidenced by the runtime error `CONSTRAINT 'CASE_DATUM.datum' failed`). CASE_DATUM was routed to the SQL path and immediately failed the CHECK on the first split-UPDATE chunk. Root cause was single-strategy detection with insufficient logging — we couldn't see whether the information_schema query was blocked, returned nothing, or was parsed wrong.

### Added
Three-strategy detection in `SchemaMetadataService`:
1. `information_schema.TABLE_CONSTRAINTS` where `CONSTRAINT_TYPE='CHECK'` — the standard MariaDB view.
2. `information_schema.CHECK_CONSTRAINTS` joined with `TABLE_CONSTRAINTS` — MariaDB 10.2+, sometimes exposes constraints the first query misses.
3. `SHOW CREATE TABLE <t>` per large-text-column table — parses the DDL for `CHECK (`. Slower but works when information_schema is restricted or the CHECK is declared in a way the metadata views don't expose (which was apparently the case here).

Each strategy is only invoked if the previous returned null or empty. Result is logged as `"Auto-detected CSV-only tables via <strategy>: [...]"` so it's obvious which strategy found the tables.

### Improved logging
- Every info-schema query now logs its raw response body when it returns zero rows — makes it obvious whether the query was silently rejected, returned an empty result, or was parsed wrong.
- Zero-result case now warns explicitly: `"...may mean the DB truly has no CHECK constraints, OR the query was silently rejected..."`.

### Fallback behavior unchanged
Priority is still auto-detect → `profile.csvOnlyTables` → built-in default. If all three auto-detection strategies fail, the profile override or built-in default catches CASE_DATUM.

---

## [0.1.0-SNAPSHOT] — Auto-detect CSV-only tables from information_schema (no more hardcode) — 2026-08-31

### Added
- **`SchemaMetadataService`** — queries `information_schema.TABLE_CONSTRAINTS` on the target env via the existing `executeSQLs` endpoint to find every table with a CHECK constraint. Intersects that set with tables that have a large-text column (`text`/`clob`/`longtext`/`blob` from the dataModel). The intersection is exactly the set of tables that MUST use CSV import — a CHECK like `json_valid(col)` would reject the partial-content intermediate states our SQL split-CONCAT-UPDATE writes between chunks. No user configuration required — schema-driven.
- Runs once per import at the start of `execute()`. Result logged: `"CSV-only tables for this run = [...]"` so the user can verify what got picked up.

### Changed
- `AppSqlExecutorService` routing now uses the auto-detected set. Priority:
  1. Auto-detection from target DB's information_schema (schema-driven — this is the answer for any project without configuration).
  2. `profile.csvOnlyTables` if set (manual override — only matters when auto-detection fails, e.g. `information_schema` blocked).
  3. Built-in `["CASE_DATUM"]` (last-resort compatibility fallback for CaseManager on locked-down envs).

### Result
DevBridge now handles a new project's schema **without any Java or profile changes** — as long as the target DB allows `SELECT` on `information_schema.TABLE_CONSTRAINTS` (standard read-only privilege that every DB user has by default in MariaDB/MySQL). The user just points DevBridge at their project's dataModel and env URLs; routing figures itself out.

### Fallback design
If `information_schema` access is blocked (rare — some hardened deploys restrict it), the tool logs a warning and falls back to the profile override or built-in default. The failure is visible in the log line `"CHECK-constraint detection returned HTTP {} … falling back"` so it's diagnosable.

---

## [0.1.0-SNAPSHOT] — Per-project CSV routing (lift CASE_DATUM hardcode into profile) — 2026-08-31

### Changed
- `CSV_ONLY_TABLES` was a hardcoded `Set.of("CASE_DATUM")`. Made project-configurable via new `ProjectProfile.csvOnlyTables` field. Renamed the built-in list to `DEFAULT_CSV_ONLY_TABLES` — used only as a fallback when the profile doesn't specify one. Empty list in the profile is respected as "no tables need CSV path" (all go SQL); null/missing falls back to the built-in default.

### Why
For any new project a user brings into DevBridge, the tables that require the CSV `/import` path (i.e. have a `json_valid`-style CHECK constraint on a LONGTEXT column that would reject partial content mid-chunk) will be different. The FAWB dataModel doesn't expose CHECK constraints, so this can't be auto-detected. Hardcoding it in Java means recompiling per project. Now the user just edits their profile JSON.

### Migration
- Existing profile JSON without `csvOnlyTables`: unchanged behaviour (falls back to `CASE_DATUM`).
- New profiles or explicit override: set `csvOnlyTables` in the profile JSON, e.g. `"csvOnlyTables": ["CASE_DATUM"]` (or `[]` if the target project has no such tables, or a different list for a different schema).

---

## [0.1.0-SNAPSHOT] — Include ALL entity columns in CSV (fix CASE_DATUM storage_key omission) — 2026-08-31

### Fixed
- **CASE_DATUM CSV import kept failing "row conversion for column IS_ACTIVE"** — after ruling out data-content issues (verified the dumped CSV was byte-clean, standard RFC 4180, header names correct, values `true`/`false` well-formed). Read the `CaseDatum.java` entity source directly and found a 12th field, `storageKey` (DB column `storage_key`, nullable, `length=1024`), that our CSV wasn't including. The "skip all-null columns" rule in `AppCsvImportService.importRows` was dropping `storage_key` because it's null for every source row. WaveMaker's CSV importer apparently requires the full entity column set in the CSV header; missing one column produced a misleading "row conversion for column IS_ACTIVE" (the adjacent column at the point of failure).
- Rule removed. Now every non-stripped column ships in the CSV. For null values the cell is empty, which WaveMaker maps to null on the target — the source's actual state.

### Why the previous rule existed
Comment on the old rule claimed "WaveMaker's CSV parser fails row conversion when a numeric/date column has an empty cell." Not verified empirically. AFFORDABILITY_ASSESSMENTS's CSV worked with the previous rule because it happened to have non-null values in every numeric/date column. If a specific column type ever surfaces the empty-cell conversion issue, `formatForCsv` already has a boolean-column fallback pattern (uses `defaultValue`) that can be extended to other types.

### Investigation notes for future me
When "row conversion failed for column X" is reported, don't just look at column X's value format. The parser may be reporting X because it's the adjacent column when the underlying issue (missing sibling column, count mismatch) surfaces. Read the entity class source and compare its field list to the CSV header.

---

## [0.1.0-SNAPSHOT] — CSV boolean format: true/false, not 1/0 — 2026-08-31

### Fixed
- **CASE_DATUM CSV import failing "row conversion for column IS_ACTIVE"** despite emitting well-formed `1`/`0` values in the CSV. Verified by reading the actual dumped CSV: header + 2 rows, `is_active` positioned correctly at column 11 with values `1` and `0`. WaveMaker's CSV importer rejects `1`/`0` for boolean columns — it requires `true`/`false`. Switched `formatForCsv` boolean handling accordingly. Also normalises non-Boolean source values (Number `1`/`0`, String `"true"`/`"false"`/`"1"`/`"0"`) to `true`/`false`, and uses the column's declared `defaultValue` when the source has null for a non-nullable boolean column.

### Notes
- The prior `1`/`0` choice was commented as "safer than true/false across parsers" — that assumption was wrong for WaveMaker's CSV importer specifically. Both formats are valid at the MariaDB level (TINYINT(1)); the format only matters to the CSV parser sitting in front of the DB.

---

## [0.1.0-SNAPSHOT] — Track remapped PK for shared-PK tables (CASE_CREDIT_APP fix) — 2026-08-31

### Fixed
- **Shared-PK relationships** (where a table's PK column is also a FK to another table's PK — e.g. CASE_CREDIT_APP.id is a `OneToOne` FK to CASE_HEADER.id) were failing post-insert verification with "expected 1 row(s) with our PKs to exist, but SELECT COUNT found only 0". Rollback would then correctly undo all landed rows (111 in this run). Root cause:
  - `ColumnPolicies` correctly excludes shared PK+FK columns from UUID regeneration (rule 4: `!col.foreignKey()`).
  - `ColumnPolicies` correctly builds an FK remap for the column via `graph.parents()`.
  - Executor loop applies the FK remap → writes the remapped value into `setPairs` → INSERT stores the correct (remapped) id.
  - **But** the loop tracked `newPk = sourcePk` at the top and only updated `newPk` in the UUID-regen branch. When the FK-remap branch fired, `newPk` was never updated → id-map recorded `source→source` instead of `source→mapped`. Downstream verification (`SELECT COUNT WHERE id IN (newPk)`) looked for the source id, which was never stored → 0 rows. Same bug in the CSV path (`AppCsvImportService.writeCsvRow`'s `assignedPk` variable).
- Both paths now update the tracked PK to the mapped value when the FK remap fires on the PK column.

### Why we hit it now
Every shared-PK table would have hit this — CASE_CREDIT_APP just happened to be the first one the topo-sort processed. Before Task #47 got reverted, we halted much earlier (on Task-#47-induced regressions) and never got this far. The post-insert `SELECT COUNT` verification (added a few entries above) is what surfaced the bug — silent data drift would have gone unnoticed otherwise.

---

## [0.1.0-SNAPSHOT] — Narrow CSV routing to CASE_DATUM only — 2026-08-31

### Changed
- Routing now sends a table to the CSV `/import` path ONLY if it's in a small explicit list (`CSV_ONLY_TABLES` in `AppSqlExecutorService`). Currently just `CASE_DATUM`. Every other table — including AFFORDABILITY_ASSESSMENTS, which has LONGTEXT columns but no `json_valid` CHECK constraint — goes through the SQL path (base64-shielded values, split-CONCAT-UPDATE for oversized rows).
- Why hardcoded? CHECK constraints aren't exposed in the FAWB `dataModel.json`, so we can't tell from data alone whether a LONGTEXT column has a `json_valid`-style CHECK that rejects partial content mid-chunk. Only CASE_DATUM is known to have this. If a new table surfaces, add it to `CSV_ONLY_TABLES`.

### Root cause of the "1-row-then-rollback" state
CSV `/import` has a structural visibility gap between its pooled connections — the same `conn=273` across three retries and 5+ seconds still can't see a row that `executeSQLs` confirms is committed. Every table on the CSV path is one more chance to hit that gap. AFFORDABILITY_ASSESSMENTS was on CSV only because it happens to have LONGTEXT — but its LONGTEXT columns are free-form text (no CHECK), so it never needed CSV. Moving it to SQL takes it off the visibility gap entirely.

### Diagnostic message tightened
`retryNote` in the CSV halt path no longer claims "target still doesn't see committed parent rows" as a general truth — it now points the user at `CSV_ONLY_TABLES` and asks whether this table actually needs CSV or can move to SQL.

---

## [0.1.0-SNAPSHOT] — Revert Task #47: LONGTEXT→CSV / else→SQL routing restored — 2026-08-31

### Fixed (regression from Task #47)
- Routing was changed a few entries above to send ALL non-identity tables through CSV import. Rationale was to unify the JPA session so child FK checks would see parent rows. **That hypothesis was wrong.** Empirically the `/import` endpoint has a structural visibility gap between its pooled connections (same `conn=273` across three retries spanning 5+ seconds still can't see the parent row that `executeSQLs` confirms is committed). The unified-CSV routing pulled CASE_HEADER off the working SQL path and onto the broken CSV path, so instead of 99 tables succeeding before AFFORDABILITY_ASSESSMENTS hit its FK error, the run halted on table #2.
- Routing reverted: LONGTEXT/CLOB/BLOB → CSV (unchanged from pre-#47); everything else → SQL. Baseline restored.

### Retained
- Post-CSV-import verification (`SELECT COUNT` via executeSQLs) is kept. It caused no regression and would have caught this bug earlier if it had been added FIRST.
- Retry-on-FK-error is kept. Only fires when the response body contains `foreign key constraint fails`. Harmless when unneeded; a hedge for tables that legitimately hit CSV path when the gap is time-bounded.
- URL and response body captured in `.meta.txt` next to the dumped CSV. Useful regardless of routing.

### Open (AFFORDABILITY_ASSESSMENTS specifically)
- Has LONGTEXT (`financials`, others) → routed to CSV under the LONGTEXT rule → hits the `/import` visibility gap on `case_header_id → CASE_HEADER (id)`.
- These LONGTEXT columns are free-form text (no `json_valid` CHECK constraint, unlike CASE_DATUM's `datum` column). Meaning the SQL path with base64-shielding + split-CONCAT-UPDATE would work — no need to route it through CSV in the first place.
- Follow-up: allow overriding CSV routing per-table (e.g. `profile.forceSqlTables`) so AFFORDABILITY_ASSESSMENTS can go SQL while CASE_DATUM stays CSV. Not doing that in this entry — reverting first, then thinking about it.

---

## [0.1.0-SNAPSHOT] — Retry CSV import on FK errors (cross-endpoint visibility gap) — 2026-08-31

### Root cause confirmed (this run)
Post-CSV verification for CASE_HEADER passed: `SELECT COUNT(*) WHERE id IN (…our-uuid…)` via `executeSQLs` returned 1 — meaning FAWB's `/import` DID persist our client-supplied UUID (`28e1936d-1cee-43ad-afef-0088f111c35f`), and the parent row IS committed and visible to `executeSQLs`. But the very next `/services/CaseManager/AffordabilityAssessments/import` on the same env (URL confirmed in the failure output) returned HTTP 500 with MariaDB's raw error `(conn=273) Cannot add or update a child row: a foreign key constraint fails … REFERENCES CASE_HEADER (id)`. So MariaDB, on the specific connection this `/import` landed on, does not see the parent row — same DB, different snapshot. Not a client-side bug; a structural visibility gap between `/import` connections.

### Added
- **Retry on FK constraint failure** — after any CSV import returns HTTP 500 with a `foreign key constraint fails` body, wait 1500 ms and retry; if that also fails, wait 4000 ms and retry once more. Only retries on FK errors (parsed from the error body via `extractFkParentTable`); any other failure fails fast. Log lines announce each retry with the referenced parent table for diagnosis.
- **Retry-exhaustion note** in the halt message. If we ran out of retries, the journal entry says so explicitly — the user knows the tool didn't just fail on the first attempt.

### Notes
- If retries fix it, we've confirmed the gap is time-bounded (connection pool eventually flushes; replica lag catches up) and total added delay is ~5.5s worst case per failing table.
- If retries don't fix it, the gap is structural — the `/import` endpoint is consistently talking to a backend that can't see rows freshly written from another endpoint. That would need FAWB-side pool/isolation config to resolve, which the user can't do from the tool side.

---

## [0.1.0-SNAPSHOT] — Post-CSV verification + surface URL in failure — 2026-08-31

### Added
- **Post-CSV-import verification** — after every successful CSV import, run `SELECT COUNT(*) FROM <table> WHERE <pk> IN (…our-uuids…)` via `executeSQLs` to confirm FAWB actually stored the ids we generated client-side. If the count is lower than expected, FAWB's `importData(file)` ignored our id column (Hibernate generated fresh UUIDs), our id-map is stale, and downstream child FKs would fail with an opaque MariaDB error. Halting with a clear message tells the user which table needs a different code path.
- **URL in CSV failure message** — the failing URL is now included in both the journal entry and the on-screen error text, so the user doesn't have to grep the console log to see what path 404'd or 500'd. Also written to a sibling `.meta.txt` next to the dumped CSV: URL, status, and response body.

### Why this matters
After routing all tables through CSV (previous entry), AFFORDABILITY_ASSESSMENTS still fails FK constraint `case_header_id → CASE_HEADER (id)` even though the parent CSV import returned 200 OK. Two possible causes: (a) FAWB replaced our id, (b) true DB-level visibility gap. This verification splits the two hypotheses cleanly — if the SELECT COUNT comes back low, it's (a); if it comes back matching, it's (b) and we need a different mitigation (delay, retry, or read-your-writes pattern).

---

## [0.1.0-SNAPSHOT] — Route ALL non-identity tables through CSV import (unified JPA session) — 2026-08-31

### Root cause discovered
Post-INSERT verification (`SELECT COUNT(*) WHERE pk IN (…)`) via `executeSQLs` returned `n:1` for CASE_HEADER 300ms after the INSERT. 1.2s later the CSV import for the child AFFORDABILITY_ASSESSMENTS failed FK constraint `case_header_id REFERENCES CASE_HEADER (id)` for that same PK. Same physical DB (`CaseManagerJ17__1` — confirmed in `development.properties`). Verdict: JPA session/transaction visibility mismatch between `executeSQLs` (JdbcTemplate on one connection pool / transaction manager) and `/services/{db}/{Entity}/import` (Hibernate JPA on another). Parent rows committed via SQL weren't visible to the child CSV import's JPA session at read time.

### Changed
- **Executor routing** — every non-identity, non-sequence table now goes through CSV import, not just tables with LONGTEXT columns. Parent and child rows now share the same JPA session mechanism, so child CSV imports see committed parent rows.
- **Identity/sequence PK tables** still use the SQL path — they need `LAST_INSERT_ID()` after each INSERT to populate the id-map before children look up FKs. CSV import can't return per-row assigned IDs reliably.

### Notes / trade-offs
- Loses the pre-clean `DELETE ... WHERE pk = X` step that assigned-PK tables had before their INSERT. Re-imports of natural-key (non-UUID) tables could hit UNIQUE-constraint collisions. Not an issue for CaseManager tables (all UUID PKs, which get regenerated on every import so collisions can't happen). Revisit if a table with natural PKs surfaces.
- Loses the split-INSERT + CONCAT-UPDATE path for URL-oversized rows. Since CSV import goes multipart, URL length is no longer a constraint — the whole split path is dead code for now. Left in place until we're sure the unified CSV route holds up across all tables; will remove in a follow-up.
- If the fix doesn't hold and we hit CSV-specific failures on tables that were fine on SQL, the SQL path is still available in `executeAssignedTable(...)` — flip the routing back per-table.

---

## [0.1.0-SNAPSHOT] — Fix snake_case vs camelCase column lookup — 2026-08-31

### Fixed
- **CSV had empty cells for audit columns** (`created_by`, `created_on`, `updated_by`, `updated_on`, `version`, `liabilities_adjustment_amount`) despite source rows having values. Confirmed by inspecting the dumped CSV: last 6 fields were `,,,,,,`. Root cause: `executeSQLs` returns row keys as **Hibernate field names (camelCase)** — e.g. `createdBy` — for tables like AFFORDABILITY_ASSESSMENTS, but our lookup was asking for `created_by` (snake_case). Case-insensitive comparison of `createdby` vs `created_by` fails on the underscore. Added snake→camel fallback to all four lookup helpers (`AppCsvImportService`, `AppSqlExecutorService`, `AppRestInsertService`, `AppSqlPlanService`). For every column we now try: exact match → case-insensitive match → snake→camel conversion + exact → snake→camel + case-insensitive.

### Notes
- Explains why other tables (CASE_HEADER, CASE_PARTY, etc.) worked previously — their column names happen to match between the SELECT response format and the DB naming. Tables where executeSQLs returns camelCase keys were silently losing their audit column values.

---

## [0.1.0-SNAPSHOT] — Fix CSV numeric formatting; dump failing CSV to disk — 2026-08-31

### Fixed
- **CSV import failing row conversion on DECIMAL columns** (e.g. `AFFORDABILITY_ASSESSMENTS.LIABILITIES_ADJUSTMENT_AMOUNT`, javaType `double`). Java's `String.valueOf(double)` emits scientific notation (`1.98E9`) for large or small values, which WaveMaker's DECIMAL parser rejects. `formatForCsv` now routes Double/Float through `BigDecimal.toPlainString()` for plain-decimal output.

### Added
- CSV import failures now dump the exact CSV file DevBridge sent to `~/.devbridge/imports/<profileId>/failed-csv/failed-<table>-<timestamp>.csv`. Path is surfaced in the journal's failure message. Makes remaining CSV formatting issues quick to diagnose without re-running.

---

## [0.1.0-SNAPSHOT] — Route LONGTEXT tables through CSV import (bypasses XSS filter) — 2026-08-31

### Root cause discovered
Reading the FAWB codebase — specifically `CaseDatumController.java` — revealed that WaveMaker's XSS filter runs on the standard `POST /services/{db}/{Entity}` create endpoint. It HTML-encodes `"`, `<`, `>`, `&` in the request body before JPA processes it. This corrupts JSON content and breaks `json_valid` CHECK constraints on the target. It also explains why source data had HTML entities encoded 19 layers deep — every save through this endpoint added another layer cumulatively.

**Only some endpoints have `@XssDisable`**: search, filter, export, count, aggregations, and — critically — **`/import`** (the CSV bulk import endpoint). The regular `POST /` create endpoint does NOT have it. Proven experimentally: a POST with `datum: "1"` (JSON number, no special chars for XSS to touch) succeeded, while the same request with `datum: "{\"test\":\"small\"}"` failed the CHECK.

### Added
- **`AppCsvImportService`** — builds a CSV file from source rows (applying policies: strip identity/database-defined columns, remap FKs via id-map, regenerate assigned-UUID PKs, HTML-repair large-text values, format dates as `yyyy-MM-dd HH:mm:ss`, booleans as `1`/`0`) and POSTs it multipart to `POST /services/{db}/{Entity}/import`.
- **`FawbClient.postMultipart(...)`** — hand-rolled `multipart/form-data` with explicit `Content-Type: text/csv` on the file part. Java's built-in HttpClient has no multipart helper; we build the byte array manually with a UUID boundary. Explicit part content-type is critical — FAWB's importer rejects `application/octet-stream` (curl/Postman's default when they can't infer).
- **Executor routing:** tables with any `text` / `clob` / `longtext` / `blob` column now route to CSV import instead of REST create. One HTTP POST per table (all rows in one CSV); rows tracked in journal individually so rollback works as before.

### Removed / superseded
- REST-create path for large-text tables (`executeTableViaRest`) is no longer reached in practice. Left in place for now — may remove in a follow-up cleanup. The pre-flight local JSON check and payload-dump-on-failure infra remain and would help if we ever route back to REST for a specific table.

### Notes
- Small/narrow tables (no large-text columns) still use the batched SQL path — fast, unchanged.
- CSV import runs through JPA (minus the XSS filter), so JPA-level validation still fires. This should be fine for content that already exists in source (source's INSERT passed the same validation).
- CSV import operates transactionally per-file: either all rows in the batch land or none do. Rollback semantics unchanged — journal DELETE-by-PK works regardless of how the row got in.

---

## [0.1.0-SNAPSHOT] — Repair HTML-encoded JSON in source large-text columns — 2026-08-31

### Fixed
- **`AFFORDABILITY_ASSESSMENTS.financials`, `incomes`, `expenses` (and similar LONGTEXT/CLOB/BLOB columns) failing `json_valid` on target** even though source stores them. Root cause found by pre-flight local JSON parse: the source values in some FAWB projects have been HTML-entity-encoded **multiple times** (19+ layers observed) by ancestor applications — likely each save doing another `htmlEscape(existing_value)` inadvertently. What SELECT returns from source is `{&amp;amp;amp;...quot;expenseBase&amp;...` instead of `{"expenseBase":...}`, and MariaDB's `json_valid` on target correctly rejects it as not-JSON.
- `SqlBuilder.repairHtmlEncodedJson(s)` — new utility. If a string isn't already valid JSON, iteratively HTML-decodes (handles `&amp;`, `&quot;`, `&lt;`, `&gt;`, `&apos;`, `&nbsp;`, numeric entities like `&#38;` and `&#x26;`) until it either parses as JSON or no more entities remain. Max 50 iterations as a safety cap.
- Applied in BOTH pipeline paths:
  - SQL path (via `SqlBuilder.convertForInsert`) — for tables that go through batched INSERT
  - REST path (via `AppRestInsertService.formatForRest`) — for tables with large-text columns
- No-op for values that are already valid JSON or contain no HTML entities — normal data flows unchanged.

### Pre-flight validation
- Added a local JSON parse check on outgoing large-text values in `AppRestInsertService.insert(...)`. If our side rejects the value as invalid JSON (after repair attempts), we halt with a specific error naming the column and showing the first 200 chars — no wasted HTTP call, no cryptic downstream 500.

### Notes
- The 19-layer encoding is a data-quality issue in source; DevBridge repairs on-the-fly rather than requiring a source cleanup pass first. Target ends up with clean JSON regardless of source's encoding history.

---

## [0.1.0-SNAPSHOT] — Defensive JSON serialisation on REST payload — 2026-08-31

### Fixed
- **REST POST for CASE_DATUM failed CHECK (`json_valid(datum)`)** because our fetch step's `mapper.convertValue(JsonNode, Map<String, Object>)` sometimes auto-parses a TEXT column whose stored content is a valid JSON document into a `LinkedHashMap` (nested structured object), rather than keeping it as a raw String. The subsequent `setJson` used `value.toString()`, which for a Java Map produces Java's default `{key=value}` shape (not JSON) — MariaDB rightly rejected that as invalid JSON.
- `setJson` now detects `Map` / `List` values and re-serialises them through Jackson (`writeValueAsString`) to produce proper JSON text before embedding in the POST body. String values pass through unchanged. Confirmed by user's manual test where posting `datum` as an unquoted object triggered the exact same JPA error our tool was hitting.

### Added
- REST insert failures now log the outgoing payload preview (first 600 chars) at WARN level. Makes diagnostics one log line away instead of a trace-through.

### Notes
- No routing or rollback changes — this is a serialisation bug fix inside the REST path.

---

## [0.1.0-SNAPSHOT] — REST CRUD fallback for large-text tables — 2026-08-31

### Rationale
Some tables (CASE_DATUM, AFFORDABILITY_ASSESSMENTS, etc.) have LONGTEXT columns holding multi-KB JSON. Combined with strict CHECK constraints (JSON_VALID), these rows can't be transported via `executeSQLs`:
- Single-INSERT: base64-shielded body still exceeds nginx's ~8 KB URL limit.
- Split INSERT + CONCAT UPDATEs: partial JSON chunks fail the JSON_VALID CHECK.
- POST to `executeSQLs`: rejected — endpoint is GET-only in WaveMaker.

Testing confirmed **per-entity CRUD REST endpoints (`POST /services/{db}/{Entity}`)** work, accept JSON bodies of any size (limited by `client_max_body_size`, typically MB), and preserve supplied timestamps (no JPA `@PrePersist` override observed).

### Added
- **`AppRestInsertService`** — POSTs a row as JSON to the per-entity CRUD endpoint. Builds payload keyed by `fieldName` (camelCase JPA form), emits FK relations as `{fieldName: {id: "<remapped-uuid>"}}`, converts epoch-ms DATETIME values to ISO 8601 strings, regenerates assigned-UUID PKs client-side. Also exposes `deleteByPk(...)` for symmetric REST rollback if needed.
- **`FawbClient.delete(...)`** — HTTP DELETE with bearer auth, used by REST rollback.
- **Executor routing:** tables with ANY `text` / `clob` / `longtext` / `blob` column now go through `executeTableViaRest(...)` instead of the SQL path. One HTTP POST per row (no batching — REST endpoint is per-row anyway). Journal marks these entries with `targetUrl = "REST POST"` for visibility.

### Notes
- SQL path stays the default for tables without large-text columns — fast batched INSERTs, unchanged.
- Rollback is unified: SQL `DELETE FROM T WHERE pk = X` works on rows regardless of how they were inserted (JPA doesn't change the physical row shape).
- Plan preview still shows sample INSERT SQL for all tables. For REST-routed tables, the actual execution uses POST — the SQL preview is informational only. Might get a UI clarification in a follow-up.

---

## [0.1.0-SNAPSHOT] — Try single-INSERT with base64 before splitting; NULL initial in split — 2026-08-31

### Fixed
- **CHECK constraint failures in split mode**, e.g. `CASE_DATUM.datum` with a `JSON_VALID(datum)` CHECK: split mode was seeding the row with `''` (empty string), which isn't valid JSON, so the initial INSERT failed the CHECK. Two-part fix:
  1. **Batch loop now base64-shields large-text values inline for the size check**. If the shielded row fits under URL budget, it's sent as a single INSERT — MariaDB decodes `FROM_BASE64(...)` into the real content, so any CHECK constraints see the actual value, not a placeholder. `CASE_DATUM` and similar small-large-text rows never hit the split path anymore.
  2. **When we still need split mode** (row exceeds URL budget even shielded — e.g. `AFFORDABILITY_ASSESSMENTS` with multi-KB JSON in three columns), the initial INSERT now uses **`NULL`** as the placeholder for large-text columns whose `nullable=true` (falls back to `''` otherwise). NULL is safe for most CHECK constraints; the first CONCAT UPDATE uses plain `SET col = FROM_BASE64(...)` which fully replaces NULL, so no `CONCAT(NULL, ...)` trap.

### Notes
- Truly split-required columns with a strict CHECK constraint (e.g., a huge column with `JSON_VALID`) still can't be transported — a partial UPDATE chunk isn't valid JSON. If you hit that, we'd need to bypass the CHECK during transport or find a different DB access path. Uncommon in practice; flag with the specific table/column and we'll address.
- Rollback continues to work as before: 93 rows deleted in the last run's rollback is proof that path is solid.

---

## [0.1.0-SNAPSHOT] — Fix split-mode regression + route all large-text rows through split — 2026-08-31

### Fixed
- **`HTTP 414` on split-INSERT** — the previous `shieldStringValue` fix was accidentally wrapping LONGTEXT values as one giant `FROM_BASE64('...')` blob during `convertForInsert`. Split mode's detector then didn't recognise the shielded value as a large-text String (`v instanceof String` was false, since v was a `Raw` sentinel), so the huge base64 blob stayed in the "small" INSERT and blew past the URL limit again.
- **Fix:** `convertForInsert` now skips shielding for large-text-typed columns (`text` / `clob` / `longtext` / `blob`). Split mode is the exclusive path for those — it handles base64 encoding per chunk via `updateSetBase64` / `updateConcatSetBase64`, which is where it belongs.
- **Additional guard:** the batch loop now routes to split mode when a row has ANY large-text column with non-empty content, not just when the overall row exceeds URL budget. This catches small large-text values that happen to contain URL-reserved chars (e.g. a short TEXT column with `hi & bye` in it) — even if the row itself fits under 6 KB, that `&` would still break URL decoding on the FAWB side. Split mode always transports large-text via base64 chunks, so no raw `&` ever reaches the URL.

### Notes on rollback
Rollback for this run reported `1 row(s) deleted, 0 failed` — that's the CASE_HEADER we successfully inserted before AFFORDABILITY_ASSESSMENTS failed. Rollback is working. Any residual data you may still be seeing in target is from **previous runs before rollback existed** — those runs left partial inserts that we can't retroactively clean up. New runs starting from a clean state (or after a manual cleanup pass) will roll back cleanly on any failure.

---

## [0.1.0-SNAPSHOT] — Base64-shield URL-fragile string values — 2026-08-31

### Fixed
- **`bad SQL grammar` on rows whose data contains `&`** (JSON blobs in `financials`, `incomes`, `expenses`, or any name/description with an ampersand). Root cause: something in the FAWB proxy stack URL-decodes the `dbCommands` query parameter more than once. Our first-pass URL-encoding of `&` → `%26` gets decoded to `&` server-side; a second pass then interprets that decoded `&` as a query-parameter separator and truncates the SQL. MariaDB then sees a malformed statement (unterminated string) and returns "bad SQL grammar."
- **Fix:** for any string value carrying URL-reserved chars (`&`, `%`, `+`, `#`), the executor now emits it as `FROM_BASE64('<base64>')` instead of a plain quoted string. Base64 output is `[A-Za-z0-9+/=]` — none of those survive a double URL-decode as reserved chars in a way that breaks the SQL. MariaDB decodes back to the original bytes server-side.
- Same shield applies to LONGTEXT split-mode chunks (via new `SqlBuilder.updateSetBase64` / `updateConcatSetBase64`) and to any string in regular batched INSERTs. Numeric, temporal, and boolean values pass through unchanged; strings without any reserved char pass through unchanged for readability.

### Added
- `SqlBuilder.shieldStringValue(v)` — inspects a value and returns a `Raw` `FROM_BASE64('...')` sentinel if it needs shielding.
- `SqlBuilder.updateSetBase64` / `updateConcatSetBase64` — split-mode UPDATE variants used unconditionally (chunks are always base64-transported since we know they'll be long).

### Notes
- Plan preview shows shielded values as `FROM_BASE64('...')` inline — an honest representation of what will be sent. Slightly less readable, but only for values with reserved chars.
- Base64 encoding adds ~33% size, but base64 chars URL-encode more tightly than JSON with quotes/braces (~1.4x vs 2.5x for direct-encoded JSON), so total encoded URL length is often SHORTER with the shield than without.

---

## [0.1.0-SNAPSHOT] — Complete rollback coverage — 2026-08-31

### Fixed
- **Rollback missed partial split-mode rows.** A row that inserted successfully but then failed on a follow-up `CONCAT UPDATE` (during LONGTEXT reassembly) was journaled as `result=failed` with the target PK still set. The old filter `result == "created"` skipped it, leaving a partially-written row in target. Filter is now **`targetId != null`** — any entry with a landed PK, regardless of result status, is a rollback candidate.
- **Unexpected exceptions bypassed rollback.** The outer catch (for anything that wasn't `HaltException` — NPE, IO, JSON parse errors, etc.) just marked the run failed and returned. Now routed through the same rollback path so target ends up clean regardless of what threw.

### Added
- `rollbackAndAnnotate(...)` — shared helper used by both catch branches. Sets journal status to `failed-rolled-back` when rollback removed everything cleanly, `failed` otherwise. Prepends the original failure reason to the rollback summary in `errorMessage`.
- `RollbackOutcome.nothingToRevert()` — distinguishes "clean rollback: 0 rows" from "rollback failed on some rows".
- Frontend toast now distinguishes three outcomes: `success` (green), `failed-rolled-back` (info — target clean), `failed` (red — target may still have some inserted rows).

### Known limitation (still)
- Pre-clean DELETEs of existing target rows (from `needsPreClean=true` for passed-through assigned PKs) are not restored on rollback — that would require snapshotting the deleted row's data first. If you re-import a case whose PK already exists in target, the existing row is removed even if the subsequent import fails. Rollback restores state relative to what was ADDED, not what was DELETED as pre-clean.

---

## [0.1.0-SNAPSHOT] — Split oversized rows across INSERT + CONCAT UPDATEs — 2026-08-31

### Fixed
- **`HTTP 414` on rows with `LONGTEXT` / `TEXT` / `CLOB` columns** (e.g. `AFFORDABILITY_ASSESSMENTS.financials`, `.incomes`, `.expenses` — each up to 2 GB in the schema, typically holding multi-KB JSON blobs). A single such row's INSERT can exceed 15 KB URL-encoded — no batch size fits it. Since FAWB's `executeSQLs` endpoint rejects POST (`"Request method 'POST' is not supported"`), we can't move the payload to a request body. Fix: **oversized rows are now split into an INSERT (with large columns set to `''`) + one or more `UPDATE ... SET col = CONCAT(col, 'chunk')` calls per large column.** Chunks are 1500 raw chars each (~4500 encoded), safely under the 6000-byte URL budget. Rows that fit within one INSERT still batch as before.

### Added
- `SqlBuilder.updateSet(...)` and `SqlBuilder.updateConcatSet(...)` — one-column UPDATE and CONCAT-append UPDATE.
- `AppSqlExecutorService.executeOversizedRow(...)` — handles the split path. Journal shows a single `created` entry per row with message like `"OK (split — INSERT + 3 column(s) chunked as CONCAT UPDATEs)"`. Failure on any CONCAT UPDATE halts and rollback DELETEs the row (via existing rollback path).

### Notes
- Detection is purely by column type + row size — a row containing a `text` column stays in the fast-path unless the whole INSERT would overflow the URL.
- Rollback for split rows is unchanged: journal records the row's target PK; a single DELETE clears the whole row (columns and all).
- If a single CONCAT UPDATE fails partway through a column (e.g. chunk 3 of 5), we still hold a target row with a partial column value. Rollback DELETEs it, so the target ends up clean.

---

## [0.1.0-SNAPSHOT] — Fix URL-length undercount by 2B/space — 2026-08-31

### Fixed
- Batching was still overflowing nginx's 8 KB URL limit on wide tables. Root cause: Java's `URLEncoder.encode()` returns spaces as `+` (form-data convention), but `SqlService` replaces those with `%20` (strict URL query-string form) before sending. The batcher's measurement wasn't applying the same replace, so every space was undercounted by 2 bytes — a 10-row batch of INSERTs typically has 500+ spaces, so we were 1 KB over the real on-wire size. Length measurement now mirrors `SqlService`'s exact transformation. Cap also lowered from 6500 to 6000 encoded bytes for a bit more headroom.

---

## [0.1.0-SNAPSHOT] — Batch by real URL-encoded length — 2026-08-30

### Fixed
- **`HTTP 414 Request-URI Too Large` still hitting** wide tables like AFFORDABILITY_ASSESSMENTS despite the earlier raw-char batching. URL encoding of typical SQL (spaces → `%20`, quotes → `%27`, backticks → `%60`, commas → `%2C`) adds 40–100% overhead depending on content, so 4000 raw chars was sometimes 8000+ encoded. Batching now measures **actual URL-encoded byte length** via `URLEncoder.encode` and enforces `MAX_ENCODED_SQL_LENGTH = 6500`. Same fix applied to rollback `DELETE ... IN (...)` batching.

---

## [0.1.0-SNAPSHOT] — Auto-rollback on failure + complete envelope processing — 2026-08-30

### Fixed
- **Partial imports left behind on failure.** `executeSQLs` runs each statement in its own auto-commit transaction, so a mid-batch failure left preceding rows in target. The executor now runs an **automatic rollback** on any halt: walks the journal in reverse (child rows first, FK-safe), groups consecutive same-table entries, and emits batched `DELETE FROM T WHERE pk IN (...)` statements against target. Best-effort — a failing DELETE is logged and rollback continues on the rest. Journal status becomes `failed-rolled-back` when everything was undone, `failed` when some rows couldn't be reverted (see `errorMessage` for detail).
- **Journal under-recorded committed rows.** `executeSQLs` continues past a failed statement in a batch, so if row 3 failed but rows 4 and 5 succeeded, our loop was throwing at row 3 without processing rows 4–5's envelopes — those got committed to target invisibly and would be missed by rollback. `recordChunk` now processes ALL envelopes first, adds every outcome to the journal, then throws if any failed. Rollback now sees a complete picture.

### Notes
- If a rollback DELETE itself fails (e.g. FK constraint because target has *other* rows referencing what we're deleting), it's logged in the journal's `errorMessage` and the run is marked `failed` rather than `failed-rolled-back`. Rows already deleted stay deleted — no attempt to "un-rollback."
- The DELETE-then-INSERT pre-clean for passed-through PKs still runs; this rollback is an outer safety net for anything the pre-clean doesn't handle (unique constraint collisions on non-PK columns, transient DB errors, etc.).

---

## [0.1.0-SNAPSHOT] — SQL literal formatting + surface real DB errors — 2026-08-30

### Fixed
- **Scientific-notation in `FROM_UNIXTIME`.** `Double.toString(1787928269.775)` returns `1.787928269775E9`, which MariaDB refuses to parse inside `FROM_UNIXTIME(...)`. Now built manually as `<seconds>.<millis>` string (e.g. `1787928269.775`). Also fixed general Double/Float literal formatting via `BigDecimal.toPlainString()` — no more scientific notation anywhere in emitted SQL.
- **Error messages were being truncated to just the failing SQL.** Spring wraps DB errors as `"StatementCallback; SQL [<full failing SQL>]; <real reason>"`, and the SQL alone was blowing past the 500-char cap so the "real reason" never made it to the journal. New `extractDbError()` strips out the SQL bracket and keeps the actual DB message; overall cap raised to 2000 chars. You'll now see MariaDB's real message (e.g. "Cannot add or update a child row: a foreign key constraint fails", "Data too long for column 'X'", "Duplicate entry '...' for key 'PRIMARY'").

---

## [0.1.0-SNAPSHOT] — Dynamic batch sizing (URL length aware) — 2026-08-30

### Fixed
- **`HTTP 414 Request-URI Too Large`** on wide tables like `CASE_ACTIVITY`. `executeSQLs` uses a GET with SQL in the `?dbCommands=` query string; nginx's default URL limit is ~8 KB and URL-encoding roughly doubles length. A fixed batch of 50 rows blew straight past that on any table with substantial column data.
- Executor now batches **dynamically by URL length**, not row count. Raw SQL budget per call is capped at 4000 chars (comfortable margin under nginx's limit after encoding + query-string overhead). `MAX_BATCH_SIZE = 100` remains as an upper bound for tiny-row tables. Rows are still emitted parent-first in topological order.

---

## [0.1.0-SNAPSHOT] — Fix DATETIME inserts + facade-key composite resolution — 2026-08-30

### Fixed
- **DATETIME columns rejected on INSERT.** FAWB's `executeSQLs` serialises DATETIME values as milliseconds-since-epoch (numeric), which MariaDB can't cast back to DATETIME on insertion. Result: `INSERT INTO CASE_HEADER SET created_on = 1787928269775` fails with "bad SQL grammar". Fix: `SqlBuilder.convertForInsert(v, col)` wraps numeric values for temporal columns (`datetime`, `timestamp`, `date`) as `FROM_UNIXTIME(sec)`. `SqlBuilder.Raw` sentinel added so raw SQL expressions can be inlined without quoting. Applied in both the executor and the plan preview (so the sample INSERT reflects reality).
- **Facade cross-check saying everything is "unmapped".** For CaseManager-style schemas where children are named `<Parent><Child>` (e.g. facade key `device` → entity `CaseDevice`; `statusHistories` → `CaseStatusHistory`), the slim resolver's exact-match + plural strip wasn't finding them. Added an **"endsWith unique match"** pass to `FacadeKeyResolver` — accepts only when exactly one entity ends with the (singularised) key, so we never silently mis-guess. Also handles Latin plurals (`data` → `datum`, `criteria` → `criterion`, `media` → `medium`, `phenomena` → `phenomenon`).

### Notes
- If the resolver still marks a key as `unmapped`, either two entities end with the same word (ambiguous — add a `facadeKeyOverrides` entry on the profile), or the facade collection genuinely doesn't correspond to a table (rare).

---

## [0.1.0-SNAPSHOT] — Module 2 rearchitected as SQL-driven FK-graph walk — 2026-08-30

### Rationale
Previous approach fetched the app as a nested JSON tree from FAWB's composite facade endpoint, then reverse-engineered which entities/tables each JSON key mapped to. That process required 4-5 heuristic layers (VO stripping, camelCase trailing word, singular/plural, composite naming, manual overrides) and still surfaced many "unresolved key" warnings for legitimate data. Reads and writes were asymmetric — reads worked because FAWB's facade knew what to include; writes had to guess. Now that we confirmed the `executeSQLs` endpoint accepts INSERT/UPDATE/DELETE with the same bearer auth used for reads, we can drive both ends of the import from the same authoritative source: the schema (FK graph in the dataModel).

### Added
- **`FkGraph`** — precomputed FK adjacency from `DataModel.relations` (ManyToOne / OneToOne). Provides `children(parent)`, `parents(child)`, and Kahn's-algorithm topological sort scoped to a subset of tables. Handles self-references and reports cycles.
- **`SqlBuilder`** — emits `INSERT INTO T SET col=val, ...`, scoped `SELECT WHERE fk IN (...)`, and `DELETE WHERE pk = v`. Uses `SET` syntax (not `VALUES`) to sidestep FAWB's SQL wrapper collision on reserved column names like `value`. All identifiers backticked; string literals doubled-quote-escaped.
- **`AppSqlFetchService`** — BFS-walks the FK graph from the profile's `rootTableName` filtered by the source app id, issuing scoped SELECTs per child table via `SqlService`. Parses envelope-array responses into row maps. Skips reference tables. Result: `Map<TableName, List<Row>>` — the complete DB slice for one app.
- **`AppSqlPlanService`** — topologically sorts the gathered tables, computes per-column policies (strip identity/sequence PKs, regenerate assigned-UUID PKs, mark FKs for id-map remap, strip `insertable=false` / `database-defined`). Produces a table-level `ImportPlan` with row counts, PK strategies, FK remaps, and a sample INSERT preview per table.
- **`AppSqlExecutorService`** — writes the rows to the target env. Two code paths:
  - **Client-known PK** (assigned, including regenerated UUIDs) → batch multiple `INSERT ... SET` statements per HTTP call (batch size 50). Fills id-map up-front so children can immediately remap their FKs.
  - **Server-assigned PK** (identity, sequence) → one INSERT per call + follow-up `SELECT LAST_INSERT_ID()`. Rare — CaseManager's schema has only `SEQUENCE` in this category.
- **Delete-then-insert** for assigned passed-through PKs — makes re-imports idempotent (source PK collision no longer halts the run).
- **Profile fields:** `rootTableName` (e.g. `CASE_HEADER`), `rootPkFilterColumn` (optional; defaults to root's PK), `facadeKeyOverrides` (used only by the future facade cross-check).

### Changed
- `AppImportController` — endpoints (`/api/apps/import-plan`, `/api/apps/execute`) keep their URLs but now take `{appId, sourceEnv, targetEnv, confirm, confirmToken}` only. No more `tree` in the request body — the SQL fetch always drives the plan.
- `ImportPlan` — reshaped from step-per-row (JSON-walker era) to step-per-table with `TableStep {order, tableName, rowCount, pkStrategy, pkColumns, strippedColumns, uuidPkColumns, fkColumns, sampleInsertSql, notes}`. Added `FacadeCrossCheck` list (populated by the future validator).
- **Frontend Import App view** — dropped the JSON tree editor and "fetch vs edit" flow. New UI: source/target env selectors + app ID input + Preview + Execute. Plan preview shows tables in insert order with row counts + sample INSERTs.
- **Frontend profile form** — replaced `rootEntityName` with `rootTableName` and `rootPkFilterColumn`; renamed `jsonKeyOverrides` textarea to `facadeKeyOverrides`. Facade fields (`facadeServicePath`, `facadeEndpoint`, `facadeQueryParam`) now labelled as "optional — cross-check only".

### Removed
- **`ImportPlanService`** — replaced by `AppSqlPlanService`.
- **`ImportExecutorService`** — replaced by `AppSqlExecutorService`.
- **`RootEntityDetector`** — replaced by direct `rootTableName` lookup on the profile.
- **`/api/apps/fetch`** endpoint — no longer needed.
- **Profile: `rootEntityName`, `jsonKeyOverrides`** — dropped. Old profiles that had them will silently drop those fields on next save (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- Frontend `fetchApp()` helper — no longer imported anywhere.

### Advisory (cross-check) layer
- **`AppFacadeFetchService`** — slim facade fetcher, invoked only by `FacadeValidator`. Returns null (no exception) if facade config is missing on the profile.
- **`FacadeKeyResolver`** — maps facade JSON keys to physical table names using `facadeKeyOverrides` + singular/plural + VO/VOList strip. Used only for cross-check.
- **`FacadeValidator`** — compares facade collection counts against FK-walk row counts and emits `FacadeCrossCheck` entries: `match` / `count-mismatch` / `unmapped` / `table-not-walked`. Plan builder pulls these into `ImportPlan.facadeChecks`. Frontend renders them as a compact table in the plan preview. Advisory only — never blocks execute.

### Deferred
- **Rollback (5e)** — journal format unchanged; reverse-DELETE SQL emission is a straightforward follow-up.

### Migration notes
- Profiles need `rootTableName` set before Import App will work (e.g. `CASE_HEADER` for CaseManager, `APPLICATION` for Core). The UI blocks the import screen with a clear error until this is set.
- Old profiles with `rootEntityName` and `jsonKeyOverrides` won't error on load — those fields silently drop next save.

---

## [0.1.0-SNAPSHOT] — Per-env SQL DB name + surface backend error body — 2026-08-30

### Added
- **`sandboxSqlDbName` profile field.** Sandbox and design usually have different underlying DB names — e.g. sandbox is `CaseManagerJ17__1` while design is `usrzzifdsi3l`. Each env now has its own `?db=` value; both fall back to `dbServiceName` for older / same-name projects. Form label is "Sandbox SQL DB name (optional)"; the existing `sqlDbName` is now labelled "Design SQL DB name (optional)".
- SqlService picks the DB name per env: design → `sqlDbName || dbServiceName`; sandbox → `sandboxSqlDbName || dbServiceName`. No cross-pollination between the two — they're independent fields.

### Fixed
- **"Show request URL" toggle now appears on failure.** The generic `request()` wrapper in `api.js` was throwing on any non-2xx, which meant the backend's structured error body (containing `requestUrl` and FAWB's response body) never reached the sql-runner error branch. `executeSql` now uses raw `fetch()` so callers see the full `{success:false, status, requestUrl, error, body}` envelope. All other endpoints keep their throw-on-error behaviour unchanged.

---

## [0.1.0-SNAPSHOT] — Sandbox SQL unified with design (bearer, no cookies) — 2026-08-30

### Changed
- **Sandbox SQL now uses the same runtime `executeSQLs` endpoint as design.** A recent finding: sandbox exposes `GET {sandboxBaseUrl}/services/schema/executeSQLs?db=X&dbCommands=Y` and accepts the same `WM_AUTH_TOKEN` bearer as design. The Studio POST + session-cookie path was never necessary. Both envs now share one code path — the only difference is the base URL.
- `SqlService.execute()` collapsed from two branches (~200 lines) to a single method (~30 lines). Removed Studio pagination loop, multipart body builder, response-merger, `inferQueryType`, and the `STUDIO_PAGE_SIZE` / `STUDIO_MAX_PAGES` constants.
- `SqlController` auth-failure handling simplified: 401 → clear bearer + emit `token-expired`, same for both envs. No more dual-path (`studio-cookies` vs `bearer-token`) discriminator on the response.
- Frontend SQL Runner: response parser no longer switches on shape — the Studio `{results:{content:[…]}}` branch is gone. Only the runtime envelope-array shape remains. Sandbox capability check now just requires `sandboxBaseUrl` + a DB name (same rule as design).

### Removed
- **Profile fields:** `studioBaseUrl`, `studioProjectId`, `studioDbServiceName`. Old profiles that had them will silently drop those fields on next save (`@JsonIgnoreProperties(ignoreUnknown = true)` keeps them loadable in the meantime).
- **Backend files:** `com.devbridge.auth.StudioCookiesHolder`, `com.devbridge.api.StudioAuthController`.
- **`FawbClient.postStudioMultipart(...)`** and its `StudioCookiesHolder` dependency. The multipart builder / XSRF header wiring is gone. `FawbClient` is now bearer-only.
- **Frontend:** `/api/studio/*` client helpers, the Studio cookies paste modal (`#studio-cookies-modal`), the sidebar "Studio cookies not set" chip, the `studio-cookies-changed` / `studio-cookies-expired` / `open-studio-cookies-modal` events, and localStorage keys under `devbridge.studio-cookies.*`.
- Home-view "getting started" step about pasting Studio cookies for sandbox SQL — no longer applicable.

### Notes
- **Config impact:** profiles that previously had Studio fields will keep working (fields ignored on load, dropped on save). No migration script needed.
- Rollback if sandbox behaves differently in some future FAWB build: just re-introduce a per-env branch in `SqlService.execute()`. The Studio-cookie code is preserved in git history for that scenario.

---

## [0.1.0-SNAPSHOT] — Iteration 5d: import executor (first real writes) — 2026-08-13

### Added
- **`Delete App` module placeholder** in the left nav (`#/delete-app`). View is a stub — no logic yet. Sits between Bulk Import and Project Profiles.
- **`FawbClient.post(url, jsonBody, profileId)`** — POST wrapper with the same auth model as `get`. Content-Type set to `application/json`. Body length logged (never body content) so PII stays out of logs.
- **`ImportJournal` + `ImportJournalStore`** — journal record + persistence at `%USERPROFILE%\.devbridge\imports\<profileId>\<yyyyMMdd-HHmmss>-<jobId>.json`. Full overwrite after every step so a crash mid-run still leaves the truth on disk.
- **`ImportExecutorService`** — the walker that actually WRITES to the target env:
  - POST-only. Never PUT/PATCH/DELETE during import.
  - Reference tables skipped (defense-in-depth beyond the planner).
  - Halt on first failure — returns partial journal.
  - Halt if a non-reference FK cannot be resolved via the id-map (prevents dangling FKs).
  - Halt if the server response has no recognisable PK field (prevents cascading garbage IDs).
  - id-map maintained in-memory across the walk; children get their FKs remapped to the target env's newly-assigned IDs before they're POSTed.
- **`POST /api/apps/execute`** — hard-gated by `confirm: true` AND `confirmToken` matching `appId`. Any missing gate returns 400 with a clear message. Response is the final journal.
- **Frontend Execute button** on the plan summary card. Disabled if the plan has unresolved keys (safer default — force user to resolve first).
- **Confirmation modal** — appears on Execute click. Shows profile, source→target, target URL, appId, step count. Requires the user to **type the source appId** into an input before the confirm button unlocks. Big yellow warn banner at the top. Danger-red confirm button.
- **Execute result card** — status (success / failed / cancelled), rows-created counter, journal preview table (order, entity, source id, target id, HTTP status, result, message). Toast summarises the outcome.

### Safety invariants documented in code (`ImportExecutorService`)
1. POST-only. No UPDATE/DELETE during import.
2. Reference tables never touched.
3. Journal written before proceeding to next step.
4. `confirm=true` and `confirmToken=appId` required at the controller.
5. Halt on first error, unrecognisable response, or unresolvable non-reference FK.

### Notes
- Synchronous execution — long runs block the request. Async polling model can come as a follow-up if runs regularly exceed ~30 s.
- Rollback / undo lands in iteration 5e via the journal file already produced here.

---

## [0.1.0-SNAPSHOT] — Import plan: clearer FK remap explanation — 2026-08-13

### Changed
- Rewrote the note under each step's "FK remaps" section to clearly explain what `<pending: TableName.sourceId>` means: a placeholder that gets replaced at execute time with the NEW id the sandbox assigned when that parent was POSTed earlier in the run. Old text implied FKs passed through — misleading for non-reference tables.
- Added a note that the executor will halt if an FK target isn't in the plan and isn't a configured reference table (prevents writing rows with dangling FKs).
- Column label in the FK list changed from "source value" to "source id" — more accurate.

---

## [0.1.0-SNAPSHOT] — Global 401 handling + cleaner error messages — 2026-08-13

### Fixed
- **401 responses from any API endpoint** now automatically dispatch the `token-expired` event, which triggers the standard flow: sidebar indicator turns red, red toast appears, token modal auto-opens. Previously this only fired for `/api/sql/*` and `/api/profiles/*/test-connection`; the App Import endpoints silently dropped the signal.
- **Cleaner error messages**: when the backend returns a JSON body like `{"success":false,"error":"…"}`, the frontend now surfaces the `error` field alone rather than the full raw JSON. Non-JSON bodies fall back to `<status> <statusText>: <body>`.

---

## [0.1.0-SNAPSHOT] — Import App: env selectors + Preview as primary action — 2026-08-13

### Changed
- **Import App screen rework.** Preview is now the single entry point — no separate Fetch button needed.
  - New **Source → Target env selectors** at the top: Design ↔ Sandbox in either direction. Defaults to Design → Sandbox. Env options are disabled for whichever URL the active profile is missing.
  - **Tree JSON editor is always visible** and drives the flow: if empty when Preview is clicked, the backend fetches from the selected source env and populates it; if already populated (fetched or manually pasted), Preview plans against the current content.
  - **Clear tree** button resets the editor so the next Preview will fetch fresh.
  - Live JSON validation as you type; invalid JSON blocks Preview with a clear error.
  - Small "Fetched · app N" / "Edited" hint next to the editor header shows the current tree source.
- **Removed the separate Fetch button.** Its behavior is now folded into Preview.

### Added
- **Env-aware fetch.** `AppFetchService.fetch(profile, appId, env)` resolves the base URL from the profile based on the env argument (design / sandbox). The old `fetch(profile, appId)` signature is preserved and defaults to design for backward compat.
- `POST /api/apps/import-plan` accepts optional `sourceEnv` and `targetEnv` fields. Backend uses `sourceEnv` when it needs to fetch (tree not supplied), and `targetEnv` when computing per-step target URLs and building the target Environment card.
- **Full target URLs in plan steps.** Each step now shows `<targetBaseUrl>/services/<dbServiceName>/<Entity>/` — the exact URL that will be hit at execute time, not a relative path.
- The plan response now includes the tree that was planned against (as `tree`) so the frontend can populate its editor when the backend fetched.
- **Same-env warning.** If Source == Target, the plan includes a warning: "Importing will create duplicate rows there with new IDs — usually not what you want."

### Notes
- Existing profile requirements unchanged: both `designBaseUrl` and `sandboxBaseUrl` should be set for full flexibility. If only one is set, the env selector disables the other option.
- Editor accepts arbitrary JSON — the walker will report unresolved keys / warnings but still produce a plan. Useful for testing with modified or synthetic trees.

---

## [0.1.0-SNAPSHOT] — Reference/setup tables safeguard — 2026-08-13

### Added
- **`referenceTables` field on `ProjectProfile`** — a `List<String>` of entity or table names that must **never** be written to during import. Common candidates: `DomainValue`, `Locale`, `Permission` — anything holding shared reference / setup data.
- **Walker skip logic.** Before adding a step for any entity, the walker checks whether that entity is in the reference set (by both table name and entityName, case-insensitive). If so, it emits a warning like `Skipped 'DomainValue' at path '/…' — configured as a reference/setup table (never written)` and skips both the step and any recursion into its children.
- **FK-to-reference short-circuit.** When a column's FK target is a reference table, the source value is placed directly in the body preview (e.g., `applicationStatus: 1815`), NOT as `<pending: DomainValue.1815>`. The FK is also excluded from the fkRemaps list — the executor will send the source value unchanged.
- **Frontend field** in profile Edit form: "Reference / setup tables (never written)" — comma-separated input. Parsed to array on save; joined back to string on Edit. Stored under the same `.devbridge\profiles\<id>.json` file as other profile config.

### Safety
- Zero writes to configured reference tables. This is enforced at plan-build time (they never enter the step list) and will continue to be enforced at execute time in iteration 5d (no journal entry means nothing to write).
- Independent of naming heuristics — even if the resolver mis-guessed and mapped a JSON key to a reference table, the walker refuses to create a step for it.

---

## [0.1.0-SNAPSHOT] — App Import: source/target sections + editable tree — 2026-08-13

### Added
- **Editable tree.** After fetching an app, the JSON tree appears in a dark-themed monospace editor. The developer can modify values before planning — the plan runs against the edited tree, not the raw fetch. Live JSON validation with clear error messages; a **Reset to original** button reverts to the freshly-fetched values.
- **Source / Target env card** at the top of every plan:
  - **Source (read from)** — full URL of the facade fetch (design env)
  - **Target (write to)** — sandbox base URL where writes will happen at execute time
- `POST /api/apps/import-plan` now accepts an optional `tree` field in the request body. If supplied, the plan is built against that tree; if omitted, the backend still fetches fresh (backward-compatible).
- `ImportPlan` record extended with `source` and `target` `Environment` fields — populated from the active profile.
- FK-remap section on each step now includes a note explaining that when a remap target isn't in the tree (typical for lookup/reference tables like `DomainValue`), the source value is passed through at execute time — no writes to reference data.

### Notes
- Plan button is disabled until a tree is available (either freshly fetched or an already-loaded edit).
- Edits stay in browser memory for the duration of the view — leaving the App Import screen resets to a clean state.

---

## [0.1.0-SNAPSHOT] — Resolver: trailing camelCase word heuristic — 2026-08-13

### Added
- `EntityResolver` now extracts the last camelCase word (from the last uppercase letter onward) and tries it as an entity name. Handles role-prefixed compound nouns:
  - `mobilePhone` → `Phone`
  - `homePhone` → `Phone`
  - `billingAddress` → `Address`
  - `primaryEmail` → `Email`
- Applied as fallback after direct / singular-plural / VO-strip / prefix matches all fail. Safe because it only matches if the trailing word uniquely identifies an entity.

### Notes
- Convention-based, project-agnostic. Any FAWB project with role-prefixed compound-noun JSON keys benefits automatically. No per-project code needed.
- Users can now remove entries like `{"mobilePhone": "Phone"}` from their profile overrides — the resolver handles it.

---

## [0.1.0-SNAPSHOT] — Resolver: VO / VOList suffix stripping — 2026-08-13

### Added
- `EntityResolver` now strips WaveMaker's `VO` and `VOList` wrapper suffixes before trying to match. Catches JSON keys like:
  - `riskProfilingResponseVO` → strip `VO` → resolves to `RiskProfilingResponse`
  - `adverseMediaScreeningResponseVOList` → strip `VOList` → resolves to `AdverseMediaScreeningResponse`
  - `c2CreateUpdateDataResponseExtractVO` → strip `VO` → resolves to `C2createUpdateDataResponseExtract`
- New helper method `matchWithVariants` in the resolver — direct / singular / plural attempts consolidated in one place, so we can call it both on the raw key and on the stripped key.

### Notes
- Convention-based, not project-specific. Any FAWB project using the same VO wrapper convention benefits automatically.
- Role-based prefix names (e.g., `mobilePhone` / `homePhone` both pointing at entity `Phone`) still require explicit `jsonKeyOverrides` — no heuristic can safely infer these.

---

## [0.1.0-SNAPSHOT] — Import plan: bug fixes + prefix heuristic + overrides — 2026-08-13

### Fixed
- **Scalar-noise bug in walker.** Previously reported denormalized scalar fields (e.g., `applicationStatusCode`, `statusCode`, `lastUpdated`, `initFeeUpfront`, `selected`, `available`) as "unresolved JSON keys". Now silently skips any JSON key whose value is a scalar or empty array — those aren't entity references. Removes ~90% of false-positive warnings in typical plans.

### Added
- **Prefix-match heuristic in `EntityResolver`.** After exact / singular / plural matches fail, strips trailing `s` and finds any entity whose entityName starts with that prefix. Accepts only if unique. Catches WaveMaker suffix conventions like `productDecisionOutputs` → `ProductDecisionOutputExtract`.
- **Per-profile JSON key overrides.** New `jsonKeyOverrides` field on `ProjectProfile` — a `Map<String, String>` mapping JSON keys from the facade response to entity names in the dataModel. Used for cases the auto-resolver can't figure out (e.g., `applicants → Party` because there's no entity literally named `Applicant`).
- Frontend: new "JSON key overrides (advanced)" textarea in the profile edit form. Accepts a JSON object; validated on save with clear error messages. Existing overrides are pretty-printed when opening Edit.

### Changed
- `EntityResolver` constructor now takes an optional `Map<String, String> jsonKeyOverrides`. Old single-argument constructor preserved for backward compatibility.
- `ImportPlanService.build()` signature changed to accept the full `ProjectProfile` (was: just the `dbServiceName` string) so it can pass overrides to the resolver.

---

## [0.1.0-SNAPSHOT] — Phase 3 iteration 5c: Dry-run import plan — 2026-08-13

### Added
- **Dry-run import planner — zero writes to any environment.** Given a source app ID + the profile's loaded dataModel, produces an ordered list of POST steps with body previews and FK remap placeholders. Read-only, purely computed in memory.
- `com.devbridge.apps.ImportPlan` — record type: `{totalSteps, steps[], warnings[], unresolved[]}`. Each `Step` has `{order, entityName, tableName, targetUrl, pkStrategy, strippedFields, bodyPreview, fkRemaps, notes}`.
- `com.devbridge.apps.EntityResolver` — resolves JSON keys to entities using case-insensitive matching against `entityName` plus singular/plural conventions. Built once per dataModel, O(1) lookup.
- `com.devbridge.apps.ImportPlanService` — recursively walks the app tree and emits Step objects. For each entity: applies rules (identity PK stripped, database-defined columns skipped, insertable=false skipped, FK columns marked for runtime remap). Recurses into nested collections whose JSON key resolves to a child entity.
- `POST /api/apps/import-plan` — accepts `{appId}`, re-fetches the tree from the design env, loads the dataModel, returns the plan. **No side effects** on any environment.
- Frontend **Preview import plan** button in the App Import view (next to Fetch tree). Renders the plan as a stepper: each step is a card with entity + table + URL + PK strategy badge, plus a collapsible body preview showing the JSON that would be POSTed, stripped fields, and FK remap list.
- Warnings / unresolved JSON keys shown as collapsible sections at the top of the plan.

### Notes
- **Guaranteed no side effects:** the planner does not POST, PUT, or DELETE anything on any FAWB env. The only network I/O it performs is the same `GET /services/…/application?…=<appId>` that the Fetch button already uses, to have fresh data to plan against.
- **Dynamic ordering:** the walker builds its dependency graph from the uploaded dataModel — different projects with different models produce different plans without any code changes.
- **Rollback plan for iteration 5d (not built yet):** write journal + reverse-order DELETE via same per-entity APIs; best-effort due to the all-REST architecture (no true transactions across REST calls).

---

## [0.1.0-SNAPSHOT] — DataModel UX: upload inside edit form — 2026-08-13

### Changed
- Moved the DataModel upload action out of the profile card and into the profile **edit form**. Card now only shows the status (loaded / not loaded) — informational, not an action. Rationale: the dataModel is per-profile configuration and belongs alongside other profile fields.
- Edit form now has a "Data model" section (visible only when editing an existing profile; hidden when creating a new one). Section shows:
  - Live status: `Loaded: N tables · N columns · N relations · <size>` (green) or `Not uploaded yet.` (grey)
  - **Upload dataModel** / **Replace dataModel** button (label switches based on state)
  - **Remove** button (visible only when loaded)
- After upload or remove, both the form status AND the card status refresh so state stays consistent.
- New profiles: the DataModel section is hidden until the profile is saved (there's no profile ID to attach the upload to yet). User must save first, then re-open Edit to upload.

---

## [0.1.0-SNAPSHOT] — Phase 3 iteration 5b: DataModel upload + parser — 2026-08-13

### Added
- **Data model layer** (`com.devbridge.datamodel.*`):
  - `DataModel` — root record with nested `Table`, `Column`, `ColumnValue`, `Mask`, `PrimaryKey`, `Generator`, `Relation`, `Mapping` types. Jackson maps by field name. Unknown fields are silently ignored so future FAWB versions with extra keys don't break parsing.
  - `DataModel` exposes convenience counters: `tableCount()`, `columnCount()`, `relationCount()`, `virtualRelationCount()`.
  - `DataModelService` — persists uploaded JSON per profile at `%USERPROFILE%\.devbridge\cache\<profileId>\dataModel.json`, parses on upload, in-memory cache keyed by profileId (survives restarts by re-reading from disk on first access).
  - `DataModelSummary` — a lightweight DTO returned to the frontend: `{loaded, name, packageName, tables, columns, relations, virtualRelations, sizeBytes, uploadedAt}`.
- **REST endpoints** (`DataModelController`, `/api/profiles/{profileId}/data-model`):
  - `GET` → returns summary (or `loaded: false` if none)
  - `POST` (multipart, field `file`) → validates JSON, saves to disk, returns success + summary
  - `DELETE` → removes the cached file for that profile
- **Multipart upload configuration** in `application.properties`: max file size 15 MB (well above the ~1.5 MB real files).
- **Frontend upload UX** — every profile card now shows a status strip: **"DataModel: N tables · 1.5 MB"** (green dot) or **"DataModel not uploaded"** (grey dot). A new **"DataModel"** action button on each card opens a file picker; if a model already exists, offers to replace or remove. Toast confirms outcome.
- Frontend `api.js` — three new helpers: `getDataModelSummary`, `uploadDataModel` (multipart), `deleteDataModel`.

### Changed
- Removed the local-path text field (`dataModelJsonPath`) from the profile form. Upload replaces it entirely. Existing profiles that had a path stored: field is simply ignored (Jackson tolerant), no migration needed.

### Notes
- Parser is deliberately field-by-field rather than raw JSON pass-through, so future walker iterations get a strongly-typed model to work with (better than `Map<String, Object>` gymnastics).
- File is validated (`readTree` + required-key check) BEFORE it hits disk — bad uploads never persist.
- In-memory cache is warm after first request; re-parses only if backend restarts or the file is replaced.

---

## [0.1.0-SNAPSHOT] — Fix: facade query param normalisation — 2026-08-13

### Fixed
- Backend now strips any `=value`, `?`, `&` prefix/suffix from the profile's `facadeQueryParam` before building the fetch URL. If a user pastes `applicantionId=6921` (or `?applicantionId`) into the profile field, the tool resolves it to just `applicantionId` instead of building a broken URL like `?applicantionId%3D6921=<id>`.
- Frontend URL preview under the app-id input applies the same normalisation, so the developer sees the actual URL that will be called — not whatever raw text is in the profile.
- Profile form help text for **Facade query param** now explicitly says "Just the parameter name — no `=`, no value, no leading `?`".

---

## [0.1.0-SNAPSHOT] — Phase 3 iteration 5a: App Import (fetch only) — 2026-08-13

### Added
- **Module 2 (App Import) — first iteration.** Fetch a single application's composite tree from the active profile's design env via the configured facade endpoint. No import logic yet — this iteration just proves the fetch path works end-to-end.
- `com.devbridge.apps.AppFetchService` — builds the URL from the profile's `designBaseUrl` + `facadeServicePath` + `facadeEndpoint` + `facadeQueryParam` (**typo-preserved verbatim** — e.g., `applicantionId` is passed exactly as configured). Delegates to `FawbClient` for the HTTPS + bearer.
- `com.devbridge.api.AppImportController` — `POST /api/apps/fetch` with body `{appId}`. Uses the active profile. Auto-clears the token slot on 401 (same pattern as `SqlController` and `TestConnectionService`).
- **App Import view** replaces the placeholder:
  - Guard clauses: warns if no active profile, or if the profile is missing any of the four facade config fields, and disables the input in either case
  - Shows the constructed URL pattern below the input so the developer sees exactly what will be called
  - App ID input + Fetch button (or Enter key)
  - Success: renders a summary table (top-level field / type / size / preview) inside a card, plus collapsible "Show raw response JSON" and "Show request URL" toggles
  - Failure: status banner with HTTP code, error message, and the response body if any; 401 triggers the standard token-expired flow

### Notes
- **Iteration scope is deliberately small.** Fetch + display only. Next iterations will:
  - Load the profile's dataModel JSON into an in-memory model
  - Compute a topological insert plan over the tree using that model
  - POST each entity to the sandbox with source→target ID remapping
  - Handle partial-failure and error recovery
- The summary table gives us — and you — a fast way to eyeball what a real app tree looks like before we build the walker.

---

## [0.1.0-SNAPSHOT] — Full UI redesign: modern SaaS aesthetic — 2026-08-13

### Changed
- **Full design-system rewrite of `main.css`.** Introduced explicit design tokens: indigo primary (`#4f46e5`), slate greyscale, semantic colors (success/danger/warning), 4px spacing rhythm, three radius steps, four shadow layers, and shared motion easing.
- **Sidebar overhaul.**
  - Darker background (slate 900) with subtle gradient brand block next to "DevBridge".
  - **Icons on every nav item** — inline SVGs (Feather set): Home, SQL Runner, Import App, Bulk Import, Project Profiles, Settings.
  - Nav items are now pill-shaped with indigo-tinted active state (background + text tint), replacing the left-border accent.
  - Token status dot now has a soft outer ring in matching hue for a more polished look.
- **Buttons.** New system: primary (indigo, subtle shadow), secondary (white with border), ghost (transparent, muted text), danger (red). Refined focus rings using the primary color at 15% alpha. Icons align inline via flex + gap.
- **Cards.** Softer shadow, 8px radius, refined hover state (subtle lift + shadow bump + border color change).
- **Modal.** Bigger radius (12px), stronger backdrop blur, entry animation with translate+scale.
- **Home view redesigned.** Two-line welcome + a quick-actions grid with four large cards linking to SQL Runner / Import App / Bulk Import / Profiles, each with icon + title + short description.
- **Profiles view: grid layout.** `repeat(auto-fill, minmax(280px, 1fr))` — 3+ cards per row on typical screens.
- **Profile cards redesigned.**
  - Compact vertical layout: colored avatar with initials (deterministic color from name hash), name, host-only URL preview, Active badge if active.
  - Action row (Test / Edit / Delete) separated by a subtle top border. Icons on every button (Feather set).
  - Ghost-style buttons for secondary actions reduce visual weight.
- **Empty state for profiles view.** Dashed-border box with folder icon, headline, description, and inline "Add profile" button — replaces the muted-text placeholder.
- Refined tables, forms, toasts, and dropdowns to share the token system.

### Notes
- No functional changes — every UI feature that worked before still works exactly the same. This is purely visual + interaction polish.
- Design vocabulary borrows from Linear, Vercel, and Stripe dashboards: clean surfaces, subtle depth, single accent color, generous whitespace.
- No external font loading — system font stack (Segoe UI on Windows, SF Pro on macOS) renders beautifully at these sizes.

---

## [0.1.0-SNAPSHOT] — UI polish, token persistence, expiry handling — 2026-08-13

### Changed
- **Pagination controls are now icon-only.** `« First`, `‹ Prev`, `Next ›`, `Last »` collapse to `«`, `‹`, `›`, `»` — same functions, less visual noise. Each icon has a `title` and `aria-label` for tooltip / accessibility. New `.btn-icon` class for square 32px icon buttons with a proper disabled state.
- **Export UI collapsed to a single dropdown button** with a Feather-style download icon: `⬇ Export ▾`. Click opens a small menu with `CSV`, `Excel (.xlsx)`, `JSON`; clicking an item triggers the download and closes the menu. Closes on outside click or Escape.
- Toast-based notifications now replace `alert()` calls for common feedback (token saved / cleared, action failures).

### Added
- **Token persistence via browser localStorage** (per profile, keyed as `devbridge.token.<profileId>`):
  - Saving a token writes to both backend memory AND localStorage.
  - Clearing a token wipes both.
  - On page load, the frontend restores tokens from localStorage back into the backend's in-memory holder (handles both browser hard-refresh and Spring Boot devtools restarts).
  - Never touches the profile JSON files — token stays out of shareable configs.
- **Token expiry detection.** When any request returns 401:
  - **Backend** clears the profile's token slot (in `SqlController` and `TestConnectionService`).
  - **Frontend** dispatches a `token-expired` event.
  - **Handler** (in `main.js`) wipes the profile's localStorage entry, refreshes the sidebar indicator (turns red), shows a red toast "Your token has expired. Please paste a fresh one.", and auto-opens the token modal.
- **Modal help text adapts** — if a token is already set for the active profile, the modal notes "A token is already stored for this profile. Pasting a new one will replace it."
- Toast notification system (`window.showToast(message, kind, duration)`), with `info` / `success` / `error` variants, dismiss button, auto-dismiss after 5s.
- New `api.js` helpers: `getStoredToken`, `clearStoredToken`, `restoreTokenToBackend` — thin wrappers around localStorage + a raw fetch to `/api/auth/token`.

### Fixed
- Token replacement on paste now visibly confirmed (modal text + success toast).
- Sidebar token indicator now refreshes automatically after expiry, without needing a manual page reload.

### Notes
- Security posture: tokens live in browser localStorage only. Same threat model as browser cookies — any process running as the user can read them. Not persisted server-side. If a 30-minute token leaks, damage window matches how long the token would have been valid anyway.

---

## [0.1.0-SNAPSHOT] — SQL Runner: exports (CSV / Excel / JSON) — 2026-08-13

### Added
- **Export toolbar** above the results table (right-aligned): `CSV | Excel | JSON` buttons.
- Every export uses the **full result set**, not just the current page.
- Filename convention: `sql-result-<YYYYMMDD-HHMMSS>.<ext>`.
- **CSV** — client-side. Properly escapes fields with commas, quotes, and newlines (doubles up embedded quotes per RFC 4180). CRLF line endings for Windows compatibility. Prefixes a UTF-8 BOM so Excel opens the file with correct encoding on Windows.
- **JSON** — client-side. `JSON.stringify(rows, null, 2)` — pretty-printed for readability.
- **Excel (.xlsx)** — server-side via Apache POI. Endpoint: `POST /api/sql/export/xlsx` with body `{columns, rows}`. Bold header row, frozen top row, fixed 20-character column width (skips POI's slow `autoSizeColumn`). Content-Disposition set for browser download.
- `pom.xml` — added `org.apache.poi:poi-ooxml:5.2.5`. Adds ~10 MB to the fat JAR; production impact negligible.

### Notes
- Because `pom.xml` changed, a **full app restart** is needed (not just devtools hot reload) to pick up the POI dependency.
- Excel column width is deliberately fixed — POI's `autoSizeColumn` is very slow on large result sets. Users can auto-fit in Excel with `Ctrl+A → Home → Format → AutoFit Column Width` if needed.
- CSV BOM: the tiny 3-byte prefix `﻿` tells Excel to treat the file as UTF-8 rather than the local codepage; otherwise non-ASCII characters (accents, currency symbols) render as gibberish on Windows.

---

## [0.1.0-SNAPSHOT] — SQL Runner: response envelope parser — 2026-08-13

### Fixed
- **The stray `sql: null` column is gone.** FAWB's `executeSQLs` endpoint returns an array of envelopes — one per row — where each envelope has a `sql` field and a `response` field. The `response` is a **JSON-encoded string** (not a nested object), containing that row's actual columns.
- Frontend parser (`unwrapFawbResponse` in `sql-runner.js`) now:
  1. Iterates the array
  2. `JSON.parse`s each envelope's `response` string
  3. Uses those parsed objects as the actual rows
- The envelope `sql` field is discarded so it no longer leaks into the table as a null column.
- The **"Show raw response JSON"** toggle still shows the original envelope-wrapped response for debugging.
- If a `response` string is malformed JSON, the row is kept but wrapped as `{_rawString: "..."}` so it's at least visible instead of silently dropped.
- Handles the edge case where `response` is itself an array (flattens).

### Notes
- Envelope shape documented in `reference-fawb-apis` memory.
- If a future FAWB response has additional envelope fields (e.g., `error`, `columnNames`), the parser will still work for the happy path — we'll extend it when we see them.

---

## [0.1.0-SNAPSHOT] — SQL Runner: client-side pagination — 2026-08-13

### Added
- **Pagination** for the SQL result table (client-side; all rows already in memory). Below the table:
  - **« First**, **‹ Prev**, **Next ›**, **Last »** buttons
  - **Page N of M** with an input to jump directly (`Enter` or blur commits; clamps to valid range)
  - **Rows N–M of Total** counter
  - **Rows per page** selector: 10 / 25 / 50 / 100 / 250 (default 50)
- Page-size change preserves the top-visible row: if you're on page 3 of 50 and switch to 100, you land on page 2 (row 101 still visible).
- Automatic scroll-to-top of the table body when the page changes.
- Split render pipeline: table + pagination re-render on interaction, but debug toggles (Show raw response, Show request URL) keep their open/closed state.

### Notes
- Purely client-side. The full result set is fetched from FAWB in one call; the tool paginates in the browser. Adding server-side `LIMIT`/`OFFSET` (or the endpoint's native pagination if it has one) is a follow-up if we ever need to page through millions of rows.
- Buttons disable at the edges (« First and ‹ Prev on page 1; Next › and Last » on the last page).

---

## [0.1.0-SNAPSHOT] — SQL Runner: request URL debug toggle — 2026-08-13

### Added
- `SqlService.buildUrl(profile, env, sql)` — extracted URL construction into its own method for reuse.
- `SqlService.ExecuteResult` record — captures the URL that was sent along with status + body.
- `POST /api/sql/execute` response now includes a `requestUrl` field (present on both success and failure), so the frontend can display the exact URL that hit FAWB.
- **"Show request URL" toggle** below every result — collapsible `<details>` with the full request URL (URL-encoded SQL included). Rendered as a clickable link that opens the URL in a new tab; since the browser has the user's FAWB session, clicking usually returns the same JSON directly for verification.
- CSS: `.sql-request-url` — light background, monospace, word-break so long encoded URLs wrap.

### Notes
- Useful for diagnosing encoding issues (e.g., special characters in SQL, quotes, `LIKE '%pattern%'`).
- Also handy when a query fails: the URL shown is the *exact* one that was sent, so you can compare it to what the FAWB console generates.

---

## [0.1.0-SNAPSHOT] — Phase 2 iteration 4a: SQL Runner MVP — 2026-08-13

### Added
- **FAWB SQL API discovered and integrated.** Endpoint: `GET {baseUrl}/services/schema/executeSQLs?db={dbServiceName}&dbCommands={url-encoded SQL}`. Same shape for sandbox and design; only the base URL differs. Documented in `reference-fawb-apis` memory.
- `com.devbridge.sql.SqlService` — builds the URL for a given profile + env + SQL, delegates the HTTP call to `FawbClient` (which handles the bearer token). URL-encodes SQL using percent-encoding (spaces become `%20`, not `+`).
- `com.devbridge.api.SqlController` — `POST /api/sql/execute` with body `{env: "sandbox"|"design", sql: "..."}`. Uses the currently active profile. Proxies the response through as JSON `{success, status, data}`; error responses include the FAWB body for debugging.
- **SQL Runner view (Module 1 MVP):**
  - Env dropdown filtered to envs the active profile actually has URLs for
  - Dark-themed monospace textarea (developer-familiar)
  - **Ctrl+Enter** to run
  - Status banner with success/info/error colors and timing info
  - Result table with sticky header, hover rows, ellipsis on long cells, up to 600px scroll area
  - Graceful fallback: if the response isn't an array of row objects, dumps the raw JSON so we can iterate on the parser once we see real data
  - Guardrails: warns when the active profile is missing `dbServiceName`, has no base URLs, or when no profile is active
- Task #22 (SQL API spike) marked complete.

### Notes
- Response shape parser is intentionally best-effort. First run against real FAWB will tell us whether the response is an array of row objects, a WaveMaker envelope, or something else. The fallback path dumps the raw JSON so we can adjust rendering in a follow-up.
- Exports (CSV/Excel/JSON) and query history are follow-up iterations.

---

## [0.1.0-SNAPSHOT] — Per-profile auth tokens — 2026-08-13

### Changed
- **BREAKING (in-memory only, no on-disk impact):** `AuthTokenHolder` now stores tokens per profile in a `ConcurrentHashMap<profileId, token>` instead of a single global string. Reason: `WM_AUTH_TOKEN` is scoped per region — different profiles pointing to different regions need distinct tokens.
- All auth REST endpoints (`GET /api/auth/status`, `POST /api/auth/token`, `DELETE /api/auth/token`) now target one profile's slot. They resolve the target as: explicit `?profileId=...` query param first, then the current active profile. If neither is available, they return 400 (or `set: false` for GET).
- `FawbClient.get(url, profileId)` — HTTP client now takes an explicit profileId so it picks the right token slot. `TestConnectionService` passes `profile.id()` through.
- Sidebar token indicator now reflects the **active profile's** token status. Switching profiles updates it live via the `profile-changed` event.
- Token modal title now shows `FAWB auth token — <Profile Name>` so the user knows which slot they're editing.
- `ProfileService.delete()` clears the deleted profile's token slot so it doesn't leak into memory.

### Notes
- Tokens remain **in memory only** and are cleared on app restart. The security posture is unchanged.
- REST API surface still never returns a token *value* — only `{set: boolean, profileId: string}`.

---

## [0.1.0-SNAPSHOT] — Phase 1b iteration 3b: FawbClient + Test Connection — 2026-08-12

### Added
- `com.devbridge.fawb.FawbClient` — Java `HttpClient` wrapper that injects the current `Authorization: Bearer <token>` header from `AuthTokenHolder`. Does not follow redirects (so login-redirect pages don't mask real auth failures). 5 s connect timeout, 15 s request timeout. Never logs the token value.
- `com.devbridge.fawb.TestConnectionResult` — result record with `env`, `success`, `statusCode`, `message`.
- `com.devbridge.fawb.TestConnectionService` — probes each configured env base URL by hitting `/services/security/user` (WaveMaker convention). Interprets HTTP status: 2xx = OK, 401 = token rejected, 403 = missing role, 3xx = redirect (likely auth expired), 404 = server reachable but probe path absent, exception = network/URL problem.
- `POST /api/profiles/{id}/test-connection` — returns a list of `TestConnectionResult`, one per env with a base URL set.
- **Test connection** button on each profile card (primary style so it's the most prominent action). Runs against both sandbox and design if both are set. Result strip appears below the card with per-env dots + HTTP status + human-readable message.
- If Test Connection is clicked with no token set, the button reports "opening token modal" and dispatches `open-token-modal`, which `main.js` listens for and opens the modal.

### Notes
- Probe path is currently hard-coded to `/services/security/user`. If a FAWB deployment uses a different auth-required path, this becomes a per-profile setting later.
- Redirects are disabled at the HTTP client level. This is deliberate: FAWB redirects unauthenticated requests to a login page that returns 200, which would otherwise look like success.

---

## [0.1.0-SNAPSHOT] — Phase 1b iteration 3a: Auth token + paste modal — 2026-08-12

### Added
- `com.devbridge.auth.AuthTokenHolder` — in-memory bearer token holder, single Spring bean. Never persisted, cleared on app restart.
- `com.devbridge.api.AuthController` — REST endpoints for the token:
  - `GET /api/auth/status` — returns `{"set": true|false}`. Never returns the token value.
  - `POST /api/auth/token` — accepts `{"token": "..."}`, stores in memory, returns `{"set": true}`.
  - `DELETE /api/auth/token` — clears the token, returns `{"set": false}`.
- Token paste **modal** — accessible from the sidebar. Overlay-based, focuses the textarea on open, closes on Escape or on clicking the overlay. Save / Clear stored / Cancel buttons. Token textarea uses monospace font and is never pre-populated (secrets should not persist across dialog opens).
- Token **status indicator** in the sidebar footer between the profile switcher and the backend status. Red dot + "Token not set" or green dot + "Token set". Clickable — opens the modal.
- Frontend `token-changed` custom event dispatched on save/clear, wired to refresh the sidebar indicator.
- Frontend `api.js` — `getAuthStatus`, `setAuthToken`, `clearAuthToken` helpers.

### Notes on security
- The token is only ever accessible server-side via `AuthTokenHolder.getToken()` — the REST API never returns the token value in any response.
- The frontend textarea is cleared on every modal open, so an accidental screen-share or over-the-shoulder viewer won't see a previously-pasted token.

---

## [0.1.0-SNAPSHOT] — Profile editing + hot reload — 2026-08-12

### Added
- **Profile editing.** `PUT /api/profiles/{id}` endpoint and matching Edit button on each profile card. Reuses the existing form in "Edit" mode (heading changes to "Edit profile — <name>", submit button reads "Save changes"). Preserves `id`, `createdAt`, `lastUsedAt` server-side.
- **Hot reload for development.** Added `spring-boot-devtools` dependency and configured `spring.web.resources.static-locations` to read directly from `src/main/resources/static/` in dev. Static file changes now reflect on browser refresh without app restart; Java changes trigger a fast in-place restart (~1–2s) instead of a full cold start.
- `application.properties` — dev-friendly cache and static-locations settings; devtools LiveReload enabled (port 35729).

### Notes on DataModel JSON handling
- The `dataModelJsonPath` field remains a local path string. No code reads it yet — this is deliberate. The parser lands with Phase 3 (Module 2 — App Import), where a `DataModelLoader` service will read the file at import time and parse it against the structure documented during requirements. A test fixture will provide coverage without needing the real file.

---

## [0.1.0-SNAPSHOT] — Phase 1b iteration 1 UX pass — 2026-08-12

### Changed
- **BREAKING (profile schema):** replaced `fawbBaseUrl`, `sandboxInstanceId`, `designInstanceId` with two full-URL fields — `sandboxBaseUrl` and `designBaseUrl`. Two environment URL patterns (path-based sandbox URLs vs. subdomain-based design/staging URLs) motivated the change. Existing profile files load without error thanks to `@JsonIgnoreProperties(ignoreUnknown = true)`, but the two new fields will be empty and should be re-entered via the UI.
- Profile form field order and help text updated to reflect the new fields and clarify which fields matter for which module.
- Profile cards are now clickable — clicking anywhere on an inactive card activates that profile. `Delete` remains an explicit button. The redundant "Activate" button was removed; a small "Click to activate" hint replaces it on inactive cards.

### Added
- Compact **profile switcher dropdown** in the sidebar footer. Opens on click, lists all profiles, activates on selection, closes on outside click or Escape. Also offers a "Manage profiles →" link that jumps to the Profiles view. Enables switching profiles from anywhere in the tool without leaving the current view.
- `@JsonIgnoreProperties(ignoreUnknown = true)` on `ProjectProfile` so schema changes don't crash on old on-disk profiles.

---

## [0.1.0-SNAPSHOT] — Phase 1b iteration 1 — 2026-08-11

### Added
- `com.devbridge.profile.ProjectProfile` — immutable record with 10 fields plus `createdAt` and `lastUsedAt` timestamps
- `com.devbridge.profile.ProfileRepository` — JSON-file-per-profile persistence under `%USERPROFILE%\.devbridge\profiles\`; loads all files into memory on startup
- `com.devbridge.profile.ActiveProfileHolder` — in-memory holder for the currently active profile ID (not persisted across restarts, by design)
- `com.devbridge.profile.ProfileService` — CRUD + activate business logic
- `com.devbridge.api.ProfileController` — REST endpoints:
  - `GET /api/profiles`
  - `GET /api/profiles/active` (returns 204 if none)
  - `POST /api/profiles`
  - `DELETE /api/profiles/{id}`
  - `POST /api/profiles/{id}/activate`
- Profile CRUD helpers in `static/js/api.js` (`listProfiles`, `createProfile`, `deleteProfile`, `activateProfile`, `getActiveProfile`)
- Real interactive Profiles view (`static/js/views/profiles.js`): form driven by a `FIELDS` array, cards with activate/delete actions, active-state highlighting
- Sidebar footer "Active profile" section that refreshes via the browser-native `profile-changed` custom event
- Form / card / badge styling in `static/css/main.css`

### Changed
- Rebuilt frontend from the initial Vaadin scaffold to plain HTML + CSS + ES6 modules with hash-based routing (see [ADR 001](decisions/adr-001-plain-html-over-vaadin.md))
- `pom.xml` — dropped `vaadin-spring-boot-starter` and `vaadin-maven-plugin`; added `spring-boot-starter-web`
- `application.properties` — removed Vaadin-specific config
- `README.md` — updated to reflect the plain-HTML/JS stack

### Removed
- Vaadin skeleton (`MainLayout.java` + six view classes under `com.devbridge.ui.*`)
- Vaadin dependency from `pom.xml`, Vaadin repository, `vaadin-maven-plugin`

---

## [0.1.0-SNAPSHOT] — Phase 1a — 2026-08-11

### Added
- Initial Maven project with Spring Boot 3.3.5 parent, Java 17, `com.devbridge` group ID
- `DevBridgeApplication` — Spring Boot entry point
- `HealthController` — `GET /api/health` returning `{status, app, version}`
- Frontend skeleton (initially Vaadin, then plain HTML/CSS/JS — see the Phase 1b iteration 1 entry above for the pivot)
- `application.properties` with base config
- `.gitignore` covering Maven, IntelliJ, Vaadin/Node artifacts, and `.devbridge/` local profile storage
- `README.md` — setup and run instructions
- `docs/PROJECT.md` — internal working documentation framework (Overview, Architecture, ADRs, QA, Progress Tracker)
- `docs/PROJECT_REVIEW.md` — versioned cross-functional review artifact
- `docs/decisions/adr-001-plain-html-over-vaadin.md`
- `docs/decisions/adr-002-json-file-profile-storage.md`
- `docs/decisions/adr-003-all-rest-no-jdbc.md`

### Notes
- Phase 1a and Phase 1b iteration 1 both landed on 2026-08-11. They share the `0.1.0-SNAPSHOT` version because no formal release has been cut yet — this changelog captures the incremental history until the first tagged release.

---

## [0.0.0] — Requirements phase — 2026-01-05 to 2026-08-11

### Added
- `problem-statement.md` v0.1 (2026-08-11) and v1.0 (2026-08-11) — external to the code repo, lives at `../problem-statement.md`
- Memory files under `.claude/projects/…/memory/` capturing user context, project scope, tech stack, FAWB terminology and API surface, dataModel JSON structure

### Notes
- Idea first written 2026-01-05.
- Requirements analysis phase spanned exploratory conversations culminating in the v1.0 problem statement on 2026-08-11.
- Tool name **DevBridge** was chosen 2026-08-11 for cross-project positioning and generic-git safety.
