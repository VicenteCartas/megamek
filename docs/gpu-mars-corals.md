# Mars coral vegetation

[Docs index](README.md) · [Terrain overview](gpu-terrain.md)

The MARS theme replaces woods and jungle with mineral coral colonies inspired by
1970s science-fiction miniatures. Snow, pavement, orchard fluff and level-one
foliage retain Martian corals. Bare ground does not acquire gameplay cover.

`BoardFeatures.MARS_CORALS` holds twelve authored colonies: ivory finger spires,
finger and antler crowns, spiral whorls and brain lobes, mauve perforated sea fans,
lattice spires and plate terraces, and hollow tube groves, crowns and organ pipes
with muted turquoise mouths. Light, heavy and ultra-heavy cover use two, four and
six colonies. They reach the hex's foliage height, except at most one shorter
colony in light cover and two in heavier cover, so the woods' height still reads.
Being solid, colonies keep their footprints off each other and off the neighbouring
hexes' colonies; crowded ones grow slimmer instead (as fungal colonies do,
[fungal terrain](gpu-fungus.md)). Selection and placement remain
deterministic and use the existing road clearance and terrain grounding paths.
Martian colonies also vary their rotation by hex, avoiding identical fan alignment.
The original woods/jungle terrain, density and foliage elevation are never changed.

`GpuTerrain` fits corals uniformly to that foliage height and marks their `coral`
material as solid vegetation. The same scene, asset cache, instancing, picking and
color/depth/shadow paths serve both camera projections. Other themes retain their
existing vegetation; fungal cliff growth and scatter are unchanged.

Coral placement derives clearance from the root geometry beside rims, retains
captured road clearance and samples the drawn terrain around it, including adjacent
hexes. The bottom six percent of the uneven mineral base is embedded, with uniform
scale adjusted to preserve the exposed foliage height. Root radii are derived once
per model during chunk construction; upper branches do not enlarge that footprint.
This prevents the colony from balancing on one low vertex or a raised ground point.
Mars scatter stones use the same `mars-cliff` texture as larger rocks in the
sixth slot of the existing scatter atlas, without the old red sandstone tint.

The sibling `mm-data` repository contains:

| File | Purpose |
| --- | --- |
| `data/models/board/mars/*.glb` | Twelve runtime models. |
| `data/models/board/textures/vegetation/mars/mars-coral-atlas_*` | Shared opaque atlas (Material Maker project `tools/materials/vegetation/mars/mars-coral-atlas`): porous ivory, veined dusty mauve, weathered tubes and dusty basal crust. |
| `tools/board-models/alien_lods.py` | Builds the mesh levels from the original sculpts in Blender. |

The normal Gradle `stageData` task copies the GLBs and texture into the game's data
directory.

Each asset was sculpted at 16,000 triangles (the sculpts are kept in mm-data's git
history). `alien_lods.py` welds each sculpt, opens the sea fans and lattice slightly
toward the sky, bakes its crevices (Blender's vertex dirt), sun-bleaching and
regolith dust on top faces into its corner colours, and decimates it to 3,200 / 820
/ 96 triangles; the fourth level is the baked impostor (`prepare_tree_lods.py`).
Fans have fewer openings at distance, so their topology can simplify while keeping
a fan silhouette. Every level is grounded at local Z=0 and is 30 board units tall.
The near meshes have sculpted mineral pits, irregular fan cells and structural
veins, curved trumpet bodies with rolled mouths, and encrusted bases. Their atlas
adds small pores, weathering and Martian dust without changing the shared shader. Snow uses the same mineral models on snowy ground; this kit does not
add separate modeled snow caps or cosmetic cliff/scatter colonies.

`BoardMarsCoralTest` covers theme selection and overrides, densities, deterministic
placement, unchanged rules, road clearance, bare ground, and real GLB loading,
textures, grounding, height and decreasing mesh counts. `GpuMarsCoralSmokeTest`
captures dry and snowy colonies in both projections and at close range, renders
the far view, checks board picking and checks for OpenGL errors.

Focused verification commands:

```powershell
.\gradlew.bat :megamek:test --tests '*BoardMarsCoralTest' --tests '*BoardFungusTest' --tests '*BoardFeaturesTest' --tests '*TreeLodTest'
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuMarsCoralSmokeTest'
```

Native screenshots are written under `megamek/build/gpu-board-review/mars-corals/`.

Verified on 2026-10-04: all 44 focused tests, the native GPU coral smoke test and
`checkstyleMain` passed. The dry-ground, snow and material-detail captures were
visually reviewed against the final detailed assets.

The subsequent scatter-color and grounding fix passed 26 focused tests across
`BoardScatterTest`, `BoardMarsCoralTest` and `BoardObstaclesTest`, plus
`GpuMarsCoralSmokeTest` and `checkstyleMain`. The native test now checks transformed
root geometry against the rendered terrain, including adjacent hexes, rejecting
both floating roots and excessive burial on narrow terraces. Updated captures
include `mars-root-contact.png` and `mars-scatter-bedrock.png`.
