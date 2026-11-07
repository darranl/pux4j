# pux4j-validation

Interactive and scripted validation tools for pux4j hardware driver bring-up.

## What is in this module

- `DisplaySmokeTest`: quick display-only smoke test for frame write and full refresh.
- `HardwareValidationTest`: interactive 10-step display + touch validation flow.

Both test the raw drivers directly, without the JavaFX bridge, through the `ServiceLoader` driver
factories from `pux4j-core`. They are deliberately not JavaFX apps. The launcher lists them as
`smoke` and `validation` (the thin `DriverApp` wrappers `SmokeDriverApp` and `ValidationDriverApp`
register them), and their static `main` methods are unchanged, so native images can call them
directly.

Icon attribution and previews are documented in [src/main/resources/icons/ATTRIBUTION.md](src/main/resources/icons/ATTRIBUTION.md).

## Running them

Build first (from the repository root, `mvn install`; for a Pi `mvn package -Pbundle-hat-...`, see
the [main README](../README.md)). The tests are run through the launcher.

**On a laptop** (against the JavaFX emulator; mouse press = touch down, release = touch up):

```bash
pux4j-bundle/run-emulator.sh smoke
pux4j-bundle/run-emulator.sh validation
pux4j-bundle/run-emulator.sh validation -Dpux4j.emulator.display=ssd1680 -Dpux4j.emulator.scale=4
pux4j-bundle/run-emulator.sh validation --start-step 3
```

The emulator profiles (`ssd1675a`, 296×128, 2.9" V2, the default; `ssd1680`, 250×122, 2.13" V4) are
described in the [main README](../README.md#emulating-different-hardware).

**On a Raspberry Pi** (against the attached HAT; SPI and I2C must be enabled):

```bash
pux4j smoke
pux4j validation
```

Options after the app name go to the test. `-D...` arguments are JVM properties and are handled by
the launcher.

## Available tests

### 1) DisplaySmokeTest (`smoke`)

- Verifies basic display initialization and visible frame output.
- Runs test frames (half/half, stripes, full white).
- Optional first argument: the display driver name, for example `pux4j smoke ssd1675a`.
- Use it as the first hardware sanity check, before touch validation.

### 2) HardwareValidationTest (`validation`)

- Full interactive acceptance test for display and touch mapping, using instruction, challenge and
  feedback phases. It checks that the display is the right way up and that touch coordinates line up
  with what is drawn.
- By default it runs all scenarios; `--scenario-count <n>` limits the run to the first n.
- Writes a report named `validation-report-YYYYMMDD-HHMMSS.txt` into the directory you ran it from.
- Use it after the smoke test passes, and when checking touch coordinate mapping.

Options:

| Option | Meaning |
|---|---|
| `--start-step <n>` | Jump to step n |
| `--scenario-count <n>` / `--all-scenarios` | How many scenarios to run (default: all) |
| `--notes "text"` | Text recorded in the report |
| `--display <driver>`, `--touch <driver>` | Choose drivers by name instead of auto-selection |
| `--orientation <name>` | Explicit orientation, for drivers with no fixed physical orientation (for example `--display png`) |
| `--dc-pin`, `--rst-pin`, `--busy-pin`, `--touch-rst-pin`, `--touch-int-pin`, `--touch-i2c-address` | Override a pin or the touch I2C address; each is written to the driver configuration only when given |

Any other option is rejected with "Unknown argument".

Orientation (`LANDSCAPE` vs `LANDSCAPE_INVERTED`) and touch calibration (native resolution, axis
flips and swap) are not options: each display driver reports its own fixed physical orientation
(`DisplayDriverFactory.physicalOrientation`) and each touch driver its own fixed calibration for the
panel it is bonded to (`TouchDriverFactory.touchCalibration`), so there is nothing to pass or to get
out of sync. Pin and I2C address defaults likewise come from the drivers.

## Practical workflow

1. Run `smoke`.
2. Run `validation`.
3. If taps miss their targets, the touch calibration in the HAT's driver module needs attention;
   keep the generated report as evidence of pass/fail and calibration status.

## Hardware trace workflow

The driver's SPI command and data writes can be traced live with ByteMan without modifying
production code. The rules are in [`src/main/byteman/`](src/main/byteman) and are shipped in the
Pi bundle's `byteman/` directory. The ByteMan agent jar itself is not shipped: install ByteMan on
the Pi and pass the agent through `PUX4J_JAVA_OPTS`:

```bash
# on the Pi, with BYTEMAN_HOME pointing at a ByteMan installation
export PUX4J_JAVA_OPTS="-javaagent:$BYTEMAN_HOME/lib/byteman.jar=script:$HOME/applications/pux4j/byteman/hardware-trace.btm"
pux4j smoke
pux4j smoke 2>&1 | grep '\[HW-TRACE\]'        # trace lines only
```

Rule files: `hardware-trace.btm` (SPI commands and data), `ioctl-trace.btm` (ioctl-level) and
`partial-corruption-check.btm`. Several can be combined, comma separated:
`=script:a.btm,script:b.btm`. Each `[HW-TRACE]` line is one of:

- `CMD  0xNN (SYMBOLIC_NAME) data[N]=<hex>`: a `sendCommand` call
- `DATA len=N preview=<first 16 bytes hex>...`: a `sendData` call

Command bytes are annotated with their symbolic names (for example `WRITE_BW_RAM`, `WRITE_LUT`,
`DISP_UPDATE_2`).
