# Terrain realism

[Docs index](README.md) · [Geometry and materials](gpu-terrain-materials.md) · [Terrain contacts](gpu-terrain-contacts.md)

The realism target is the **main 3D board renderer, viewed from above and
isometrically**. A top-down camera in that renderer is not Tactical View, which is
a separate presentation outside this scope. Judge materials, lighting and
performance in the main renderer at both gameplay angles, with the same scene and
settings. The work spans `megamek_temp` and the sibling `mm-data`.

A board should read as a connected natural landscape, with memorable rock walls,
pillars, ridges, sheltered hollows and deposits. Hexes remain the game model and
can stay visible as a tactical overlay; the natural surface should not look like
individually decorated hexagonal pedestals. Richness needs hierarchy: a few
prominent geological features, supporting groups of smaller forms, and quiet
traversable ground. Uniformly adding noise, scatter or mesh resolution obscures
that hierarchy and costs more.

## Scope and decisions

The user set these boundaries:

- 3D unit models are out of scope. Do not recalibrate or regenerate them as part
  of scenery work.
- Buildings and roofs are out of scope apart from colour encoding and linear-light
  filtering of their existing texture reductions: no material calibration, roof
  maps or geometry changes.
- Cliffs keep their current profile, including the wider dry cliff tops. Do not
  add tall-cliff, overhang, ridge-connection or support-pillar shapes.
- Grass keeps full coverage and its blade size distribution. Shorter, narrower
  blades make the meadow sparse, and a lower shared maximum size shrinks shrubs and
  admits more of them.
- Snow keeps its intrinsic blue: wavelength-dependent absorption and multiple
  scattering produce blue inside snow, separately from blue skylight
  ([NSIDC, Science of Snow](https://nsidc.org/learn/parts-cryosphere/snow/science-snow)).
  Uniformly neutral snow is not an acceptance target. Evaluate the cool source
  palette and the camera-detail-dependent blue wear against material composition
  and light transport before any recoloring.
- Fixed intrinsic colour, brightness, saturation and contrast corrections belong in
  the final albedo assets, made by a reproducible, documented generator. Runtime
  sRGB decoding, lighting, visibility, reflections and condition-dependent surface
  changes are separate responsibilities; do not change sunlight to compensate for
  material colour. World-space dry/lush variation and elevation grading are
  artistic controls, not intrinsic pigment. Do not describe shader tints or
  generated maps as measured materials.

## Non-negotiable behavior

- Board terrain, elevation, exits, visibility, movement and LOS belong to the game.
  Cosmetic relief must not silently create a new gameplay obstacle.
- Drawing, picking and unit support share `BoardSurface`. Place dressing on the
  installed finished geometry; do not maintain a second cosmetic heightfield.
- Reuse canonical edge/corner samples and absolute cliff rows. Preserve seams at
  mixed elevations, mixed LOD, negative levels, road cuts, shores and map edges.
- Ordinary ground stays six triangles. Pores, grains, small cracks and wear belong
  in material maps; spend triangles on visible silhouette or support only.
- Concrete stays constructed, with geometric tops and retaining faces. Roads keep
  their width, native grades, roundabouts and tunnel/bridge clearances.
- Level one/two transitions stay native slopes where the board allows them. Do not
  create prominent features by converting small banks to cliffs.
- Reuse existing resource ownership and draw batches: no per-hex textures,
  materials, simulation state or unbounded scatter attempts.

## Current renderer

- **Geometry.** Ordinary planar hex tops are six-triangle fans; cliff and coast
  outlines use polygon triangulation with shared boundary samples. Rounded cliff
  tops refine their interior shading samples even when a centre fan would fit.
  Dry natural drops of three or more levels use 60% of the gentle-slope transition
  room (2.4 m per side at the default 4 m), leaving wider connected tops.
  Directional joints connect ribs, caprock breaks and talus, and fallen blocks group
  below clefts; see [canonical geometry](gpu-terrain-materials.md#canonical-geometry).
- **Rocks.** Sand, desert, Mars, volcano and concrete use angular blocks for rough,
  rim, slope, cliff and scattered rocks; other families use rounded boulders. Rough
  forms fractured groups of a rooted mass and two smaller pieces, with a
  board-space fracture direction shared by nearby hexes.
- **Materials.** All sculpt materials use shared texture arrays, world-scale
  projection, height-based contacts and projected-size normal detail. Desert, Mars
  and volcanic plateaus expose connected stone; sandstone and basalt use authored
  height sources, and their caps fade around the crest into the face. Concrete
  faces sample along their rim direction. Eleven ground families have ambient wear;
  the SAND tile cover has none. See [materials and geometry](gpu-terrain-materials.md).
- **Finish.** Every surface material authors its finish: roughness, metallic,
  subsurface scattering and emission ([board
  materials](gpu-board-materials.md#finish-one-meaning-on-every-surface)). Roughness: grass, earth and
  contact soil .92, sand and tropical .94, dirt, hardpan, gravel, scree and concrete
  .90, granite and basalt .73, rock .80, sandstone and Mars rock .81, snow .82, lunar
  ground .96 (its cliffs .92, scree .90) and fungal ground and cliffs .84 (mats .90,
  fibres .92). All but concrete vary by ±.04 with their grain, smoother on raised
  grain. No terrain material is metallic, scatters or glows yet. The `cliffMaterial`
  path, tundra mats, turf, meadow blades and road coats read the same maps as tops, rock
  kits, pavement, tree pits and volcanic programs' natural material.
  Marsh peat (.80), quick mud (.72) and moss (.93) stay rough; only exposed pools get
  the water response. Cavity is visibility, not roughness.
- **Lighting.** The scene stays linear in RGBA16F targets with highlight headroom,
  and emission joins reflected light before the single display encoding. Direct
  light uses energy-coupled GGX; an offline table integrates the analytic sky for
  environment reflection. See the
  [light and color contract](gpu-atmosphere.md#light-and-color-contract).
- **Imported models.** `model-surface.glsl` lights units, buildings and props with
  their glTF roughness/metalness factors and optional packed map (G roughness,
  B metalness), normal maps on a tangent frame derived from the mesh UVs, and
  occlusion on indirect light, through the same GGX and environment helpers as
  terrain.
- **Vegetation.** Instanced trees, palms and cacti have four LODs within
  480/240/96/12 triangles. Cutout crowns carry normal maps and packed
  occlusion/roughness/transmission maps. Grass (roughness .86), bark (.90) and
  snow-covered plants (.95) use the shared dielectric reflection. Local light from
  lava and luminous fungi reaches plants and distant cards; transparent texels are
  discarded first.

## Controls

Printable board PNGs are straight-alpha cutouts. Their scene is composed in linear
premultiplied form, then unassociated once for display encoding; material coverage,
including translucent ice, is preserved. Emission on the board remains visible;
lens halos and glare are clipped to physical coverage, and additive light outside
that coverage is omitted. A single ordinary RGBA PNG cannot retain additive light over arbitrary replacement
backgrounds. This does not change emission or lens effects in the live board view.

| Control | Default | Effect |
| --- | --- | --- |
| `GpuGroundDamage.COMBAT_SCARS` | `true` | Source constant for [combat scars](gpu-terrain-materials.md#combat-scars), independent of ambient wear. |

Elevation grading leaves level zero unchanged. Uphill, one scalar exposure curve
keeps RGB ratios and bright texture variation; valleys darken and warm, 12.5% at level -1 building to 50% at -6
and below (linear reflectance; about 6% and 27% on screen), so each level of a deep pit reads apart; fungus lifts
more strongly uphill, near-white snow darkens 5% at -1. Beds under water, which their depth already darkens,
keep a gentler grade above level -1.5. It applies to the completed material colour of ground and Classic column
artwork before lighting, and grass blades, crops, sedge and roads share its downhill
part; magma keeps its own
shading. It is presentation styling, not a physical claim about altitude.

## Open work

| Priority | Open work | Acceptance boundary |
| --- | --- | --- |
| 1. Lighting and material consistency | Photographic material accuracy is not established: passing the linear-light and BRDF checks does not prove it, and the final appearance review of the maps is pending. | The default landscape review's 34 top/isometric conditions plus dry/wet plant checks. Record render cost separately from geometry counts and whole-game FPS. |
| 2. Vegetation assets | Crowns and trunks show their authored shapes at close range; silhouettes, branch art, large-form variety and ground contact are below the photorealism target. | Preserve grass fullness, support, alpha coverage and the 480/240/96/12 triangle budgets. Replace an asset only for a visible improvement at gameplay scale. |
| 3. Landscape composition | Large level-derived plateaus and tall hex columns remain visible; outcrop and fin-group placement is not done. | Keep game heights, route clearance and shared picking/support. |
| 4. Material sources | Height and roughness are artistic estimates; the roughness maps derive from each material's height, not from measurement, and source images carry some baked lighting. | Preserve aligned channels, shared arrays and continuous weathering. Raise resolution only for a demonstrated texel-density problem. |
| 5. Contact and image stability | Motion aliasing and photographic asset quality are visible limits. | Keep the existing contact shading. A new screen-space or temporal pass needs its own visual and timing comparison. |
| 6. Combat scars | First impacts in new areas pay projection and upload cost, and array growth re-uploads existing marks; the per-frame queue bound is not a millisecond budget. | Follow confirmed attack playback; no new playing heights, terrain triangles or per-mark draws. |

Shader costs and the remaining expensive workloads, including terrain-only scenes
on Intel Iris Xe, are in the [shader audit](gpu-shader-audit.md). No measurement
establishes complete-game 60 FPS on any adapter. Many local lights over dense
vegetation add GPU cost (`GpuVegetationLightingSmokeTest` benchmarks it), and local
spill casts no occlusion. Environment reflection covers the analytic sky and ground
only; nearby objects are not reflected.

## Acceptance rules

- **Materials.** Stone must not read as tiled masonry; plateau and face meet
  without a painted seam; sand and snow dominate open flats; normal maps respond to
  light near the camera and fade correctly at distance. Inspect every family a
  shared helper affects, including concrete and volcanic terrain, in both views.
- **Maps.** Colour, normal, height and cavity agree spatially. Correct baked
  lighting and wrong texture scale before raising map resolution. Add roughness
  data only where the existing response cannot produce a required visible effect,
  after verifying that the lighting path consumes it. No displacement texture may
  change legal support.
- **Contacts.** Review same-family ground–mantle–stone contacts as carefully as
  cross-biome ones. Snow accumulates over soil and grass, sand forms deposits rather
  than recoloring turf, and grass forms irregular tufts at a sandy boundary.
  Preserve long authored `ground_fluff` gradients, and express contacts through the
  shared role calculation rather than a branch per biome pair.
- **Landform.** Deposits correspond to breaks in the face; neighboring hexes read
  as one cliff system; a large cliff has broad quiet planes and deep recesses; small
  slopes stay slopes. Watertightness, picking, support and road-clearance tests pass
  before any visual review.
- **Landmarks.** Derive them from existing elevated/rough cells and cliff topology:
  isolated tall hexes for columns, convex cliff ends for buttresses, narrow ridges
  for fins and concave feet for talus fans, within their legal footprints and
  centre/support constraints. Connected cliffs share regional bedding and strike.
  Prefer the reusable rock/outcrop assets; author a GLB only for a silhouette the
  kit cannot produce, with its editable source, bounds, material role and LOD beside
  it. A distant LOD keeps the landmark's outline. A board gets recognizable focal
  formations, not a forest of random towers, and the same board and seed give the
  same landscape.
- **Lighting and vegetation.** Compare matched midday, low-sun and overcast
  captures; relief must read from normals and shadows, not baked black cracks. Keep
  the shared lighting path, without showcase-camera special cases. Plant clustering
  follows the biome/soil field and preserves woods density; keep broad quiet ground.
  The terrain looks consistent across lighting, vegetation does not look like pasted
  props, and materials keep their identity at playing distance. A good close-up
  alone does not pass.

| Surface | Physical cues | Implementation boundary |
| --- | --- | --- |
| Temperate rock/grass | Rock planes and fractures, soil in recesses, grass rooted in soil, sparse moss stains. | Rock/mantle/ground roles; grass pigment and roots follow the same broad cover. |
| Desert | Stratification, broken caprock, wind-scoured shoulders, sand in sheltered pockets. | Sandstone/sand roles; vertical strata stay upright when breaking repeats. |
| Snow/ice | Scoured rock ribs, snow on upward ledges and in hollows, exposed side facets on blocks. | Snow roles and the dedicated ice renderer; never reveal undiscovered black ice. |
| Dirt/gravel | Compaction, grains, sparse ruts and local wet depressions. | Road/ground material response; road geometry and wheel-track spacing are separate. |
| Volcanic/lunar | Cooling fractures, irregular rock plates and terrain-specific deposits. | Keep the volcanic shader and zero-gravity behavior; no gravity-driven snow or talus there. |
| Concrete | Cast planes, joints, clean silhouette and correct side projection. | `BoardConcrete`, not natural weathered cliff geometry. |

## Budgets and verification

Budgets are acceptance rules, not claims of measured speedups:

- Material/contact-only work adds no geometry, material groups or draw calls for
  the same scene and camera, and reuses the two 512² sculpt layers per material.
- Landform work uses existing samples first. More vertices need a silhouette or
  support defect that those samples or maps cannot solve.
- Scatter keeps bounded candidates, shared meshes/materials and current LOD.
  Measure accepted placements and submitted geometry, not one asset's triangles.
- Before adding board-sized state or per-tile allocation, run
  `gpuBoardLargeMapTest`, a 200×200 board under a 512 MB heap. A small showcase
  does not establish large-board memory safety.
- A performance claim needs warm, repeated CPU/GPU frame times with percentiles,
  build/edit latency, heap/VRAM and per-pass submissions on a named adapter and
  resolution. `GpuStageTimings` records asynchronous GPU timestamps; keep GL-call
  counting and coverage out of timed samples, and measure loading, shader linking
  and camera-refinement stalls separately. First-run build timing includes cache
  and initialization effects. A 60 FPS target means 16.7 ms per complete frame.
- Keep the 16-sampler portability check (`GpuSamplerBudgetSmokeTest`). Run the
  material map check (`GpuMaterialLodSmokeTest`) when changing
  normal/height reads, filtering or layer selection.

Shared geometry changes use `BoardSculptTest`, `BoardReliefTest`,
`BoardSurfaceContactTest`, `BoardPlateauShadingTest`, the road/slope/tunnel
regressions and mixed-LOD checks. Shared shader changes use
`GpuTerrainContactSmokeTest`, `GpuMaterialLodSmokeTest` and
`GpuSamplerBudgetSmokeTest`. Add a test only for a newly identified behavioral gap.

The visual regression set is the terrain showcase, Short Canyon, `16x17 Mines 1`,
`16x17 Lava Tubes 1`, `Strassengitter 3`, a concrete city, snow/grass and
sand/grass contacts, six-exit ramps/bridges, and a large board. Check for holes,
floating dressing, terrain penetration, repeating patterns, transition rings and
distant shimmering.

## Native reviews

These tests open an OpenGL window and save captures under
`megamek/build/gpu-board-review/`; `-Dmegamek.gpu.screenshots=<folder>` selects
another folder. Keep the original captures when comparing a later change.

`GpuTerrainRealismSmokeTest` renders shipped landscapes through the real scene
compositor in top and isometric views, and times settled terrain frames with
`GpuStageTimings`, separately from loading and whole-game FPS. By default it covers
Heavy Forest 1, Box Canyon (Savannah), Mountain Lake (Savannah), Lava Tubes 1 and
Fungal Crevasse at morning, midday and overcast, plus night on the lava and fungus
boards. `megamek.gpu.realismBoards` also accepts `dome-vent`, `box-canyon` and
`calibration` (the [material calibration board](gpu-terrain-calibration.md));
`megamek.gpu.realismViews` and `megamek.gpu.realismConditions` narrow the views
and conditions.

```powershell
.\gradlew.bat :megamek:gpuBoardBenchmark --tests '*GpuTerrainRealismSmokeTest' '-Dmegamek.gpu.realismBoards=forest,canyon,lake,lava,fungus'
```

`GpuTerrainMaterialReviewSmokeTest` pans and zooms deterministically over sand,
desert and volcanic terrain, top and isometric. It times the moving frames, checks
that pans keep aligned material pixels stable and captures gameplay scales and
crest close-ups.

```powershell
.\gradlew.bat :megamek:gpuBoardBenchmark --tests '*GpuTerrainMaterialReviewSmokeTest'
```

`GpuBoxCanyonSmokeTest` captures material, normal, contact-distance and albedo
views of the notched Box Canyon plateau tops; `megamek.gpu.measureBoxCanyon=true`
adds timings. `GpuGroundDamageSmokeTest` checks combat scars over the worn
ground; `megamek.gpu.measureGroundDamage=true` times both, and
`megamek.gpu.measureAccumulatedScars=true` times LRM20 bursts
that leave 1000 scars.

```powershell
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuBoxCanyonSmokeTest' '-Dmegamek.gpu.measureBoxCanyon=true'
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuGroundDamageSmokeTest' '-Dmegamek.gpu.measureGroundDamage=true'
```

`GpuTerrainShowcaseSmokeTest` renders a deterministic plateau, notch, formation,
ridge, basin, lake and woods for each surface family;
`megamek.gpu.showcase.families` (default `SAND,GRASS`) and
`megamek.gpu.showcase.hours` choose families and times.

## Environment lighting table

`environment-lighting.bin` is generated offline. From the repository root, run
`java scripts/GenerateEnvironmentLighting.java <new-output-file>` with JDK 25, then
copy the reviewed output to `megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/`.
The tool refuses to overwrite an existing file; the runtime validates the table's
dimensions and source radius.

## References

ImageGen reference images are in
[mm-data/tools/terrain-realism](../../mm-data/tools/terrain-realism/README.md).
Generated paintovers are direction studies, never screenshots of an
implementation. The canyon concept exaggerates height and the sand paintover turns
some gentle banks into cliffs: keep their material hierarchy and weathering, not
those geometry changes. Review every generated asset for scale, repeat, baked
lighting and invented features before use.

- **Gaea:** erosion has separate wear, deposit and sediment outputs, and larger
  structures come before smaller erosion. A gully, its exposed rock and its debris
  share a cause; borrow that relationship without simulating erosion during play.
  [Erosion](https://docs.gaea.app/reference/nodes/simulate/erosion),
  [erosion workflow](https://docs.gaea.app/using/using-gaea/understanding-erosion/erosion_2/).
- **Gaea's showcase:** broad rock masses, irregular subordinate cuts and strong
  light/shadow organization matter more than fine noise. The monolith image is a
  composition reference, not a literal obstacle for an ordinary clear hex.
  [Gaea 3](https://quadspinner.com/Gaea3),
  [monolith image](https://cdn.gaea.app/web2026/img/gaea3/gi2.webp).
- **World Creator:** exposed rock, channels, angular fragments and quieter lower
  slopes connect; sand accumulates through masks with an associated material layer.
  This suggests shared distribution controls for existing geometry, cover and
  scatter, rather than independent noise for each.
  [Showcase](https://www.world-creator.com/en/index.phtml),
  [debris image](https://world-creator.b-cdn.net/assets/img/index/World-Creator-0015.webp),
  [sand workflow](https://docs.world-creator.com/walkthrough/run-simulations/add-sand).

These are visual and engineering interpretations, not a need for either product's
architecture; their offline renderers are not a real-time performance target.

## Deliberately deferred

Full heightfield replacement, runtime hydraulic/thermal erosion, voxels, dynamic
tessellation, a new terrain framework or editor, a new asset or biome registry, a
node graph, virtual texturing, path tracing and a global geology simulation.
Reconsider one only after a specific measured limitation defeats the existing
implementation.
