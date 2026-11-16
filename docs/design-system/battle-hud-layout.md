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
| 2 Actions | The phase's commands, its options row and Confirm | `GpuCommandDock` (not in deployment) | Also in deployment: Deploy, Clear, facing, elevation, load |
| 3 Enemy unit | Hovered or inspected enemy / target: its card | `GpuUnitCard` (other) | None for now; in firing the target info stays in the right column (decision 1) |
| 4 View & filters | Utilities (view, tactical, minimap, log, menu), overlays, minimap, log | `GpuUtilityBar`, `GpuTacticalOverlays`, `GpuMinimap`, `GpuLogPanel` | None |
| 5 Phase / turn | Phase, round, turn order, whose turn; the initiative result | `GpuPhaseHeader` (drake, 2026-11-14) | The initiative result card moves from the board centre into this zone |
| 6 Units list | Own and allied units, contacts tab, grouping, Next pending; per-phase status line | `GpuForcesPanel` | Also in deployment (replaces `GpuDeploymentQueue`); hover expand without reflow |

## Per phase

| Phase | 6 Units list | 2 Actions | Next to the unit (shortcuts) |
| --- | --- | --- | --- |
| Initiative | Status "Ready" | Continue | — |
| Deployment | Status: to deploy, placed, later round, walks on (round, edge) | Deploy, Clear, facing, elevation, load | Placement ghost; double-click deploys |
| Movement | Status: pending, moved; walk-on units "Enters from the N edge" | Walk / Run / Jump, turns, Confirm, Hold | Route preview; double-click moves |
| Firing | Status: drafted, declared | Fire, Skip, twist, next target | Target letters, TN badges |
| Physical | Status: attack or none | Attack options, Skip | — |
| End / report | Attacks and hits of the round | Continue | — |

Walk-on deployment adds marks just outside the board edge with the own units still to walk on (count and round). It
never shows other players' reinforcements.

## Known issues from the tester screenshots

- The forces rail widens on hover and moves the hovered row out from under the pointer.
- The dock is not centred in the window and covers the right side of the own unit card ("WEAPONS 6 of" is cut).
- The initiative card sits in the middle of the board.
- The movement mode buttons (Walk, Run, ...) are in the dock while the deployment buttons were next to the unit; rule 2
  fixes the inconsistency.

## Decisions (2026-11-15)

1. **Firing:** the weapons list and the firing solution stay in the right column for now. Revisit in the firing phase
   rework.
2. **Movement fire preview** (`GpuContactsPanel`): stays in the right column for now. Revisit in the firing phase
   rework.
3. **Shortcuts next to the unit:** kept, on by default.
4. **Ownership:** VicenteCartas owns the battle HUD layout across all six zones. The team, and exeea in particular,
   can override any of these decisions.
