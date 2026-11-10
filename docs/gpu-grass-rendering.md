# Grass and shared vegetation wind

`GpuGroundCover` draws low grass over finished terrain, and the fields' crop
plants among it. `GpuBiomeVegetation` plants the crops and draws the wetland
plants; trees belong to `GpuTreeInstances`. Grass, crops
and reeds follow one ground-cover contract, described in
[fields and marsh](gpu-fields-marsh.md#one-pipeline-for-ground-cover): terrain
workers plant them with each chunk, chunks keep persistent instance buffers, and
the shaders share wind, wetness and lighting. Each kind keeps its own placement,
density and mesh rules.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardVegetation`, `BoardBiome` | Terrain eligibility, deterministic placement inputs and shared growth fields. |
| `BoardPlants` | What each terrain detail level plants; grass and its banks' rim blades on medium and full detail. |
| `GpuGroundCover` | Root planting for terrain workers (meadow and bank rims), projected density, blade templates and per-chunk submission. |
| `terrain-grass.glsl` | Blade shape, root-fixed bending, a rim blade's hang, a tussock's tuft and per-root detail selection. |
| `terrain-meadow.glsl` | Growth variation shared by ground color and grass shape. |
| `terrain-tundra-sward.glsl`, `BoardBiome.sward` | Earth tundra's sward: its stands' colours for blades and ground, and its openings. |
| `terrain-vegetation-wind.glsl` | World-space gust used by grass, crops and marsh plants. |
| `terrain-vegetation.frag` | Plant surface lighting, root darkening and wetness. |
| `terrain-materials.glsl` | The ground under the blades, including a bank's turf lip and the root mat below it. |
| `GpuTerrain` | Wind phase integration, finished support surfaces and pass ordering. |

The **Grass blades** control under Tuning → Terrain hides the grass and its rim
blades; the crops' plants stand either way. Planted roots remain available when it
is re-enabled.
Ground shading, crops, marsh plants and trees have separate controls/owners.

## Roots and drawing

Roots are deterministic positions on finished terrain triangles, filtered by
existing surface and road rules. Terrain workers plant them for every hex of a
medium- or full-detail chunk (`GpuGroundCover.plant`), and the installed tile keeps them.
Each instance stores position plus a stable sample rank. Density selects an
ordered prefix by rank: existing roots do not move when density changes.

Beside same-level positive-depth open water, standing grass thins smoothly over
the last three metres of dry land before the bare bank. Planting uses the nearest adjoining
shore edge and the candidate's existing random chance, after the usual cover
threshold, so the whole band fades rather than ending at another cutoff. Inland
roots and surviving positions/ranks stay unchanged. This is CPU preparation only:
the existing instance layout, blade shaders and draw passes are unchanged.

Depth-zero coasts instead grow grass on their exposed finished banks, fading by
height above the local waterline and leaving a bare wet toe. Curved shore and bed
triangles can cross the hex edge; neighboring owners share that support while
each root stays in its canonical hex. The vegetation snapshot carries the same
per-vertex water reference as the bank material, including descending streams,
without retaining terrain builders. A root is also checked against the actual
water triangles, whose diagonals can differ from the bank's on descending streams.
Grass uses the neighboring land's sampled
cover, so a water tile's default grass family cannot green a desert coast.
Frozen, molten and positive-depth water retain their existing vegetation rules;
shallow wetland banks remain reed-owned.

At dry slope feet and crests, density follows interpolated terrain shading normals
instead of rejecting whole triangles. Each hex also receives the finished slope
triangles that extend into it from higher natural neighbours. Roots still belong
to their own hex, so this fills the formerly bare green strips without overlapping
two hexes' grass. The terrain worker prepares this support once, retaining only
triangles and normals for repainting; it adds no per-frame terrain work. Restored
grass does add blade instances in those strips.

No cover grows through paint. `BoardPlants` builds `BoardDecals.Opacity` once
per hex from the decals painting its ground (each image's alpha at the decal's
drawn transform, so a round emblem leaves no square bald patch) and from its
legacy paint overlay, and every planter multiplies its coverage by one minus that
opacity before its usual threshold: blades, rim blades, reeds and crops alike, in
every 3D view. Cover vanishes where paint is more than about 45 % opaque and thins
under soft paint such as the rubble path; each paint keeps the one-model-px margin
a road's shoulder keeps. A candidate's place and chance are drawn before any
rejection, so the roots outside the paint keep their places and ranks, and
removing a decal restores them exactly. Roofs, decks, industrial tops, fuel tanks
and ice grow no cover, so only paint on the ground counts. Not covered yet: the
dressing baked into the solid terrain (`BoardScatter` pebbles, tufts and bushes,
`BoardRelief.field` stones and shrubs) still ignores paint until it is planted by
the workers too (the pending scatter consolidation); see
[3D board editor](3d-board-editor.md) for the decal-side limitations.

Nothing grows through stationary map content either. Blades, rim blades, reeds,
crops and the baked dressing reject a root that `BoardObstacles` reports for its
hex: structures (for plants a military wall by its body alone, a civilian wall not
at all), placed objects, bridge pier footings, and each bridge deck's passage from
the slab's underside to its top. Cover tall enough to reach a deck
at or near the ground is left out there, while the ground under a raised deck
keeps its grass. This is decided when the hex is built, never per frame: units
and meeples stand in the grass and never clear or bend it.

Two-level grassy banks include a shallow intermediate shoulder in their canonical
terrain profile. Its grass density follows the same smooth slope fade, using the same
finished triangles as support and picking. Its ground material remains visible when
blade density fades at a distance, so both earth faces still read from above.

Along both contours of such a bank, its upper lip and its middle shoulder's, the grass
leans out and hangs over the edge. `GpuGroundCover.plantRim` plants these rim roots on
the same finished triangles: 48 candidates per metre of each contour, half of them
behind the lip, up to two metres back on the top and one on the narrower shoulder,
thinning into the meadow there, and half dropped on the contour a tenth of a metre to
two metres under its top, most where it rounds over. Each leans out along the local
slope, so the rim follows rounded corners rather than the hex edge, and as far as the
ground falls away under its free end, a metre to two out: standing behind the lip,
arching over it, hanging down the face, so the meadow's upright blades turn into the
hanging fringe without a gap. A root whose ground rises ahead, as at a junction with a
higher neighbour, does not grow. Hexes whose drop is not exactly two levels to dry
ground, and edges with a cliff-top exit, grow no rim. Paint, a road's verge, obstacles
and the cover threshold reject rim roots as they do meadow roots. Every candidate
draws its place and chance before any rejection, and ranks spread over the hex's
4,096, so density selects a prefix as for the meadow. A rank's fraction packs twelve
bits, the root's contour, its lean in eight steps and its heading in 256, so the
instances keep the meadow's four floats. A rim root follows its contour on whichever
ground is installed, so unlike a meadow root it can shift slightly when a chunk's full
detail replaces its medium detail.

Rim blades are grass blades of the same program, template part 9: the meadow's
height, width, colour (in its darker half: they hang under the sward), finish,
lighting, wind and scorch, fanned within some 40° of their heading. A hanging blade
arches out over the lip, its free end .15 to .6 of its height below the root. On the
middle shoulder they grow only where its ground draws turf, by the material's own
patch field (`cliffMaterial`). They stand up between 100 and 140 projected pixels per
hex, a little before the meadow's first blades; below 400, fewer stand, by rank, and
each widens by as much, so the rim keeps its fringe.

Under the turf lip the ground material darkens the soil into a root mat: half a metre
to a metre of dark topsoil before the paler subsoil of the face (`rootMat` in
`terrain-materials.glsl`), from the same rim distance and world fields as the lip.
It carries the edge where the blades are too small to draw.

Measured on an Iris Xe against the cutout crowns these replaced, two rounds each
(2026-10-28): Grassland 2 and 3's bank views (`GpuBankRimSmokeTest` with
`megamek.gpu.bankRimFrames`, 1600x1000, at 140 to 830 px/hex) changed by -0.8 to
+2.4 ms in median frame time, within the -0.9 to +1.6 ms between two runs of the same
code. In `GpuTerrainBenchmarkSmokeTest`, whose board has few two-level banks, the
opaque terrain stage took 0.1 to 0.8 ms less in its medium views (a draw and a program
fewer) and 0.2 to 0.3 ms more in its close views, with the same geometry.

The blade templates, coarse and fine, hold three or seven triangles for grass, five
or eleven for a rim blade, five or nine for a wheat stalk and 23 or 49 for a maize
plant. The vertex shader
derives curvature, orientation, color variation and wind from the root; the
fragment shader adds lighting and wetness. The grass color pass has no draws
below its visible scale, one at a uniform orthographic scale, and at most two in
perspective. Grass receives shadows but does not cast a separate shadow per blade.

Density rises smoothly with projected hex size, currently between 120 and
500 pixels with up to 4,096 candidates per hex before filtering. Each chunk's
buffer holds all of its roots, bound once, and draws the prefix by rank that
each hex's own projected size needs as one range per hex
(`GpuInstancedMesh.drawRanges`): the instance attributes are pointed at the
range's first instance and a plain instanced draw follows, which works on any
OpenGL 3.3 and stays visible to libGDX's profiler. This submits about a seventh
of the blades a chunk-wide prefix would. Multi-draw-indirect would issue the
ranges in a single call, but the Intel driver tested rejects it intermittently.
Hexes out of view draw nothing. The shader evaluates density again at each
actual root. Blade width introduces fractional density continuously. The two blade
topologies switch at their detail threshold without a silhouette cross-fade.
Grass needs at least 120 projected pixels per hex. Full terrain detail is
requested from 64 pixels, but a full-detail chunk takes from a few hundred
milliseconds to seconds to build and arrives one chunk at a time, so medium
detail chunks carry grass roots as well (`BoardPlants`): a view that closes in
finds the blades already installed while the finer ground is still being built.
A chunk refined from coarse detail passes through medium detail first for the
same reason (`GpuTerrain.nextDetail`). Roots are sampled in the hex's plane and
dropped onto whichever ground is installed, so a root keeps its place and rank
at every detail level; when the full-detail chunk lands, only the heights change
with the ground under them, and the chunk uploads once without a blade moving.
Roots that do arrive while their hex is already on screen without any, the only
case left, grow in over half a second instead of appearing at once
(`GpuGroundCover.visible`), and the frame their growth ends submits them in full
before a still view reuses its submission; roots a chunk brought while off screen
show complete.

The meadow field varies blade height and thickness continuously, keeping shorter
growth in sparse patches. Blades full-coverage proportions:
height varies linearly from .025 to .07 of hex width before the meadow factor,
and width from .0015 to .0028. The realism pass's shorter, narrower distribution
was reverted at the user's request; root count and projected-density LOD were
unchanged. The shared maximum height also controls shrub sizing; altering blade
proportions must not unintentionally admit more shrub geometry through placement checks.
The meadow field is also used for ground color. Avoid introducing a
separate fragment cutoff or procedural field that makes bare ground disagree with
root placement. Grass and the crops' plants draw in their own batch
(`GpuTerrain.renderCover`) after the depth pre-pass and before the terrain's
colour flush: the ground they hide fails the depth test instead of being shaded,
and their instances do not split terrain material batches. On an Iris Xe at 1080p
this takes the Farmland board's views at 198, 83 and 52 px/hex from 40.5, 45.0
and 45.0 ms to 32.6, 37.5 and 38.1 ms, and Mesa City's grass views by 1 to 2 ms;
on NVIDIA the frames match the old order but for single pixels at roots. Two further
changes measured within noise on the Iris Xe in A/B runs on those views and were not
kept: one draw per chunk in the orthographic view (roots interleaved by rank instead
of a range per hex), and the grid shade taken once per plant in the vertex stage.

## Tundra sward

Earth tundra (every theme but Fungus, Mars, Lunar and Volcano, and not under pavement) grows a sward of long dry
grass from the meadow's own candidates, buffers and program. `GpuGroundCover.plant` gives a candidate to the sward
where the tundra's coverage reaches and `BoardBiome.sward` grows: about two thirds of a hex, with openings some
metres across where the carpet's lichen, moss and gravel show. The sward
crosses into neighbouring hexes with the tundra's coverage and stops at the Snow theme's remnants. A sward root
carries half a rank more than a meadow's.

Its blades (`terrain-grass.glsl`) are leaves two to three times as wide as the meadow's and up to a quarter longer,
never narrower than two and a half pixels. They lie over, combed one way, along their ground's slope, and are lit by
that ground's normal, like all grass. Stands several metres across colour them (`terrain-tundra-sward.glsl`): straw from bleached to ochre for
the most part, russet sedge, which grows shortest, and green. Stands have no outline: between two, each blade
belongs to one or the other. The ground under the sward (`tundraSurface`) is its thatch in the same stands' tones,
a little paler, over the grain of the theme's own ground, so Low, which draws no blades, and distant views keep the look. The first
blades to stand keep to the thatch's colour; sunlit tips come with the full sward. Wind, scorch, blast, the
**Grass blades** option and the quality's meadow share apply as to the meadow.

Tussocks are tufts of the same grass (`GpuGroundCover.tufts`, template part 10): the first 32 candidates of a hex may
seed one where earth tundra grows, in loose clusters 1.6 m apart or more, some ten a hex, in the sward and its
openings alike. Each grows 112 finer blades out of a disc .9 to 1.8 m across: those at its centre stand, half as tall
again as the sward's are long at most, and towards its rim they lean out, arch over and droop to the ground. A tuft is
dark at its heart, pale at its tips, takes half its stand's colour and is lit as a mound, its blades' normals leaning
out with them. The tufts' blades stand up and thin out as a bank rim's do (100 to 140 px/hex, fewer and wider below
400), in their own layer of the chunk. Low and the **Grass blades** option draw none.

## Lighting and planting cost

Meadow, sward and tussock blades are lit by a normal the vertex stage gives them: their ground's (its slope rides in
the low seven bits of a root's rank fraction, `GpuGroundCover.slopeCode`), tilted a quarter each blade's own way, or a
tussock's mound. Lit by their own faces, blades peppered a meadow with near-black pixels and stood out bright on banks
turned from the sun. The fragment stage keeps that normal on the viewer's side (`terrain-vegetation.frag`): at the lowest
tilt a blade tilted away would face away from the camera and reflect no sun. A meadow's blade is a pixel wide at its
root or more. Rim blades and crops keep their own faces.

A planter asks the biome coverage of thousands of points in one hex: `BoardBiome.Coverage` looks the nine hexes of
its stencil up once per hex (4,096 tundra points: 1.20 ms per point, 0.78 ms per hex, same sums), and level tundra
with level tundra all around skips the query (`BoardVegetation.uniform`), as fields and marsh do.

Measured 2026-11-10 on a busy machine, the fastest of twelve batches: planting a level tundra hex with its sward takes
0.90 ms, a meadow hex 0.80 ms (0.67 to 0.70 ms before the slope code). Whole frames on the Esker (Tundra) board at
1920x1080 on NVIDIA, against the build without tundra grass: High 6.4 to 7.2 against 5.6 to 6.3 ms at 330 px/hex and
4.9 to 5.5 against 4.6 to 5.3 ms at 700; Medium within the 0.5 ms between runs. On the Iris Xe at Medium only the
sward without tufts was timed: one undisturbed pair read 21.5, 20.5 and 18.4 ms without and 23.4, 20.1 and 17.6 ms
with it (330 and 700 px/hex, and a tilted view), a second pair was disturbed, so its cost there is not resolved below
about 2 ms, and the tufts' is not measured.

## Wind

`GpuTerrain` integrates a wrapped gust phase; changing strength changes its speed
without jumping its position. This phase is separate from water and rain clocks.
Zero strength leaves the plants still. Increasing strength increases both the
bend and the gust's travel rate.

Grass blends angular poses around fixed roots so slider steps remain useful
throughout the range. Crop plants keep their leaves on their swaying stalks; marsh
plants form a deeper arch. Detailed, simplified and distant plant meshes use the
same world-space gust. Wind changes update shader inputs without regenerating
roots or uploading unchanged instance buffers.

## Cache and update boundaries

No grass is prepared on the render thread. A chunk's roots are planted by the
terrain worker that builds it at medium or full detail and replaced with it;
tiles an edit reuses keep their roots, except a hex whose paint changed: a
paint-only edit re-plants its cover on the worker from its installed support and
installs it with the new paint meshes, leaving its solid terrain untouched. An
unpainted hex pays one null check per candidate root. Planting costs about 0.4 ms per hex,
against several milliseconds of surface work per hex at either level, and a
grass hex holds about 65 KB of roots, so a medium chunk's grass is cheap to
plant but not free to keep; coarse and distant chunks carry none. A chunk's
instance buffer uploads again only when the installed roots of one of its hexes
change. Zooming changes the drawn prefix, panning changes which chunks draw, and
neither uploads anything.

The selected submission is reusable while the camera matrix, physical viewport,
scene tile list, ordered candidates and the installed roots of the drawn hexes
match. Camera movement, resizing and chunk replacement trigger selection again;
wind and lighting continue to update every frame.

Change placement or clearance in `plant`, appearance in the shader, and what a
detail level carries in `BoardPlants`.
