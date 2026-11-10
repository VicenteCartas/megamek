# Board materials

Every board material is a Godot 4 material resource. Almost all are a `StandardMaterial3D`,
exactly as Material Maker's **Godot/Godot 4 Standard** export writes it. The three with a
map that a standard material has no slot for (lava, crust and ice) are a `ShaderMaterial`,
which is how Godot itself adds maps to a material ([below](#shadermaterials)). Artists who
know Godot, or any engine that reads a standard PBR material, can read and replace these
files. No custom export target or packed files remain.

## Files

A material named `terrain/surfaces/grassland-ground` is
`mm-data/data/models/board/textures/terrain/surfaces/grassland-ground.tres` plus its maps beside it:

| File | Content |
| --- | --- |
| `<name>.tres` | The `StandardMaterial3D` resource: which maps it uses, and its factors. |
| `<name>_albedo.png` | sRGB colour; alpha is opacity where the material is transparent. |
| `<name>_orm.png` | Linear R ambient occlusion, G roughness, B metallic. |
| `<name>_normal.png` | Tangent-space normal, OpenGL convention (+Y up the image), as Godot and glTF store it. |
| `<name>_heightmap.png` | Grey height, white highest (Godot's `heightmap_texture`). |
| `<name>_emission.png` | Emission colour (sRGB). |
| `<name>_sss.png` | Subsurface scattering strength (R). |

A material uses only the maps its resource names. `GodotMaterial` reads the resource:
Material Maker's `format=2` text and the Godot 4 editor's `format=3` text (quoted ids,
`uid`, `res://` paths) both work. A `res://` path is relative to the textures folder, so open
`mm-data/data/models/board/textures` itself as the Godot project to edit them there; its
`project.godot`, `.godot/` and `.import` files are git-ignored. Map paths must stay inside the
textures folder. The game reads these `StandardMaterial3D` properties and ignores the rest:

| Property | Use |
| --- | --- |
| `albedo_texture`, `albedo_color` | Colour, times the tint. |
| `normal_enabled`, `normal_texture`, `normal_scale` | Normal; the scale mixes it with the flat normal, as Godot does (0 flat, above 1 steeper). |
| `ao_enabled`, `ao_texture`, `ao_texture_channel` | Ambient occlusion. |
| `roughness`, `roughness_texture`, `roughness_texture_channel` | Roughness: the factor times the channel. |
| `metallic`, `metallic_texture`, `metallic_texture_channel` | Metallic: the factor times the channel. |
| `heightmap_enabled`, `heightmap_texture` (or `depth_texture`), `heightmap_flip_texture` | Height, white highest. |
| `heightmap_scale`, `heightmap_deep_parallax`, `heightmap_min_layers`, `heightmap_max_layers`, `heightmap_flip_tangent`, `heightmap_flip_binormal` | Parallax ([below](#parallax)); for magma the relief range too. |
| `emission_enabled`, `emission`, `emission_operator`, `emission_texture`, `emission_energy` (or `emission_energy_multiplier`) | Emission. |
| `subsurf_scatter_enabled`, `subsurf_scatter_texture`, `subsurf_scatter_strength` | Subsurface scattering. |
| `transparency` | ALPHA (1) or ALPHA_DEPTH_PRE_PASS (4): the colour map's alpha (times `albedo_color`'s) is the opacity. `MASK` cutouts, plants and fences alike, draw their solid texels with depth; their faded texels then blend over the scene in any order, layers stacking as in life (`GpuCutoutFade`, in every view that draws a cutout). How much a stack covers is exact; its colour is a depth-weighted average that favours the nearer layers, not strict back-to-front blending. Only the nearest glass is captured, so a faded texel between two panes has both panes in front of it. Glass blends whole, last among the scene's surfaces, without depth writes or sun shadows: generated geometry's (kit glass roofs, the multi-hex domes) by its `.tres`, a GLB's material when its `alphaMode` is `BLEND` (the authored building exporter writes it for a transparent `.tres` that is no cutout). |
| `blend_mode` | With `transparency`: MIX (0) fades the whole lit colour by the opacity; PREMULT_ALPHA (4) fades only the diffuse share, so a reflection stays whole (glass). |

## Finish: one meaning on every surface

Whatever draws a material (the sculpted terrain and its covers, turf and meadow blades, roads,
buildings, props and scenery, classic tiles, industrial plants, trees, shrubs and fungus), it
reads the same channels from the material's `.tres`, with Godot's meaning, so a new export is
all a change needs: grass that glows, metallic sand or shrubs that scatter light.

| Channel | Godot's rule, as the game reads it |
| --- | --- |
| Roughness | `roughness` times the roughness map's channel; the factor alone without a map. |
| Metallic | `metallic` times the metallic map's channel; the factor alone without a map. The metal share reflects the albedo and diffuses none. |
| Subsurface scattering | Only where enabled: `subsurf_scatter_strength` times the scattering map's red. Thick bodies wrap the light past the terminator by that share; leaves, which are thin tissue, pass it through to their far side. |
| Emission | Only where enabled: (`emission` + map) × energy under the add operator (Godot's default), `emission` × map × energy under multiply. `emission` is an sRGB colour, black by default. A uniform map counts as a constant. |
| Ambient occlusion | The occlusion map's channel. |
| Normal | The normal map with its height rebuilt from X and Y, then `normalize(mix(N, map, normal_scale))`. |
| Height | Parallax ([below](#parallax)): Godot's whole algorithm on models and roads, one step on the sculpted terrain. |

Covers that bury a material keep none of its metal, scattering or glow: churned and charred
ground, scorch deposits, mud, marsh peat and tundra mats (which bring their own finish). A
material's own wear and wetness change its roughness only; the distance fade of its grain takes
roughness, metallic and scattering to the map's means (which costs no texture read) and keeps its
emission.

`material-finish.glsl` holds the shared record (`Finish`) and its blending;
`surface-shading.glsl` lights it (`metallicShare`, `Surface.scatter`, `Surface.emission`).
Two contracts carry it to the shaders:

- Array-drawn materials, the sculpted surfaces and the road materials, have their finish in the
  terrain's finish arrays (`finish-arrays.glsl`, `GpuAssets.finishArrays`): an RGBA8 layer of
  roughness, metallic and scattering for each material whose finish varies, an sRGB layer for each
  emission map, and per material its values or means, emission tint and constant glow. Sculpt
  materials sit at their sculpt layer's index, road materials from `ROAD_FINISH`. The layers follow
  the sculpt normal/AO layers in the sculpt data array, which the sculpt programs (`u_terrainData`)
  and road coats (`u_sculptData`) already sample, so the finish takes no texture unit of its own
  there; other programs bind the same array as `u_surfaceLayers`.
- A draw of one material, a model's or the board's own (`GpuModelMaterial`): roughness, metallic
  and scattering factors over a `METALLIC_ROUGHNESS` map, which carries the factors itself, the
  emission map as the emissive texture times its tint, plus the glow, and the heightmap parallax
  (the heightmap is the material's bump texture, so no attribute type is added). A GLB material whose base colour is a board material's `_albedo.png` *is*
  that board material: its `.tres` replaces the GLB's own metallic-roughness and adds every map
  the GLB leaves out (normal, occlusion, emission, height). `GpuAssets.finish` gives the board's
  own single-material draws (classic tiles, industrial plants) the same.

`mmEmission` says when a material glows: `always`, `night` (fades with daylight) or `off`. GLB material extras
override a shared `.tres`'s `metadata/mmEmission`; with neither it is always on. Off keeps the albedo, reflectivity,
textures and stored emission, so removing it restores them. A GLB's nonzero emissive factor replaces the shared
material's glow. A shared `.tres`'s `metadata/mmPaintMask = true` makes its albedo alpha a paint mask (extras
`mmPaintMask` for an image embedded in the GLB). A generated kit reads its pieces' materials from their albedo images,
whatever they are called. These controls survive authored hex slicing, LOD copies and generated modular kit assembly.

What keeps its own shading:

- Lava, crust and ice are `ShaderMaterial`s with shaders of their own ([above](#shadermaterials)).
- A GLB material with no board texture is the GLB's own glTF material: its roughness, metallic and
  emissive factors. On a plant it keeps its role's response (bark .90, snow .95, leaves passing
  .35 of their light).
- Impostors draw their plant's baked look; fungal glow breathes slowly across a colony.
- Marsh sedge, whose artwork is composited at run time, takes its material's finish as the
  means of its maps: a uniform roughness, metallic, scattering and glow. Crop plants have no
  artwork; they shade as dry leaves (roughness .86).
- Meadow blades are their grass: they take the grass material's finish where they root, as
  turf takes its ground's.
- Ground-damage brushes (`terrain/damage`) and the scatter atlas's props read their colour only:
  scars and scatter set their own finish.
- Modular buildings' bark takes `vegetation/bark`'s colour; its plant role gives it bark's .90.
- Roads darken by 70% of their occlusion map, as before.

Plant GLB materials on a board albedo are their `.tres` like any other model, roughness map and
scattering included. Shrubs and snowy canopies, which used to take the bark and snow roles' fixed
.90 and .95 without scattering, now take their maps' (about .6–.85, scattering up to .43): a
deliberate change.

### Parallax

Godot's heightmap parallax: the view moves every map read of a material by up to
`heightmap_scale` / 100 of a repeat, by the material's depth below the heightmap's white, as if the
surface had that relief. `heightmap_flip_tangent` and `heightmap_flip_binormal` reverse it along U
and V, and `heightmap_flip_texture` inverts the map. A relief that would move less than half a
pixel moves nothing visible, so its reads are skipped: at board distances most ground costs
nothing, and only deep reliefs (rock and granite cliffs, scree, caps) still move. The GPU tuning
panel's **Normal maps** switch turns models' and roads' parallax off with the normal maps;
**Terrain Parallax**, a switch of its own, turns the sculpted terrain's off, so it can be compared
on its own.

- Models (`model-surface.glsl`) and roads (`terrain-road.frag`) draw one material through its own
  UVs and take Godot's whole algorithm: with `heightmap_deep_parallax` it marches from
  `heightmap_max_layers` at grazing views to `heightmap_min_layers` head-on and interpolates the
  crossing, otherwise it takes one step. Plants (`terrain-foliage.frag`), scenery drawn with the
  ground (geysers, rubble, fortifications: `terrain-normal.frag`) and road coats that paint a
  sculpted material take none.
- The sculpted terrain blends up to four materials in a pixel, each read in several projections,
  so it takes one step per material, whatever the deep settings say (`relievedProjection` in
  `terrain-materials.glsl`): each projection is the plane it is read in, a top the ground's and a
  side the wall's, and the height is the colour map's, read once more at the unmoved coordinates.
  Natural ground, banks and cliffs of every family, volcanic ground, pavement and its rubble, and
  the rock kit's walls take it; tundra mats, mud, scorch, field earth, tree pits and concrete
  slabs keep their maps in place. A move under half a pixel reads nothing. Its height read is one
  trilinear sample, without the colour's anisotropic filtering. On llvmpipe it adds about 1% to a
  grass board's frame at tactical zoom and 6% to a rock board's, 2–20% close up.

### ShaderMaterials

Three materials need one map that a standard material has no slot for, and colour data a
standard material would misread. Each is a `ShaderMaterial` of the `<name>.gdshader` beside it:

| Material | Extra parameter (map) | Colour space |
| --- | --- | --- |
| `terrain/lava/lava-flow` | `texture_coverage` (`lava-flow_coverage.png`): exposed-melt coverage | `texture_emission` is linear radiance, times `emission_energy` (5) |
| `terrain/lava/lava-crust` | `texture_glow` (`lava-crust_glow.png`): fissure glow | `texture_emission` is linear heat in R, which the shader colours |
| `terrain/ice/ice-sheet` | `texture_cracks` (`ice-sheet_cracks.png`): cracks and bubbles | `texture_albedo` is a tint whose hue alone matters |

The standard maps and factors take the names of Godot's own converted shaders: `albedo`,
`texture_albedo`, `roughness`, `metallic`, `texture_normal`, `normal_scale`,
`texture_heightmap`, `heightmap_scale`, `texture_emission`, `emission_energy`,
`texture_subsurface_scattering` and `subsurface_scattering_strength`. One `texture_orm`
holds occlusion, roughness and metallic in R, G and B, as Godot's ORMMaterial3D packs them.
As in Godot, a parameter the resource omits takes the default the shader declares, and a
parameter the shader does not declare has no effect.

The `.gdshader`s are previews for Godot only: each declares the parameters, with hints that
give each map's colour space, and draws a static surface close to the game's look. The game
reads the parameters and renders these materials with its own shaders (`terrain-magma.glsl`,
`magma-flow.glsl`, `magma-solid.glsl`, `terrain-ice.glsl`), which add the board's flow, banks,
floes and edges.

Their Material Maker projects carry their own export target, *Godot 4 ShaderMaterial*: the
standard maps, the extra map from a stock Export node, and the `ShaderMaterial` itself (see
`mm-data/tools/materials/README.md`). A `StandardMaterial3D` with a `<name>.gdshader` beside it,
which a *Godot 4 Standard* export of them leaves, is refused with an error naming that target.

## How the renderer packs them

`GpuMaterialMaps` repacks the files on the CPU into the GPU layouts the shaders have
always read, so the shaders and the texture-unit budget are unchanged. Packing moves
bytes between channels unchanged. Two exceptions: the normal's green is flipped from
Godot's "up the image" to the board's "down the image" (+V), and Godot's factors apply.
Converting the previous packed maps into this format and packing them again gives
the previous bytes exactly, except crust heat's G and B, which no shader reads.

| Layout | Channels | Used by |
| --- | --- | --- |
| `COLOR` | Albedo RGBA | Everything else's colour |
| `COLOR_HEIGHT` | Albedo RGB, height | Sculpted terrain (`terrain/surfaces`) |
| `NORMAL` | Normal XYZ, opaque | Roads, magma, ice, models |
| `NORMAL_OCCLUSION` | Normal XYZ, occlusion | Sculpted terrain |
| `NORMAL_ROUGHNESS` | Normal XY, roughness, occlusion | Mud and scorch, whose shaders rebuild Z |
| `RELIEF` | Height, roughness, occlusion, `heightmap_scale` × 25.5 | Roads and magma |
| `ICE` | Height, roughness, cracks, cloudiness | Ice |
| `OCCLUSION` | Occlusion, roughness, transmission | Models' glTF occlusion map |
| `METALLIC_ROUGHNESS` | Scattering, roughness, metallic | A model's or single draw's finish map |
| `HEIGHT` | Height, white high | A model's parallax |
| `MOLTEN_HEAT` | Emission RGB, coverage | Molten lava |
| `CRUST_HEAT` | Heat, –, –, glow | Solid crust |
| `FLOES` | `<name>_floes.png` as it is: joints, snow field, shard cracks, shard value | Ice, which alone has this map; no `.tres` or preview shader names it |

`heightmap_scale` is Godot's parallax depth in texture coordinates times 100: 10 is the
full range, 0.1 of a repeat. Magma's relief takes it too. For sculpted terrain it is the
project's *Relief*, a percentage of the repeat: at 2.5, a 2 m repeat lifts its white 5 cm above
its black.

Models (`.glb`) name these maps directly: a base colour `<name>_albedo.png`, a normal
`<name>_normal.png`, and an occlusion or metallic-roughness `<name>_orm.png`.
`ModelTextures` recognises a board material's map by its `.tres` beside it. The model
then draws it in the layout its glTF role takes, sharing the board's own texture.

### Sculpted terrain finish

Sculpted terrain takes each material's whole finish from the finish arrays. A uniform
finish is sent as its values; one that varies is a layer, resampled to the largest map
there, as the colour arrays are. Roughness 1 is fully rough: the game estimates no
roughness of its own for any material.

Every role of a natural surface (cover, mantle, debris and rock, on tops and walls)
reads its material's finish with the same projection as its colour, and so do tundra
mats, turf and meadow blades (their ground's or their grass's), road coats, pavement,
tree pits and rock kits, at every distance. The surface layers' mips widen the roughness by the
normals' spread ([material detail at distance](gpu-terrain-materials.md#material-detail-at-distance)).
Mud and scorch also keep their roughness in their own normal maps, which their shaders read.

## Loose and constrained materials

`textures/materials.json` sorts every material into one of two kinds:

- **Loose** materials repeat across the board in world space: terrain surfaces, roads,
  lava, ice and industrial plating. Each entry gives the width (`pixels`) of the texture
  it was catalogued at. Terrain surfaces and roads also give the metres one repeat spans at
  that width (`metres`); for the others, the renderer fixes the repeat.
- **Constrained** materials fit a footprint: facades, classic tiles, scars, scenery and
  plant cutouts. Their size never changes what they cover.

A loose material keeps its texel density. Exported wider than catalogued, it covers
proportionally more ground before it repeats: grass catalogued at 512 pixels and 12 m
covers 24 m at 1024 pixels. `GpuAssets.coverage` gives that factor (texture width ÷
`pixels`), and each world-space renderer multiplies its repeat by it, including the
enlarged secondary sample that breaks up terrain repetition. A loose material's maps
must be square. A terrain array holding a wider material takes its grid, and the
smaller materials are resampled up to it. To change a density instead, change
`metres` (or `pixels`) in the catalogue. *Reload assets* in the Tuning panel re-reads the
catalogue with the maps and rebuilds the board, so the new density shows at once.

| Value | What it is | What changing it does |
| --- | --- | --- |
| `pixels` | The texture width the entry describes; with `metres`, the texel density (`metres` ÷ `pixels` per texel). | No pattern size by itself: a texture exported wider covers proportionally more ground at the same density, narrower less. |
| `metres` | The ground one repeat covers at `pixels` wide (terrain surfaces and roads). | Stretches or shrinks the material on the ground: its features grow or shrink with its repeat. |

A texture covers `metres` × (its width ÷ `pixels`) before it repeats (`GpuAssets.sculptTile`,
and `GpuAssets.roadCoverage` in road repeats of 6 m, `BoardRelief.detailMetres(1)`). Roads are
1024 pixels over 12 m, so their stones and patches do not line up into a lattice along the road;
a road without `metres` would span one road repeat at its catalogued width. The road shader's own
patterns (wear, mud, loose clumps) are anchored to the ground in metres (`u_worldMetre`), whatever
ground a road texture covers. Road paint that lays a sculpted material takes that material's
catalogued metres too (`GpuAssets.sculptCoverage`), times the paint's own factor
(`GpuRoads.Patch.repeat`): a sandy or snowy verge meets the sand or snow beside it at the same
scale; markings take concrete at 1.5 times, a metal deposit's halo earth at 0.3 times. A terrain
surface's or road's Material Maker project measures its relief and crack shading per repeat, so the
catalogue alone sets its size (mm-data's `tools/materials/README.md`, which also says how to edit
these values safely).

### Loose (47)

| Material | Catalogued pixels | Metres |
| --- | --- | --- |
| `roads/asphalt`, `roads/dirt`, `roads/gravel` | 1024 | 12 |
| `structures/industrial/paint`, `structures/industrial/steel` | 1024 | – |
| `terrain/ice/ice-sheet` | 1024 | – |
| `terrain/lava/lava-crust`, `terrain/lava/lava-flow` | 1024 | – |
| `terrain/surfaces/concrete-mulch` | 512 | 1 |
| `terrain/surfaces/arid-cliff`, `arid-debris`, `concrete-bank`, `concrete-ground`, `desert-cap`, `desert-floor`, `desert-ground`, `dirt-debris`, `dirt-floor`, `dirt-ground`, `fungus-bank`, `fungus-cliff`, `fungus-debris`, `grassland-floor`, `grassland-ground`, `lunar-cliff`, `lunar-debris`, `lunar-floor`, `lunar-ground`, `mars-cap`, `mars-cliff`, `mars-floor`, `mars-ground`, `rock-ground`, `sand-ground`, `sand-verge`, `scatter-stone`, `snow-ground`, `temperate-bank`, `temperate-cliff`, `temperate-debris`, `tropical-floor`, `tropical-ground`, `volcano-cap`, `volcano-cliff`, `volcano-floor`, `volcano-ground` | 512 | 12 |
| `terrain/surfaces/biome-soil` | 512 | 20 |
| `terrain/surfaces/fungus-ground` | 512 | 22 |
| `terrain/surfaces/fungus-floor`, `rock-floor`, `tundra-mat` | 512 | 24 |
| `terrain/surfaces/tundra-ground`, `tundra-ground-variant` | 1024 | 48 |
| `terrain/surfaces/tundra-crust` | 512 | 28 |
| `terrain/surfaces/biome-mud`, `ground-scorch` | 512 | 64 |

### Constrained (106)

- `scenery/`: `demolition-materials`, `geyser-travertine`, `pool-water`, `rubble-brick`, `rubble-debris-concrete`, `rubble-debris-masonry`, `rubble-debris-timber`, `rubble-fines`, `rubble-slab`, `rubble-steel`, `rubble-timber`, `timber-bark`, `timber-endgrain`, `tunnel-concrete`
- `structures/common/`: `chrome`, `corrugated`, `corrugated-painted`, `diamond-plate`, `hazard-stripes`, `iron`, `louvre`, `rusted-steel`, `safety-yellow`, `steel`, `vent-slots`, `weathered-paint`
- `structures/facades/`: `facade-fortress`, `facade-hangar`, `facade-hard`, `facade-heavy`, `facade-industrial`, `facade-light`, `facade-medium`
- `structures/industrial/`: `fan`
- `structures/turret/`: `turret-dark`, `turret-edge`, `turret-paint`
- `terrain/classic/`: `concrete`, `dirt`, `fungus`, `high-incline-dark`, `incline-dark`, `lunar`, `mars`, `rock`, `sand`, `snow`, `volcano`, `water-bed`
- `terrain/damage/`: `ballistic-blast`, `ballistic-gouge`, `explosive-blast`, `fire-burn`, `laser-blast`, `laser-gouge`, `plasma-splash`, `ppc-blast`, `ppc-gouge`
- `vegetation/`: `bark`, `birch-bark`, `broadleaf-cutout`, `broadleaf-snow-cutout`, `cactus-flower-cutout`, `cactus-skin`, `conifer-cutout`, `conifer-snow-cutout`, `fern-cutout`, `marsh-sedge`, `palm-bark`, `palm-frond-cutout`, `palm-fronds`, `shrub-cutout`, `snow`
- `vegetation/fungus/`: `fungus-atlas`
- `vegetation/mars/`: `mars-coral-atlas`
- `vegetation/orchard/`: `orchard-leaning`, `orchard-round`, `orchard-spreading`, `orchard-upright`, `orchard-vase`, `orchard-young`
- `vegetation/shrubs/`: `shrub-barren`, `shrub-desert`, `shrub-highland`, `shrub-rocky`, `shrub-snow`, `shrub-temperate`, `shrub-wetland`
- `vegetation/volcano/`: `cinder-bark`, `cinder-cutout`

Tree impostor atlases under `textures/vegetation/impostors` are baked renders of each plant's far mesh, not
materials: `<name>.png` its unlit colour from 32 directions, `<name>_normal.png` its surfaces' mean normal (RGB, as short
as they face different ways) and depth along each view (alpha), `<name>_crown.png` its coverage from above
(BoardTreeCoverage). Each impostor's coverage and colour are fitted per row of frames so that at the switch it covers
what the far mesh covers and is as bright, in the board's own frame at 8, 13 and 17 h, seen from each row's height
(`mm-data/tools/calibrate_impostors.py`, GpuImpostorCalibrationSmokeTest): the material's alpha cutoff, and per row a
colour factor and a coverage exponent baked into the atlas and recorded in the impostor material's extras
(`mmImpostorTint`), which `prepare_tree_lods.py` applies when it rebakes.

Every model colour image with transparent texels (leaves, impostors, fences) and every decal takes mip levels
averaged by alpha (`GpuColorTexture.cutoutLevels`, made beside the pooled decode): plain mipmaps mix in the colour of
the texels behind a cutout, and distant leaves took the colour of that filler, darkening as the view zoomed out.

## Authoring and export

Each material's Material Maker project is
`mm-data/tools/materials/<folder>/<name>/<name>.ptex`. Its stock **Material** node is
the material, and its Size is the export size. Export with *File > Export material >
Godot > Godot 4 Standard* into `data/models/board/textures/<folder>`. Alternatively, run
`python tools/materials/export.py [relative/path.ptex]` from the mm-data root, which
exports with Material Maker's command line at each node's Size and writes only the files
whose content changed (see `mm-data/tools/materials/README.md`). *Reload assets* in the Tuning panel picks
up a new export, and any catalogue change, without restarting.

`GpuBoardMaterialsTest` checks that every shipped material reads, that its maps exist,
and that it is catalogued exactly once, with square maps if it is loose. A `ShaderMaterial`
must assign every map its shader declares, set only parameters it declares, and be exactly
the materials with a `<name>.gdshader`.
