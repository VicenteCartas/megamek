# Level-one foliage

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardFeatures` | Interpret woods/jungle foliage height and choose the biome's low-vegetation family. |
| `BoardVegetation` | Supply placement inputs against the finished terrain. |
| `GpuAssets / GpuTreeInstances` | Load and instance shrub/cactus/reed assets using the shared foliage drawing path. |
| `TreeLod` | Select visual detail while preserving common placement across the asset's mesh levels. |

Level-one woods and jungle use nine purpose-built understory GLBs. `BoardFeatures`
chooses a family from the existing terrain and theme. `GpuTerrain` scales these
models uniformly to the foliage height, preserving their authored proportions.
They use the existing tree placement, asset cache, instancing and colour/depth/shadow
LOD selection. Taller woods continue to use the tree catalog.

The meshes and their textures are in the sibling `mm-data` repository:

- `data/models/board/foliage-{family}.glb`: three meshes under the existing
  `foliage-{family}-lod0`, `-lod1`, `-lod2` node convention, plus the `-lod3`
  impostor that every plant carries (see
  [gpu-board-performance.md](gpu-board-performance.md)).
- `data/models/board/textures/vegetation/shrubs/shrub-{family}_albedo.png`: original, opaque
  1254 × 1254 botanical atlases.

Tundra's tussocks are tufts of blades in the grass engine ([grass](gpu-grass-rendering.md#tundra-sward)), not models.

## Families and triangle counts

| Family | Selection | LOD0 | LOD1 | LOD2 |
| --- | --- | ---: | ---: | ---: |
| Temperate | Grass and paved parks | 460 | 236 | 92 |
| Highland | Elevated grass or tundra | 460 | 236 | 92 |
| Rocky | Rock theme | 460 | 236 | 92 |
| Volcanic | Volcanic woods and jungle | 480 | 240 | 96 |
| Wetland | Swamp, mud, water or dirt | 460 | 236 | 92 |
| Desert | Desert theme | 478 | 238 | 94 |
| Jungle | Jungle terrain or tropical theme | 472 | 228 | 96 |
| Barren | Lunar theme | 456 | 232 | 96 |
| Snow | Snow terrain or theme | 460 | 236 | 92 |

The [Mars coral kit](gpu-mars-corals.md) supplies Martian cover at every foliage
height, including on snow. Volcanic themes likewise retain cinder trees and their
low thickets on snow and pavement. For the other themes, snow takes precedence; jungle
or the tropical theme then selects jungle understory, followed by desert,
barren and wetland overrides. Existing light/heavy/ultra-heavy counts and deterministic
placement remain shared with the tree path. Foliage is only added for actual woods or
jungle terrain.

All roots reach local Z=0. LOD0 is 18 units tall; lower LODs share its coordinate system
and uniform normalization. The smallest cactus LOD is 17.77 units tall. Atlases have
separate canopy, leaf/skin, bark and accent panels with inset UVs. Each family borrows
one texture across all parts and LODs. Folded leaf geometry has explicit back faces.

## Desert topology

The prickly pear has eleven thick pads on branches extending in both horizontal
directions. Each pad has its own orientation. Child bases overlap their parent's rim;
Blender Boolean unions produce one welded, closed body, including the basal stem.
Lower-detail meshes retain the basal stem's ground contact without moving the
crown. Position-equivalent UV/normal seams may have separate vertices, but the
welded surface must remain closed, with every pad in one connected body.