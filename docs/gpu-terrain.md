# Terrain: from board data to a frame

The terrain renderer converts captured hex data into shared surfaces, placed
features and material inputs. Rules for movement, elevation, hazards and line of
sight remain in the game model. The classes here are in
`client/ui/clientGUI/boardview/gpu`; use the
[code responsibilities](gpu-code-map.md) for the full class map.

## Capture, geometry and installation

`BoardFeatures` captures terrain appearance, exits and feature heights.
`BoardArtwork` resolves tileset images and model references.
`BoardLiquid`, `BoardFireSmoke` and `BoardBiome` provide liquid, hazard and
biome inputs derived from the same scene.

`BoardGeometry` defines coordinates and scale. `BoardSurface` describes finished
topography, including road ramps, shores and beds. `BoardRelief` constructs the
shared natural tops, corners, cliffs and rims; `BoardConcrete` handles slabs and
foundations. Drawing, picking and unit support must agree on these surfaces.
A gap or misplaced unit therefore starts with geometric queries, rather than a
camera-specific visual offset.

`GpuTerrain` prepares terrain chunks from captured data on CPU workers, then
installs GPU resources on the render thread. Request generations reject old work.
Local edits invalidate the changed area plus neighbors needed by shared geometry
and fields. Camera detail changes select a representation of the same scene.

[Materials and geometry](gpu-terrain-materials.md) explains corners, cliffs,
textures and projections. [Updates and caches](gpu-board-performance.md) explains
the worker pipeline, detail selection, opaque batches and resource lifetime.

## Roads and structures

`BoardRoad` computes the road footprint and clearance mask.
`BoardRoadGrade` owns physical approach heights and `BoardRoadCut` lays them into
the terrain. `GpuRoads` carries road material masks
on the supporting triangles. Change the footprint, grade or appearance at its
respective owner so road drawing and ground contact continue to agree.

`BoardBridge` follows connected spans and classifies their deck material.
`BoardNaturalBridge` constructs natural arches; `BoardBridgeFooting` fits
manufactured ends onto banks. `BoardTunnel` supplies cosmetic cliff portals.
[Roads](gpu-roads.md), [slopes and tunnels](gpu-road-slopes-tunnels.md) and
[natural bridges](gpu-natural-bridges.md) describe the connection contracts.

For buildings, `GpuAssets` finds a custom kit or legacy model selected by the
tileset. `GpuBuilding` assembles seeded floor/roof recipes to the game's height, with the connection pieces the
hex's exits pick (`ConnectedKit`);
`GpuBuildingInterior` derives slabs and supports from the occupied footprint.
Use [modular buildings](gpu-modular-buildings.md) for kit assembly and
[asset proportions](board-asset-proportions.md) for legacy models and bridge assets.

A Solaris elevator's hex (`BoardFeatures.shaft`, from its `SOLARIS_ELEVATOR` terrain) is a flat, unsculpted lift
platform: its relief keeps lattice edges and its walls are straight (`BoardSurface.straightWalls`, as a Classic
column's), which picking, support and the camera use. Enhanced draws neither its top nor its walls: the platform
(`BoardElevator`) has a diamond-plate top inside a hazard-striped band 1.2 m wide, whose stripes run along each rim
edge, and its level painted on as a decal (`decals/elevator`: an up-and-down arrow and the number). The paint is
painted plate: drawn with the plate's UVs, it takes the plate's normals, occlusion and relief in a matte, non-metal
finish, so the lugs stand through it (a decal turned on the platform paints flat). Raised above its
lowest neighbour, the platform stands open on its lift (the deck's hazard-striped rim and girders on a
massive scissor table, two planes of safety-yellow box-girder arms on chrome axles and cast hubs, tied by torsion
tubes, rolling on rails and pushed open by a hydraulic cylinder outside each plane in every stage; its paint, steel and
iron grime over where wear gathers, round its pins and joints and low in the pit, `BoardElevator.Lift#wear`), standing
in a machinery pit sunk below the lowest ground beside it: diamond plate behind a hazard-striped curb, each wall
lined up to its side's ground, cut only to the rim beside another raised lift and closed up to the deck where no lower
ground lies beside it. All of it is in the shared `structures/common` materials: `steel`, `iron`, `chrome`,
`safety-yellow`, `hazard-stripes` and `diamond-plate`, each with its whole finish. Nothing
scatters on it. Classic keeps its column art.

When the server moves elevators (`ElevatorProcessor`, end phase), `GpuBoardSource` queues one
`BoardScene.TileTransition`, the timeline's event for hexes that change in motion, just before the checkpoint that holds
their new levels; the units on them get no forced move. `UnitPlayback` plays it as one action (1.5 s of animation
time) and applies its checkpoint as it starts. It waits until the terrain shows each moving hex at the lower of its two
heights (`GpuTerrain#ready`), so the ground beside it keeps its walls down to there. `GpuTerrain#present` takes the
hex's top and lift out of its chunk's draws. It draws the top raised to the platform's eased height, on a lift built for
that height, which casts its shadow as a unit does, and the units on it ride along (`liftOffset`). Once the action
ends, the platforms hold their end height until the after tiles are installed. Meanwhile, picking, ground support, hex
text and overlays follow the installed tiles. Classic columns snap.

Authored scenery can dig into its hex (`BoardGroundCut`): a model's `cut` group is the ground it digs out, triangles
at the floors' depth. In Enhanced the hex's top (not a road over it) gives way over them and they become the ground there
(`BoardSurface.Finish.DUG`): picking, ground support and the camera use them, and the terrain never draws them, as the
model draws its own floor and walls. Grass, plants and hex overlays, which keep to the top, stay out. Classic columns
keep their flat top. A model's `stands` group marks where troops resting in its hex stand (`InfantryMotion`): they walk
to the stands, face out from them and step down onto the dug ground there (`UnitGroundContact`), a meeple by its middle
as a vehicle does. Its `vehicle-N` stands are where a platoon's transports park. Infantry hitting the deck lies prone
(`GpuBoardSource.proneCause`) only while that is its cover: dug in or in a fortified hex it has the +2 of that cover
instead, never both (TO:AR p.106), and mans its holes or trench.

Two earthworks dig in (built by `tools/board-models/military`). A dug-in platoon (TO:AR p.106) digs a fighting hole for
each of its figures round its hex's centre (`scenery/military/trench`, `BoardFeatures.withTrench`): 1.4 m deep, roomy
enough for battle armour, their earth thrown forward, no timber, and far enough apart that a battle armour squad's
meeples stand clear of each other. The model holds six holes, one per formation slot, each a numbered part (`part-N`
nodes and cut pieces, `stand-N`, `RigidGlb.part`); a feature shows the parts its `parts` mask names
(`BoardGroundCut.showParts`, `BoardGroundCut.shown`). The hex keeps the holes of the slots its figures stood in when they
dug in (`GpuBoardSource.holes`, `InfantryVisual.slots`) for as long as they stay dug in, so a casualty leaves its hole
empty; each figure stands in its own slot's hole. A digging platoon shows its figures' holes as a ghost. A fortified hex
(its layout's `scenery/military/fortification`) is a strongpoint filling the hex: a vehicle pit 1.8 m deep at the centre,
wide enough for a tank at any heading, its ramp out to one corner, and round it a fire trench 2.2 m deep with five
revetted fire bays, their stands numbered for the formation slots nearest them, and duckboards on its trodden floor. The
trench is open where the ramp leaves and behind the pit, at the hide: level ground for a tank that could go hull down
there (TO:AR p.19) but has not. Such a tank (`UnitAnimator.waiting`) waits toward the hide, keeping its facing, as far
out as its hull stays inside the hex, on the bearing within 40 degrees of the hide's that takes it furthest, hanging over
the pit where no bearing takes it clear (`BoardGroundCut.hide`). A hull-down
tank and a mechanized platoon's transports, side by side at the pit's vehicle stands, settle onto its floor by their
hull's middle; so does whatever else stands in the hex's middle and cannot use the strongpoint, as a Mek or a Large
Vehicle, and meeple tokens stand in the pit as models do. A platoon dug in in a fortified hex takes to its strongpoint
and digs no holes. Pavement and roads of any kind cannot be dug (TO:AUE p.153), so a fortified hex there is built up
instead, as is one whose author chose sandbags (`HexAppearance.Fortification`, `scenery/military/sandbags`,
`BoardFeatures.onGround`): sandbag walls 1.83 m high round the hex,
open at the same corner and hide, with a firing notch (a top bag left out) at each of five fire positions and a
revetment round the centre for vehicles. It digs nothing; the same stands put its troops behind the notches, a
hull-down tank and the transports in the revetment and a waiting tank toward the hide, all on the pavement. Near, a
wall is one shell of whole bags, nothing modelled behind its faces, each bag a photographed one projected onto it (the
`fortified-sandbag` material, made from two photographic studies by `sandbag_atlas.py`): the bags whose outline shows
are modelled pillows (the top course, the ends, a notch's), the rest flat faces under the material's strong relief;
bags turn round its corners, and at its ends one bag sags over the shorter course below it before the courses above
rake back half a bag each; about 12,000 triangles. Further out it is a prism with the photographs on its faces, its
ends sloped alike.

Each earthwork keeps within a hexagon short of its hex's edges. On a level hex it draws at full size; beside a step,
cliff or shore its top stops short of, it shrinks across (not in depth) about its anchor until it keeps inside the top
(`BoardGroundCut.fitted`, from `BoardRelief.topInset` and the model's outline). The drawn model, its cut, its stands, its
ghost and the plants round it all use that fit. Its walls and spoil are the shared `dirt-ground`, the strongpoint's
trench floor `biome-mud`, in one colour slot, which takes the colour of the ground drawn in the hex
(`BoardGroundCut.earth`): each family by its share of the hex's cover (so sand over a grass theme and desert
transitions count), as its ground material's mean albedo stands to dirt's. Soil (grass, dirt, snow, tropical,
pavement) keeps the model's own colours; Mars digs red earth, the Moon grey, a volcano black. Its spoil's foot frays
into lobes, islands and scattered clods, and grass and plants grow up to it and through its spoil where that lies thin:
an earthwork's footprint (`BoardObstacles`) keeps them only out of its heap where it is over 20 cm thick and out of
what it digs, with no cleared band round it.

Terrain-owned rubble (`scenery/rubble/`, including cleared paths) shares that footprint fit. Its GLB's finest-level
outline is read even without earthwork groups; it never digs ground. Only receding edges constrain the pile, leaving
15 cm of clearance inside the rim and embedding a fitted pile's base 10 cm into the ground. Horizontal scale changes
uniformly while vertical scale stays authored. Level ground, ice and bridge decks keep the original placement. The
same fitted feature drives the visible mesh, shadows and vegetation clearance; game terrain and movement rules do
not change. Narrow tops can make the pile's horizontal proportions more compact.
`BoardRubbleFitTest` checks all ten assets and their model detail levels against the actual top triangles, flat-ground
placement, all six single-edge cliffs and ice/deck support. `GpuRubbleFitSmokeTest` captures level ground, one exposed
edge and an isolated plateau from overhead, isometric and low angles.

## Ground appearance and vegetation

`BoardSurfaceBlend` supplies a continuous material-contact field.
`GpuSurfaceBlend` refines shading attributes without changing the supporting
planes. The same family weights must reach color, normal, height and occlusion;
a color-only fade will not hide mismatched relief. See
[terrain contacts](gpu-terrain-contacts.md).

`BoardBiome` shares world-space rows, wet patches and growth patterns between
ground shading and plant placement. `GpuBiomeSurface` uploads per-hex attributes;
`GpuBiomeVegetation` prepares crops/reeds; `GpuGroundCover` prepares grass roots;
`GpuTreeInstances` draws repeated trees. Stable seeds and support identities keep
plants from moving when snapshots or detail levels change.
[Fields and wetlands](gpu-fields-marsh.md) and [grass](gpu-grass-rendering.md)
explain these paths. Small props use `BoardScatter`/`GpuScatter`; rough terrain
uses `BoardRough` where its geometry also matters to support and picking.

## Liquids and atmosphere

`BoardSurface` constructs liquid boundaries and beds. `BoardRiver` and
`BoardFlow` derive stream paths and visual currents.
`GpuWaterShader`, `GpuIceShader` and `GpuMagmaShader` bind the materials,
while `GpuOcean` supplies shared wave fields. Follow [water](gpu-water.md),
[ice](gpu-ice.md) or [magma](gpu-magma.md) when changing those surfaces.

`BoardAtmosphere` maps scenario conditions to visual lighting/weather.
`GpuAtmosphere` captures and composes scene color/depth, fog, exposure and shafts.
`GpuClouds`, `GpuCloudShadow` and `GpuWeatherParticles` provide cloud
attenuation and precipitation. `GpuTerrainEffects` renders captured terrain fire
and smoke before atmosphere composition. Their feature guides explain pass order
and ownership; [shaders](gpu-shaders.md) maps each GLSL family to its Java assembler.

Runtime board assets and their formats are documented with the assets in
[mm-data/data/models/board](../../mm-data/data/models/board/README.md).
