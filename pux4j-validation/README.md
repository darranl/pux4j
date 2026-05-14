# pux4j-validation

Interactive and scripted validation tools for pux4j hardware driver bring-up.

## What is in this module

- DisplaySmokeTest: quick display-only smoke test for frame write and full refresh.
- HardwareValidationTest: interactive 10-step display + touch validation flow.

Both tools run via JPMS and ServiceLoader driver factories from pux4j-core.

Icon attribution and previews are documented in [src/main/resources/icons/ATTRIBUTION.md](src/main/resources/icons/ATTRIBUTION.md).

## Available tests

### 1) DisplaySmokeTest

Purpose:
- Verifies basic display initialization and visible frame output.
- Runs three test frames (half/half, stripes, full white).

When to use:
- First hardware sanity check before touch validation.

Run:
```bash
./pux4j-validation/run-smoke-test.sh
```

Optional driver override:
```bash
./pux4j-validation/run-smoke-test.sh ssd1675a
```

Show script help:
```bash
./pux4j-validation/run-smoke-test.sh --help
```

### 2) HardwareValidationTest

Purpose:
- Full interactive acceptance test for display + touch mapping.
- Uses instruction/challenge/feedback phases.
- Default run executes the first 2 scenarios for stabilization.
- Additional scenarios can be enabled incrementally.
- Writes a validation report file in the working directory:
  - validation-report-YYYYMMDD-HHMMSS.txt

When to use:
- After smoke test passes.
- During touch coordinate mapping calibration.

Run (default profile for Pi 500+ 2.9" V2):
```bash
./pux4j-validation/run-hardware-validation.sh
```

## Hardware profiles (one-command run)

The run script supports profiles so you do not need to pass long argument lists.

Profiles:
- pi500-2in9 (default):
  - display=ssd1675a
  - touch=icnt86x
  - orientation=LANDSCAPE
  - touch-native-width=296
  - touch-native-height=128
- little-2in13:
  - display=ssd1680
  - touch=gt1151q
  - orientation=PORTRAIT
- custom:
  - no preset arguments (you pass everything)

Examples:
```bash
# Default profile (pi500-2in9)
./pux4j-validation/run-hardware-validation.sh

# Explicit profile
./pux4j-validation/run-hardware-validation.sh pi500-2in9
./pux4j-validation/run-hardware-validation.sh little-2in13

# Add notes to report
./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --notes "baseline calibration run"

# Run first 4 scenarios
./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --scenario-count 4

# Run all available scenarios
./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --all-scenarios

# Enable partial-refresh prompts (default is full refresh prompts)
./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --use-partial-prompts

# Fully custom invocation
./pux4j-validation/run-hardware-validation.sh custom -- \
  --display ssd1675a \
  --touch icnt86x \
  --orientation LANDSCAPE \
  --touch-native-width 296 \
  --touch-native-height 128 \
  --flip-x
```

Show script help:
```bash
./pux4j-validation/run-hardware-validation.sh --help
```

## Setup and first run

From repository root (pux4j-ui):

```bash
mvn -pl pux4j-validation -am -DskipTests package
mvn -pl pux4j-validation -DincludeScope=runtime dependency:copy-dependencies -DoutputDirectory=target/run-lib
```

Then run:
```bash
./pux4j-validation/run-hardware-validation.sh
```

The launcher scripts refresh artifacts automatically when module sources/resources are newer than the built JAR.
To force refresh manually:

```bash
FORCE_PREPARE=1 ./pux4j-validation/run-hardware-validation.sh
FORCE_PREPARE=1 ./pux4j-validation/run-smoke-test.sh
```

Note:
- Both run scripts auto-run build/dependency preparation when artifacts are missing.
- If you prefer to skip this auto-prepare behavior, set SKIP_PREPARE=1.
- Hardware validation defaults to 2 scenarios for stability; use `--scenario-count` or `--all-scenarios` to expand coverage.

## Native access requirement

Pi4J FFM backend requires native access enabled for module com.pi4j.plugin.ffm.
Both run scripts already include:
- --enable-native-access=com.pi4j.plugin.ffm

## Practical workflow

1. Run smoke test.
2. Run hardware validation with the hardware profile.
3. If taps miss targets, rerun with custom touch mapping flags.
4. Keep the generated report as evidence of pass/fail and calibration status.

## Hardware trace workflow

The driver's SPI command and data writes can be traced live using ByteMan without
modifying production code. The rules live in
`pux4j-core/src/main/byteman/hardware-trace.btm`.

```bash
# Run smoke test with SPI-level trace printed to stdout
./pux4j-validation/run-hardware-trace.sh

# Filter trace lines only
./pux4j-validation/run-hardware-trace.sh 2>&1 | grep '\[HW-TRACE\]'

# Capture to file
./pux4j-validation/run-hardware-trace.sh 2>&1 | tee /tmp/hw-trace.log

# Type-check rules only (no hardware required)
./pux4j-validation/run-hardware-trace.sh check
```

Each [HW-TRACE] line is one of:
- `CMD  0xNN (SYMBOLIC_NAME) data[N]=<hex>` — a sendCommand call
- `DATA len=N preview=<first 16 bytes hex>...` — a sendData call

Command bytes are annotated with their symbolic names (e.g. `WRITE_BW_RAM`,
`WRITE_LUT`, `DISP_UPDATE_2`) via `CmdNames` in the driver package.

Default ByteMan install location: `~/development/byteman-download-4.0.26`.
Override with the `BYTEMAN_HOME` environment variable.

