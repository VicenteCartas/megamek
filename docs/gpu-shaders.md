# Shader sources and live editing

Shader resources live in
[resources/.../boardview/gpu](../megamek/resources/megamek/client/ui/clientGUI/boardview/gpu/).
Programs are assembled by their Java renderers; many GLSL files are helpers
inserted into a larger program, so editing a helper can affect several materials.

## Which source does what

| Source family | Purpose and Java owner |
| --- | --- |
| `terrain-normal.frag`, `terrain-sculpt.frag` | Ground materials; `GpuTerrain` assembles the programs and their material helpers. |
| `terrain-road.frag` | Road masks/markings; `GpuTerrain` with `GpuRoads`. |
| `terrain-projection.glsl`, `terrain-materials.glsl`, `terrain-concrete.glsl` | World projection, common surface sampling/blending and concrete finish. |
| `terrain-biome.glsl`, `terrain-biome-mask.glsl`, `terrain-meadow.glsl` | Ground response to fields, wetlands and meadow growth. |
| `terrain-foliage.frag`, `terrain-vegetation.frag` | Tree/plant surface shading; programs configured by `GpuTerrain`. |
| `terrain-grass.glsl`, `terrain-biome-vegetation.glsl`, `terrain-vegetation-wind.glsl` | Instanced blade/crop shapes and shared wind; `GpuGroundCover` and `GpuBiomeVegetation` inject the vertex helpers. |
| `light-model.glsl`, `surface-lighting.glsl`, `rain-surface.glsl` | Shared light response, shadow/specular terms and wet surfaces. |
| `surface-shading.glsl` | One `Surface` description and its lighting entry `shadeSurface`: sun or moon with the caller's shadow, sky and environment reflection, local lights, with both specular lobes scaled for GGX multiple scattering (`specularMultiscatter`, so rough metals keep their brightness); sculpted ground, ground and roads, trees and plants, and imported models (`model.frag`, `model-surface.glsl`). |
| `color-space.glsl`, `environment-lighting.glsl` | Exact sRGB boundaries and shared integrated BRDF/environment reflection; `GpuEnvironmentLighting` owns the lookup table. |
| `water-surface.frag`, `water-fall.frag`, `water-spray.frag`, `water-cut.frag`, `water-beyond.frag` | Separate water draw modes; assembled by `GpuTerrain`, with attributes/uniforms from `GpuWaterShader`. |
| `water-optics.glsl`, `water-lighting.glsl`, `water-interactions.glsl`, `water-pool.glsl` | Water color/transmission, illumination and local motion/contacts. |
| `terrain-ice.glsl` | Lake ice and discovered ground/road glaze; injected and bound by `GpuIceShader`. |
| `terrain-magma-solid.frag`, `terrain-magma-flow.frag` | Separate crust and flowing-lava programs; `GpuMagmaShader` injects `terrain-magma.glsl` and the corresponding solid/flow helpers. |
| `ocean-spectrum.frag`, `ocean-fft.frag`, `ocean-water-finish.frag`, `ocean-lava-finish.frag` | Wave spectrum, inverse FFT and mode-specific output; `GpuOcean`. |
| `cloud-shadow.glsl` | Direct-light cloud attenuation; `GpuCloudShadow` inserts it into receiving programs. |
| `atmosphere-fog.frag`, `atmosphere-composite.frag` | Fog/shafts and final scene composition; `GpuAtmosphere` assembles their helpers. |
| `smaa-*.frag`, `fsr-easu.frag`, `fsr-rcas.frag` | The window's SMAA 1x on the composite at the render resolution, then below 100% FSR 1 (EASU, RCAS) to the window's pixels with the scene depth; `GpuSmaa`, `GpuFsr`. |
| `weather-particles.vert`, `weather-particles.frag`, `weather-rain.glsl`, `weather-snow.glsl`, `weather-hail.glsl` | Shared precipitation programs with per-kind motion/shape; `GpuWeatherParticles`. |
| `effects.vert`, `particles.frag`, `particles-smoke.glsl`, `particles-fire.glsl`, `particles-jet.glsl` | Batched combat/terrain effects; `GpuEffectBatch` selects and assembles each appearance. |
| `terrain-effects.vert`, `terrain-effects.frag`, `terrain-effects-composite.frag` | Persistent terrain fire/smoke volumes and their reduced-resolution composite; `GpuTerrainEffects`. |
| `model.vert`, `depth.vert`, `unlit.frag` | libGDX 1.14.2's model, depth and unlit stages in core-profile GLSL, kept here. `GpuUnitShader`, `GpuTerrain` and `GpuThermalUnits` extend `model.vert`; shadow casters, the wireframe view and unit outlines extend `depth.vert`; `GpuUnitShader.unlitProvider` draws the tactical pages, fire control and route markers with `model.vert` and `unlit.frag`. |
| `shadow-depth.frag` | Shadow depth/cutout behavior; `GpuTerrain` configures the provider and `GpuTreeInstances` supplies instanced transforms. |
| `tessellation-control.glsl`, `tessellation-evaluation.glsl`, `tessellation-shape.glsl` | Sculpted relief's Phong tessellation (`GpuTessellation`): each edge's split, each point and normal of the patch, and how far each kind of vertex rounds. |

Unit materials are configured by `GpuUnitShader`: the `model.vert` vertex stage with linear colours, our
`model.frag` fragment stage, normal maps and per-unit material inputs. Imported model materials fill a
`Surface` in `model-surface.glsl`, with factors/maps carried by `GpuModelMaterial`, and are lit by
`shadeSurface` like the terrain. Roughness/metalness and normal/AO channels are linear data.
Normal/AO/surface maps use mesh UVs rather than the projected unit camouflage
coordinates. The same model path is used for ordinary imported buildings and
props. Custom terrain, foliage and water programs retain their own surface
composition. See the [model material contract](unit-models.md).
Do not assume every program starts from a standalone vertex file: some extend
`model.vert` or `depth.vert` with injected attributes and functions. libGDX's
SpriteBatch and ShapeRenderer stages still draw the 2D HUD.
libGDX binds only the instance attributes of the renderable a material program was
created for, and `DefaultShader.canRender` does not compare them. `GpuTerrain`
therefore also matches the instance layout: crop rows, which carry a row span,
never reuse a reed program, which binds only the root.

## Loading, drafts and saving

`GpuShaderSource` resolves a source through runtime `data/shaders` overrides,
checkout resource directories, then bundled classpath resources. A session draft
takes precedence during a managed compile. If a resource edit has no effect, check
the resolved source and any runtime override before changing unrelated code.

`GpuShaderManager` tracks source dependencies while programs are assembled.
It prepares affected replacements on the GL thread before committing a reload,
so a compile failure can retain the working programs. Standalone programs register
their replacement callback; material programs go through `GpuShaderProvider`,
whose stable handles keep existing materials connected to their replacements.

`GpuShaderEditor` is opened through Tuning → Edit shaders. Live preview compiles
after typing pauses; Apply/Ctrl+Enter compiles immediately. Save all/Ctrl+S applies
and writes the edited files; Revert file restores the saved text.
Saving checks for external
file changes rather than silently overwriting them. A live draft and a durable
source edit are distinct: use the displayed resolved path to know which file a
save will change.

## Terrain startup

Terrain startup prepares the material variants found in actual chunks while CPU
geometry is being built. The loading display's **Compiling shaders** bar (the UI kit's
progress bar, `UiKit.progress`) shows the share linked of the programs the load needs so far,
as a percentage: these generic programs and their depth twins, then the family programs below
once they start (`GpuTerrain.shaderProgress`). It shows only while a program is left to link;
the total grows as chunks find new variants, so the share can fall back as well as rise.
`GpuGlsl.Preparation` polls the driver's parallel shader
compiler when available; otherwise a worker compiles in a private hidden shared
context. While links run, the warm-up asks for its programs again only once one finished or
its samples or light changed: asking assembles a program's sources again, which on Iris Xe
with nothing cached took 83 s of render-thread time on Fire and Ice 2. The render thread adopts each completed program into libGDX without a
second compilation and retains resource ownership. Lighting and shadow changes
recheck the required variants. Closing a renderer during a native compiler call may
wait for that call before releasing the shared context. One worker is enough: Intel's
driver links one program at a time across contexts, so on Iris Xe 30 terrain programs
took 168 s with one context, 192 s with two and 295 s with four.

`GpuProgramCache` keeps every linked program in `mmconf/gpu-program-cache`, named by
the SHA-256 of the driver's vendor, renderer and version, a salt and both complete stages
without their comments (`GpuGlsl.withoutComments` keeps the line breaks), so a later
launch loads it instead of compiling and a comment-only edit changes nothing; Intel's own
cache stores nothing once it is full. A damaged entry, or one the driver rejects, is
deleted and compiles again; a driver without binary formats compiles at every launch.
Opening a board window, and every 256 MB written, deletes entries unused for 30 days, then
the least recently used beyond 1 GB. `-Dmegamek.gpu.programCacheDir=` keeps the entries
elsewhere; the Gradle `test`, `gpuBoardSmoke`, `gpuBoardBenchmark` and `gpuBoardLargeMapTest`
tasks use `build/gpu-program-cache`, so test runs never evict the player's entries.
`-Dmegamek.gpu.programCache=false` compiles from source, for compiler timings. On Iris Xe the calibration board's 30 terrain programs compile in 168 s and
load from the cache in 0.3 s; a warm load of the board takes 6–10 s, and its 44
programs take 5.5 MB (`GpuProgramCacheSmokeTest`).

No program compiles once the loading stage has gone. No program exists for combat scars: a
scar patch on a wall or an object draws with its receiver's sculpt or model program, which
its page UV per vertex and a per-draw uniform (`u_surfaceScar`, `scar-material.glsl`) make
draw the patch where its image covers the receiver and nothing elsewhere (alpha 0 under
blending, no discard, `GpuSurfaceScars`). In games (`GpuTerrain.setCombatScars`) the loading
stage also links the plain program of each object drawn instanced. The editor's samples take the board's ambient-occlusion setting,
so they draw with the board's programs; on NVIDIA they match the samples without it bit for
bit (`GpuExactProgramsSmokeTest`). Sculpted ground leaves out the biome stage (biomes,
tundra, mixed liquids) on a board without biomes that the editor does not change
(`GpuEnvironmentLighting.biomes`, part of every program key): the frames stay bit-identical,
and on NVIDIA a one-family program links in 0.9 s instead of 1.9 s. Ground, water and magma
programs link behind the loading stage with the sculpt programs. The frames after a loading
stage still draw under it, so that their programs link first: the board as it will show
and, unless the board makes its entrance, a close view of its centre;
the brief effects of play (weapons, missiles, explosions, TAG beams, jump jets, heat, hex
masks, range walls and labels, routes and their pulse) link with the first of them
(`GpuBattleView.warmUp`), and those of the board where it has them: hex fire and smoke,
splashes, spores and geysers (the editor's always). Lasting effects link when they first
show: the board's weather, pilot flames and ECM fields in those frames; star shells, the
deployment tint and another weather once they appear. Fire and smoke a board gains later link
as a nuke launches or a scheduled one goes off, before its burning hexes arrive; other new
fire or smoke when it appears. Without the parallel compiler, a program requested for a
framebuffer object links on a context that never draws (`GpuGlsl.linkOffscreen`), since
Intel would compile it again at its first scene draw and keep that variant in its binary.
The game's units not on the board (`BoardSource.reserveUnits`) stand in the middle of each
of those frames; every loading stage, a lighting switch's too, probes again. Optional views
and settings are not probed: the Tactical View, the thermal (wireframe) view, SMAA and a
render resolution below 100% link their own programs when they first show, without a
loading stage. Saved on, SMAA and the render resolution link behind the loading stage, since
those frames draw with them. What they share with the board is already linked, and the program cache
serves the links of earlier runs. On a cold cache Grassland 3's Tactical View links one
unit-material program, its thermal view three wireframe and three thermal-unit programs,
SMAA three and FSR two. The views' programs link in the background (`after-loading-deferred`):
no frame freezes, and until they are linked their draws wait. SMAA and FSR link on the render
thread when switched on later, holding that frame (`GpuShaderLoadSmokeTest`). The editor's board also
prepares the palette's kinds while it loads (`GpuEditorTerrainPreviews.kinds`: each preview,
preset and look but buildings' designs, apart on clear ground; frozen land and its border
both ways) and the wave simulations. `GpuMapHud.prepare` also warms the palette's own
terrain and model preview providers before the loading stage ends; warming the main board
provider alone does not prepare those separate owners. An edit whose new materials still
need a program shows the previous terrain until it is linked (`GpuTerrain.awaitingLinks`),
not the loading stage. Changing wet terrain to dry resets ocean storage while retaining
its compiled programs; only final disposal releases those programs.
Once the board shows, a material program still missing links in the same preparation
(`GpuGlsl.deferral`) while its draws are skipped (`GpuShaderProvider.UNLINKED`), at most
6 ms of starts a frame; palette cards, model previews and print tiles draw again until
complete. `-Dmegamek.gpu.shaderTiming=true` logs a compile that held a frame as
`after-loading` (`after-loading-offscreen` for those renderers), one linked so as
`after-loading-deferred`; `GpuShaderLoadSmokeTest` asserts no late synchronous or deferred
links and no returning loading stage for a game (late deployments, SMAA, a 75% render resolution; the Tactical
View and the thermal view may link their own programs, but bring back no loading stage)
and for the editor (every Assets category, a search, strokes of lava, water, ice, scorch,
a car and the board's first building, followed by a specular-AA switch and fresh model/junction previews). Such links still
cost frames: NVIDIA reports a parallel link complete, then holds its first status query for
about half the link (5–150 ms for an object's program, 5–8 s for a sculpt program), and
first draws a program loaded from the cache slowly (3.5 s for the ice card's sculpt program);
on Iris Xe a link on the shared context holds the render thread throughout. The preview
warm-up therefore includes the palette's object models even when no terrain kind shows them.
Model previews key preparation by both the board renderer and their actual lighting
environment flags, so changing specular AA prepares the new programs during the
lighting reload. Obsolete warmup meshes are disposed when that key changes.

The 2026-10-19 Iris Xe runtime check passed with zero late synchronous/deferred links
and no returning loading stage, both during normal editing and on the first presented
frames after the AA change. This used a private application cache and is a preparation
correctness result, not a claim of hitch-free editing: the longest editing frame was
546.5 ms. Evidence is in `build/shader-audit/handoff-20261018/iris-finish/runtime-editor/`.

Once the board shows, sculpted ground draws with family programs (`GpuFamilyPrograms`): the
same source with the draw's surface families as constants (`SCULPT_FAMILY`,
`COVER_FAMILIES`), so the compiler keeps only those families' material paths. The
generic program of the same flags stays every draw's fallback and draws the same pixels; a
family program sorts with it, so draw order and depth-tie winners do not change. Family
programs build behind the loading stage, after the generic ones: when a board opens, the
graphics switch or the lighting changes, the most-drawn variants compile six at a time with
the driver's parallel compiler (NVIDIA), one at a time on the worker otherwise. Variants that appear later, such as a newly painted combination, keep the
generic program until the next load. A load compiles at most four family programs from source
(`GpuFamilyPrograms.FRESH`), the most-drawn first, beside every one the program cache holds; the
other variants keep the generic program, and each later load compiles the next four (without a
program cache, every load the first four). A lighting change behind the loading stage counts as a load. Uncached, a
family program costs about 15 s on Iris Xe, most of it Intel's compile at its first draw: with
nothing cached, Fire and Ice 2's first load took 693 s with all 28 and 367 s with four (RTX 4070:
112 s and 45 s). Crust and bank draws and volcanic palettes with at most
one ordinary family keep the generic program, which already compiles one family's path; so
do scarred surfaces, scatter, water, editor previews and board prints. A terrain keeps at
most 32 family programs for each lighting configuration and 64 in all. A board without clouds has four
configurations (specular anti-aliasing with and without a directional light), up to eight across the cloud marker's
states; once a terrain holds 64, a third configuration draws with the generic programs: on the calibration board at
1080p on Iris Xe that is about 6.5 ms slower fitted and 16 ms at the middle distance (step 4's
family-program timings). A shader-editor change recompiles
the family programs with the other variants, on the render thread.
`-Dmegamek.gpu.familyPrograms=false` draws with the generic programs only.

The depth pre-pass (`GpuTerrain.renderPrepass`) draws the trees' depth before the opaque colour pass: leaves and
impostor cards with their own alpha test, after which `terrain-foliage.frag` returns under `depthPrepassFlag`, trunks
and other solid tree parts with an empty fragment stage (`terrain-depth.frag`). It also draws the relief of chunks with
many cliffs, with that empty stage: a chunk's walls (each relief triangle's area as seen from beside it, the board's
outer sides left out) must cover more than 0.45 of its hexes' area (`GpuTerrain.cliffWalls`), measured where its meshes
are prepared. Such a chunk marks its relief materials (`Ground.cliffs`, `Prepass.CLIFFS`), so their draws stay apart
from other chunks' in shared pages and batches and use the same programs. Their colour draws then test `GL_EQUAL`
without writing depth, so hidden leaves and ground are not shaded. It draws building shells too, the walls and roofs of
buildings and fuel tanks other than kit buildings: their surfaces carry `GpuModelMaterial.occluder`, so each of their
draws takes its program's objects twin (`Prepass.OBJECTS`), which links behind the loading stage, while a hex is at least
16 pixels wide on screen (`TreeLod.PREPASS_OBJECTS`, ±10 %); further out they draw as before and keep their twins, so a
zoom links nothing. Ambient occlusion sees the shells only while they are pre-passed, so ground occlusion beside
buildings changes slightly across that gate. Every other prop stays out, also where it shares a shell's program: kit buildings' instanced
modules, walls, industrial structures, steel structures, rubble, fortifications and placed objects. With every object's
program twinned, the pre-pass took 5 ms in Valencia City's middle view and 16 to 18 ms in its fitted view on an Iris Xe,
for 1 to 1.5 ms of colour shading saved. Each pre-passed generic program has a depth twin with
the same prefix and vertex stage, which its family programs share. Twins are programs beside the colour programs,
outside the family programs' capacity, and link behind the loading stage with the generic programs, every tree detail
level included. Every terrain vertex stage declares `gl_Position` invariant, and a cutout's vertex stage also the alpha
test's inputs (`foliage-coverage.glsl`); below GLSL 4.20, `terrain-foliage.frag` declares those inputs invariant too
(`coverageIn`). The frame's single gather, draw order and flushes do not change. A draw without a twin, a variant that
appears later and a shader edit's replacements draw as before until the warm-up pairs them. The Tactical View, the
Classic columns and the wireframe draw without it. `-Dmegamek.gpu.depthPrepass=false` turns it off; `=all` adds the
other chunks' relief, for measurements only, since on an Iris Xe their twins cost about what they save. On NVIDIA the
frames are bit-identical with and without the pre-pass; on an Iris Xe, `GL_EQUAL` drops a few isolated pixels on leaf
edges and tufts (5 to 2133 of a 1080p view's 2.07 million) and on cliff relief (0 to 5). On an Iris Xe at 1080p the 40
by 40 forest's middle view draws in 32 ms instead of 53.5 ms; the cliff chunks take the Box Canyon's middle view from
19.2 to 13.5 ms and the calibration board's from 22.2 to 20.7 ms, leave the Jotun Mountains' unchanged within its
rounds' spread of about 1 ms, and the 48 by 48 city and Grassland 3 have none. The building shells save 5.2 to 6.7 ms in
the 48 by 48 city's middle view (two processes; rounds 2.7 to 8.2 ms) and 0.8 to 1.6 ms in Valencia City's, for 0.1 to
0.4 ms of pre-pass; in the city's fitted view (29-pixel hexes) about 0.5 ms, within its rounds' spread of 3 ms. Grass and the crops' plants stay out: they
shade about 1.55 fragments per visible pixel, and on an Iris Xe their twins saved about 1 ms on the Farmland board's views
and cost about 1 ms on Mesa City's grass. `GpuDepthPrepassSmokeTest` checks the
parity and shader edits; `GpuDepthPrepassBenchmarkSmokeTest` times it.

Ambient occlusion (`GpuAmbientOcclusion`; the Display page's Ambient occlusion, off until switched on, and its
strength) darkens the sky light where the relief and the trees hide the sky. It runs between the depth pre-pass, which
also draws the relief while it is on, and the opaque colour draws: ground-truth ambient occlusion (Jimenez et al. 2016,
`ambient-occlusion-horizon.frag`) at half resolution from the pre-pass depth, with normals from that depth, two
directions of four steps on each side within 4 m, a cell of the board's sky visibility, which covers the farther
relief (its cells grow on boards over about 1 km across; at most 160 scene pixels on screen), then
two depth-aware blur
passes (`ambient-occlusion-blur.frag`) that remove its 4 by 4 rotation pattern. The same pass
marches contact shadows: eight jittered steps toward the sun or moon over 2 m of the pre-pass depth, an occluder taken
as 1 m deep, for the small occluders the shadow map is too coarse for; none without a direct light, and they fade out
where the sun grazes the surface, whose own facets the march would meet. Lit programs read
both with `screenOcclusion()` (`ambient-occlusion.glsl`; `shadeSurface` takes the lower of the first and the sky's
geometric occlusion, then a texture's finer occlusion, and multiplies the sun's visibility by the second), which
upsamples them against the fragment's own depth: surfaces the pre-pass does
not draw (units, props other than building shells, and the shells while a hex is under 16 pixels)
read 1, as does every draw after the units (water, transparent effects). Its environment attribute compiles
`ambientOcclusionFlag` into the lit programs, so a switch rebuilds them behind the loading stage; without it the accessor
is the constant 1 and the frames are bit-identical to those without the feature. Sculpted ground with volcanic crust
draws without it while the board's sky visibility is compiled in (`GpuSkyVisibility.STRENGTH` above 0), and always with ice:
its sampler would be the program's 17th texture unit (`GpuSamplerBudgetSmokeTest`), and the board-wide sky occlusion is
the one a crust patch in deep shade would visibly lack.
Its two RGBA16F targets (visibility, camera distance, contact visibility) follow the scene's size; in an orthographic view the distance is stored less the orbit pivot's depth (`GpuAmbientOcclusion.rangeOrigin`), since that camera stands about 10,000 units back, and the sky is clamped to 60,000. `GpuAmbientOcclusionSmokeTest` checks it and, with
`-Dmegamek.gpu.measureAmbientOcclusion=true`, times it.

Sun shadows filter the shadow map with one tent (`shadowTent`, `surface-lighting.glsl`): a separable tent of a half
width in texels, read as bilinear comparisons at positions whose weights make it exact, so it is smooth in position
and width with no noise pattern. Models and other surfaces take it a texel and a half wide in four comparisons
(`surfaceVisibility`). On sculpted ground and trees (`sculptShadow`) it widens with the distance to what casts the
shadow, as the sun's half-degree disc does, from one texel to three, in nine comparisons. The widest tent reads six by
six texels, nine blocks of two by two: the centre and its four edges lit, against the highest depth any tent compares
there, is lit (the corners, a fifth of the widest tent's weight, unseen); all nine shadowed against the lowest is the
umbra, exactly, without the tent. Elsewhere the same five blocks at the receiver's depth and 3 m and 15 m nearer the sun
tell how far the caster stands. No second sampler reads raw depth. On
sculpted ground the lookup rises with the visible grain (its height map, `RELIEF_METRES` deep), so raised grains break
a shadow's edge, and the cavity shades hollows first where the sun grazes. Its lookup also shifts across the light by
up to `FRAY_METRES` (a quarter metre) in 0.1 to 0.3 m tufts of the rain-noise texture, as grass, clods and stones fray
a natural shadow's edge; the noise's mipmaps take the shift out with distance.

Sculpted relief can be tessellated where the context offers it (`GpuTessellation`; OpenGL 4.0 and later, so every Mac's
4.1 and current Windows and Linux drivers' 4.6), by the Tuning panel's Tessellation switch (`GpuTerrain.setTessellation`,
off by default). The switch is part of the build, as the 3D Graphics are: a switch rebuilds every chunk behind the
loading stage, and with it off the meshes and programs are the untessellated ones. Its triangles span 1 to 2 m on banks
and cliffs; the smooth normals of its shading hid them, while its silhouettes and the shadow map showed them. Each
triangle becomes a Phong patch (Boubekeur and Alexa 2008) through those normals, rounded by the kind its vertex colour
gives (`tessellation-shape.glsl`): soil and turf fully, as creep rounds them; a cliff by its rockiness (its green
vertex channel, as its shading decides: a bank of up to two levels as soil, from three as rock), which barely rounds,
so its faces stay planes and only its corners soften (fully rounded, a mesa and a pillar bulged like cushions); built
panels and plants not at all. Contact distances (the texture coordinates) keep the shared rims and feet fixed even where
the adjoining material or shading normal changes. Everything else that must stay where its mesh puts it comes as one
input, `a_tessFixed` (`BoardSurface.fixedTessellation`), added to the curved layouts and resolved after water clipping
and material subdivision: the board's cut, against its flat wall; rail grades, whose planes `BoardRail.layout` samples;
built bridge passages (`BoardBridge.approaches`); the shared edges of water banks, shaded apart on either side; and a
liquid hex's ground at and below its water, so bed fans and their clipped neighbours keep one support.

Road coatings at full detail tessellate with their support: they borrow its control positions, normals, colour, contact
distances and fixed input (`GpuRoads.TESSELLATED_VERTICES`) and add their clearance only after evaluation, so the curved
ground never rises through the pavement. Where the ground clips a supporting face at the water or splits it for its
material blend, the coat takes those final triangles (`GpuTerrain.coatSupports`), as a split edge's new point can curve
where the whole edge would not. Coats on volcanic hexes and on bridge decks stay flat, as their support does.
Coatings also cover the walkable upper rim faces emitted with a slope (`BoardSurface.coatingFaces`, within the upper
tenth of a step), so a road end spilling onto a rim keeps its pavement in both modes. The curving coats' programs link
behind the loading stage in the game as in the editor.

Material subdivision (`GpuSurfaceBlend`) splits an edge where one triangle's blend needs it, at a point on the straight
edge; where its neighbour leaves that edge whole and curves it, a hairline gap can open between them.

Only what shows passes through the stages. Built with the switch on, a full-detail chunk (the detail levels below it
draw a facet a few pixels across) puts its sculpted triangles that curve, those with an edge whose Phong curve rises
more than nine tenths of `GpuTessellation.BULGE_METRES` (3 cm) from it at its middle (`GpuTessellation.curves`), in a
copy of their material (`GpuTessellation.curvedCopy`, id suffix `#curved`). The curve only rises: the evaluation stage
(`tessellation-evaluation.glsl`) keeps the outward part of the Phong offset, along the point's normal, so a rim rounds
and a slope's foot keeps its plane. The shadow map draws the flat triangles, which the drawn surface so never sinks
below: nothing shades itself. The control stage (`tessellation-control.glsl`) gives an edge that rises less, or would
sink, one segment, straight, so those triangles meet the others without a crack. Otherwise it splits the edge into the
fewest whole segments (equal spacing, so no sliver triangles) that keep the curve within half a pixel of them as the
camera sees it (the bulge over the segments squared), at most 8, from the edge's two vertices alone, so neighbouring
patches split it alike; the inside takes the edges' mean. The evaluation stage places the point and runs the original
vertex stage on it, its inputs interpolated, so every output the fragment stage reads stays the same. The stages are
generated from the vertex source and travel in it, apart at `#pragma stage(...)` lines that `GpuGlsl` splits when it
links; the program cache keys on the whole. The colour programs and their depth-pre-pass twins tessellate alike, from
the same camera; the wireframe view keeps the triangles. While the switch is on, every sculpted material's curving copy
links its programs and requests its family program behind the loading stage (`GpuTerrain.prepareTerrainMaterial`,
`curvedFamilies`), so a zoom into full detail links nothing (`GpuShaderLoadSmokeTest`). On the Iris Xe it costs the
opaque terrain 1.1 to 2.3 ms a frame in `GpuTerrainBenchmarkSmokeTest` (medians of four runs, most close up,
2026-10-28); drawing every relief triangle tessellated had cost 8 to 13 ms.

## Uniforms, inputs and previews

`GpuShaderUniforms`, `GpuShaderInputs` and `GpuShaderValue` record typed inputs
from actual programs. `GpuShaderInputPanel` presents them; this list is not a
separate hand-maintained registry of every possible GLSL variable.
Rows distinguish the renderer's supplied value, the effective value and a local
override. Clearing an override resumes the current supplied value.
Inputs used before shading cannot be discovered as GLSL uniforms; supported
samples expose those under Preview setup (CPU inputs).
`GpuShaderPreview` and `GpuShaderPreviewPanel` render previews using the same
source-management path. `GpuGlsl` handles compilation/prefix compatibility;
`GpuGlslTokenMaker` supplies editor syntax highlighting.

For a new material input, update its Java attribute/setter, the assembled source
and the pass that binds any required texture. A declared uniform alone does not
supply its value. For a helper change, inspect all consumers recorded through
`GpuShaderSource.read`, not only the material visible in the editor.

## Reloading assets and exporting programs

Tuning → Reload assets first checks for a sibling `mm-data` checkout, beside either
the installation or the repository containing Gradle's `megamek/data` directory.
It copies new or byte-different files only from `mm-data/data/models` (including
its textures and descriptors) into the running data directory in the background,
then reloads textures, models, descriptors, tilesets and shaders. Save authoring
changes in `mm-data/data/models` and press Reload assets to preview them with the
current camera and tuning settings. Files present only in the running data directory
are left in place; source deletions are not propagated. Other data directories are
not scanned or copied; edits there must already be in the running data directory.
Without a sibling checkout, Reload assets simply rereads the running data directory
as before. Live shader
drafts remain separate from an asset-file reload.

The JVM property `-Dmegamek.gpu.shaderExport=<directory>` writes assembled program
sources for external GLSL tools. Use these when inspecting the complete
libGDX prefix and injected helpers; resource snippets alone are not always a
standalone compilable shader.

## Boundaries to preserve

Material programs share lighting, world projection and contact fields. A surface
blend needs matching color, normal, height/roughness and occlusion weights.
Wetness must affect the intended receiving materials. Decode authored albedo and
emission, add emission to reflected light in linear space, and keep the scene
output linear through alpha/additive blending. Retain values above one until the
atmosphere's shared exposure, highlight shoulder and final display conversion.
Changes to alpha/cutouts may also require the shadow/depth path and the depth
pre-pass's twin (`depthPrepassFlag` in `terrain-foliage.frag`). Weather and
tactical/UI draws after the composite still supply display colors. Isolated
shader and section previews use `GpuSceneDisplay` for the same final conversion.

macOS's OpenGL gives each shader stage only 16 texture units, and a program that
samples more fails to link there, so the board does not draw on a Mac although
Windows and Linux drivers (32 units) accept it. Add maps as layers of an existing
array where they are aligned repeats of one set, as the sculpt maps
(`u_terrainLayers`), each magma set (`u_magmaMaps`) and the ice normal and surface maps
(`u_iceMaps`) are. The on-demand
`GpuSamplerBudgetSmokeTest` renders the battle view over volcanic, rain, snow, fog,
river, highway and hazardous-liquid boards, fails on any program over 16, and
prints every variant's count. On 2026-10-16 the combined ice and volcanic sculpt
material used all 16, including the local light list (`u_lavaLights`).

A shader edit normally replaces programs and invalidates affected render caches;
it does not change game state, terrain support or picking. Change Java geometry
when the intended result alters silhouettes or contact surfaces. Programs,
textures and previews must be installed and disposed by their GL-thread owner.
