# Roads and ramps

[Docs index](README.md) · [Terrain geometry](gpu-terrain-materials.md)

Roads have two cooperating parts: a footprint/material overlay and the ground that
supports it. A painted road does not own a second height field. Rendering, picking and
unit support must continue to agree on the underlying surface.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardScene.Tile.road / roadExits` | Carry captured ground road kind and exits. `railBed` comes from ground rail terrain; `wayExits` (road or rail) shapes the carriageway. `deckRoad / deckRoadExits` and `corridor / deckCorridor` keep the two layers separate. |
| `BoardRoad` | Build carriageway/junction footprints, end shapes, wear coordinates and obstacle-clearance queries. |
| `BoardRoadGrade` | Reserve each carriageway's ground and give its height along the course. |
| `BoardRoadCut / BoardSurface` | Lay the reserved road into the terrain: cut faces in Classic and on concrete, natural banks otherwise. |
| `GpuRoads` | Rasterize coverage/control masks and draw them on existing supporting triangles. |
| `GpuAssets / terrain-road.frag` | Supply material maps and shade asphalt, dirt, gravel, paint, deposits and wear. |

## Footprints and materials

Normal roads use marked asphalt; alleys use unmarked asphalt. Dirt and gravel have
porous margins exposing the local ground. All use a common 30-pixel carriageway
(`HexTile.ROAD_HALF_WIDTH` is 15), twice the original width, so a material change
must not pinch the route. Two exits form a bend, three/four form a
junction, and five/six form a roundabout unless ground rails require a solid hub. Dead ends extend through the center but retain
a ground margin before the opposite hex; an exit-free road uses a short isolated stretch.

At a 120-degree connection with square border crossings, roads and bridges use
`RailRoute.course` across the whole hex. The shared broad curve keeps the deck and
its side structure centered around the track. Oblique crossings keep their shared
neighbor-derived tangents, and tighter adjacent-exit turns keep their own layout.
Three/four-way junctions retain their radial approaches and add the same broad curve
at full carriageway width for every legal gentle pair of exits, including pairs used
only by explicit rail layouts. Their tyre wear retains the radial approach courses.
Rail and road use that same course before applying an authored beside-corridor offset.

Single-hex roundabouts have one circulating lane. Eligible level, paved three-hex
triangles become one shared two-lane roundabout; ground rails, bridges and grade
changes prevent that conversion. Both circulate counter-clockwise. Entries have
broken give-way bars following the outside of the ring, clipped to each incoming
lane, with an inverted triangle aligned to that lane. Oblique entries keep the
same setback from circulating traffic. Only the
two-lane ring has a broken lane divider; neither ring has repeated direction arrows.
Tangent fillets join the approaches to the ring; exits use twice the entry turning
radius so departing traffic follows a gentler curve.
All members derive the same world-space circle, and a canonical owner prevents
duplicate paint. Road paint and clearance may extend into adjoining dry, level
hexes; the hex grid does not trim the outside of a bend or roundabout.

### Junction controls

Three/four-way paved junctions use solid crossing hubs and rounded inside corners.
Rail-bearing five/six-way junctions also use controlled solid hubs instead of roundabouts.
White stop bars cross incoming right-hand lanes immediately before their conflict
with the other approaches. A T junction's opposite pair is the dominant through
road, with a stop only on the secondary approach; ambiguous crossings use all-way
controls. Centre dividers stop before the conflict area on every junction arm,
including the dominant through road, leaving turning movements clear; their final
carriageway width before that boundary is continuous. Unmarked roads omit
these markings. Road/rail corridors keep the bars out of the central railway median.

Ordinary two-exit bends use continuous white centre lines through the bend and for
one carriageway width on their connected approaches. Remote straights retain dashed
dividers, and shared-rail medians retain their boundary lines. Roundabout entry and
circulation markings keep their own geometry. These are conservative visual choices;
real-world bend markings also depend on visibility and the country's conventions.

Signal-controlled four-arm junctions use a yellow keep-clear box: an outline and
open diagonal lattice fitted inside the four stop planes, clipped to the actual
carriageway and clear of railway reservations. Stop-only junctions and roundabouts
omit the box. This is the project's selected box-junction convention, not a universal
US/EU requirement at every traffic light. Geometry and paint are shared in Classic,
Enhanced and either tactical view.

The road brush and the ground/deck road inspector offer **Junction controls: Stops**,
**Stops + traffic lights**, or **Stops + overhead traffic lights**. Stops uses an
octagonal roadside STOP sign; traffic lights replace the sign with a three-aspect
signal. The overhead option uses a curved metal mast arm carrying two overhead
heads and a lower roadside repeater. All modes keep the stop bars.
The choice belongs to the `road` or `bridgeRoad` appearance. With no stored choice,
three-arm junctions use STOP signs and larger junctions use overhead traffic lights.
An explicitly signalled T controls every approach. Explicit choices store
`trafficControl: stops`, `signals`, or `gantry`, including a gantry override at a T. Material
changes, copy/paste, layer moves, save/load and undo preserve that choice.

A single-lane entrance (on-ramp, below) gives way instead of stopping: under **Stops** it has a broken give-way line
across its whole lane with the outlined triangle upstream, as a roundabout entry, and a give-way sign
(`scenery/road/give-way-sign.glb`) on the merging traffic's right; under traffic lights it has a solid bar and lights
like any approach. Nothing waits on an exit (off-ramp).

Legacy `.board` X crossings use the same gantry default. Native boards retain explicit
authored choices; roads without one inherit the default. Controls are derived from the same
approach geometry as the paint, use the existing ground/deck support and GLB prop
pipeline, and disappear when a junction is removed. They are cosmetic terrain
decorations, not stored or independently selectable objects.
Controls may extend into a dry, level neighbour when their whole footing is
supported. Ground poles and gantry footings also clear the neighbouring road and
rail courses, including overlapping junction pavement. Bridge controls must fit
the actual deck shoulder. A crowded approach falls back to a roadside signal only
where that pole also fits; an unsupported pole is omitted while its stop bar remains.
If a sideways search crosses another branch, placement retries from the incoming
lane edge to find the small island it skipped. Gantries and single poles may move
up to two pixels upstream and 1.5 pixels outward there, with the same road clearance
and ground support checks. Existing valid placements are kept.

The Blender-authored assets are `scenery/road/traffic-signal.glb` and
`scenery/road/stop-sign.glb` in mm-data. Each contains the existing `-lod0` and
`-lod1` groups: detailed nearby models (416/96 triangles) and simple distant
models (36/40 triangles), selected by the normal prop LOD system. Both signal
levels flash yellow in out-of-service mode, with red and green off. This
animation is cosmetic and does not change movement rules.

`GpuTrafficSignal` selects the amber materials; `GpuModelLight` supplies reusable
emission intensity, blink period (zero for steady light), and optional diffuse
dimming while off. Its immutable attributes survive prop LOD switches and instance
batching, while draw setters sample the existing terrain clock. `model.frag` only
scales the material's authored emission and diffuse colour through `u_modelLight`;
it contains no traffic-specific timing or colours. Ordinary materials retain their
authored emission. These controls affect the visible material, not illumination of
neighbouring surfaces.

The overhead option adds `scenery/road/traffic-signal-gantry.glb`, also with native
`-lod0`/`-lod1` groups (1,800/212 triangles) and the same animated `Amber` material. It is a single-footing
cantilever: the origin is the supported roadside base, +Y faces waiting traffic,
and +X reaches over the incoming lane. The near model includes the curved steel
tube, base fixings, brackets and lamp visors; the far model keeps the arch and
all three signal heads. The existing prop system owns placement, LOD and blinking.
Placement keeps the authored proportions wherever both overhead heads fit the
incoming lane. Limited horizontal fitting preserves the vertical vehicle clearance;
if the lane or shoulder cannot accommodate the complete assembly, that approach
uses the ordinary roadside light. The mast may move up to two pixels toward waiting
traffic to clear an acute junction without moving its stop bar. The four-metre arm
and both LODs share the same lamp positions and supported roadside origin.

Rails share the carriageway (batch 10, docs/3d-board-editor.md): every shape and
clearance site reads `wayExits` and `BoardRoad.carriageway` (a drawn road or a ground
rail on dry ground), so a rail-only hex is shaped exactly as the road hex
with the same exits (hub, ramps of one or two levels, concrete cuts, containment walls,
the ramp onto a deck), and the rail's track is draped on the faces road paint lies on.
Only roads receive carriageway paint and vote a bridge deck's road material.
Classic, Enhanced and either Tactical View share the infrastructure preparation,
road paint, rail meshes, built bridge decks, tunnel openings and supported controls.
Classic natural bridges use a normal full hex cap over the hollow arch; Enhanced
retains its eroded span outline. Both use the same road and rail courses, and each
mode draws and picks its installed shell. Classic tileset art omits baked roads
and flat bridge sprites.

`BoardRoad` is also the clearance source for boulders, trees and vegetation. The
relief's own rim, talus and field rocks and shrubs may stand beside a road but never
on its carriageway or shoulder (`BoardRelief.place`).
Changing width or a junction therefore affects more than its rendered mask.
Authored roadside tree rows sit near the plain-road verge with canopy clearance.
Only hexes carrying conventional railway tracks receive the additional rail clearance,
using their existing rail courses and the chosen trees' model footprints.
Markings follow the main paved route through unmarked branches. Wheel wear follows
the lane centres through bends, endings and material changes.

### Loose roads

A dirt or gravel road is one coat per hex (`GpuRoads.patches`). Its mask carries a heading and its coverage, and B,
which the shader reads by the road's maps:
- Dirt (`terrain-road.frag#dirtTrack`): a farm track, two pale packed wheel ruts at ±0.9 m of every wheel course
  (`BoardRoad.wheels`), each about 0.6 m wide with a crumbly lip, damp patches and faint tread up close, the ruts' walls
  in the normal; between them a crown that grass has mostly taken back; outside them a ragged band of loose soil, then
  grass. Its wheel courses are its paths. At a junction the tracks run straight on across it, or bend through it where
  all its other arms lie to one side, and any other arm runs into the middle; between neighbouring arms one rut rounds
  the corner (3.5 m radius). Turning traffic otherwise crosses a scuffed middle (`BoardRoad.scuff`, most worn within
  about 1.2 m of the centre, gone by about 3.9 m, with a ragged edge), where the ruts run together without vanishing.
  B is the signed distance from the nearest rut, negative between a course's two ruts and shrunk in the scuffed middle
  (`BoardRoad.ruts`), so crossing and parting ruts cross and part with grass between them. The coat covers only their
  reach and the middle (`BoardRoad.rutReach`). Past its soil's ragged edge the soil thins out into the grass over most
  of a metre, bare between ever fewer tufts under a little dust, and grass blades thin out towards the ruts, over a
  metre beside them and a quarter of one on the crown (`BoardRoad.grass`). Its soil is a photographic study
  (`roads/dirt` sources), which the track lightens.
- Gravel (`terrain-road.frag#looseTrack`): crushed stone with two smoother, paler wheel paths, loose stone windrows along
  the crown and edges, a firm edge at about ±3.4 m of its nearest course with stray stones beyond it, and a hint of
  washboard and potholes in the wheel paths. Its courses (`BoardRoad.courses`) are its paths, but at a junction one per
  pair of arms, straight across or along one arm and round a circular fillet into the other (9 m radius for a
  60-degree turn, 5 m for a 120-degree one). B is where across the road a point lies (`BoardRoad.track`, in the same
  metres on every course); across a junction's hub (`BoardRoad.trackHub`) its wheel paths and crown fade into evenly
  worn stone, and its turns grade the corners.

The coat opens onto whatever ground lies under it; it never paints grass. A loose road's coat stops at the border with a
loose neighbour at its level, whose own coat draws its hex (`BoardRoad.trackedHexes`). Where gravel meets dirt it spreads
on over the dirt for a few metres and runs out in tongues and stray stones. Where a loose road meets a paved one (two
arms into one paved road each meet it on their own, with the ground between them left as it is), the paved arm's lanes
close in on its course over its last 12 m, so that its wheel paths reach the border as the loose road's one lane
(`BoardRoad.merged`), and faint grit lies along them for 14 m (`GpuRoads.carry`, finish -7). Against gravel the asphalt
ends square on the border under a short, broken spill of grit and dust, about a metre, most in the wheel bands
(`GpuRoads.spill`, finish -7 with B 1). Against dirt it
ends as a bridge landing does (`GpuRoads.painted`, `#bare`): it breaks up over its last 7 m above a little gravel, and
the dirt track's ruts alone run on over it, crumbling out (`GpuRoads.runOn`). Landings and end clips keep to their own
arm (`BoardRoad.arm`), so a junction's other arms stay whole. Gravel grass grows up to its graded edge and thins in over
a metre (`BoardRoad.grass`), as along a dirt track's ruts.

### Paved wear

A paved carriageway's core coat carries its traffic: its mask holds the wheel direction and where across its lane a point
lies (`BoardRoad.wearLane`), and `terrain-road.frag#asphaltWear` shades the asphalt under the paint from them and from
world-anchored fields:
- Age: binder pales from black to grey over broad stretches.
- Traffic: polished, darker wheel paths and ruts; an oil-drip line down each lane; grit along the kerbs and the middle.
- Ravelling: loose aggregate between the wheel paths.
- Cracks: an intermittent joint crack between the lanes, transverse thermal cracks, block cracking on old stretches
  (sealed into black bands where the road is maintained), and fatigue cells in decayed wheel paths.
- Bleeding: black binder in hot wheel paths.
- Repairs: sealed cracks, rectangular patches and trenches with sealed saw cuts.
- Damage: potholes down to the aggregate, skid marks and oil stains.

Ruts, cracks and potholes fill first in rain. Markings flake and dull on weathered stretches. Across a junction's hub
(`BoardRoad.wearHub`, carried in the length of the mask's heading) turning traffic wears the surface evenly, and
the lane-bound and oriented marks (oil drip, kerb grit, joint and transverse cracks, skids) give way. Wear never
lowers the roughness, so worn asphalt does not glint in the sun. Features are
placed per 8 m world cell and use the heading only symmetrically, so neighbouring hexes agree whichever way their paths
run.

### Single-lane roads

A paved ground road may be a single, one-way lane (`HexAppearance.lanes`, owner `road`; [editor](3d-board-editor.md)):
a ramp or slip road. Its course is the road's, half as wide (`BoardRoad.SINGLE_HALF`, 7.5 pixels, one lane of the
two-lane carriageway and as wide as a roundabout's lane), so grading, cuts, verges, wear and every clearance that
follows the footprint narrow with it. The road keeps `Kind.PAVED`: width is per arm (`BoardRoad.single`), set by
`layout`'s single mask. A single-lane hex's arms are all a lane wide, but one meeting a bridge deck or a dirt or gravel
road, which keeps that neighbour's width; a two-lane paved road's arm onto a single lane is a lane wide at their border
and widens into its two-lane hub with the usual rounded corners and turn reserves. Both hexes of a border derive the
same width (`BoardRoad.singleArms`, and `BoardFeatures.singleArms` for captured hexes). A two-lane hex with only two exits
narrows across itself into the single lane, its centre line ending midway. A single lane forms no roundabout, takes
no part in a shared triangle roundabout, and is never drawn on a deck or beside a railway: there it is two lanes.

Its direction is stored on the single-lane hexes alone (`inbound`, the sides traffic enters by;
`SingleLane.inbound` derives an absent one from the neighbouring single lanes). A two-lane hex stores nothing: whether
a single lane joining it is its entrance or exit is read from that neighbour (`SingleLane.entrance`), so both ends of a
run and its arrows agree. The single lane has no centre line; a white arrow about 4.3 by 1.2 metres in the middle of
each hex points the way its traffic runs (`BoardRoad.arrows`), and a single-lane fork or merge has one per arm. Its
wheel wear runs down the lane's centre; where it widens into two lanes, the axle parts smoothly into the two lanes'
(`laneCenter`).

At a two-lane junction the road keeps its priority: under Stops its two-lane arms (at most two of them, straight or
bent) have no stop, an entrance gives way and an exit has nothing; with three two-lane arms the opposite pair keeps
priority as at a T. Where a single lane joins a two-lane road, `BoardFeatures.rampSigns` stands a sign at the ramp's
mouth facing the junction, on the right of traffic heading out along the ramp: a no-entry sign at an entrance
(`no-entry-sign.glb`, a red disc with a white bar: the ramp's traffic only comes towards the junction) and a one-way
sign at an exit (`one-way-sign.glb`, a blue square with a white arrow ahead). It uses the junction's stop plane, or
partway out along the arm of a narrowing two-exit hex (`BoardRoad.rampFrame`), keeps four pixels clear of the
junction's own poles and of props placed in the hex, and walks out along the ramp when the mouth is crowded; a sign
with no dry, level footing is left out. The three signs are built without Blender by mm-data's
`tools/build_road_signs.py` on the STOP sign's conventions (2.8 units per metre, face +Y, base at Z=0, `-lod0` and
`-lod1`); their faces are flat-coloured geometry, not artwork.

A lane count change rebuilds the edited hex's surroundings as a road kind change does (`GpuTerrain`, and the surface
cache key's `singleLane`); a direction change rebuilds two hexes out, which covers a join's markings and a derived
direction. The single lane is centred on the two-lane road's course, not offset to its right-hand lane, and a
single lane on colony slabs is narrowed but, like any slab road, unpainted. `BoardRoadSingleLaneTest` covers widths and
border agreement, arrows, markings, give-way and priority, the signs' assets, sides and facing, their clearance from
signal poles, hex and scene agreement, loose roads and rails, the surface cache key and the sign models' two levels;
`SingleLaneTest` and `SingleLaneEditorTest` cover direction, runs, persistence, flips and the editor.

## Road and rail corridors

A corridor exists only on reciprocal road/rail sides that share the same reachable
layer. Ground roads and traditional rails use their shared, non-stranded sides;
submerged ground and five/six-way road roundabouts do not form corridors. On a deck,
the candidates are the intersection of `bridge_road`, `bridge_rail:1` and bridge
exits. Deck type and distant bank connectivity do not gate a corridor. Ground and
deck values are captured independently, and off-board shared exits retain full width.

The default **Middle** layout has a central ballast strip and a full-width traffic
lane on either side. Its half-width derives from the ordinary road half-width,
`RailRoute.BALLAST_HALF_WIDTH` and a small median margin. White median lines border
the ballast; centre dashes stop on shared arms. Ordinary side roads keep their
15-pixel half-width. Where one crosses the track, the rails are set into dark,
track-aligned infill with recessed flangeways, and the median lines stop. No automatic
concrete diamond decal is placed. This crossing is derived from the live road and
rail geometry, never a separate stored or pickable object.

Transverse, same-level conventional tracks also derive crossing-signal poles, incoming
stop bars and continuous approach dividers from one shared crossing description.
Road paint clears the track band. A lane approaching both a road junction and a railway
uses one stop bar before the first conflict. Parallel corridors, diverging turnouts,
maglev and separate ground/deck levels do not acquire level-crossing controls.
Existing authored crossing poles suppress nearby generated duplicates; generated
deck poles must fit the deck shoulder. The signals remain cosmetic, like the road lights.

**Beside** keeps the ordinary road width and centre dashes. The shared road and
ballast widths plus `RailRoute.CORRIDOR_GAP` determine their opposite offsets. Its side is stored in
the `rail` or `bridgeRail` appearance's `beside` bits. The editor changes a connected
corridor run together; Swap side reverses it, and Middle clears it. The one shared
`RailRoute` course drives track meshes, buffer stops, train snapping/following and
clearance. Authored junction layouts also determine the median rather than inventing
a different crossing underneath the rails.

At a shared terminal, the rail course, ballast and median continue to the road's
end plane (`HexTile.ROAD_END_REACH`). Buffer stops and train snapping read that same
extended course. Middle keeps its full width to the end; Beside keeps its lateral
offset there. Standalone rail endings retain their existing reach.

Road masks, scatter, bridge slabs, bridge mouths, tunnel floors and terminal support
consume the same corridor width. Ramp grading spans the carried width while retaining
shared edge heights. Conventional railway cross-sections and conventional trains are
scaled by 1.3; route centre paths, maglev guideways and maglev vehicles are unchanged.
These changes use the existing road material and rail vertex colours.

The editable `data/boards/examples/Road and Rail Junctions.board2` fixture contains
all 64 exit masks for roads, rails, Middle and Beside corridors, plus mixed crossings,
triangle layouts, rotated Seaport junctions, parking, trees, bridges and tunnels.
`GpuRoadJunctionFixtureSmokeTest` captures each group and the original Seaport board;
`BoardInfrastructureModeTest` checks shared geometry across graphics modes.
For a focused native review, `-Dmegamek.gpu.fixtureGroups=seaport-board,width-oblique`
selects capture groups; omitting it renders the complete fixture.

`RailCorridorTest` covers layer matching, persistence, flips, run propagation, stops
and riders. `BoardRoadRailTest` covers widths, markings, crossings, wheel wear and
the shared height at the shipped Trainjob approach. The on-demand
`GpuRoadRailSmokeTest` renders Middle and Beside on Trainjob, Depot and Ocoee from
above and at an angle. These focused CPU and native checks passed on 2026-10-18;
the reviewed corridor captures are in
`build/shader-audit/handoff-20261018/final/road-rail/`. The later Depot42 mask and
draw-order regression also passed in the grouped run recorded by
`build/handoff-multihex-verify.log`, with its corrected capture in
`build/shader-audit/handoff-20261018/multihex/depot42/`. No corridor timing
improvement is claimed.

Roads run like real roads through plain two-way hexes at one level: `BoardRoad.bends`
crosses each shared border along the line from the border before it to the border after
it, so a zigzag of hexes becomes one straight road and a turn one smooth arc instead of
a kink at every centre. Crossings stay within 40 degrees of square. Junctions, dead
ends, bridges and climbs keep square crossings. Except for the shared broad 120-degree
bend, their straight corridor holds a climb's ramp and a bridge deck's bank end. Next to such a crossing, the
bent crossing is chosen so that the hex's whole course into the corridor is one circular
arc; otherwise the turn would be left to the corridor's mouth, where a deck reaching
into the bank hides its start. Both hexes of a border derive the same crossing from the
same exits around them. Capture uses the same function with the board's hexes (`BoardFeatures`), so
trees and rough stay clear of the bent course.

At fitted concrete edges, level approaches extend to the actual boundary across their
full width; the existing ground triangles trim the paint into the diagonal join. Road
masks cover their complete footprint instead of cropping to the original hex box, so
shifted terrain boundaries cannot leave strips of bare ground between connected roads.
Roads entering a roadless concrete apron end flush at its fitted edge. Through-roads
and roundabouts retain their full width when their sides cross a neighbouring concrete
edge, and explicitly authored roads on concrete keep their paint. Road clearance
preserves the fitted boundary shared by both surfaces.
At a roadless concrete entrance, markings stop one lane width plus the shoulder
before that boundary. A stop bar intersecting this clearance is omitted whole rather
than leaving a short fragment. This also applies to graded concrete entrances.
Apron entrances do not count as road junction arms for either markings or physical
stop signs and signals; a genuine junction of three remaining roads keeps its controls.
Connected bridge decks above concrete remain road continuations. Once the scene's
concrete fit is available, generated ground controls use the same final stop approaches
as the paint, including skewed entrances and rail crossings. Authored objects and deck
controls keep their existing ownership.

Centre-line dashes are anchored at the borders: every border a road crosses sits in the
middle of a gap, seen from both hexes, and a road between two borders fits whole dashes.
A junction arm fits the period of the straight road through it. The line therefore
continues across hex borders, through bends and onto bridge decks.

At mixed-material joins across a bridge's deck, the same priority and coordinates must be used on both sides:
dirt covers asphalt, and gravel covers either. Dust and aggregate deposits lead into
the seam without changing carriageway width. World-space variation keeps borders and
wear continuous when adjacent paths run in opposite directions.

`GpuRoads` prepares cropped masks on CPU workers and uploads a chunk-owned atlas on the
GL thread. Coverage, transition coordinates and wheel-direction controls are distinct
inputs. The masks modulate repeating textures; they are not the road's albedo image.
Existing terrain triangles carry the overlay with a small depth offset, so the overlay
still has draw/fill cost even though it adds no road-shaped terrain tessellation.

## Batching and measured cost

Road mask atlas regions travel in the vertex stream. Draw materials retain the atlas
page and surface properties, allowing roads with different masks to share a draw within
their existing chunk. The tile ranges retain their original mask data: when an edit
replaces an atlas, reused vertices receive the new region before the old atlas is disposed.
The local mask coordinates and sampling calculation are unchanged. The two generic
vertex attributes use distinct units so libGDX binds both correctly.

Asphalt, wear and paint retain their explicit surface-lift order. Chunk centers cannot
reliably order overlapping coats after batching. Roads render before the other blended
overlays, whose normal depth ordering is retained. This adds 16 bytes per road vertex
for atlas placement, with the existing CPU and GPU copies; it adds no triangles or masks.

Coats merge as well: road coats of a chunk using one mask-atlas page share one material and one draw.
`GpuAssets.roadArray` holds the colour, normal and surface maps of asphalt, dirt and
gravel in one texture array; markings and sand/snow verges sample the existing sculpt
array. Each coat's maps, finish and wet response travel in its vertex colour
(`GpuRoads.coat`), which coats did not otherwise use. A chunk emits its coats in lift
order, so its one draw blends base coats first and wear and paint over them. A draw
that overflows a 16-bit mesh continues with that coat's lift, which the sorter orders
after the first. If a chunk's coats span multiple pages, draws retain their coat lift:
a gravel/markings page cannot safely draw wholly before or after an asphalt page.
This fixes the Depot42 junction's atlas-dependent verge ordering without changing
its geometry or coverage masks. Single-page batching is unchanged; this correction
has no new timing claim. Data sets without complete road maps, and coats whose sculpt material
is not in the array, keep separate materials. Bridge decks and tunnel floors keep their
own road textures.

The 2026-09-29 MesaCity comparison used the real 96x51 scene, a 1280x960 framebuffer,
terrain LoD enabled, RTX 4070 Laptop GPU, 60 warm-up and 240 measured frames in each
of seven views. Two sequential before/after pairs used a frozen source snapshot to
exclude concurrent terrain and water work.

| Overview metric | Before | Batched |
| --- | ---: | ---: |
| Road draws | 940 | 180 |
| Cover/overlay pass draws, including other overlays | 971 | 211 |
| Pass shader switches | 458 | 31 |
| Pass GL calls | 23,486 | 5,096 |
| Pass submitted indices | 560,373 | 560,373 |
| Pass CPU median, first / second pair | 2.593 / 1.892 ms | 0.810 / 0.519 ms |

Draw reduction was repeatable. Whole-frame timing remained variable under concurrent
work; this is not a general FPS guarantee. The second pair's GPU pass time rose from
0.262 to 0.322 ms, while the first pair fell from 0.979 to 0.415 ms. Explicit coat order
also increased texture bindings from 60 to 188. Terrain shading and long-frame stalls
remain separate costs; reducing road submissions does not solve them.

Merging the coats was measured on the 96x51 MesaCity overview at 1280x960 on an Intel
Iris Xe, one run each, with the same tree built with and without the merge:

| Overview metric | Separate coats | Merged coats |
| --- | ---: | ---: |
| Road draws | 175 | 44 |
| Cover/overlay pass draws, including other overlays | 189 | 58 |
| Frame median / p95 | 19.3 / 23.4 ms | 17.4 / 21.5 ms |

The 44 draws are the board's 41 road chunks plus mesh overflows. The road array takes
48 MiB of GPU memory (nine RGBA8 1024-pixel layers with mipmaps), against about 41 MiB
for the separate maps, which now load only for the kinds that decks and tunnel floors use.

## Physical ramps and joins

`BoardRoadGrade` reserves each carriageway's ground and gives its height; `BoardRoadCut` removes the
terrain over it. [Road grades](gpu-road-slopes-tunnels.md) describes the edge heights, ramp shares and the
Classic/Enhanced profiles. Tile centres keep their game elevation and grades stay within the connected
hexes. A road exit onto dry ground within two levels ramps to it; greater differences need connected exits
on both sides, and unconnected roads keep the cliff instead of inventing movement connections.

Raised bridge approaches form a closed concrete support spanning both insets, built by
`BoardBridgeFooting`. The road keeps the deck's plane over its own inset half. In Enhanced a solid ramp (deck
above its bank) eases off the deck and onto the bank, one smoothstep over both insets in eight rows per half
(`BoardBridgeSlope.ease`); the concrete structure keeps its straight lines, and Classic keeps the straight ramp. The deck half uses
`BoardBridgeMesh`'s actual carriageway and slab outlines, clipped to its approach grade. Its retaining walls
follow the curve, so the ramp cannot cut across the road or protrude beyond the parapets. The bridge's
generated rails continue to the road inset, with the existing `bridge-terminal` GLB wedges on the flat road
beyond the base. The level centre of the bridge remains open below. The block's faces serve both road paint
and picking; `BoardBridgeSlope` omits the generated slab inside that block to avoid overlapping faces.
Descending approaches cut the natural road bank; the bridge footing owns the actual concrete structure.

For cracks beside a road, inspect the reserved boundary and its cut faces before changing the road mask.
For a bright seam with continuous geometry, inspect mask coordinates, material weights and normals instead.

## Bridges and invalidation

`BoardBridge` follows reciprocal span connections and connected approaches. Deck road
material and exits come directly from `bridge_road`; a clean manufactured deck is
bare concrete. A ground road below it does not paint its deck. Legacy conversion writes
the old connected-road material choice into deck terrain once when loading old boards.
The stored type selects manufactured, natural or implicit geometry; natural spans use
[natural bridge geometry](gpu-natural-bridges.md).

`BoardBridgeMesh` generates the built slab and kerbs from the road footprint and the
actual on-deck rail courses, including explicit junction choices and unfinished tracks.
`BoardBridgeTruss` follows that boundary with continuous chords and fitted support panels.
Members meet at connected exit planes, with caps closing cuts; outside shoulders may
extend across inactive hex edges instead of pinching the deck. Steel fascia
replaces the concrete side faces it covers so the two materials do not fight at one plane.
Road-only five/six-way spans keep their raised roundabout island; a span carrying rail has
an open center. The rail curves themselves remain the `RailRoute` courses.

Middle and beside road/rail corridors share one centered, wide structural footprint.
Moving the track beside the road changes the surface courses, not the position of the
slab or its outside barriers. Ending a track beside a continuing road retains its buffer
stop without building a separate enclosure. Wide ramp profiles include the outer
shoulders; road paint, central ballast and white separators continue onto the bank ramp.
At a taper, truss chords and braces follow the exposed boundary while posts retain
structural panel spacing. Curvature subdivisions must not each add a full-height post:
that closes the open truss into a dense red wall.
Validated on 2026-10-19 with 137 focused geometry/paint tests and a native render/picking
check covering concrete and truss ramps, both track placements, a rail terminus, and a
narrow-road control. Captures and test reports are in
`build/shader-audit/wide-bridge-20261019/`.
The subsequent recheck passed 33 focused corridor, slope, footing and natural-bridge
tests, plus three native smoke tests. Its nine wide-bridge views include reverse low
angles, a diagonal beside-track ramp with swapped sides, a rail terminus and a bare
roadless ramp. Natural mud joins and truss junctions were also recaptured under
`build/shader-audit/bridge-recheck-20261019/`.

`BoardBridgeSlope` places these local facets on the shared grade profile. Flat and graded
spans both retain one complete `BoardBridge.Shape` in the prepared terrain snapshot;
drawing, picking, rail placement and road paint consume those same facets. Geometry is
built during chunk preparation and retained between edits, without a separate per-frame
bridge builder or a second camera-specific mesh. Shape-specific bridge GLBs are no longer
used for spans; terminal and pier GLBs remain reusable details. An implicit deck
carrying only `bridge_rail:2` uses the existing maglev guideway beam instead of the
ordinary slab. Its exact beam triangles provide bridge picking and support, and it
has no road landing or material apron.

`BoardBridgeFooting` extends terminal decks to actual bank support. Road-connected
ends retain the ordinary material transition. At a bare bank, rails taper after they
are supported and the material apron blends into the existing bank triangles, except on
a bank drawn as pavement.
The physical span remains opaque.

At a curved road connection, the landing, parapets and extended paint follow the
bank road's existing course. Parapet offsets interpolate the course tangents, while
the terminal GLBs rotate as rigid blocks instead of bending with the slab. The
level road beyond a curved ramp remains its sole asphalt carrier; adding another
flat slab there would overlap its markings and tyre wear.

A built bridge meeting a dry bank one level below its deck uses the same full solid
ramp with or without a road or rail. The slope spans both hex insets; adding or
removing road paint does not change its shape, picking or support. The bank terrain
underneath stays intact. Natural spans and maglev guideways retain their own geometry.
Ground-cover clearance includes the solid ramp from its inner inset through the bank
landing, including the terminal model's actual outward width. It leaves the unsupported
middle of a raised span available for vegetation underneath.
This clearance fix passed 27 obstacle/footing tests and a native bare-ramp render/picking
check on 2026-10-19. The final capture without vegetation penetrating the ramp is in
`build/shader-audit/bridge-recheck-final-20261019/wide-bridges/`.
The bridge regression suite (139 cases) and the native bare/road/bare edit check passed
on 2026-10-19; the two bare captures were pixel-identical. Captures are under
`build/shader-audit/bare-bridge-ramp-20261019/verified/bridge-slopes/`.

Changing a road's kind, exits or supporting geometry must invalidate the ground and
road masks together. Rail layout edits also rebuild the containing bridge's footprint and
side structure. Corridor edits recapture the local neighbourhood and invalidate both
ground and deck inputs; their eligibility has no span-wide gate. Reused road materials must bind the replacement chunk's
atlas before the previous atlas is disposed.

## Assets and fallback

Road materials live in `mm-data/data/models/board/textures/roads` as Godot 4 Standard
materials ([board materials](gpu-board-materials.md)). The GPU receives albedo, tangent
normals and a packed surface map: R height, G roughness, B cavity occlusion, A relief
range (from `heightmap_scale`). Texture height affects fine
coverage/wetting; the ground mesh continues to own actual height and support.
The Material Maker projects under mm-data's `tools/materials/roads/` export the aligned maps.
Each road texture is catalogued at 1024 pixels over 12 m (`materials.json`), so it covers 12 m
of road before it repeats (`GpuAssets.roadCoverage`); one exported wider covers proportionally
more. Sand and snow verges and markings take their sculpted material's catalogued metres
(`GpuAssets.sculptCoverage`), so a verge meets the ground beside it at its scale.

A road through shallow (depth 0) water, a ford or a flooded street, lies on its bed in
Enhanced (`BoardRoad.bed`); under a level or more of water it is out of sight, as in real
water. A bridge over the water carries the road instead, and Classic's water column shows
its liquid only. Ice on dry ground supports native roads. Supported road
fluff uses the native material and geometry, including bent and horizontal routes.
Roadside-tree and parking-car models contain only their objects. Parking sprites can
provide cosmetic exits when the hex has no gameplay ROAD; existing ROAD takes
precedence. See [legacy scenery conversion](3d-board-editor.md#native-files-and-compatibility) for source selection and coverage.
