# AGENT.md — pux4j

Canonical context document for AI agents working in this repository.
`CLAUDE.md` and `.github/copilot-instructions.md` both reference this file. Read it first.

---

## What this project is

`pux4j` is an open-source Java 25 library for driving WaveShare eInk displays on
Raspberry Pi and integrating them with JavaFX applications.

| Module | Purpose |
|---|---|
| `pux4j-core` | Pi4J SPI/I2C hardware drivers for eInk displays and touch controllers; no JavaFX dependency |
| `pux4j-fx` | JavaFX bridge — intercepts scene rendering, pushes to the eInk display; translates touch events to `MouseEvent`s |

## Supported hardware

Targets Raspberry Pi running **Pi OS 12 (Debian bookworm, aarch64)**. Developed and tested on
Raspberry Pi 4B, Pi Zero 2 W, and Pi 500+.

Designed to support multiple WaveShare eInk panels via a common `EInkDisplay` interface.
Specific supported modules are documented in the individual driver README files.

## Technology stack

- **Java 25**, Foreign Function & Memory API (Project Panama) — no JNI
- **Pi4J v4** with `pi4j-plugin-ffm` backend; SPI and I2C providers
- **GraalVM native image** — AOT compilation for Raspberry Pi targets
- **JavaFX** — `pux4j-fx` module only
- **Maven** multi-module build

## GraalVM native image notes

Cross-compiling from x86_64 to aarch64:

- Add `-J-Djdk.internal.foreign.CABI=LINUX_AARCH_64` to the native Maven profile `<buildArgs>`.
  Without this, `CABI.computeCurrent()` returns the host ABI and `ForeignFunctionsFeature`
  throws `ClassCastException` during analysis.
- Every `FunctionDescriptor` shape used at runtime must be registered via
  `RuntimeForeignAccess.registerForDowncall()` in a GraalVM `Feature` at build time.
  Omitting this causes `MissingRegistrationError` in the native binary.
- Use `native-image-agent` to generate `reachability-metadata.json` rather than writing it
  by hand. Key points:
  - `ADDRESS` layouts must be represented as `"void*"`, not `"long"`.
  - `captureCallState("errno")` prepends an extra `MemorySegment` to the stub signature;
    the registered descriptor must match.

## Information Required protocol

Before beginning implementation of any phase, check `notes/project-plan.md` in the parent
`waveshare-integration-project` repository for the Information Required section of that
phase. Verify each item is present on the current machine; ask if anything is missing.

Key checks:
- **DeepWiki MCP** — use to query Pi4J v4, JavaFX, and GraalVM APIs directly.
  If not installed, flag it before beginning Phase 1.
- **`red-amber-graal/`** — primary reference for Pi4J v4 FFM usage patterns.
  Verify present and builds before any Pi4J driver work.

## Contributing guidelines

- **FFM over JNI** — use the Foreign Function & Memory API for all hardware access.
- **Pi4J as the hardware abstraction** — prefer Pi4J SPI/I2C providers; drop to raw FFM
  only if Pi4J cannot cover a specific low-level requirement.
- **Hardware POC first** — `pux4j-core` drivers must be working before `pux4j-fx`
  JavaFX bridge work begins.
- **Interface-driven** — `EInkDisplay` and `TouchSensor` interfaces enable a virtual
  PNG-rendering driver for headless/CI testing without physical hardware.
- **Logging** — all modules use SLF4J as the logging facade. No `System.out` or
  `System.err` anywhere in production or test code. Runtime binding is Logback unless
  there is a specific reason to change it.
- **JPMS** — every module has `module-info.java` from the outset. Default to unexported;
  only add `exports` when a package is deliberately part of the public API. Use
  `ServiceLoader` (`provides`/`uses`) for runtime driver selection.
- **Virtual threads** — prefer `Thread.ofVirtual()` for I/O-bound threads (display write,
  touch polling). Note: FFM native calls may briefly pin a carrier thread; this is
  acceptable for short-lived native operations.
- **Minimum public API** — if it is not proven to be needed by another module, keep it
  internal. It is easier to open things up later than to take them back.
