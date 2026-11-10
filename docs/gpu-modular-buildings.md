# Modular buildings

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `TilesetScenery.customBuildingFile` | Map selected tileset artwork to the custom building catalog. |
| `GpuAssets.building` | Choose custom kit or legacy fallback and own shared loaded assets. |
| `ConnectedKit` | Read and check a kit's pieces; place base, floors, roof and connection pieces for a hex's exits. |
| `GpuBuilding` | Upload a kit, choose stable base/floor/roof recipes, instance their placed pieces and expose picking geometry. |
| `GpuBuildingInterior` | Generate shared floor sheets and supports from the roof's occupied footprint. |
| `BoardIndustrial` | Generate deterministic machinery with independent equipment heights and neighbor pipe joints. |
| `BoardWall` | Choose a wall family from pristine map CF and assemble its layered components for drawing, picking and clearance. |
| `GpuTreeInstances / GpuTerrain` | Instance module ranges; install chunks and retire unused recipes/interior buffers. |

The board's tileset still chooses building artwork. `TilesetScenery.customBuildingFile` maps the selected legacy
asset to `data/models/buildings/<family>/<name>.glb`, removing the `saxarba/` provenance directory. For example,
`buildings/saxarba/fortress_light/fortress_light_a_52` selects
`data/models/buildings/fortress_light/fortress_light_a_52.glb`. Without that exact file, a kit named for the design
alone stands for all of its per-combination keys: `buildings/saxarba/fuel_tanks/fuel_tank_light_05` selects
`data/models/buildings/fuel_tanks/fuel_tank_light.glb`.

Every kit may carry connection pieces (`data/models/buildings/README.md`). The hex's own exits pick them
(`ConnectedKit.reach`): a connected side runs the structure's whole height, lowered to the top of a neighbour of the
same structure terrain (building or fuel tank) that connects back where their vertical spans overlap. An exit edit
rebuilds the hexes within two of it. The fuel tanks connect by ground pipes, which stand in their base module only.

`GpuAssets.building` tries that file first for building, fuel-tank and industrial terrain. Missing kits keep the existing
`data/models/board/buildings/...` GLB/G3DJ loader, height fitting and wall textures.
Discovery accepts a custom kit even if there is no legacy model.
Malformed custom kits fail with an authoring error rather than silently concealing invalid assets.

Generated `buildings/wall-<exits>` uses `data/models/buildings/walls/<family>/` in both
the game and editor. Pristine `BLDG_CF` selects civilian (1–10), light (11–15), medium
(16–40), heavy (41–90), hardened (91–150), or Castle Brian (151+). `Cover.pristineCF`
captures the map value separately from current gameplay CF, so damage cannot change
the family. Editor CF edits across a tier boundary invalidate geometry and select a
different cached layout. Explicit wall model overrides retain their authored appearance.

Each wall component has base/floor/roof modules and two internal LODs. The shared
module-role selection grounds one base and repeats 18-unit floors for the remaining
rules levels, then adds the roof above them. Picking and vegetation clearance use the
same assembled geometry and scale. Civilian connectors use the narrower junction footprint.

## Asset contract

One building kit per GLB, with identity root groups:

```
fortress_light_a_52-lod0
  fortress_light_a_52-lod0-base0
  fortress_light_a_52-lod0-floor0
  fortress_light_a_52-lod0-floor1
  fortress_light_a_52-lod0-roof0
fortress_light_a_52-lod1     (optional; same module roles)
  ...
```

Author buildings with `lod0` and, when useful, `lod1`. Consider `lod0` alone for a simple kit, especially below
200 triangles. The fortress ships with two levels. This is an authoring budget, not a loader rejection rule:
if a GLB also supplies `lod2`, the loader uses it. Other asset types retain their existing LOD rules.
Each child is a rigid mesh node; multiple material primitives in that mesh are fine. Variant numbers may be
sparse. At least one `base#`, one `floor#`, and one `roof#` are required; each role supports multiple variants,
including variant 0. Do not add helper mesh nodes.

Opaque materials may set glTF `doubleSided: true`. The shared rigid importer adds reverse-facing triangles with
reversed normals, preserving colours and UVs, so both sides use the existing lighting, shadow and cutaway paths.
Only double-sided primitives gain reverse faces; their additional vertices count toward the rigid asset's 65,535
vertex limit. Single-sided materials remain unchanged. Explicit inner walls remain useful when the inside needs
different geometry or textures. Alpha-blended and alpha-masked rigid materials remain unsupported.

Author each base and floor exactly one model level (18 units) high in Blender's Z-up space. The GLB exporter converts to
standard glTF Y-up. For enterable buildings, LOD0 has hollow walls with inward-facing geometry; runtime generates the interior slabs and
struts. LOD1 floor walls are flat, with oppositely wound faces so both sides remain visible, without thickness or
edge caps. The inside retains the shared concrete texture. Outside, a padded 2048x256 atlas combines concrete,
windows, doors and slab bands on the same surface, avoiding nearly coplanar accent meshes and their z-fighting.
The three small rooftop vents are omitted from LOD1. The fortress uses 32 triangles per distant floor and 262
for the complete distant kit. The atlas adds a shared texture; lower triangle counts alone do not establish an FPS gain.
Author the modules as a readable stack: this kit's base0, floor0, floor1 and roof0 stand at Z=0,18,36,54.
Both LODs share the same XY origin and are superimposed; hide one LOD when inspecting the other in a viewer.

The loader preserves each module's authored XY coordinates, including node transforms and baked vertex offsets.
It removes only the module's minimum Z, over its core and connection pieces, so modules can be stacked vertically. Author all modules and
LODs relative to the same horizontal origin; do not offset them sideways for display. Asymmetric protrusions do
not recenter a piece or shift its alignment with the other modules. Group transforms remain identity, as required
by the rigid importer. Rendering, picking, interiors and obstacle clearance use the same authored placement.

For N levels, select one random `base#`, then N-1 random `floor#` modules (including `floor0`), then one random
`roof#`. A single variant is always used when it is the only choice for that role. A placement seed derived from
the hex and selected artwork makes choices stable across camera movement, LOD changes and reloads. Base and roof
selection are independent of height, and a height edit preserves existing lower-floor choices. Offsets are `index * 18`.
Scale the assembled stack only by the board's configured level size; the roof is an additional cap, not a reason
to compress the floors. A five-level building has one base, four floor modules and its roof; a one-level building
has only a base and roof.

## Interiors and picking

Only `FeatureKind.BUILDING` requests an interior. Industrial terrain is captured separately as
`FeatureKind.INDUSTRIAL`, using the authoritative `Terrains.INDUSTRIAL` height. Its base/floor/roof names are
assembly roles for machinery, not occupiable storeys. Industrial and fuel-tank assemblies allocate no interior
and do not require closed wall outlines. Wall footprints are computed lazily when a real building requests them.

The full roof projection preserves notches, courtyards and disconnected wings; its lowest underside alone may
be only a border around an inset panel. The generated interior is clipped against closed mid-storey wall
sections of the pieces placed on each storey, using the external wall boundary so wall thickness remains included.
Sections come from the simplest authored wall LOD (all LODs must share the same envelope), independently of
camera distance. This keeps detailed window recesses and facade seams out of the floor geometry.
Roof overhangs, sidewalks and horizontal decorative ledges do not enlarge those wall sections. The common
footprint is extruded through the stack height and passed to `GpuBuildingInterior`; differing floor outlines
therefore use their intersection rather than allowing full-height columns to extend outside any storey.
Supports require their center and all four corners to lie inside the footprint. The roof cap's furniture does
not increase occupied floor height. Generated floors and struts enter the render/depth passes only while that
building is faded because of an occupant. Leaving, or disabling transparency, hides them again. A closed building's
floors and struts are built when a unit first stands in its hex, and kept from then on (`GpuTerrain#showInterior`):
a city's would be hundreds of megabytes that nothing shows. A hex's floors are one prop, which shows all of them or
none. A building that stands open builds them with its chunk and always draws its floors: a kit building where a hex,
or a hex it joins, has an exit with nothing connecting back, such as a collapsed neighbour (`ConnectedKit#open`); an
authored building where a hex of it beside the hex, or beside a hex of it the hex adjoins, has collapsed
(`GpuAuthoredBuilding#open`). Kit buildings, authored buildings and buildings without a kit (a generated multi-hex
building, or a kit that cannot load) all go through this one path; the last kind never stands open. A floor's
edge keeps 0.2 units off any outward wall face beside its slab (`GpuBuildingInterior#keepClear`), in kit and authored
buildings, so it never lies on a facade or a wall's cut end and flickers through it. Each kit caches its storey outlines by the placements of the
pieces that shape them; repeated storeys cost a lookup. They do not enter the exterior shadow pass, which retains the authored opaque shell. Occupancy cutaways
fade the walls and, a quarter of the way more opaque, all of the hex's floors; the struts remain opaque. Hovering opens
nothing, and neither does a unit standing on the roof: only a unit inside opens its building (in
the Tactical View a building under any unit goes, so none of it covers a token).

Picking keeps one triangle set per LOD0 piece and moves the ray into each placed piece's frame. It does not retain
expanded triangles for every possible building combination. Picking is independent of the displayed LOD.

A unit on a generated kit's roof is drawn on the roof's own surface (`GpuTerrain#roofLift`, `GpuBuilding.Assembly#roof`
over the roof parts keyed `GeneratedMesh.ROOF_DECK`): a vault's sheeting, a raised deck. Parapets, fittings and aviation
lights are other parts and never raise it. The lift is looks only, at most half a level, and fades out within a quarter
level of the roof's game height. A unit on an authored building stands at the game's height, its volumes' top.

Industrial pointer selection passes through equipment to the underlying terrain in both camera presentations,
as it does for foliage. Physical effects still hit the equipment's authored triangles. Industrial shells never
receive occupant fading or hover cutouts, including when a real building coexists in the same hex. The renderer
does not create or modify movement/LOS rules: industrial height already supplies cover without granting floors.

### Heavy industrial kits

The four standard `misc/heavy_industrial_a` through `d` assets use `BoardIndustrial` instead of a whole-hex GLB stack.
The tileset identifier chooses the family: cooling tower with generators; a farm of differently sized silos;
a pressure reactor with horizontal exchangers; or a flare stack with shorter distillation equipment. A seed
derived from coordinates and artwork chooses equipment details, distinct cap shapes and the smaller machines'
heights. Domes, pointed cones and flat tops with offset vents have different silhouettes. The tallest equipment,
including its cap, reaches the terrain's cover height; the other machines end below it. The central ground lane
remains open. There are no rooms, stairs or full-width decks.

Only adjacent generated industrial installations receive border connections. Both sides independently derive
the same edge midpoint, pipe radius and perpendicular approach. An unordered pair seed chooses connection
heights inside their overlapping vertical range. Tall neighbors have two distinct pipe heights, varied per
neighbor pair; short installations use one. Internal equipment also connects at different elevations, with
exchanger risers attached to actual drums. On uneven ground, connections pass above the higher ground and below
both installations' cover heights; steep differences omit them. Pipes route around the ground passage and
have supports. Isolated sides keep ground-mounted service valves
inside their hex rather than outgoing stubs. Neighbor edits use the existing two-hex terrain invalidation, and
remove or rebuild the connection without moving the remaining machinery.

`BoardIndustrial.model` supplies the same CPU triangles to rendering, physical picking and cosmetic obstacle
clearance. `GpuAssets` owns the generated models and retires layouts unused by installed/cached chunks at scene
commit. There is no generation during animation or camera changes. The current generator uses one bounded
mesh with two material ranges per layout, without a distance-dependent machinery LOD.

Generator fans sit in real roof openings with recessed blades. Parent shells omit end caps covered by another
cap, exchanger posts end below their top beams, and shared pipe supports emit one footing per location.
These avoid coplanar top faces that would flicker while orbiting. The geometry regression scans the uppermost
surface across all four families; native captures inspect the caps from four azimuths.

The two photographic IMAGEN materials are `models/board/textures/structures/industrial/paint.tres` and `steel.tres`
(loose: a wider export widens their planar repeat), with different texture scales and specular settings, circumferential tank/pipe UVs, full-resolution mip levels and
anisotropic filtering. These are diffuse/specular materials; the shader does not implement a full metalness
workflow. The original whole-hex modular GLBs are authoring prototypes; the runtime
generator supersedes them for these four terrain identifiers. Ordinary buildings keep the base/floor/roof contract.

## Cache and ownership

- A kit owns its shared module models and one triangle set per piece. Every key resolving to one kit file shares
  them. Textures use the existing `ModelTextures`/`GpuAssets` cache. Concrete trim and wall tints use vertex colors,
  sharing one embedded concrete image; the roof uses the second image, and LOD1 exteriors use the padded facade atlas.
- An assembly cache holds the selected modules and connection heights, its kit reference and shared interior
  reference. Its key also includes whether an interior was requested; the same kit can serve both terrain semantics
  safely. Placements are derived per LOD when an instance is made. There is no cached expanded Model per combination.
- Live scene instances are constructed during terrain preparation/load/edit. LOD transitions replace lightweight
  node/material instances while borrowing the same GPU buffers. A single-LOD kit never switches models.
- Floors and struts are shared by roof footprint and level count. Scene commits discard recipes and interior
  buffers no installed or cached chunk uses. Native resources have explicit GL-thread disposal.
- New buildings bypass the whole-building chunk mesh merger. The existing `GpuTreeInstances` backend also draws
  building module ranges, sharing one compact range buffer per LOD/material/pass across all placements. The GPU
  stores ordinary instance transforms in addition to the small recipe; total rendering memory is not a few bytes.
- A weak map caches persistent renderable-to-batch lookups without retaining its keys. Weak references never own
  GPU resource disposal. Unchanged frames reuse meshes and upload no new instance data; visibility/LOD changes
  can update instance buffers. There is no per-frame building assembly or interior generation.

Adding or replacing a GLB in an already-open viewport requires the existing asset reload or reopening the view;
missing kits and loaded kits are cached for that renderer's lifetime. Editing a building's height uses the cache.
