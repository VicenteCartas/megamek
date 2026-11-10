# Terrain geometry and materials

[Docs index](README.md) Ãƒâ€šÃ‚Â· [Class responsibilities](gpu-code-map.md)

The native board separates game terrain, finished geometry and shading. The game
provides levels/types/exits; CPU builders derive a shared surface; shaders add
material detail. Geometry changes affect picking and support, while texture relief
changes only appearance.

## What does what

| Code | Responsibility |
| --- | --- |
| `BoardGeometry` | Board/world conversion, common surface queries, bounds and picking integration. |
| `BoardSurface` | Finished topography: plateau, road approaches, water bank/bed and surface-role data. |
| `BoardRelief` | Canonical corners, stepped profiles, cliff rows, transition bands, shore shapes and rock placement. |
| `BoardConcrete` | Constructed slab outlines, retaining walls and joins. |
| `BoardRocks / BoardShape` | Reusable rock CPU meshes; placement stays with the feature/relief builders. |
| `BoardRough` | Authored rough variants whose drawing, picking and support use the same placement. |
| `GpuTerrain` | Worker preparation, installed chunks, resource ownership and rendering. |
| `TerrainLod` | Projected-size render sampling; board dimensions do not select quality. |
| `GpuAssets / terrain-sculpt.frag` | Material resources and the main geological surface program. |

## Canonical geometry

Anything adjacent meshes share must be derived from the same board data and world
position. Corners use an integer lattice; all three adjoining hexes compute the same
corner. The higher hex owns a cliff and its foot coincides with the lower boundary.
Rows at fixed absolute heights let cliffs of different spans meet without cracks.

`BoardRelief` combines an inset face, broad buttresses, bedding/joints, a caprock rim
and a talus foot. Its corner fillets and transition bands must preserve a usable top
and shared edge samples. Decorative displacement remains bounded; picking bounds must
include the permitted horizontal overhang and vertical headroom.

Tall natural transitions use elongated joints along a shared oblique strike.
The same joints connect vertical ribs, breaks in the caprock and deposits at the
foot. Smaller slopes keep their soil mantle and quieter jointing. Fallen-block
groups sample recesses in the actual shaped face; they reuse
the existing candidate count, assets and clearance checks. The sampling grid stays
fixed across dressing LODs. No additional terrain subdivisions are introduced.
The transition band and rock relief share the talus-height field; fallen blocks
use it too, so the deposit does not repeat a uniform horizontal band.

Grass/dirt and snow use soil/cover mantles on small steps, exposing rock on taller
faces. Desert, Mars and rock retain steeper geological profiles. Concrete uses the same
slope/cliff selection: unmarked one- and two-level steps slope, while authored cliff
exits and taller drops form cliffs. Its slopes are planar with sharp edges, and its
vertical cliffs stay concrete for their full height. `BoardConcrete` owns the fitted
corners shared by the top, neighbouring ground and shoreline. Transition bands use
those fitted edge normals, keeping a straight rim and a constant slope width. Each
cast wall triangle retains its own face normal, including non-planar junctions.
Cast wall rows join the canonical corners directly, so the face cannot bow away
from its straight slab rim at a cliff-to-slope junction. At dry board edges the
slope and the outer cut use the same corner profile: the slope stays in the upper
hex with its foot on the lattice corner, and the cut follows the upper hex's edge,
so the board keeps its hex outline at every height. Fitted concrete corners on the
board edge keep their lattice place as well. Straight-sided
panels split at adjoining terrain heights and use flat facets across their diagonals,
preserving shared boundary samples without narrow triangle fans at the slope ends.
Concrete and outer cut walls retain one texture orientation across their facets.
Their actual normals light the geometry without rotating or switching the texture
projection at a triangle boundary.
Concrete samples its cast map in a frame running along that rim and down in
world height. Slab joints and grain therefore follow diagonal faces instead of
shearing between world XZ/YZ projections. The normal and cavity maps use the same
coordinates, with normal relief expressed in the actual face's tangent frame.
Courses keep their absolute level heights. Each map takes one sample, with no added
geometry, textures or draw passes.
The outer cut also keeps its face normals separate from the road or ground above;
sharing the upward rim normals produces diagonal shading bands on the cut wall.

Dry grassy two-level transitions have two earthen faces separated by a shallow shoulder
at the intermediate level. `BoardRelief.band` shapes it within the existing transition
width and preserves the rim, foot and game elevations. Canonical corner evaluation,
surface support and grass planting see the same shoulder. Its strength varies in
world space, while the height profile stays monotone and the faces stay under about 75°, nearer how soil stands (at
up to 85° they cast the rim's shadow far down the bank); the shoulder still grows grass at every detail level.
Single-level slopes, water
contacts and explicitly marked cliff faces retain their own profiles. The grass material
uses the existing rim/foot heights and world-space variation to add an irregular turf
lip and patches on the shoulder, including overhead views with grass blades hidden,
and darkens the soil under the lip into a root mat, half a metre to a metre of dark topsoil
before the paler subsoil of the face. From 100 projected pixels per hex the grass
engine's rim blades hang over both contours; see [grass rendering](gpu-grass-rendering.md).
A meadow dries towards any convex rim only in patches (`groundToneFor`), never as a
yellow band tracing every edge.

Poured tops use six triangles and a rectangular cast wall panel uses two. A road cut,
water contact or junction can require additional boundary vertices. Concrete keeps
its own material down to the foot, without a rock foundation or fallen rubble.
Natural terrain must not spread its material across concrete or subdivide it for that blend.
Concrete shores continue vertically to the water bed using the same fitted boundary
as the land; no independent shoreline shortcut or sloping beach crosses that wall.
Fitting defaults to Everywhere. A material boundary on one ground plane can move
beside buildings without changing their support height. Structures on the concrete,
water contacts, height changes and road ramps keep the foundation constraints they
need; merely being near a building does not pin the surrounding paving.
The water hex owns the submerged panel; the land column stops at its rim so those
faces cannot overlap and flicker.
Both GPU views omit the legacy `quay_fluff` sprites (`fluff:100`, used by
`Templates/SeaPort.board`). Projecting those painted walls onto the seabed leaves pale
wedges outside the actual quay. Tactical columns and water use the same fitted corners.

Natural tops stay exactly at their board level. An ordinary flat hex, including map
edges, uses six triangles at every detail level. Slopes use six columns, cliffs twelve,
with six absolute-height rows per level. Concave road cuts retain twelve slope boundary
samples and twenty-four cliff samples on both sides of the join. Their planar tops keep
those boundary vertices. Rounded cliff tops also refine interior shading samples,
including tops whose shape permits a centre fan. Their boundary normals and
rim/foot distances must fade locally instead of interpolating across a whole
plateau. Both fan and concave triangulations use the same adaptive edge sampling;
only near-degenerate slivers at the floating-point precision limit are protected
from subdivision. No outline vertex or playing height moves.
Mixed-material tops subdivide only where sampled blend weights differ from linear
interpolation by more than 4%, down to the existing detail-dependent spacing limit.
Ordinary homogeneous tops without rounded cliff rims remain six triangles in
the submitted mesh.
Natural material contacts share a world-space cover field with grass placement.
Their nominal half-width is seven metres, with bounded irregular edges. The shader
combines that broad gradient with texture-height contacts, softening transitions
between themes while keeping pure tile centres and visible material detail.
Material detail belongs in the fragment shader: world-space wear exposes soil and
rock on low-poly slopes without relying on small changes in mesh normals.
The desert hardpan sources have their row/column tone bias removed, so the six-metre
repeat does not line up into bands. Other sources keep intentional bedding, ripples
and directional features.
Additional ambient wear follows those materials' existing height, cavity and normal
maps, with continuous ground fields controlling its strength. Each ground family
has its own recess/crest colour response and balance between grain-height wear
and chipped mineral edges. It does not place independent slash shapes on the map.
This is ordinary terrain material construction in `terrain-materials.glsl`:
cover erosion participates in layer blending, then pigment wear applies before
lighting. Both always run at full strength.
It includes the broad pale exposed-rock patches on desert hardpan, the related
mineral exposure in other families, rim abrasion and worn organic cover. The rock
of cliff walls outside the desert, lunar, Martian and fungal families also weathers
by its height on the wall: a bleached rim, upright water streaks on its steep faces
(about .6 by 6 m, in the rock maps' side projections, mipmapped) running down from
the top, and a
darker lower part towards the foot. Earth banks keep their own colour. Slope
geology, the source textures and loose sand's tile-cover behaviour remain intact.
The strength is a shader uniform, so it needs no terrain rebuild. It does not depend
on combat history.
Desert's maximum recess darkening is 20% before field/detail modulation. Snow's
base maps have shallow irregular crust and fine grains; its cool compaction response
reaches 16% in the red channel before modulation. Pigment changes multiply the source
colour to retain its grain. Grass and tropical wear also reveal existing soil
through the cover, while worn fungal colonies reveal existing mauve tissue.
These are art-directed surface responses, not erosion simulation or measured scans.

| Ground family | Recess treatment | Raised-surface treatment |
| --- | --- | --- |
| Grass | Worn turf exposes soil | Warm, dry grass tips |
| Dirt | More pronounced compacted soil pockets | Pale, crumbling grains |
| Sand tile cover | No ambient wear | No ambient wear; exposed substrate retains its own response |
| Rock | More pronounced mineral fissures | Clearer abraded edges |
| Concrete ground | Restrained grime in the existing pores | Original aggregate; no artificial bright crest outlines |
| Snow | Irregular grey-blue compaction in granular crust | Restrained cool grain highlights; no repeated waves |
| Lunar | Neutral regolith pockets | Pitted mineral rims; no wind/water treatment |
| Fungus | Worn colonies reveal mauve tissue | Raised skin retains the source's mauve/cyan hues |
| Desert | Soft sandstone varnish in recesses | Restrained abrasion on rock plates |
| Mars | Rust-coloured dust pockets | Lighter mineral ridges |
| Volcano | Ash-darkened cold basalt pores | Slightly cooler exposed edges |
| Tropical | Worn organic cover exposes soil | Warm dry litter highlights |

These responses use the **final** material blend weights, including authored
mixtures and windows through sand onto the substrate. A material boundary does
not choose weathering from a hex's nominal family. Sand is a tile cover over the
theme, not a theme; its existing capture preserves the underlying material and
biome. It contributes zero ambient pigment. Only exposed substrate can weather.
Concrete ground reuses its sampled height instead of a constant midpoint and
uses the existing translated sampling helper to reduce fixed-pattern repetition;
colour and normals share that projection and the previous two-sample budget.
Its ambient grime uses cavity detail rather than exaggerating the shallow float
finish's height into a pattern of bright, apparently raised islands.
Relief and world fields are evaluated once. Blending the pigment responses adds
arithmetic; organic wear can also sample existing soil/tissue maps where it
reveals those materials. No textures, geometry or draw calls are added. Wear and
worn organic cover show at every zoom, from the filtered height, cavity and normal.
Intermediate intensities can change which existing maps contribute to a blend;
unchanged draw counts alone do not establish identical GPU time.
Existing field, mud, marsh, quicksand and tundra overlays retain their own maps;
they inherit the ground-family response, with damp ground reducing dry wear and
wetland pools suppressing it. Submerged beds and active magma/cooling-crust
materials omit the pigment effect. Pigment wear applies to sculpted ground tops and
the level toe and lip of natural banks, fading as they steepen, so a bank meets its
foot in the same worn tone; cover erosion shares the natural top/slope material mix so the rim contact stays
continuous. The control does not affect separately drawn roads, buildings,
vegetation or units.

<a id="combat-scars"></a>

`GpuGroundDamage.COMBAT_SCARS` (also default `true`) controls runtime combat marks.
Their shared terrain/grass mask sampling lives in `terrain-damage-mask.glsl`,
with the ground material response in `terrain-damage.glsl`. Combat marks use
the attack effects' captured endpoints and contact times, then sparse,
mipmapped image tiles sampled in the existing sculpted-ground pass. They change
colour, roughness and lighting normals, not geometry or game rules. Their stronger
scorch/chip response is applied after natural weathering; ambient style does not
alter scar strength or the amount of attack history. Scar history is cosmetic
render-thread state, not game state: a new board or a change of its dimensions or
geometry scale clears it, while camera changes, chunk rebuilds and detail changes
keep it.

Scar sizes use `ResolvedAttack.Shot.damagePerHit`, captured from the weapon and
its ammunition before playback: nominal beam/round damage, damage per individual
missile, or artillery shell damage. This excludes hit-dependent and target-specific
damage modifiers; variable-range weapons use their nominal close-range value.
Special munition adjustments applied by attack handlers are not recalculated here.
Missile rack size controls projectile count independently, including one projectile
for large missiles and missile artillery. For scar sizing, damage has a minimum of
1, including zero-damage weapons. The base visible span is `3 * sqrt(damage)`
metres for gouge length and burn width.
Gouges use a consistent 5:1 footprint; their perpendicular variants have a
diameter of twice gouge width. Doubling damage doubles footprint area
for these shapes. Missile/artillery blast and plasma splash diameter instead follows
`2.7 * damage^0.4634086426` metres: one smooth power curve through 2.7 metres
at 1 damage and 12 metres at 25 damage. SRMs leave 3.7-metre marks;
Thunderbolt 5/10/15/20 leave 5.7/7.8/9.5/10.8-metre marks.
Artillery uses the blast shape even with a bullet
emitter. Flame ground impacts leave singed patches at a fixed 65% opacity;
damage changes their size only. They have no glass or excavated relief, and the
game still owns terrain fire. Rack size never enlarges an individual scar.

Laser gouges have a smoother vitrified core. PPCs use a more fractured fused
texture with the same dimensions at equal damage. Scar style
uses the captured `F_PPC` flag even when a model supplies a generic emitter.
Plasma (`F_PLASMA` or `F_PLASMA_MFUK`) leaves an irregular melted splash with a
charred fringe, minimal ejecta and shallower relief. Its diameter uses the same
damage curve as missile and artillery blasts, with a 7.8-metre mark at 10 damage.
Its identity is captured in the resolved shot and retained by playback; this does
not change the projectile animation. The Clan Plasma Cannon's variable damage
falls back to its zero-damage ammo, so its scar uses the one-damage minimum rather
than inventing damage from target-dependent heat or damage rolls.
Ballistic furrows have coarse disturbed-soil patches and remain matte.
The shared length scale gives an AC/20 a 13.4-metre gouge, a PPC 9.5 metres,
and an eight-damage Large Laser 8.5 metres. Shape artwork does not supply hidden
weapon-specific size multipliers.
Lasers, PPCs and ballistics use their authored round variants when the shot lies
within 45 degrees of the receiving triangle's normal, including the boundary.
The triangle comes from the existing contact check. A squared dot-product test
selects the shape once per impact, without normalization, trigonometry or a new
ray cast. Shallower shots keep the gouge. Perpendicular blast diameter is twice
gouge width (40% of gouge length): AC/20 is 5.4 metres across, PPC 3.8 metres,
and Large Laser 3.4 metres. Both forms retain the same material response.
Missed beams and all projectile families also stop at their first cliff or opaque
structure contact. Straight projectiles check one bounded segment; missiles and
arcing shots check 32 segments of their existing flight curve once at emission.
The captured contact supplies the position, direction, material and arrival time
for the flight, impact effect and scar. Authorized unit hits and game damage
results are unchanged.
Scars retain the receiving terrain's grain and normal detail. Authored luminance
modulates that material rather than replacing it with an opaque gray picture;
grass exposes its existing soil colour map at the soil's own metre scale. This
adds one texture read for damaged grass fragments, using the existing terrain
sampler. Round stamps have a translucent irregular fringe and opaque center.
A packed RGBA mask holds coverage, premultiplied authored luminance,
vitrification and shallow relief. Relief changes cavity shading only; impacts never
excavate terrain geometry.
The editable RGBA source PNGs live in
`mm-data/data/models/board/textures/terrain/damage/`: laser, PPC and ballistic gouges and
round variants, missile/artillery blasts, burns and plasma splashes. Their alpha controls coverage and grayscale
detail preserves dark char/melt and light debris. Sources are decoded during board
setup, converted into the mask's material channels, and retained as CPU mip chains
capped at 256 pixels on the longest side. Gouge PNGs are 256 by 64 with 240-by-48
artwork; round PNGs are 256-square with 240-square artwork. The renderer trims
authoring margins using alpha >= 16/255, so invisible padding/residue cannot make
lasers smaller than equal-damage PPCs. The artwork uses broad dark forms and a few
coarse details designed for its small projected size, without bright raised rims
or finely rendered rubble.
Stamping selects a filtered level for the projected footprint and bilinearly samples
the art instead of generating a procedural silhouette. Ground stamping adds no GPU sampler or
draw pass. Authored light/dark detail supplies the relief appearance; the shader
does not differentiate low-resolution mask cells into artificial ridges.
Missing artwork logs a warning and skips that style. All decoded images
are owned and disposed by the ground-damage renderer. The art directory's README
documents editing and reload.
Vertical scars use cached patches clipped from the installed receiving triangles,
including deformed cliffs and opaque model walls. Each patch retains the original
material, UVs and vertex attributes, and adds its image-layer coordinates. A small
surface offset prevents depth fighting. The receiver's own program draws its patches
(a per-draw uniform, `u_surfaceScar`), with the shared scar material response; empty
mask fragments end with alpha 0 under the patch's blending, and patches write no depth.
Patches share the existing page batching, including in perspective view, and are
drawn after opaque receivers. Further hits on the same area repaint its image;
they do not add meshes or draw calls. Detail replacement rebuilds patches from
the newly installed geometry without repainting the images. Trees, scatter and
transparent surfaces are excluded. At most two queued wall impacts are projected
per frame; settled frames do no projection or image upload.
The native wall review exercises PPC gouges and round impacts, AC/20, Long Tom,
plasma and flames on a sculpted cliff. It also checks a building wall in both
camera projections, fading the building around a unit and back, a rebuilt cliff, and
40 repeated hits with unchanged patch triangle and draw-call counts. Stamping
reads and blends the existing CPU pixel buffers directly; no extra image copy is
kept. First impacts in new areas still pay for projection, mesh and image uploads
and possible array growth; the per-frame queue bound is not a millisecond budget.

Each image tile covers eight metres at 30 texels per metre: a 240-square interior
with eight border pixels on every side, stored in a 256-square texture-array layer.
Adjacent tiles rasterize their overlapping borders at identical world positions.
The shared shader uses explicit gradients before selecting a layer, avoiding mip
selection discontinuities at tile edges. A compact directory uses one RGBA texel
per possible tile, stored in the same array; empty entries return no damage.
Image layers are allocated only around impacts, so a 50-by-700 board retains the
same local detail as a small board. Terrain fragments and grass vertices each
read a directory entry and, when present, its image layer using one sampler.

Only changed image tiles and their mipmaps are uploaded during normal updates.
A reusable 85 KiB scratch chain averages premultiplied texels; no whole-array mip
rebuild runs on a new hit. Array capacity grows geometrically, re-uploading existing
marks when it grows. Settled frames and camera changes do no raster or upload work.
Each allocated image tile needs 256 KiB of CPU storage and roughly
341 KiB on the GPU including mipmaps; spare array capacity also consumes GPU memory.
The GPU's texture-array layer limit bounds history: at that limit, existing marks
remain, but new areas cannot receive image tiles. Storage and update cost therefore
depend on damaged area, not only board dimensions. These masks are combat-only;
terrain weathering and authored board decorations use their existing resources.

`GpuGroundDamageTilesSmokeTest` paints the same AC/20 scar on a 50-by-50 board and
16 km along a 50-by-700 board. It checks unchanged local scale, a continuous
four-tile join, the distant directory lookup and retention of existing layers after
array growth.

Scar coverage removes grass blades by their roots in the grass vertex shader and
replaces turf colour with exposed soil. This shares the mask lookup above,
without rebuilding vegetation meshes or allocating another
texture. Coverage is complete in the core and thins grass across the ejecta fringe,
whose material and normal response blend through exposed soil into the existing turf.
Ejecta fades outwards from the core without a solid outer ring; surviving edge blades
are shorter and browned. Turning
off combat scars restores both turf and blades. Trees, bushes and crop meshes are
unaffected. Native regression checks cover clearing, surviving surrounding grass,
restoration and unchanged receiving geometry; this change has no isolated timing
measurement yet.

Ground vertices shared with a sculpted cliff foot use zero foot distance, so debris
coverage continues across that contact despite the displaced outline.
Plateau vertices at the sculpted rim likewise use zero rim distance. Measuring those
vertices against the approximate lattice segment instead could move the cover contact
several metres away. Sand and snow expose irregular rock shoulders across that
shared crest through the existing material roles; open flats retain their cover.
Corner relief fades over the tangent space left by both transition bands and rounded
corners. Using the original edge length let adjacent columns reverse into a hanging
strip in Mines 1; the corrected fade keeps the shared endpoints and triangle count.

Natural hex transitions reserve room on each side of a step for the slope or cliff
and its foot. Roads, buildings and special artwork have additional boundary constraints.
An explicit `cliff_top` exit on the higher hex selects the cliff profile even for a
one- or two-level drop. Natural cliffs expose rock; constructed cliffs remain concrete.
Only the marked directions change;
the real rim and foot elevations stay fixed. The captured exit mask participates
in terrain, support and picking cache invalidation, including neighbouring shores.
Hex padding uses the same step-room machinery; it does not enlarge the lattice.
When altering these controls, follow `BoardRelief.stepRoom`, corner/edge sampling,
surface construction and bounds together.

## Authored scorched ground

The editor's **Scorched ground** card stores one appearance on each hex: Singed
(.15), Charred (.35), Ash (.62), or Glass (1). It changes neither terrain rules nor
height. The sparse damage directory stores authored severity in A and nuclear
severity in B. Authored severities feather across hex boundaries over roughly
16 metres, written into the existing eight-metre directory cells when an edit
changes the field. Equal neighbouring severities stay constant. Shading uses the
stronger of authored and nuclear severity. Disabling combat scars does not hide authored scorch. Erasing
the appearance clears A without erasing combat history.

The editable source is `mm-data/tools/materials/terrain/surfaces/ground-scorch/ground-scorch.ptex`.
Its procedural deposits, cinders, ash and fused fissures export as a Godot 4 Standard
material ([board materials](gpu-board-materials.md)) with stock Material Maker, or through
`tools/materials/export.py terrain/surfaces/ground-scorch/ground-scorch.ptex`.
The authoring-only `preview_s` control previews severity; exports always contain
the fixed material basis. Its 512-square maps cover a 64-metre square, wider
than a hex. Deposits span metres while the re-authored grain remains sub-metre.
The GPU receives them as two maps:

- Colour/height: sRGB albedo in RGB, linear height in A.
- Normal: linear normal XY in RG, **roughness in B**, AO in A (`GpuMaterialMaps.NORMAL_ROUGHNESS`).
  Authored scorch and mud store roughness instead of normal Z; their shaders reconstruct Z.

`scorched()` in `terrain-damage-mask.glsl` evaluates one shared linear palette and
partition of soil, cinder, ash and glass. Sculpted tops sample the two existing
material arrays only where severity is positive. Mud and scorch share translated
top coordinates selected by the existing slow world-region field, preserving
feature size across hexes. Colour/height and packed surface data use identical
offsets and weights; normals are reconstructed before blending and roughness
blends as squared roughness. Steep faces use the same averaged
response without projecting a stretched texture. Coverage blends material before
rain, grid lines and elevation grading; roughness blends as squared roughness.
The ordinary ground, road and plant paths use the shared response without those
material samples. Imported models retain their existing materials. Enhanced shows
scorch; Classic does not borrow this material.

Painted glass is always cold. Only nuclear severity heats the low-height fissures,
using the existing cooling clock; distant mip levels suppress these small details.
A later nuke still restarts that shared clock. Any authored or nuclear scorch
clears grass blades, a bank's rim blades included. Both also remove
decorative grass and shrub scatter through the existing chunk rebuild. Affected
presentation tiles also set the existing barren bit so independently generated
field bushes cannot replace removed scatter; authoritative terrain is unchanged. Baked
scatter is removed across each touched hex; blades follow the precise
mask. Rocks remain. Woods and jungle follow authoritative terrain conversion;
biome crops and reeds retain their existing behavior.
There is one appended material layer and one uniform, with no new sampler or
shader variant. Zero severity leaves the original material unchanged. Scorched
boards add four directory reads per receiving fragment, then four material samples
on affected tops; performance is checked separately on Iris Xe.

## Shores, ramps and support

Water geometry also participates in canonical corners and cliffs. `BoardSurface`
owns the bank/bed and water surface; `BoardRelief.bank` connects the shoreline to the
shared terrain boundary. Water-to-water drops keep the seam needed by the falling
surface. A land-to-bed difference includes the water depth when deciding whether the
bank behaves like a small slope or a taller cliff.

Open-water banks and beds use one sampled material field across every natural
sector. A water hex's authored ground mixture must not consume its bank faces in
the ordinary land-material pass. Adjacent water hexes at the same surface level
blend their bank/bed fields at their shared edges, avoiding triangular underwater
wedges. Bank coverage references the water column's surface elevation, so increasing
the water depth cannot discard the neighboring land cover. Higher banks and cliffs
continue their land material below the waterline. Protected road, concrete
and frozen banks retain their existing boundaries; geometry and picking are unchanged.

Road approaches change the finished surface, not just its texture: reserved road ground replaces the
relief beneath it, and the rest of a roadside slope keeps its native relief. See [roads](gpu-roads.md) and
[water](gpu-water.md) for their connection rules.

Trees, rocks, vegetation and units must sample the installed finished surface.
Dressing such as a tree pit can be drawn/picked with the ground without raising the
support height. Field stones, shrubs and tree-pit kerbs seat against TOP faces, so other
dressing cannot raise them. Small field stones use eight triangles and
[small shrubs](../../mm-data/data/models/board/README.md#rock-and-scatter-meshes) use open branches and folded leaves;
both appear only at full detail. Keep support roles distinct when adding a
decorative layer.

Cosmetic scatter clears the selected authored footprints of buildings, tanks and solid
props on its own and adjacent hexes, including the scatter's full radius. Projected
triangles retain courtyards and gaps; offset props use conservative vertical bounds
without building a second terrain surface. Reloading assets clears the CPU footprint
cache. Positive-depth water and volcanic liquids reject decorative scatter before
loading its kits. Depth-zero shallows permit only visible placements above the water;
the board's explicit Rough terrain remains separate from this cosmetic rule.

## Material roles and textures

Each natural family combines ground cover, soil/mantle, exposed rock and deposits.
`GpuTerrain.SCULPT_MATERIALS` selects each family's maps; `GpuAssets` loads and
owns the shared textures. Sculpt maps are under
`mm-data/data/models/board/textures/terrain/surfaces`. `GpuAssets.MaterialArrays` owns
two compact texture arrays: sRGB colour/height (`u_terrainLayers`) and raw normal/AO
(`u_terrainData`). Sampling helpers map the stable even-colour/odd-normal semantic
IDs into those arrays. A sculpt material's `SculptLayers` gives its family's ground,
debris, wall and mantle IDs (`u_sculptLayers`), and boundary palettes give theirs per
family. With `GpuTerrain.SUBLEVEL_FLOORS`, sunken ground takes its family's floor from
`GpuTerrain.FLOORS` (every natural family but sand and snow, which stay rippled sand and snow; concrete keeps its
pavement) in place of its ground cover:
`cliffMaterial` and `naturalMaterialFor` read `u_floorMaterials` and evaluate the material with the floor
from level -1 down, both ground and floor between -.3 and -.72 on level ground and gentle slopes (a
contour broken by 6 m and 2 m patches), where the two meet by height; banks and walls keep their own roles. The shared pair takes two texture units (see
[shaders](gpu-shaders.md#boundaries-to-preserve) for the 16-unit budget). The arrays set
their own anisotropic filtering, because libGDX's `setAnisotropicFilter` only addresses
`GL_TEXTURE_2D`.

Each material is a Godot 4 Standard material ([board materials](gpu-board-materials.md));
on the GPU its colour carries height in alpha and its tangent normal carries ambient
occlusion in alpha. `textures/materials.json` gives each material's repeat in metres at
its catalogued width; exported wider, it repeats over proportionally more ground.
The Material Maker project `mm-data/tools/materials/terrain/surfaces/<name>/<name>.ptex`
generates a material's maps from editable primitives and any required `sources/` images.
`python tools/materials/export.py`, run from the mm-data root, exports every project
with Material Maker's stock Godot 4 Standard target; an optional path relative to
`tools/materials` exports one project. Gradle `stageData` mirrors the maps
into `megamek/data`. Their height, normal and AO are artistic estimates, not measured
scans.

Concrete planting pits use `concrete-mulch` for their interiors and the existing
authored concrete for their kerbs. The mulch project exposes bark-chip placement,
palette and relief controls, takes its dark soil substrate from its own photographic source,
and exports registered colour/height and normal/AO maps. The catalogue gives its 1 m
repeat, its 1.5 cm relief shapes its normal, and its material's `roughness` of 0.92
is its scalar roughness. The shader applies no
separate brown palette or fixed kerb brightness boost. Bare stone beneath snow caps
likewise keeps its authored granite pigment; snow coverage and per-rock population
variation remain separate material effects.
LUNAR has its own `lunar-ground`, `lunar-debris` and `lunar-cliff` map pairs (neutral
regolith, angular scree and fractured bedrock) and its own geology profile,
independent of terrestrial rock. Editing either family must leave the other alone.
The lunar pairs are 512 pixels square, span 12 metres each, and carry 3.75, 24 and
12 centimetres of estimated shading relief.
Lunar ground also carries impact craters placed in world cells, never on the maps' 12 m repeat
(`lunarCraters` in terrain-sculpt.frag): three scales, from about half a metre to eight metres across, each a bowl
whose raised rim rounds over at its crest. No crater is a circle: its outline has broad lumps, a faint polygonal lean
and a ragged edge, and its rim stands higher along some arcs than others. Most are old and shallow; the younger ones
throw a bright ejecta apron, streaked radially out to about two and a half radii (streaks narrower than two pixels
average to a faint apron), and a few fresh ones are deep with a bright halo. A high sun barely shades them, so a pale
rim and a duller bowl carry them too. They are shading only (normal, cavity, albedo) on
level ground: support, movement and line of sight keep the actual surface. A crater smaller than a few pixels fades
out, so none turns into grain. A crater forms only where its bowl and apron clear the nearest drop or rise (each
estimates the ground's rim and foot distance at its own centre), so a step or its talus never cuts one. Every lunar surface is lit as moon dust (`Surface.regolith`, `regolithResponse` in
surface-shading.glsl): the sun's diffuse light follows Lommel-Seeliger, cos i / (cos i + cos e), instead of cos i, so
faces turned from the sun darken far less, with a mild brightening toward the opposition point; normalized on level
ground, so the board keeps its exposure.
World-space projections keep coordinates continuous across hexes. Two differently
oriented ground samples reduce repetition; walls use compatible vertical projections.
Bare dirt, the soil mantle beneath grass/dirt and natural stone share one
vertical sampler. They blend translated source windows selected by a continuous world field,
preserving wind/bedding direction and feature size without a regular per-hex motif.
The fungal materials use four overlapping translated windows, varying in both
horizontal and vertical directions. Enlarging a ground sample by 2.37 also divides
its normal gradient by 2.37, rather than exaggerating the same relief over a longer
distance.
Slope and height-aware blending determine how cover gives way to mantle, rock and scree.

Terrain surface materials are named `<surface>-<role>`: the ground type, then what it does there
(`ground`, `debris` at cliff feet, rims and slopes, `cliff` walls and bedrock, a soil `bank`, or mesa
`cap` plates). `temperate-*` is shared by grassland, dirt, rock, concrete, snow and tropical ground, and
`arid-*` by sand and desert. Every one but `concrete-mulch` repeats every 12 m or more, so a 30 m hex
shows few repeats; their features are enlarged to that scale, as on a diorama.

`dirt-ground` repeats at 12 m with 14 cm of estimated relief: dry compact soil with embedded and loose
stones of about 2 to 25 cm. Exposed grass banks use `temperate-bank` rather than dirt: dry crumbly loam
with small clods and stones and no direction (parallel rills combed every bank), 12 m with 12 cm.
`arid-cliff` (sandstone) repeats at 12 m with 16 cm, in broad rock planes and discontinuous fractures.
`temperate-cliff` repeats at 12 m with 24 cm: pale karst limestone in jointed blocks with water streaks
and lichen; `rock-ground` (12 m, 63 cm) is the same limestone as a weathered plateau of slabs with grass
and soil in its fissures, and `temperate-debris` is its talus of angular blocks. Level bedrock of the
families whose wall is `temperate-cliff` reads the `rock-ground` map instead (`u_flatRock`), as the
cliff's joints stand upright. `arid-debris` is varnished pebbles and red sandstone chips of about 25 to
55 cm on a warm sandy crust, its hue shifted to the desert ground's in its project, 12 m with 15 cm:
debris must read as big, distinct stones at board zoom. These, `grassland-ground`, the mulch's soil and the
arid cliff, Mars, sand, tundra ground, crust and mat, field soil and boulder
(`scatter-stone`) surfaces come from photographic studies, each study's seamless tile one whole repeat so its
plates, joints and stones keep their size at board zoom.
`desert-ground` (pale veins between pebbles), `desert-cap` (the hardpan's cracked pale patches), the volcanic
ground (its pale dusty patches kept at 55 % of their contrast, so the top view is less mottled, with the
photographic study's cinder stones of 12 cm and more laid on it, in HEAD's tint) and cap, and the
concrete pavement and walls, the lunar ground, debris and cliff, the snow and the tropical ground keep their own sources; `volcano-cliff` keeps its basalt,
slightly sharpened (unsharp mask 0.4) with 17 cm of relief (1.4 % of its 12 m) instead of 14 cm.
`sand-verge`, `grassland-ground` and `temperate-debris` have 5, 7.5 and
30 cm of relief; lunar has its own `lunar-debris`. See [terrain realism](gpu-terrain-realism.md) for the visual
references and acceptance rules.

`terrain-materials.glsl` owns that common evaluation.
`terrain-projection.glsl` owns projection helpers, and `terrain-concrete.glsl` handles
constructed slab appearance. The same role weights must affect color, normal, cavity
and roughness together. Cross-family contacts are described in
[terrain contacts](gpu-terrain-contacts.md).

For a change in silhouette or support, edit CPU geometry. For grain, wet response,
material breakup or per-level color grade, edit the relevant material helper and its
maps. Editing a normal map will not correct a geometric seam.

### Physical texture scale

`BoardRelief.metres` defines 30 metres across a hex. Sculpt UVs are world positions
divided by the repeat in the asset manifest; walls and banks blend ground and vertical
projections at the same physical scale. No UV origin or fixed repetition count is
assigned per hex. A material can span several source windows within a hex without
restarting at its edges. Window blending breaks up recognisable repetitions.

Outside volcanic programs every natural surface takes `cliffMaterial`
(`terrain-materials.glsl`): tops, walls, banks (drowned walls too) and zero-gravity
bedrock. `cliffFamily` holds each family's role rules as data: cover, mantle or arid
cap plates, foot deposit and wall rock. On level ground worn turf opens onto soil,
fungal crust forms colonies above level zero and along raised rims, and exposed stone
keeps a dust film of its cover (arid .24, dirt .18, rock and lunar .12). Only the two
strongest roles are sampled. Walls read once per side projection, plus the top
projection where the face lies back; the bed hardness `BoardRelief` stores per cliff
vertex tints their strata by about 4 %. Tops read the top projection twice: two
repeats (the second 2.37 times larger and turned) mixed by a world field, settling to
the map's average far away, or two offsets of one repeat for sand, desert and volcano
cap plates and fungus. Up close the enlarged repeat only magnified its texels: its share fades out
as the first repeat's texels pass one screen pixel, from 2.37 a pixel to one (`enlargedShare`), so a
full zoom reads the first repeat alone, as Medium does everywhere but desert. A blended cliff takes its two strongest palette slots
(`blendCliff`). A blended top (`blendCovers`) leaves out slots under 2 % coverage, its
neighbour slots take only their strongest role, and one noise read per family breaks
the contacts.

Volcanic programs keep `naturalMaterialFor` on every face. Their side projections
advance the translated source selection with projected height, so tall walls do not
repeat identical patches at the map period. `cliffMaterial` reads each wall map once,
so tall walls such as Thunder Rift's basalt repeat at the map period, broken only by
the strata tint.

| Material | Base source span in metres |
| --- | --- |
| Every ground, debris, bank, cliff and cap map not listed below | 12 |
| Cast concrete wall map | 12; panel joints have their own constructed dimensions |
| Fungal ground | 22 |
| Tundra ground dry / wet / mat / crust | 48 / 48 / 24 / 28 |
| Tree-pit mulch / mud / ground scorch | 1 / 64 / 64 |
| Wetland earth | 9; the shared earth map sampled at 45% of its 20 m source span |
| Road and bridge deck maps | 6 for the ordinary repeat of one legacy detail unit |
| Ice / solid volcanic crust / flowing lava | 24 / 12 / 24 |

Fungal material roles and their mushroom effects are described in [fungal terrain](gpu-fungus.md).

The legacy `detailMetres` helper uses a different unit: one is six physical metres.
Road and bridge deck projections agree in that convention; new geological materials
use physical metres. The ground sampler's second source window spans 2.37 times its
base repeat. Ice blends complete samples at its original and turned coordinates for
colour, surface properties and normals; its normal gradients follow the rotation,
scale and wind stretch. Solid crust bends its domain within a few repeats to reduce
the visible plate grid while retaining one material sample and its matching Jacobian.
The solid magma source uses varied broken crust, quiet cold faces and sparse narrow
fissures instead of a few repeated large slabs. Its aligned maps are exported by
the Material Maker project `mm-data/tools/materials/terrain/lava/lava-crust`.

`GpuSurfaceScaleSmokeTest` captures 14 materials at identical overhead, oblique and
close scales, including one-level slopes and three-level cliffs. These are visual
review images, not an automated assertion of realism. `GpuSurfaceProjectionSmokeTest`
compares GPU normals with independently differentiated physical height and checks
the CPU/GPU sand exposure field. The scale audit also renders road ramps, retaining
walls, wet/dry road materials and material LOD. Native sampler-budget and ice checks
pass on the tested machine; no frame-time or cross-platform performance claim follows.

## Lighting, grading and inspection

`BoardAtmosphere` supplies linear light. Lit shaders decode albedo, apply the common
sky/direct/ground-bounce model and encode display color once.
`light-model.glsl`, `surface-lighting.glsl` and `surface-shading.glsl` are shared by terrain and other lit
surfaces; introducing a terrain-only light multiplier would make those surfaces disagree.

Per-level grading belongs to the terrain material path and changes continuously down
cliffs. Final exposure and highlight treatment belong to `GpuAtmosphere`'s composite.
Rain response uses the shared surface helpers; snow retains its separate handling.

The normal/relief toggle controls material detail. `GpuBoardTuning` and
`TerrainSettings` carry geometry settings through capture/build/install so displayed
frames do not mix results from different settings.

## Material detail at distance

The ground material does not change its look with zoom: medium and far views show the
close view averaged into their pixels. Nothing fades with distance; texture filtering
(trilinear and anisotropic) does the averaging.

- **Cache.** The ground material cache (`GpuMaterialCache`) draws a top from its pages only at
  the level the view needs, once every page under it is built; until then the top draws
  directly, which looks the same. No coarser page stands in, so a zoom never shows blurred
  pages sharpening one by one.
- **Reference.** The close view at `GpuMaterialCache.CLOSE_PIXEL` (0.1 m per pixel) is the
  look every zoom averages. Detail finer than it shades as the close
  view always has: the normals' mean direction and the plain mean roughness.
- **Mips.** The sculpt data array and the magma surface maps build their own mips on the
  decode pool (`GpuAssets.mipmaps`). Up to the level the close view reads they are the plain
  means, normals renormalised. Above it they average that level's unit normals,
  unnormalised, and its roughness as GGX alpha^2, widened by the normals' spread as a von
  Mises-Fisher lobe: alpha^2 += 1 / (2 kappa), with kappa = l (3 - l^2) / (1 - l^2) and l the
  mean normal's length, as the ground material cache widens its roughness. Each
  surface's finish layer takes its normal layer's spread; mud and scorch also widen the
  roughness in their normal maps.
- **Relief.** A natural surface's normal length is its relief: how well the close view's
  normals under the pixel agree (`normalSpread`: half of one less the filtered read's
  length, an empirical factor fitted on NVIDIA to `GpuMaterialLodSmokeTest`'s seven
  materials). Mixing independent reads adds their spreads times their squared weights, so
  the close view's mixed normals agree better than any one map's. The relief takes the
  share of direct sun that facets finer than the pixel turn away (`sunOnRelief`), counted
  from the close view: it enters over the octave above `CLOSE_PIXEL`, where finer reads
  shorten by their filtering alone. The cache fill stores the close view's relief (none),
  so the cached ground's mean normal length alone carries the averaged relief. Other
  surfaces' relief is 1.
- **Skips.** Only look-neutral work is skipped: a parallax move under half a pixel reads
  nothing (magma too), and a role that cannot show through its height blend is not read.
  Procedural fine noise, crop rows, rain and pool ripples and specular antialiasing keep
  their own footprint filtering.

`GpuMaterialLodSmokeTest` renders every family, crust and lava with altered normal maps, which
must change close pixels; dry surfaces must still reflect light. These native checks
establish the rendering behaviour, not an FPS change; frame times need measurement in the
complete board view.

## Themes and the SAND gameplay treatment

Desert and Mars supply ground and geology. Desert uses compact ochre hardpan,
pale abraded sandstone shoulders and browner exposed faces. Mars has independent
rust-red hardpan and darker iron-rich bedrock maps; it does not darken the dirt theme.
Neither theme implies `Terrains.SAND`.

`BoardSurfaceBlend.capture` derives one shared SAND cover from that gameplay flag.
Its pale sand-ground material is the same in desert, Mars and grassland. The tile
keeps its theme's geology, vegetation selection and coexisting rough/swamp data.
Its palette retains a small share of the substrate. A continuous metre-based field
opens sparse patches through the sand, exposing the substrate's complete material:
turf, stone, earth or the theme's hardpan, including normals, cavity and wet response.
Grass roots use that same field and grow only where enough turf is exposed. Existing
rock scatter and authored Rough features remain in place. Sand stays the dominant
gameplay cue; these openings neither change the board flags nor subdivide the mesh.
Exposed cliffs below the surface deposit regain the theme's rock. Snow, pavement
and magma retain their established material precedence. Buildings block neighbouring
cover without discarding their own authored SAND; water keeps its bed treatment.
Authored `ground_fluff` gradients mix themes (including Desert and Mars), not SAND.

The shared sand-ground source is a photograph of wind-rippled sand: sinuous crests about 2 m apart, capped
with coarser grains, over fine sand in the troughs. Its tile is sheared by the crests' drift so they continue
across both edges. Its 512-square color/height and normal/AO pair repeats over 12 metres. Its
22-centimetre relief is an artistic estimate for normals, not mesh displacement.
The shader translates source windows instead of rotating or resizing them, retaining
the dominant wind direction and wave size. Existing material LOD filters detail at
distance. Uniform flat SAND tops still use six triangles; no extra sampler or
draw pass is introduced. Only existing mixed-material boundaries need refinement.

`BoardAridSurfaceTest` covers actual artwork capture of SAND with desert, Mars,
grass/marsh, rough and buildings, the shared grass-root field, plus the flat mesh
budget at all four LODs in grass, dirt, rock, lunar, desert and Mars themes.
`GpuAridSurfaceSmokeTest` provides matching overhead, oblique and close native
views of these themes.

## Tundra terrain

`Terrains.TUNDRA` selects a surface-only biome treatment while retaining the theme's
geology and ground mixture. Reference-authored low mats break into exposed substrate:
sage/ochre lichen on ordinary ground, lilac/cyan colonies on Fungus, and mineral
pigments on Mars and Lunar. Meadow blades stop at the shared biome
boundary, where earth tundra's own sward takes over
([grass](gpu-grass-rendering.md#tundra-sward)); existing dry-grass scatter remains available on Grass and Tropical.
Mats continue over slopes between tundra terraces using the same broad contact
stencil as marsh sediment. Isolated banks taper below their rim, and sheer faces
retain exposed geology. Shared material projection carries color and normals over bends:
two translated reads of the top projection, plus one read per side projection in
volcanic programs.

The existing biome snapshot, per-hex texture and nine-hex coverage stencil carry the
tundra mask for both cameras. Four source materials (dry and wet earth-tundra
carpets, fungal colonies and barren mineral crust) occupy four colour/height and
four normal/AO layers of the shared terrain arrays,
with no new sampler or flat-ground geometry. The shader preserves their image
pigments and internal detail rather than thresholding one gray source into flat
colored spots. Theme contacts use the existing material weights.
Concrete keeps its slabs visible between softly keyed sage lichen branches from
the earth source, without the source's soil or tussocks. Water, ice and magma
suppress it; fields, marsh, quicksand and mud keep their biome precedence. Explicit
SAND or SNOW also covers it. A Snow *theme* can show exposed tundra between snow
remnants. None of these appearance choices changes the board's terrain or rules.

Earth tundra's dry carpet, `tundra-ground`, is a fell-field from a photographic study: gravelly soil with pebbles,
pale reindeer-lichen cushions, olive moss and red bearberry, graded warmer and more saturated toward the user's
reference and lightly sharpened. It is 1024 pixels square over 48 metres (21 texels per metre), with 10 cm of relief:
its lichen, moss and gravel stretches are tens of metres across, as in the reference, so no repeat shows at play
distance. `tundra-ground-variant` is the same place's wet hollow, from a study made with the fell-field as its
reference: sedge, rust-red sphagnum, bearberry and small dark pools, also 1024 pixels over 48 metres, with 7 cm.
The two carpets keep their full size in the full-size arrays (`GpuAssets.fullSizeMaps`); the shared
sculpt arrays keep a copy at their 512 grid for the carpets' finish and means, which never widens that grid. Like
ordinary ground, each carpet is also read 2.37 times larger and turned, blended by a world field at half the weight
ordinary ground gives it, so the carpets keep their own detail foremost. The fungal mat and mineral crust stay in the shared arrays, 512 pixels over 24 and 28 metres.
Sedge fills the hollows at the foot of rises, unless a drop is near (`tundraWetness` in terrain-tundra.glsl, from the
ground's rim and foot distances); the fell-field carries its own lichen, moss and gravel everywhere else. The
transition is metres wide, so the carpets meet as tones, never as patches.

Earth tundra's straw grass tussocks are not in the carpet (they were painted out of its source): they grow as tufts
of blades with its sward ([grass](gpu-grass-rendering.md#tundra-sward)), as tall as the meadow's tallest blades or a
half more, far below the low woods, so they never read as cover; the Tactical View omits them like the grass. Fungal
mats, mineral crust and pavement lichen grow none. Material Maker projects and
their required source images remain the authoring source of truth.

`BoardTundraTest` checks native capture, theme preservation, precedence, cliff
reach, connected coverage, grass roots and the tussocks. `GpuTundraSmokeTest` renders paired
base/tundra patches for ten themes, terraced slopes and paved lichen through the actual OpenGL renderer in both
cameras, saving review images under `build/gpu-board-review/tundra`.

## Volcano theme

`volcano` selects cool ash-grey ground and blue-grey basalt, independently of the
rust-coloured Mars theme. Explicit magma still selects its own solid crust or
flowing lava; ordinary volcanic rock does not become emissive. Shared SAND keeps
its terrain identity and wave detail, with a grey mineral tint over lunar or
volcanic substrates. The tint follows the existing cover weights at contacts.

`volcano-ground` and `volcano-cliff` use the existing sculpt array, world-space
projection, material LOD and native slope/cliff builders. Their aligned 512-square
color/height and normal/AO maps repeat over 12 metres. Estimated relief is
9 and 14 cm respectively; this is shading relief, not extra mesh subdivision.
Scatter reuses the basalt map in its shared atlas. No per-hex texture or draw pass
is added.

Height and normal maps are artistic estimates, not measured photogrammetry. Basalt
uses a separately authored, registered height source; the ash ground's height is
estimated from luminance.

Theme variations belong in the shared material/cover system, while SAND, ICE,
pavement and magma keep their gameplay precedence.

`BoardAridSurfaceTest` covers volcanic/lunar SAND capture and the six-triangle flat
mesh at every LOD. Native board captures cover Thunder Rift and Crystalline Canyon;
`GpuMaterialLodSmokeTest` also exercises the volcanic normal maps.

## Ultra-sublevel pits

Presence of `ULTRA_SUBLEVEL`, including `ultra_sublevel:0`, selects a pit in every
theme. Scene capture folds edges toward it into the existing `cliffTopExits` mask;
the normal cliff builder supplies the walls, including one- and two-level drops.
Pit boundaries disable the normal slope/talus transition and pin the cliff foot
to the opening, so there is no textured shelf inside the hex. The shared cliff
shader fades these walls into black with depth; their rock texture and rim still
come from the surrounding theme. Pit corners keep one shared vertical boundary;
all incident walls fade from the same cap height, including mixed-height rims.
Neither the board's elevations nor its gameplay cliff exits are modified.

The opaque black hex cap lies one visual level below the authored sublevel, so
even equal-level neighbours have an exposed rim. `Tile.groundLevel()` is the one
derived height used by the surface and its receiving cliffs. The cap replaces
the ground, relief and vegetation in the sculpted view, seals the sky, and
remains pickable. Tactical View keeps its existing tileset pit artwork at the
authored game elevation. Neighbouring pits at the same level join without an internal wall. Pit
edits invalidate the cap and surrounding cliff geometry.

Scatter cannot land inside the pit opening, including placements owned by neighbouring tiles.
Rim rocks settle fully onto the finished land surface, preserving scatter around
the opening; walls facing the pit do not grow fungal colonies into it.

`BoardUltraSublevelTest` checks zero-valued flags, theme independence, cap closure
and picking at all LODs, forced cliff classification and an unobstructed opening,
closed mixed-height seams and their shading, cache invalidation, live game/map-preview
edits, adjacent pits, and the three pits on Fungal Crevasse.

## Dry surface response

Natural ground, slopes, cliffs and boulders use the existing dielectric lighting
response when dry as well as when wet. Ground cover, soil, debris and exposed
stone blend their roughness with the same weights as their color, normals and
cavity. Cleaner stone faces reflect more than loose grains; fine variation
comes from the already sampled height/cavity channels and fades with material
LOD. These are art-directed estimates, not measured roughness scans.

The shared ground-color function receives the actual rim and foot distances for
its wear and shelter tint at those contacts. Sand and snow
covering a boulder also blend its roughness. No new geometry, texture lookup,
sampler, material batch or shadow pass is introduced. This adds fragment shading
work, so it is not a performance optimization.

Grass blades, a bank's rim blades included, use that same dry/wet dielectric
response at a dry roughness of 0.86. Bark and snow-covered plants also reflect light,
using rough 0.90/0.95 estimates; leaf maps retain their authored roughness, and separately
marked snow surfaces do not gain a rain film. Distant cards retain their combined
leaf/body approximation. These plant responses add no sampler, geometry or pass.
`GpuVegetationLightingSmokeTest` checks dry and wet plant reflection in both camera
views.

## Plateau weathering

Desert, Mars and volcanic ground expose broad, connected patches of flush stone
through their hardpan or ash. The existing region, broad and fine world fields
control the coverage, with the existing material height blend breaking the
contact. Foot deposits shelter loose cover; a thin film of the local ground
material softens exposed horizontal stone. Color, normal, cavity, height and
roughness all use the same final weights. Submerged beds keep their sediment.

Sandstone and basalt caps extend around the rounded crest and fade with its
slope and the existing depth-below-rim field (a variable 1ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“3 metre falloff).
Cap and cliff are treated as two presentations of the same rock: their blended
height competes with hardpan and deposits, then the rock weight is distributed
smoothly between the two maps. This prevents the height competition from
turning the cap/face transition into a sharp cutoff. All material channels use
that distribution. Deep steep faces retain their wall texture, and submerged
sediment still removes the dry cap. Mars uses its own mantle behavior.

The three mineral caps use their families' mantle slots: pale sandstone,
salmon Martian plates and cool basalt. Their aligned 512-square color/height and
normal/cavity pairs repeat at 12 metres, with estimated shading relief of 15, 15
and 18 cm. Their six RGBA16F layers of the texture array take about 16 MiB with
mipmaps; they need no extra sampler, draw batch or geometry subdivision.

Dirt and rocky ground expose pale bedrock; lunar regolith exposes darker stone.
Snow retains its cover with exposed scoured shoulders. Fungal lowlands keep
their mauve skin, weathered into violet bedrock; raised cyan crust has separate
colony gaps. Grass and tropical ground keep continuous cover and their native
banks. SAND remains a separate gameplay cover.

`GpuMaterialLodSmokeTest` checks mineral patches at close and normal map zoom,
and fungal weathering and palette identity at levels -3, 0 and +3.

## Authoring and related features

Sandstone and basalt caps and cliff faces use a registered height source,
`sources/authored-structure.png` in their Material Maker projects. Its linear
grayscale describes shallow plate faces, chipped rims and recesses independently of
pigment; the project derives normals and cavity from that field. These are
generated artistic height studies, not measured geometry.

The two caps use translated sampling at their 8-metre scale, for both color
and normal/cavity. This avoids overlaying an enlarged second set of fractures.
Single-scale sand and cap colour/height samples use their explicit texture
gradients and mipmaps without the extra fade toward the texture's overall mean.
Resolved ripples and fractures therefore survive normal gameplay zoom. `GpuTerrainMaterialReviewSmokeTest` checks
its stability during a deterministic pan and zoom; see
[native reviews](gpu-terrain-realism.md#native-reviews).

The [board asset format](../../mm-data/data/models/board/README.md) owns dimensions,
mesh roles and editable source locations. Runtime loads the generated models/maps;
offline tools in mm-data author them.

Use the dedicated guides for [rough](gpu-rough.md), [fields/marsh](gpu-fields-marsh.md),
[grass](gpu-grass-rendering.md), [buildings](gpu-modular-buildings.md) and
[terrain fire/smoke](gpu-terrain-fire-smoke.md). Their builders share the surface and
resource boundaries described here.


## Shared mud

`mm-data/tools/materials/terrain/surfaces/biome-mud/biome-mud.ptex` is the shared procedural mud
material for MUD ground hexes and BRIDGE_MUD on natural arches. Its author controls
cover clod size, basin size and coverage, pigment, roughness, repeat size and relief.
Stock Material Maker exports it as a Godot 4 Standard material, also with
`tools/materials/export.py terrain/surfaces/biome-mud/biome-mud.ptex`. Its 512-square maps
repeat every 64 metres (wider than two hexes). On the GPU they become colour with height,
and the normal XY/roughness/AO format described above.

Both surfaces sample those same maps at world coordinates. Muddy ground continuing
onto an arch therefore keeps the same pigment, clods and shallow pools across the
join. The bridge keeps its muddy walking surface, then fades deposits over the
caprock and down the adjacent sides into the underlying rock. Existing world
variation and the material's height break up that margin; the underside stays bare.
The authored low basins use the existing wet-surface BRDF;
there is no second transparent surface, water simulation or additional draw.
The interior uses four texture samples: two colour/height and two packed surface
reads, with no runtime pigment recolouring. The shifted samples reuse the ordinary
terrain helper and the slow world-region field. If height competition rejects the
mud, its two surface-data reads are skipped. Absent mud takes no material reads.
Packed roughness/AO remain needed with normal maps off. This adds no
sampler, pass or shader variant and does not alter the base terrain's role pruning.
Marsh and quicksand retain their existing materials and vegetation.
