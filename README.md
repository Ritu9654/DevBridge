# DevBridge

A developer productivity tool for WaveMaker-derived low-code platforms.

Configure once per project via a project profile, then use the tool for:

- Free-form SQL execution with downloadable results (CSV / Excel / JSON)
- Copying a single application from a source environment to a sandbox
- Bulk-importing N applications for testing and analytics

## Stack

- **Backend:** Spring Boot 3.3.5 on Java 17
- **Frontend:** Plain HTML + CSS + Vanilla JS (ES6 modules, hash-based routing)
- **Build:** Maven

## Requirements

- Java 17 or newer (any JDK — Oracle, Temurin, Corretto, etc.)
- Maven 3.8+

## Run

```bash
mvn spring-boot:run
```

Then open http://localhost:8080

## Project layout

```
src/main/java/com/devbridge/
  DevBridgeApplication.java              Spring Boot entry point
  api/
    HealthController.java                GET /api/health — round-trip test

src/main/resources/
  application.properties                 Spring config
  static/                                Everything the browser downloads
    index.html                           App shell with sidebar
    css/main.css                         All styles
    js/
      main.js                            Entry point + hash router + health check
      router.js                          Route table (path -> view module)
      api.js                             fetch() wrapper for /api/* calls
      views/                             One module per screen
        home.js
        sql-runner.js
        app-import.js
        bulk-import.js
        profiles.js
        settings.js
```

## How it works

- Spring Boot serves the static frontend from `src/main/resources/static/` and exposes REST APIs under `/api/*`.
- The browser loads `index.html`, which loads `main.js` as an ES6 module.
- `main.js` listens to `hashchange`, looks up the current route in `router.js`, and injects the view's HTML into `#view-container`.
- Views are plain JS modules that export `{ title, render(): string, mount?(): void }`.
- All backend calls go through `api.js`, which is a thin `fetch()` wrapper.

## Status

Early development. Currently a skeleton with placeholder views and a health-check API to prove the round trip works.
