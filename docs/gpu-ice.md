# Ice and black ice

Ice shades the existing lake, road or ground surface. `GpuIceShader` owns the
optical material; `BoardLiquid` and the captured terrain flags decide where it
is present. Rules for discovery, movement and breaking ice stay in the game code.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `GpuIceShader`, `terrain-ice.glsl` | Floes, snow, joints, transmission, reflection and the jagged land-ice edge. |
| `BoardSurface.freeze`, `GpuTerrain` | Lake slab, its edges, ragged margin and floes; iced banks; mesh batching. |
| `GpuBiomeSurface` | Per-hex ice flags (texture B channel) for the land-ice edge; bit 7 marks land ice with no border within reach. |
| `Terrain`, `ServerHelper.checkEnteringBlackIce`, `MovePathHandler` | Store discovery, reveal black ice during movement and publish the changed hex. |
| `BoardEditorTerrain` | Authoring markers for hidden flags, independently of gameplay appearance. |
| `data/models/board/textures/terrain/ice` | Chromatic scattering-tint input, normal and packed optical controls. |

The Material Maker project `mm-data/tools/materials/terrain/ice/ice-sheet` exports the
`terrain/ice/ice-sheet` material, a Godot 4 `ShaderMaterial` of its preview shader `ice-sheet.gdshader`
([board materials](gpu-board-materials.md#shadermaterials)): `ice-sheet_albedo`, `_normal`,
`_heightmap`, `_orm` (roughness), `_sss` (cloudiness) and `ice-sheet_cracks` (`texture_cracks`).
A second Export node writes `ice-sheet_floes`, which only the game reads, repeating over 102 m (six plates,
16 floes): R joints, G where snow settles first, B the cracks between a broken border's shards,
A each shard's value, flat up to its edge.
Run `python tools/materials/export.py` from the `mm-data` root.
`u_iceCoverage` scales its repeat by its exported width against the catalogued 1024 pixels.
Its maps share arrays with the other full-size maps (`GpuAssets.fullSizeMaps`), which take the
largest layer's size: a wider export would enlarge every layer.

The albedo is a chromatic input to the optical coat, not a final diffuse albedo
or measured transmission coefficient. The shader divides a filtered RGB sample
by its mean (with a .05 floor), and uses 35% of that tint around its
bare-ice scattering color. Changing hue affects the coat; uniformly scaling RGB
largely cancels above the floor. Snow, cloudy ice, cracks and joints have separate
optical controls. Their light response and transmission still depend on the coat
and the surface beneath it.

This normalization happens after filtering. Pre-normalizing the PNG would change
mip and repeat blending and is not an equivalent optimization. The generator's
separate mean subtraction centers the engineered height field; it does not
normalize color brightness. The renderer packs height, roughness, cracks and
cloudiness into one surface map, and uploads it with the aligned normal as the
layers of one array (`u_fullSizeData`, with the other full-size maps). These
are artistic control maps, not measured ice samples. Rebuilding from a changed
source can also change those numeric maps; a color-only revision must explicitly
preserve and verify them.

## Lake and ground surfaces

A frozen lake is a slab whose top is the level units stand on, one pixel above
open water. It runs on into frozen neighbours and up banks below frozen land. It
breaks raggedly out over open water, shedding floes, and breaks off short of
ice-free shores, leaving a lead with rubble. All of that is drawn only; support
and picking keep the hex's waterline. Ice changes no bed: frozen and open water
at one level share the depths of their mouths and corners
(`BoardSurface.cornerDepth`), so no slit opens between their beds.

All ice shares world-anchored floes: snow drifts, bare grey ice and refrozen
joints, so lake and land ice continue across each other. Land ice meets dry
ground at its level along a shattered border straddling their hex edge: the dry
tile draws its side (`GpuIceShader` modes 4 and 5). Toward water or another
level it breaks off short. Ice never coats ground under water. Land ice coats its
ground's blended cover, so neighbouring materials still fade across the edge beneath it.

Costs: lake ice stays one transparent draw per chunk, blended after open water;
land ice is a shader variant of existing ground draws, and so is its border on
dry neighbours. Detail, cracks and joints fade with projected size; there is no
geometric LOD or reflection capture. The floes are one read of a baked map, and
each of the sheet's maps is read once. Cut faces skip floe and snow work that
cannot contribute to an exposed edge.

Inside an iced area (`GpuBiomeSurface`, B bit 7: a hex and all within two hexes
are land ice at one level) no border is searched and no ground is shaded beneath:
the constant `ICE_GROUND` stands in for it where bare ice lets it show.

`GpuIceShader.variant` folds the modes on the ground into two programs: land ice
with its dry border, black ice with its paved border. Slabs and waterfalls compile
their own, as they need no border or base shading.

The map holds no outline of a shore, so a shore can cut a shard straight.

Baked floes against the procedural ones they replaced: Iris Xe at 1080p, terrain
cache off, median frame ms over a palindrome of eight runs at Medium and four at High.
Plain ground, which neither changes, stays at 8.7 and 14.6 ms.

| View | Medium before | Medium after | High before | High after |
| --- | ---: | ---: | ---: | ---: |
| All land ice | 19.8 | 11.0 | 28.3 | 21.4 |
| Dry borders throughout | 18.7 | 13.9 | 24.7 | 21.9 |
| One land border | 12.9 | 10.2 | 17.8 | 16.5 |
| Black ice | 14.3 | 12.8 | 22.8 | 22.1 |
| Lake slabs | 17.8 | 16.3 | 31.7 | 31.6 |
| 16x17 Frozen Lake | 23.0 | 19.9 | 35.4 | 34.4 |

## Discovery and editor visibility

Black ice remains `BLACK_ICE` in the rules model after discovery. A discovery bit
on `Terrain` survives snapshots and game serialization; text board files start
hidden for a new game. The server reveals it through its movement-entry check
and sends the changed hex to clients.

Undiscovered black ice has no gameplay surface graphic or terrain-tooltip entry.
The Legacy 2D tile matcher also avoids letting invisible black-ice or metal metadata
select an unintended base pavement image. Revealing black ice must not replace its
rules type with ordinary lake ice.

The 3D editor receives explicit annotations for hidden terrain flags through
`BoardEditorTerrain`. Gameplay views do not receive those authoring markers.
See [map markers](map-editor-3d.md) for these annotations and [the 3D board editor](3d-board-editor.md) for editing.

## Frozen waterfalls

A frozen source pool now grows an ice cascade across its level drop, including
when the receiving pool is unfrozen. An unfrozen source retains its flowing
waterfall when it falls onto ice. `BoardSurface` supplies the shared crests and
receiving heights; the drawn slab meets the cascade without changing the level
where units stand. Freezing or thawing either endpoint invalidates that geometry.

`GpuFrozenWaterfall` builds deterministic fluted columns, a closed body and
hanging icicles. Rendering and picking use the same triangles. Ice material mode
6 reuses the existing ice maps and optical lighting for blue ice, frosted ribs,
trapped bubbles and reflections. It has no scrolling, spray or animated water
impacts. The existing straight cut at the board edge is unchanged.

`BoardFrozenWaterfallTest` covers mixed endpoints, slab joins, cache invalidation
and picking. `GpuFrozenWaterfallSmokeTest` checks both camera projections,
stationary ice, normal detail and thawing, and saves a 3200-by-1800 comparison of
a large flowing waterfall beside a frozen waterfall. Run the native check with
`gradlew :megamek:gpuBoardSmoke --tests '*GpuFrozenWaterfallSmokeTest'`.
