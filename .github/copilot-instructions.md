# GitHub Copilot Instructions — epaper4j

Read `AGENT.md` in the repository root for project context, technology stack, GraalVM notes,
and contributing guidelines.

Key points:

- Java 25 / Pi4J v4 / FFM — no JNI, no legacy Pi4J APIs.
- Register all `FunctionDescriptor` shapes via `RuntimeForeignAccess.registerForDowncall()` in a GraalVM `Feature`.
- `epaper4j-core` hardware drivers must be working before `epaper4j-fx` JavaFX bridge work begins.
