# Input-event mapping: touch → JavaFX events

**Status: agreed design, not yet implemented.** Until it is, `EInkBridge` still emits only the four
synthesized mouse events described under "Today".

`EInkBridge` turns touch contacts from a panel into JavaFX input events. The rule is
**fidelity to JavaFX**: an ordinary JavaFX application, unaware of eInk, should see the events and
state it would see on a touchscreen JavaFX platform, so its handlers need no bridge-specific
changes. Where the bridge deviates from JavaFX, this document says so and gives the reason; a
deviation without a reason here is a bug. "Standard JavaFX app" means: one that reacts to the
events marked **emit** below.

## How JavaFX does it (reference)

Read from the OpenJFX 27 sources (`Scene.java`, `Node.java`, `TouchEvent`/`TouchPoint`); the project
builds against 23.0.1 and this machinery is long-standing. Native platform code (glass/Monocle)
could not be read; claims that depend on it are marked **assumption**.

- **Dispatch.** `Scene` delivers every mouse event with `Event.fireEvent`, exactly as the bridge
  does. Every node's dispatch chain starts with `Node.preprocessMouseEvent`, which sets `:pressed`
  on the target *and all its ancestors* on PRESSED/RELEASED and `:hover` on `*_ENTERED`/`*_EXITED`
  when the node is the target — unless the event was consumed first. So `:pressed`/`:hover` are
  reproducible through public API.
- **Enter/exit.** `Scene.MouseHandler` posts only `MOUSE_ENTERED_TARGET`/`MOUSE_EXITED_TARGET`, one
  per node in the target hierarchy including the `Scene`, outermost first. A node's own plain
  `MOUSE_ENTERED`/`EXITED` is derived from the `_TARGET` event inside that node's dispatcher, so
  ancestors' `onMouseEntered` handlers do not fire for a child.
- **Click.** `MOUSE_CLICKED` is fired at the deepest common ancestor of the press and release
  hierarchies (which includes the `Scene`). There is **no distance cutoff**. The toolkit's
  multi-click distance/time only drive `clickCount` and `stillSincePress`.
- **Drag detection.** `DRAG_DETECTED` is fired once per gesture at the press target, on the first
  drag beyond a small hysteresis.
- **Touch set.** One `TouchEvent` per touch point, each carrying all points of the set
  (`getTouchPoints()`), a shared `getEventSetId()` (incremented per set, reset to 0 after the last
  contact lifts), and the point's state. Touch-point ids are small integers handed out per gesture
  (1, 2, …) and not reused until every contact has lifted. A point with no node under it targets
  the `Scene`.
- **Grab.** A point is delivered to the node it was pressed on, wherever it moves — but handlers may
  call `TouchPoint.grab(node)`, `grab()` or `ungrab()`; `Scene` reads the grabbed target after each
  set and re-picks every event for an ungrabbed point.
- **Assumptions (native code, not in `Scene`):** touch and synthesized-mouse events are two
  independent streams; consuming one does not cancel the other; touch precedes mouse; only the
  primary contact synthesizes mouse events; `TOUCH_STATIONARY` is reported for a contact that did
  not move while another changed (`Scene` merely maps the state the platform reports).

## Today (before this design is implemented)

`MOUSE_PRESSED/DRAGGED/RELEASED/CLICKED` at the picked node for **every** contact, `synthesized =
true`, `clickCount = 1`, `stillSincePress = true`, `PickResult` fixed to the pressed node; nothing
fired when no node is hit; a click only when released on the pressed node within 4 px. No
`TouchEvent`s, no enter/exit, no `DRAG_DETECTED`.

## Mapping

| JavaFX event | Emit? | Notes |
|---|---|---|
| `TouchEvent` `TOUCH_PRESSED` / `MOVED` / `RELEASED` / `STATIONARY` | **Emit** (new) | Per the touch-set rules above. `PickResult` at the contact's current position. Handler `grab`/`ungrab` honoured. |
| `MOUSE_PRESSED` / `DRAGGED` / `RELEASED` | **Emit** (exists, narrowed) | Synthesized from the **primary contact only** (assumption above); secondary contacts yield touch events only. `PickResult` at the current position, not the pressed node. |
| `MOUSE_CLICKED` | **Emit**, JavaFX rule | At the deepest common ancestor of press and release hierarchies; no distance cutoff. `clickCount` and `stillSincePress` tracked as JavaFX does (below). **Changes the 4 px rule — see deviations.** |
| `DRAG_DETECTED` | **Emit** (new) | Once per gesture at the press target, first drag beyond the hysteresis. Needed for `setOnDragDetected`/`startFullDrag` apps. |
| `MOUSE_ENTERED_TARGET` / `MOUSE_EXITED_TARGET` | **Emit** (new, D1) | Per node in the target hierarchy (incl. `Scene`), outermost first, entered before `PRESSED`, exited after `CLICKED`. `_TARGET` only — plain `MOUSE_ENTERED`/`EXITED` is derived by each node's dispatcher, never fired by the bridge. Gives `:hover` for the duration of the touch. |
| `:pressed` / `:hover` | Reproduced | Via the dispatch-chain preprocessing above; nothing extra to do, provided events are not consumed (same as JavaFX). |
| `MOUSE_MOVED` | Not emitted | A touch contact does not hover; JavaFX sends none without a pointer. Faithful. |
| Events with nothing under the contact | **Emit at the `Scene`** (changed) | JavaFX targets the `Scene`; scene-level handlers (`scene.setOnMousePressed`) fire. Today dropped (`releaseContact` returns early too). |
| Pressed node removed mid-gesture | **Handle** (new) | JavaFX moves the target to the nearest remaining ancestor and clears `:pressed`. The bridge must do the same instead of firing at a detached node. |
| Disabled / invisible / mouse-transparent nodes | Faithful | `ScenePicker` already skips them as `Node.pickNode` does. |
| `MouseDragEvent` (full press-drag-release), drag-and-drop | Not emitted — **deviation** | Needs the internal `Scene` handler (D2). `startFullDrag` apps are not supported. |
| `ScrollEvent` / `SwipeEvent` / `ZoomEvent` / `RotateEvent` (direct gestures) | Not emitted — **deviation** | On real JavaFX these come from the platform's gesture recogniser, not the scene; there is nothing in `Scene` to align with, so a recogniser would be a new design. Add when an app on a panel this size needs one. |
| `ContextMenuEvent` (long press) | Not emitted — **deviation** (D3) | Also platform-generated; needs a hold timer, and a panel refresh takes ~0.5–1 s, so the user cannot see the menu appear in time to trust the gesture. |
| `KeyEvent`, key focus traversal, text input | Out of scope | No keyboard. Focus follows `requestFocus()` calls made by controls on mouse press, which works. |

### `clickCount`, `stillSincePress`

Tracked as `Scene.ClickGenerator` does: a counter incremented on each press that follows the
previous one within the multi-click time and distance, reset otherwise; `stillSincePress` is false
once the contact has moved beyond the multi-click distance since press. JavaFX takes the time and
distance from the platform toolkit; the bridge has no toolkit, so it uses configurable defaults
(500 ms, 2 px — to be confirmed against the platform values when implemented). The time is measured
with a monotonic clock, not a JavaFX `Timeline`, because the bridge may run without pulses.

### Ordering for one tap

Per poll batch, per contact (the touch-before-mouse order is an assumption; apps must not depend
on it):

1. `MOUSE_ENTERED_TARGET` chain; `TOUCH_PRESSED`; `MOUSE_PRESSED` (primary only).
2. Held and moving: `TOUCH_MOVED` (others `TOUCH_STATIONARY`); `MOUSE_DRAGGED`; `DRAG_DETECTED` once.
3. Lift: `TOUCH_RELEASED`; `MOUSE_RELEASED`; `MOUSE_CLICKED`; `MOUSE_EXITED_TARGET` chain.

### Touch sets and ids

- Raw contact ids from the driver (the GT1151Q reuses and re-numbers them) are **remapped** to
  JavaFX-style ids: 1, 2, … within a gesture, not reused until every contact has lifted.
- Each event gets a **fresh** `TouchPoint` list. `TouchEvent.copyFor` mutates shared points in place
  and the code that resets them (`TouchPointHelper`) is internal.
- A lift **inferred** from a contact missing in a report (GT1151Q) goes into the *same* set as the
  rest of that poll; an empty poll with only inferred releases still produces a set of releases;
  no set is emitted when nothing changed.
- Event-set id: increments per set, reset to 0 after the last contact lifts.
- After each set, read `getGrabbed()` back from every copy and merge grab changes into the bridge's
  per-contact state.

## Deviations from JavaFX, and why

| Deviation | Reason |
|---|---|
| No `MouseDragEvent`/drag-and-drop | Only produced by the internal `Scene` mouse handler (D2); no public equivalent. |
| No gesture events, no `ContextMenuEvent` | Platform-generated, not scene behaviour; designing recognisers/timers is out of proportion until an app needs one (D3). |
| Multi-click time/distance are configurable defaults, not toolkit values | The bridge runs without a toolkit/stage. |
| Controls behave as on a desktop | `ScrollPane` etc. consume `TOUCH_*` and switch on the platform's `IS_TOUCH_SUPPORTED`, which is false for this bridge. Whatever events arrive, those controls follow the desktop path. |
| Touch-before-mouse order and primary-only mouse synthesis | Chosen from the assumptions above; the real order is decided in native code. |

## Decisions

- **Fidelity rule:** align with JavaFX unless a deviation is documented with its exact reason
  (table above). This replaces the earlier 4 px click rule.
- **D1 — enter/exit:** emit `*_TARGET` chains around each contact. *Agreed.*
- **D2 — internal `SceneHelper` route for mouse events:** **not used in production code.** It
  would add genuine drag/click/removal handling but depends on JavaFX internals (`--add-exports`
  at build, run and native-image time, version-fragile) in a public library that uses only public
  API, and it cannot cover touch (`processTouchEvent` is private). **Use it in tests only**, as an
  oracle: feed the same contact sequence to a real `Scene` via `SceneHelper.processMouseEvent` and
  compare event traces with the bridge's (surefire `--add-exports`).
- **D3 — long press:** deferred, documented deviation.

## Testing

The implementation should be covered by unit tests (display-free where possible), plus oracle
comparison in test scope as described under D2:

- Event order per tap; `_TARGET` chains outermost-first; no plain `MOUSE_ENTERED` fired by the bridge.
- `:pressed` on target and ancestors, `:hover` during touch and cleared after.
- A real `Button` fires `onAction`; its `:pressed`/armed state follows the contact.
- Click at the common ancestor when press and release land on different nodes; no distance cutoff;
  `clickCount` for a double tap; `stillSincePress` false after a drag.
- `DRAG_DETECTED` once per gesture.
- Multi-contact sets: id remapping, ids restarting at 1 after all lift, shared set id and its
  reset, `TOUCH_STATIONARY`, mouse from the primary contact only.
- Inferred lifts (GT1151Q path) inside a touch set; an empty poll with only releases.
- Handler `grab`/`ungrab`; consumed events.
- Contact over nothing targets the `Scene` (touch and mouse); pressed node removed mid-gesture.
- A node handling both touch and mouse (filtering on `isSynthesized()`) sees both streams.
- Headless dice check: tapping a die toggles through the app's unchanged `setOnMouseClicked`.
