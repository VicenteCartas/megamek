# Road grades and tunnel portals

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardRoadGrade` | A hex's reserved road ground and its height: edge levels, ramp shares and the Classic/Enhanced profile. |
| `BoardRoadCut` | Meshes reserved ground and removes the terrain standing over it, closing the difference with cut faces; builds containment walls. |
| `BoardRoadBank` | Enhanced: moves natural ground near roads vertically into banks, one field every hex evaluates alike. |
| `BoardSurface` | Builds each hex's top (a Classic column or the Enhanced relief) and lets the carriageways reserve their ground. |
| `BoardRelief` | Natural slopes and cliffs; a step's foot recedes from a road beside it (`roadPush`); bridge decks and tunnel portals still clear it. |
| `BoardRoad` | Courses and footprints; a climbing return turn runs through the hex centre (`climbingTurn`). |
| `BoardTunnel` | Detect eligible blocked exits and define portal placement/clipping. |
| `GpuAssets / GpuRoads` | Load the portal kit and continue the road material through its lining. |
| `mm-data/tools/board-models/road-tunnel.md` | Portal GLB dimensions and contract. |

## Reserved ground

A carriageway reserves its footprint and shoulder (`BoardRoad.footprint(SHOULDER)`) inside its own hex. A wide
road/rail corridor also reserves the slivers past its edge's corners, and a bridge mouth a short strip beside the
deck. The terrain is built first; reserved ground then replaces it. The road surface is level across its width and
graded only along its course.

Two connected carriageways meet at one height on their shared edge (`BoardRoadGrade.edgeLevel`):

- A junction (three or more exits) keeps its level; the road beyond it carries the whole ramp.
- Two junctions, or two ordinary roads, meet halfway.
- A road ending at unpaved dry ground within two levels carries the whole ramp to that ground's level; the
  unpaved hex keeps its shape. A roadless concrete apron within two levels is graded like a road.
- `BoardSurface.roadEdgeElevation` answers the same question for any caller, with a connected deck's height first.
- A bridge deck keeps its own approach height.

Where two hexes share a ramp, each climbs over its outer `SHARED_RAMP` (30%) of the hex width and keeps the middle
40% level. A road carrying a whole ramp starts at its centre. A climbing return turn runs straight in to the centre
and out again, and the wedge between its two ramps is reserved with them. A junction keeps its core
(`BoardRoad.CORRIDOR`, 55% of each arm's run; 75% for roundabouts) and ramps on its straight arms. A bridge approach
keeps the deck's plane over the inset half of the hex, as `BoardBridgeFooting` builds it.

Classic ramps are straight. Enhanced ramps ease in and out: level where they leave flat ground, and meeting the
neighbouring ramp at their edge with one slope that both hexes derive. A ramp ending at a level junction eases
fully into it.

## Graphics modes

**Classic** keeps sharp hex columns. One plane sweep meshes the column top and its reserved pieces together, so both
sides of every boundary share their vertices. Vertical cut faces close each height difference along that boundary
and use the column's wall art. A neighbour's wide corridor reserves its sliver of the adjacent column too.
`BoardRim` keeps the cliff-top rim art clear of a ramp's mouth.

**Enhanced** builds the natural relief for every dry hex, roads included, then lets the roads reserve their ground:

- Natural ground near a road moves only vertically, by one field of its position (`BoardRoadBank`): within the
  height range a road spans it eases into the road as a bank (rise per run at most `SLOPE`, rounded where it leaves
  the ground), and a shallow drop of up to `BoardRoadGrade.FILL` below the road beside it fills up as an
  embankment. Ground further above or below keeps its shape, and the board's floor never moves. Faces are split on a
  world-aligned grid, so tops, slopes and cliffs of neighbouring hexes stay joined.
- A step beside a road recedes from it (`BoardRelief.roadPush`) instead of being cut. Where it rises from the road,
  each height below the road's headroom (`BoardRoadGrade.CLEARANCE`) moves only as far as it must to clear the road:
  the foot steepens into the verge and the face above keeps its place, so it never overhangs. A drop below a road
  moves as one band. Where a road leaves its hex into a step, the step is cut through instead.
- On reserved ground, natural tops give way wholly below the road's headroom, so no ground lies hidden just under the
  road; slopes and cliffs give way above the road's surface, so their faces run on beneath its edge. A cliff over the
  headroom stands over the verge. Cut faces close the reserved edge, meeting the road at its own vertices. Where the
  road's edge misses the ground beside it by less than a tenth of a level, the edge meets the ground, so no hairline
  cut face shows.
- With containment walls on (`BoardRelief.Tuning.containmentWalls`, a checkbox in the GPU Tuning panel's Terrain
  tab), an earth slope of up to two levels rising from a road to a higher roadless hex beside it keeps its toe behind
  a precast wall at the road's edge, at least a metre tall and stepping up in half metres; only the slope above the
  wall's top recedes, so the wall stays low. The relief decides where (`BoardRelief.walled`): only where the wall
  saves the slope at least a verge of ground, along runs of at least `WALL_PANELS` panels through the steps' corners;
  shorter stretches, ramps through the road's own hex and taller cliffs recede as without walls. The bank keeps the
  walled slopes standing up to the wall's top, and each run of panels continues one panel past either end.
  Rounding-width seams between adjoining road mouths produce no soil cut faces and do not split the wall run;
  panels keep the same five-centimetre joints through those mouths.
- Concrete is cut vertically. A road ramping across the edge of concrete, and not also between natural hexes, is
  built in concrete (`BoardRoadGrade.poured`): natural ground neither banks into nor stands over it, and its cut faces
  use the concrete hex's walls.

Drawing, picking, unit support, rails, road paint and tactical overlays all sample the same emitted faces. Faces
without area are dropped; picking has no plane for them. Built bridge landings and piers sample the same mode's
ground, including support borrowed from another chunk. Classic natural bridges keep a full hex cap with the carried
terrain's art and graded approaches; Enhanced natural bridges keep their sculpted span.

## Fixtures and verification

The editable `mm-data/data/boards/fixtures/Road Grades - Grassland.board2` and
`Road Grades - Concrete.board2` maps cover 36 layouts in road-only, centered-rail
and beside-rail panels (216 cases total: 36 × 3 × 2). Their adjacent README and case
CSV give coordinates and regeneration instructions. `BoardRoadGradeFixtureTest` checks
support under every carriageway, shared edge heights in both modes, level junction cores
and rail support; `BoardRoadSlopeTest` checks that slopes around roads stay closed.
`GpuRoadGradeFixtureSmokeTest` renders the saved maps in both graphics modes.

## Measured geometry work

`GpuRoads.keepClear` generates only lattice strokes that can reach the inverse-
rotated keep-clear box bounds, including stroke width and a numeric margin. It
preserves the original 8-pixel phase, ±128 line endpoints, transform, stroke and
final clipping. Alternating before/after blocks in one JVM gave these median
times and per-invocation allocations for mask 27 with gantry signals. The local
Windows/JDK 25 probe used three warm-up blocks and six measured blocks of eight
calls per version, with alternating order:

| Corridor | Before | After |
| --- | --- | --- |
| Road only | 28.952 ms / 21.507 MiB | 2.593 ms / 1.342 MiB |
| Centered rail | 45.729 ms / 22.995 MiB | 6.497 ms / 2.697 MiB |
| Beside rail, first side | 103.153 ms / 56.268 MiB | 10.688 ms / 2.823 MiB |
| Beside rail, other side | 91.653 ms / 56.667 MiB | 7.843 ms / 2.902 MiB |

Geometric comparisons covered all 15 four-arm masks, all four corridor variants
and both signal modes (120 configurations). Bounds were unchanged and XOR area
was at most 6 × 10⁻¹² model-pixel². Java2D `Area` resegmentation changed antialiasing
in 3,530 of 6,590,592 raster pixels, all within 0.25 model pixels of the original
paint boundary; the largest alpha difference was 23/255. These are geometrically
equivalent masks, not byte-identical raster images.

These measurements concern one local geometry operation, not frame rate or whole-board load time.
Probe reports are under the ignored `megamek/build/geometry-perf-audit` directory.

## Tunnel portals

Authored road exits facing a dry wall more than two levels higher receive a
cosmetic tunnel entrance if they have no existing graded road approach. The
opening is clipped from the native cliff. Portals do not add game connections,
change elevations, or introduce tunnel movement rules.

The maintained asset is in the adjacent mm-data repository:

- `data/models/board/road-tunnel.glb`: winged ground portal.
- `data/models/board/road-tunnel-bridge.glb`: wingless bridge portal.
- `tools/board-models/road-tunnel.md`: the portal's dimensions and contract.
- `tools/materials/scenery/gallery-concrete/bake_normal.py`: normal-map bake for
  the continuous concrete textures embedded in both portal GLBs.

External walls and roof extend the full 18-unit depth. The lining and pier have
no overlapping inner faces. Vertex colour fades continuously toward the dark
end cap. Whole decorative rocks are excluded from the portal shell and approach,
including rocks generated by neighbouring hexes; surrounding formations remain.

Only bridges with an attached road anywhere along their connected span receive portals; natural rock
formations without road approaches receive none. Eligible bridge exits use the deck's actual elevation,
inherited road material and a wingless portal. All six
terrain families share this detection and clipping path. Connections between
bridge decks remain open. Portal models are installed before the ground rendering
branches, including space and sky hexes: suspended bridge entrances must close
their cliff openings even when the ground is transparent (Quatre Belle).
Bridge elevation/CF/repair metadata must not replace
the ground beneath the span with a legacy tile: the deck has its own model and
repairs have a tactical marker. Authored water and unsupported ground overlays
retain their existing handling.
The tunnel floor continues the full 30-unit road width into the lining, using
the approach's material, normal/surface maps and world-space texture phase.
Road/rail portals expand to their shared corridor width. Only the lateral scale
changes; the opening cut uses the same transform, and height and depth stay fixed.
The visible road stays bright at the entrance, then fades deeper inside. A tiny
recessed underlap at the hex edge prevents cracks on the slightly oblique lattice.

The runtime bridge builder must omit zero-width shared boundaries when extruding
rail outlines; otherwise coincident clipped loops create vertical faces across
an open carriageway.
