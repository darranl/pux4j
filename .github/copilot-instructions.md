# GitHub Copilot Instructions — pux4j

Use this repository's files as the source of truth.

1. Read `AGENT.md` in this repository root first.
2. Keep this repository standalone: do not require or assume any parent repository files.
3. If parent-level files are present in a larger private workspace, treat them as optional context only.

Keep this file as a thin Copilot-specific wrapper that references `AGENT.md`.

Key technical reminders:

- Java 25 / Pi4J v4 / FFM — no JNI, no legacy Pi4J APIs.
- Register all `FunctionDescriptor` shapes via `RuntimeForeignAccess.registerForDowncall()` in a GraalVM `Feature`.
- `pux4j-core` hardware drivers must be working before `pux4j-fx` JavaFX bridge work begins.
