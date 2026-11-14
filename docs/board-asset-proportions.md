# Building and bridge proportions

These are the shared dimensions of native structures and manufactured bridges.
[Modular buildings](gpu-modular-buildings.md) describes the floor-kit contract.
Board units are defined by `BoardGeometry`.

## Owners and coordinate contract

| Owner | Responsibility |
| --- | --- |
| `TilesetScenery` | Resolve native structure designs and default kits. |
| `GpuAssets` | Load/cache board models, generated structures and modular assemblies. |
| `BoardGeometry` | Convert game levels and model units to displayed board dimensions. |
| `BoardBridgeMesh`, `BoardBridgeTruss` | Generate manufactured spans from the live road and on-deck rail footprint. |
| `BoardBridgeSlope`, `BoardBridgeFooting` | Grade the span and fit its bank landings and optional piers. |

The 3D board's hex is regular: 72 across its flats and 83.125 across its corners (the
regular 83.138 to a 32nd, so lattice coordinates stay exact floats), every edge midpoint
36 from the centre within 0.005. Files use glTF Y-up; the renderer uses Z-up.
Native modular kits stack one-level modules, with their roof cap kept separate
from the storey height. Board scale and level height apply to the assembled
structure. Their connected footprint and interior contracts are documented in
[modular buildings](gpu-modular-buildings.md).

## Manufactured bridges

`BoardFeatures.capture` preserves a bridge's exit mask, elevation and appearance.
During terrain chunk preparation, `BoardBridgeMesh` builds its slab and kerbs from
`BoardRoad` and the actual on-deck `RailRoute` courses; `BoardBridgeTruss` adds the
steel members for a truss design. All 64 exit masks use these same builders, and
explicit rail junctions and stubs extend the supporting footprint where needed.

The base carriageway is 15 model units wide. Deck Z=0 has a 1.5-unit slab below it;
standard outside rails reach Z=2.5. A truss has a low steel fascia and members
reaching Z=12.5. Board scale applies uniformly; terrain-level height controls
placement elevation. Shapes meet the actual hex edges, including closed cuts
where a truss member reaches an edge.

`BoardBridgeSlope` grades the generated facets. `GpuTerrain` retains the complete
shape for drawing, picking, decals and rail placement, including flat spans.
`GpuRoads` shades the connected road surfaces, while `BoardSurface` grades the
approach without raising the ground below the span.

Decks use the road system's material, world-space coordinates, wetness and surface
clearance. Changing exit patterns must not rotate/restart the material at a join.
Concrete sides and undersides use repeating face UVs; truss steel uses its span colour.
Deck-family selection across a connected span is described in [roads](gpu-roads.md).

Continuous decks have rails only on exposed outer boundaries, leaving exits open.
Road-only five/six-exit shapes keep their roundabout's raised concrete island;
an on-deck rail opens the centre so tracks remain supported and unobstructed.
Zero/single-exit shapes have closed terminal edges. Rendering supports all masks
even though construction/repair rules during play allow exactly two exits.

Span geometry is derived from the live footprint; there are no baked per-mask
span assets or separate saved authoring outlines to update. Terminal and pier
GLBs remain reusable details. See [bridge geometry](gpu-natural-bridges.md) for
bank connections, truss colours and pier ownership.

## Editing building assets

Buildings use native kits under `data/models/buildings/`: a generated JSON kit
or an authored GLB. Gameplay weight and class select the default kit; an explicit
native appearance can select another. See [modular buildings](gpu-modular-buildings.md)
and `data/models/buildings/README.md` for their geometry and material contracts.
Retired tileset building GLBs under `models/board/buildings` are not loaded.
