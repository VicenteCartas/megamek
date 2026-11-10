# Tactical overlays, markers and occlusion

Gameplay visibility is decided before rendering. Native overlays present the
client's captured classifications, paths and annotations; depth effects only
change how already-visible information is displayed.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardTacticalMarks`, `BoardTacticalGraphics` | Draw the client's markings (its `TacticalMark` records, highlights, deployment, sheets) on the hex lattice on Swing, with their playback category. |
| `BoardFieldOfView`, `GpuFieldOfView` | Share the client LOS/sensor classification and upload its per-hex rendering mask. |
| `BoardTacticalGeometry`, `BoardFiringGeometry`, `BoardDeploymentGeometry` | Convert captured paths, boundaries and footprints into common board geometry. |
| `GpuTactical`, `GpuFireControl` | Draw tactical primitives, range walls and attack lines. |
| `BoardMarker`, `GpuMarkers`, `GpuCutout` | Describe visible point symbols and build/place their raised artwork. |
| `UnitAnnotations`, `GpuHexText` | Present unit annotations and board text with the relevant placement/visibility rules. |
| `GpuHexGrid` | Draw the shared hex border pattern through the completed scene in top view. |
| `GpuUnitVisibility` | Draw the screen-space outline/fill for eligible units occluded by the scene. |
| `GpuTerrain` | Fade occupied non-tree props and maintain their cutaway/depth state. |

The shared descriptor/painter classes live beside the GPU package in
`client/ui/clientGUI/boardview`. Native draw and placement classes live in
`boardview/gpu`.

## Tactical geometry and playback

Movement paths, flight arrows, ranges, deployment zones and target data come from
the existing client handlers. Native adapters must preserve each command/target
identity instead of deriving their own legality from rendered geometry.

Flat hex annotations use `BoardTacticalGeometry.floatingZ`, at the owning
hex's nominal ground/water level plus a small clearance. Road ramps and sculpted
relief do not raise this plane. Raised point symbols use the separate
`GpuMarkers.locationSupport` helper to clear the hex ceiling and overlapping units.
Matching range bands are connected by vertical strips wherever they cross a shared
hex edge at different elevations. The strips retain the captured color and opacity;
separate bands, board edges and equal-height planes do not acquire extra walls.
Flat annotations still test scene depth, so rising terrain can hide parts of them.
Hover and editor-brush outlines draw hidden sections in an additional depth pass at
`GpuBattleView.HOVER_OCCLUDED_ALPHA` (50% opacity), keeping unobstructed sections fully
bright. Both passes use the same geometry and preserve the scene depth. Unit
selection and target bands retain their own hidden-section pass in `GpuBoardOverlay`.
`GpuBattleView.HOVER_HEX_INSET` controls the native hover and editor-brush outline's
inset as a fraction of the hex radius; `.1` preserves the original 10% inset.
When the pointer hits a raised surface, its hover outline snaps the ray-hit height to the supporting floor level (the bottom of the storey hit by the ray). A second
outline at the hex base and six connecting edges use 25% opacity to show the selected column.
Terrain tints follow the terrain, unit bands follow the
animated unit, and cross-hex lines retain their continuous layout. Upright range
walls and raised point symbols have their own geometry; changing one must not
move every overlay onto the same plane.

`GpuBoardSource` captures scene checkpoints in packet order for
`UnitPlayback`. Unit-dependent overlays are hidden during movement playback,
and later scene changes wait for the appropriate visual checkpoint. Manual rulers,
LOS cursors and commands can remain live. A delayed visual scene must not become
an alternate source of game state or visibility.

Field-of-view classification is shared with the Legacy 2D painter. The GPU uses
one texel per hex and applies the chosen grayscale, dimmed or fog-of-war style.
The atmosphere composite reconstructs the visible surface from the existing scene
depth and applies the mask there. Shading follows terrain slopes, cliffs and props;
foreground surfaces naturally hide anything behind them. The contour follows the
surface too and fades on vertical faces. At exact cliff boundaries, ownership is
sampled just inside the solid to avoid switching hexes as the camera moves.
There is no separate LOS mesh, connector geometry or render target.

Visibility changes upload the per-hex mask without rebuilding terrain or repainting
its textures. Unchanged visibility reuses the mask; style and opacity preferences
update uniforms only. Camera changes reuse the same mask and composite
the current scene depth. Terrain rebuilds remain tied to terrain/geometry changes,
with camera detail refinement managed independently by `GpuTerrain`.
Range-band connectors keep their own geometry and colors.
Change classification in the client capture; change appearance in
`GpuFieldOfView` and the atmosphere's FOV helper.

## Point markers and labels

`GpuHexText` uses only the labels captured by `BoardHexText`, preserving display preferences and the
omission of LEVEL 0. HEIGHT labels draw through content in their own hex, including units, while content
in other hexes still occludes them. In top view, enabled ground labels ignore terrain, roofs, bridges
and units. Angled ground labels retain scene depth, with the existing
exception for their own decorative relief. Camera changes reuse the uploaded glyph meshes.

One method decides a hex's labels: `BoardHexText.capture(..., coordinates, types, printed)`, with View > Hex Types
(`GUIPreferences.SHOW_HEX_TYPES`, off by default; Swing and 3D View menus right after Hex Coords, and the Overlays
panel's Type row) as its `types` parameter. Legacy 2D, Classic, Enhanced and the Tactical View follow the setting
through the coordinates' refresh path. The lines stack down to the bottom edge (the last line on the edge, each
centred, a line wider than the hex in a smaller font), top to bottom in the official map sheets' order and wording:
the height line (`LEVEL n`, below level 0 `SUBLEVEL n`, an ultra sublevel `ULTRA` / `SUBLEVEL`), `DEPTH n`, the
structures, the cover, the ground, the liquids, the modifiers, `FIRE`. Off, only today's labels follow the height
line: `DEPTH n` unless 0 and without a basement, the tallest structure's `HEIGHT n` (on its roof in 3D) and `FOLIAGE`.
On, the type block in the height line's colour: `DEPTH n` for every water hex, 0 and a basement of any type included;
`SWAMP` (`QUICKSAND` for swamp 2 and 3), as the sheets' `DEPTH 1 / SWAMP`; each structure with its height after the CF
chip's black triangle: `BRIDGE▲n`, the building type (`MEDIUM▲5`; a gun emplacement keeps its type, e.g. `HARDENED▲1`),
`FUEL TANK▲n`, `INDUSTRIAL▲n`; the woods/jungle cover word (`FOLIAGE`, `LIGHT`, `HEAVY`, `SUPER HEAVY`); then
`BoardHexText.BELOW_COVER` in order: `ROUGH` (`ULTRA ROUGH` for 2), `RUBBLE` (`U. RUBBLE` for 6), `PAVED` (not
under a standing building or fuel tank), `SAND`, `MUD`, `TUNDRA`, `FIELDS`, `SNOW` (`DEEP SNOW` for 2), `ICE`, `BLACK
ICE`, `MAGMA CRUST` (`LAVA` for 2), `RAPIDS` (`TORRENT` for 2), `HAZ. LIQUID`, `GEYSER` (`MAGMA VENT` for 3),
`IMPASSABLE`, `FORTIFIED`, `ELEVATOR` (Solaris and industrial), `SPACE`, and `FIRE` last, as the sheets print it.
Black ice is hidden information: the views write it once detected, by the hex tooltip's rule
(`Terrain.isBlackIceDetected`), and the saved image (`printed`) never does. Building classes (hangar, fortress) and
metal content write nothing. The tallest structure's line lies on its roof in 3D (its elevation is the height), where
HEIGHT lies without types, as the model would hide it at the ground in every angled view; the rest of the block stays
at the ground. The Tactical View then tells the depth and the height only in the type block (`BoardHexText.tactical`):
no large depth number, and the roof chip shows `CF n` alone while the structure line goes back into the block at the
ground, which the top view shows; off, the chip stays `CF n ▲h`. The chip drops its ▲h when a captured line already
ends with it, so the tile's own labels decide, not the live setting: a tile still holding the other setting's capture
(until the next refresh after a toggle, a hex just exposed by a pan, a scene held during playback) shows the height
once too. The lines' ▲ comes from `BoardView1.hexType.structure` (`MEDIUM▲5`, no space), the chip's from `BoardView1.tacticalCfHeight` (`CF 150 ▲1`); Legacy 2D and the
saved image draw it in AWT's Sans Serif (Dialog's fallback font has the glyph), the 3D board in the HUD font, as the
chip did. The 3D board settles every hex's lines (`BoardHexText.settled`): the ground lines end on the edge, and a
roof's HEIGHT, which it lays on the roof, takes the slot above them (drawn there only without a roof); so does the
roof's structure line. The same rule moves the lines down where a view drops one: the Tactical View's DEPTH or roof
HEIGHT, and the zero-gravity bed's DEPTH and the words of what it lost with the liquid: its vegetation (`FOLIAGE`, the
cover, `FIELDS`, `TUNDRA`), its ice and snow (`ICE`, `BLACK ICE`, `SNOW`, `DEEP SNOW`) and its liquids (`SWAMP`,
`QUICKSAND`, magma, `RAPIDS`, `TORRENT`, `HAZ. LIQUID`); there a bridge's `HEIGHT n` and `BRIDGE▲n` (on the deck) are
raised by the depth, as its deck keeps its level. In vacuum with gravity the frozen water keeps its DEPTH, `ICE`,
`RAPIDS`, `TORRENT`, magma and `HAZ. LIQUID` lines; only the vegetation, `SNOW`, `DEEP SNOW`, `BLACK ICE`, `SWAMP` and
`QUICKSAND` lines go.
Every label is drawn on an outline in every view (`BoardHexText.outline`: white around dark text, black around light
text): Legacy 2D and the saved image (`BoardHexText.draw`) draw the text eight times in the outline
colour in a ring around it (`BoardHexText.outlineOffsets`), then the text. `GpuHexText` draws a bordered font under
the plain font instead. Legacy 2D, which draws its labels on every paint,
spends about four to five times as long on them (a scratch benchmark of 400 hexes: about 6 ms instead of 1.5 ms; a
`StringDrawer` stroke took 60 to 125 ms). `GpuHexText` builds the meshes one label at a time in one reused font cache
and appends each label to its chunk: a font cache grows its arrays to the exact size at every text it adds, so the
former cache per chunk copied them over and over, and with the outline a rebuild of the whole 48x48 board (coordinates
on) took 0.3 to 2 s on the Iris Xe; now 36 to 86 ms, 8 to 30 ms without the outline (Types off and on, 3D and Tactical
View, a scratch harness alternating both in one process at 1920 x 1080). It keeps its meshes when the scene's new
tiles change only their markings and no roof moved (`sameLabels`), as a path preview's do: 3 to 7 ms even with every
hex repainted. The outline adds about 1.5 to 2.3 ms to a frame showing that whole board (10th percentile; the medians
are too noisy to tell). A line is fitted to the hex's width at its baseline, outline included: on the bottom edge (49
px wide at the export's scale) the longest lines, such as `HARDENED▲12`, `FUEL TANK▲12` and `INDUSTRIAL▲3`, shrink
below 10 points (about 6 points before the space before the ▲ was dropped), a line higher up less.
Save Map as Image and the game summary images (`BoardArtwork.printable`) always print coordinates and types, black
on white.

The 3D fill and outline fonts use `UiTheme.worldFont`: 60-texel glyphs with trilinear mip filtering and up to 8×
anisotropy where supported. Their theme-owned atlas pages keep 64 transparent-white texels between glyphs; each
quad includes eight texels of that margin so filtered edges are not clipped. Sampling stops at mip level 3 to
avoid averaging unrelated glyphs together. These are still bitmap labels: sufficiently distant or edge-on text
can lose detail. Incremental glyph uploads, including fallback characters and new atlas pages, retain the same
filtering and refresh mipmaps. HUD fonts keep their separate atlas and existing mip-level-1 limit.
Apart from the roof's structure line the type block is a ground label: angled views hide it under buildings. Tests:
`BoardHexTextTest`, `BoardLunarTest`, `GpuBoardSourceTest`, `GpuBoardActionsTest`, `BoardEditorSessionTest` (the
printed outline), `GpuHexTextSmokeTest`, `GpuHexTextFilteringSmokeTest` (angled filtering and incremental atlas uploads),
and `GpuTacticalViewSmokeTest.hexTypesLabelTheHexesInEveryView`
(`hex-types-*.png`).

`GpuHexGrid` repeats the existing terrain border pattern in top view after scene geometry and before
labels. It shares the terrain's grid shading control, clips to the board footprint and does not write
depth. One shared hex mesh uses captured coordinates and elevations, so overhead perspective also stays
aligned; camera changes reuse the instance buffer. Angled views retain their existing terrain grid.

`BoardClientState.getBoardMarkers()` captures existing marker descriptors,
including minefields, artillery, objectives, notes, cargo and engineering status.
Existing knowledge, owner, phase and preference filters still apply.
`GpuMarkers` owns their artwork, meshes and cosmetic animation.

Symbols clear the finished hex, structures and overlapping posed units.
Angled views group them into a compact grid; near-overhead views arrange them
around the unit or in a ring for an empty hex. They stay inside their source hex
and return that hex when picked. Sensor contacts retain their own centered,
anonymous question mark and never acquire a hidden model identity.

In oblique views symbols rotate; near overhead they align with the camera.
`FLAT_TILT_DEGREES`, `GROUP_*`, `TOP_GROUP_*` and `TOP_UNIT_MARKER_*`
in `GpuMarkers` control those transitions and spacing. Unit labels and marker
detail labels avoid one another's bounds. Unit annotations follow the displayed
pose and can clamp to the viewport edge; selected/hovered labels have priority
in crowded views.

To add a point-symbol kind, extend `BoardMarker.Kind`, capture it through the
existing painter/handler, provide artwork and an outline choice in `GpuMarkers`,
and suppress the equivalent flat symbol during native capture. Keep area
footprints and route lines in the tactical layer.

The Tactical View draws each unit as its meeple on a `GpuUnitBases` base, posed
by the same animation ([HUD](gpu-hud.md#5-the-tactical-view)); labels hang from
the ring's north point and picking is the meeple's. This is separate from the
anonymous sensor symbol and from the flat sprite fallback for a missing 3D asset.

## Building cutaways and see-through outlines

A visible unit overlapping a non-tree prop's bounds can fade that prop. Hovering opens nothing.
`GpuTerrain` uses animated bounds, so movement and flight height affect occupancy.
Walls use the chosen building opacity; interior struts remain opaque. All of the hex's floors fade
together, with an opacity one quarter of the way from the wall opacity to fully opaque.
Faded surfaces stop writing camera depth, while buildings retain their full
solid shadows. Leaving the prop restores its ordinary opacity/depth state.
This is a bounds-based presentation effect, not physical collision.

Trees retain opaque geometry. `GpuUnitVisibility` instead compares the nearest
posed unit surface with scene depth and draws a team-colored outline and faint
fill where an eligible unit is obscured. It also uses this frame's terrain-smoke
opacity. Units omitted by gameplay visibility never enter the capture.

Ground decoration in the unit's current support hex is exempt from ordinary
occlusion highlighting; taller trees/buildings can still obscure it. The
comparison uses the rendered position and a depth tolerance, avoiding highlights
from the unit's own rear faces or depth quantization. Sensor contacts retain
anonymous geometry and their fixed highlight color.

The outline runs after atmosphere/weather and before tactical annotations.
Its capture targets resize with the viewport; zero strength skips the work.
`GpuBattleView` shares current posed bounds across culling, cutaways, shadows
and outlines, refreshing them after animation. Marker outline switches use the
same see-through strength without changing gameplay visibility.
