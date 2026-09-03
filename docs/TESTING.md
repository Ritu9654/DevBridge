# DevBridge — Testing Guide

> Practical, step-by-step testing runbook. Use this to verify a build before shipping to teammates, before demoing, or after any non-trivial change. As modules land, their sections here go from "planned" to "manual checklist" to "automated + manual".

## Contents

1. [How to run the app](#1-how-to-run-the-app)
2. [Automated tests](#2-automated-tests)
3. [Manual test checklists (per feature)](#3-manual-test-checklists-per-feature)
4. [End-to-end scenarios](#4-end-to-end-scenarios)
5. [Regression suite (run every release)](#5-regression-suite-run-every-release)
6. [How to file a bug](#6-how-to-file-a-bug)

---

## 1. How to run the app

### Prerequisites

- Java 17+ installed (`java -version` confirms).
- Maven wrapper or Maven 3.8+ available.
- Port `8080` free on your machine.

### Steps

1. Open the project folder in IntelliJ (or your IDE of choice) as a Maven project.
2. Wait for Maven to import dependencies (bottom-right progress bar).
3. Run `DevBridgeApplication` (green ▶ next to `public static void main`), **or** from the folder in a terminal:
   ```bash
   mvn spring-boot:run
   ```
4. Open a browser at http://localhost:8080.

### What you should see

- Sidebar on the left labeled **DevBridge** with six nav items.
- Home view visible, welcome text rendered.
- Sidebar footer shows **Active profile: None selected** (italic muted text) and **Backend v0.1.0-SNAPSHOT** (green dot).

If the backend status is red ("Backend unreachable") or the page is blank, see [§6](#6-how-to-file-a-bug).

---

## 2. Automated tests

### Run all tests

```bash
mvn test
```

### Current coverage

*As of 2026-08-11, only the default Spring Boot context-load test exists (auto-generated). Real unit and integration tests come with Phase 1b domain classes and beyond.*

### Planned test suites

| Suite | Location | Scope | Status |
|---|---|---|---|
| Unit — profile model | `src/test/java/com/devbridge/profile/*UnitTest.java` | `ProjectProfile`, `ProfileRepository`, `ProfileService` in isolation | ⏳ Planned |
| Integration — profile REST | `src/test/java/com/devbridge/api/ProfileControllerIntegrationTest.java` | Full Spring context, `MockMvc`, on-disk H2 | ⏳ Planned |
| Contract — platform client | `src/test/java/com/devbridge/fawb/FawbClientContractTest.java` | Replays recorded HTTPS fixtures with MockWebServer | ⏳ Planned |
| Integration — import walker | `src/test/java/com/devbridge/imports/*IntegrationTest.java` | Fake platform + verifies POST sequence + ID remap | ⏳ Planned |

---

## 3. Manual test checklists (per feature)

### 3.1 Backend health round-trip *(Phase 1a)*

- [ ] Start the app and open the browser at `http://localhost:8080`
- [ ] Sidebar footer shows **"Backend v0.1.0-SNAPSHOT"** in muted white, green status dot
- [ ] Open browser DevTools → Network tab → refresh page → confirm one call to `/api/health` returns `200`
- [ ] Response body is JSON: `{"status":"ok","app":"DevBridge","version":"0.1.0-SNAPSHOT"}`

### 3.2 Navigation *(Phase 1a)*

- [ ] Sidebar shows 6 nav items: Home, SQL Runner, Import App, Bulk Import, Project Profiles, Settings
- [ ] Clicking each nav item updates the URL hash and the topbar title
- [ ] Active nav item is highlighted (blue left border + white text)
- [ ] Refreshing the page keeps you on the same view

### 3.3 Profile system — create *(Phase 1b iteration 1)*

- [ ] Navigate to **Project Profiles**
- [ ] On first launch with no profiles: page shows "No profiles yet. Click '+ Add profile' above."
- [ ] Click **+ Add profile** — the form appears below with 8 input fields
- [ ] Submit with **Profile name** empty → browser prevents submit (HTML required validation)
- [ ] Fill only Profile name and submit → profile card appears in the list, form closes and resets

### 3.4 Profile system — activate via card click *(Phase 1b iteration 1 UX pass)*

- [ ] Create at least 2 profiles
- [ ] Hover over an inactive card — cursor becomes a pointer, card lifts slightly with a blue-tinted shadow
- [ ] Click the card body (not on the Delete button) — the card gets a green "Active" badge and blue outline; sidebar footer updates to show the profile name
- [ ] Click a different inactive card — badge and outline transfer to it; sidebar footer updates
- [ ] Click a Delete button on any card — activation is not triggered (only Delete happens)

### 3.5 Profile system — switch via sidebar dropdown *(Phase 1b iteration 1 UX pass)*

- [ ] Sidebar footer shows the current active profile name in a button-shaped element with a `▾` chevron
- [ ] Click the switcher button — dropdown opens upward, listing all profiles
- [ ] Currently-active profile shows an "Active" hint on the right
- [ ] Click a different profile in the dropdown — dropdown closes, sidebar name updates, and the corresponding card in the Profiles view (if open) refreshes its active badge
- [ ] Click outside the dropdown (or press Escape) — dropdown closes without changes
- [ ] With zero profiles, dropdown shows "+ Add a profile" link jumping to the Profiles view

### 3.6 Profile system — edit *(Phase 1b iteration 2)*

- [ ] Click **Edit** on any profile card — the form appears at the bottom of the page with the heading `Edit profile — <name>` and the submit button reads `Save changes`; all fields pre-populated with the current values
- [ ] Change one field (e.g., append text to the name), click **Save changes** — form closes; the card shows the updated name; sidebar footer also reflects the new name if the edited profile was active
- [ ] Click **Edit**, change nothing, click **Cancel** — no changes persist; card is unchanged
- [ ] Click **Edit**, then click **+ Add profile** — form switches back to create mode (heading: "New profile", button: "Save profile"), fields empty

### 3.7 Profile system — delete *(Phase 1b iteration 1)*

- [ ] Click **Delete** on any non-active profile → confirm dialog → card disappears
- [ ] Click **Delete** on the active profile → confirm → card disappears **and** sidebar footer resets to "None selected"

### 3.8 Auth token — save, clear, indicator *(Phase 1b iteration 3a + per-profile update)*

**Prerequisite:** at least one profile exists and is **activated** (sidebar shows its name, not "None selected").

- [ ] Sidebar footer shows a red dot with "Token not set" (below the profile switcher, above the backend status)
- [ ] Click the token status line — a modal opens with the title **"FAWB auth token — <Active Profile Name>"**, a textarea, and three buttons: Cancel, Clear stored, Save token
- [ ] Textarea has focus automatically on open
- [ ] Type nothing, click **Save token** — alert says "Paste a token first"
- [ ] Type any non-empty string, click **Save token** — modal closes; sidebar indicator turns green with "Token set"
- [ ] Click the sidebar indicator again — modal reopens with textarea **empty** (never pre-populated for security)
- [ ] Click **Clear stored** → confirm — modal closes; sidebar indicator goes back to red "Token not set"
- [ ] Click the indicator, click **Cancel** — modal closes with no change
- [ ] Open the modal and press **Escape** — modal closes with no change
- [ ] Open the modal, click on the dark overlay (outside the panel) — modal closes with no change
- [ ] Verify in DevTools: `GET /api/auth/status` returns `{"set": true|false, "profileId": "..."}` but **never** the token value in any response

### 3.8a Auth token — per-profile isolation *(new)*

**Prerequisite:** at least two profiles exist.

- [ ] Activate profile A. Sidebar indicator is red "Token not set". Click it → modal title shows "FAWB auth token — A". Paste "token-for-A", Save. Indicator turns green.
- [ ] Switch to profile B via the sidebar dropdown. Sidebar indicator immediately switches back to red "Token not set" (B has no token yet).
- [ ] Click the indicator → modal title shows "FAWB auth token — B". Paste "token-for-B", Save. Indicator turns green.
- [ ] Switch back to profile A. Indicator turns green (A's slot still holds "token-for-A" from earlier).
- [ ] Delete profile A. Confirm A's token slot is cleared: reactivate any profile, then re-create a profile named A — its token indicator should be red (fresh slot, not the old one).

### 3.9 Auth token — cross-restart *(Phase 1b iteration 3a)*

- [ ] With a token saved (green indicator), stop the Spring Boot app
- [ ] Restart, refresh the browser — indicator is back to red "Token not set" (in-memory only, by design)

### 3.10 Test connection — happy path *(Phase 1b iteration 3b)*

**Prerequisites:** a profile with at least one valid base URL (sandbox or design), and a fresh `WM_AUTH_TOKEN` pasted (sidebar shows green "Token set").

- [ ] On the profile card, click **Test connection**. Button text changes to "Testing…" and disables briefly.
- [ ] A result strip appears at the bottom of the card. For each env that has a base URL:
  - Green dot + env name + `HTTP 200` + "Reachable and authorized."
- [ ] Click **Test connection** again — the strip is replaced with fresh results (no duplication).

### 3.11 Test connection — no token *(Phase 1b iteration 3b)*

- [ ] Sidebar shows red "Token not set". Click **Test connection** on any profile card.
- [ ] Result strip briefly appears with an "auth" row saying "No token set — opening the token modal…"
- [ ] Token modal opens automatically.
- [ ] Paste a valid token, click **Save token**. Sidebar goes green.
- [ ] Click **Test connection** again — success as in § 3.10.

### 3.12 Test connection — bad token *(Phase 1b iteration 3b)*

- [ ] Click the sidebar token indicator → paste an intentionally invalid string (e.g., `not-a-real-token`) → Save.
- [ ] Click **Test connection** on a profile with real base URLs.
- [ ] Result strip should show red dot + HTTP status (401 or 302 depending on how FAWB rejects) + a message like "Token rejected — paste a fresh WM_AUTH_TOKEN" or "Redirected — token likely expired or not sent".

### 3.13 Test connection — bad URL *(Phase 1b iteration 3b)*

- [ ] Edit a profile → change the sandbox base URL to `https://this-does-not-exist.example.com` → Save.
- [ ] Click **Test connection**.
- [ ] Result strip shows red dot + no HTTP status + exception message (typically `IOException` or `ConnectException` with detail).

### 3.14 SQL Runner — happy path *(Phase 2 iteration 4a)*

**Prerequisites:** an activated profile with a valid sandbox or design base URL, `dbServiceName` set to `Core` (or whatever your project uses), and a fresh token in that profile's slot (sidebar green).

- [ ] Navigate to **SQL Runner**. Env dropdown offers "Sandbox" and "Design" (only those with URLs configured on the active profile).
- [ ] Textarea has a placeholder example. Type: `SELECT * FROM APPLICATION_DETAILS LIMIT 5` (or any known-safe query for your DB).
- [ ] Press **Ctrl+Enter** — button shows "Running…"; a blue "Running query…" banner appears.
- [ ] On success: banner turns green with `Success — N rows in T ms`. A table appears with column headers and up to 5 rows.
- [ ] Table columns are sticky when scrolling vertically. Long cells truncate with ellipsis.
- [ ] Change env to Design (or Sandbox), run again — separate table replaces previous results.

### 3.15 SQL Runner — no active profile *(Phase 2 iteration 4a)*

- [ ] Delete or deactivate all profiles.
- [ ] Navigate to SQL Runner. Instead of the form, the view shows: "No active profile. Add and activate one to enable SQL runner." Textarea and Run button are disabled.

### 3.16 SQL Runner — missing dbServiceName *(Phase 2 iteration 4a)*

- [ ] Create a profile with only Name + sandbox URL (no `dbServiceName`). Activate it.
- [ ] Navigate to SQL Runner. A warning appears: "Active profile is missing dbServiceName..." with a link to edit.
- [ ] Try to run anyway — status banner shows the same error, no HTTP call is made.

### 3.17 SQL Runner — bad token *(Phase 2 iteration 4a)*

- [ ] Set an invalid token in the active profile's slot.
- [ ] Run any SQL — status banner: `Failed (HTTP 401): Token rejected — paste a fresh WM_AUTH_TOKEN for this profile.`

### 3.18 SQL Runner — unrecognised response *(Phase 2 iteration 4a)*

**This is a diagnostic step, not a bug:**

- [ ] If a valid SQL returns a response the parser doesn't recognise as an array of row objects, results area shows: "Unrecognised response shape — showing raw payload:" followed by pretty-printed JSON.
- [ ] Paste the raw JSON back to me so we can improve the parser.

### 3.19 SQL Runner — pagination *(Phase 2 iteration 4a UX pass)*

**Prerequisite:** run a query that returns at least ~100 rows (e.g., `SELECT * FROM APPLICATION_DETAILS`).

- [ ] Pagination bar appears below the results table with (left to right): « First, ‹ Prev, `Page [ N ] of M`, Next ›, Last », then "Rows N–M of Total", then "Rows per page: [50]"
- [ ] On page 1: **« First** and **‹ Prev** are disabled (greyed out)
- [ ] Click **Next ›** — table shows page 2's rows; "Rows N–M" updates
- [ ] Click **Last »** — jumps to last page; **Next ›** and **Last »** are now disabled
- [ ] Click **‹ Prev** — one page back; row count updates
- [ ] Type a page number in the input, press **Enter** — jumps to that page
- [ ] Type a nonsense number (e.g., `9999`) and press Enter — clamps to the last valid page
- [ ] Change **Rows per page** to **100** — table now shows 100 rows; page number recomputed to keep the previous top row visible
- [ ] Change to **10** — page number recomputed again
- [ ] Vertical scroll inside the table body works independently of pagination; changing page resets the scroll to the top

### 3.20 SQL Runner — exports *(Phase 2 iteration 4b)*

**Prerequisite:** run any query that returns rows (e.g., `SELECT * FROM APPLICATION_DETAILS`).

- [ ] Above the pagination bar, on the right, an **Export** label + three buttons: `CSV | Excel | JSON`.
- [ ] Click **CSV** — file `sql-result-YYYYMMDD-HHMMSS.csv` downloads.
  - [ ] Open in Excel or a text editor. Header row = column names. Data rows follow, one per line. Fields with commas or quotes are quoted. UTF-8 characters render correctly (test with a column containing a non-ASCII name).
- [ ] Click **JSON** — file `sql-result-...json` downloads. Open in a text editor; contents are pretty-printed JSON of an array of row objects — same rows the table displays.
- [ ] Click **Excel** — button briefly reads "Exporting…". File `sql-result-...xlsx` downloads. Open in Excel:
  - [ ] Header row is bold; scroll — header stays frozen at the top.
  - [ ] All columns present, all data rows present.
  - [ ] Numbers render as numbers (not text); nulls are blank cells.
- [ ] Exported row count matches the "Rows … of N" indicator (i.e., all rows, not just the current page).
- [ ] Change page or page size, then export again — exported rows are unaffected (still the full set).
- [ ] For 0-row results the export buttons are not shown (the toolbar is hidden).

### 3.21 UI polish — pagination symbols *(Phase 2 iteration 4c)*

- [ ] Run any query with plenty of rows. Pagination bar shows `«`, `‹`, `[input]`, `of N`, `›`, `»` — no text buttons.
- [ ] Hover over each icon — a tooltip appears (`First page`, `Previous page`, etc.).
- [ ] Icon buttons on page 1: `«` and `‹` show a disabled/greyed state.

### 3.22 UI polish — export dropdown *(Phase 2 iteration 4c)*

- [ ] Above the pagination, on the right, a single **`⬇ Export ▾`** button (no separate CSV/Excel/JSON).
- [ ] Click it — a small menu opens below with three items: `CSV`, `Excel (.xlsx)`, `JSON`.
- [ ] Click outside the menu — it closes; press Escape — it closes.
- [ ] Click **Excel (.xlsx)** — menu closes, `.xlsx` file downloads.
- [ ] Click **CSV** — `.csv` downloads.
- [ ] Click **JSON** — `.json` downloads.

### 3.23 Token persistence across refresh *(Phase 2 iteration 4c)*

- [ ] Activate a profile, paste a valid token — sidebar goes green "Token set".
- [ ] **Hard-refresh** the browser (`Ctrl+Shift+R`) — sidebar goes green again after page load (token restored from localStorage).
- [ ] Trigger a devtools restart by editing any Java file — after Spring Boot restarts, refresh browser — sidebar still green (token re-pushed to backend from localStorage).
- [ ] In DevTools → Application → Local Storage → `http://localhost:8080` — verify a key `devbridge.token.<profileId>` exists.
- [ ] Clear the token via the modal — localStorage entry is removed too.

### 3.24 Token expiry handling *(Phase 2 iteration 4c)*

- [ ] Deliberately paste an invalid token (e.g., `not-a-real-token`) for the active profile.
- [ ] Run any SQL query — expect a failure banner. Then observe:
  - [ ] Sidebar token indicator flips to red "Token not set".
  - [ ] A red toast appears at the bottom-right: "Your token has expired. Please paste a fresh one." (auto-dismisses after ~6 s or click the × to dismiss).
  - [ ] The token modal opens automatically after a brief delay.
- [ ] Same flow via **Test Connection** on a profile card: 401 result triggers the same expiry handler.
- [ ] Paste a fresh valid token, Save — modal closes, success toast, sidebar green.

### 3.25 Token replacement *(Phase 2 iteration 4c)*

- [ ] Paste a token, Save. Sidebar green.
- [ ] Open the token modal again — help text now says "A token is already stored for this profile. Pasting a new one will replace it."
- [ ] Paste a different string, Save — new token replaces old (verify via Test Connection returning the new state).

### 3.26 App Import — fetch happy path *(Phase 3 iteration 5a)*

**Prerequisites:** an activated profile with `designBaseUrl`, `facadeServicePath`, `facadeEndpoint`, `facadeQueryParam` all set; a valid token in the profile's slot; an application ID you know exists in design.

- [ ] Navigate to **Import App**. Below the input, a small monospace line shows the URL pattern that will be called (`.../services/<facade>/…?<param>=<id>`).
- [ ] Type an app ID (e.g., `6901`) and press **Fetch** (or Enter).
- [ ] Status banner shows "Fetching…", then turns green: `Fetched app 6901 in <N> ms — <M> top-level fields`.
- [ ] A summary table appears (inside a card) listing each top-level field of the response, with columns: Field / Type / Size / Preview. Objects show `N fields`; arrays show `N items`; scalars show a short preview.
- [ ] The **Show raw response JSON** toggle expands to the full tree.
- [ ] The **Show request URL** toggle expands to the URL used — click it: opens the URL directly in a new tab (uses your active FAWB browser session).

### 3.27 App Import — missing profile config *(Phase 3 iteration 5a)*

- [ ] Edit the active profile and clear `facadeQueryParam` (or any of the four facade fields). Save.
- [ ] Navigate to Import App. Input and Fetch button are disabled; the page shows: "Active profile is missing: <list of missing fields>. Edit the profile…".
- [ ] Re-add the field, save, return to Import App — inputs re-enabled.

### 3.28 App Import — bad token *(Phase 3 iteration 5a)*

- [ ] Deliberately paste an invalid token for the active profile.
- [ ] Fetch any app ID. Status banner shows red "Failed (HTTP 401): Token expired…".
- [ ] Token-expired flow triggers: sidebar indicator turns red, red toast appears, token modal opens automatically.

### 3.29 App Import — unknown app ID *(Phase 3 iteration 5a)*

- [ ] Fetch a nonsense app ID (e.g., `99999999`). Response behavior depends on the facade:
  - [ ] If it returns 200 with an empty/near-empty tree, the summary table shows fields with size 0 or nulls — fine.
  - [ ] If it returns 4xx/5xx, the status banner shows the HTTP code, the request URL is visible for debugging, and the response body (if any) is displayed as raw JSON.

### 3.30 DataModel upload *(Phase 3 iteration 5b)*

**Prerequisite:** at least one profile exists. A valid `*_published_dataModel.json` file available on disk.

- [ ] Navigate to **Project Profiles**. Each profile card shows a status line **"DataModel not uploaded"** (grey dot) below the URL.
- [ ] Click the **DataModel** button on a card. A file picker opens (accepts `.json`).
- [ ] Select the `Core_published_dataModel.json` file. Button briefly shows "Uploading…", then a green success toast: `DataModel loaded: N tables.`
- [ ] Status line on the card flips to **"DataModel: N tables · 1.5 MB"** with a green dot.
- [ ] Click **DataModel** again on the same card. A confirm dialog: "OK = upload a new file to replace it. Cancel = remove the current one."
  - [ ] Choosing **Cancel** removes it; status returns to "DataModel not uploaded".
  - [ ] Choosing **OK** then selecting a file replaces the cached model.
- [ ] Verify on disk: `%USERPROFILE%\.devbridge\cache\<profileId>\dataModel.json` exists after upload; is removed after "remove".
- [ ] Restart the Spring Boot app. Refresh the browser. Card status still shows the loaded state (re-read from disk on first request).
- [ ] Try uploading a non-JSON file (e.g., a .txt). Toast should say: `Upload failed: Could not parse file as JSON: ...`.
- [ ] Try uploading a JSON file that lacks the `tables` key (e.g., `{"foo": "bar"}`). Toast: `Upload failed: Uploaded file is missing required key 'tables'...`.

### 3.31 App Import — dry-run plan *(Phase 3 iteration 5c)*

**Prerequisites:** activated profile with facade config + valid token + uploaded dataModel + known valid app ID in design env.

- [ ] Navigate to **Import App**. Two buttons: **Fetch tree** (secondary) and **Preview import plan** (primary).
- [ ] Enter an app ID and click **Preview import plan**. Button reads "Planning…".
- [ ] Green banner: `Plan built — N steps in <ms> ms (M notes). No writes performed.`
- [ ] Below the banner, a **Dry-run import plan** card summarises the plan; expand any "Warnings" / "Unresolved JSON keys" to see notes.
- [ ] Below the summary, a stepper. Each step card shows:
  - Order number badge, entity name, DB table name, target URL, PK strategy badge
  - A brief note on what will happen (e.g., "New row — server will assign the PK. 2 FK column(s) to remap.")
  - Collapsible "Body preview" — expand to see the JSON body that would be POSTed, plus lists of stripped fields and FK remaps
- [ ] Verify by network inspection: only **one** HTTP call to FAWB happens — the facade fetch. Zero POSTs.

### 3.32 App Import — plan without dataModel *(Phase 3 iteration 5c)*

- [ ] Remove the dataModel for the active profile (Edit → Remove).
- [ ] Try Preview import plan. Error banner: `Plan failed: No dataModel uploaded for this profile...`
- [ ] Re-upload the dataModel; retry — should succeed.

### 3.33 App Import — plan with unresolved JSON keys *(Phase 3 iteration 5c)*

- [ ] For an app whose response contains fields not mappable to entities (e.g., denormalized codes like `applicationStatusCode`, or nested wrappers), verify:
  - The plan's "Unresolved JSON keys" section lists them explicitly.
  - The walker skips them and continues.
  - Steps for known entities still appear normally.

### 3.34 App Import — reference table safeguard *(Phase 3 iteration 5c)*

- [ ] Open Profile Edit. New section at the bottom: **Reference / setup tables (never written)**.
- [ ] Enter `DomainValue, Locale, Permission` (comma-separated). Save.
- [ ] Reopen Edit — the field shows the same three names still comma-separated.
- [ ] Fetch and Preview import plan for any app.
- [ ] For any step's Body preview: FK columns pointing at `DomainValue` (or the other reference tables) now show the actual source value directly, NOT wrapped in `<pending: DomainValue.NNN>`. Example: `applicationStatus: 1815` instead of `applicationStatus: <pending: DOMAIN_VALUE.1815>`.
- [ ] If your tree accidentally contains a nested reference-table object (unlikely but possible), the plan's Warnings section notes `Skipped '<EntityName>' at path '/…' — configured as a reference/setup table (never written)`, and no step is generated for it.
- [ ] Remove a reference table from the profile (edit, save). Preview plan again. Values that were pass-through are back to `<pending: TABLE.value>` (would be remapped at execute time).

### 3.35 App Import — env selectors + Preview flow *(Phase 3 iteration 5c)*

- [ ] Navigate to Import App. Form now has (top to bottom): Source env dropdown, arrow, Target env dropdown, then App ID input + Preview + Clear tree buttons.
- [ ] Source defaults to **Design**, Target defaults to **Sandbox**.
- [ ] Change Source to Sandbox → the "Will fetch:" preview URL updates to reflect the sandbox base URL.
- [ ] Enter an app ID (with editor **empty**) and click Preview. Backend fetches from selected source env, populates the editor, shows the plan. Editor header shows "Fetched · app <id>".
- [ ] Edit any value in the editor → header changes to "Edited" as soon as JSON differs from the fetched content.
- [ ] Click Preview again with edited content — plan is built against the edited tree (verify: whatever you edited now appears in the corresponding step's Body preview).
- [ ] Click **Clear tree** — editor empties. Next Preview fetches fresh.
- [ ] Select **Source = Sandbox, Target = Sandbox** (same env). Preview → plan has a warning note "Source and target are the same environment…".
- [ ] Try Preview without an app ID and empty editor — status shows "Enter an application ID (or paste a tree JSON)…". No plan runs.
- [ ] Paste an arbitrary tree JSON directly into the editor, leave app ID empty, hit Preview — plan runs against the pasted tree.

### 3.36 App Import — Execute confirmation modal *(Phase 3 iteration 5d)*

**Prerequisites:** everything from earlier tests, plus you're ready to actually create rows in your sandbox.

- [ ] Build a plan for a known-good app (Preview import plan). Verify: 0 unresolved keys, expected step count, source/target look right.
- [ ] Click **Execute this plan…** on the plan card.
- [ ] Confirmation modal appears. Verify it shows: profile name, source env → target env, target base URL, source appId, step count, big yellow warning banner.
- [ ] The **Execute now** button is disabled at first (red / "danger" styling when enabled).
- [ ] Type a wrong appId into the confirm input — button stays disabled.
- [ ] Type the exact appId — button enables.
- [ ] Click **Cancel** — modal closes, no request sent.

### 3.37 App Import — actual write to sandbox *(Phase 3 iteration 5d — WRITES)*

**⚠ This test WRITES rows to whatever target env you have selected. Use a throwaway sandbox.**

- [ ] Open the confirmation modal, type the appId, click **Execute now**.
- [ ] Button reads "Executing…". Wait for completion (may take 30 s+ for large trees).
- [ ] Green toast appears: "Import complete — N rows created."
- [ ] Below the plan, an **Import result** card appears: status = "success", N rows created / 0 failed.
- [ ] Expand "Show full journal" — every step is present: source ID, target ID (server-assigned), HTTP 200/201, result = "created".
- [ ] Open `%USERPROFILE%\.devbridge\imports\<profileId>\` — a `<timestamp>-<jobId>.json` file exists with the full journal.
- [ ] Verify in the target env: go to your sandbox app, find the newly-created app (new server-assigned ID). All child entities present. FK values point at the new local IDs, not source IDs.

### 3.38 App Import — halt on failure *(Phase 3 iteration 5d)*

**To reproduce cleanly:** intentionally edit the tree in the editor to include a bogus value that the target sandbox will reject (e.g., a required foreign-key column set to a non-existent DomainValue ID that isn't in your reference-tables list), then Execute.

- [ ] Execution halts on the first failure. Toast: "Import halted after N step(s). See details below."
- [ ] Import result card: status = "failed", errorMessage populated with a clear description.
- [ ] Journal shows the failing step with HTTP status + message, and all prior steps as "created".
- [ ] Journal file on disk matches the on-screen state.
- [ ] Verify in sandbox: rows created BEFORE the failure are present. Rows after aren't. Rollback (iteration 5e) will clean these up later.

### 3.39 Delete App — placeholder view *(nav preview)*

- [ ] Left nav has a new **Delete App** item between Bulk Import and Project Profiles.
- [ ] Clicking it navigates to the view. The view is a placeholder with a "Not yet implemented" note.

### 3.40 Profile system — persistence *(Phase 1b iteration 1)*

- [ ] With ≥1 profile created, stop the Spring Boot app (red square button in IntelliJ)
- [ ] Open `%USERPROFILE%\.devbridge\profiles\` in Explorer — confirm one `<uuid>.json` per profile
- [ ] Open one `.json` in Notepad — pretty-printed JSON with all the fields you entered
- [ ] Restart the app, refresh the browser — Project Profiles view shows the same profiles
- [ ] Sidebar footer shows "None selected" (activation is in-memory, not persisted — this is expected)

### 3.7 Planned features (checklists to be added when built)

- Auth token entry modal
- Test-connection button on profile card
- Module 1 — SQL Runner
- Module 2 — App Import
- Module 3 — Bulk Import

---

## 4. End-to-end scenarios

> Each scenario is a numbered flow across multiple features. Marked ✅ once verified for the current release, ⏳ if planned but not yet possible.

### E1 — First-time onboarding *(Phase 1b iteration 1 — ✅)*

1. Fresh install (no `%USERPROFILE%\.devbridge\` folder).
2. Run the app; open browser.
3. Sidebar shows "None selected".
4. Navigate to Project Profiles → "No profiles yet."
5. Add one profile → card appears.
6. Activate it → sidebar updates.

**Success:** all six steps complete without error; profile persists on disk.

### E2 — Cross-restart persistence *(Phase 1b iteration 1 — ✅)*

1. From a running app with ≥1 profile, stop the Spring Boot process.
2. Restart it, refresh the browser.
3. Profiles view shows the same profiles.
4. Sidebar shows "None selected" (activation cleared).

### E3 — Auth token round-trip *(planned, Phase 1b iteration 2)*

*Fill in when the token holder + FAWB client land.*

### E4 — Golden SQL query & export *(planned, Phase 2)*

*Fill in when Module 1 lands.*

### E5 — Import a known-good app *(planned, Phase 3)*

*Fill in when Module 2 lands.*

### E6 — Bulk import & idempotent resume *(planned, Phase 4)*

*Fill in when Module 3 lands.*

---

## 5. Regression suite (run every release)

Every release runs, at minimum:

1. **§3.1 — backend health** (30 s)
2. **§3.2 — navigation** (30 s)
3. **§3.3–3.6 — profile CRUD end to end** (2 min)
4. Once Module 1 exists: **§3.7 golden SQL query** (1 min)
5. Once Module 2 exists: **§4 E5** (10 min)
6. Once Module 3 exists: **§4 E6** (15 min)

If any step fails, the release does not ship.

---

## 6. How to file a bug

1. Reproduce reliably first — write the steps down.
2. Capture: full IntelliJ Run console output, browser DevTools → Console tab errors, screenshots.
3. Note: OS, JDK version (`java -version`), Spring Boot version (in `pom.xml`), browser + version.
4. If a profile is involved, include the profile JSON (redact any secrets).
5. Open an issue in the project tracker (once git is set up) — or paste the above into a shared doc / DM for now.
