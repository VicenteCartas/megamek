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
Opening a board window, and every 512 MB written, deletes entries unused for 30 days, then
the least recently used beyond 2 GB. `-Dmegamek.gpu.programCacheDir=` keeps the entries
elsewhere; the Gradle `test`, `gpuBoardSmoke`, `gpuBoardBenchmark` and `gpuBoardLargeMapTest`
tasks use `build/gpu-program-cache`, so test runs never evict the player's entries.
`-Dmegamek.gpu.programCache=false` compiles from source, for compiler timings. On Iris Xe the calibration board's 30 terrain programs compile in 168 s and
load from the cache in 0.3 s; a warm load of the board takes 6–10 s, and its 44
programs take 5.5 MB (`GpuProgramCacheSmokeTest`).

While the window shows a menu (start screen, lobby), `GpuShaderWarmup` runs a process of its
own that builds boards' terrain as a loading stage does and links their generic and family
programs, blends included, into the program cache; a game, the lobby's map preview or the
editor then loads them as binaries. It prepares the boards likely next first (the last one
shown, the lobby's map, a scenario's; the newest four are kept in `warmup-boards/` beside the
cache), then one sample board per theme with every blueprint component, preset and look, for
four lighting configurations (a game's, the editor's, biomes or crystals alone,
`GpuTerrain.setLightingStages`), all with the saved Display settings, until the sample boards fill half the
cache (1 GB): the boards played keep the rest. It ends at once when a
board view is asked for or a board draws inside a menu (the lobby's preview), and goes on at
the next menu, or 2 s after the preview last drew: that board's own links and frames come
first. A file beside the cache lists the steps done for a fingerprint of driver, shading
language, settings, `megamek.gpu.*` properties, shader files, code and blueprint; with nothing
changed no process starts. It keeps only programs linked off its render thread, where nothing
draws, as the game's terrain links are without the parallel compiler; programs a board finds
only by drawing (units, shadows, effects) still link in its loading stage. The window shows the
editor's spinner, translucent in its bottom-right corner, while the warm-up, the link helper or
a deferred link runs (the editor shows its own). `-Dmegamek.gpu.shaderWarmup=false` turns it
off (the Gradle GPU test tasks do); `-Dmegamek.gpu.shaderWarmupSteps=` chooses sample boards.
Its process runs below normal priority (Windows' class; on Linux and macOS it starts under
`nice -n 10`); its two threads in the game's process keep the game's priority, since the window's
thread waits on their lock. It builds chunks on two threads and, where the driver compiles in
parallel (NVIDIA), compiles on two of the driver's threads. It held at most 4.2 GB on Iris Xe (2.9 GB
at the median; up to 1.3 GB of it the system memory its textures took) and 2.9 GB on an RTX 4070
(1.8 GB), mostly the driver's, its Java heap 0.6 GB; with the driver's own thread count it held
4.9 GB on the RTX 4070. It starts only while the system has 4.3 GB available beyond the 1 GB it
leaves free, and ends within a second once less than that 1 GB is
(`-Dmegamek.gpu.shaderWarmupFreeMiB=`, negative for no check), trying again 10 s later; where
the available memory can't be read (macOS, where Java counts only free pages) it doesn't start.
The progress file expires after 30 days unwritten, as unused cache entries do, so what pruning
took is prepared again.
With the drivers' own shader caches empty, the grass board in a game's lighting stores 88
programs in 70 s on an RTX 4070 and 349 s on Iris Xe (14-23 s on the RTX 4070 with NVIDIA's cache
warm), after which Grassland 3 links none of its 23 terrain programs, and on Iris its first shown
frames take at most 33 ms; Grassland 3 asked for by itself takes 24 s on the RTX 4070 and leaves
its game none of 23 generic and 4 family programs to link (`GpuShaderWarmupSmokeTest`).

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
splashes, spores and geysers (the editor's geysers always). Lasting effects link when they first
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
thread when switched on later, holding that frame (`GpuShaderLoadSmokeTest`). The editor loads
only what its starting view draws, with no close view: the palette's cards, a closer zoom and
whatever a stroke brings link when first asked for, while the board shows and a spinner beside
the issues triangle says so (`GpuBoardEditor.showPreparing`). Its geysers' and wave simulations'
programs still link while it loads, 18 ms together, so that a first stroke of water holds no
frame. An editor's programs always keep the crystal stage, as they keep the biome stage: that
flag is in every program's key, so the first crystal in a card or a stroke would link the whole
set again. An edit whose new materials still need a program shows the previous terrain until it
is linked (`GpuTerrain.awaitingLinks`), not the loading stage. Changing wet terrain to dry resets ocean storage while retaining
its compiled programs; only final disposal releases those programs.
Once the board shows, a material program still missing links in the same preparation
(`GpuGlsl.deferral`) while its draws are skipped (`GpuShaderProvider.UNLINKED`), at most
6 ms of starts a frame; palette cards, model previews and print tiles draw again until
complete. `-Dmegamek.gpu.shaderTiming=true` logs a compile that held a frame as
`after-loading` (`after-loading-offscreen` for those renderers), one linked so as
`after-loading-deferred`; `GpuShaderLoadSmokeTest` asserts no late synchronous or deferred
links and no returning loading stage for a game (late deployments, SMAA, a 75% render resolution; the Tactical
View and the thermal view may link their own programs, but bring back no loading stage).
For the editor (every Assets category, a search, strokes of lava, water, ice, scorch, a car and
the board's first building, followed by a Quality switch and fresh model and junction previews)
it asserts that no link holds a frame, that no loading stage returns and that the spinner shows,
then goes. It counts links from source only, so a program the cache serves hides a missing
preparation: run it with an empty `-Dmegamek.gpu.programCacheDir`. Background links can still cost
frames: on an empty cache the walk's 116 links left a longest frame of 445 ms on an RTX 4070
Laptop. On Iris Xe a link on the shared context holds the render thread throughout, and the
spinner stops turning meanwhile. On an empty cache that walk's frames over 1 s there were five, of
3.5–7.5 s: the 242 KB sculpt program, plain and blended, and its land-ice variants, each linking the
whole shader. All the others stayed under 1 s, and on the walk's second pass the program cache left
a longest frame of 361 ms.

Opening the editor, RTX 4070 Laptop at 1920x1080, until the first frame without the loading stage:

| Board | Program cache | Prepared up front | On demand |
| --- | --- | ---: | ---: |
| 16x17 Grassland 3 | warm | 37 s | 12.6 s |
| 16x17 Grassland 3 | empty | 103 s | 15–29 s |
| 48x48 The Trainjob | warm | 76–81 s | 35 s |
| 48x48 The Trainjob | empty | 100 s | 36 s |

An empty program cache can still find the driver's own: the lower Grassland figure had it warm.

The same on Iris Xe, at 1920x1080. The first ten seconds after opening also ran smoother: on warm
Grassland 3, 224 frames and a longest of 474 ms against 83 and 10 s; on warm The Trainjob, 408 and
280 ms against 109 and 15 s.

| Board | Program cache | Prepared up front | On demand |
| --- | --- | ---: | ---: |
| 16x17 Grassland 3 | warm | 25.5 s | 11.9 s |
| 16x17 Grassland 3 | empty | 152 s | 85 s |
| 48x48 The Trainjob | warm | 41.6 s | 29.8 s |

The empty-cache "on demand" ran first, with the driver's cache cold, so that gap is understated.
The Trainjob opened right after Grassland 3 took 48.8 s on demand against 41.1 s, as the old load
had already linked everything.

A board's plant searches run on the pool (`BoardTiles.search`), and before a window's first capture
with the Swing thread free (`BoardTiles.searchAhead`): The Trainjob's capture holds that thread for
about 2 s instead of 8–11 s, and every plant lands where it did.

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
family program links on Iris Xe at Medium in about 1.2 s on plain ground, 2.3 s on a blend and 3.4 s on a blend with the
biome stage, at High in 1.7, 4.4 and 5.9 s; a level tops' program (below) in 0.8, 1.4 and 2.4 s, at High in 1.2, 2.4 and
4 s; a first draw in a raw test adds 1-3 ms. Crust and bank draws and volcanic palettes with at most
one ordinary family keep the generic program, which already compiles one family's path; so
do scarred surfaces, scatter, water, editor previews and board prints.

A chunk's level tops draw apart from the rest of their material (`GpuFamilyPrograms.flat`): triangles whose three
vertices are ground kind with normals at least .601 high, so the wall test (`rocky`) is false throughout. Their family
program (`SCULPT_TOPS`, `unlessTops`) has no wall, rock, plant or pit path. Concrete and crust stay whole, a generic
program draws both parts, and page merges keep them apart (`GpuPropBatch.Merge`). On Iris Xe at 1080p, with every family
program built, that saves 1.4-5.8 ms on terrain views at Medium and 1.2-7.6 ms at High, for 2-9% more draws on real
boards.

Without the driver's parallel compiler a link anywhere in the game's process holds the render thread for the whole link:
on Iris Xe, frames of 2.1 to 6.8 s, since every call that touches a program, texture or buffer waits. There
`GpuLinkHelper` links in a process of its own, and the binary comes back through the program cache in 2-4 ms: one helper,
below normal priority, started on the first job, gone after a minute with none or with the game. The loading stage then
waits for the generic programs and for family programs the cache holds. Every other family program asks once behind it,
with no `FRESH` limit, and arrives beside the frames, one a frame within the frame's budget for links. A generic program
draws the same pixels meanwhile. Nothing optional links in the game's process: if the helper ends, those stay generic
until the next load. A required program links there only when its binary can't reach the game, because the cache can't be
written or the driver keeps rejecting it. A driver without binary formats keeps its own path.

Where the driver links in parallel (NVIDIA), the programs a load waits for link in the game's process, several at
once: one helper would link them one after another (Fire and Ice 2, nothing cached: 66 s of links against 22). Only
the family programs go to the helper there. Linked in the game's process, four of them kept the loading stage up for
30-42 s on each of a board's first loads: the driver reports such a link complete, then holds the link status query
for about 2.4 s a program and the program's first draw for up to 20 s. From the helper their binaries load without
either. RTX 4070, Fire and Ice 2, nothing cached, without / with the helper for family programs: the board shows
after 38.4 / 32.5 s, and 23 family programs arrive over the next 150 s of play; a frame that adopts one takes
26-59 ms, one took 155 ms. `-Dmegamek.gpu.linkHelper=false` keeps every link in the game's process, `=true` gives the
helper every program.

Iris Xe, 1080p, Medium, nothing cached, without / with the helper: Fenland loads in 118 / 72 s, then all ten family
programs arrive 30 s later (four of ten without, and 59.6 / 51.3 ms a settled frame); Fire and Ice 2 loads in 185 / 168 s,
and 32 of its 33 arrive over seven minutes of play (four without, and 30.2 / 25.6 ms). The longest frame while they
arrive is 59-157 ms; a frame that adopts one costs up to about 10 ms more, up to about 25 ms on Fire and Ice 2. Second
loads: 62.5 / 47 s and 32 / 17 s. Programs asked for outside a preparation (effects, the first hidden frame's 4 s) and
the shader editor still link in the process. Not run yet in a packaged build; two running MegaMeks each have a helper.

A terrain keeps at
most 32 family programs for each lighting configuration and 64 in all. A board without clouds has four
configurations (specular anti-aliasing with and without a directional light), up to eight across the cloud marker's
states; once a terrain holds 64, a third configuration draws with the generic programs: on the calibration board at
1080p on Iris Xe that is about 6.5 ms slower fitted and 16 ms at the middle distance (step 4's
family-program timings). A shader-editor change recompiles
the family programs with the other variants, on the render thread.
`-Dmegamek.gpu.familyPrograms=false` draws with the generic programs only.

The depth pre-pass (`GpuTerrain.renderPrepass`) draws the trees' depth before the opaque colour pass: leaves and
impostors with their own alpha test, after which `terrain-foliage.frag` returns under `depthPrepassFlag`, trunks
and other solid tree parts with an empty fragment stage (`terrain-depth.frag`). It also draws the relief of chunks with
many cliffs, with that empty stage: a chunk's walls (each relief triangle's area as seen from beside it, the board's
outer sides left out) must cover more than 0.45 of its hexes' area (`GpuTerrain.cliffWalls`), measured where its meshes
are prepared. Such a chunk marks its relief materials (`Ground.cliffs`, `Prepass.CLIFFS`), so their draws stay apart
from other chunks' in shared pages and batches and use the same programs. Their colour draws then test `GL_LEQUAL`
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
(`coverageIn`). An instanced tree's twins, its solid parts' included, run `terrain-foliage.frag` to its alpha test and
its dissolve between detail levels, so they keep the pixels its colour draw keeps. The frame's single gather, draw order and flushes do not change. A draw without a twin, a variant that
appears later and a shader edit's replacements draw as before until the warm-up pairs them. The Tactical View, the
Classic columns and the wireframe draw without it. `-Dmegamek.gpu.depthPrepass=false` turns it off; `=all` adds the
other chunks' relief, for measurements only, since on an Iris Xe their twins cost about what they save. On NVIDIA the
frames are bit-identical with and without the pre-pass. On an Iris Xe, `GL_EQUAL` loses a few isolated pixels on leaf
edges and tufts (5 to 2133 of a 1080p view's 2.07 million) and on cliff relief (0 to 5), others each frame, so a still
camera's frames differ. With `GL_LEQUAL` they repeat exactly (Grassland 3, City Districts, Sand Drift 1, Dome Vent 2).
City Districts' view took 21.3 to 22.3 ms at `GL_EQUAL` and 22.05 to 23.36 ms at `GL_LEQUAL`, about 0.7 ms on the means
within a spread of about 1 ms. Only a building without a kit is a shell; City Districts, City (Skyscraper) and the 48 by
48 city draw kit buildings. Those stay out of the pre-pass: with their modules in it, on an Iris Xe at 1080p and Medium,
the 48 by 48 city's fitted view went from 39.3 to 45.8 ms and its middle view from 56.9 to 60.8 ms (runs of 55 to 71 ms),
and City Districts' and City (Skyscraper)'s views stayed within their runs' spread, although the middle view's shaded
samples of the instanced model program fell from 3.7 to 1.9 million. On an Iris Xe at 1080p the 40
by 40 forest's middle view draws in 32 ms instead of 53.5 ms; the cliff chunks take the Box Canyon's middle view from
19.2 to 13.5 ms and the calibration board's from 22.2 to 20.7 ms, leave the Jotun Mountains' unchanged within its
rounds' spread of about 1 ms, and the 48 by 48 city and Grassland 3 have none. Grass and the crops' plants stay out: they
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
and width with no noise pattern. Grass blades, roads, ice and, at Medium, models take it a texel and a half wide in
four comparisons (`surfaceVisibility`). On sculpted ground and trees (`sculptShadow`) and, at High, on models
(`modelVisibility`) it widens with the distance to what casts the shadow (`contactShadow`), as the sun's half-degree disc
does, from one texel (on models a texel and a half, narrower their straight edges show the map's texel steps): up to
three in nine comparisons, wider in six by six (`shadowWideTent`) on one of the map's three coarser levels, the finest
that holds it in five of its texels, blended into the next across the top fifth of that range (`shadowPenumbra`). Each
coarser level keeps the top left texel of every two by two of the one above (`shadow-levels.frag`), so a tree crown's
gaps stay gaps; `GpuShadowLight.levels` writes them once a map revision, at High only (the Shadows choice changes
only the map's resolution). Widths are per map axis
(`shadowTexels`), as the fit stretches the square map's texels along one. Both look the shadow up out along the face's
normal (`shadowLookup`), so a face lit at a grazing angle never shadows itself, and a face turned from the sun that
neither scatters nor transmits light reads none. The three-texel tent reads six by six texels, nine blocks of two by
two: the centre and its four edges lit, against the highest depth any tent compares there, is lit (the corners, a fifth
of its weight, unseen); all nine shadowed against the lowest is the umbra, exactly, without the tent. When the frame's
widest penumbra (a caster at most the casters' slab away) passes three texels, the coarse level whose blocks span it must
agree: its five for lit, looking only for casters farther than the three-texel tent shows (nearer ones the finest blocks
saw, and a receiver's own plane across the coarse blocks would read as one), and its nine for the umbra. Elsewhere five blocks at the receiver's depth and nearer the sun
tell how far the caster stands, on the finest level, or on the coarse one where the finest find none: a far caster's
penumbra reaches past them. They sit on the pixel, not on the block grid, so the width changes smoothly along an edge,
and probe at a twentieth (only past three texels) and every fifth of the distance whose penumbra fills the frame's
widest, each past a blocker at the one before; the caster's distance is the area under the share still blocked, joined
by straight lines: a few hexes up close and many zoomed out, so a tower's long
shadow under a low sun is crisp at its foot and soft for hexes toward its tip. The fog's light shafts read the finest
level. No second sampler
reads raw depth. On
sculpted ground the lookup rises with the visible grain (its height map, `RELIEF_METRES` deep), so raised grains break
a shadow's edge, and the cavity shades hollows first where the sun grazes. A shadow's edge follows what casts it: grass,
plants and stones break it with their own geometry, nothing shifts the lookup.

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
Windows and Linux drivers (32 units) accept it. A fragment stage keeps to 13, which
leaves room for new features. Add maps as layers of an existing array of their format
and size, read at the same texel density:

- `u_terrainLayers` and `u_terrainData`: the sculpted surfaces.
- `u_fullSizeColor` and `u_fullSizeData` (`GpuAssets.fullSizeMaps`, full-size-maps.glsl):
  the 1024-texel maps of the tundra carpets, ice and both lavas. A board that reads any
  of them loads them all.
- `u_roadColors` and `u_roadData` (`GpuAssets.roadArray`): the road materials, and the ice
  sheet again, so ice on a road coat takes no other unit.
- `u_surfaceNoise` (`GpuWaterShader.surfaceNoise`, surface-noise.glsl): the water detail
  and the rain noise, whose 64 texels repeat four times across their 256-texel layer and
  are read at a quarter of the coordinates, so reads and mips stay those of a 64-texel texture.

The order of both pairs' layers is `GpuAssets.FULL_SIZE_PLAN` and `ROAD_PLAN`, which build the
arrays and write the layer numbers the shaders read (`GpuAssets.carpetLayers`, `iceLayers`).

Exact-texel tables can share a texture: `u_lavaLights` also holds the board's hex table
(`GpuLavaLighting.setHexes`, `boardHex` in terrain-hexes.glsl). Procedural water compiles
out the authored colour's sampler. The on-demand `GpuSamplerBudgetSmokeTest` renders the
battle view over volcanic, rain, snow, fog, river, highway and hazardous-liquid boards and
an editor, whose biome stage and crystal stage add to every program, fails on a fragment
stage over 13 or a program over 16, and prints every variant's fragment count. At the
budget are road ground and cached ground with ambient occlusion, and the editor's sculpted
ground; water's fragment stage reads 12. Two kinds of program are
exceptions: at High quality with ambient occlusion and the crystal stage (an editor, or a
board with crystals) road ground reads 14, since its 1024-texel road maps and 512-texel
sculpt maps need an array pair each, and cached ground on a board with biomes reads 14,
since its pages stay in three arrays: one array for moments and detail would halve the
pages a driver's layer limit allows, and a wide 4K view needs more than that.

A shader edit normally replaces programs and invalidates affected render caches;
it does not change game state, terrain support or picking. Change Java geometry
when the intended result alters silhouettes or contact surfaces. Programs,
textures and previews must be installed and disposed by their GL-thread owner.
