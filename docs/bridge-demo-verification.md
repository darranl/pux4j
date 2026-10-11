# Verifying the counter demo on `EInkBridge`

How to check the counter demo hosted on `EInkBridge` — headless, in the emulator, and on a real
e-paper HAT — and what you should see at each stage.

All commands run from the repository root. Two panel geometries are supported:

| Panel | Logical size | Emulator profile |
|---|---|---|
| 2.9" HAT (SSD1675A) | 296×128 | `ssd1675a` (default) |
| 2.13" HAT (SSD1680) | 250×122 | `ssd1680` |

## 1. Headless check (automated)

```bash
./pux4j-bridge-smoke/run-demo-check.sh
```

Hosts the real counter demo on the PNG display driver plus the programmatic touch driver at both
panel sizes, taps the real "+" and "−" buttons, and exits `0` on success. It asserts that the
layout fits the scene and is ordered `−` | number | `+`; that the first write is a FULL refresh;
that tapping the centre of "+" increments and "−" decrements (so touch coordinates agree with the
rendered frame); and that "−" is disabled at 0. Frames are written to
`pux4j-bridge-smoke/target/demo-bridge-check/<panel>/frame-NNNN.png`.

**Open the PNGs** — the script cannot judge looks. Expected on both panels:

| Frame | Shows |
|---|---|
| `frame-0001.png` | Large black `0` in the middle and a black-bordered `+` box on the **right**. The `−` box on the left is **invisible**: it is disabled, and its grey border is thresholded to white on a monochrome panel (a known limitation of the current disabled style). |
| `frame-0002.png` | `1` in the middle; the `−` box is now visible on the left. |
| `frame-0003.png` | Back to `0`; `−` invisible again. |

Upside-down, mirrored, or `+`/`−` swapped means an orientation bug.

## 2. Emulator (manual, needs a desktop)

The headless run proves the logic; this checks a real window with mouse input.

```bash
./pux4j-host/run-emulator.sh -Dpux4j.emulator.display=ssd1680 -Dpux4j.emulator.scale=4
```

Omit the `-Dpux4j.emulator.display` property for the 2.9" profile. A panel-shaped window opens and
the mouse acts as the finger. Check:

1. The window shows `0` and a `+` box on the right; `−` is invisible or faint grey.
2. Click `+`: the number becomes `1` and `−` appears. Keep clicking up to `10`; `+` is then disabled.
3. Click `−` back down to `0`.
4. Click the number in the middle: nothing changes.
5. The layout fills the window edge to edge, with no clipped number and no overlapping boxes.
6. Close the window. Known issue: shutdown can stall for up to ~10 s, because the close handler
   calls `System.exit(0)` on the JavaFX thread while the bridge is stopping. Ctrl-C in the terminal
   exits cleanly.

## 3. Expected behaviour on a real HAT

Running on a Pi needs a hardware bundle (HAT drivers instead of the emulator), which is not
available yet; these instructions will be added here when it is. What to expect when it runs:

- **Start-up:** one visible full-panel flash (FULL refresh), then the static frame from the table
  above. Monochrome, no greys; the `−` box is invisible.
- **Tapping `+`:** a PARTIAL refresh (no full flash) that updates the number and reveals `−`.
  Roughly a second of latency or less is normal for e-paper.
- **Handedness:** touching the **right** box must be the one that increments. Swapped left/right or
  up/down means the touch mapping or orientation is wrong (the 2.13" panel is mounted
  `LANDSCAPE_INVERTED`).
- **One tap, one change:** a tap should change the number by exactly one.
- Some ghosting after many partial updates is normal e-paper behaviour.

### Known risk: GT1151Q "no new report" read as a lift

`Gt1151qTouchDriver.readTouches()` returns an empty list whenever the INT pin is high ("no new
report"). The bridge polls every 10 ms and treats a contact missing from a poll as a lift, so a
finger resting on the panel between the chip's reports can produce release, re-press and another
click. Symptom: one tap increments the counter by 2 or more.
