# Fungal terrain and spores

The `fungus` theme keeps its authored cup, rounded, cliff and scatter GLBs. The
woods colonies (`fungus/cup-*`, `fungus/spore-*`) carry 3,200 / 820 / 96 triangle
levels and a baked impostor, built from the authored meshes by mm-data's
`tools/board-models/alien_lods.py`, which also darkens their crevices and turns
them toward the concept art's orange-red cups (burnt-orange skin, warm brown
stalks darkening to the foot, dark red-brown bowls, crimson pods). Woods density and the reduced scatter/cliff placement rates remain in
`BoardFeatures`, `BoardScatter` and `BoardFungus`. Colonies are solid, so they do not
pass through each other: `BoardFeatures` pushes overlapping footprints apart within
the hex and away from the neighbouring hexes' colonies, and where tall, dense woods
leave no room the crowded colonies grow slimmer (down to 70% of their width; their
height, and the cover it reads, is kept). Mars corals share the rule. Visual effects do not change terrain rules,
cover, visibility or picking.

## Living surfaces

The surface has four material roles, coloured after the Fungal Crevasse concept art
(each project's Pigment hue, saturation and value):

- Open plain: blue-violet mineral skin and irregular blue mycelial marbling, 22 m span.
- Ridge crowns and lips: lobed turquoise crust, 12 m span.
- Slopes: a separate violet fibrous mantle, 12 m span.
- Exposed cliffs: dark navy-violet rock with sparse cyan fissures, 12 m span.

`terrain-materials.glsl` evaluates these roles with the shared physical projection.
Color, normal, height and cavity maps use the same role weights and translated
sampling windows; neither scale nor UV origin resets at a hex edge. The maps retain
broad fungal pigment boundaries, since translating the samples already breaks up
repeating landmarks. Normal-map relief is an artistic estimate from source
luminance, not measured displacement or new terrain geometry.

The four color/height and normal/AO pairs are layers of the sculpt texture array.
Their Material Maker projects are `fungus-ground`, `fungus-debris`, `fungus-cliff` and
`fungus-bank` under `mm-data/tools/materials/terrain/surfaces/`;
`python tools/materials/export.py`, run from the mm-data root, exports all projects as
Godot 4 Standard materials ([board materials](gpu-board-materials.md)).

The shared `levelGrade` helper lightens fungus uphill more strongly than other ground,
with a scalar linear-light gain and a small, bounded reduction in chroma; downhill it
darkens exactly as every other ground does. It does not mix in white,
so the elevation cue retains the underlying pigment and texture contrast.
Grading happens after the four roles combine, so the cyan crust receives the same
elevation treatment as the underlying skin, slopes and cliffs. The curve follows
continuous world height and eases at extreme elevations rather than bleaching to
white. Captured contact weights blend the treatment at theme boundaries.

## Bioluminescence and mist

Fungal tissue glows by its materials' emission maps (their `.tres`): the fungus
atlas's lips, gills and pores (dimmed and mottled, so lips glow softly instead of
outlining the cups), and the cyan fibres of the cliffs' hanging `mycelium`; stems
stay opaque and unlit. This includes scatter and all mesh
LODs. `terrain-foliage.frag` adds a restrained pulse on the terrain's shared
animation clock; the surfaces remain visible at night.

All installed fungal models also contribute colored spill through the existing
`GpuLavaLighting` light list, filtered by the captured field of view. Each lit pixel
evaluates only the emitters whose reach covers its grid cell, so every colony and
scatter light counts. Emission color and reach follow the actual placed model:
warm lips/gills and cyan hanging mycelium light the nearby terrain and ordinary
objects. The existing diffuse-area approximation does not cast obstacle shadows;
it retains lava's response. Lights install and retire with their terrain chunks,
and native fungal prop lights are omitted from Tactical View.

`GpuFungus` derives emitter anchors once from the installed large cover prop bounds
when a chunk is assembled. Each occupied hex receives one broad, low violet cloud,
regardless of woods density, plus a small wisp starting inside each large cup or
spore body. Cup wisps start one wisp radius below the upper rim and rise through
the opening, expanding and fading as they clear it. Cliff and scatter fungi do
not emit mist, but retain their glow.
Replacing the chunk replaces its emitters, so board edits and scene changes retire
the old clouds. Both native camera projections use those same emitters and clock.

The existing `GpuEffectBatch` draws seeded, drifting magenta-violet billboards with the
`fungus-spores.frag` noise shader. Wisps fade in and out, are depth-tested against
the scene, and do not write depth or cast shadows. Visibility and projected size
filter emitters; a bounded 4096-quad batch retains the nearest colonies if full.
Tactical View keeps its original tileset artwork and does not draw these effects.
The shader is editable under **Terrain** in the existing shader editor.

## Cliff attachment

`BoardFungus` reads the mounting vertices from the same finest GLB meshes as the
renderer, using the authored local Y >= 0 attachment side. A cached `BoardKit`
snapshot owns these CPU points and refreshes with asset reload. For each candidate,
horizontal rays test all rear contacts against the finished wall triangles. The
whole upright cluster moves inward just enough to bury its roots in rock. A site
is retried when it spans a gap or would swallow more than 45% of the model's outward
reach. This supports all shelves of a tiered cluster rather than only its origin;
it does not add new cliff geometry or change the existing placement probabilities.

The hanging mycelium GLB embeds its strand roots in the shelf's curved underside.
Its three mesh levels share matching root positions.

## Verification

`BoardFungusTest` covers asset loading, theme selection, density and support of the
actual GLB's rear vertices on the finished cliffs.
`GpuFungusSmokeTest` compiles and renders the actual GL shaders on Fungal Crevasse
by day and night, verifies visible night emission, checks the mist changes over
time through the shared HDR/display boundary, checks one cloud per occupied hex,
and compares the same night frame with and without spill lighting. Replacement
cliff-only and scatter-only boards must have no mist or leftover clouds. A controlled
overhead comparison keeps the physical plateau, material and camera fixed at level 2
while changing only the actual shader's height-grade argument through levels 1–5.
Raising the physical board would also change cyan colony coverage and world-space
material noise, conflating the grade with a different pigment mixture. The comparison
tracks the same cyan pixels through the complete rendering pipeline and requires
each grade step to lighten and gently lose saturation.
The full progression must span more than four display luminance levels and a
normalized saturation change of 1/255, while retaining at least 85% of the first
shelf's saturation. `GpuTerrainGradeSmokeTest` separately
checks the actual shader's chromaticity, texture contrast and bounded height curve. Captures
and `fungus-elevation.csv` are saved under the configured GPU review directory's
`fungus` folder. `GpuLavaLightingSmokeTest` guards existing lava lighting,
fungal illumination of ordinary objects, field-of-view gating and source removal.
`BoardUltraSublevelTest` guards the shared pit geometry and retained tactical tiles.
