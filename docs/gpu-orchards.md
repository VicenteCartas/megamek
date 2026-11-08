# Orchard trees

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardFeatures` | Recognize orchard appearance, choose a species and place deterministic rows with road clearance. |
| `GpuAssets` | Resolve the authored orchard tree and snow variants. |
| `GpuTreeInstances / TreeLod` | Draw repeated trees at the selected detail in color, depth and shadow passes. |
| `mm-data tree assets` | Own the silhouettes, proportions, textures and authored mesh levels. |

`WOODS` with `FLUFF:12` selects the orchard models in the shared 3D board scene.
The marker takes precedence over ordinary biome trees and level-one shrubs;
jungle retains its own vegetation. Removing woods removes the trees even if the
cosmetic fluff remains. The hex's `FOLIAGE_ELEV` still determines their height.

Light orchards contain six trees in two planted rows that align across adjacent
hexes, heavy orchards nine, and ultra-heavy orchards sixteen. Coordinates
deterministically select and rotate the variants. Road clearance uses the same
trunk relocation as other woods.
Orchard fluff allows native ground materials; the Tactical View still shows the
orchard's tileset artwork.

The six forms are round, spreading, upright, vase, leaning, and young. Each has
its own mesh and atlas, with a snow-covered counterpart that
reuses the existing snow texture. Near and middle meshes have hanging fruit;
the far mesh retains the fruit colour in its canopy texture. Fruit uses solid
lighting, while leaves retain the existing foliage lighting.

| Forms (including snow counterparts) | Near triangles | Middle triangles | Far triangles |
| --- | ---: | ---: | ---: |
| Round, spreading, upright, leaning, young | 408 | 216 | 72 |
| Vase | 388 | 196 | 64 |

All levels share a ground origin and a 30-unit authored height. The existing
`GpuTreeInstances` and `TreeLod` select the meshes at 80/48/24 framebuffer pixels
with 10% hysteresis, including the colour, depth and shadow passes, and below
24 px the impostor (`-lod3`, 2 triangles) described in
[gpu-board-performance.md](gpu-board-performance.md). The atlas is
shared across parts and LODs through the existing texture cache.

Assets live in the sibling `mm-data` repository:

- `data/models/board/orchard-*.glb`: twelve models, three LOD nodes per file.
- `data/models/board/textures/vegetation/orchard/orchard-*_albedo.png`: six opaque
  1254×1254 atlases with canopy, leaf, bark and fruit panels.
