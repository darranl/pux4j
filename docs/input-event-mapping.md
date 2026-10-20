# Input-event mapping: touch → JavaFX events

**Status: the mouse stream is implemented; `TouchEvent` sets are not yet.** The design was revised
after a review against the Monocle and `Scene` sources. Until touch sets land, `EInkBridge` emits the
mouse events below but no `TouchEvent`s.

`EInkBridge` turns touch contacts from a panel into JavaFX input events. The rule is
**fidelity to JavaFX**: an ordinary JavaFX application, unaware of eInk, should see the events and
state it would see on a touchscreen JavaFX platform, so its handlers need no bridge-specific
changes. Where the bridge deviates from JavaFX, this document says so and gives the reason; a
deviation without a reason here is a bug. "Standard JavaFX app" means: one that reacts to the
events marked **emit** below.

## How JavaFX does it (reference)

Read from the OpenJFX 27 sources (`Scene.java`, `Node.java`, `TouchEvent`/`TouchPoint`); the project
builds against 23.0.1 and this machinery is long-standing. What happens *before* the `Scene`
(touch-set construction, mouse synthesis from touch) is platform code. The embedded touch port,
**Monocle** (`com.sun.glass.ui.monocle`, Java source, `TouchInput`/`MouseInputSynthesizer`/
`SmallMoveTouchFilter`), is the closest model for a touch panel and was read; the desktop ports
(GTK, Windows, macOS) were not, and may differ.

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
  drag where |dx| or |dy| from the press point exceeds the toolkit's drag hysteresis (per axis, not
  a straight-line distance). A handler can suppress it with `setDragDetect(false)`/force it with
  `true`; the flag is shared by the event copies, so it can be read back after `fireEvent`.
- **Hover while pressed.** While a press is held, `Scene` enters/exits only nodes in the *press*
  hierarchy. Dragging off a `Button` therefore exits it (clearing `:hover` and disarming it), and
  dragging back re-enters it.
- **Touch set.** One `TouchEvent` per touch point, each carrying all points of the set
  (`getTouchPoints()`), a shared `getEventSetId()` (incremented per set, reset to 0 after the last
  contact lifts), and the point's state. Touch-point ids are small integers handed out per gesture
  (1, 2, …) and not reused until every contact has lifted. A point with no node under it targets
  the `Scene`.
- **Grab.** A point is delivered to the node it was pressed on, wherever it moves — but handlers may
  call `TouchPoint.grab(node)`, `grab()` or `ungrab()`; `Scene` reads the grabbed target after each
  set and re-picks every event for an ungrabbed point.
- **Platform behaviour (Monocle, read).**
  - *Order:* `TouchInput.setState` posts the whole touch set first, then synthesizes the mouse
    events. Touch events and synthesized mouse events are independent streams; consuming one does
    not cancel the other.
  - *Primary contact:* the lowest-id point is the primary. When it lifts, the primary is
    **reassigned** to a remaining contact (`TouchState.assignPrimaryID`). The mouse button is
    pressed while *any* contact is down and released only when the **last** contact lifts
    (`MouseInputSynthesizer`); the mouse position follows the current primary.
  - *Mouse move:* a mouse move to the new position (`MOUSE_MOVED`, or `MOUSE_DRAGGED` while the
    button is down) precedes the press when the pointer is not already there.
  - *Touch point states:* `TOUCH_STATIONARY` is reported for a contact that did not move while
    another changed. `SmallMoveTouchFilter` turns moves within a radius (default 20 px) into
    "no move", so jitter does not generate drag events.
  - *Multi-click:* 500 ms and 20 px on each axis by default; the same distance feeds click
    counting and `stillSincePress`.

## Today

The mouse stream follows this document: one pointer from the primary contact, `MOUSE_MOVED`,
`PRESSED`, `DRAGGED`, `DRAG_DETECTED`, `RELEASED`, `CLICKED` and the `_TARGET` enter/exit chains, with
the `Scene` as the target over empty space. No `TouchEvent`s yet (touch sets and grabs are the
remaining piece).

## Mapping

| JavaFX event | Emit? | Notes |
|---|---|---|
| `TouchEvent` `TOUCH_PRESSED` / `MOVED` / `RELEASED` / `STATIONARY` | **Emit** (new) | Per the touch-set rules above. `PickResult` at the contact's current position. Handler `grab`/`ungrab` honoured. |
| `MOUSE_PRESSED` / `DRAGGED` / `RELEASED` | **Emit** (exists, reshaped) | Synthesized as Monocle does: one mouse "pointer" that follows the **primary contact** (lowest remaining id, reassigned when it lifts). `PRESSED` on the first contact down, `RELEASED` only when the **last** contact lifts; secondary contacts yield touch events only. `PickResult` at the current position, not the pressed node. |
| `MOUSE_CLICKED` | **Emit**, JavaFX rule | At the deepest common ancestor of press and release hierarchies; no distance cutoff. `clickCount` and `stillSincePress` tracked as JavaFX does (below). **Changes the 4 px rule — see deviations.** |
| `DRAG_DETECTED` | **Emit** (new) | Once per gesture at the press target, first drag beyond the hysteresis. Needed for `setOnDragDetected`/`startFullDrag` apps. |
| `MOUSE_ENTERED_TARGET` / `MOUSE_EXITED_TARGET` | **Emit** (new, D1) | Per node in the target hierarchy (incl. `Scene`), outermost first, entered before `PRESSED`, exited after `CLICKED`. `_TARGET` only — plain `MOUSE_ENTERED`/`EXITED` is derived by each node's dispatcher, never fired by the bridge. Gives `:hover` for the duration of the touch. |
| `:pressed` / `:hover` | Reproduced | Via the dispatch-chain preprocessing above; nothing extra to do, provided events are not consumed (same as JavaFX). |
| `MOUSE_MOVED` | **Emit** (new) | Monocle moves the pointer to a contact's position before pressing; the bridge fires one at the new primary position before `MOUSE_PRESSED`, only when the position changed (`button = NONE`, `clickCount = 0`, carrying the previous gesture's `stillSincePress` — false if this move leaves the 20 px box around that press, as `ClickCounter.moved` is applied first). While the button is held a move is `MOUSE_DRAGGED`, including when the primary is reassigned. Never fired between gestures — a lifted finger leaves no pointer. |
| Events with nothing under the contact | **Emit at the `Scene`** (changed) | JavaFX targets the `Scene`; scene-level handlers (`scene.setOnMousePressed`) fire. The `PickResult` has no node. |
| Pressed node removed mid-gesture | **Handle** (new) | JavaFX sends `MOUSE_EXITED_TARGET` to the entered nodes up to the removed one (innermost first), clears `:pressed` (an internal setter) and retargets to the nearest remaining ancestor. The bridge does the same except `:pressed` on the removed nodes, which it cannot clear through public API; they are out of the scene, so nothing renders it, and it is not re-sent to them. Detected on the next event, structurally: a node in the recorded press hierarchy that is no longer a child of the next one (so re-parenting counts as removal, as in JavaFX), or a root that is no longer the scene's root. `toFront()`/`toBack()` do not count. A node that becomes disabled or invisible mid-press stays the press target, as in JavaFX. |
| Disabled / invisible / mouse-transparent nodes, `viewOrder`, clips | Faithful | `ScenePicker` skips them as `Node.pickNode` does, orders siblings by `viewOrder` then position, and excludes a clipped node's subtree outside its clip. 3D picking is not modelled. |
| `MouseDragEvent` (full press-drag-release), drag-and-drop | Not emitted — **deviation** | Needs the internal `Scene` handler (D2). `DRAG_DETECTED` is still fired (apps use it for their own drag tracking), but a handler calling `startFullDrag()`/`startDragAndDrop()` fails (they need the scene's drag gesture). The bridge catches and logs that per event and carries on; such apps are not supported. |
| `ScrollEvent` / `SwipeEvent` / `ZoomEvent` / `RotateEvent` (direct gestures) | Not emitted — **deviation** | On real JavaFX these come from the platform's gesture recogniser, not the scene; there is nothing in `Scene` to align with, so a recogniser would be a new design. Add when an app on a panel this size needs one. |
| `ContextMenuEvent` (long press) | Not emitted — **deviation** (D3) | Also platform-generated; needs a hold timer, and a panel refresh takes ~0.5–1 s, so the user cannot see the menu appear in time to trust the gesture. |
| `KeyEvent`, key focus traversal, text input | Out of scope | No keyboard. Focus follows `requestFocus()` calls made by controls on mouse press: the press target becomes the scene's focus owner, but see the deviation below on `isFocused()`. |

### Contact model, `clickCount`, `stillSincePress`

The raw-contact → touch-set/pointer step is a toolkit-free class driven by the caller's clock and
run on the touch thread, so a throwing app handler on the FX thread cannot corrupt it and the FX
thread is only woken when something changed (a GT1151Q finger replayed every poll produces
nothing). Its rules, modelled on Monocle's touch filters and `Scene.ClickCounter`:

- **Three positions per contact:** the last *reading*, the last *passed-on* position (the
  dead-band anchor) and the press. Releases land at the passed-on position, so a
  `MOUSE_RELEASED` is never up to a dead-band away from the last `MOUSE_DRAGGED`.
- **Dead-band (Monocle `SmallMoveTouchFilter`):** a reading less than 20 px (straight line, strict)
  from the last passed-on position is dropped; the anchor moves only when a reading is passed
  on, so a slow drag still arrives. Touch `MOVED`/`STATIONARY` and the pointer position are both
  derived from the filtered position. A separate constant from the multi-click distance, since
  the dead-band is the one to tune on hardware; each reported move can cost an e-ink refresh.
- **Identity:** when the set of raw ids is unchanged, a contact is its raw id; when it changes
  (a lift, a renumbering chip, an id reused for a new finger), readings and contacts are paired
  by nearest position (as Monocle's `AssignPointIDTouchFilter`), unpaired contacts lift and
  unpaired readings press. Explicit `down=false` is a definite lift and is never re-paired.
  Duplicate raw ids in one report keep the last (logged); `down=false` for an unknown id is
  ignored.
- **Jump rule:** a pairing is only accepted within 20 px plus 10 px per millisecond since the
  contact's last reading — time-aware, because the bridge does not read the chip while the FX
  thread is busy, so the gap can be hundreds of ms. Beyond it, the contact is released and a new
  one pressed. Starting values, to be tuned from captures of real two-finger lifts and fast swipes.
- **Mouse button, primary, one-poll cases:** the button goes down with the first contact and up
  with the last. If the only contact lifts and another presses in the same poll (a jump), the
  button stays down and the pointer moves (`MOUSE_DRAGGED`, no click), as in Monocle.
- **`clickCount` / `stillSincePress` (JavaFX `ClickCounter`):** 500 ms and 20 px per axis. The
  "out" flag is *sticky*: once the pointer leaves the box around the last press at any point (or
  500 ms pass), the next press restarts the count at 1, even if the pointer came back. This state
  belongs to the pointer, not a contact, and survives the end of the gesture. **Deviation:**
  `stillSincePress` is reported `true` on the press itself (JavaFX reports the previous gesture's
  value there). Every pointer result while pressed and at release carries `clickCount` and
  `stillSincePress`, as `ClickGenerator` puts them on `DRAGGED`/`RELEASED`/`CLICKED`.
- **Drag detect:** the model reports the *default* — per axis from the mouse press (the first
  contact's press, which stays the reference after the primary is reassigned), strictly more
  than 20 px. Whether `DRAG_DETECTED` has already fired is the FX consumer's state, because a
  handler can force it with `setDragDetect(true)`, even on the press.
- **`MOUSE_MOVED`:** the pointer position persists across gestures, so a press at the previous
  release position needs no move.
- **Time:** monotonic clock sampled right after the read, not on the FX thread and not a
  `Timeline`.

### Ordering for one poll

Following Monocle, per poll batch the **whole touch set is posted first, then the mouse events**
(the order is a platform choice; apps must not depend on it):

1. Touch set: one `TOUCH_PRESSED`/`MOVED`/`STATIONARY`/`RELEASED` per contact, all carrying the
   whole set.
2. Mouse stream, from the single pointer. As in `Scene.MouseHandler.process`, **every** event is
   preceded by its enter/exit handling (against the node picked at that position), and then
   delivered: on the first contact down, `MOUSE_ENTERED_TARGET` chain, `MOUSE_MOVED` (if the
   position changed), `MOUSE_PRESSED`; while held, `MOUSE_DRAGGED` (only beyond the dead-band;
   the press-hierarchy filter applies to the enter/exit) and `DRAG_DETECTED` once, after the
   drag that triggers it; when the last contact lifts, `MOUSE_RELEASED`, `MOUSE_CLICKED`, then
   the `MOUSE_EXITED_TARGET` chain.
3. `DRAG_DETECTED`: as `Scene` does, the default is set on the event *before* it is fired
   (false for `PRESSED`; the per-axis distance for `DRAGGED`, false once already fired), the
   handlers run, and the flag is read back afterwards, so a handler can force or suppress it,
   even on the press.

The enter chain belongs to the mouse stream, so it follows `TOUCH_PRESSED`. **Deviation:** on
Monocle `:hover` stays set after the lift (no pointer-leave is sent); the bridge exits after
`MOUSE_CLICKED` so `:hover` does not stick on a touch-only surface.

### Touch sets and ids

- Raw contact ids from the driver (the GT1151Q reuses and re-numbers them) are **remapped** to
  JavaFX-style ids: 1, 2, … within a gesture, not reused until every contact has lifted.
- Identity comes from the contact model above (nearest-position pairing and the time-aware jump
  rule), not from raw ids: the GT1151Q renumbers contacts and the ICNT86X's id is an array index.
- Points within a set are ordered by remapped id.
- Each event gets a **fresh** `TouchPoint` list. `TouchEvent.copyFor` mutates shared points in place
  and the code that resets them (`TouchPointHelper`) is internal. Each fresh point starts grabbed
  on its target, as `Scene` does (otherwise `getGrabbed()` reads null, which looks like an ungrab).
- A lift **inferred** from a contact missing in a report (GT1151Q) goes into the *same* set as the
  rest of that poll; an empty poll with only inferred releases still produces a set of releases;
  no set is emitted when nothing changed.
- Event-set id: increments per set (the first set is 1, as `Scene` increments before use), reset to 0 after the last contact lifts.
- After each set, read `getGrabbed()` back from every copy and merge grab changes into the bridge's
  per-contact state: the last copy whose value differs from its starting value wins.

## Deviations from JavaFX, and why

| Deviation | Reason |
|---|---|
| No `MouseDragEvent`/drag-and-drop | Only produced by the internal `Scene` mouse handler (D2); no public equivalent. |
| No gesture events, no `ContextMenuEvent` | Platform-generated, not scene behaviour; designing recognisers/timers is out of proportion until an app needs one (D3). |
| Multi-click time/distance are fixed Monocle-derived defaults, not toolkit values | The bridge runs without a toolkit/stage. |
| `MOUSE_EXITED_TARGET` chain after `MOUSE_CLICKED` | A touch surface has no pointer to leave; Monocle leaves `:hover` set. |
| Small moves are suppressed | Monocle does the same (`SmallMoveTouchFilter`); on e-ink each drag event can cost a refresh. |
| `:pressed` not cleared on a node removed mid-gesture | Setter is internal; see the table. |
| A node removed and re-added under the same parent between two events is not treated as removed | JavaFX retargets immediately on the removal; the bridge only compares the hierarchy at the next event, so it sees an unchanged one. A node removed and re-added *within* one handler therefore keeps its press target. |
| No re-pick after the release | JavaFX clears the press hierarchy and re-runs enter/exit unfiltered at the release point, so nodes held back during the drag get `MOUSE_ENTERED_TARGET` there. The bridge goes straight to exiting everything (a touch surface has no pointer to leave), so those nodes never see an enter/exit pair. |
| A gesture whose release never arrives is abandoned on the next press | Only possible after a lost dispatch or a failed poll. The bridge releases it at the press target with no click and exits everything, so `:pressed`/`:hover` cannot stick; JavaFX has no such case. |
| `MOUSE_EXITED_TARGET` for a removed node fires after it is detached | JavaFX fires it before the node leaves its parent, so it bubbles to the surviving ancestors; ours does not, because the removal is only noticed on the next event. |
| A throwing handler does not abort the rest of the poll | `Scene` lets the exception escape its mouse processing, which would skip e.g. `MOUSE_CLICKED` and leave gesture state stale. The bridge passes the exception to the FX thread's uncaught-exception handler (where applications expect handler exceptions to end up) and carries on. |
| Screen coordinates equal scene coordinates | The bridge has no stage or window. |
| `stillSincePress` is `true` on the press itself | JavaFX reports the previous gesture's value there (see above). |
| Controls behave as on a desktop | `ScrollPane` etc. consume `TOUCH_*` and switch on the platform's `IS_TOUCH_SUPPORTED`, which is false for this bridge. Whatever events arrive, those controls follow the desktop path. |
| Mouse synthesis model | Modelled on Monocle; desktop glass ports may order or synthesize differently. |
| Re-entering at the press | After the exit that follows a lift, a repeat press at the same spot enters the hierarchy from the press itself (enter events carry its button state and click count) with no `MOUSE_MOVED` first. Follows from the exit-after-lift rule. |
| `MOUSE_EXITED`/`MOUSE_EXITED_TARGET` after a lift carry the release's `clickCount` | They are derived from the release event; JavaFX gives enter/exit events a click count of 0. |
| A control never reports `isFocused()` | `Node.isFocused()` needs a focused window and the scene has none. The press target is the scene's focus owner (`scene.getFocusOwner()`), but there is no `:focused` style and a `TextField` shows no caret. |
| Controls that open a popup are not supported | `ComboBox`, `ChoiceBox`, `MenuButton`, `ContextMenu`, `Tooltip` and dialogs need an owner window; tapping one throws `NullPointerException: Owner window must not be null` out of the control's own handler (outside the synthesizer's per-event catch). Not designed yet; apps on the panel should avoid them. |

## Decisions

- **Fidelity rule:** align with JavaFX unless a deviation is documented with its exact reason
  (table above). This replaces the earlier 4 px click rule.
- **D1 — enter/exit:** emit `*_TARGET` chains around each contact. *Agreed.*
- **D2 — internal `SceneHelper` route for mouse events:** **not used in production code.** It
  would add genuine drag/click/removal handling but depends on JavaFX internals (`--add-exports`
  at build, run and native-image time, version-fragile) in a public library that uses only public
  API, and it cannot cover touch (`processTouchEvent` is private). **Use it in tests only**, as an
  oracle: feed the same contact sequence to a real `Scene` via `SceneHelper.processMouseEvent` and
  compare event traces with the bridge's (`--add-exports` at test compile time only).
- **D3 — long press:** deferred, documented deviation.

## Testing

Tests that need no toolkit run in the `pux4j-fx` unit tests: contact tracking, id remapping, set
diffing, inferred lifts, primary selection, dead-band and click counting against a supplied
clock, and the mouse synthesis itself against a detached node tree with a stub in place of the
`Scene` (event order, `_TARGET` chains, `:pressed`/`:hover`, click target, `DRAG_DETECTED`,
removal, re-parenting, throwing handlers, empty-space fallback to the top target).

Tests that need a real `Scene` and controls run in the bridge-smoke module, which needs a display
for the JavaFX toolkit (they are skipped without one; `-Dpux4j.fxTests.required=true` makes that a
failure). They go through the public API only: a real `EInkBridge` hosting a stage-less `Scene`
(as in production), fed by a scripted touch driver that enqueues a whole gesture atomically (the
touch thread reads one batch per poll and reads an empty poll as a lift), so the whole path
touch driver → contact model → mouse synthesis → scene is exercised. Tap positions are taken
from node bounds after `applyCss()`/`layout()`; without that, skins and so control behaviour
do not exist yet.

The oracle can only compare **mouse** events (`Scene.processMouseEvent` is reachable, but
`Scene.processTouchEvent` is private). Two identical scenes are traced by the same scene filter:
one hosted by the bridge, one fed directly through `SceneHelper.processMouseEvent`. The oracle's
expected trace is rewritten for the documented deviations above (no re-pick, re-entering at the
press, the exit click count) and then compared exactly, including target path, scene position,
button state and `clickCount`; `stillSincePress` is not compared because the toolkit's
multi-click distance and time come from the desktop (400 ms / 5 px under GTK) while the bridge uses
Monocle's 500 ms / 20 px. Its `Timeline`-based multi-click timeout never fires inside one FX turn,
so the double tap compares counts only because both sides count up. The oracle needs
`--add-exports javafx.graphics/com.sun.javafx.scene=<test module>` in the compiler's test-compile
arguments (the test sources are compiled in module mode); surefire runs the tests on the plain
classpath, so nothing is needed at run time. Coverage:

- Event order per tap; `_TARGET` chains outermost-first; no plain `MOUSE_ENTERED` fired by the bridge.
- `:pressed` on target and ancestors, `:hover` during touch and cleared after.
- A real `Button` fires `onAction`. `:hover` and armed follow the contact; `:pressed` stays on the press target until the release, wherever the contact goes.
- Click at the common ancestor when press and release land on different nodes; no distance cutoff;
  `clickCount` for a double tap; `stillSincePress` false after a drag.
- `DRAG_DETECTED` once per gesture.
- Multi-contact sets: id remapping, ids restarting at 1 after all lift, shared set id and its
  reset, `TOUCH_STATIONARY`, mouse from the primary contact only.
- Inferred lifts (GT1151Q path) inside a touch set; an empty poll with only releases.
- Handler `grab`/`ungrab`; consumed events; `setDragDetect` read back.
- Hover while pressed: dragging off a `Button` exits it and disarms it; dragging back re-arms.
- Primary reassignment when the primary lifts first; mouse button down until the last lift.
- The contact-model cases: slow drag arrives; exactly 20 px is outside the dead-band; renumbered
  survivor keeps its identity (ICNT86X index shift); implausible jump = release + press with the
  button held; the same distance after a long gap is a move; raw id 0; duplicate and unknown ids;
  five contacts; sticky click-out; a double tap; no `MOUSE_MOVED` before a repeat tap in place.
- A handler that throws (including `startFullDrag()`) does not leave contact state stale or stop
  the next poll being dispatched.
- Contact over nothing targets the `Scene` (touch and mouse); pressed node removed mid-gesture.
- A node handling both touch and mouse (filtering on `isSynthesized()`) sees both streams.
- Headless dice check: tapping a die toggles through the app's unchanged `setOnMouseClicked`.
