# Battle HUD layout

Status: **agreed** layout, from the 2026-11-15 tester round of the deployment prototype; the team may still revise it. The other design documents keep their component details; this page only decides *where* things live.

## What testers told us

- They want the unit list on the **left side**, not along the bottom (the deployment queue cards were rejected).
- They expect the **actions in the bottom centre**. The Deploy / Clear buttons next to the unit went unnoticed: too
  small, and not where they looked.
- They want a fixed, predictable layout:

```
+--------------------+------------------------+--------------------+
| 6 Units list       | 5 Phase / turn order   | 4 View & filters   |
|   (top-left, left) |   (top centre)         |   (top-right,      |
|                    |                        |    right)          |
|                    |                        |                    |
|                    |   keep the centre      |                    |
|                    |   ~60% of the board    |                    |
|                    |   free of panels       |                    |
|                    |                        |                    |
+--------------------+------------------------+--------------------+
| 1 Your unit        | 2 Actions              | 3 Enemy unit       |
|   (bottom-left)    |   (bottom centre)      |   (bottom-right)   |
+--------------------+------------------------+--------------------+
```

## Rules

1. **Every phase uses the same six zones.** A phase changes what a zone holds, never where the zone is. No
   phase-specific strips (such as the deployment queue) and no panels in the middle of the board.
2. **The actions always live in the bottom-centre dock**, in every phase. Anything shown next to a unit on the board is
   a *shortcut* that repeats a dock action (for example double-click to deploy or move, the hover ghost), never the
   only way to act.
3. **The three bottom slots never overlap.** The dock is centred in the window and gets the room left between the two
   unit cards; the cards have fixed widths.
4. **Hovering never reflows a list.** Anything that expands on hover opens as an overlay over the board, keeping each
   row where the pointer already is; otherwise it expands on click.
5. **It scales from 4 v 4 to 20 v 20+.** The units list scrolls and groups; the player can always see their whole force.

## Zones

| Zone | Holds | Component today | Change needed |
| --- | --- | --- | --- |
| 1 Your unit | Selected own unit: paperdoll, heat, MP, weapons; Unit record, Locate | `GpuUnitCard` (own) | Fixed width; the dock must not cover it |
| 2 Actions | The phase's commands, its options row and Confirm | `GpuCommandDock` | Done (deployment): Deploy, Clear, height stepper; turning, load and the rest in More |
| 3 Enemy unit | Hovered or inspected enemy / target: its card | `GpuUnitCard` (other) | None for now; in firing the target info stays in the right column (decision 1) |
| 4 View & filters | Utilities (view, tactical, minimap, log, chat, bots, menu), overlays, minimap, log; deployment's overlay chips; the bot sheet | `GpuUtilityBar`, `GpuTacticalOverlays`, `GpuDeployOverlays`, `GpuMinimap`, `GpuLogPanel`, `GpuBotSheet` | Done: the deployment overlay chips moved here from the queue; the bot sheet below |
| 5 Phase / turn | Phase, round, turn order, whose turn; the initiative result | `GpuPhaseHeader` (drake, 2026-11-14) | Done: the initiative result card stacks under the header, top centre |
| 6 Units list | Own and allied units, contacts tab, grouping, Next pending; per-phase status line | `GpuForcesPanel` | Done: also in deployment (`GpuDeploymentQueue` removed); the rail unfolds only by its button or pin |

## Per phase

| Phase | 6 Units list | 2 Actions | Next to the unit (shortcuts) |
| --- | --- | --- | --- |
| Initiative | Status "Ready" | Continue | — |
| Deployment | Status: to deploy, placed, later round, walks on (round, edge) | Deploy, Clear, elevation, setup; Turn left / right in More | Placement ghost; Deploy / Clear over the placed unit's nameplate (kept expanded so they never move), its height row under them on the unit, its setup tag above and turn arrows beside it, the same column and arrows as movement's chip (`GpuBoardChip`); drag to face; double-click deploys |
| Movement | Status: pending, moved; walk-on units "Enters from the N edge" | Walk / Run / Jump, Confirm, Hold; Turn left / right in More | Route preview; turn arrows beside the ghost; drag from the destination (or the unit) to face; double-click moves |
| Firing | Status: drafted, declared | Fire, Skip, twist, next target | Target letters, TN badges |
| Physical | Status: attack or none | Attack options, Skip | — |
| End / report | Attacks and hits of the round | Continue | — |

**Your unit (zone 1) in deployment:** in the local turn it shows the unit a click places, tagged *Deploying*; outside
the local turn no unit is selected until the player picks one.

### Walk-on deployment

Walk-on is placed like deployment and moved like movement, in the movement phase (when every unit walks on, the server
skips the deployment phase and its initiative):

- Marks just outside each board edge show the units still to walk on there, own, allied and enemy (count and round);
  the own mark lists the units.
- The player's unit is a hologram on its legal entry hexes until a click places it; it then turns solid, walks in from
  beyond the edge to that hex, and plots its move from there. Esc on the bare placement takes it back.
- Another player's walk-on replays from beyond the edge, and its arrival notice marks the hex where its move ends, as
  soon as its own replay finishes.

### Bot orders

The orders of an allied bot (Princess) open in the **bot sheet**, a non-modal panel in zone 4: the right column, from
under the utility bar to the bottom edge, so the board, the own unit card and the dock stay usable while it is open.
The dock treats it as a right-column panel and stays clear of it (rule 3).

- **Entry points:** the bots utility (shown only while the player commands a bot), the *Orders and behavior* row under
  each commandable bot in the Players panel, and the bot commands key (Ctrl+Shift+G), which opens the sheet when the
  player commands exactly one bot and the Players panel otherwise.
- **Scope:** the sheet's header says what its options change; today every option applies to the whole bot. Its
  pages are Behavior and Targets; lance and artillery pages come when they have content.
- **No chat:** each change goes to the bot as a typed command, never as a chat message. The sheet shows what was asked
  until the bot reports it back.
- **Strategic hexes:** *Pick on board* starts a board pick with the sheet still open. Board clicks pick or unpick
  hexes; the Targets page shows the picked hexes in place of the button, with Done and Cancel (the hint line's chip
  shows them too). Done adds the hexes, Cancel adds none, and Esc cancels the pick before it closes the sheet.

Allied bots' forces and orders are shown, read-only, in zones 6 and 1:

- **Units list:** each allied bot gets a header row: its name, then its personality ("Balanced"). Its posture follows
  only when the player set one (Attack or Defend); Auto, the default, decides each round. The AI (Princess or CASPAR)
  is not shown. Its lances and units follow. Enemy bots are not listed.
- **Selection:** a click on a CASPAR lance header selects the whole lance. The lower-left card then shows the lance
  form, with a LANCE scope chip, a units table (armor, heat, state) and the lance's Orders band. The lance's units show
  their nameplates on the board. Princess gives orders per unit, so its lances can't be selected; the player picks
  single units.
- **Allied bot unit:** an allied bot's unit opens in the left card, not the right one. Its card has a UNIT scope chip,
  a subtitle naming the bot and the lance, and an Orders band ("Orders · follows Fire lance"). Princess reports its
  waypoints to allies, so the band shows its route.
- **Ending it:** a unit row in the lance form inspects that unit. The card's ✕, Esc and a new own selection end the
  lance selection.

The dock gives orders to the selection (its bot-orders variant, shown while an allied bot's lance or unit is selected
and no replay runs). Its head names the lance or unit; its foot says what the orders change: the whole lance, or one
unit (a unit that follows its lance leaves it).

- **Move to…** and **Add waypoint…** start a board pick; the pick ends like the strategic hex pick.
- **CASPAR:** Hold (Resume once every unit holds), Retreat, and Rejoin lance for a single unit that left its lance.
  More holds Resume, Clear orders, Remove last waypoint, the four exit edges, the formations (whole lance only) and
  the priority.
- **Princess:** Remove last and Clear waypoints.

The **Bot orders** overlay (Overlays panel, Units group, on by default) draws allied bots' orders on the board:
CASPAR's routes, Princess's waypoints and trails, and Princess's strategic hexes as flags in the bot's color. Enemy
bots' orders are never drawn.

## Known issues from the tester screenshots

Addressed in the layout rework, pending a retest:

- The forces rail widened on hover and moved the hovered row out from under the pointer. It now unfolds only by its
  expand button or pin, and stays open in deployment.
- The dock was not centred and covered the right side of the own unit card ("WEAPONS 6 of" was cut). It now centres
  in the window, clamped between the bottom-left and bottom-right panels.
- The initiative card sat in the middle of the board. It now stacks under the phase header.
- The movement mode buttons (Walk, Run, ...) were in the dock while the deployment buttons were next to the unit. The
  dock now holds deployment's commands too (rule 2); the buttons next to the unit stay as shortcuts.

## Decisions (2026-11-15)

1. **Firing:** the weapons list and the firing solution stay in the right column for now. Revisit in the firing phase
   rework.
2. **Movement fire preview** (`GpuContactsPanel`): stays in the right column for now. Revisit in the firing phase
   rework.
3. **Shortcuts next to the unit:** kept, on by default.
4. **Ownership:** VicenteCartas owns the battle HUD layout across all six zones. The team, and exeea in particular,
   can override any of these decisions.
