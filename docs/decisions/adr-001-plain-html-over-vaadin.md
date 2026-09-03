# ADR 001 — Plain HTML/CSS/JS over Vaadin

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** [author]

## Context

The frontend needs a UI. Options considered were Vaadin (Java-first, no JS), React + TypeScript, Vue, Thymeleaf + htmx, and plain HTML + CSS + Vanilla JS with a Spring REST backend.

The developer building this project has strong Java + Spring Boot skills and moderate HTML/CSS/JS skills, but no prior React/Vue/TypeScript experience. The tool must be single-user, run on a laptop, and be explainable to interviewers.

The Vaadin skeleton was scaffolded first (as originally recommended) but the developer found the Java-generating-DOM abstraction unfamiliar and preferred writing real HTML/CSS/JS. Vaadin was removed and the frontend rebuilt with vanilla web tech.

## Decision

Use plain HTML + CSS + ES6 modules with a Spring Boot REST backend. Hash-based routing on the frontend. No JS framework.

## Alternatives considered

- **Vaadin** — Pure Java, fastest to a working UI, but the abstraction felt foreign to the developer and adds a non-transferable skill.
- **React + TypeScript** — Modern, most-transferable skill, best visual polish. Rejected because the developer would be learning React + npm + Vite while also learning the domain — too much cognitive load for a solo-owner project.
- **Thymeleaf + htmx** — Server-rendered HTML with declarative interactivity. Solid choice, but adds htmx as a novel concept when plain vanilla JS is already sufficient at this scale.
- **Plain HTML + Vanilla JS (chosen)** — Familiar to the developer, no learning curve, fully explainable in interviews, no build step for frontend.

## Consequences

- **Positive:** Zero JS toolchain to maintain. Every frontend file is a plain source file that renders in-browser without compilation. Explanation-friendly for interviews. All development happens in one IDE without switching between Java and JS build tools.
- **Negative:** We hand-roll UI primitives (forms, modals, grids) instead of using library components. Component reuse across views is patterns-based rather than framework-provided.
- **Neutral:** If Module 1's SQL runner needs a rich data grid, we may add a lightweight standalone grid library (e.g., Grid.js) — not a framework, just a component.

## References

- Related ADRs: [ADR-003 — All-REST architecture](adr-003-all-rest-no-jdbc.md)
