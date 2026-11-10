# Native battle HUD

The 3D battle view ([GPU battle view](gpu-board.md)) draws a native HUD over the board: the hud-v3 design, built in
libGDX Scene2D. The Swing client underneath keeps the game, every rule, the selection and the command availability.
The HUD shows immutable snapshots of them and calls MegaMek's existing commands; it decides no rule.

| Code | Where |
| --- | --- |
| Battle HUD (package-private) | `megamek/src/megamek/client/ui/clientGUI/boardview/gpu`: `GpuHud`, `GpuHudState`, the components and services below |
| UI toolkit (public) | `megamek/src/megamek/client/ui/gdx`: `UiTheme`, `UiKit`, `UiButton`, `UiPopover`, `UiMenuList`, `UiList`, `UiTree`, `UiFlow`, `DisplayScale` |
| Paperdoll data | mm-data `data/images/paperdolls` (JSON), sources in mm-data `tools/paperdolls` |
| Tests | `megamek/unittests/megamek/client/ui/clientGUI/boardview/gpu` and `megamek/unittests/megamek/client/ui/gdx` |

Sections: [1 Architecture](#1-architecture) · [2 Input](#2-input-routing) · [3 Components](#3-components) ·
[4 Keys](#4-keys-and-binds) · [5 Tactical View](#5-the-tactical-view) ·
[6 Zoom-out unit scaling](#6-zoom-out-unit-scaling) · [7 Unit panel](#7-unit-panel-and-paperdolls) ·
[8 Toolkit](#8-the-ui-toolkit) · [9 Swing](#9-swing-in-gpu-mode) · [10 Tuning](#10-the-developer-tuning-utility) ·
[11 Tests](#11-tests) · [12 Limits](#12-known-limits)

## 1. Architecture

### 1.1 Two threads

| | Swing event thread (EDT) | Render (GL) thread |
| --- | --- | --- |
| Classes | `ClientGUI`, the phase displays, `BoardClientState`, `GpuBoardSource` and its HUD services | `GpuBattleView`, `GpuHud`, `GpuHudState`, the HUD components |
| Owns | the game, rules, selection, command availability, the captures | the Scene2D stage, the layout, which panels are open, the playback history |
| Hands over | immutable snapshots in a `GpuBoardSource.Frame` | commands, posted back to the EDT |

- Snapshots are immutable records. Images travel as `BoardScene.Pixels`, never as live game objects.
- Every snapshot type has an `EMPTY` or `NONE` value. The source keeps the previous instance while nothing changed,
  so the GL side compares `GpuHudData` and its parts by identity.
- The GL thread never reads the game. The EDT never waits on the GL thread: a native dialog waits in a nested Swing
  event loop (1.6).

### 1.2 Board state: `BoardClientState`

- Each board has one `BoardClientState` (`megamek.client.ui.clientGUI.boardview`). It holds the tactical marks,
  overlays, selection, LOS and ruler, field of view, ECM, chat box, tooltips and every GPU capture
  (`captureFieldOfView`, `getBoardMarkers`).
- The marks are plain records (`TacticalMark`): envelopes, ranges, zones, route dots, steps, links, markers and the
  like. Sprite handlers, dialogs and the movement display publish them (`addMarks`/`removeMarks`); the state makes the
  path, C3, fly-over, vector and LOS marks itself. `BoardTacticalMarks` draws them on the 3D board's hexes, the state
  makes the Legacy 2D sprites from them, and both boards share their look (`TacticalStyle`) and glyphs
  (`TacticalGlyphs`).
- In 3D, work under way shows as the board editor's blue ghosts of what it builds (`GpuEditorPreview.work`): a bridge
  being built, a fortified hex's sandbags, a cleared rubble path, a trench being dug. What marks set on the ground is
  scenery of its hex in the scene, which no hex stores (`GpuBoardSource.markedTiles`): a dug-in platoon's trench
  (`scenery/military/trench`, `BoardFeatures.withTrench`); woods being cleared by saws, whose trees fall from one side
  a share for each turn of work, each leaving a sawn stump, their logs in a low pile along that side
  (`BoardFeatures.withSawClearing`; the game
  lowers the woods when the work is done); and cargo set down, a worn military container
  (`scenery/military/cargo-crate`, `BoardFeatures.withCargo`) that replaces the cargo marker and stays in the Tactical View.
  Its sides are stencilled with a two-digit number its hex picks (`BoardFeatures.label`, written by `RigidGlb.label`); its
  paint is a colour slot, olive by default.
- Special hexes draw no pictures in 3D: markers are native icons, and artillery and bomb landings show as their blasts,
  craters and drift lines. The server reports each dropped bomb's landing from the bomb's own mount, so the blast takes
  its type's area and style (`GpuArtilleryBlast`). `HexDrawPlugin`s paint only the Legacy 2D board and the saved
  board image.
- `BoardView` is only the Legacy 2D renderer. `ClientGUI` builds it only while the Legacy 2D view is enabled; in GPU
  mode no `BoardView` exists.
- `GpuBoardSource.currentView()` is the shown board's state. Every service reads it per call, so a board switch needs
  no rebinding.
- Reach a board through `source.currentView()` or `ClientGUI.getBoardState(..)`, `boardStates()` and
  `onAllBoardStates(..)`. `getBoardView()` and `boardViews()` return the Legacy 2D view, which GPU mode does not build.
- A command captured for a board that is no longer the selected one, was replaced or closed goes stale and does
  nothing (`isCurrentView`, the board generation).
- `UnitStatusWords` holds a unit's status words and label tiles. The Legacy 2D label (`UnitAnnotations`) and the HUD
  (`GpuBattleStatus`) both read it, so each status rule exists once.
- Tests: `GpuBoardFixture.view` is a `BoardClientState`; `legacy2DView()` builds the renderer only for a test that
  needs it. A mocked `ClientGUI` that runs captured commands must report its board through `getCurrentBoardState()`.

### 1.3 The capture: `GpuBoardSource`

`GpuBoardSource` captures a frame on the EDT: on a 100 ms Swing timer (which also advances the state's overlay
fades), on game events (reports, resolved attacks, unit moves, new and removed units), on board edits, and after every
command it runs. The render thread takes it with `takeFrame()`, together with the animation events queued since the
last frame.

| `Frame` component | Content |
| --- | --- |
| `scene` | `BoardScene`: tiles, units, markers, tactical geometry, firing lines, range borders and labels, field of view |
| `timeline` | movement, combat and scene-update events for the playback |
| `context`, `globalCommands` | MegaMek's map menu at a hex; the menu bar, the game commands and a Maps group |
| `status` | `GpuBattleStatus.Snapshot`: units with side, state and status words, initiative, turns, phase, round |
| `reports` | `GpuReportLog.Snapshot`: the visible reports as entries; combat, PSR and movement events; critical hits; artillery in flight |
| `panels` | `GpuHudData`: one snapshot per HUD service (1.4) |
| others | tooltip, centring request, board generation, actor name, the scenario's atmosphere |

Two channels sit outside the frame and are read without the source's monitor:
- `uiPreferences` (`UiPreferences`): the 3D UI scale (1.7), report keywords, the minimap, contacts, planetary
  conditions and turn details preferences, every key binding (`Bind`: command, key, modifiers, text), the 3D board's
  graphics, the overlay switches, the movement colours and the nameplate setting. `UiPreferences.capture()` is its one
  producer; it is captured again at every capture and on preference events. Tests change single components of a base
  snapshot with `GpuHudInputTest.with`.
- `dialog()`: the newest pending native dialog (1.6).

Units the local player cannot see are not captured, and a sensor contact is captured without its identity.

### 1.4 HUD services

Each service owns one domain on the EDT: its snapshot, its capture and its commands. A command called on the GL
thread only posts to the EDT. There it runs only while the input guard (1.5) holds, rechecks the phase, turn and
actor, calls MegaMek's own code and republishes the frame.

| Service | Snapshot | Commands |
| --- | --- | --- |
| `GpuBoardActions` | `panels.phase()` (`PhaseInfo`: phase status, Done/Skip/Clear ids, turn details, conditions lines); the phase, context and global commands | each `BoardScene.Command` clicks its captured button or menu item if its turn is still current |
| `GpuBattleStatus` | `status` | none |
| `GpuMovePlan` | `panels.move()` | `planTo`, `pinDestination`, `turn`, `setMode`, `undo`, `clearRoute`, `holdAll`, `stopHolding` |
| `GpuFireOrders` | `panels.fire()` | `selectUnit`, `selectWeapon`, `focusTarget`, `assign`, `clickHex`, `remove`, `removeLast`, `removeTarget`, `retarget`, `move`, `setPrimary`, `setAmmo`, `cycleMode`, `calledShot`, `aim`, `twist`, `clearAll`, `resolvePhase`, `stopResolve` |
| `GpuPhysicalOptions` | `panels.physical()` | `target`, `declare` |
| `GpuUnitRecord` | `panels.record()`: the card unit's record | `setMode`, `setSystem`, `moveWeapon`, `setDumping`, `setAmmo` |
| `GpuFirePreview` | `panels.preview()`: the movement fire preview | none (time-sliced jobs, 12.4) |
| `GpuChat` | `panels.chat()` | `send` |
| `GpuToasts` | `panels.toasts()` | none; `ClientGUI.addToast` feeds it through `GpuBoardWindow.toast` |
| `GpuLosResult` / `RulerModel` | `panels.los()`: endpoints, heights, range, reciprocal LOS, locks and comparison | `lineOfSight`, `changeRuler`; native controls post to the EDT |
| `GpuPlayers` | `panels.players()` | `setPanelOpen` |
| `GpuReportLog` | `reports` | none |

`GpuBoardSource` has its own commands too: `selectUnit` (MegaMek's SELECT_UNIT event: the phase display decides),
`locateUnit` (`ClientGUI.centerOnUnit`), `click` and `hover` (MegaMek's board tool through
`BoardClientState.mouseAction`), `measure` (the ruler's point at the pointed height), `key` (1.5), `setCardUnit`,
`setFocusUnit` and `answer` (1.6).

- **Fire targets.** The fire orders know a target as MegaMek's attacks do, by its target type and id (`TargetKey`,
  which `Game.getTarget` resolves): a unit, a hex, a building or a minefield alike. The focus is the firing display's
  target whatever it is (a unit only while the player may identify it), and every weapon attack in the queue gives its
  target a letter, so a target MegaMek's board click or map menu chooses (woods to clear, a building) is focused,
  assigned, lettered, carded and traced as an enemy is, and its attacks count for Fire weapons. A target that is no
  unit has its hex in the snapshot while it lies on the shown board; the view anchors its card and trace on that hex
  (`HudView.targetHeads`), and the overlay rings it.

- **Captured commands.** The phase commands are the phase display's own buttons: those of
  `StatusBarPhaseDisplay.getActionButtons()` on all its pages (without the More paging button), the visible buttons of
  other phase panels, and Done and Skip; the HUD builds no parallel set. The context commands are MegaMek's `MapMenu`
  at the hex; building it is read-only, and a target is applied only when an action is chosen. A command runs only
  while its phase panel, phase, turn index and actor are unchanged and its button is enabled; a menu command rebuilds
  the current menu first, so a choice that became hidden or unavailable cannot run.
- The movement planner plots for ground units in the walk, jump and back-up gears. Aerospace units, the other gears
  (such as charge and death from above) and a hex the display picks itself (an escape pod's landing, a bridge) keep
  MegaMek's own board tool. So does a unit that walks on (walk-on deployment) until MegaMek's click on its entry hex
  places it; the planner then continues from the entry and keeps the placement (the DEPLOY step) at the start of the
  path, as it keeps a jump's start.
- Toggling Walk backwards preserves the current route and waypoints. Each pinned segment keeps its forward or
  backward steps in the movement display's path; the new direction applies when plotting the next segment from the
  last waypoint. Turning at that waypoint updates its facing, so later segments keep the turn and its MP cost.
  Undo restores the steps, waypoint facing and direction together; switching to or from jumping clears the route.
- The fire orders read and change `FiringDisplay`'s queue through its own methods; the queue is the one source of
  the orders. While another own unit acts, a unit's orders wait as its draft, and "Resolve phase" declares the
  drafts on the following own turns.

### 1.5 The input guard

`GpuBoardSource.acceptsInput()` is false once the source or its board state is closed, while a native dialog of the
source is waiting, and while a Swing modal dialog is shown (`UIUtil.isModalDialogDisplayed`). Every command checks it
first, so input queued before a dialog appeared is dropped instead of running inside the dialog's nested loop.
Non-modal windows such as Help or the accessibility window do not block it. Keys forwarded to Swing also respect
`ClientGUI.shouldIgnoreHotKeys()`, which is true while a Swing modal or a native dialog is shown. A captured command
also goes stale when its turn (phase, turn index, actor) or its board changed.

### 1.6 The modal bridge

The client's prompts reach the HUD through `ClientGUI`'s chokepoints: `askNative`, `askYesNo`, `askRows`, `askText`
and `askForm`, and the facades `confirm`, `input`, `doAlertDialog` and `doYesNoDialog`. Choice lists
(`AbstractChoiceDialog`), the ECM suite choice and aimed shots check the same predicate.

1. The chokepoint calls `GpuBoardWindow.route(gui, request)`. It asks natively only while
   `GpuBoardWindow.drawsDialogsFor(gui)` holds, that is while this client's native window is presented. Otherwise
   it returns null and the caller shows its Swing dialog.
2. `ask` raises the native window and calls `GpuBoardSource.ask`. That pushes the request on an EDT-only stack,
   publishes the newest unanswered request in a volatile field and pumps Swing events in a nested loop
   (`SecondaryLoop`) until the request is answered. If the source closes first, it returns the cancelled answer,
   the same result as closing the Swing dialog.
3. The HUD reads `source.dialog()` every frame, also while no board has arrived yet. `GpuModalDialog` draws it over a
   scrim on the top layer. The answer goes back through `answer(id, answer)`, which posts to the EDT and never blocks.

| Kind | Body |
| --- | --- |
| MESSAGE | the text, with the request's image on its left when it has one (an image file, base64 encoded) |
| CHOICE | a list; the highlighted row is the choice, a double click confirms |
| MULTI | ticks, at most `max`, with Select all and Clear all |
| INPUT | a text field; with `min` or `max` an integer of that range, shown beside the field |
| FORM | one field per `DialogField`: INTEGER, TEXT, CHOICE or CHECKBOX |

- Enter presses the default button (none for -1). Esc presses the cancel button; with -1 it closes the dialog like a
  JOptionPane close box (button -1, the checkbox as the player left it).
- Only the newest request shows; an outer one shows again after it (last in, first out).
- While a dialog is pending it takes every key, its scrim takes every press, and no other command runs.
- With the native window presented, the physical attack's "To hit" yes/no is skipped, aimed shots are offered as a
  native choice, and the Unit Display window stays closed (the unit card and sheet replace it).
- The bomb payload, mine, called blow, building facing, turret facing and suicide implants dialogs ask natively from
  their own controls: combo boxes as choice lists (`askEntry`), the facings that do not fit or that the mount cannot
  take disabled, and bombs and troopers as live form choices whose every pick goes through the Swing control, so the
  entries narrow and the damage line follows as in Swing.
- The pre-end declarations (infantry action, Nova CEWS networks, Variable Range Targeting, abandonment, demolition
  charges, minesweepers), the AP and B-Pod triggers and the automatic ejection warning ask natively from the rows their
  Swing dialogs show, as tick lists and forms. The infantry action's form is live, so its totals follow each choice;
  the Nova dialog shows its units and pending changes with its five buttons, and Link... and Unlink... ask for the
  units.
- The Victory Setup phase's control point editor asks as a form of the rows its Swing pane shows; changing the
  scheme, the counting or the retention asks again with the rows, labels and description the pane then shows. A
  scenario's story message shows with its image; a story that arrives while another dialog is pending shows after it.

### 1.7 `GpuHud`: stage, layers and frame order

One stage unit is one CSS pixel of the hud-v3 design. `DisplayScale.calculate` sets the scale: the larger of the
monitor's DPI scale and the window's resolution (window / 1600 x 1000), but never so large that 960 x 640 stage units
do not fit, times the 3D UI scale. The 3D UI scale (`GUIPreferences.BOARD_UI_SCALE`, 50-100%, the Tuning panel's
Display page) is a share of that scale, and of the board editor's own 1280 x 800 limit, so each step shows in every
window; it is saved when the slider is released and reaches the layout through `UiPreferences.scale`. Swing's GUI
scale is Swing's alone: the native windows neither follow it nor offer its menu commands, and its keys (Ctrl+NumPad
+/-) do nothing there. The board camera keeps its zoom relative to the HUD, so the board's apparent size follows the
scale. The native window cannot be smaller than 900 x 600 logical pixels.

| Metric (stage units) | W > 1500 | 1350 < W <= 1500 | W <= 1350 |
| --- | --- | --- | --- |
| gap | 20 | 20 | 16 |
| left column | 300 | 270 | 250 |
| forces list | 250 | 250 | 250 |
| right column | 310 | 290 | 270 |
| dock | 580 | 540 | 500 |
| log | 380 | 340 | 310 |

The forces list is `GpuHud.FORCES_WIDTH` wide, at most the left column: narrower than the prototype's column, which
left its rows much room (the user's request of 2026-10-02). The phase header and the unit card keep the column, the
forces grid its own width, and the dock's band still starts after the column. The forces panel is as tall as its
content: a long list ends 12 units above the unit card and scrolls.

Height <= 800 is the low-height layout. The layers, bottom to top: board labels (the Tactical View's north mark,
guides, traces and leaders, nameplates, other board labels, target cards), panels, forces overview, chat and the More drawer,
popovers (the unit card's hover card, the sheet's slot popover, the context menu), dialogs (Help, Menu, Players, Tuning),
toasts, and the modal dialog.

Each component implements `GpuHud.Component`: `actor()`, `update(Inputs)` and `dispose()`. `Inputs` holds the frame,
the `HudView`, the pending dialog, the preferences, the metrics and the bounds of the shown panels.
`GpuBattleView` builds the `HudView` each frame: the view mode, whether the playback still presents live events,
each drawn unit's screen rectangle, label anchor (head) and animated board position, the hovered hex and unit, and a
hex's width on screen.

`GpuBattleView.render`, per frame:
1. Take the frame and feed its timeline to the playback history.
2. `ui.updateState(...)`: the focus unit, the presented units and the log's own opening (1.8).
3. Use the full viewport for normal camera navigation and selection. Movement/combat replay framing and the explicit Fit board action use the visible area between HUD panels; idle panel changes never move or zoom the camera.
4. Pose the units.
5. `updateHud(frame)`: build the `HudView`, run `GpuHud.update` and update the board overlay.
6. Draw the board, then `ui.draw()`.

A frame without a board still updates and draws the HUD, so a dialog asked before the first map is shown and can be
answered.

### 1.8 Shared view state: `GpuHudState`

`GpuHudState` holds only what several components read or change together: the inspected unit, the armed weapon, the
one open dialog (Help, Menu, Players or Tuning), the overview, chat, unit sheet and forces grid flags, the sheet's tab,
whether the nameplate key shows every nameplate, the log's open state and the playback history. It is presentation state, never game state.

- **Focus unit.** The candidates are the own units still to act, in ascending id order, the order of the phase's
  "next" command. A phase starts at the first candidate, or at the player's pick while it is one. During the local
  turn the focus is the actor; after it, the next candidate. When the local turn begins in MOVEMENT, FIRING or
  PHYSICAL and the focus can act, the HUD selects it once. A report phase has no candidates: the turns of the
  initiative report only list the round's order.
  Automatic focus and turn changes wait for the shared animation queue and its final hold to finish, across all
  phases. Incoming game packets remain authoritative; only their presentation waits. The previous unit's card,
  preview and selection stay together, while unit commands are unavailable. Explicit inspection remains possible.
- **Own units outside the local turn.** A click on an own unit (board, row or card) makes it the focus, shown and
  selected in mint; its menu's "Inspect" item only inspects it. Enemy units are inspected.
- **Inspectors.** The own unit stays at bottom left; an inspected enemy or ally appears in a second instance of
  `GpuUnitCard` at bottom right. Its height reserves space under the log, contacts and weapons panels; the minimap
  shrinks when needed. The last explicit own inspection stays visible during playback, with a visible own unit as
  fallback when the report has no focus. The EDT captures `panels.ownRecord()` alongside the inspected record using
  the same `GpuUnitRecord` implementation, and both records wait for the same playback boundary. Closing the right
  inspector only ends that inspection.
- **Cleared selection.** The own card's ✕ ends the inspection and clears
  the selection (`GpuHudState.clearSelection`; the user's decisions of 2026-10-02): no card and no highlighted unit.
  In the local turn no unit is then selected at all. MegaMek's phase display keeps its current unit (it has no
  selection without one), but the HUD presents the turn without it (`GpuHudState.presented`, used by the HUD and the
  board overlay): no acting unit, no unit commands in the dock, no route or envelope, no fire orders, preview or physical options, no map
  menu. No input reaches that unit: a hex click does nothing, a unit click selects (an own unit that can act) or
  inspects (any other, which keeps the selection cleared), and the HUD keeps every key from MegaMek except the menu
  bar's binds (`KeyCommandBind.isMenuBar`) and `GpuHud.UNITLESS_BINDS` (camera, chat, pause, report keys, turn timer,
  the HUD's panels). A selection (board, row, menu, Next pending, MegaMek's next or previous unit) or the next turn,
  phase or focus unit ends it; MegaMek then selects the unit afresh.
- **No fire preview outside the local turn.** `GpuHudState.presented` also drops the fire preview while another
  player moves or fires: no to-hit badges or guides from the last own unit (the user's decision of 2026-10-03).
- **Presented units.** While the playback still presents live events, every component shows the units and the record
  of the last status captured while the playback was idle, so armor and damage never run ahead of the animation.
  Unit context, phase and turn presentation share that boundary. Visibility revocations still remove concealed
  identities immediately, and chat and notifications remain live.
- **Log.** A closed log opens for the report phases after the initiative phase and closes again with the next phase
  that is not a report. A log the player opened stays open.
- **Playback history.** `GpuBattleView` owns one `GpuPlaybackHistory` over its `UnitPlayback` and hands it to the
  HUD. The board feeds it; the dock's transport, the phase header's speeds, the log's cards and Replay, the playback keys and the
  board's pop-ups all act on it. A review presents a retained shot again and never applies an event, so damage, heat
  and ammunition stay as the client reports them. Live events that arrive during a review play after it, in order.
  A report card's Replay plays only that event and keeps its actor inspected and its camera framing afterwards.
  The dock's Replay and explicit Play transport still traverse the sequence. Historical review never sends a live
  movement-completed notification to the client. While a firing, physical or end report still plays, the dock shows its
  transport under the latest phase, although the presented phase holds back with the units.

### Strikes and explosions

Blasts without an attacker use `NUCLEAR_DETONATION` BEGIN/END packets around the server's existing damage resolution
(`TWGameManager#detonation`, animation only). `NuclearDetonation` carries the confirmed position, original ground level,
four resolved statistics (damage, degradation, secondary radius, crater depth), its `Kind` and, on END, every structure
hex the blast took to CF 0:

- `NUCLEAR`: a scheduled `/nuke` strike, stock or custom. Command coordinates are converted from one-based input to
  zero-based once.
- `ORBITAL`: a Game Master's orbital bombardment.
- `EXPLOSION`: a conventional explosion (a fuel tank, a unit's ammunition, a fusion engine, a booby trap), drawn as a
  blast falling off evenly over the rules' damage table, whose reach it keeps exactly.

The server's animation capture brackets them: what comes down while one is open is the innermost's. An explosion inside
a strike, inside a nuclear round's attack or at the centre of an open explosion (a multi-hex tank's next hex) is folded
into it and never sent; an explosion that brings nothing down is not sent. A tank set off elsewhere is its own explosion,
nested inside the one that set it off. Weapon nukes stay on the resolved-attack path (captured ammo selects Davy Crockett
type 1, Alamo 2, Santa Ana 3 or Peacemaker 4); what their attack brought down falls with their front as the round goes
off.

The client applies authoritative updates immediately. `GpuBoardSource` retains the preceding presentation until END, then
queues the detonation, one final terrain checkpoint and its visible casualty animations; a nested explosion queues inside
the enclosing one and plays after it. `UnitPlayback` releases the checkpoint at flash contact. A strike holds its front;
an explosion plays as the hit that set it off ends and holds its front and its falls, or, in a chain, only until its
front reaches the next tank, which goes off there. No detonation frames, follows or returns the camera: it stays the
player's. An
explosion draws a fireball, smoke and its shock ring, moves plants without scorching, pushes units only within its
damage's reach, and bursts the fuel tank that went off: every piece at once, thrown up and out. If an update conceals a unit, visibility takes
precedence: catch up first, discard consequences that could restore its identity (including carrier attachments), then
play the public flash. Board changes clear retained blast effects. Detonations are live timeline events, with no replay
card in the report log.

### Trees an attack takes down

The server names on an attack the hexes whose woods or jungle its damage thinned or cleared
(`ResolvedAttack#cleared`, noted by `TWGameManager#tryClearHex`; what an explosion it sets off clears is that explosion's,
felled by its front). The trees those hexes lose in the attack's checkpoint are the ones it took down: `GpuBoardSource`
names them on that checkpoint's `SceneUpdate` with the attack's hit. As the playback shows that checkpoint at the hit, `GpuTerrain#breakTrees` breaks them into
fragments that burst away from the attacker and out, scatter and sink once the terrain shows them gone. A tree's model
splits on the fragment cutter thread as the checkpoint arrives (`KitFragments#splitLater`): leaf cards and twigs stay
whole, a trunk breaks between its triangles; a model not split by the hit falls in whole parts. Woods drawn as a
container yard or as crystals break the same way, each prop in its own fragments, and behind a blast's front too.
Instant and a review show only the outcome.

A piece thrown past the board's edge does not land: it falls on and shrinks to nothing below its floor's height, as do
the cut pieces of a bridge, an authored building or a destroyed unit. A blast also removes the visual props of the hexes
it damages (decals, route markers and building models stay).

## 2. Input routing

### 2.1 Mouse

1. The HUD's stage gets every press first. A panel takes presses on its whole area; the slots that span the window
   (board labels, target cards, popovers, the Tuning panel) take presses on their widgets only. While a dialog is
   pending, the scrim takes every press.
2. A press on the board calls `GpuHud.boardPress()`, which ends a keyboard focus and closes the context menu. Right
   drags pan and middle drags orbit; Shift held at the press swaps the two. A drag starts past 6 pixels times the layout scale. A left drag moves nothing and cancels its
   click. A middle drag on the minimap's map orbits the camera as well, by the same `GpuBattleView.ORBIT_DEGREES` per
   unit. An orbit (in free flight a look) hides the
   cursor and wraps it inside the window (`UiCursorCapture`), so the screen's edge
   never stops it: the board's hides at the drag's start, so a click keeps the cursor, the minimap's at the press. The
   cursor reappears at the press on release, focus loss, a modal dialog or a new board. A pan keeps the visible
   cursor, which the board follows.
3. A short release (not the middle button) on the board of its press picks the nearest of the unit models, location
   markers and terrain along the pointer's ray. In both views a unit model or icon wins over the ground, and the
   ground of a hex holding a unit picks that unit too (in a building, only on that unit's floor). The release calls
   `GpuHud.boardClick` with the modifiers held at the press:
   - right: the context menu at the pointer; opening it never changes orders. Every unit and hex menu starts with one
     item per unit the board draws in the hex, wrecks excepted (the user's decisions of 2026-10-11;
     `GpuContextMenu.stack`, a multi-hex unit in each hex of its footprint, in the board's order), then a separator:
     "Select {name}" for an own unit that can act now (`GpuHud.acts`; it switches the acting unit through
     `GpuHud.select`, off for the acting unit), "Inspect {name}" for every other unit (enemy, allied, an own unit that
     cannot act now), which only inspects and never changes the acting unit, and "Inspect sensor return" for a sensor
     contact. These replace the unit menus' former "Select unit", "Inspect unit" and "Inspect sensor return" items. A
     unit row's menu lists the units of that unit's hex on the shown board, else the unit alone. Every unit menu (own,
     allied, enemy, sensor contact; from the board or a unit row) of a unit on the shown board ends with MegaMek's full
     map menu of the unit's hex in sections, as the hex menu lists it, once the source captured that hex's map menu,
     below the items already shown (the user's decision of 2026-10-11), in every phase: for a gamemaster its Gamemaster
     commands (Edit Damage of the units there, Change Terrain, Building, the special commands), in the local movement
     turn Physicals with Charge and D.F.A., which press their command and click the hex as the Legacy 2D map menu does,
     and the rest of what MapMenu offers for that hex;
   - Alt + left click: native LOS including range, each point at the height the pointer shows there (a unit or
     building floor, else the ground); a plain left click completes a pending measurement. Ctrl + click no longer
     starts a native measurement;
   - left: by phase, below.
4. The wheel zooms at the pointer over the board, and scrolls a panel under the pointer.
5. Hovering picks the hex and the unit as a left click takes them (`GpuHud.clickedUnit`). The pointer is a hand over
   units a click selects or inspects, and a move cursor over the minimap (desktop platforms only). Where a click
   plans the move into an occupied hex (below), on its ground and on its unit's model alike, the hover shows no unit:
   the arrow, no unit outline, and the route preview. On a stack the outline and tag show the unit the click takes
   next (below). The board's pointer follows that every frame, so a mode change or a new envelope changes it without
   a pointer move. No route preview is requested while a move animates.

In the local planning turn the end destination wins (the user's decision of 2026-10-11): a left click on a hex in the
plan's envelope, the highlighted legal end positions of the mode (jump included) on the actor's board, plans the move
there as in an empty hex, also when a unit stands there and the click hits its model or meeple; in a building the
pointed floor decides, as for an empty hex (a model click has none, so MegaMek's own choice of elevation applies). The
actor's own hex is excluded. Outside the envelope a click on a unit or an occupied hex selects or inspects as in any
other phase; the menu's "Inspect" items reach a unit inside it. A click on a transport the actor can board but may
not stand with (`GpuMovePlan.Snapshot.mounts`: grounded friendly Small Craft and DropShips, towing or towed units that
can load it, whose hex is a stacking violation for the actor) plans to it in the walk gear; MegaMek's click stops the
path beside it, where Mount boards it. A transport the actor may stand with (infantry with a DropShip, a vehicle with
a Small Craft) is no such click: in the envelope its hex plans, outside it the transport is selected or inspected as
other units are. When the rules cut a route where it enters its destination because a unit stands there (an illegal
stack, a walking Mek entering an enemy Mek's hex) the toast says that hex is occupied; a cut on the way there, or one
the MP explain, keeps the MP toasts.

| Left click | On a unit | On a hex without a unit |
| --- | --- | --- |
| Outside the local turn | own unit: becomes the focus; other: inspected | nothing |
| Local movement turn, planner | in an envelope hex (not the actor's), model or ground: plan the route to it; a transport the actor can board: plan to it; else select an own unit that can act, else inspect; Ctrl pins a waypoint in its hex; Shift orients the planned endpoint toward it | plan the route to it; Ctrl pins a waypoint; Shift orients the planned endpoint toward it |
| Local FIRING turn | own: select; identified enemy: focus it as the target (an armed weapon is assigned); sensor contact: refused with a toast | MegaMek's board click chooses the target there (`clickHex`: a building, woods to clear, its dialog among several; Shift twists), focused as an enemy is; an armed weapon is assigned to it |
| Local PHYSICAL turn | own: select; enemy: inspect, and make it the target when adjacent | MegaMek's board tool |
| Any other local turn: deployment, TARGETING and OFFBOARD, movement outside the planner | MegaMek's board tool (`hover` then `click`) | MegaMek's board tool |

While a bot order picks target hexes (Players: Strategic Target, waypoints, fire missions, Scoot-To Hex), every left
click on the board, in any phase and on a unit too, picks that hex for the order, as on the Legacy 2D board (`click`).
Where the window hides the hint line (W <= 1350), the pick shows in a chip on the dock: its instructions, the picked
hexes, and Done and Cancel buttons.

`GpuHud.select` is the one selection rule for clicks, rows, cards and menus. An own unit that can act now in the
local MOVEMENT, FIRING, TARGETING, OFFBOARD or PHYSICAL turn is selected through that phase's command. Outside the
local turn an own unit becomes the focus. Any other unit is inspected.

Repeated left clicks on a hex holding several units take them in turn, in every phase (the user's decisions of
2026-10-11; `GpuHud.cycled`): while the unit the HUD shows as taken stands in the clicked hex (the fire orders' focused
target, else the card's unit), a click that takes a unit takes the next of the hex's units after it, in the menu's
order, instead of the picked one. Only units the battle status lists take part, not the wrecks the board also draws, and
in the local FIRING turn no sensor contact, which a click cannot focus. Two own units in one hex therefore switch the
acting unit back and forth, an enemy stack is inspected (or, in the FIRING turn, focused) unit by unit, and a click in
the movement turn that plans is not affected. In the FIRING turn a hex holding both an own unit and an enemy cycles from
the focused target, so such a stack does not reach every unit by clicks alone; the menu's items do.

### 2.2 Keys

`GpuBattleView.BoardInput` receives every key; the stage never sees one directly. For a press:

1. `GpuHud.keyDown`: a pending dialog takes every key (the CANCEL bind presses its cancel button);
2. the CANCEL bind runs the Esc chain (2.3);
3. a focused text field takes every other key;
4. a focused grip or open list gets the key first (menus navigate, Alt+Up/Down moves an attack on a target card, and
   a weapon row of the sheet's focused list);
5. the HUD hotkeys (section 4);
6. the camera binds;
7. everything else goes to Swing: `GpuBoardSource.key` runs `MegaMekController`'s binds on the EDT unless the client
   ignores hotkeys, then the menu bar's accelerators. With the selection cleared in the local turn only the binds of
   no unit go (section 1.8); the HUD keeps the others.

A key's release goes to the HUD first; the release of a key whose press the HUD consumed ends there. A forwarded
release carries the modifiers of its press. Typed characters reach only a focused text field or a pending dialog. The
held camera keys work with non-modal panels open and pause while a text field has focus or a modal dialog is open.

### 2.3 The Esc chain

CANCEL (Esc by default) takes one step per press:

1. a pending dialog or dock confirmation: its cancel button;
2. a row dragged in a list (a target card's attack, a weapon of the sheet or declaration panel): it glides home and nothing moves;
3. a keyboard focus outside the context menu, the sheet's popover and an open dialog: dropped;
4. the context menu or a select list: closed;
5. Help, Menu, Players or Tuning: closed, with the focus inside it;
6. the forces overview: closed;
7. chat: closed;
8. a bot order's hex pick: cancelled, no order sent;
9. the line of sight panel (its popover, then the panel); the unit sheet (its popover, then the expanded row, then the
   sheet);
10. the weapons panel: the armed weapon disarmed, else the weapon deselected (the solution card closes with it);
11. the local planner turn's route: cleared;
12. the chosen physical attack: released, undeclared;
13. the local movement turn without the planner (aerospace, vector, the other gears) while MegaMek's display has a
    path, a gear other than walking or a hex pick: the key goes to the phase display, which drops it;
14. the inspected unit: no longer inspected;
15. a running playback: skipped to its results, as the dock's Skip does;
16. the selection: cleared, as the unit card's close button clears it;
17. otherwise: the Menu opens, and the next Esc closes it. The key never reaches the phase display in FIRING,
    TARGETING, OFFBOARD or PHYSICAL, where MegaMek's CANCEL would clear the declared attacks (Clear does that). The
    `GAME_MENU` bind (F10) toggles the Menu directly, over any other centred dialog.

## 3. Components

The command dock's Hold all, twist-with-queued-attacks and Resolve phase confirmations float above their
triggering button, or below it when there is no room above. They use the shared `UiConfirmation` component,
without dimming or resizing the dock. Outside clicks dismiss and continue to the clicked control or board;
clicking the same trigger again only closes. The safe choice receives initial focus, Tab/Shift+Tab stay between
the two choices, and Enter or Space activates the focused choice on release. Escape and explicit cancellation
restore focus to the trigger; outside clicks leave focus with the clicked control.
A change of acting unit, phase or turn, an unavailable action, or another
dialog discards the pending confirmation. Hold all's Stop action only prevents future holds; it does not restore
turns already skipped.

Other components can reuse `UiConfirmation`: add its `actor()` to the popover layer, set its `text(...)`, and call
`toggle(trigger, confirmedAction)` from the trigger. Call `resize(width, height)` after laying out the controls,
and `cancel()` when the pending action becomes invalid. Its dismissal callback lets the owner clear its pending
state. `UiPopover.showAnchored(...)` supplies the reusable placement and outside-click behavior for other kinds
of popover content too; its caller handles toggling the trigger.

Every component reads the frame's snapshots and `GpuHudState`. Its orders go through the commands of 1.4; only
view changes (open panels, the camera) stay on the GL thread. Names in quotes are the English texts.

| Component | Where | Shows | Runs |
| --- | --- | --- | --- |
| `GpuPhaseHeader` | top left | round, phase ring, phase name, whose turn; in movement the activation ribbon (hidden under double blind); the playback speeds in the frame's top right corner as one pill of sections (0.5x, 1x, 2x, 4x, I for Instant), in every phase and turn; the round shortens to its number ("01") only where the line has no room for "Round 01" | the playback history's speed |
| `GpuForcesPanel` | left column | own and allied units, or the contacts, as grouped rows or a grid with search, grouping and filter | selection rule, unit menu, the phase's next-unit command |
| `GpuUnitCard` | bottom left and bottom right | own unit on the left, inspected enemy or ally on the right: shared paperdoll, vitals and chips; the left mini card while the sheet is open; anonymous contact card for a sensor contact | "Unit record", Locate |
| `GpuRecordSheet` | left, at the grid's width | the unit sheet (section 7) | the record service's unit actions |
| `GpuConditionsCard` | beside the left column | the planetary conditions overlay's lines while its View preference is on | its close button runs that View item |
| `GpuUtilityBar` | top right | "Tactical view", "Wireframe" (an open grid icon: three lines each way, no border), "Map", "Log" (amber count of the round's reviewable events the board has presented), "Help", "Menu"; the Tactical View chip and north mark | the camera's Tactical View, the tuning model's wireframe view (without the cosmetic scatter), View > minimap, the HUD's toggles |
| `GpuInitiativeCard` | centre, initiative phase | each side's reported roll, "Wins", "Moves first", the turn order in pages of eight (double blind hides the order and "Moves first") | none |
| `GpuMinimap` | under the utilities | the board in its tileset colours, units at their animated positions, the planned route, the camera's ground area; its close button straight on the panel's corner | a left press or drag centres the camera, a middle drag orbits it and the wheel zooms it as on the board, about the view's centre; no order changes |
| `GpuTacticalOverlays` | Tactical View only: top right, left of the right column's top panel (the minimap, else the contacts, weapons or log panel that moves up in its place), level with its top; at the right edge when the right column is empty | "OVERLAYS" and a chevron; one column of twelve checkboxes in four groups, each with a tooltip: Units (Status badges, Links), Combat (Envelope, Rear arcs, LOS shading, Sensor ranges), ECM (Fields, Jamming), Map (Coordinates, Type, Level shading, Distance rings). Collapsed: the header only, same place and width. The layout shows it collapsed for the frame while the window is too short for its rows or the solution or initiative card beside the right column needs the room | a row runs its View-menu item (Unit Links, Movement Envelope, FOV darken, Sensor Range, Hex Coordinates, Hex Types, FOV highlight) or toggles its client setting on Swing (`TacticalStatusBadges`, `TacticalRearArcs`, `BoardEcmTacticalFields`, `BoardEcmTacticalHatch`, `TacticalLevelShading`); the header and H toggle `TacticalOverlaysOpen` |
| `GpuContactsPanel` | right column | enemy units; with the movement fire preview, where the unit fires from, each enemy's best salvo both ways and the guide toggles | selection rule, unit menu |
| `GpuWeaponsPanel` | right column, local weapon declaration | target pills with wrapping names, one row per weapon with its roll and slots (an assigned slot in its target's colour); editable rows drag through `UiList` into the unit's saved custom weapon order, shared with the record sheet, without changing queued attack order; a fixed footer holds HEAT IF FIRED and ALPHA STRIKE, which assigns or retargets all available weapons to the focused target; only the weapon list scrolls, and the minimap shrinks in short windows to leave a complete weapon row visible; no assign line while a weapon is armed, which its row shows selected; on another player's turn the focus unit's draft, read-only. Declaring never ends the turn by default: MegaMek's auto-end firing (`GUIPreferences.AUTO_END_FIRING`, now stored as `AutoEndFiringAfterLastWeapon` so that the old `AutoEndFiring` value is ignored) defaults to off, so FIRE WEAPONS sends the attacks | fire orders |
| `GpuSolutionCard` | under or beside the right column | the selected weapon's shot: roll, range brackets, arc, modifiers, or why there is no shot; a long target name ends in an ellipsis, the roll after it stays whole | close deselects the weapon |
| `GpuLogPanel` | right column, log width | the round's events as cards, Summary and Full log, filters, search, totals, earlier rounds, report keywords, copy, artillery in flight | review a step, locate, replay |
| `GpuCommandDock` | bottom centre | one variant per phase and turn: initiative, movement plan, waiting, weapon fire, physical, no physical attack, playback, generic; in the local TARGETING turn the generic option row leads with the off-board targets ("Off-board West"), the edges whose arrows `OffBoardTargetOverlay` shows; the playback transport has no speeds (the phase header holds them) | phase commands (an off-board target runs the overlay's own click), movement, fire and physical services, the playback, the Follow camera toggle |
| `GpuHintLine` | above the dock (hidden at W <= 1350); there a bot order's hex pick shows in its chip above the dock; the dock keeps the shared window-edge margin below it | what a left click does now ("Plan move · a unit out of reach: select or inspect" while planning, "Choose target" in the charge, DFA and ram gears), the gestures and their keys; the pick's instructions and picked hexes | the chip's Done and Cancel end the pick |
| `GpuChatPanel` | anchored above the command dock, at its width with the standard 12-unit panel gap | chat replaces the hint line; drag the top grip to resize vertically; the icon-only mini button is always last in the dock footer, including when no unit commands are available, with an unread dot | the board chat's send, which keeps its history |
| `GpuToastStack` | centre, above the dock | MegaMek's toasts: at most 5, levels INFO, SUCCESS, WARNING, ERROR, GAMEMASTER | presses pass through |
| `GpuForceOverview` | over the board; the panels it covers are hidden (the prototype blurs them) | a card for every presented unit, grouped, filtered and searchable | locate, selection rule, unit menu |
| `GpuHelpDialog` | centred | Compact "Controls": category sidebar, action rows and current key badges; mouse gestures have their own category | none |
| `GpuMenuPanel` | centred | the lobby's game menu (`GpuGameMenu`). Its first page: Resume, Save Game…, Quick Save, Players, the pages Game, Display, Commands, Maps (with more than one map), Settings and the Help page, then "Return to Lobby" (the Commands group's Reset Game, offered where the player may reset: its confirmation, then the game ends for everyone and the server returns to the lobby) and "Quit to Main Menu" (the client's exit: the save prompt, then the startup screen or MekHQ). A page opens in place with a Back button; Esc or Backspace goes back a page, and Esc on the first page closes the Menu | each item's own action, or a native surface (below) |
| `GpuPlayersPanel` | centred | the players as the Swing player list shows them; each bot's commands under it | bot commands |
| `GpuContextMenu` | popover | unit menus and the hex menu (its header: the hex, MegaMek's terrain line and level, then the hex tooltip's other lines of the hex: a building, bridge or fuel tank with its height and CF, minefields, objects on the ground, clearing and bridge-building work), each starting with a Select or Inspect item per unit of its hex and ending with MegaMek's map menu of its hex in sections (a group's commands between separators, no menu of menus), weapon-row and queued-attack menus, the dock's More, select lists; a group opens its submenu beside its item as a desktop menu does: the menus before it stay open, another group replaces the chain below its menu, Esc closes the deepest and a press outside closes all | existing commands only |
| `GpuHexTip` | popover, beside the pointer | after the pointer rests on a hex of the board for `GpuHexTip.DELAY` (0.75 s), the hex and its menu header's lines in small text on a faint plate, the first line broken only between its terms into the fewest, most even lines; gone when the pointer leaves the hex or the board, at a press, and while a menu or dialog is open | none; it never takes input |
| `GpuModalDialog` | top layer | the pending dialog (1.6) | answer |
| `GpuNameplates` | board labels | a team pip per unit; a tag for the focus, hovered and inspected unit, the physical target and sensor contacts, and every tag as Client Settings > 3D board nameplates chooses (always, by default; while the nameplate key is held; or toggled by it): the unit named in the client's label style (`UnitAnnotations.labelName`) before its status words and, on a hovered enemy, the best roll or distance (a tag with neither name nor words stays a pip); under the pip or tag, each unit's Legacy 2D label marks (damage tile, armor and structure bars, from `UnitAnnotations`), which the unit card's damage label repeats; target cards replace their targets' plates | none |
| `GpuBoardLabels` | board labels | to-hit badges (every shot, on the unit's nameplate), fire-preview guides (the best 50 of each direction), traces of the queued attacks, each guide and trace leaving its shooter outside its rectangle at its front, or its back at a target in its rear arc; leaders to the target cards, the destination tip, waypoint numbers, playback pop-ups (a unit's stack: the newest at its place, each older one 4 over the next) | none |
| `GpuTargetCards` | board labels | a card per target in letter order, placed by `GpuCardPlacement` clear of panels and units, its frame neutral; the target's letter square carries its colour, as in the weapons panel and the sheet (`GpuHudKit.targetColour`: MekBay's twelve target colours from A); the focus without attacks has no letter; target names wrap in full; the attack rows (no fire-order number) are a reorderable `UiList`; the footer offers Clear all while attacks are assigned, including on collapsed cards | fire orders; Clear all removes only this target's assigned attacks; a row with a grip drags to another place of the card (from any part of it, as the prototype's draggable `.or`), its focused grip moves it on Alt+Up/Down, each a single `move` |
| `GpuTuningPanel` | dialogs layer | developer tuning (section 10); the frame rate beside its title | the tuning model |

The centered Controls, Menu and Players dialogs and native prompts use UIKit's `UiModal`: a dark backdrop blocks
the board and lower panels and keeps keyboard and wheel input inside. A left click outside closes a centered dialog;
native prompts still require their own answer or cancel action. Background hotkeys and held camera motion pause
while a modal is open. The backdrop uses dimming,
without a blur render pass. UIKit draws the backdrop only behind the highest visible modal on each stage. Opening
a nested prompt dims the previous dialog once, without dimming the background twice; closing, hiding, removing or
reordering modals moves that single backdrop with the visible stack. Controls keeps its selected category when
bindings refresh and reads the existing key-binding snapshot, including camera tilt and Fit Board.

Two board-space components are world meshes that `GpuBattleView` draws:
- `GpuBoardOverlay`: the route and its ghost, the front arc or the displayed weapon's arc, the physical-attack
  neighbours as meshes, rebuilt only when what they draw changed, with
  the route's pulse over the route (below). The unit marks (side rings, glows, the other targets' rings and the
  hovered unit's ring) are drawn every frame where the units stand, so the pointer rebuilds no mesh. 
  Where the terrain or a model hides the acting unit's, the focused target's or the hovered
  unit's ring, it is drawn again at half opacity behind what hides it (occluded outlines).
  `GpuBattleView` rings the hovered hex; over a building floor the ring lies at that floor with a
  faint ring at the ground and faint corner posts between them. The movement envelope is MegaMek's own (below).
  Movement paths follow the snapshot's actual floor heights, including vertical steps within one hex. The path,
  destination ring, facing arrow, waypoint markers and hologram use `GL_ALWAYS` without writing scene depth, so
  opaque walls, floors and terrain cannot hide them. The hologram draws after the route and the units. The
  Tactical View keeps its flat route projection; both 3D camera projections share the same route geometry.
- `GpuFireControl`: range walls of the displayed weapon, the firing lines and the flat range labels
  ([attack controls](gpu-attack-controls.md)). During the local weapon declaration the actor's lines are the HUD's
  traces instead; every other line is drawn in its attacker's side colour. Lines hide while an attack plays
  (`GpuBattleView.HIDE_TARGET_ARROWS_DURING_ATTACKS`).

**Region markings** The displayed weapon's range brackets are `GpuFireControl`'s continuous walls along the brackets'
contours (`BoardFiringGeometry`) with the camera-facing S/M/L letters. 
The visual range (`TacticalMark.SensorRange`) and the deployment zones (`BoardDeploymentGeometry`) stand
upright through `BoardRangeBorder` in the tactical capture, which `GpuTactical` lays flat in the Tactical View.
MegaMek's movement envelope, the sensor ranges and the zones lie flat on their hex's plane; `GpuTactical` depth-tests
those markings two levels nearer (`PLANE_SEE_THROUGH_LEVELS`), so that the slopes and rocks inside their hex never
cover them while a hill in front of the hex still does.

**The route's pulse** (`GpuRoutePulse`, user item 55). In the local movement turn a plotted route pulses: a glowing
head leaves the unit, runs the route and settles into the destination; the ghost then surges and a ring ripples out of
the destination ring; after a rest the next pulse leaves. The hover preview, a route MegaMek's board clicks plan (G20)
and a cleared selection have none.
- **3D.** The head is a pool of light on the ground under it and four soft glows facing the camera, from a wide faint
  bloom to a white-hot core, in the band colour of the step it runs (whiter at the core), with a tapering streak
  behind it. The route's dashes, jump dots and step discs light up just ahead of it and fade over the 2.5 hexes
  behind; about every other one lets a small mote of light rise and drift off. A jump's head rides its arc. On
  landing the head flares, the destination hex flashes, a ring with a bright edge and a fading wake runs out to 1.65
  hex radii with a fainter echo, and the ghost's glow rises toward white while it turns more opaque, then eases back.
  The ghost never grows: units keep their size.
- **Tactical View.** The same as flat glows on the dashed line; the destination ring ripples and the ghost icon
  brightens in the route's last colour.
- **Timing.** 0.16 s a hex or vertical level, at least 0.5 s and at most 1.8 s for a route, divided by the speed in use; the head
  speeds up over the first quarter of that time and settles to rest over the last 35 %. The surge rises in 0.08 s and
  ends 0.9 s after the landing, then the pulse rests 0.6 s. A rebuild for the same plan keeps the rhythm; another plan
  starts it from the unit. A route that only turns in place has no head: its ghost surges and its ring ripples.
- **How.** While it builds its meshes, the overlay hands the pulse the route's line (over the ground, through the
  selected floors, or along the jump's arc) and its marks. Each frame `update` moves the pulse on by the frame's time (at most 0.1 s), `render`
  draws its quads after the static meshes (one dynamic mesh of up to 256 quads through the overlay's ModelBatch, with a
  64 x 64 glow texture made once per overlay), and `renderGhost` sets the surge on the ghost's own material copies.
  The quads use premultiplied blending: each adds light and covers a share of what lies under it, so the colours stay
  visible on bright ground. They remain visible through scene geometry without writing depth; in the Tactical View
  they lie flat under the icons. The static meshes are never rebuilt for the pulse, and a frame allocates nothing.
- **Constants** (`GpuRoutePulse`): `ENABLED`, `SPEED`, `INTENSITY`, `SECONDS_PER_HEX`, `MIN_TRAVEL`, `MAX_TRAVEL`,
  `SURGE`, `REST`, `HEAD_SIZE` and `FLAT_HEAD_SIZE`, `TRAIL`, `GHOST_SURGE`, `GHOST_SURGE_OPACITY`, `ICON_SURGE`,
  `RING_RADIUS`, `RING_SECONDS`. They are the defaults of the values in use (`enabled`, `speed`, `intensity`), which the
  Tuning utility's Board page edits under "Route pulse"; Defaults restores the constants.
- **Limits.** The expanding ring stays on the destination floor's plane, including beyond that hex's boundary.

The Menu's items that open a native surface instead of Swing (`GpuMenuPanel`):

| Menu item | In the HUD |
| --- | --- |
| Players | Players |
| Help › Keyboard Shortcuts | Help |

The menu bar's items the HUD shows or does itself are left out of the Menu: Unit Display, Force Display, the minimap,
Player List, Round Report, Rounds in the Air, the keybinds overlay, Planetary Conditions, Reset Window Positions, the
camera (Zoom In/Out, Overview, Isometric), the hex coordinate and type labels, the field-of-view and LOS settings,
sensor range, field of fire and unit links. So are the lobby's Load and Quick Load, the GM commands, the cache and
board-image commands, Reset Nags, skinning, accessibility and the Renderer and Bot commands submenus. A check item shows
as a switch that applies at once and keeps the Menu open.

Every other item runs its own action on the EDT. The client's menu shortcuts reach the same items (2.2).

## 4. Keys and binds

Every key comes from `KeyCommandBind`, so a player can rebind it in the client settings; the HUD reads the binds the
client captured (`UiPreferences.binds()`). The defaults:

| Bind | Default | Action in the HUD |
| --- | --- | --- |
| `CANCEL` | Esc | the Esc chain (2.3); with nothing to close, the Menu |
| `GAME_MENU` | F10 | the Menu, open or closed |
| `ROUND_REPORT` | Ctrl+R | Log |
| `KEY_BINDS` | Ctrl+K | Help |
| `BOT_COMMANDS` | Ctrl+Shift+G | Players |
| `LOS_SETTING` | L | Native LOS panel, as View > Ruler / LOS Tool |
| `UNIT_OVERVIEW`, `FORCE_DISPLAY` | Ctrl+U, Ctrl+F | forces overview |
| `UNIT_DISPLAY` | Ctrl+D | unit sheet (closes the overview) |
| `UD_GENERAL` ... `UD_EXTRAS` | F1-F6 | the sheet's tabs |
| `FORCES_GRID` | G | forces list or grid |
| `TACTICAL_OVERLAYS` | H | Tactical View: expand or collapse the Overlays panel (remembered); elsewhere MegaMek's |
| `TOGGLE_CHAT`, `TOGGLE_CHAT_CMD` | Enter, / | open chat; "/" starts the message |
| `DONE` | Ctrl+Enter | the dock's main button; while a bot order picks hexes, the order with the picked hexes (the hint line shows the pick and both keys; in narrow windows the pick's chip has Done and Cancel buttons) |
| `SHOW_NAMEPLATES` | Alt, held or toggled (Client Settings) | every nameplate |
| `MOVE_MODE_WALK`, `_RUN`, `_JUMP` | 1, 2, 3 | movement mode (local planner turn) |
| `TURN_LEFT`, `TURN_RIGHT` | Shift+A, Shift+D | turn (local planner turn, when rebound away from boosted camera keys) |
| `TWIST_LEFT`, `TWIST_RIGHT` | Shift+A, Shift+D | torso twist (local FIRING turn, when rebound away from boosted camera keys) |
| `UNDO_LAST_STEP` | Backspace | undo a step (planner) or the last attack (FIRING) |
| `CLEAR_ORDERS` | Delete | clear the route (planner) or the orders (FIRING) |
| `PLAYBACK_TOGGLE`, `_PREV`, `_NEXT` | Space, Left, Right | report phases: pause and step the playback |
| `CENTER_ON_SELECTED` | Space | other phases: locate the acting or inspected unit |
| `REPORT_KEY_NEXT`, `_PREV`, `REPORT_KEY_SELECT_NEXT`, `_PREVIOUS`, `REPORT_KEY_FILTER`, `REPORT_FILTER_KEY_SELECT_NEXT` | N, Shift+N, Ctrl+N, Ctrl+Shift+N, Shift+F, Ctrl+Shift+F | while the log is open: find and filter by report keyword |

Camera binds (`GpuBattleView`): `SCROLL_NORTH/WEST/SOUTH/EAST` (W, A, S, D, held), `CAMERA_ROTATE_LEFT/RIGHT` (Q,
E, held: 90 degrees per second), `CAMERA_TILT_UP/DOWN` (Page Up, Page Down, held: 60 degrees per
second), `TOGGLE_ISO` (T: Tactical View), `ZOOM_IN/OUT` (numpad + and -, a step of 1.2, also the Menu's zoom items),
`CAMERA_RESET` (Home), `CAMERA_FIT_BOARD` (End) and `ZOOM_OVERVIEW_TOGGLE` (Z).

Hold either Shift key for 4× keyboard camera speed, including when Shift is pressed or released during movement.
Normal panning is 750 screen pixels per second before zoom scaling. Free Flight moves 1.5 hex heights per second;
WASD moves along the view and Q/E descends/ascends. Shift plus an unmodified camera bind takes priority over the
unit's step, turn and twist shortcuts in every camera mode, including rebound camera keys. Text fields and pending
dialogs still take their input first; mouse gestures keep their existing speeds.

Forwarded to MegaMek on purpose, among others: `NEXT_UNIT`/`PREV_UNIT` (Tab, Shift+Tab), the weapon and target
cycling binds, `FIRE`, `PHYS_PUNCH`, `PHYS_KICK` and `PHYS_PUSH` (they declare at once, because the native window
skips the "To hit" question), `MINIMAP` and `PLANETARY_CONDITIONS` (menu accelerators that toggle the preferences
the minimap and the conditions card follow), and in TARGETING and OFFBOARD the targeting display's own binds.
`GpuHudRoutingSmokeTest` presses every default bind in 14 phase and turn scenarios and checks that exactly one
handler runs.

## 5. The Tactical View

The Tactical View is the board seen straight down. The scene, picking and animation
timeline stay the 3D view's own; every unit is its flat icon on a base. The terrain is the 3D Graphics setting's (Classic
the tileset columns, Enhanced the shaded 3D terrain), without the scatter.

- **Switch it** with `TOGGLE_ISO` (T), the "Tactical view" utility, the Menu's "Tactical view" item, or the chip's
  "Back to 3D". Entering it ends Free Flight; switching Free Flight on leaves it. The chip ("Tactical view" with "Back to 3D") shows centred at the top when the top row has room, and
  the north mark "N ↑" centred below it, under the board labels.
- **Overlays panel** (`GpuTacticalOverlays`; b11 design, placed as the user chose on 2026-10-10). Each row is one
  setting, the same wherever its overlay is drawn, and the panel keeps none of its own: the View rows show and run
  the View menu's items (both views, Swing and the Menu panel), the Tactical View rows are client settings that
  `BoardSource.UiPreferences.Overlays` publishes to the panel and to the passes. Defaults: everything on except Level
  shading and Distance rings; the panel starts expanded, then H or its header switch it and the state is remembered.
  - Status badges off keeps each base's ring, notches, elevation or altitude pill and wreck cross
    (`GpuUnitBases.BaseSpec.withoutStatus`).
  - Rear arcs off: the source picks no arc target, so neither the amber arcs nor the REAR chip show.
  - Fields off leaves the 3D view's mid-edge ticks; hovering or selecting an emitter still opens its field and pulse
    (`ecm-field.frag` `u_fieldsAll`). Jamming off hatches only an open field (`u_hatchAll`).
  - Level shading (off by default) washes each hex 7 % toward a warm white per level above the board's lowest, at most
    21 %, in the ground layer under its marks, from the elevation its instances already carry (`hex-grid.frag`
    `u_levels`; `GpuHexGrid.LEVEL_SHADE`).
  - View > Unit Links (`ShowUnitLinks`) hides the C3 lines of the captured board (3D, Tactical View and Legacy 2D) and
    the spotting and TAG links of the board labels.
  - View > Hex Types (`showHexTypes`, off by default; the Type row) writes each hex's type at its bottom edge in every
    view; see [GPU overlays](gpu-overlays.md#point-markers-and-labels). A structure's line carries its height
    (`MEDIUM▲5`), so with it on the Tactical View's roof chip shows `CF n` alone and no large depth number is drawn;
    off, the chip stays `CF n ▲h`.
  - The panel floats over the board's top-right corner; the camera's framing leaves no room for it. The utility bar's
    Jamming and Ranges buttons are gone (their settings are rows).
  - Tests: `GpuTacticalOverlaysTest` (rows, settings, H, placement at 1920 x 1080 and 1280 x 800);
    `GpuTacticalViewSmokeTest.theOverlaysPanelDrivesItsOverlaysAndStandsBesideTheRightColumn` (each Tactical View row's
    overlay on the mock-up battle with the game's View menu; `tactical-overlays-*.png`); `GpuElectronicWarfareSmokeTest`
    (Fields and Jamming).
- **Camera.** `BoardCamera.setTactical(enabled, scene)` saves the full 3D pose (focus, zoom, azimuth, tilt, whether
  the view fits the window) and turns the same orthographic camera straight down. Pan, zoom, reset, fit
  and automatic framing work as usual; framing keeps the top view. Zoom has the
  3D view's limits.
  Leaving restores the saved pose exactly; after a board change it fits the new board from the saved angle. The flag
  is render-thread state and is never saved.
- **Units: icons on bases** (`GpuUnitBases`, `MeepleVisual.icon`). T forces the token display (`GpuBattleView.displayMode`) with
  each unit's flat icon, unlit and casting no shadow, over the board and its markers; the units are recreated live, and
  leaving brings the chosen display back. Each listed unit gets a base model drawn
  before the units (a ring in its side colour, mint own and allied, coral enemy; an acting or focus glow; a pulsing
  outer ring while it can act now; a facing notch and a thin twist notch that turn with the animated pose) and a badge
  model after them (heat pip, elevation or altitude pill, damage tile and armour/structure bars, TMM chevrons in the
  movement colour, the two most severe condition icons, the E(C)CM source badge, a destroyed unit's cross). Sizes are
  in apothems of the hex; badges fade between 40 and 20 px per hex. A multi-hex unit has one base: `UnitFootprint.oval`
  fits a ring around a stitched ship's opaque art, nearly round for a Union and long for an Aurora, and without a stitched
  image around its hexes. The notches and badges lie on its rim, the wreck's cross across it, and the selection marks of
  `GpuBoardOverlay` follow the same oval. A unit's models are
  rebuilt only when its `BaseSpec` or oval changes; each frame moves them with the pose. Labels hang from the ring's north
  point, the nameplates show tags only, and picking is the icon's.
- **Ground layer** (`GpuHexGrid`, `hex-grid.vert/.frag`), drawn right after the opaque terrain: cliff strokes with
  hachures (`BoardRim.high`, Cliff Top exits included), 1- and 2-level step lines, woods and jungle tints, roofs with
  their outline and the "CF n" chip, water shaded by depth with its depth number, smoke and fire, and the hovered or
  focused enemy's rear arcs. Liquids lie a hair under their surface and the rest on a plane above the board, so a
  bridge deck covers the water fill.
- **Terrain.** The 3D Graphics setting chooses the terrain, not the view ([GPU board](gpu-board.md#graphics-classic-and-enhanced)),
  and `GpuTerrain.setScatterHidden` hides the scatter, grass and biome vegetation at draw time
  ([GPU board](gpu-board.md#tactical-view)).
  Picking is unchanged by that rule. The wireframe view and the Tactical View exclude each other: turning one on turns
  the other off. The minimap shows the tileset art.
- **Board overlay.** One palette serves both views; the route's ghost is the translucent icon, and the front arc and
  the selected weapon's wedge show as in 3D. Cliff strokes belong to the ground layer.
- **Tests.** `BoardCameraTacticalTest` covers the camera, `GpuUnitBasesTest` the bases' spec and fade rules,
  `GpuHexGridTest` the ground layer's codes. `GpuTacticalViewSmokeTest` renders a 12 x 10 board with woods, jungle,
  buildings, a fuel tank, an industrial hex and a bridged river at 1920 x 1080 and enters and leaves through the real T
  bind. It checks the restored pose, the icons and their bases, the fixed light, that the icons cast no shadow, that one
  frame submits no scatter or grass draw while a click on every hex picks the same hex and floor as with the scatter
  shown, in Enhanced and Classic, picking on the columns' art and the icons, and field-of-view dimming;
  it captures the mock-up battle in both graphics. `GpuGraphicsModeSmokeTest` checks that T starts no rebuild.

## 6. Zoom-out unit scaling

Zoomed out, the 3D view's units grow so they stay readable (`UnitScreenScale`).

- While a hex is at least `THRESHOLD` (80) HUD pixels wide on screen, units keep their size. A HUD pixel is a window
  pixel divided by the display scale (`hexPixels(zoom, scale)` = `BoardGeometry.WIDTH / (zoom * scale)`).
- Further out, the factor is `threshold / hexPixels`, capped at `MAX` (2): a unit stays as large on screen as at the
  threshold, until it is twice its size. `ENABLED` (true) switches the rule.
- The growth multiplies the placed transform on every axis about the unit's feet, on top of `UnitFamilyScale`. Meshes
  keep their canonical size and proportions.
- Picking, bounds, outlines, anchors and labels, attack points and the route ghost follow the grown unit. The gait
  and the wheels use the grown scale too (`UnitAnimator`), so feet stay planted.
- Sensor contacts, the parts of a large unit and units across several hexes keep their size. The Tactical View's
  icons never grow.
- The constants are the defaults of the values in use (`UnitScreenScale.enabled`, `threshold`, `max`). The Tuning
  utility's Board page edits them under "Zoom-out unit scaling"; Defaults restores the constants.

Tests:
- `UnitScreenScaleTest`: the rule, the HUD-pixel measure across display scales, the uniform growth and a planted foot
  of a grown walking biped.
- `GpuUnitScreenScaleSmokeTest` zooms the running view out from the threshold. The Atlas stays 46.9 pixels tall on
  screen on hexes from 80 down to 40 pixels wide and 31.2 pixels at 26.7 (past the cap). Its anchor keeps pace, a
  point above the ungrown Atlas's head picks it, and the tuning rows change the growth on the next frame.
- `GpuLivePlaybackSmokeTest` measured the real Atlas's stride: 25.57 world units at its size and 50.64 grown twice.
- All on Windows 11 with an Intel Iris Xe.

## 7. Unit panel and paperdolls

The unit panel replaces Swing's Unit Display window in GPU mode.
- **Cards** (`GpuUnitCard`): the own unit at bottom left and the inspected enemy or ally at bottom right. Each shows the paperdoll with a
  number per location (a Mek's rear armor in a strip under it), four vitals rows by unit family, one line of chips
  for what the doll cannot show, and the "Unit record" and Locate buttons. Hovering the doll shows a hover card. An
  card has a ✕ that clears the selection (the cleared selection, section 1.8).
- **Sheet** (`GpuRecordSheet`, `GpuUnitSheetTabs`): six tabs, opened by `UNIT_DISPLAY` and F1-F6. Its close button
  sits at the right end of the tab row; while it is open the card shrinks to its one-line mini form.

| Tab (key) | Content |
| --- | --- |
| Status (F1) | this round, pending changes, heat, movement, conditions |
| Crew (F2) | the pilot and the crew's advantages |
| Armor (F3) | front armor beside rear armor over structure (Meks), or one drawing |
| Weapons (F4) | the weapons, with ammunition and mode controls; an own unit's rows are a reorderable `UiList` (the grip drags a row, Alt+Up/Down moves the selected one) into its custom weapon order |
| Critical table (F5, Meks) or Systems (F5, other units) | the record-sheet slots (`GpuCriticalTable`) or the systems |
| Extras (F6) | sensors, networks, transport and the unit's readout |

- **Critical table** (`GpuCriticalTable`, Meks only): each location's slots in blocks of six under a bold heading with
  its CASE tag. Slot names are record-sheet names (`RecordSheetSlotNames`), and an empty slot reads "Roll Again". A
  hit or destroyed slot is struck through in coral, a slot of a blown-off or breached location in grey; empty and
  filler slots are dimmed. Ammunition shows its dots and a count badge, and a box shows the engine, gyro, sensor and
  life support hits.
- **Interaction.** A click on a location of the sheet's dolls (or Enter on the Armor tab's dolls) opens the Systems
  tab, a Mek's critical table, and briefly flashes that location's block; nothing is selected or filtered. Hovering a
  weapon row, slot or doll region outlines the location on every visible doll. The sheet's slot popover runs the
  record service's unit actions (modes, ammunition, dumping and the like) for the unit shown.

**Paperdoll pipeline.** The HUD reads platform-neutral JSON at run time: no SVG, Batik or AWT
(`GpuPaperdollsTest.everyFileParsesWithoutDesktopClasses` parses every file in a class loader without desktop
classes).

| Step | Where |
| --- | --- |
| Source art: 41 SVG views, copies of MekBay's location cut set | mm-data `tools/paperdolls/src` |
| Offline converter (Batik, validation, review PNGs) | `megamek.utilities.PaperdollConverter`, Gradle task `convertPaperdolls` |
| Output: one JSON per family and view (format 2: regions, triangles, outline rings, label anchors, value boxes, hull, shield variants) | mm-data `data/images/paperdolls`, with its README (licence, provenance, format, validation) |
| Review output | mm-data `tools/paperdolls/review`: a PNG per family and `validation.txt` |
| Staging | `stageDataImages` copies `paperdolls/` into `megamek/data/images` |
| Runtime | `GpuPaperdolls` (libGDX `JsonReader`, lazy per family, view and shield arms); `GpuPaperdoll` draws one view |

- The family comes from `GpuPaperdolls.family(Entity)`, the record-sheet choice of MegaMekLab. A family without JSON
  falls back to tiles.
- Biped and tripod front armor has shield variants per arm; a mounted shield shows its capacity and absorption
  numbers.
- To regenerate, edit the SVG copies, then run from the MegaMek checkout:

```powershell
.\gradlew.bat :megamek:convertPaperdolls
```

  The task reads `tools/paperdolls/src`, writes the JSON, the review PNGs and `validation.txt`, and writes no JSON for a
  family whose checks fail. Look at every review PNG after a change. The checks (codes, overlaps, the hands inside the
  arms, mirroring, anchors, shields) and the recorded corrections are in the JSON folder's README.

## 8. The UI toolkit

`megamek.client.ui.gdx` holds the hud-v3 design system and its generic widgets, for any native view.

| Class | Provides |
| --- | --- |
| `UiTheme` | colour tokens (`TEXT`, `MUTED`, `MINT`, `CORAL`, `AMBER`, `BLIP`, ...), the fonts, the icon sheet, the `hud-*` styles, the drawables `HudFrame`, `EdgeBox` and `Dashes`, the builders `box`, `flat`, `font`, `scrollStyle`, and the registration guard `add(name, resource, type)` |
| `UiKit` | `label`, `caption`, `icon`, `panel`, `header`, `closeButton`, `dialog`, `footer`, `empty`, `button`, `segmented` (tabs, separate segments, or `hud-pill`: one pill split in sections), `select`, `checkbox`, `menuRow`, `chip`, `search`, `meter`, `scrollList`, `tip` (hides when its actor or panel hides, so no tooltip stays stuck), `fill`; statics `text`, `onChange`, `size`, `ownEvents` (a control inside a clickable row, such as its remove ×, keeps its press and click to itself); `DIALOG_WIDTH` (620) and `DIALOG_MARGIN` (140) |
| `UiButton` | the button with icons, detail lines, a pressed state, a badge and a dot; the only toolkit class that may be subclassed |
| `UiPopover`, `UiMenuList` | the popover frame (opens at a point or above an anchor, closes on an outside press) and its keyboard menu list |
| `UiList` | a vertical list of any rows; `reorderable(moved)` turns on drag-to-reorder by each row's handle (`add(row, handle)`): the lifted row follows the pointer above everything with a shadow, bounded to the list (and a scroll pane's view of it), a slot opens where it would drop and the other rows slide (springs), a scroll pane scrolls near its edges; `move(index, delta)` flies a row as the keyboard asks; `cancel()` (Esc) sends a dragged row home; `busy()` while a row moves. The view hears one `moved(from, to)` once the rows rest; the list keeps that order until the view gives it new rows and glides back after a second when none come |
| `UiTree` | a tree of list rows (`hud-plain`, the editor's Layers look) to any depth. A `Node(id, label)` (a record: the id is unique and is also the row button's actor name) has an optional second line, icon, tooltip, search `keywords`, trailing `Action`s (a red delete × or a quiet icon button, heard by `onAction(id, name)`) and children. `nodes(model)` shows a model: an equal model changes nothing, and a row whose node did not change is kept with its hover and tooltip. A chevron expands or collapses a node with children (nodes start expanded); `expandAll`, `collapseAll`, `expand(id, open)`. Single selection: a click, or Enter, selects and `onSelect` hears it; the view's `select(id)` is silent, expands the ancestors (also of a node the next model brings) and scrolls the row into view in the surrounding scroll pane at the next layout (no frame late). `onHover` hears the row under the pointer; `highlight(ids)` shows another view's hover on rows. While the tree holds the keyboard focus (a click gives it), Up/Down/Home/End move the keyboard's row (the hover look), Left collapses it or goes to its parent, Right expands it or goes to its first child, Enter selects it. `filter(text)` shows the nodes whose label, second line or keywords contain the text, with their ancestors, expanded, and restores the expansion from before when cleared; `empty(text)` shows when no row does. `reorderable(tip, moved)` makes siblings next to each other with the same `stack` one `UiList` whose rows (with their descendants) drag by a grip, `moved(id, target, before)`. `controls(placeholder)` is the Client Settings sidebar's tool row: a search field and Expand all/Collapse all (`unfold-more`, `unfold-less`). Expansion, selection and the keyboard's row are kept by id; nothing runs per frame. No virtualisation (no list of the kit has one to reuse; `UiCardGrid`'s is for equal cards) |
| `UiFlow` | wrapped runs of differently styled text |
| `UiCursorCapture` | hides the cursor for a relative drag (`UiNumber`'s caption scrub, `UiAngleDial`, the board's, the minimap's and the lobby preview's orbits) and wraps it at the window's border, a tenth of its size inside so a fast move cannot carry it into view. `release()` puts it back where the press began (`beganAt`) and only then shows it. The drag reads its pointer through `trusted(x, y)`, which reads the live cursor and makes any jump in the same step, so no jump reaches the drag; once the button is up the pointer stops, so nothing moves the drag after the release. While the cursor is hidden (`pointerHidden()`) the HUD and the hover ignore it, and its return move refreshes the hover after the release. Wayland cannot move the cursor, so there it stays visible and the drag stops at the screen edge. |
| `UiChoiceGrid` | equal-size visual choice cards, created with `UiKit.choiceGrid(cardWidth, gap)`; `fit(availableWidth, availableHeight)` chooses columns from the content and viewport, allows up to 50% growth when the complete grid fits, and adds columns before vertical scrolling. `UiKit.fitChoices(dialog, scroll, grid, width, height)` also measures dialog chrome. Reflow preserves the original actors, actions, keyboard focus and loaded previews. Used by the editor's library, material cards and theme gallery |
| `UiKit.fitSearch` | collapses an optional search row and its vertical padding when no choices are hidden; keeps an active filter visible until cleared. Measure choices without search first, then pass actual paging/overflow so the search bar cannot cause its own overflow |
| `DisplayScale` | the one scale rule (1.7) |

Rules (checkstyle enforces the first):
- Toolkit code imports only `java` (no AWT), libGDX without its backends, `Messages`, `MMLogger` and its own package
  (`config/checkstyle/import-control.xml`). Only the test harness `UiTestStage` may open a desktop window. The guard
  does not see fully qualified names.
- Toolkit classes are `public final` (except `UiButton`), use no battle vocabulary, hold no static GL object, and take
  every user text from the caller. Tokens are read-only: derive colours with `UiTheme.alpha` or `tint`.
- Battle-only widgets stay in `GpuHudKit`: unit sprites (masks tinted #E4EBE7 for friends, coral for enemies), unit
  rows, letter tiles, heat bars. Components reach the toolkit through `kit.ui`.
- A widget or style with one user stays in its component; the change that adds a second user moves it into the
  toolkit under a generic name.
- No private copies of toolkit code: before finishing a change, run this on the changed files and justify every hit.

```text
grep -nE "extends BaseDrawable|new ScrollPane\(|M00\]|fontScale|private static (String text|Color (alpha|rgba|css|tint))|new ChangeListener|new TextTooltip\(" <files>
```

Fonts: the `hud-*` faces are named instances of the shipped Roboto variable font, generated at 4x and drawn at a
quarter. Characters Roboto lacks come from Noto Sans Symbols 2, then Bitter Bold (the arrows U+2190-U+2193, at 0.8 of
the size), merged into the same font. Icons are Material Symbols Rounded glyphs baked once into one mipmapped sheet.

**How to build a new view with the toolkit:**
1. Depend on `megamek.client.ui.gdx` only. Pass in every text (`UiKit.text(key, args)`), the fonts directory and the
   scale.
2. In `create()`, on the window's GL thread: `UiTheme theme = new UiTheme(Configuration.fontsDir());` (fonts, glyph
   pages, icon sheet, every `hud-*` style; one theme per window) and `UiKit ui = new UiKit(theme.skin);` (it owns
   nothing).
3. `Stage stage = new Stage(new ScreenViewport());` In `resize`: `float scale = DisplayScale.read(preference,
   contentScale);` with the window's own UI scale captured on the EDT and the window's content scale (desktop:
   `GpuDisplayScale.contentScale()`), then `((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
   stage.getViewport().update(width, height, true);`.
4. Panels: `Table panel = ui.panel();` then `panel.add(ui.header(title, count, ui.closeButton(this::close)))`,
   a list in `ui.scrollList(list)` with rows from `ui.button("hud-row", null, text, null)`, `ui.menuRow(...)` or
   `ui.empty(list, text)`, and `ui.footer(panel)`. Centred dialogs: `ui.dialog(title, close)`.
5. Controls: `UiButton button = ui.button(style, icon, text, sub); UiKit.onChange(button, action);` show state with
   `button.pressed(...)`; also `ui.segmented`, `ui.select`, `ui.search`, `ui.chip`, `ui.meter`, `ui.checkbox`;
   `ui.progress(caption)`, a meter whose `set(done)` shows the percentage and the mint share and whose
   `caption(text)` names the running step (the map views' print card); tooltips through `ui.tip(actor)`.
6. Custom drawing: `ui.fill(batch, color, alpha, x, y, width, height)`, `UiTheme.pixelScale(batch)` and `snap`, the
   drawables `UiTheme.HudFrame`, `EdgeBox`, `Dashes`.
7. A view's own styles go into the same skin through `theme.add(name, resource, type)`, `theme.box(...)` or
   `theme.font(...)`; a name the toolkit owns throws.
8. `dispose()`: `stage.dispose()`, then `theme.dispose()`, which frees every texture, glyph page, font and generator.
   Never share a theme between two applications.
9. Test with `UiTestStage.run(...)` (a hidden 1920 x 1080 window with a theme from `data/fonts`, which fails when a
   texture outlives the test or a label shows a missing message key), `UiTestStage.place`, `capture` and `compare`,
   as `UiGallerySmokeTest` does.

## 9. Swing in GPU mode

The board editor's Open, Save, Save As and Save As Image use `UiFileDialog` in the window's Scene2D stage. The Open
flow's unsaved-change, save-validation, overwrite and error prompts also remain in that stage. `GpuEditorFiles`
owns that interaction on the EDT and publishes an immutable prompt; replies are accepted only for that prompt
and board. `BoardEditorSession` still loads/saves boards and updates Recent Boards. Other Swing workflows below
are unchanged.

Save opens the in-scene picker for an unnamed or imported board; subsequent Save/Ctrl+S writes its current file.
Save As always opens the picker with the current filename and the remembered board folder. Selecting another file
confirms replacement in-scene, and a successful Save As becomes the destination for later saves.

`UiFileDialog` is shared by the Tuning lighting-environment picker and can be used by any desktop Scene2D scene:

```java
// Construct on the render thread, alongside the scene's UiKit and Stage.
UiFileDialog files = new UiFileDialog(ui);
files.show(stage, new UiFileDialog.Options(title, UiFileDialog.Mode.OPEN,
      "my-file-purpose", defaultFolder, "", new UiFileDialog.Filter(description, "png", "jpg")),
      path -> { if (path != null) { load(path); } });
// Close with the scene, before disposing its stage/skin.
files.close();
```

The callback runs on the render thread, once, with a path or null on cancellation. It does not perform the actual
file load/save. SAVE mode appends the filter's first extension when needed and confirms replacement before returning
an existing file. Directory enumeration and path validation run on workers; cancelled or superseded results cannot
update the scene. `close()` cancels outstanding reads without calling back into a disposed scene. The picker borrows
the stage/skin and owns no GL resources.

Locations are stored in Java's per-user preferences, under `megamek/client/ui/gdx`, using the caller's purpose key.
Boards and lighting environments have separate keys; successful navigation is remembered even on Cancel. A missing
location falls back to its readable ancestor, then the caller's folder/home. Drives, UNC paths and Unix paths use
Java NIO; no native dialog or AWT filesystem service is involved. The picker offers Back/Forward/Up, locations,
an editable path, filename and search. Ctrl/Cmd+L focuses the path, Ctrl/Cmd+F search, Alt+arrows navigate, F5 refreshes,
Tab/Shift+Tab traverse controls, Enter opens and Escape cancels. There is no hidden-files toggle.
Selecting a folder enters it when Open/Save is activated, including after tabbing to the button. Editing the
filename clears that selection; navigating folders in SAVE mode retains the suggested filename.
Paths retain Unicode text; the glyphs shown in filenames depend on the supplied UiKit fonts' coverage.

Scenes with their own input dispatch must check `UiModal.active(stage)` before scene shortcuts, focus that modal,
route keys to the stage, and suspend held camera input while it is active. `GpuMapHud` and `GpuHud` do this. Ordinary
stage input dispatch needs no extra picker-specific handler. Continue acting/drawing the stage and updating its
viewport on resize; the picker sizes itself in stage units. The battle HUD preserves existing game-dialog key
bindings, and an incoming game prompt leaves the higher picker's focused field intact.

Coverage: `FileDialogPathsTest`, `GpuEditorFilesTest`, `UiFileDialogSmokeTest`, and `GpuFileDialogSmokeTest` cover
filesystem/path handling, document preservation and stale replies, rendering/focus/resize/fullscreen, and the two
real HUD integrations. `GpuHudInputTest` and `GpuChatToastModalSmokeTest` check game-dialog key routing and focus,
including a prompt arriving underneath another modal. Desktop execution is verified on Windows only; Linux and
macOS runs remain required for platform verification. The Windows run skips symlink creation when the account lacks
that privilege.

`GpuFileDialogSmokeTest` also drives the editor's Save and Save As buttons in fullscreen, reloads the written
boards, checks overwrite cancellation, remembered folders and dirty state, and verifies Ctrl+S after Save As.
The save test isolates folder preferences and Recent Boards from the player's settings.

While the native window is active (`GpuBoardWindow.isActiveFor`), the HUD replaces the battle view's Swing surfaces.

Native startup, including scenarios, constructs neither the Swing Unit Display panel nor its dialog. Weapon and
ammunition selection and the firing solution live in `UnitDisplayState`, owned by `ClientGUI` on the client thread.
Phase controllers and GPU commands use that state directly. Shared weapon calculations, equipment actions and unit
text live beside it in `clientGUI.unitDisplay`; they have no dependency on the legacy inspector panels. The Swing
inspector is created only when requested with the Legacy 2D board and renders the same state.

| Swing surface | Native replacement |
| --- | --- |
| Menu bar | Menu utility (`GpuMenuPanel`) |
| Minimap window | `GpuMinimap` |
| Player list, bot commands | Players panel |
| Unit Display window | unit card and sheet |
| Force display, unit overview strips | forces panel and forces overview |
| Rounds in the air | the log's artillery in flight |
| Chat box overlay | chat panel |
| Toast overlay | toast stack |
| Keybindings overlay | Help |
| Planetary conditions overlay | Conditions card |
| Turn details overlay | the dock's Steps tooltip |
| Prompts and choice lists | the modal dialog (1.6) |
| Bot orders' hex picker dialog | the hint line's pick: the board's clicks, the Done key and Esc (2.1, 2.3, 4); in narrow windows the pick's chip with Done and Cancel |
| Off-board target arrows (`OffBoardTargetOverlay`) | the dock's off-board targets in the local TARGETING turn (3); several units beyond one edge are the modal's choice |

- `ClientGUI` keeps the minimap, player list, rounds in the air, force display, bot commands and Unit Display windows
  hidden while the native window is active; they show again on the Legacy 2D board as their preferences say.
  Native LOS uses the EDT-owned `RulerModel` and the shared `LOSModifierCalculator`; `GpuLosPanel` presents its
  immutable snapshot. The card follows the ray's midpoint using the existing floating-card placement, keeping
  24 stage units clear of the full projected ray and endpoints when space permits, including expanded details. It shows
  hex range, LOS status, both heights and Flip in a compact 240-unit-wide card. Applicable From → To modifiers and
  their causes appear directly below the status (for example, `+2 · target in heavy woods`), derived from the same
  engine result as the full explanation; Flip and height changes refresh them. Details expands endpoint/unit
  selection, terrain, both LOS directions and rule comparison; it is collapsed by default. Lock/unlock icons sit
  beside From/To; comparison uses one row per rule with full explanations on hover. Height controls
  reuse UIKit's `UiNumber` (caption click opens a slider; caption drag scrubs; typing commits on blur/Enter).
  The slider popup displays the live numeric value beside its track, including during caption scrubbing.
  Native heights are LOS levels above each hex: zero is the surface, and the ray and calculation use the same
  absolute level. Actual units use the game's entity LOS path, including in rule comparison. The Legacy 2D ruler's
  one-based TW height convention is converted only at its calculation boundary.
  The ray is a six-pixel stroke without a black outline, shared by both native cameras: green until the first
  intervening terrain modifier, solid yellow from that hex's entry edge onward, and dashed red after a blocker.
  Both transitions come from the engine's chosen LOS path and update with Flip and height changes. Both elevated
  endpoints have the same vertical ground tethers as airborne units, drawn in the same depth-tested pass. The Swing
  `RulerDialog` and elevation diagram are constructed only for the Legacy 2D board and disposed on switching to native.
- The frame carries no Swing overlay layers, so each toast shows once, in the native stack.
- **Allowlist.** `GpuBoardWindow.allowedOverBattle(Dialog)` decides which Swing dialogs may still show over the
  presented window: the classes in `GpuBoardWindow.SWING_DIALOGS` (the Menu's settings and tools, the Game Master
  editors, the accessibility window, the Nova network view, Readme and
  Help and the planetary conditions editor), file choosers, a game master's
  choice of the player to reinforce, and the message boxes the user kept, known by their titles: the exit's save
  question, About, the bug report and its package result, and a reinforcement file's version check. A dialog that
  opens inside one of these (owned by it, or while it shows modally) belongs to it. An allowed dialog is raised over
  the native window. Any other is logged at error level and not raised, so it may open behind the window. While the
  window cannot ask the client's prompts itself (before its board source exists, or while it closes), the prompts
  show their Swing dialogs and every client dialog is raised. `GpuSwingDialogGateTest` drives the routed prompt
  chokepoints, the dialogs made native and a list prompt of each routed class with the window presented, and the same
  openers on the Legacy 2D client.

### The map tools of the board editor and the map preview

The board editor's 3D view and the lobby's map preview run the same `GpuBattleView` over a `GpuMapSource` without a
battle session. `GpuBattleView` holds its HUD as a `GpuBoardHud`: the battle HUD (`GpuHud`) over a `GpuBoardSource`, else
`GpuMapHud`. Shared map utilities provide Tactical view, Wireframe and Tuning; the editor adds its editing panels
and settings.
The preview keeps the hovered hex's card in the bottom-right corner; plain left-click does not pin it.
Right-click pins the card to that hex while its native menu is open: Top view, Isometric view, Fit board,
then Line of Sight after a separator. Closing the menu resumes hover inspection. The
camera presets are plain actions that change orientation while preserving Tactical View and wireframe settings. The
LOS action starts a new ruler at the clicked hex and pointed height. Alt + click also starts LOS, and a plain click
completes the second endpoint. These gestures also work with an editor tool active, without editing either hex.
`GpuMapSource` uses the same `RulerModel` and `GpuLosPanel` as battle mode.
Changing maps clears the measurement and rejects commands from an older board generation.

The lobby's 3D Preview (`LobbyBoardPreviews`) shows the lobby preview game's board. A roll of the map settings, a map
change and a server-built battlefield update it without bringing it to the front (`GpuBoardWindow.updatePreview`). It
belongs to the lobby window, and the lobby's end (Done, the game's start, leaving) closes it. It is 3D only: its View >
Renderer offers Classic and Enhanced, not Legacy 2D (`GpuBoardWindow.openLobbyPreview`). A file or start-screen 3D preview's View > Renderer > Legacy 2D still switches: it closes the 3D preview and opens a separate 2D
window of the same board, whose Renderer menu returns to 3D.


## 10. The developer Tuning utility

A developer and tester tool, to be removed before release.
- **Button:** "Tuning", before the icon-only Help (?) and Menu buttons at the far right in gameplay. Gameplay,
  preview and editor share Top view, Isometric view and Fit board controls and compact, 34-unit-high buttons.
  Fit board accounts for the current panel clearances. The gameplay right column starts one gap below the utility
  row and fills the available height; the phase header moves below that row when both cannot fit across the window. Tuning opens
  at the same top, 360 wide and ending at least 70 above the bottom, with the pages Display, Board, Atmosphere, Terrain
  and Camera, and in its footer Defaults, Reload assets with its outcome, and Edit shaders (the GLSL editor).
  During Reload assets, an area above the footer shows discovery, checking/copying progress, the checked/total
  file count, copied count and current relative filename (hover for the full path). Discovery and later artwork
  reload stages pulse without a percentage; file progress uses the actual inventory total. The display keeps
  updating while board snapshots are paused, and disappears when the reload succeeds or fails. The
  Terrain page opens the Material palette (`GpuMaterialPalette`): a native gallery card over the battle with every
  board material on a flat tile, a cube or a sphere, a few drawn each frame (the chosen shape first, then the others,
  kept until it closes so a change of shape shows at once), drawn by the model shader with its whole finish (`GpuMaterialSwatches`,
  `GpuAssets.finish`) under the default daylight. It orders them by folder, similarity (a chain of nearest looks:
  mean colour and contrast on a 4 x 4 grid of each swatch), brightness (the material's own pixels), map bytes on disk
  or newest file; a tooltip gives the albedo size, map bytes and change time. Its Reload assets button requests the
  board's asset reload, as the footer's does, and draws the palette again from disk.
- **Model:** `GpuBoardTuning` keeps every value as a Scene2D control that is never drawn; `GpuBattleView` reads the
  values from it. `GpuTuningPanel` mirrors the model's rows in the hud-v3 look, and an edit goes to the model's own
  control. Its tooltips are the model's texts; the row texts are English literals.

| Page | Sections |
| --- | --- |
| Display | Display mode Windowed/Fullscreen, VSync, Render resolution 50-100% with its scene size, UI scale 50-100% (applied when the drag is released), Anti-aliasing Off/SMAA, Specular anti-aliasing, Normal maps (on until chosen), Ambient occlusion (off until chosen), the detected graphics and the Graphics card choice with its in-use and pending notes, Terrain cache with its live A/B switch and status. Resolution, UI scale, both anti-aliasing choices, normal maps and ambient occlusion are saved across sessions; Defaults leaves the whole Display page alone |
| Board | Geometry (with Unit display), Unit family sizes, Zoom-out unit scaling, Route pulse (on/off, speed, intensity), Unit visibility, Outside field of view, Outside sensor range, Unit damage |
| Atmosphere | Atmosphere presets with "Planetary conditions…" (the native conditions card), Lighting (time of day, exposure, moonlight, fixed sun, the lighting environment and Show lighting sky, not saved, which shows the sky that lights the scene, or its image, as the backdrop), Planet properties, Clouds and ground air, Weather effects, Light and fog effects |
| Terrain | Terrain build progress, Hex joins (Hex transitions, Hex padding), Water (Cliffs directly into water), Terrain detail (Grass blades, Water effects). Terrain and object detail levels are always on |
| Camera | Fixed sun/moon, framing on selection, framing on movement (combat framing is the dock's Follow toggle); the model's camera rows: Free Flight, the thermal wireframe view, the Free Flight field of view; the live view as one line with Copy (`BoardCamera.readout`: `cam ortho\|persp\|fly fov az tilt span at x,y,z hex`) |

The Terrain cache section reports the ground material cache's render-thread state: building/rebuilding, prefetching,
ready, partial coverage, inactive, disabled or unavailable. Its percentage is the share of **admitted view pages**
that are current, including walls; it is not a whole-board or time estimate. The readout also shows requested/admitted
pages, cache level, occupied/allocated slots, stale and partly built pages, pending prefetches and any wall pages
outside the page table. Off-view stale pages wait until needed, and prefetches can remain pending when storage is full.
GPU allocation is an estimate from texture formats and mip levels, fill targets and page-table buffers; it excludes
driver overhead and unrelated terrain resources. Page storage and its occupied share are shown separately, including
rebuild spares. Turning the checkbox off uses direct rendering and pauses fills while retaining storage and pages;
turning it back on resumes the cache without a geometry rebuild. The choice lasts for this window, survives asset
reloads, and respects startup cache-disable flags. Diagnostics refresh only while Display is open, resolve the current
renderer after asset reloads, and retain the completed plan's counts when an unchanged frame skips cache planning.

**Removing it before release:**
1. Delete `GpuTuningPanel`, `GpuTuningPanelSmokeTest` and `BoardCamera.readout`.
2. In `GpuHud`, delete the tuning block (its comment says so) and the `tuningModel` constructor parameter, and drop
   the argument in `GpuBattleView.createBoard`.
3. Delete `GpuHudState.Dialog.TUNING` and the `GpuBoard.hud.tuning.*` message keys.
4. Remove the panel steps from the tests that click `tuning-button` or measure it: GpuAtmosphereSmokeTest,
   GpuBoardSmokeTest, GpuHudLayoutSmokeTest, GpuHudRoutingSmokeTest and GpuPlanetaryConditionsSmokeTest.
5. Keep `GpuBoardTuning`: the board reads its values, and the Wireframe utility and Free Flight switch its camera
   rows. Its `cameraRows()`, `boardRows()`, `atmosphereRows()`, `terrainRows()` and footer buttons also serve
   `GpuBoardTestUi.tuning`, which many smoke tests use. Keep `UiKit.checkbox` too: `GpuModalDialog` uses it.
6. Move Reload assets and Edit shaders to another developer surface first, or drop them with the panel.

The tuning model's "Planetary conditions…" button is the only caller of `GpuBoardSource.editPlanetaryConditions`, so
without the panel the visual conditions preview is gone from the native window.

## 11. Tests

Use the project's Gradle wrapper from the repository root (`./gradlew` outside Windows), with mm-data beside the
checkout. `test` excludes `@Tag("on-demand")`, runs `checkstyleMain` first and stages the data.

```powershell
# Unit tests of the GPU package and the toolkit (no OpenGL)
.\gradlew.bat :megamek:test --tests "megamek.client.ui.clientGUI.boardview.gpu.*" --tests "megamek.client.ui.gdx.*"
# One class or method
.\gradlew.bat :megamek:test --tests "*GpuHudInputTest"
# Smoke tests (on-demand; need desktop OpenGL; write PNGs to megamek/build/gpu-board-review)
.\gradlew.bat :megamek:gpuBoardSmoke --tests "*GpuHudRoutingSmokeTest"
.\gradlew.bat :megamek:gpuBoardSmoke
# Checkstyle, as CI runs it
.\gradlew.bat :megamek:checkstyleMain :megamek:checkstyleTest
```

- `gpuBoardSmoke` runs the on-demand tests named `megamek.client.ui.clientGUI.boardview.gpu.*SmokeTest` and
  `megamek.client.ui.gdx.*SmokeTest`, and always reruns. Most open hidden windows; `GpuBoardWindowSmokeTest` opens
  the real, maximised native window.
- `gpuBoardBenchmark` runs the smoke classes tagged `gpu-benchmark` instead: the benchmarks that print timing
  reports and assert only that GL reports no error (ground cover, forests, biome zooms, board, terrain, mixed-unit
  and unit-model draw cost). `gpuBoardSmoke` leaves them out; their knobs are the `megamek.gpu.*` system properties.
- Screenshots go to `megamek/build/gpu-board-review` (system property `megamek.gpu.screenshots`). Side-by-side
  comparisons with the hud-v3 screenshots read the folder named by `megamek.gpu.hudMock` (default
  `../../megamek_temp/docs/design/claude-ui-concepts/hud-v3`, relative to `megamek`) and are skipped without it.
- Every capture through `UiTestStage` or `GpuHudTestStage` fails when a label shows a missing message key
  (`!key!`). `GpuUnitCardSmokeTest` runs that check on the whole stage of a real `GpuHud`.
- Lit 3D scenes take a random time of day per board source, so their screenshots differ from run to run.

| Area | Tests |
| --- | --- |
| Routing and keys | `GpuHudInputTest` (dispatch, Esc chain, focus rule, held units, click routing; no GL), `GpuHudRoutingSmokeTest` (real `BoardInput` and `GpuHud`: every default bind in 14 scenarios, clicks and drags, text fields, dialogs before the first board), `GpuKeyboardSmokeTest`, `ClientGUIHotKeyTest` |
| Modal bridge and Swing cut | `GpuModalBridgeTest`, `GpuDialogRoutingTest`, `GpuListPromptBridgeTest`, `GpuChatToastModalSmokeTest`, `GpuSwingCutTest`, `GpuSwingCutSmokeTest` (a scripted round with real Swing windows under the native window), `GpuVictoryHexFormTest`, `GpuStoryDialogTest`, `GpuHexPickTest` (the bot orders' hex pick), `GpuTurretFacingRoutingTest`, `GpuSwingDialogGateTest` (every prompt opener with the window presented and on the Legacy 2D client; the allowlist) |
| Live view | `GpuLiveBoardSpaceSmokeTest` (board labels, overlay, traces, minimap, 100 v 100), `GpuLivePlaybackSmokeTest` (presented units, review, speeds, stride), `GpuHudParitySmokeTest` (the live view of a real battle beside each of the 16 hud-v3 shots: every anchored panel within 2 units of the mock's frame; `parity-NN-sbs.png`) |
| Layout and coverage | `GpuHudLayoutSmokeTest` (900 x 600, 1280 x 720, 1920 x 1080), `GpuMenuCoverageSmokeTest` (every menu item and every phase command of real phase displays reachable) |
| Board overlay | `GpuBoardOverlaySmokeTest` (beside the mock's shots; envelopes, route, hover preview, ghost, rebuilds), `GpuRoutePulseSmokeTest` (the route's pulse frame by frame: the head from the unit to the destination, the surge, the rhythm across rebuilds, a jump's arc, a turn in place, none without a plotted route or switched off, the Tactical View, and the real view's own frames moving it on without a rebuild; `pulse-*.png` and its measured cost in `pulse-cost.txt`) |
| Services | `GpuHudServicesTest`, `GpuMovePlanTest`, `GpuFireOrdersTest`, `GpuFireDraftsTest`, `GpuOffBoardTargetsTest` (the Legacy 2D off-board arrows pinned; the dock's command sends the same attack; the native choice and its Esc), `GpuPhysicalOptionsTest`, `GpuUnitRecordTest`, `GpuFirePreviewTest`, `GpuLosResultTest`, `GpuHudMessagesTest`, `GpuChatRoundLinesTest`, `GpuBattleStatusTest`, `GpuReportLogTest`, `GpuPlaybackHistoryTest`, `GpuBotCommandsTest` |
| Components | one smoke test per component, e.g. `GpuPhaseHeaderSmokeTest`, `GpuForcesPanelSmokeTest`, `GpuCommandDockSmokeTest`, `GpuWeaponsPanelSmokeTest`, `GpuTargetCardsSmokeTest`, `GpuLogPanelSmokeTest`, `GpuUnitCardSmokeTest`, `GpuTuningPanelSmokeTest`; `GpuCardPlacementTest`, `GpuContextMenuTest` |
| Toolkit and skin | `UiGallerySmokeTest`, `UiListSmokeTest`, `UiTreeSmokeTest`, `DisplayScaleTest`, `GpuHudKitSmokeTest`, `GpuHudSkinSmokeTest`, `GpuPaperdollsTest`, `GpuPaperdollSmokeTest` |

## 12. Known limits

### 12.1 Not verified

- **Platforms.** Every recorded test run was on Windows 11, and every GL run reported the Intel Iris Xe as its
  `GL_RENDERER`. The hybrid laptop's RTX 4070 was not measured. Nothing was run on macOS or Linux: the macOS screen menu bar was not observed, and on macOS FlatLaf
  binds only Space in a confirm dialog (read from its input maps, not run), so a native dialog's Enter = Yes would
  differ from Swing there.
- **Running game.** The automated tests use fixtures, real phase displays and mocked or local clients. These checks
  in a game against a server were not performed: a `MovementDisplay.ready()` nag opens no Swing dialog, the native
  window is raised when a dialog is asked, no Unit Display window opens in any phase, and a punch declares without the
  yes/no question. Full games across all phases and usability sessions with new and experienced players are still
  needed before the native view could replace the Legacy 2D one by default.

### 12.2 Behaviour

- **Off-board artillery.** The native window draws no board overlay, so MegaMek's off-board target arrows
  (`OffBoardTargetOverlay`) become the dock's commands in the local TARGETING turn: one per edge whose arrow shows,
  named after the edge ("Off-board West"), not after the units beyond it. The HUD does not show which weapon the
  targeting display has selected; the commands follow MegaMek's selection, which its Skip command and weapon keys
  cycle.
- **TARGETING and OFFBOARD** keep MegaMek's board tool and keys, with the generic dock.
- **Dialogs.** A native dialog shows no severity icon, does not answer the confirm dialog's Y and N keys, and shows
  monospaced alerts in Roboto. A nested outer dialog loses typed text when it shows again. A Swing dialog missing from
  the allowlist is not raised, so it may open behind the native window; the error log names it. A dialog with no
  owner (`MMLogger.errorDialog`'s) or of another client's window is left as it is: neither raised nor reported. A
  local bot's alert, which has no owner either, is its creating client's native alert while that client's window is
  presented.
- **Initiative** shows a side's total, roll and bonus, not its dice: the client does not receive team dice.
- **Presented units** switch to the client's state only when the whole playback queue has drained, so the record's
  "-n" delta shows a volley's total. A physical attack's review does not move the camera.
- **Zooming during a walk** moves the leg phase and the wheel angle at once, because the gait follows the drawn size.
- **The "reveal all artillery rounds" debug flag** reaches the server only when it changes, because the hidden rounds
  window no longer pushes it in GPU mode.
- **Saved games.** A save written by this build stores the kept initiative dice (`InitiativeRoll`'s `<keptDice>`
  element). Older saves load (tested); an older MegaMek build is expected to reject the new element (not tried).
- **Glyphs.** No shipped face has ↰ ↱, several planetary-condition symbols (⌁ ⌒ ⎚ ⎯ █ ░ ☽ ⚐ ⟡ ⇶) or emoji;
  they draw as the missing glyph or blank. ⟐ and ⋯ draw as their lookalikes ◈ and … (`UiTheme`).

### 12.3 Tests

- `GpuBoardWindowSmokeTest`'s `switchesExclusiveWindows...` failed under load ("A second request must retain the same
  framing"): it read the camera before the source's timer had published the center request. It now waits until the
  render thread has applied the request.
- Smoke tests bounded by a frame count can run out early when frames are very fast; `GpuCombatCameraSmokeTest` uses a
  30-second budget because a hidden, uncapped window rendered 400-600 frames per second.
- Five smoke tests outside the HUD fail in every recorded full run: `GpuAtmosphericTaintSmokeTest`,
  `GpuBuildingMaterialsSmokeTest`, `GpuPlaybackSmokeTest`, `GpuScatterSmokeTest` and `GpuVolleySmokeTest`.

### 12.4 Measured costs

All on Windows 11; GPU numbers on an Intel Iris Xe, often with other builds running on the machine.

| What | Measured |
| --- | --- |
| Board overlay rebuild at 100 v 100 | 2.7-3.2 ms in 3D, 1.7-2.1 ms in the Tactical View; an unchanged recapture at most 0.22 ms and no rebuild |
| The route's pulse, per frame (1920 x 1080, `GpuRoutePulseSmokeTest`) | the overlay's draw takes 0.026-0.039 ms more GPU time with the pulse (median of 100 timestamped frames each: 43 quads while the head travels, 75 while the ring ripples) and under 0.02 ms more submission time; filling and uploading the quads 0.001-0.004 ms median (CPU timings under JaCoCo) |
| The frame's "board-space HUD" stage (HudView, `GpuHud.update`, overlay update) at about 200 units | 0.39-0.66 ms median; while the pointer sweeps, 2.1-3.6 ms median, at most 10.4 ms |
| Forces overview, first open at 100 v 100 | about 0.5 s in a profiling run (200 cards built in 251 ms, laid out in 91 ms, first frame 165 ms), 133 ms in a later run; a status change then 1.5-2.9 ms. Mostly the JVM's and the fonts' first use: a second overview built in the same run took 80-120 ms (cards 40-74 ms, layout 29-38 ms, first frame 6 ms), and 49 ms after other tests had run |
| Movement fire preview (CPU only, i7-13700H) | a plan change 16-43 ms at 12 v 12 and 0.24-0.50 s at 100 v 100; slices of 8 ms budget, at least one enemy each, up to about 31 ms per Swing event at 100 v 100; where the unit stands, a preview starts only after 250 ms without a game change |

They come from the stages' runs on one laptop and are no performance claim beyond those runs.
