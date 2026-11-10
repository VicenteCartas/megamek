# Terrain updates, detail and render caches

Most rendering work is coordinated by `GpuTerrain`, but each cache has a different
owner and invalidation boundary. Use this guide when a view rebuilds too often,
stutters during edits, or draws stale geometry.

For the realism scope and the user's decisions, start with
[terrain realism](gpu-terrain-realism.md#scope-and-decisions).
Ordinary flat tops use six-triangle fans. The
[landscape review](gpu-terrain-realism.md#native-reviews) renders five shipped
landscapes in the main renderer's top and isometric views and times settled
terrain frames separately from loading and whole-game FPS.
Older dated reports below apply to their recorded revisions and fixtures.

## Quality: Low, Medium and High (2026-11-05)

Display → Quality chooses the level; `BOARD_QUALITY` defaults to Medium and a saved choice stays. Each level is a row of
the `BoardQuality` table and code reads its columns, never which level it is: whatever else comes to differ between levels
gets a column there. Quality leaves the render resolution to its own slider. Low and Medium share their programs, so a
switch between them links nothing; a switch to or from High rebuilds the shaders behind the loading stage. High's shaders
are unchanged: `mediumQualityFlag` is off, and the only edits in its path rearrange the same arithmetic.

Low is Medium without the meadow's grass and rims, and with 75% of a field's plants, each wider by the root of that; the
Grass blades option keeps its choice, disabled. Medium, against High:

- **Options off, choices kept:** high shadows, specular anti-aliasing, parallax, tessellation and AO
  (`BoardDisplaySettings`). Their controls are disabled; High brings the player's choices back.
- **Light:** no environment table reads (analytic split sum, mobile GGX, fallback sky at .9), reflection reusing the
  face's sky unless wet or metallic, one bilinear sun shadow comparison, thin tissue's far side lit by the ground.
  These pay together: reverted one at a time each looks free, reverted as a set they cost about 1 ms of 9.
- **Ground:** first repeat alone except on desert, where a single repeat reads busy; first-repeat normals; mean finish;
  the second role keeps the first's normal; only the strongest cover in full, the others one colour read; a family
  program's blend folds the families outside its palette (`isFamily`) and reads a repeated slot once; a bank's second
  cover reads one layer per role the first cover's bank shows there (ground at crest and foot, soil or rock on the face),
  so it meets the level cover beside the bank, and a soil river bank's is one layer of the bank's soil; rim and
  two-level arithmetic skipped where it is exactly zero (`IF_MEDIUM`); wetlands with two noise octaves and no warp.
- **Geometry and animation:** `TerrainLod` takes each level at 3x the hex size; relief depth twins only below 96 px a
  hex (`TreeLod.PREPASS_RELIEF`), needed because AO's pre-pass is gone and hidden relief would be shaded; 60% of the
  meadow's blades, survivors by the root of that wider; waves every other frame, none while no water is in view;
  terrain anisotropy x2.
- **Plants on coarse chunks:** at 3x the hex size a chunk asks for medium detail at about 79 px, while grass shows from 120 and
  takes a rebuild of 1-2 s per chunk to arrive. Coarse chunks therefore carry grass, rims, crops and medium's reeds
  (`BoardPlants`, `BoardVegetation.carriesGrass`), with no grass on a level that draws none. A first zoom shows no bare ground
  (without them 2.0-2.4 s on a 16x17 field board, 2.9-3.0 s on 64x34 Grasslands) and holds about the grass memory High does
  (120 MB at load on 64x34). Load on a quiet machine, three runs each: 8.0-10.8 s against 9.8-11.2 s on the field board,
  13.0-14.8 s against 11.5-15.3 s on 64x34. A level switch on an open board keeps each chunk's plants until it is rebuilt.
- **Rocks on coarse chunks:** coarse chunks on such a level also carry the rim and fallen rocks, waterfall rocks and planting
  pits (`TerrainLod.formations`), in the places every detail gives them, so a first close zoom shows them at once; the coarse
  ground itself still refines about 3 s later. On Iris Xe at 1920x1080, terrain cache off: Kozice Valley's coarse surfaces
  rise from 144,856 to 218,364 triangles with no measurable overview cost (20.6-21.6 ms against 20.8 ms), 48x48 The Trainjob's
  from 1,029,131 to 1,169,240 with at most about 1 ms (23.9-25.2 ms against 25.2-25.8 ms); loads unchanged.

Measured on an RTX 4070 Laptop at 3840x2160 and 100% render resolution, terrain cache off, Enhanced,
`GpuBoardPerformanceSmokeTest` with `performanceCompareQuality` (`performanceComparedQuality` for Low): High, the level, the
level, High in one process, 60 warm-up and 120 sampled frames per view. Median frame-interval gain against High, overview /
close / moving:

| Board | Medium | Low |
| --- | ---: | ---: |
| 32x17 Kozice Valley (CDS) | 1.69 / 1.85 / 1.79 | 1.73 / 1.93 / 1.91 |
| 16x17 Fire And Ice 2 | 1.51 / 1.55 / 1.56 | 1.59 / 1.64 / 1.66 |
| 48x48 The Trainjob | 1.62 / 1.63 / 1.69 | 1.69 / 1.65 / 1.68 |
| 96x102 MesaCity1 | 1.16 / 1.58 / 1.59 | 1.20 / 1.56 / 1.67 |
| 16x17 Grassland 2 | 1.58 / 1.70 / 1.75 | 1.69 / 1.89 / 1.91 |

High there carries AO on and parallax on, a player's settings. Against the game's defaults (AO off), Medium gains: Kozice
1.65 / 1.66 / 1.72, Fire And Ice 1.53 / 1.55 / 1.55, Trainjob 1.50 / 1.52 / 1.60, MesaCity 1.05 / 1.45 / 1.39.

Limits:

- Neither level reaches x2 at equal resolution. The table was measured before plants on coarse chunks and before cutouts' faded texels.
- On a quiet machine MesaCity is GPU-limited in Medium: 7.7 ms overview, 7.6 close, 7.2 moving, gain 1.50 / 1.56 / 1.57, faded texels included. The CPU's share of the
  overview is about 6.2 ms: 1.1 walking and sorting, 3.8 submitting 1,150 opaque draws and the pre-pass (about 35 GL calls a draw), 1.4 the rest. A loaded machine inflated those about 4x.
- MesaCity's CPU limit, measured with the scene target at a quarter of 4K and the same draws: Medium 5.7-5.9 ms overview, 2.7 close, 3.3-3.5 moving; High 7.1, 3.3, 3.6. Moving frames in Medium spike: worst 1% 11-13 ms, slowest 21-25 ms, cause not established.
- Cutouts' faded texels (`GpuCutoutFade`) cost 0.2-0.5 ms a frame at Medium on these boards: Kozice 5.25 to 5.6 ms overview, 8.15 to 8.6 close, 7.95 to 8.5 moving; MesaCity 7.55 to 7.7, 7.35 to 7.6, 6.9 to 7.2.
  Their targets take 10 bytes a pixel (calculated: 83 MB at 4K), allocated with the first faded draw.
- GPU time misleads on CPU-limited views: the GPU idles, its clocks drop and even untouched stages slow by up to 1.8x.
  Normalise by the depth and composite stages, or trust only GPU-limited views. The first phase of an A/B runs slow.
- Look: Medium's fields fade to their flat stand colour by 120-72 px a hex (High 40-24); COARSE also drops rim rocks below about 72 px a hex; meadows are sparser and up to 2.6% brighter
  in mean display value; shadow edges are crisper. Cached tops keep High's material: fills take no lighting flag.
- Not measured on Iris Xe. Texture reads cost little on this NVIDIA GPU and arithmetic costs more, so the mix may differ.
- `TerrainLodTest.waterFieldDoesNotIntroduceASeamBetweenDetailLevels` fails identically at the prior commit.

## Citadel material cache diagnosis (2026-11-02)

Display → Terrain cache has an inline **Debug** switch. It starts off for each view,
is never saved, and remains usable with the cache disabled. It tints sculpted ground
and cliffs by the material actually submitted: red means direct rendering; cached
levels 0–6 are green, cyan, blue, violet, magenta, yellow and orange. Water surfaces,
objects and other passes are not tinted, although tinted beds show through water.
The tint is applied after material evaluation and never written into cache fills.
The Display panel includes a legend and counts of terrain chunks actually using
the cache. “Admitted pages built” measures completion of admitted texture pages,
not the fraction of the board rendered from them.

With the default 0.1 m base texel, each level doubles the texel's world size.
The 240×240 usable texels of a page cover 24×24 m at L0, 96×96 m at L2, and
1,536×1,536 m at L6. Every page has the same storage cost, about 0.984 MiB for its
three material arrays and three mip levels. A frame selects one cache level to
match its pixel footprint; the colours do not indicate separate caching systems.
These are alternative resolutions, not seven successive rendering passes. Fewer
levels would make the resolution and memory changes between levels larger.
For equal world coverage, L0 needs nominally 16 times the texture storage of L2,
or 4,096 times that of L6, before page-edge rounding. Keeping Citadel entirely at
L0 would therefore require several GiB for material pages alone, while the far
view cannot display most of that detail.

### Initial reproduction and measurements, before admission changes

The full-view benchmark loaded `data/boards/buildingsnobasement/citadel.board`
(50×50) on an NVIDIA GeForce RTX 4070 Laptop GPU. Its physical framebuffer was
3840×2259, render scale 100%, Enhanced graphics, AA off, specular AA and AO on.
VSync and the application's FPS limiter were disabled. The camera readout matched:

`cam ortho fov 45.0 az 45.00 tilt 54.74 span 2977.81 at 1568.98,-1818.00,-54.00 hex 2727`

The fixture includes one Atlas and the game HUD, with a fixed clear 13:00 atmosphere;
it is a hidden window with the screenshot's framebuffer dimensions, not the user's
original fullscreen/editor session. Each variant settled its geometry and admitted
cache pages, warmed for 60 frames, and sampled 180 frames without `glFinish`.
GPU timestamps were collected separately from frame intervals. Debug captures,
draw counting and 32-frame shader-family attribution ran outside those samples.

| Allocation / toggle | Admitted view pages | Cached top / wall batches | Median GPU frame | Median opaque terrain | Average FPS |
|---|---:|---:|---:|---:|---:|
| 94 slots, off | — | 0 / 0 | 13.81 ms | 10.07 ms | 66.1 |
| 94 slots, on | 86 / 421 | 0 of 4 / 0 of 4 | 14.03 ms | 10.22 ms | 68.0 |
| Automatic 552 slots, off | — | 0 / 0 | 14.12 ms | 10.35 ms | 69.1 |
| Automatic 552 slots, on | 421 / 421 | 4 of 4 / 4 of 4 | 11.17 ms | 7.22 ms | 86.9 |

The 94-slot run used `-Dmegamek.gpu.materialCacheMiB=93` to reproduce the screenshot's
92.5 MiB page storage and 97.2 MiB estimated total allocation. The automatic run
allocated about 543 MiB of pages. Requested coverage was 421 pages in this fixture
versus 420 in the screenshot. Cross-run FPS is affected by CPU scheduling and GPU
clocks; the limited-cache GPU measurements and identical draw paths demonstrate
no useful rendering benefit, rather than a meaningful 66-to-68 FPS improvement.

### Why the original toggle did nothing at 94 slots

The original `GpuMaterialCache.plan` reserved eight slots, then admitted top pages
nearest the view centre before wall pages. At capacity 94, 86 pages fit out of 180
requested top pages and 241 wall pages. Its final selection required every needed page of a terrain
mesh batch to be readable before any of that batch's tops (or walls) draw cached.
Citadel spans four such batches. None is completely covered at this budget:
`cached=[]`, `wallsCached=[]`, despite every admitted page being current. The debug
capture is therefore red throughout. This is an admission/batch-granularity mismatch,
not a failure to finish filling the admitted pages.

Opaque terrain is the main GPU cost: approximately 10.2 ms of a 14.0 ms frame.
The colour pass submits 369 draws and 7.53 million indexed vertices before any
tessellation amplification; the depth pre-pass submits another 7.06 million.
At the limited budget, depth takes about 1.1 ms, transparent effects (including
water) 0.8 ms, AO 0.35 ms and the atmosphere composite 0.52 ms. Cached geometry
shadows are negligible in this stationary view. Shader-family attribution also
identifies direct sculpted grass tops/walls and mixed ground as the large costs.
The cache replaces material evaluation, not geometry, lighting or these other passes.

Automatic capacity uses half of driver-reported free GPU memory when allocating,
subject to texture-array limits and the scene's estimated need; a startup MiB
override replaces that budget. Originally the memory limit stayed fixed until reallocation.
The screenshot alone cannot establish why its initial capacity was only 94 slots.
These initial results motivated the admission and allocation changes below.

Reproduce with `gpuBoardBenchmark --tests '*GpuBoardPerformanceSmokeTest.measuresTheCompleteBoardViewWithLiveCapture'`
and these `-Dmegamek.gpu.` properties: `performanceFullView=true`,
`performanceBoard=data/boards/buildingsnobasement/citadel.board`,
`performanceOverviewOnly=true`, `performanceCompareMaterialCache=true`,
`performanceWidth=3840`, `performanceHeight=2259`, `performanceSpan=2977.81`,
`performanceAt=1568.98,-1818.00,-54.00`, `performanceWarmupFrames=60`,
`performanceSampleFrames=180`, `performanceNoFinish=true`, `performanceFixedAtmosphere=true`,
`performancePinnedSettings=true`, `performanceAmbientOcclusion=true`,
`performanceGraphics=ENHANCED`, `performanceFamilyFrames=32`, `performanceDrawAudit=true`,
`performanceSettleSeconds=300`, `card=DISCRETE`, `expectedRenderer=NVIDIA`.
Add `materialCacheMiB=93` for the limited run. Local logs and captures are under
`work/citadel-cache/`, with the two benchmark logs at `work/citadel-cache-profile.log`
and `work/citadel-cache-limited-profile.log`.

Validation passed all 16 selected native tests: the complete `GpuTuningPanelSmokeTest`,
both `GpuMaterialCacheSmokeTest` cases, and the new material-debug rendering test.
They cover the inline control at three window sizes, a fresh view starting with
Debug off, red direct rendering, green L0 and blue L2, byte-identical restoration
when Debug is disabled, and fills containing no diagnostic tint. Checkstyle and
Spotless passed for the changed Java files. The final run compiled into an isolated
directory and reused the already-staged assets to avoid shared build-output and
mapped-font conflicts; its log is `work/citadel-cache-isolated-final.log`.

### Coverage and allocation improvements

The planner now admits all required pages of a chunk's visible tops or walls
together, charging shared pages only once. Readable regions retain priority while
visible; remaining regions are ordered by distance, with tops and walls competing
together. A region that cannot fit is skipped in favour of another complete one.
Wall regions outside the page table are excluded before admission, and an
unaddressable top window does not spend time building unusable pages.

Material selection uses chunk IDs independently of the 4×4-chunk mesh pages.
`GpuMeshPage` selects cached/direct materials over its existing source index
ranges, merging adjacent ranges with the same material. Mixed coverage adds draws
where needed; complete coverage retains merged draws. Camera motion and changing
cache coverage do not rebuild buffers or mutate the original materials. Unmerged,
oversized and replacement sources use the same material selection.

The initial allocation still uses the render-size estimate. Later growth follows
observed demand plus 25% for prefetch and eight rebuild slots, retaining capacity
across camera movements at the same render size. A driver-limited shortfall lasting
300 unchanged, settled frames triggers a fresh memory query. The query credits the
current arrays before taking half the available pool. Growth must be at least 25%
(or eight slots), or sufficient to fit the view; a changed allocation target must
then persist for 30 frames. Fixed configured/default budgets do not grow through
this reassessment. Temporary decreases do not evict a useful allocation; a settled
render-resolution change can resize it. Display reports the reason for capacity:
driver/configured/default memory budget, array limit, board extent or view estimate.

The native regression fixture exercises limited coverage and recovery from a
controlled low driver budget. The mesh-page fixture compares mixed-material pixels
against unmerged draws, checks merged draw counts at full coverage, then replaces
and hides sources and checks that coverage changes never rebuild geometry.

The final overview-only comparisons retain the camera and framebuffer above and
wait for geometry, admitted cache pages **and mesh-page merging** before timing.
The larger run explicitly uses `materialCacheMiB=544` to hold capacity at 552 slots
rather than let other applications change the budget. Each row is an independent
off/on pair with identical submitted terrain geometry.

| Capacity | Cached top / wall chunks | Median GPU frame, off → on | Median opaque terrain, off → on | Terrain draws, off → on |
|---|---:|---:|---:|---:|
| 94 slots | 4 / 49 tops; 6 / 36 walls | 13.63 → 13.17 ms | 9.90 → 9.46 ms | 381 → 383 |
| 552 slots | 49 / 49 tops; 36 / 36 walls | 13.86 → 10.85 ms | 10.06 → 6.87 ms | 387 → 387 |

The small allocation still admits 86 of 421 requested texture pages, but now those
pages enable real cached draws. Its GPU-frame reduction is about 3.4%; the larger
allocation's is about 21.7%. The small budget does not make close views broadly
cacheable: the separate close/moving diagnostic stayed near 21/23 ms GPU time with
either toggle. Moving GPU p95 was 24.46 ms off and 24.32 ms on, while median CPU
submission rose from 12.24 to 14.96 ms as pages were planned and filled. These
results support an overview improvement, not a general close-view or motion gain.

Final validation passed 18 native tests (all tuning-panel, material-cache and
terrain-page tests, plus the debug rendering check), the mesh-page unit test, and
Checkstyle/Spotless. The memory fixture also verifies that reducing render
resolution releases excess storage. The benchmark's `performanceCacheCoverage`
property asserts `partial` or `full` overview coverage as appropriate. Native
pixel comparisons cover mixed materials, culling, source replacement and ownership.

Reproduction commands and captures are under `work/citadel-cache-improve/`:
`benchmark.ps1 -Label limited-settled -OverviewOnly` and
`benchmark.ps1 -Budget 544 -Label full-settled -Coverage full -OverviewOnly`.
The corresponding `*-profile.log` files contain GPU timestamps, draw audits and
coverage reports; the same-named directories contain normal/debug screenshots and
timing tables. `final-checks.log` and `benchmark-style.log` record final verification.

### Hex-label batching

Cosmetic scatter does not change the captured hex elevation. Its visual height
previously split the labels at one elevation into separate font groups, because
the relief allowance was a uniform for each group. `GpuHexText` now carries that
allowance per glyph in the unused third component of its existing owner attribute.
In this first measured change, the vertex size remained 36 bytes and groups retained their elevation and overlay mode;
outline/text ordering, atlas-page ordering, chunk culling and bounded index buffers
are preserved. Camera framing, indirect-fire arc clearance and effect ray hits
retain their previous scatter behavior.

That native label regression included 576 tiles with different scatter heights:
one atlas required just two draws (outline and ink), with exact pixel parity
against the font-cache reference. Existing checks cover relief, foreground cliffs,
unit occlusion, roof labels, adaptive contrast, filtering, incremental atlas pages,
offscreen culling and resource disposal. The contrast fixture explicitly selects
its original .2 threshold, because its history samples assume that value rather
than the current .04 application default.

Citadel before/after on the RTX 4070 Laptop GPU, 3840×2259, ENHANCED, cache off,
normal maps and tessellation on, parallax off: 60 warmup and 180 measured frames,
without `glFinish`. The overview uses the exact camera above; close uses span
1129.50 at (1600.16, -1872, 0), with the same azimuth and tilt.

| View | Label draws, before → after | Median label CPU, before → after | Median label GPU, before → after |
|---|---:|---:|---:|
| Overview | 1,050 → 442 | 1.58 → 0.56 ms | 0.081 → 0.071 ms |
| Close | 707 → 312 | 1.04 → 0.61 ms | 0.097 → 0.097 ms |
| Moving close | 722 → 318 | 0.99 → 0.38 ms | 0.099 → 0.098 ms |

This removes CPU submission work, not the close view's terrain GPU bottleneck.
Complete close GPU time was 20.62 → 20.54 ms, with identical terrain submissions
(1,408 draws and 7,115,046 indices). The merged groups' conservative chunk bounds
submitted 96,876 label vertices instead of 93,894 near the viewport edge; their GPU
time stayed the same. Overview frame intervals were 14.30 → 14.31 ms. No overall
FPS gain is claimed from this pair. Other stages' CPU time varied between runs.

The isolated original/fixed comparisons, timings and captures are
`work/citadel-fps/hex-before.log`, `hex-after.log` and their matching directories.
`hex-test.log` records both passing native label suites; `final-style.log` records
the scoped Checkstyle and Spotless checks. The stage profiler now separates hex
grid, hex labels and later overlay labels from the surrounding tactical overlays.

### Instanced labels and Tidewater loading

The next text change combines elevations, outlines, ink and font atlas pages in
ordered instance buffers. Each glyph takes 64 bytes, replacing four 36-byte
vertices and six indices. A batch borrows up to eight existing font textures;
it does not copy or repack their images. One shared quad draws the instances,
with a 262,144-glyph limit per buffer. Chunk visibility changes compact the
visible instances in their original order; stationary frames upload nothing.
Mip gradients are calculated before selecting a font sampler. Relief allowance,
roof and unit occlusion, contrast, atlas updates and outline order remain intact.

In the approximate Tidewater screenshot view (isometric, span 1900, centre
5616/-5256/18, 3840×2259, ENHANCED), labels went from 292 draws to one. Median
label submission was 0.471 ms before and 0.137–0.156 ms after; GPU label time
was 0.161 ms before and 0.145–0.148 ms after. The whole view remained around
21–22 ms per frame. These label results remove CPU work; they do not establish
a corresponding whole-frame FPS gain.

Citadel also uses one label draw in the original overview, uncached close view
(span 1129.50, centre 1600.16/-1872/0), and moving view. Against the preceding
headroom-batched implementation, median label CPU submission was respectively
0.559 → 0.065 ms, 0.611 → 0.070 ms, and 0.375 → 0.055 ms. GPU times remained
about 0.06–0.09 ms. These runs use the same 3840×2259 NVIDIA settings with the
terrain cache disabled; `work/tidewater/citadel-instanced.log` contains the
camera, draw-count and timing reports. They establish a label CPU improvement,
not an isolated whole-scene FPS comparison against other concurrent changes.

The impassable label is part of that same text batch. Its red border and stripes
share retained tactical geometry, packed into pages of at most 10,000 triangles,
not one draw per hex. Citadel fits all those markings into one page. The ordinary
grid is an instanced draw in top view; relief also evaluates its grid in the
terrain/cover shaders, without an additional terrain draw. Other tactical layers
can use more pages, materials and passes; they are not all one global draw.

The isolated `terrain markers` stage in `work/tidewater/citadel-markers-top.log`
measured the following medians at 3840×2259 on the RTX 4070 Laptop GPU, ENHANCED,
terrain cache off. Each entry used one draw in overview, close and moving views:

| Top-view pass | Close CPU | Close GPU | Moving GPU |
|---|---:|---:|---:|
| Impassable borders and stripes | 0.040 ms | 0.020 ms | 0.017 ms |
| Hex grid overlay | 0.032 ms | 0.109 ms | 0.115 ms |
| All hex labels | 0.073 ms | 0.065 ms | 0.070 ms |

These overlays were not the limiting passes: the close view's opaque terrain
took 14.545 ms GPU, against 17.592 ms for the complete GPU frame and a 17.701 ms
median frame interval. Opaque terrain submitted 907 draws and 4,762,731 indices
(before instance multiplication or tessellation). These are steady rendering
measurements; changing an impassable flag, highlighted hex or terrain geometry
can rebuild retained marker pages and is not covered by these pass medians.

Increasing texture-array capacity reproduced the loss of cache coverage shown in
the screenshots: it discarded every existing page. Growth now copies every
layer and mip, retaining page ownership, content generations, stale pages and
unfinished builds. The copy preserves encoded sRGB bytes and GL framebuffer,
scissor, colour-mask and sRGB state. Shrinking after a real render-resolution
change can still release storage. Memory-limited partial coverage remains valid:
unadmitted terrain uses the direct renderer.
The exact focus/unfocus sequence was not reproduced. Fluctuating driver memory
reports can request the capacity growth whose destructive behavior was reproduced.
The independent review also found that a failed growth allocation could close
the view. A failed allocation or mip copy now keeps the old readable arrays and
falls back to their capacity; the existing budget retry delay prevents retries
every frame. A native injected out-of-memory test verifies continued rendering,
the retry delay and later successful growth.

Tidewater's initial CPU profile placed most terrain preparation work in repeated
road-path sampling for road masks. Immutable bounds over consecutive segments
now let the shared `BoardRoad` sampler visit nearby branches first and discard
branches unable to improve either centre-line distance or road-edge distance.
Exact segment arithmetic and original ordering for equal-distance ties are
retained, including variable widths and open ends. Both road masks and scenery
clearance use this one implementation. A chunk builds each road's paint
(`GpuTerrain.roadPaint`) once and shares it between the hexes it reaches. A mask is
told from every other before it is rendered by its outline and by its colours at a
10x10 grid of points, from the code that renders it (`GpuRoads.maskKey`), so roads alike
anywhere on a board share one rendered mask, and nothing has to keep track of what a
mask reads. A difference falling wholly between the grid's points would go unseen. All
1,192 corpus boards with roads were rendered mask by mask (945,802 masks, 8,605 keys):
one key never gave two masks. Frames of 7 boards in both graphics and 5 views match the
code before this within what that code differs from itself (at most 36 pixels of 1.6
million, 10 of 255). Over whole boards in Classic the road stage runs 3.8-4.5 times faster
(Valencia 25 s to 6 s); GL upload and a real load were not measured. Do not time this code under `:megamek:test`: JaCoCo,
which that task attaches, makes the stage about 5-8 times slower. The 24×24 Tidewater region at (80,56)
retained checksums `038805db` (COARSE) and `047be856` (FULL) before the distant
wear-mask change below, covering geometry and every generated mask pixel.
A separate numerical regression compares bounded
and exhaustive sampling, including path endpoints and degenerate segments.
The pavement wear mask now shares one finite-curve search between wheel heading
and lane position. Endpoint projections are also shared, evaluating a second
distance only where the bounded and extended positions differ.
Unbounded road ends are sampled before the finite bounds rather than preventing
those bounds from pruning. Adjacent workers also share an in-progress road-mask
build; reload generations, failures and a cancelled request cannot poison the
shared result. The numerical and concurrent-sharing tests cover these boundaries.
Large masks split independent row groups within the existing terrain worker pool,
with captured settings and a private colour scratch per group; they allocate no
additional executor. Serial/parallel pixel parity is covered separately.

Only the broad asphalt wheel-wear field now uses one sample per tile pixel at
COARSE/DISTANT, instead of four in each axis. Paint, thin markings, other road
materials, and MEDIUM/FULL retain their original resolution. Repeating albedo,
normal and roughness maps keep their full resolution at every level. Mask density
is part of the sharing key, so a distant mask cannot satisfy a close-detail build.
The final regional COARSE checksum is intentionally different (`53a7c443`);
FULL remains exactly `047be856`. Regional preparation measured 5.330/5.375 seconds
for COARSE/FULL, against the original 18.089/14.916 seconds on four workers.

Native road captures compare six exit patterns in top/isometric views at 26.3
projected pixels per hex (near the default COARSE refinement boundary), with
three subpixel pan positions, then refine to MEDIUM and zoom back. All twelve
MEDIUM/return frames are pixel-identical to the full-mask reference. The six
COARSE frames differ at road edges: their largest RGB-channel difference is
27/255, with 84–224 pixels above 8/255 in each 960×720 image. These are small
screen-space differences, not pixel parity or a guarantee for every lighting
condition. Thin markings remain intact in the captures. The paired images and
comparison are in `work/tidewater/masks-visual-{coarse,full}/` and
`masks-visual-comparison.txt`.

The map also waited behind the loading screen for optional cache baking after
all geometry and shaders were ready. Cache prewarm now gets a 250 ms head start
(plus the current fill step), then interactive frames continue the build within
the existing frame budget. Unfinished cache regions draw complete direct terrain.
Camera-specific detail refinement continues after opening. The benchmark records
the first usable map separately from settled geometry and cache timing.
The temporary close-up used to discover startup shaders no longer queues terrain
refinement or bakes cache pages for its camera. Grass shaders prepare from their
actual blade layout without planting a hidden full-detail chunk. Ordinary zooms
still refine the same terrain and restore its detailed geometry.
The cancellation/reopen smoke test also exposed `ForkJoinPool.close()` returning
after termination started. Disposal now waits for actual worker termination
before releasing the chunks and assets those workers borrow.

The subsequent load profile exposed repeated scans of unchanged plant meshes:
impostor material setup recalculated the source bounds for each placement, and
each new placement scanned them again. Impostor setup now runs once per source
material. A chunk build shares local bounds for fresh instances of the same
model, copying the box before applying each placement transform. Building
assemblies retain their instance-specific calculation. The cache dies with the
chunk build; it owns no model resources. The native regression checks sixteen
differently rotated/scaled plants against exhaustive vertex bounds and verifies
that their world boxes are independent. The independent reviewer found no issue
with either lifetime or the boundary before placement-specific node changes.

Complete Tidewater opening now measures 58.366 / 59.060 / 64.848 seconds, compared
with 149.369 / 149.939 / 152.359 seconds for the original control. The median
falls from 149.939 to 59.060 seconds: **60.6% less loading time**. Each measurement
starts before board loading and scene capture, and ends after the first actual
map/HUD frame (`GpuBattleView.frames() > 0`), not merely `terrain.ready()` or a
loading-screen frame. Gradle/JVM startup before capture is excluded. These are
fresh JVMs with installed asset/shader caches, not deliberately cold filesystem
runs. The control overrides the original road sampler, file lookup, road-mask
builder, hidden startup view and unbounded cache prewarm; other production
classes are shared with this working tree.

The native runs use the RTX 4070 Laptop GPU at 3840×2259, ENHANCED, normals,
tessellation and AO on, parallax off, VSync off, cache enabled. The first visible
map is the initial isometric FIT view, span 8734.76, at 4978.05/-5038.17/-36;
it precedes the benchmark's requested screenshot camera. Optional cache baking
continues after that frame with the direct terrain fallback. This is an opening
result, not a fully warmed cache or a close-view FPS measurement. Logs are
`work/tidewater/visible-{before,control-before,isolated-original}.log` and
`visible-bounds-final-{1,2,3}.log`; `final-opening-times.json` retains all six
capture-to-visible durations. The first-map PNGs show the actual board and HUD.

Building-file resolution is centralized in `TilesetScenery`, with one shared
result per asset and data-directory/reload generation. It retains exact GLB,
design GLB, generated JSON and legacy fallback behavior. `Reload Assets` clears
positive and missing-file decisions; changing the data directory replaces the
cache. Generated-kit variant metadata retains its modification checks.

The controlled Urbanruins comparison uses the same current production classes
except for original versus optimized `BoardRoad`/`TilesetScenery`. Java 21,
headless, 4 GiB generational ZGC, one unrotated 50×50 sheet: first scene capture
was 8.855 → 3.800 seconds; median of three warm captures was 3.384 → 1.158 seconds
(66% lower). All 2,500 tiles and 8,356 features retained the same asset and
placement checksum `db925a41f4b9993e59afa73151ca2648a91f8602c8ea75c29b87be1ff2182c1b`.
This CPU capture measurement excludes GPU preparation and is separate from map
opening time. The original handoff evidence remains untouched under
`megamek/build/urban-conversion-profile-20261102`; controlled runs and the harness
are under `work/tidewater/urban/` and `work/tidewater/UrbanLoadProfile.java`.

A later controlled capture profile, with the same feature checksum, reduced
sampled road-distance stacks from 150 samples to below the top 22 stacks; native
file-attribute leaf samples fell from 45 to 8. The profiled capture was 3.346 →
2.545 seconds; two subsequent unrecorded captures were 2.719/2.709 seconds before
and 1.359/1.095 seconds after. Sampling counts are diagnostic rather than exact
time attribution. `urban/final-profile-summary.txt` retains both profiles;
remaining capture costs include tree coverage work outside this change.

Validation for this stage passed 22 focused tests: 13 native tests covering text,
cache growth and GL state, startup, placement bounds and worker cancellation/reopening; nine CPU
tests covering exact road sampling, concurrent mask sharing, parallel pixel
parity, distant mask coverage and asset lookup/reload. Before the distant-mask
change, the exact-pixel road-region comparison measured COARSE preparation
18.089 → 9.138 seconds and FULL 14.916 → 6.240 seconds on four workers.
Scoped Checkstyle and Spotless passed.
These are not an all-project test result. Two wider road fixtures fail equally
with the original road implementation: the shared-roundabout curb assertion and
the rough/woods tree-count assertion. Their original-code reproduction is in
`work/tidewater/original-road-regressions.log`; focused results are in
`final-tests.log`, `shared-ends-tests.log`, `distant-wear-tests.log`,
`cache-growth-failure-tests.log` and `placement-bounds-tests.log`.

### Natural board-edge cuts (2026-11-02)

The two additional Citadel reproductions use the supplied orthographic cameras
at 3840 × 2259 on the RTX 4070 Laptop GPU, with the same Enhanced settings as
above and a 2048 MiB cache cap (2048 physical layers, 2016 MiB page storage):

| View | Azimuth / tilt | Span | At |
| --- | --- | --- | --- |
| Wide | 328.79 / 58.03 | 2373.90 | 1446.68, -2273.15, 94.80 |
| Closer | 26.16 / 47.94 | 1073.83 | 2092.52, -2967.17, 54.00 |

The original wide view reproduced all 1273 requested pages admitted, yet red
board cuts, curved slopes and mud remained. This was an eligibility limit:
charts skipped off-board neighbours, and wall admission rejected every fixed
texture projection, including the natural board cut. The progress percentage
counts requested eligible pages, not every visible terrain face or pixel.

Natural board cuts now use the existing wall charts down to the renderer's
actual board floor. Their fixed texture projections remain in the vertex data
used by both the cache fill and direct fallback. Indexed chart vertices also
include their complete shade in their sharing key, retaining distinct face
normals and other shading attributes at coincident positions. Floor changes
participate in the existing chart-layout/reuse check; chart layout remains
independent of geometry detail. No terrain geometry or texture resolution is
reduced by this addition.

Cached and direct output is not pixel-identical. On the newly cached border
interiors (one-pixel silhouette margins excluded), mean absolute RGB error was
2.24/255 in the wide view and 1.53/255 in the closer view. The 95th percentile of
the largest channel difference was 7/255 and 5/255 respectively; maxima were
39/255 and 14/255. Native-resolution and enlarged side-by-side crops showed
small fine-texture/shading variation without a noticeable detail loss or new
seam in normal viewing. These are checks of the two supplied poses with parallax
off under the fixed clear-day lighting, not a guarantee for every view and
lighting condition. `board-cut-comparison.png` in each capture directory keeps
the original pixels; the `-2x` image uses nearest-neighbour enlargement.

The wide view admits 1632 pages with all 47 visible wall chunks using the cache,
up from 1273 pages and 36 wall chunks. The closer view admits 1600 of 1677 pages,
with 16 of 19 wall chunks cached. Its rectangular wall address table excludes
77 pages (previously 4), leaving a small far-right border section direct despite
available physical slots. This existing address-range limit is separate from
VRAM capacity; adding more eligible walls does not remove it.

Mud and other unsupported biome cover, including its neighbouring blend area,
still draw directly. So do curved tessellated faces and concrete panels (whose
evaluator modifies occlusion that the cache does not store). Chart overlap,
footprint and water-clipping safeguards remain. These restrictions can still
leave red portions of the border. Slope and mud GPU costs have not been isolated;
the edge addition alone has not established a repeatable FPS improvement.

Five focused CPU tests cover every edge orientation and detail, dry and
underwater cuts, material-boundary subdivision, unsupported-material fallback,
floor changes, and exact indexed vertex payloads on the CommCenter board's
boundary. Scoped Checkstyle and Spotless pass. Native cached/direct captures for
both camera poses are under `work/citadel-fps/board-cut-final-view-1/` and
`work/citadel-fps/board-cut-view-2/`; test results are in
`work/citadel-fps/board-cut-final-tests.log`.

## Board loading: where the time goes (2026-11-10)

A load is the board's file and scene, the view's renderers, the terrain build, then the hidden frames that link what
the first views draw. Profiled with a wall-clock sampler of the render thread (`handoff/board-load/harness2`), programs
cached, RTX 4070 and Iris Xe, `48x48.board` (a city) and `16x17 Fire And Ice 2`:

- **The terrain build is two thirds of a city board's load, and the render thread is its limit**: it collects each
  chunk's hexes one after another (`ChunkBuild.collectUntil`) while the workers wait. Of 24.6 s on the RTX 4070 it
  worked 22 s: kit buildings 8.2 s (their storeys' outlines 4.9 s, floor meshes 1.4 s, the kits 1.5 s), model textures
  3 s (mostly waits for their decodes), the chunks' road mask atlases 2.2 s, the board's shader programs 2.7 s,
  decal projection 1.3 s, and 2.2 s waiting for the monitor's refresh between slices (7.6 of 36.9 s on the Iris Xe).
- **A small board with relief is limited by its workers**: Fire and Ice 2's 272 hexes take 43 thread-seconds on eight
  workers (roads cut out of the ground 16 s, walls 7 s, plants 8 s), about 7 s, then 3 s of collection.
- **Family programs**, where four fresh ones link behind the loading stage: 7-42 s a load on the RTX 4070 (docs/gpu-shaders.md).
- **Nothing cached**: Fire and Ice 2's generic programs take 22-25 s on the RTX 4070 beside the build, and 122 s on
  the Iris Xe, where the link helper links them one at a time.

What the build does about it:

- A closed kit building's floors and struts are built when a unit first stands in its hex, not at load
  (`GpuTerrain#showInterior`); only a building that stands open, or an authored one, builds them with its chunk.
- Loading frames are not held for the monitor's refresh (`GpuBattleView.pace`): a frame that built a slice is followed
  at once, one that only waited by the next at 60 a second. A new board's slice is 16 ms.
- A new chunk's road masks pack into their atlas's pages on the worker that prepared it (`Prepared.packed`).
- Whoever joins pooled work that no pool thread has taken runs it (`GpuAssets.joined`).
- A decal is projected only on the faces whose bounds reach it.

Board load without the harness's own start-up and window, family programs off, medians of alternating loads: 48x48
on the RTX 4070 28.2 to 25.4 s (terrain build 19.1 to 15.6 s; three rounds), on the Iris Xe 31.5 to 27.3 s (21.2 to
16.8 s; two rounds). Fire and Ice 2 13.6 to 13.5 s. Fixed-light captures of both boards at three zooms are the same
pixels.

Kit interiors left the load after that. Built at load, the 48x48 city's kit buildings held 343 interior models of 3,168
floors: 13.3 million vertices, 304 MiB of vertex data and 2.3 s of the render thread, for floors drawn only in a
building a unit stands in (a floor is about 4,200 vertices, its outline cut back at every wall face, `keepClear`).
Built on first entry, on the RTX 4070, medians of alternating loads (four rounds, the first load discarded as a cold
start): 48x48 26.8 to 24.8 s, terrain build 16.9 to 14.8 s, peak committed memory 5,445-5,485 to 5,060-5,108 MiB. A
unit's first entry into a hex takes 3-24 ms of `GpuTerrain.animate` (twelve entries; 7-15 ms with the floors already
there). Fixed-light captures of both boards at three zooms are the same pixels.

Still on the render thread for a city chunk on the RTX 4070 (profiled with the interiors still built at load): the
first use of each kit 1.2 s, decal projection 1.1 s, model textures 1.5 s (0.6 s of it decodes, the rest uploads), the
board's shader programs 1.2-2.9 s.

Tried on the same board and not kept (2026-11-10):
- Decoding the models' images with their files, ahead of their creation: creation's waits fell from 0.6 to 0.1 s, and
  the road material arrays then waited 0.6 s behind those decodes. No gain.
- Floor vertices added as raw floats, not `VertexInfo` objects (identical meshes): 0.8 of the time in one JVM.
- Six or eight sections in flight for four: terrain build 14.8 against 14.8 s over six alternating loads each. The
  render thread waits for workers mostly before the first sections arrive, which more sections at once do not hasten.

## Preparation and replacement

`BoardSource` publishes immutable scene/frame data. `GpuTerrain` captures that
scene and `TerrainSettings` in a request, prepares CPU geometry on workers, and
collects/uploads the result on the GL thread. Generation checks reject superseded
requests. A worker must not read changing Swing state or allocate GL resources.

Natural plants settle on full-detail support at every chunk level, so zooming moves
none of them. Workers prepare that support with the chunk (`GpuTerrain.plantSupports`),
and a full-detail chunk shares its own surfaces. Classic columns, a biodome's garden
and a root past the prepared hexes build theirs on first use, on the GL thread. At
most `BUILD_JOBS` chunks hold it at once: about 100 MB in the worst measured case
(399 KB per hex). Against building it all on the GL thread, GL time in `refine` with
no frames drawn (RTX 4070 machine, six alternating runs each, medians; the ranges
overlap): loading `16x17 Sherwood Forest` fell from 842 to 529 ms, loading `45x45
Moore Rainy Woods` from 2.16 to 1.85 s, and a zoomed-in walk across it from 1.95 to
1.63 s. Calls over 4 ms fell from 75 to 25, 127 to 79 and 130 to 85. The longest
single stall did not improve, and `32x17 Kozice Valley`, with few plants, did not
change measurably. Captures of ten views, including rigid fungal and Mars colonies,
are pixel-identical to building it on the GL thread. Iris Xe was not measured.

An edit plan identifies changed tiles plus their geometric dependents. Shared
corners, road/bridge spans, shore mouths and fields can reach beyond a single hex
or chunk. Reuse unchanged tile meshes where their inputs still match, and replace
the affected installed resources together. A new scene object alone is not a
reason to rebuild everything.

An edited hex rebuilds the hexes within its reach (`GpuTerrain.editReach`): two
rings for dry relief, features, ice, biome or ground cover; three for a level,
ground or road edit within six hexes of liquid, and for a pool that only grows
deeper or shallower while staying at least a level deep, since beyond a level the
shore field and river channels read no depth; `BoardSurface.SHORE_RINGS` (six)
where liquid appears or disappears or a neighbour's shore class changes. Changed
river currents and bridge spans add their own hexes. The on-demand
`GpuTerrainEditReachSmokeTest` applies and reverts edits incrementally on shipped
boards (`megamek.gpu.reach.boards`, `.samples`) and fails when a hex differs from
a full build or changed without being rebuilt. It leaves road masks' atlas slots
out of that comparison, since each build packs a section's masks afresh.

Camera refinement and local edits use the same bounded preparation pipeline, with
at most four sections in flight (`GpuTerrain.BUILD_JOBS`). A new board's update plan
starts on the first frame, and terrain program links wait until its first sections
have started, so those prepare on the terrain workers while the programs link.
Collection/upload is spread over frames, but the frame budget is soft: an individual
tile collection or buffer upload can overrun it. Retired chunks are disposed by
the GL owner once they are no longer installed or cached.

Preparation allocates heavily, about 96 MB a chunk on a 32x34 random board, and
G1's 5-20 ms collection pauses hitched drags. MegaMek therefore runs generational
ZGC: `mmJvmOptions` in `megamek/build.gradle` is the one option list for `run`,
the start scripts behind `MegaMek.sh`, the Windows launcher (`MegaMek.l4j.ini`)
and the GPU test tasks. MekHQ hosts the board in its own JVM and needs the same
options in its own build. Dragging that board with a selected unit on an RTX 4070, frames over
16.7 ms fell from 6-8 to 1-4 a drag, p99 from 19-22 to 14-16 ms and the worst
frame from 23-34 to 18 ms, the median unchanged. On an Iris Xe the same drag is
GPU-bound (20-45 ms frames) and measured the same under both collectors. ZGC keeps
no compressed pointers and wants headroom: the live heap grew by an eighth to a
quarter (32x34: 380 to 430 MB; 128x192 Valencia: 1.18 to 1.47 GB) and the
committed heap grows towards `-Xmx` (32x34: 1.1 to 3.0-3.6 GB; Valencia: 3.3 to
4.1 GB). No soft heap target suits
both: 1 GB held the small board at 1.4 GB but kept ZGC collecting on Valencia, and
2 GB did not hold Valencia's load below 4 GB.

The opening screen retains the overall percentage of completed map sections and
lists the current task for each in-flight section in map order. Task percentages
and counts measure completed work (hexes, shapes, buffers or discrete operations),
not estimated time. The elapsed seconds belong to that task, including any wait
for its worker or the GL thread. Preparation, vegetation, roads, water fields,
models and mesh uploads have separate labels. Each job owns its progress and
publishes immutable snapshots; superseded jobs cannot overwrite a newer build's
status. Display updates occur between frames, so a single synchronous GL operation
can still pause the display until it returns.

## Coverage stencil flags near water (2026-10-08)

On `unofficial/VictorMorson/48x48.board`, looking at the lake with three dormant
geysers cut the frame rate on integrated Intel Iris Xe graphics. The geysers were
not the cost: removing their models or their spray changed nothing measurable. The
water was, and most of it was the biome/liquid coverage stencil
(`terrain-biome-mask.glsl`). The board's mud hexes switch the stencil on, and
`GpuBiomeSurface` also flagged every hex within two rings of any liquid, so the
lake's bed, its surface and its banks all ran the nine-hex stencil although the
lake holds a single palette.

A liquid hex now only records its palette on the hexes within two rings; a hex is
flagged when two or more palettes reach it, or, as before, when a biome hex lies
within two rings. For a single palette the stencil returns that palette or nothing,
and both callers already fall back to the water's own palette when it is skipped.

Iris Xe, 3840×2160, the 3D map preview's path (`GpuMapSource`), top view about five
hexes across, `glFinish` per frame. Old and new flags alternated in one process
through a scratch switch, two rounds, frame medians:

| View | Old flags | New flags | Stencil disabled |
| --- | ---: | ---: | ---: |
| Lake with geysers | 94.3, 98.0 ms | 74.4, 69.9 ms | 68.8, 72.1 ms |
| Grass beside the lake | 70.9, 73.6 ms | 65.4, 63.2 ms | 74.7, 62.3 ms |
| Mud hexes (stencil still needed) | 63.4, 58.5 ms | 62.8, 59.0 ms | 48.2, 43.1 ms |

In the lake view the new flags take about 14 ms from the opaque terrain pass (bed
and banks, 58–59 to 44–45 ms) and 9.5 ms from the water surface pass (21–22 to
12 ms).

Pixels: 17 deterministic captures (fixed animation step) of that board's lake, pond,
Mars pool and mud hexes, and of synthetic scenes with three palettes meeting, a
two-palette edge, marsh/field/mud contacts, marsh and quicksand beside water and a
lone pool far from mud and tundra, from above and isometric. Two runs of the same
code differ by one pixel per image at most, by 1/255, plus a z-fight seam at a
helipad that changes between runs. The new flags match the old within that, except
for up to four pixels per image differing by 1/255: on water, where the old path's
palette normalisation divides, and four on road kerbs in the mud view, away from any
changed flag.

Not adopted: dropping the two far hexes of the 3×3 stencil, which would keep a hex
and its six neighbours. With the shader's polygon distance a dropped hex lies 12.9 m
from a hex corner, inside the 14 m blend, so it carries up to 0.47% of the weight
over 3.7% of the area; the captures change by 1/255 on up to 0.03% of their pixels
near biome contacts. Alternated in one process over three rounds, the opaque pass
of the mud view measured 39.2, 42.9 and 49.4 ms with nine hexes against 40.4, 38.8
and 52.7 ms with seven: no measurable gain, so the exact stencil stays.

Per-quarter flags (2026-10-09): the texel's spare R bits 4–7 flag the quarters of a
hex's stencil cell (halves of its column and of its row) that a biome hex or two
palettes can reach, from the exact least shader distance between each nearby hex
and each quarter; fragments beyond the board edge keep the stencil. Checked against
the stencil itself on random boards (360,000 points), no skipped point would have
received a weight. Hex against quarter flags with the same shader, 20 captures
including Fenland's marsh and fields: 15 identical, 5 pixels in all at 1/255, one
of them the run-to-run pixel. Opaque pass, alternated over four rounds at 3840×2160:

| View | Hex flags | Quarter flags | Biome coverage off |
| --- | ---: | ---: | ---: |
| `64x68 Fenland`, marsh beside water | 81.2–88.5 ms | 75.7–77.6 ms | 43.4–46.1 ms |
| `64x68 Fenland`, fields meeting mud | 70.5–70.8 ms | 70.4–70.7 ms | 37.6–37.8 ms |
| 48×48, mud corner | 38.7–49.6 ms | 38.3–43.3 ms | 25.6–29.4 ms |

Quarters pay where few fragments lie inside a blend, such as a marsh's edge on open
water (frames 101–110 to 93–96 ms there); where biomes fill the view every quarter
is reached and nothing changes.

Limitations: one laptop (i7-13700H, Iris Xe, 4K panel); timings drift 10–25%
between and within runs, so only alternated comparisons are quoted, and the A/B
harness was scratch work outside the tree. Where biomes fill the view, their blend
and the shading it drives still cost 13–37 ms of the opaque pass at 4K on this GPU
(the coverage switched off for comparison), and the water surface pass about 12 ms
in the lake view.

## Level water-bed clipping (2026-10-07)

`BoardSurface` already builds sparse beds, but `GpuTerrain.prepareSculpt` used to
split them against every triangle of the water surface. Its internal diagonals
turned even a flat six-triangle floor into 293 triangles, without adding shape
or shading information.

A fully submerged bed under level water now keeps its original triangles and
receives that pool's water optics directly. Material transitions still use the
same sampler and adaptive subdivision. Depth-zero bars, descending streams,
waterfall lips and refined cliff contacts retain coverage clipping. Canonical
bed geometry, depth sampling, picking and the animated water surface are unchanged.

On Valencia, columns 65–96 and rows 17–48 contain 277 depth-positive water hexes.
Comparing the saved original sources with this change gives these **prepared
blended terrain** counts, including those water hexes' banks and material blends:

| Detail | Before | After |
| --- | ---: | ---: |
| Full | 789,395 | 69,523 |
| Distant | 775,501 | 48,825 |

Both versions retain the same 21,075 canonical bed faces in that sample. Its four
flat-bed hexes go from 1,170 rendered triangles together to 24. These are regional
geometry counts, not a whole-board load-time or frame-rate measurement.
The focused mesh tests cover all four detail levels, varying depths, curved banks,
mouths and reconstructed water geometry. Local comparison sources, logs and
wireframe plots are in `build/water-bed-review`.

Verification: 84 CPU cases pass across bed geometry, coverage, material blending,
slopes, waterfalls and wet cliffs. A focused native render also passes, capturing
flat beds, changing depths and mixed shores in top and oblique views, with and
without water. The broader `GpuTerrainBlendSmokeTest` fails its dry-ground
edit/rebuild comparison before reaching the water captures; the saved original
code fails identically (mean byte error 0.0172764). That separate failure remains.

## Valencia loading investigation (2026-10-06)

`unofficial/VictorMorson/128x192 Texlos - Livius - Valencia_City.board` contains
24,576 hexes: 2,667 building, 3,329 road, 6,685 water and 128 bridge hexes,
with extensive jungle. The live-view loading test originally exceeded its
600-second terrain-settle limit. JFR attributed about 89% of sampled terrain
worker stacks to `BoardConcrete.distance`: it repeatedly reconstructed fitted
hex outlines and queried an immutable boxed-long map whose coordinate keys
collided heavily.

The fitted concrete outline now owns primitive-long maps and one derived
twelve-float polygon for each affected hex. Unmoved hexes return immediately.
Construction order and fitted corners remain unchanged; adjoining natural hexes
use the same derived outlines. These maps are constructed before publication
and never mutated by terrain workers. Water clipping also retains polygons
wholly inside or outside a clipping plane instead of allocating replacement
lists and temporary vectors; crossings keep the same interpolation.

| Run | Terrain build and visible-detail settlement |
| --- | ---: |
| Original | Exceeded 600 seconds; no completed load measurement |
| Concrete lookup/outline fix | 116.643 seconds |
| With clipping allocation reduction | 90.031 seconds |

All three runs used the same Valencia board, 1920×1080 full live view, RTX 4070
Laptop GPU, enabled terrain LOD, JFR profiling and no JaCoCo. They were sequential
workspace measurements with other source changes occurring between runs, not
a frozen single-variable A/B; do not derive an exact speedup ratio. The final
run spent another 9.429 seconds capturing board artwork/state and 1.182 seconds
opening the view before the measured terrain stage. It installed 384 distant
chunks and retained about 396 MiB of Java heap after GC.

The final prop pages occupy 501.706 MiB of vertex/index capacity for **each** CPU
and GPU copy, in addition to source caches. That figure includes ground scatter
as well as buildings, so it is not a building-only attribution or total memory
measurement. Terrain pages account for another 16.452 MiB per copy. Loading is
still expensive: remaining worker samples include shore-field/material queries
and water coverage clipping. Further work should measure those operations and
the contributors to duplicated prop buffers before changing quality, adding
workers, or introducing a disk cache. Current grass density and geometry detail
were not reduced for this result.

Seventy-nine CPU cases pass across concrete fitting, material blending, terrain
seams and water coverage, including distance-vs-geometry checks at three scales
and water clipping area/optics invariants. The final live Valencia view, underwater
cliff render and tree-LOD checks pass. Both existing 200×200 constrained-heap cases
also pass: full-resolution capture/editing and native rendering/picking/culling.
The native case sampled 511.5 MiB peak out of 512 MiB and retained 332.7 MiB after
GC. Its full render exercise took 432.56 seconds; passing this capacity check
does not establish satisfactory load time or frame rate at that heap limit.

## Valencia loading, second pass (2026-10-06)

The first pass above left the two terrain workers as the bottleneck. A JFR profile
of the working copy on a 20-core i7-13700H (one load, terrain worker threads only)
put 14% of worker time in the `ThreadLocal` lookup behind `TerrainSettings.current()`,
about 40% inclusive in `BoardSurfaceBlend.sample`, `sampleWater` and `sampleAt` (with
`contact`, `blendable` and `natural` as the leaves, and stream objects as a fifth of
all allocation), 15% in shoreline tracing through `BoardRiver.field`, 14% in water
clipping (`coveredPolygons`, `splitCovered`), 11% in `GpuRoads.mask` and 10% in
`GpuWaterShader.Field.prepare`. The pool was capped at `min(2, cores / 2)`.

### Changes

- **Workers.** `GpuTerrain.DEFAULT_WORKERS` is `max(1, min(8, cores / 2))`: 8 on a
  20-core machine, still 2 on a 4-core one. The bound of four chunks in flight is
  unchanged. `TerrainSettings.workers` creates numbered `TerrainSettings.Worker`
  threads that hold the active settings in a field; `current()` reads that field on a
  worker and keeps the thread-local scope for every other thread.
- **Material sampling.** `BoardSurfaceBlend.Sampler` serves one hex's vertices. The
  tiles that blend into a tile, its bank families and the pools around it never depend
  on the queried point, so they are derived once per tile, and a position asked again
  returns its earlier cover. The static `sample` and `sampleCliff` remain as one-off
  wrappers. `Tile.building()` replaces the per-call feature streams in `natural`,
  `blendable` and `BoardBiome`. `BoardGeometry.tile` reads the board dimensions once
  and uses `BoardScene.tile(int, int)`; `BoardScene.neighbor` avoids a `Coords` per
  neighbour lookup. `BoardConcrete.distance` chooses the nearest edge on squared
  offsets and calls `Math.hypot` once instead of six times.
- **Water clipping.** Each drawn water triangle's plane and edge normals are derived
  once per hex (`WaterTop`) and binned on a grid (`GpuWaterCover`), so a face tests the
  few triangles near it, in the original application order. `splitCovered` takes its
  plane as floats, `GpuSurfaceBlend.degenerate` replaces the per-triangle `Vector3`
  cross products, and `appendPolygon` no longer streams.
- **River field.** Each `BoardRiver.Channel` keeps its bounds and the largest radius
  it can reach at any wander. A channel whose field cannot come within the union's
  rounding of the result so far is skipped: `smoothMin` is an exact minimum once two
  values differ by its rounding or more, so the skip changes nothing.
- **Harness.** `GpuTerrain.prepare` is static (the road-mask sharer is a parameter),
  so `GpuTerrainLoadBenchmarkTest` runs the real chunk preparation on a board region
  and checksums everything it produces. `gradlew :megamek:gpuTerrainLoadBenchmark`
  takes the `megamek.gpu.benchmark*` properties (board, region, LODs, workers,
  repeats, JFR file). Since batch 9 it loads the board as a game loads a sheet
  (`BoardFile.loadSheet`, converted: placed objects and car-park lane roads), so its
  checksums differ from the ones recorded below for the board as read.

### Measurements

Two detached worktrees at the same commit, differing only in these changes, were
measured alternately on an i7-13700H (20 logical cores, 32 GB, RTX 4070 Laptop GPU).
Every checksum matched at every detail level, including the whole board: the geometry
and materials are bit-identical. Other builds were running on the machine at the same
time, so the ratios are more reliable than the absolute times.

| CPU chunk preparation (warm repeats) | before, 2 workers | before, 8 workers | after, 8 workers |
| --- | ---: | ---: | ---: |
| 32×32 shoreline block, distant detail, 16 chunks | 5.35–5.46 s | 2.31–2.75 s | 1.39–1.60 s |
| 32×32 shoreline block, full detail | 4.81–4.97 s | 2.29–2.39 s | 1.29–1.42 s |
| whole board, distant detail, 384 chunks | not run | 48.6 s | 42.0 s |

Live load, `GpuBoardPerformanceSmokeTest.measuresTheCompleteBoardViewWithLiveCapture`
on the same two worktrees (1920×1080, terrain LOD on, 4 GB heap, JFR on, both runs
while other builds and a 13 GB smoke-test JVM from another session were running):

| Live Valencia load (terrain build and visible-detail settlement) | before | after |
| --- | ---: | ---: |
| detail-settle | 114.6 s | 68.5 s |
| frames during settlement | 13,984 | 3,012 |
| building-submission median / p95 | 4.0 / 15.4 ms | 13.9 / 49.9 ms |
| installed chunks | 384 distant | 384 distant |

The render thread now spends longer per frame on chunk collection and upload because
the workers hand it chunks faster; it is the next limit, not the workers alone (see
below).

The `boardview.gpu` unit tests fail the same 46 tests before and after at that commit;
none are introduced by this work. The earlier live run that ended in an
`OutOfMemoryError` had copied `gpuBoardSmoke.jvmArgs` into an ad-hoc task, which drops
the `-Xmx4096m` and leaves Gradle's 512 MB test-worker default; set `maxHeapSize`
explicitly in any such task.

### What is still expensive

A JFR profile of the whole-board preparation with these changes (eight workers, 6,848
worker samples) ranks the remaining worker time:

| Share of worker samples (inclusive) | Where |
| ---: | --- |
| 38% | water clipping in `coveredPolygons`: the clip itself (`splitCovered` 11% self), not the candidate search |
| 25% | `BoardSurface.<init>`, 17% of it shoreline tracing (`shoreline`, `firstDry`, `BoardRiver.field`); part of it rebuilds water hexes just outside the chunk at full detail, once per neighbouring chunk |
| 24% | `GpuRoads.mask`: `BoardRoad.pathSample` runs `Math.hypot` for every path segment of every mask pixel |
| 16% | `GpuWaterShader.Field.prepare`: `Pools.get` constructs the same out-of-chunk water surfaces, `Pools.sample` per texel |
| 5% | `BoardSurface.Face.height` scans every face of a surface per height query |

A piece-level skip in the clipping (leave a dry piece alone when its bounds clear the
water triangle's) was tried and removed: the half-plane tests of the original misclassify
points far beyond the apex of very acute water slivers, so the original keeps rounding
slivers that a bounds test drops, and the output changed (16 fewer triangles on the
reference block). An exact replacement would have to repeat the plane tests themselves.
A bounds-based skip of far path segments in `BoardRoad.pathSample` was exact (same whole-board
checksum) but not measurably faster in alternating runs (40.7 and 57.7 s with it, 39.5 and
49.8 s without), so it was dropped too.

On the render thread during the live load, Java samples put 39% in `collectTile` (decal
projection 12%, `ModelInstance.calculateBoundingBox` per prop 9%, foliage 6%), with the
prop pages (501 MiB of vertex data) copied and uploaded after the chunks are installed;
13% of its samples were regex matching with no MegaMek frame on the stack, which is
test-harness output handling, not the view. With chunks arriving faster, the render
thread's collect and upload slices (8 ms per frame while a board opens) are the next
limit on the live time; moving the per-prop bounds to a per-model cache and preparing
the non-sculpted blends on the workers are the obvious next steps there.

### Limitations

- One machine, under load from other builds; the live times are single runs.
- The shoreline block is the most water-heavy region of the board and shows the largest
  gain; chunks of pavement, buildings and roads gain less because their time is in
  concrete fitting, road masks and the water field texture, which are unchanged.
- The render thread's collect/upload stages and the capture phase before terrain
  construction are unchanged.
- Hexes where more than four cover families meet still subdivide to the depth limit
  (three such hexes on Valencia reach 27,000 to 114,000 blended triangles at distant
  detail in the working copy); that is pre-existing and was not changed here.

## Valencia loading and the editor, third pass (2026-10-06)

The second pass left water clipping as the largest worker cost and did nothing for the
board editor, where one hex edit on Valencia took 1.4 s to show. Every change here keeps
the chunk output bit-identical (same `gpuTerrainLoadBenchmark` checksums, f63fbf6e distant
and 56af45d8 full on the reference block) and, for the atlas, the same rendered pixels.

### Changes

- **Exact clip skip.** `WaterTop.misses` tests a dry piece's vertices against the same
  four clipping planes the clip uses. When every vertex lies beyond one plane by more than
  rounding (0.01 world units), the clip at that plane would keep nothing and hand the piece
  back whole, so the piece is left alone; `coveredPolygons` also keeps the piece list as it
  is until a triangle actually splits something. Unlike the bounds test tried in the second
  pass this repeats the clip's own half-plane tests, so acute slivers behave identically.
- **Opening slice.** A 32 ms render-thread slice while a board opens (instead of 8 ms) made
  no measurable difference (32.5 s against 34.5 s live, one run each), so the slice is
  unchanged. The limit there is the work per chunk, not the slice.
- **Editor: artwork per dirtied chunk.** `prepareUpdate` recomposes the rim, ground and
  decal artwork only for the hexes of chunks the edit dirtied and keeps the previous
  build's pixel objects for every other hex (`terrainPixels`, `normalPixels`,
  `decalPixels` fields). The change scan therefore also compares each hex's decal,
  tileset decal and tileset scenery artwork, which the atlas comparison used to notice
  for every hex. A changed limb model still recomposes everything.
- **Editor: concrete fit.** `BoardConcrete.adopt` lets a scene whose fit inputs did not
  change (ground, water, level, roads, cliff tops, structures and props) keep the previous
  fit, and `prepareUpdate` then skips the corner comparison. A planting edit no longer
  refits the whole board's concrete (layout 52 ms to 2 ms).
- **Editor: currents.** `BoardFlow.calculate` reads liquid hexes and their liquid
  neighbours only (`BoardLiquid.connects` needs both present), so a level or frozen change
  on dry ground no longer recomputes the board's currents; a liquid change still does.
- **Editor: atlas update.** `GpuTextures.updateRegions` compares each slot's artwork by
  reference with what the slot holds and looks only at the slots given other objects;
  equal artwork under another object is recognised by content and left alone. With the
  pages on display (`retainReplacedPages`), changed keys are rebound by `append`: artwork
  no live slot holds is packed into the existing pages and only those rectangles (with
  their duplicated border) are uploaded with `glTexSubImage2D`, then mip levels 1 and 2
  are regenerated. Slots keep a user count, so a slot nobody uses leaves the content index
  exactly when the full update would have dropped it, and the full update's compaction
  rule is evaluated on the same live area. A page that fills up still goes through
  `PixmapPacker.updateTextureAtlas`, which creates the new page's texture and reloads the
  pages packed into since. Before this, one new rim image on Valencia reloaded both
  2048×2048 pages (colour and normal, 100 ms on the Iris Xe) and rebuilt 49,152-entry
  maps three times (70 ms).
- **Editor: frame slice.** While an edit's rebuild is pending, `refine(camera, true)`
  gives collection and upload a 12 ms slice (was 6 ms; camera refinement keeps 2 ms), and
  `GpuTerrain.update(BoardScene)` drives its blocking loop with that slice too. A chunk's
  collection and upload take about 60 ms of render-thread time, so an edit now lands in a
  handful of frames.
- **Harness.** `GpuBoardPerformanceSmokeTest.updateWithTimeline` prints the wall time of
  every stage the terrain reports while driving an edit, the test adds a planting edit
  (woods on a plain hex) next to the level edit, and it captures `performance-edit.png`
  and `performance-woods.png` so an atlas or mesh change can be compared pixel for pixel.

### Measurements

Same i7-13700H; the invisible test window renders on the Intel Iris Xe, and another
MegaMek instance and other builds were running during most of these runs, so absolute
times vary by 2× between runs and only runs quoted together are comparable.

| CPU chunk preparation, reference block, 8 workers (warm repeat) | second pass | with the clip skip |
| --- | ---: | ---: |
| distant detail | 1.39–1.60 s | 1.20 s |
| full detail | 1.29–1.42 s | 0.79–1.04 s |

Live Valencia load on a quiet machine, same test as the second pass: 32.5 s and 34.5 s (one run each with the 32 ms and the 8 ms opening slice), against 68.5 s under load with the second pass and 114.6 s before it.

One hex edit on Valencia (`measuresTerrainUpdatesAndSeparatedWater`, close view): the
level edit turns a hex into level-1 plain ground, which changes its rim artwork, its
concrete inputs and (before this pass) its currents; the planting edit adds woods to a
neighbouring hex and changes no ground, water or level.

| Edit on Valencia, wall time until the terrain is published | before the pass | slice and chunk-local artwork | plus atlas, currents, concrete |
| --- | ---: | ---: | ---: |
| level edit | 1,419 ms | 452–465 ms | 321 ms |
| planting edit | not measured | 238–276 ms | 158 ms |

The old and the new atlas code were run back to back under the same load, each capturing the frame after both edits: `performance-edit.png` and `performance-woods.png` (1280×900) are byte-identical between the two, and the totals (486 against 511 ms, 212 against 342 ms, the later pair slower) are within that load's noise. Measured in-process on a quieter run, the atlas pass of the level edit takes 12.1 ms with the new code and the planting edit's 7.2 ms, against 171 ms for the level edit with the old code (two full-page reloads, 100 ms, and three passes over the 49,152 slots, 70 ms). The first build's atlas pass is unchanged at about 200 ms.

Per-stage timeline of the level edit after this pass (one run, loaded machine): queued 8, layout 32 (the concrete refit), changes 44 (including the artwork of the dirtied chunk and the atlas pass), sections 4, then for the one dirtied chunk surfaces 30, cliffs 16, topography 16, vegetation 32, water 58, models 17, meshes 47 and upload 13 ms.

### What is still expensive in an edit

- The dirtied chunk is rebuilt whole: surfaces, cliffs, topography, vegetation, water and
  models each run a parallel stream over its 64 hexes, and one chunk alone cannot keep
  eight workers busy, so these stages take 150–250 ms together for a full-detail chunk.
  Rebuilding only the edited hexes would need per-hex mesh ownership inside a chunk.
- A level or ground change refits the board's concrete (50–85 ms on Valencia) because the
  fit reads neighbours several hexes out (`continueSides`); bounding that radius exactly
  was not attempted.
- Collection and upload of the rebuilt chunk on the render thread (about 60 ms).
- The change scan over all 24,576 hexes (15–20 ms) and, for a liquid change, the
  board-wide currents (about 35 ms).

### Limitations

- All edit timings are single runs on a laptop shared with another MegaMek instance; the
  stage timeline is sampled from `buildDetails()` every 0.2 ms, so stages shorter than
  that merge into their neighbours.
- When one edit packs several new images, they are packed in the order the changed slots
  were given rather than in the previous code's hash-map order; placement inside the page
  was never a rendering property, but it is not byte-for-byte the previous layout.
- A page that fills still repacks the atlas, which moves every slot and rebuilds every
  chunk, as before this pass; on Valencia the 131 ground images use a fifth of each page.
- The live load time on this machine is now mostly chunk work and render-thread
  collection, not the atlas; the atlas change helps the editor only.

## Detail and support are different representations

`TerrainLod` chooses detail from projected size. Board dimensions do not determine
quality. Chunk refinement preserves shared boundaries so neighboring detail
levels meet. The bounded detail cache can retain a previous chunk representation
for a return to its scale.

`BoardSurface` and the finished tactical/support surface remain the basis for
picking, unit contact and vegetation placement. Rendering may simplify parts such
as a water bed within an error bound; it must not replace the canonical support
with an unrelated approximation. `TreeLod`, `MeshLod` and `FormationLod` select
their own tree, rigid-unit and formation representations.

Every installed hex keeps its finished support and water geometry for as long as
its chunk is installed or cached, as engines keep a loaded chunk's collision:
picking, draping, placement and replanting read it, and no query rebuilds a hex
on the render thread. On the 32x34 random board every hex at FULL detail holds
40 MB (38 KB a hex, 31 KB of it the shape without ground cover support), at
MEDIUM 16 MB. Chunks keep the detail they had when last in view, so this grows
with the area seen close up, alongside the chunks' meshes. Before, a bounded
cache and weak links let collections drop supports, and dragging a 32x34 random
board with the pointer over it rebuilt 58 hexes (203 ms) on the render thread in
600 frames; now none, and frames over 16.7 ms on an RTX 4070 fell from 9-16 to
6-7 a drag. Only a hex its
preparation gave no support builds one when queried, the shape alone
(`BoardTacticalGeometry.Surface.shape`); a paint edit replants from a support
only when it carries ground cover support. Ground cover is planted by the same
worker on the installed support; see
[fields and marsh](gpu-fields-marsh.md#one-pipeline-for-ground-cover).

`GpuTactical` joins the movement envelope's and the zones' planes again only when
their fills, the tiles or the tuning change, not when panning refines a chunk.
With a selected unit's envelope and the pointer over the board, a 600-frame
drag sampled 24 ms (RTX 4070) and 21-24 ms (Iris Xe) in surface lookups before
these changes, and 7-9 and 4-9 ms after; plane joins no longer appear in the
samples. On the Iris Xe the slowest drag frames then cost what the same view
costs held still: 28-37 ms of GPU time in the opaque pass with the board filling
a 1080p window. A chunk replacement adds 3-6 ms there, the shadow map
`replaceDetail` marks for redrawing.

## Opaque drawing and pages

| Owner | What it combines or reuses |
| --- | --- |
| `GpuTerrainBatch` | Compatible terrain material state during opaque submission. |
| `GpuOpaqueSorter` | Ordering of opaque renderables to keep compatible work together. |
| `GpuPropBatch` | Static opaque prop ranges with compatible state. |
| `GpuTerrainPages`, `GpuMeshPage` | Small ranges from nearby chunks in persistent page buffers. |
| `GpuTreeInstances`, `GpuInstancedMesh` | Repeated models/transforms through instanced draws. Each tree takes the level its own projected diameter asks (`Stand.detail`); every chunk's trees stay uploaded per species and detail level in board order, and a pass draws the visible chunks' ranges, so panning uploads nothing and a zoom uploads only the species-level buffers in which a chunk's run changed (a dissolve changes them twice: when it begins and when its old level drops the tree). In the live view a tree that changes level dissolves into it over `TreeLod.DISSOLVE` (0.25 s of the terrain clock): meanwhile it draws at both levels, which keep complementary pixels of one screen pattern (`terrain-foliage.frag`, and the depth twin alike), and holds the level it takes, so a reversed or continued zoom starts the next dissolve only after this one (larger trees wait for it too, as none takes a coarser level than a smaller one). The run it left drops it once the last dissolve has ended. Captures and previews, whose clock stands still between frames, and the wireframe view switch at once; the shadow map keeps its own levels without a dissolve. At rest it costs nothing measurable: on an Iris Xe at Medium the forest, grassland and city views' frames and the trees' programs (their depth twins included) stayed within run-to-run noise of the build without it. Building parts still gather per pass. |
| `TerrainLod` | Ground sampling per level, chosen by projected hex size (64/24/6 px) with hysteresis. `BoardRelief` already emits six-triangle fans for ordinary planar hex tops at every LOD. Shaped cliff/coast boundaries retain their shared samples; concave outlines use polygon triangulation. Rounded cliff tops refine interior shading samples at the same playing height, so the six-triangle budget applies to ordinary tops without those rims. Dressing, slopes and walls have separate geometry costs. The earlier 573-face flat-ground measurement predates this implementation and is not a current cost or a pending optimization. Measure the installed scene before changing sample counts or thresholds. |
| `TreeLod` | Four plant levels by projected diameter (80/48/24 px, 10% hysteresis): the near mesh, two decimated meshes and, below 24 px, an impostor: one quad (2 triangles) turned toward each pass's own camera (colour, depth, shadow), each pixel showing the nearest of 32 renders of the far mesh it replaces (8 directions at 10, 35.26, 60 and 90 degrees above the horizon, `impostor-frames.glsl`): its unlit colour, and its surface's normal and depth, with which each texel is lit and takes its shadow at that surface (`mm-data/tools/prepare_tree_lods.py -- impostors`). The quad is alpha-tested, so it shadows and picks like the meshes; each texel writes its surface's depth, and the quad itself takes the depth of its plant's front (`impostorFrontZ`) with conservative depth (`depth_greater` from GLSL 4.20), so the early depth test stays on. Against the earlier crossed cards (12 triangles), measured on the Iris Xe at Medium before texels wrote their own depth (2026-11-08, cards/frames/frames/cards): every tree and city view within its run-to-run spread, e.g. far forest 16.9 vs 16.8 ms. Far and middle views were primitive-bound (halving the window changed frame time little), so fewer triangles, not cheaper shading, is what pays there. A tree-only vertex shader without the scene shader's per-vertex light loops, shadow projection and fog was tried and measured identical (the driver already discards that unused work), so trees keep the shared vertex shader. |
| `GpuTerrainDepth` | Depth-only ranges borrowing finished chunk meshes and materials. |

Pages receive the source ranges from invisible chunks as well as visible ones.
Panning changes which existing ranges are selected, not the page's geometry.
Replaced source ranges, such as a chunk's that a pan refines as it comes into
view, outdate a page without discarding it: its merged meshes keep drawing the
unchanged sources and the replaced ones draw as they are. Once the page has been
unchanged for `GpuMeshPage.SETTLE_FRAMES` (30) frames it merges again, a source
range at a time, within one share of the frame for every page merge
(`GpuMeshPage.BUILD_NANOS`, 2 ms), and the new meshes replace the old when
complete. Merging a whole page in the frame a chunk was refined stalled that
frame: dragging a 32x34 random board at 80 px/hex in the battle view, the worst
frame went from 103-155 ms to 34 ms on an RTX 4070, and from 623-649 ms to
56-62 ms on an Iris Xe (p99 109 to 47 ms there; the median rose from 14.6 to
17.0 ms while replaced chunks drew unmerged). A completed merge's meshes upload
in their first draw, 4-6 ms of `glBufferData` on the RTX 4070, about one frame
in a ten-second drag. Uploading each in a merge step of its own, apart from the
copy or with smaller or dynamic meshes, only moved that cost to other frames:
over-budget frames stayed at 9-16 per drag in every variant.

Unit cutaways retain the chunk's complete opaque source in the static page.
While a chunk is opened, its original ranges are hidden and its existing cutaway
cache is drawn separately; `GpuSmallObjectSmokeTest` checks that occupancy
repacks no page.

Page grouping is used for orthographic rendering. Perspective keeps the original
ranges and their projected-detail uniforms. Large meshes and ineligible material
states also remain separate. Do not merge transparent, cutout, instanced or
unusual depth-state work just because its texture matches.

### Dense urban hover measurement

On `unofficial/VictorMorson/48x48.board` (915 building hexes), opening a chunk
under the pointer previously replaced that chunk's page source and recopied its
whole four-by-four-chunk page. A controlled comparison on an RTX 4070 Laptop GPU,
1920 × 1080, used identical compiled classes and resources with only the old or
new `GpuTerrain` page policy substituted. Both runs used the complete live board
view, 80 warmup frames and 180 samples per view, `glFinish`, no JaCoCo, and GL
counting disabled during timing. The measured moving view was:

| Measure | Replace page source | Keep static page source |
| --- | ---: | ---: |
| Total render median / p95 | 6.521 / 16.341 ms | 8.193 / 12.893 ms |
| Frame interval p95 / p99 | 17.602 / 19.003 ms | 13.788 / 17.665 ms |
| Opaque submission p95 | 9.943 ms | 3.988 ms |
| Opaque GPU p95 | 10.808 ms | 1.005 ms |
| Cumulative prop-page builds | 32 | 4 |
| Opaque draws / submitted vertices | 415 / 627,432 | 436 / 627,432 |

This removes page-upload stalls, with a small draw-count cost for opened chunks;
it does not establish a general FPS increase. The median of the whole view was
slower in this pair. Native page comparisons retain identical pixels, including
pan/cull/edit transitions. The separate `GpuRoofHoverSmokeTest` checks legacy and
modular roof picks in both projections. The roof-selection and prop-page pixel
checks pass independently.
The measurements are recorded in `build/urban-review/frozen-old`
and `build/urban-review/frozen-patched` (2026-10-06).

Depth snapshots copy no vertex data. They must be recaptured when the owning
chunk's meshes, transforms or relevant material state change. Their borrowed
resources cannot outlive that chunk.

## Shadows, depth and animated fields

`GpuTerrainShadows`, which `GpuTerrain` owns and casts into, can reuse a static
terrain shadow when coverage, light direction and terrain are unchanged. Moving
units are drawn over the retained terrain shadow.
Both color and depth attachments must be restored: retaining only color leaves
incorrect depth for moving casters. Terrain changes, shader changes and unsuitable
shadow-camera coverage invalidate the cache. A camera refinement does not: the
refined chunk casts the same terrain at finer detail, so the cached shadow serves
until refinement has paused for `GpuMeshPage.SETTLE_FRAMES` (30) frames and is then
drawn once. Dragging the 32x34 random board on an Iris Xe, full shadow passes fell
from 60 to 20-22 per 600 frames (every refined chunk drew one before) and their GPU
time from 116 to 72-78 ms; frame-time percentiles moved less than the run-to-run
spread.

Only the changed units' footprints are restored from the cache: each unit's light-space
bounds mark 64-texel tiles, before and after it moved or posed anew, and the marked
row runs are copied back (`restoreTiles`), then every unit is drawn again. The whole
map is copied where it holds another cache's terrain, past half its tiles or past 32
runs. In `GpuMixedUnitBenchmarkSmokeTest` on an Iris Xe (two rounds, 2026-10-29) the
geometry shadows' GPU median fell from 0.56-0.69 to 0.17-0.21 ms with 64 units, from
1.14-1.52 to 0.69-1.16 ms with 256 and from 4.1-4.8 to 4.05 ms with 512.

`GpuAtmosphere` reuses the captured scene depth for fog, shafts and composition.
`GpuEffectDepth` supplies depth to effects that need it. These are distinct from
the light's shadow map. An animated cloud field changes direct illumination
without requiring a new geometry shadow.

Below 100% render resolution the composite and SMAA run at the scene's size and FSR 1
(`GpuFsr`: EASU, then RCAS at one stop) scales up to the window. On an Iris Xe at
1920 x 1080 (`GpuRenderResolutionSmokeTest.compositeAndSmaaTimings`, ABAB, 2026-10-29)
the composite stage costs 0.4-0.8 ms more than the former bilinear scale-up (70% with
SMAA 1.37 -> 1.80 ms, 80% without 0.86 -> 1.68 ms); 100% is unchanged. It buys detail,
not time: at 75% it is visibly sharper than the bilinear scale-up.

Wind, water waves, cloud motion and most lighting changes update existing uniforms
or textures. Grass and plant buffers depend only on the installed plants of their
chunk; camera movement and time should not upload them again.

## Classic and Enhanced graphics

The 3D Graphics setting is a build mode of the same chunk pipeline ([GPU board](gpu-board.md#graphics-classic-and-enhanced)).
A Classic build prepares each hex's tileset column on the terrain workers (`GpuTerrain.prepareColumns`) and composes its
art in the update plan's texture pass; it builds no relief, sculpted material, water or lava field, wave simulation,
plant, road, biome map or ground cover, and its chunks have one level of detail, so the camera never refines them.
Enhanced builds no Classic data (no tileset decals, sprites or column art). A switch either way is a full rebuild
behind the loading stage; it gets an 8 ms upload slice per frame (a new board's frames 16 ms) instead of camera
refinement's 2 ms.
The Tactical View hides fluff at draw time (`GpuTerrain.setFluffHidden`): the 3D prop caches and their pages stay,
and each chunk's few kept props are packed once on the view's first draw (`Chunk#keptProps`). Recaching every chunk's
props with the keep rule instead (the design's first plan) cost about 1 s more on entering the view on Valencia
(longest frame 1.9-2.3 s against 0.7-1.3 s before, RTX 4070) and rebuilt the prop pages both ways.

Measured 2026-10-12 on the user's laptop (RTX 4070 Laptop with `-Dmegamek.gpu.card=DISCRETE`, Intel Iris Xe with
`INTEGRATED`), 1920x1080 hidden window, `glFinish` per frame, 15 warm-up and 30 sample frames, before = 79273ff075,
after = this change, in `GpuBoardPerformanceSmokeTest.measuresTheCompleteBoardViewWithLiveCapture` with
`-Dmegamek.gpu.performanceGraphics` (one JVM loads one graphics, then switches to the other) and
`-Dmegamek.gpu.performanceTactical=true` (overview, close and moving in the 3D view, then in the Tactical View). Other
sessions' builds and, for long stretches, an interactive MegaMek client on the Iris Xe shared the machine: a scenario
during which a foreign Gradle test JVM ran, or other processes put 10 % or more 3D load on the card, is left out.
Ranges over 1-6 runs (NVIDIA) and 1-4 (Iris Xe). "Load" is the first frame to three frames without a terrain job;
memory is the used heap after `System.gc()` and the Windows GPU process memory (dedicated on the NVIDIA card, shared
on the Iris Xe) after load.

| Board | Loaded as | Load s, before / after (NVIDIA; Iris Xe) | Heap MiB | GPU MiB (NVIDIA; Iris Xe) |
|---|---|---|---|---|
| Valencia 128x192 | Classic | 64.1 / 28.4; 43.2 / 13.2 | 490 / 391-392 | 5512 / 1193; 6677 / 1278 |
| Valencia 128x192 | Enhanced | 53.9-78.3 / 63.7; 47.0 / 42.8 | 444 / 444 | 5616-5628 / 5556; 7214 / 6882 |
| MesaCity1 96x102 | Classic | 17.1-34.3 / 10.5; 16.7 / 5.0 | 231-232 / 211 | 1400-1402 / 440; 1926 / 402 |
| MesaCity1 96x102 | Enhanced | 23.0-29.9 / 20.8; 16.6 / 15.2 | 206-207 / 206-207 | 980-1669 / 1243; 2187 / 2144 |
| 48x48 lake | Classic | 19.5-19.9 / 7.7; 13.9 / 5.4 | 254-255 / 227 | 1247-1262 / 228; 1515 / 461 |
| 48x48 lake | Enhanced | 13.8-19.9 / 20.0; 21.4 / 15.8-20.3 | 251-252 / 251-252 | 981-1276 / 875; 1650 / 1354-1733 |

- Runtime switch on Valencia (settle / longest frame, NVIDIA; Iris Xe): Enhanced to Classic 4.5-7.9 / 4.2-7.3 s;
  4.1 / 3.6 s before (the 3D terrain stayed and the columns were built on the render thread) against 15.2 / 1.0 s;
  11.6 / 1.8 s after. Classic to Enhanced 3.8 / 3.2 s; 3.0 / 2.4 s before (the 3D terrain was already built) against
  85 / 3.6 s; 42.6 / 2.5 s after, a full build behind the loading stage. After a switch the other build's chunks are
  retired over the next frames: the Valencia heap after a switch to Classic reads 549-552 MiB in the first scenario
  and settles at 420-421 MiB by its Tactical View scenarios (a Classic load: 391-393), after a switch to Enhanced
  447-450 MiB (an Enhanced load: 444). A switch does not free `GpuAssets`: it keeps the Enhanced texture arrays (sculpt,
  road, magma) and the loaded models until the renderer closes, since the print and the editor previews borrow it.
  GPU memory after a switch was not sampled once settled (the last samples fall in the build or the shutdown).
- Entering the Tactical View on Valencia (settle / longest frame): NVIDIA Enhanced 0.9-1.6 / 0.7-1.3 s before,
  1.0-1.4 / 0.9-1.1 s after; Iris Xe Enhanced 1.3-1.4 / 0.7-0.9 s before, 1.4-1.6 / 0.9-1.5 s after; Iris Xe Classic
  0.9-1.4 / 0.7-1.2 s before, 0.5-0.7 / 0.4-0.5 s after.
- Frame time: the Tactical View's Valencia overview dropped in both graphics on both cards (median ms, before / after:
  NVIDIA Enhanced 24.8-42.0 / 15.8-24.7, Classic 20.4-41.0 / 16.8-18.3; Iris Xe Enhanced 71.4-72.3 / 42.3-47.1,
  Classic 45.8-46.3 / 23.5-24.9), as did the Iris Xe's 48x48 Classic Tactical View (overview 21.6-24.7 / 9.7-19.9).
  The other cells whose ranges do not overlap go both ways, by up to about 30 %, among them 3D cells whose drawing
  this change does not touch (Iris Xe Valencia Enhanced overview 81.0-81.1 / 82.1-105.0, close 17.6-19.2 / 20.0-22.1),
  and within a run the stages it does not touch (HUD, overlays, atmosphere) move by the same factor: that spread is the
  machine's state. Classic 3D close views read slower after (Valencia NVIDIA 7.6-12.0 / 12.3, Iris Xe 7.8-9.2 /
  10.6-11.6; MesaCity1 Iris Xe 6.5-8.0 / 8.8-9.0) with the same draws and vertices; in the Valencia Iris Xe pair the
  terrain's own GPU time rose 14 % against 23-24 % for the untouched stages, so no cost of the change is shown there.
  Entering the Tactical View packs each chunk's kept props once; later entries reuse them (the harness times the
  first).
- CPU preparation (`gpuTerrainLoadBenchmark`, Valencia region 64,16,32,32, 8 workers): the Enhanced chunk checksums
  are unchanged (DISTANT `f1c708af`, FULL `35b92066` before and after); a Classic build prepares the columns only,
  18-25 ms for the 1024 hexes with the column checksum unchanged (`d374c543`), where before it prepared the 3D chunks
  as Enhanced does (1.2-2.3 s per level) and built the columns on the render thread at the first Classic frame.

## Choosing the right place to investigate

If a stationary view rebuilds terrain, follow request equality, generation and
support-cache misses. If panning rebuilds page buffers, inspect source-range
identity and page membership. If geometry is correct but old flow, road masks or
biome appearance remain, inspect replacement material bindings and chunk-owned
textures. If an edit loses newer work, inspect the generation check at installation.

More triangles, more submissions, CPU preparation and GPU execution are separate
costs. A change to one does not establish a whole-frame improvement. Keep runtime
diagnostics in the existing rendering paths and keep measured claims scoped to
what was actually measured.

## Imported legacy scenery (2026-10-07)

Batch 9 (2026-10-10): every place that loads a legacy board converts it
(`BoardSceneryLayouts.convert`; docs/3d-board-editor.md, Legacy conversion), so no
legacy render exists any more and the "legacy" figures in this and the following
sections are history. The benchmark that measured them is now
`measuresValenciaInTheGameAndTheEditor` (see "Batch 9: one conversion, one capture" at
the end).

Opening a legacy board in the editor, or saving a game board as `.board2`,
imports its scenery tokens as placed objects (`BoardSceneryLayouts.importBoard`).
Outside the editor those objects were built as ordinary props: batched with the
other scenery, faded with their building, and plain trees in the instanced tree
stand. The editor kept every object an editable instance and drew its parts
instanced (see "Editor objects drawn instanced" below). Since batch 13 every view
draws them the editor's way ("Batch 13: one object renderer" at the end).

Valencia City (128x192), legacy `.board` against its import (4,484 objects in
479 groups; maglev and seaport stay legacy tokens). Measured by
`GpuLegacyImportSmokeTest.measuresValenciaLegacyAgainstImported`
(`gpuBoardBenchmark`), hidden 1600x1000 window, 60 frames after the terrain
settled, `glFinish` after each frame, two runs on the same laptop:

| | legacy | imported |
|---|---:|---:|
| `importBoard` | — | 573–609 ms |
| Board packet (`ObjectOutputStream`) | 4,030,278 bytes | 4,476,056 bytes (+11%) |
| XStream savegame XML of the board | 35,958,847 chars | 39,359,518 chars (+9%) |
| Map preview, whole-board overview, median frame | 12.8–14.0 ms | 12.6–13.1 ms |
| Map preview, close view of the densest hex, median frame | 4.5–5.3 ms | 3.0–3.5 ms |
| Editor, whole-board overview, median frame | 12.8–13.0 ms | 22.9–23.5 ms |
| Editor, close view, median frame | 4.8 ms | 5.9 ms |

The preview (the same chunk path as a game) shows no cost from the import in
these views. The editor rows are the state before the editor drew its objects
instanced; see below for the current numbers. The import
resolves artwork only for hexes with a FLUFF, `road_fluff` or `road:2` token. In
the opt-in `LegacySceneryCorpusTest`, whose test JVM runs with JaCoCo coverage,
Valencia's import took 7.9 s before that skip and 6.0 s after it, with identical
corpus totals (755 boards, 102,586 objects, 14,747 groups); the 0.57–0.61 s above
is the benchmark JVM without coverage, measured only with the skip. These are
local runs, not general guarantees; the close view is centred on hex 3509.

### Editor objects drawn instanced (2026-10-08)

The editor drew each part of each placed object as its own renderable: 5,136
imported Valencia objects gave about 9,000 opaque draws, and the frame was limited
by draw submission. Now `GpuTerrain.Chunk.cacheDecorations` splits the parts of the
placed objects: a part whose world transform is a place, a turn about z, one
horizontal and one vertical scale (`GpuTreeInstances.placeable`) and whose material
has an instanced shader goes to `GpuTreeInstances.add(Array<Renderable>)`, the path
the game uses for shared building modules, in the colour, depth and shadow passes;
tilted, mirrored and unevenly stretched parts still draw one by one. Plain placed
trees stand in the chunk's tree stand with its detail levels (`Chunk.restand`), as
in the game; a drag preview refills the stand with new arrays, and a part preview
only changes the gathered instance data. Nothing is packed, so an edit still
rebuilds only its chunk; the commit keeps the object meshes' instanced batches
(`retainParts`).

Same benchmark (now also printing a profiled frame's GL draws per render stage),
Valencia City imported (5,136 objects in 616 groups), editor; two runs per stage,
each started while no other build of this repository ran:

| Editor, imported | Median frame | p90 | Opaque draws | All draws |
|---|---:|---:|---:|---:|
| Overview, before | 24.3, 24.2 ms | 32.1, 28.4 ms | 9,084 | 9,401 |
| Overview, parts instanced | 12.4, 14.4 ms | 15.0, 19.8 ms | 2,851 | 3,141 |
| Overview, and trees in the stand | 12.2, 12.4 ms | 15.0, 15.1 ms | 2,842 | 3,132 |
| Close view (3509), before | 5.2, 5.7 ms | 5.9, 6.5 ms | 963 | 1,129 |
| Close view, parts instanced | 3.8, 4.4 ms | 4.4, 5.6 ms | 165 | 304 |
| Close view, and trees in the stand | 3.4, 4.0 ms | 3.9, 4.8 ms | 163 | 302 |

In the same runs the imported map preview overview took 12.4–16.5 ms (2,758
opaque draws) and the legacy editor overview 11.1–13.5 ms (2,561). Instanced at
full detail, the placed trees already took one draw per species and part, so the
stand saves only 9 draws here; it adds the trees' detail levels. The benchmark fails when an editor
view needs more than the preview's opaque draws plus 150. These are local runs on a
shared laptop, not general guarantees.

### Cheaper drags and shared per-frame work (2026-10-08)

Three further stages of the editor plan, measured on imported Valencia City:

- **Drag previews read only what changed.** `GpuTerrain.previewEditorObjects` used
  to index every placed object on the board and visit every prop of every chunk
  for each new tile list, then scan every prop again for objects that crossed a
  chunk boundary. A chunk now remembers the tiles its props show (set when the
  chunk is built), the changed objects are found by comparing tile references
  (`changedPlacements`; the map source keeps unchanged tile objects), each chunk
  indexes its props by object id, and only chunks that show a preview are scanned
  for crossed objects. `GpuAtmosphere.configureClouds` likewise checks only the
  changed tiles for magma.
- **Animation skips idle chunks.** `GpuTerrain.animate` no longer builds maps and
  sets and scans every prop of every chunk each frame: a chunk without units in
  its bounds, without a hovered building interior and without faded props, and
  with an unchanged building opacity, has nothing to fade or restore.
- **Cover candidates only when cover can draw.** `renderCover` gathers the
  candidate hexes only when grass or crops/reeds can draw at the camera's scale
  (`GpuBiomeVegetation.visibleAtScale`, like `GpuGroundCover.visibleAtScale`).

A coarse level for generated industrial structures (half the facets, chamfered
bends, open small pipes, no coil or footing plates) was built and measured, then
removed: on Valencia it cut the 722 structures from 3.46M to 1.13M triangles
(overview: 252 to 127 draws, 10.4M to 3.4M indices), but in five alternating pairs
in one process the overview frame was 14.7 ms at full detail against 14.3 ms
coarse, and the opaque stage's GPU time 9.8 against 9.1 ms, within the run-to-run
spread. The opaque stage is limited by draw submission (about half of the render
thread's samples are `glBindVertexArray`), not by vertices.

Scratch harness (hidden 1600x1000 window, glFinish per frame, the benchmark's
views; the close view is hex 3509), runs alternating between the code before these
stages (including other work in progress at that time) and after them, started
only while no other build ran. The laptop was slower during these runs than for
the table above: the unchanged map preview overview measured 15.3–16.8 ms here.

| Imported Valencia, editor | Before | After |
|---|---:|---:|
| Drag a 23-object group, previews per tile-changing step (timed with `-Dperf.timePreview`) | 6.4, 8.0 ms | 1.6 ms (one run) |
| Drag, first frame after a tile-changing step (median) | 38.0, 34.6 ms | 20.7, 24.6 ms |
| Drag, release to settled | 507, 418 ms | 435, 409 ms |
| "Cutaways and light" stage, CPU median, overview | 1.00, 0.90 ms | 0.24, 0.20 ms |
| Overview frame median | 20.8, 18.9 ms | 16.2, 17.4 ms |
| Close view frame median | 6.0, 5.2 ms | 4.3, 4.5 ms |

The "cutaways and light" stage fell the same way in the legacy editor (1.46, 1.04
to 0.21, 0.28 ms) and the imported map preview (1.03, 1.05 to 0.22, 0.25 ms); their
frame medians stayed within the spread (legacy editor 24.4, 15.5 to 13.8, 21.2 ms;
imported preview 16.3, 16.8 to 16.1, 15.3 ms). The drag group is the one at hex
3509, moved in 60 pointer steps of 9 pixels, 28 of which changed the tiles. Only one
timed run measured the previews after these stages (1.6 ms); the other timed run of
that stage ran on an earlier build (4.3 ms) and the final runs did not time them, so
the final code's preview time is not measured. The first frames after a step stay
above 20 ms, and each step still redraws the changed chunks' geometry shadows
(3.7, 4.6 ms CPU and 4.2, 5.6 ms GPU median in the final runs): refreshing them only
on release, or every few steps, is the next candidate and has not been measured.

`gpuBoardBenchmark` `measuresValenciaLegacyAgainstImported` afterwards, two runs:

| View | Overview | Close view | Opaque draws (overview / close) |
|---|---:|---:|---:|
| Imported, editor | 17.2, 17.8 ms | 4.5, 6.0 ms | 2,898 / 163 |
| Imported, map preview | 15.4, 22.9 ms | 5.0, 3.9 ms | 2,815 / 138 |
| Legacy, editor | 13.7, 17.3 ms | 3.7, 5.2 ms | 2,617 / 126 |
| Legacy, map preview | 20.2, 16.1 ms | 7.3, 5.8 ms | 2,619 / 126 |

On this slower day the imported editor overview did not stay below 13 ms; it ran
at the map preview's level. Still expensive: the opaque stage's 1,506 building page
draws over 23 meshes, split by 368 material values, and their vertex-array binds;
during a drag, each committed step rebuilds its chunk on the render thread, where
re-packing the chunk's road masks (`GpuTextures.update`) took about a tenth of the
samples. These are local runs on a shared laptop, not general guarantees.

### Valencia re-measure after the decal batch (2026-10-08)

After the decal batch (emblems and standard car parks imported as decals), every
placed decal was its own draw in every chunk it reached: imported Valencia drew 398
paint draws at the overview, and the imported editor's opaque draws rose from 2,898
to 3,098. Two changes in `GpuTerrain` bring them to 84:

- `Layer.paintGroups`: a chunk draws its paint in painter order, one draw per run of
  the same image. A decal joins the latest draw of its image unless a decal of another
  image drawn after that one shares a hex with it; one draw blends its triangles in
  index order. The draws are numbered (`GpuDecalOrder.layer`): 398 to 150 draws, 149
  being the sum of distinct images per chunk.
- `paintPages`: chunks never paint the same hex, so the same numbered draw of nearby
  chunks merges in `GpuTerrainPages` (isometric camera only, like the terrain pages):
  150 to 84 draws. A paint edit rebuilds only its page.

`GpuDecalBatchingSmokeTest` checks that a decal painted between two decals of another
image stays under the later one in the paged view. In `GpuDecalReviewSmokeTest` the 39
emblem decals add 16 draws to the overview instead of 45; the imported template board
draws 52 instead of 114 and the Perdition Spaceport crop 116 instead of 223.

`gpuBoardBenchmark` `measuresValenciaLegacyAgainstImported`, four runs before (machine
busy with other builds) and four after (`-Dmegamek.gpu.card=DISCRETE`, started only
while no other build or test ran). Frame medians and p90 per run, in ms; draws are
opaque / all:

| View | Before: median (p90) | After: median (p90) | Draws before | Draws after |
|---|---|---|---:|---:|
| Imported editor, overview | 15.7 (20.4), 16.2 (21.2), 13.7 (16.5), 15.3 (20.9) | 11.7 (14.6), 12.1 (14.6), 12.0 (14.7), 14.5 (18.3) | 3,098 / 3,389 | 2,784 / 3,075 |
| Imported editor, close | 4.7 (6.0), 5.1 (6.2), 4.3 (5.0), 4.2 (4.8) | 3.1 (4.2), 3.6 (4.3), 3.4 (4.0), 4.6 (6.2) | 174 / 314 | 156 / 296 |
| Imported preview, overview | 14.8 (18.2), 14.3 (17.9), 15.3 (25.1), 15.5 (18.5) | 11.7 (14.5), 11.3 (14.7), 10.9 (12.8), 10.6 (12.1) | 3,015 / 3,208 | 2,700 / 2,893 |
| Imported preview, close | 3.4 (4.1), 2.8 (3.3), 3.2 (3.7), 3.1 (3.9) | 2.8 (3.3), 2.6 (3.1), 2.3 (2.6), 2.4 (2.8) | 149 / 197 | 131 / 179 |
| Legacy editor, overview | 13.5 (18.5), 16.2 (24.7), 16.3 (22.8), 16.4 (24.8) | 11.5 (14.5), 10.9 (17.2), 10.7 (13.4), 12.4 (19.7) | 2,617 / 2,908 | 2,618 / 2,909 |
| Legacy editor, close | 4.4 (7.4), 4.5 (5.7), 4.4 (5.2), 5.5 (7.3) | 4.4 (5.4), 4.1 (5.0), 4.0 (4.7), 4.8 (6.1) | 126 / 266 | 126 / 266 |
| Legacy preview, overview | 17.3 (24.5), 16.4 (22.8), 16.8 (21.2), 14.7 (18.1) | 13.3 (17.1), 15.1 (19.5), 12.0 (15.6), 12.3 (14.8) | 2,617 / 2,810 | 2,617 / 2,810 |
| Legacy preview, close | 6.6 (9.2), 4.6 (5.9), 4.6 (6.3), 4.8 (6.1) | 5.8 (6.4), 4.1 (4.8), 5.6 (6.8), 6.2 (6.9) | 126 / 174 | 126 / 174 |

The legacy views did not change, so the machine was slower during the "before" runs.
A fair comparison is the scratch harness (same frames, `glFinish` per frame, 120
samples), alternating the code before, the code after and the legacy editor, three
rounds, imported editor overview median: before 13.7, 14.4, 13.5 and after 13.1,
12.3, 12.7 ms (with an extra change described below) in one session; before 17.5,
15.4, 16.2 and after 13.2, 14.8, 14.8 ms in a slower one, against the legacy editor's
10.8, 10.9, 12.1 and 11.6, 11.6, 12.7 ms.

Against the target (imported editor overview at most 13 ms, within 0.5 ms of the
legacy editor in the same runs): at most 13 ms in three of the four final runs;
within 0.5 ms in one (+0.2, +1.2, +1.3, +2.1 ms). Not met. (The legacy editor's draws
rose by one, 2,617 / 2,908 to 2,618 / 2,909.) Retired in batch 9: no legacy editor
exists to compare against. The benchmark then compared the game view and the editor
view of the same converted Valencia, which differed only by the draw strategy of placed
objects; since batch 13 they draw placed objects alike (see the end of this file).

What the remaining difference is, measured in the harness:

- Removing the editor's instanced object parts (189 instanced draws over 4,250 parts)
  takes the overview from 12.9–13.5 to 10.1–10.6 ms in the same process, and the
  opaque stage's GPU time from 8.8 to 6.9 ms. Without them the imported editor is
  faster than the legacy editor.
- Cutting every instanced part to one triangle (same draws, no geometry) leaves the
  frame where it was (11.7 and 11.4 against 12.6 and 11.5 ms; 9.4 and 9.5 ms without
  them): the cost is per draw, not the 1.7 million triangles.
- Gathering the instances each frame takes 0.14 ms, and no instance buffer is uploaded
  while the view is still. Reusing each instanced part's shader instead of looking it
  up every frame changed nothing measurable.
- Keeping the instance attributes in each mesh's vertex array between draws (libGDX
  enables, points and disables them at every bind) removed about 1,800 of 44,100 GL calls
  but did not change the frame time beyond the noise, so it was not kept.
- The imported map preview, which packs the same objects into prop pages, measured
  10.6–11.7 ms in the final runs: at or below the imported editor in each run, and
  between 1.8 ms below and 0.4 ms above the legacy editor of the same run. Most of the
  difference to the legacy views is drawing the imported objects at all: the legacy
  board shows them as flat tile art.

These are local runs on one shared laptop (RTX 4070 Laptop GPU), not general
guarantees. During this work a saved board preference (`BoardView3DGraphicsCard`)
switched later runs to the integrated GPU; the "after" runs and the harness rounds
above forced the NVIDIA card and were checked to use it, and the "before" runs
predate that preference.

### Small objects at a distance, one mesh per recolourable model (2026-10-09)

The two options the user approved for the remaining editor-overview gap (packing the
editor's objects into prop pages stays rejected):

- **Object LoD.** A decorative placed object (a `.board2` object or, from batch 9 to batch 13, a
  terrain-owned layout row such as rubble) whose world-box diagonal is under a quarter hex is small
  (`GpuTerrain.Prop.small`, set when its hex is built). Small objects are skipped
  while a hex is narrower than 12 framebuffer pixels: they hide below 10.8 px and
  return above 13.2 px (`TreeLod.SMALL_OBJECTS`, the tree levels' hysteresis), in
  orthographic views only. One decision per camera (`updateDetail`) and one
  predicate (`GpuTerrain.drawn`) serve every pass, picking and box selection.
  - Editor: each instanced or loose part keeps its object (`Chunk.decorationOwners`,
    `looseOwners`) and the gathers skip hidden objects: no re-cache, no per-edit
    work, and the commit still retains every part's instanced batch. Selected
    objects and those hovered in Layers or the side view are pinned by id
    (`GpuTerrain.keepShown`), so pinning re-caches nothing.
  - Game and map preview: each chunk packed its small props into caches of their
    own at the end of its prop pages, so hiding them rebuilt no page. Batch 13
    removed those caches once placed objects left the pages ("Small packed caches
    removed", below).
  - Shadows: the shadow pass applies the rule whenever it redraws; a crossing alone
    does not redraw it, as for tree levels and scatter. A forced redraw would drop
    the cached board-wide shadow map on every zoom-out to the overview, which the
    `GpuTerrainShadows.render` comments record at 220–530 ms on the Iris Xe. Hidden objects are
    at most about 3 px, and so are the shadows that wait for the next redraw.
- **One GPU mesh per recolourable model.** The editor drew a mesh copy per colour set
  (`GpuAssets.model(name, colours)`), so 18 car colours were 18 instanced draws per
  pass. It now draws one shared mesh per slotted model (`GpuAssets.slotted`,
  `RigidGlb.colourSlotMesh`, with each vertex's shade relative to its slot's default
  and its slot); each placement passes its four slot colours in a third instance
  attribute (`a_instance2`; a uniform for parts drawn one by one and the side view),
  and the vertex shader applies `RigidGlb.recolour`'s rule
  (`model-colour-slots.glsl`). The 2026-10-12 color-pipeline update keeps model
  colors and shade ratios linear, decoding only the replacement sRGB color.
  The game and the map preview kept their
  CPU copies until batch 13: their packed pages already merged the copies into the same
  draws.

Checked by native tests (not timed):

- `GpuSmallObjectSmokeTest`: at a 9 px hex exactly the fixture's cars disappear from
  the colour and shadow passes, in the editor and the game; at 16 px nothing does;
  crossings rebuild no prop page; at 16 px the rule adds no draw, and at 9 px the
  game draws as many times as the same board without the cars. The editor's upright
  cars of two different colours cost one instanced draw.
- `GpuSceneryColourSmokeTest`: the editor's opaque pass draws the colour fixture
  109 times both with every object in its model's own colours and with the fixture's
  colours; the editor's close-ups of its 66 hexes, a mirrored car and a car in a
  colour far from the model's included, match the game view's CPU-recoloured copies
  (0 pixels over 2/255, 2 on one hex).

Valencia's frame times and draws with these changes are measured separately (the
batch's verification); this section claims no time gain. Limits: what Object LoD
hides depends on the window's pixels (at 1600×1000 and 1080p Valencia's fitted
overview, 8.3–9 px a hex, hides small objects; a 1440p overview opens at about 12 px
with them shown; a 4K overview hides nothing). A selected small object casts no
shadow while the zoom hides the others. The imported editor still draws more than
the legacy editor: both lose their small objects, and the rest is the difference
between instanced and packed objects.

### Batch 9: one conversion, one capture (2026-10-10)

Every load site converts a legacy board once (`BoardSceneryLayouts.convert`, image-free
common code): the server per sheet before flip and combine (`BoardFile.loadSheet`), old
saves when they load, the map previews (`GpuBoardWindow.loadPreview`) and the editor.
Every 3D view captures through one owner, `BoardTiles`. So the game view and the editor
view of one board differed only by the draw strategy of placed objects: instanced with
per-instance colours in the editor, packed prop pages with CPU recolouring in the game
and the previews (b9-decisions D9, KEEP IN SYNC comments on both sides;
`GpuSceneryColourSmokeTest` held them to 1/255 per pixel but for at most 8 edge pixels
per capture). Batch 13 replaced the two strategies with the editor's (below).

`gpuBoardBenchmark` `GpuLegacyImportSmokeTest.measuresValenciaInTheGameAndTheEditor`
replaces `measuresValenciaLegacyAgainstImported`. It prints Valencia's read, its sheet
load (read and convert) and the editor's open, the Board packet and savegame XML sizes
as read and converted, and for the game view (`GpuBoardSource` through
`GpuBoardFixture`, which also places one Atlas) and the editor view
(`BoardEditorSession.open` and `GpuMapSource`) the settle time, the median and p90 of
60 frames with `glFinish`, and the draws and GL calls per render stage, at the overview
and at the close view of the densest hex. It fails when the editor's opaque draws
exceeded the game view's plus 150 (retired in batch 13, when both views began to draw
placed objects alike). Captures:
`legacy-import-valencia-{game,editor}-{overview,close}.png`. Not run in the step that
wrote it; the batch-9 verification times it.

Measured in batch 9's conversion gate (a headless JVM, no rendering): converting
Valencia took 378–419 ms in a fresh JVM (class loading and the first parse of the
tileset, `layouts.json` and `decals.json`) and 59–95 ms warm, against 653–710 ms cold
and 4.0–7.4 s warm for the old image-based import, whose warm runs waited on animated
GIFs; all 2,422 shipped boards converted in 16.3 s in one JVM. With its legacy tokens
kept in memory, converted Valencia's packet is 4,851,842 bytes (+20.4 % against the
board as read) and its savegame XML 42,674,160 characters (+18.7 %); 48x48 grows by
80.5 % and 66.0 %. These are local runs on one laptop, not general guarantees.

### Batch 13: one object renderer (2026-10-11)

Placed objects (`.board2` objects, converted legacy scenery, the buffer stops a rail
owns) draw the editor's way in every 3D view: game, lobby and map previews, the editor
and the Tactical View. Each model is one shared mesh per mesh
level (`GpuAssets.slotted`, `GpuAssets.objectLevels`); each placement passes its place,
turn, scales, mirror and slot colours as instance data (`GpuTreeInstances`), gathered
per pass from the chunk's object parts (`Chunk.cacheDecorations`, `addDecorations`).
Parts whose pose the instance data cannot hold (tilted, sheared, unevenly stretched) or
whose material has no instanced shader draw one by one. Everything else that was packed
stays packed: buildings' non-modular props, industrial structures, layout scenery rows,
scatter, bridges and routes. The user decided this after a benchmark of the two
strategies (b13-scope item 6; it replaces b9-decisions D9).

What went: the `editableObjects` flag, its setter and `GpuBattleView`'s call; the
`assets.decoration` branch for placed objects and its per-level variant (the CPU colour
copies of placed objects); placed objects in the packed chunk caches and prop pages;
the KEEP IN SYNC pairs that only served the two strategies (`GpuAssets.slotted` /
`decoration`, `Chunk.cacheDecorations` / `cacheProps`, the build switch, the fade-rule
note); and the pixel-parity test between the editor and the game. The remaining pairs
now describe placed objects against packed buildings and scenery (`drawn` and the packed
caches' Tactical View rule, `addDecorations` and the packed caches in each pass) and the GPU recolour
against the CPU copies that layout rows and editor ghosts still use
(`RigidGlb.recolour` / `model-colour-slots.glsl`, compared per vertex by
`RigidGlbTest`). `GpuAssets.decoration` remains for the editor preview's ghosts and the
marked box of a missing file.

**Mirror images.** The benchmark's main risk was mirrored objects: on the all-mirrored
48x48 stress the editor path drew 3,362 loose parts one call each (8,433 draws against
the packed path's 2,043; +36 % frame time on the Iris Xe overview). Now a mirrored
placement is instanced too: the instance's last float marks the mirror, which reverses
the model's y axis before the turn (`tree-instances.glsl`), and mirror images go to a
batch of their own (`GpuTreeInstances.Part` keyed on the mirror) whose mesh draws with
`glFrontFace(GL_CW)` (`GpuInstancedMesh.mirrored`), so every pass culls the faces it
culls for the model unmirrored. Instanced parts therefore keep their model's own culling;
a mirror image drawn one by one or faded keeps both faces or the reversed face as before
(`GpuTerrain.culling`). The tree stand still takes no mirror image (its species batches
keep the counter-clockwise front face); a mirrored plant draws as an object part. The scratch harness on the
all-mirrored 48x48 after the change (Iris Xe, 3840x2359): 1,821 draws in a profiled overview frame, against 1,911
with no object mirrored in the same build (city 1,448 and 1,442, dense 1,287 and 1,277). Frame medians of 31-34 ms
mirrored and 31-33 ms unmirrored at the overview are indicative only: another session's test JVM ran at the time.

**Level changes.** A placed object's mesh level change now gathers only the chunk's
object parts again; buildings still repack the chunk's prop caches
(`GpuTerrain.updateDetail`). The shadow map keeps the previous level until its next
refit, as before. After the level change of a faded (hovered-roof) mirrored tree, consecutive frames took a few frames
to become identical again (2 pixels by 2/255 on its trunk; cause not isolated), so the orchard round trip of
`GpuSceneryColourSmokeTest` compares settled frames.

**Tactical View.** It hides placed plants as it hides every other plant (the game's
former rule, now also the editor's): a placed plant is no structure (`Prop.structure`),
and `GpuTerrain.drawn` applies the Tactical View's rule to drawing and picking alike.

**Picking data.** Every view keeps each placed object's id (not for what a rail owns),
so GM tools can use them; game picking (`selectionHit`, `walkableHit`) is unchanged and
never reports an object, only `decorationHit` does, which only the editor calls.

**Combat scars.** A scar patch on a placed object copies the shared mesh's vertices, so
it carries its placement's colours (the receiving part's `userData`) like a part drawn one
by one; such patches stay out of the merged scar pages, which hold no per-object colours
(`GpuSurfaceScars`). Before batch 13 the game's patches copied the CPU colour copies.

**Small packed caches removed.** With placed objects out of the pages, the packed small
caches could only hold terrain-owned layout rows under a quarter hex, and no shipped row
is that small (rubble, fortification and geyser rows span most of a hex): 36, 102 and 137
small packed parts on the benchmark boards before the switch, 0 after. The user decided to
delete them. Only placed objects are small now (`Prop.small`); a layout row of any size is
packed with the chunk's other props and never hides with the zoom.

Benchmark that led here (`GpuObjectPathBench`, a scratch harness, 2026-10-11, the
user's laptop: Iris Xe and RTX 4070 Laptop 8 GB, 3840x2359, seven boards with 145 to
4,965 objects; the independent check's corrections applied):

| | Packed (game) | Instanced (editor) |
|---|---|---|
| Frame time, NVIDIA, one terrain in memory | 1 | median 1.010 (quartiles 0.971-1.040) |
| Frame time, Iris Xe, clean rounds | 1 | 0.97-1.04 by view (48x48), 0.97-1.02 (Tian-Tan) |
| GPU time | 1 | median 0.992; 2-8 % lower on large overviews |
| CPU time per frame | | +0.2 to +1.3 ms in close views |
| Pixels | | under 0.02 % of a frame differ, all on building seams, as between two builds of one path |
| Object geometry (MiB) | 48x48 48.8, Tian-Tan 312 | 6.6 + 4.3, 33.5 + 42.7 |
| Edit to final frame | | 5-30 % sooner, worst frame about halved |

Two-terrain runs on the 8 GB NVIDIA were video-memory artefacts and are not quoted; no
difference under about 5 % per view is resolvable with 4 rounds a view. Not measured:
units, the HUD, a tilted perspective camera, cutaway fades and LoD-switch sweeps.

Measured after the switch (2026-10-11, scratch harness `AbBench`): the game view before
(b13's base, placed objects packed) and after (instanced), each version's classes in its
own class loader, so both alternate A B B A on one GL context in one process; 3840x2359;
every timed quad, round and edit batch repeated until no other test or benchmark JVM ran
(a first 48x48 run that overlapped one was discarded). Frame median before / after ms,
with the after/before ratio of the four quads (true median):

| Board, GPU | Overview | City | Dense | Pan | GPU time ratio |
|---|---|---|---|---|---|
| 48x48, Iris Xe | 44.0 / 41.9 (0.97) | 41.0 / 40.4 (0.97) | 30.5 / 30.3 (1.00) | 36.5 / 36.4 (1.04) | 0.95-1.01 |
| Tian-Tan, Iris Xe | 228.8 / 227.6 (0.99) | 38.7 / 40.2 (1.02) | 43.1 / 42.9 (1.00) | 42.0 / 42.9 (1.04) | 0.97-1.02 |
| Valencia, Iris Xe, one terrain, 4 rounds | 274.9 / 261.8 (0.97) | 45.5 / 45.5 (0.98) | 62.9 / 62.0 (1.02) | 46.5 / 52.2 (1.01) | 0.95-1.00 |
| 48x48 all mirrored, Iris Xe | 39.4 / 41.4 (1.06) | 35.2 / 35.1 (0.98) | 30.2 / 31.2 (1.05) | 35.8 / 37.1 (1.03) | 0.98-1.03 |
| 48x48, RTX 4070 Laptop | 4.6 / 5.0 (1.08) | 4.6 / 4.8 (1.04) | 4.4 / 4.5 (1.05) | 4.3 / 4.5 (1.05) | 0.89-1.00 |

- CPU per frame: -1.4 to +2.8 ms on the Iris Xe (median +0.7 ms over the 16 views), +0.2
  to +0.5 ms on the NVIDIA (the frames end with `glFinish`, so this is an upper bound).
- All-mirrored 48x48: 1,821 draws in the profiled overview frame against the packed
  path's 2,043 (the editor path drew 8,433 before mirror images were instanced).
- Object geometry (chunk caches, pages, instance buffers, models): 48x48 141.9 to 56.0 MiB,
  Tian-Tan 1,210 to 695 MiB, Valencia 1,493 to 1,183 MiB; no colour copies remain (17, 2
  and 31 before), and no shipped board packs a small prop any more (36, 102 and 137
  small packed parts before, all placed objects).
- A move of a recoloured car (8 samples each, Valencia 16), median time to the final frame
  and worst frame: 48x48 348 / 347 ms and 102 / 65 ms; Tian-Tan 572 / 644 ms (ranges
  505-776 and 554-873) and 98 / 83 ms; Valencia 1,280 / 1,289 ms and 201 / 149 ms; NVIDIA
  48x48 164 / 125 ms and 65 / 22 ms.
- Pixels: a second capture of the before path is identical to the first in every view;
  before against after differs in 0 to 349 pixels of 9.06 million (largest channel
  difference 122/255), all on roof and ground seams where draw order decides; on the
  all-mirrored stress also on thin edges of open mirrored meshes, whose back faces the
  packed path drew (culling none) and the instanced mirror image culls as its model.

Checked by native tests (not timed), all in the game view unless named:

- `GpuMirroredObjectSmokeTest`: a mirrored parking barrier (off its model's centre),
  three mirrored recoloured cars and a mirrored coral draw instanced as mirror images
  (no part one by one), in one draw per model part (the same cars drawn one by one cost
  two more draws per car part), and alike their one-by-one draw with both faces (a tilt
  of 0.05 degrees): barrier 6 of 7,673 object pixels over 32/255, car 30 of 7,749, coral
  766 of 60,245 (its fine self-shadowing); unmirrored, the barrier and the coral differ
  in 15,341 and 45,202 pixels.
- `GpuSceneryColourSmokeTest`: the converted colour fixture loaded the game's way draws
  205 object parts instanced (3 of them mirror images) and 2 one by one, each with its
  object's colours; the magenta mirrored car shows 3,016 magenta and no red pixels and
  the white car 2,957 white ones, as the editor showed them before; levels, the hover
  of the roof's building and its end as before; the Tactical View draws and picks no
  placed plant (3 plant parts in 3D, 0 in the Tactical View, 204 other parts in both),
  while game picking and the hover outline still find the cars' hexes. A combat scar on a
  magenta car draws magenta: of 15,720 changed pixels 3,223 magenta and 16 red (without
  the placement's colours on the patch, or with the patch merged into a scar page: 3,239
  red, 0 magenta).
- `GpuSmallObjectSmokeTest`: the cars hide and return in every pass as before; the upright cars of
  three colours cost one instanced draw and the mirrored car one more; a unit fades the lone car without
  rebuilding a prop page.
- `GpuLegacyImportSmokeTest`: 84 object parts of the converted fixture, 81 instanced and 3 one by one (a
  tilted and a stretched table and the narrow glass roof; the mirrored table instanced); a unit inside the
  roof's building fades its roof objects; 48x48's roof cars stand on the roof.

## Direct full-quality shader experiments (2026-11-02)

Two shader-only experiments were tested on Citadel with the terrain cache disabled. Neither demonstrated a
repeatable improvement, so both were removed. No production shader variants, quality reductions or shader changes
from these experiments remain.

The first explicitly reused the view's integrated GGX lookup and specular albedo across diffuse lighting,
multiscatter, the finite sun and reflected sky. The second skipped side-projection texture reads on cliff shoulders
where the existing top weight was exactly one, retaining the same top samples, normals, roughness, emission and AO.
Static independent review found no equation/derivative mismatch; neither experiment was accepted for performance.
The measurements do not establish whether the driver already reused the lighting calculations or whether changing
their lifetime affected register use.

Measurements use the RTX 4070 Laptop GPU, a 3840 x 2259 framebuffer, ENHANCED graphics, normal maps, tessellation,
specular AA and AO on, parallax and VSync off, fixed clear daylight, and frozen Swing capture. The mid view is the
reported azimuth 26.16, tilt 47.94, span 1073.83, at 2092.52,-2967.17,54.00. The close view retains those angles with
span 451.80, at 1600.16,-1872.00,0.00. No cheaper material shader was substituted.

Each comparison loads one scene and switches its installed shader programs in A/B/B/A order, with 120 warm-up
frames and 360 measured frames per block. Shader compilation occurs before warm-up. Draw counts and geometry stay
the same within each comparison. These are whole-frame GPU medians in milliseconds, with both blocks shown rather
than selecting the fastest sample:

| Experiment | View | Original A1 / A2 | Candidate B1 / B2 |
| --- | --- | --- | --- |
| Explicit lighting reuse | Mid | 21.96 / 22.32 | 21.22 / 22.06 |
| Explicit lighting reuse | Close | 15.86 / 16.05 | 16.42 / 16.26 |
| Skip zero-weight side projections | Mid | 17.77 / 17.89 | 17.05 / 18.08 |
| Skip zero-weight side projections | Close | 16.47 / 16.49 | 16.34 / 16.79 |

Lighting reuse's close opaque-terrain medians were 12.75 / 12.82 ms originally and 13.21 / 13.00 ms in the candidate
(about 2.5% slower when averaging the two block medians). Other differences overlap the observed variation. These
desktop runs included concurrent development activity and changing laptop load; compare within a row, not absolute
times across experiments. No FPS gain or cross-GPU result is claimed. Animated full-view captures are diagnostic
artifacts, not a claim of pixel-identical output. Since neither change was retained, no visual tradeoff was shipped.

The retained benchmark option is `-Dmegamek.gpu.performanceCompareShaders=<absolute-folder>`: place the original
shader files in that folder before editing, then run
`GpuBoardPerformanceSmokeTest.measuresTheCompleteBoardViewWithLiveCapture` with the candidate in the checkout.
Use `performanceOverviewOnly=true`, `performanceFrozenCapture=true`, explicit camera settings and
`performanceMaterialCache=false` for the direct-rendering comparison. The benchmark waits for any enabled material
cache to settle after a shader swap and rejects simultaneous graphics/cache/page comparisons. It uses the shader
editor's in-memory replacement mechanism; it does not save or modify shader files. Existing
`performanceShaderDirectory` overrides remain available for a single-source run.

Artifacts are under `work/citadel-fps/lighting-abba-{mid,close}` and
`work/citadel-fps/projection-abba-{mid,close}`, with matching logs. Original sources and rejected candidates are in
`work/lighting-reuse`. All four native comparisons completed with the benchmark's GL/readiness checks. During later
runs, unrelated in-progress lobby and UI changes prevented a full Java/test compilation; those runs used the already
compiled renderer, and the final mid comparison compiled the benchmark against frozen test-helper sources. This is
not a full-workspace build validation.

## Authored finish specialization and direct-path diagnostics (2026-11-03)

The sculpt shader now uses the material cache's existing authored-finish eligibility rule for direct rendering too.
Only when every sculpt material has zero metallic/scattering and no emission map or constant glow does its existing
program discard those channels before live effects. Volcanic programs remain excluded because their emission is
procedural; nuclear scorch can still add emission afterward. Fungal props use the separate foliage program. Adding
an active metallic, scattering or emission channel to any sculpt material and reloading assets retains the full
program automatically. This replaces the program for that asset set, without adding another active program per
material. Cache enablement is independent of this decision. The existing rock-role test explicitly opts out because
it intentionally uses emission as a diagnostic data channel.

A same-context original/candidate/candidate/original image comparison on the 32x34 terrain calibration board passed
with **zero RGBA or depth differences** in fit, close, rain and hot nuclear-scorch views. The board includes lava and
fungal ground. The hot comparison freezes only the wall-clock-dependent scorch heat in both source versions while
retaining the real scorch mask. Main/test compilation and the native comparison succeeded. Files and the one-off
comparison source are in `work/shader-workload/plain-parity` and
`work/lighting-reuse/GpuLightingComparisonSmokeTest.java`; its original/candidate shader snapshots are in
`work/shader-workload/plain-baseline` and `plain-production-candidate`.

The corrected performance experiment retains live scorch emission. Same RTX 4070 Laptop settings and reported mid
camera as above, cache off; 600 warm-up and 480 measured frames per block, with mid/close/moving in each ABBA block.
Whole-view GPU medians in milliseconds:

| View | Original A1 / A2 | Candidate B1 / B2 |
| --- | --- | --- |
| Mid | 15.9427 / 17.6671 | 15.5167 / 16.6390 |
| Close | 15.0077 / 15.4214 | 14.5920 / 15.7686 |
| Moving close | 15.8740 / 16.0850 | 15.6426 / 16.4321 |

Mid opaque-terrain medians were 13.0877 / 14.6883 versus 12.5809 / 13.5168 ms. Close was 12.1733 / 12.5307
versus 11.6777 / 12.7109 ms. The first close pair saved about 0.5 ms, but the complete run does **not** establish a
meaningful whole-frame close-view gain; block variation is larger than that effect. This is not a demonstrated
2 ms improvement or a solution to the direct-rendering bottleneck. An earlier cold-only prototype cleared emission
too late and showed larger savings; it was replaced because it would erase live scorch glow. Raw corrected results
are in `work/citadel-fps/plain-safe-abba-mid-close` and its matching log.

Optional `performancePipelineStatistics=true` records one opaque pass after timing has finished. It reports submitted
geometry, shader invocations, clipping and passing depth samples through ARB pipeline queries. Unsupported tessellation
targets are skipped; query objects are disposed after capture. Counts are driver-dependent and not an exact overdraw
ratio. In `work/citadel-fps/pipeline-mid-close`, mid/close opaque passes reported 8.34/9.19 million fragment invocations
and almost the same passing-sample counts. This supports investigating the cost of visible terrain shading rather
than assuming hidden-fragment work is dominant. `performanceAssertPlainGround=true` verifies authored metadata for
source experiments; it is not proof that procedural live effects emit nothing.

Hardware profiling setup is under `work/gpu-profiler`: NVIDIA Nsight Graphics 2026.4 was signature-verified and
extracted there, without installing/changing a driver. After the user authorized an administrator launch, a visible
`GpuProfilerMain` target produced an eight-frame GPU Trace of the steady Citadel close view. GPU clocks were left
unaltered; system-wide counter permissions were not changed. The artifact is
`work/gpu-profiler/cli-trace-close/java_2026_11_03_00_45_18.ngfx-gputrace`; the accompanying `.xls` exports are TSV.
Across its 14.49–15.57 ms frames, average SM throughput was 46.0% of peak, DRAM throughput 18.0%, pixel active warps
27.9% of peak and graphics register-file allocation 71.9%. L1 texture/L2 hit rates were 73.8%/94.9%. These whole-frame
counters justify testing shorter register lifetimes, but do not establish a particular shader's register bottleneck
or an Intel bottleneck. OpenGL source-line shader profiling was unavailable; no spill count was obtained.

## Native Iris Xe validation and smaller-map probes (2026-11-03)

The acceptance target is Iris Xe at native **3840×2259**, including close and moving views; lowering internal
resolution is excluded. NVIDIA is used for initial prototypes. GPU timestamp totals and actual presentation frame
intervals are distinct measurements: hidden `performanceNoFinish=true` runs on Intel queued work far ahead and are
invalid as FPS evidence. Hidden experiments now use `glFinish` and report GPU cost, not pipelined FPS. Visible tests
must remain unminimized; an occluded/minimized run that rendered nothing was rejected by the artwork check.

Intel also exposed an existing instanced-label GL error: libGDX's ordinary `Mesh.unbind` unbound the VAO before
disabling its instance attributes. `GpuHexText.Page` now uses the existing `GpuInstancedMesh`, as other live instance
batches already did, to preserve unbind order. Subsequent native Iris runs passed GL and artwork checks. This is a
correctness fix, not a claimed speed improvement. The benchmark configures a camera only once per logical frame,
so detail-settle retries no longer repeatedly restart Fit and the camera transition. Cache/page controls and graphics
selection also run once: looking up a tuning control reconstructs the tuning rows, which must not happen on every
loading retry. Optional GL debug output stays off by default.

A visible, non-serialized Citadel close run (span 451.80, azimuth 26.16, tilt 47.94, cache off, full quality) measured
123.445 ms median frame interval and 8.00 average FPS, with whole GPU median 122.0716 ms and opaque terrain
110.3022 ms. Its screenshot and 120-sample timestamps passed; this dense direct view does not represent the user's
usual approximately 30 FPS Iris experience. Evidence: `work/citadel-fps/iris-visible-close-camera` and its log.

At the user's request, subsequent small-map testing started with 32×17 Grasslands A BattleMat, then switched to
**16×17 Fire And Ice 2** (`unofficial/Drewbacca`). `work/shader-workload/fire-ice.ps1` fixes its overview at span 1400,
azimuth 45, tilt 54.7356, at 546,-612,162; close starts at the board centre with span 564.75. AO, normals, tessellation
and specular antialiasing remain enabled; parallax, VSync and SMAA are off. All are native-resolution runs.

Initial NVIDIA direct-path diagnostic runs on Fire And Ice 2, 60 warm-up and 120 samples per view, whole GPU medians:

| View | Full rendering | Material-only diagnostic | Lighting-only diagnostic |
| --- | ---: | ---: | ---: |
| Overview | 9.0860 ms | 7.5438 ms | 4.6131 ms |
| Close | 21.0442 ms | 16.5171 ms | 8.7429 ms |
| Moving close | 20.6305 ms | 15.9283 ms | 9.0276 ms |

The diagnostic substitutions retain geometry and other passes but deliberately change sculpt shading; they are
not optimization candidates or acceptable visual results. Compiler dead-code elimination and interactions mean
these totals cannot be added or interpreted as independent material/lighting timings. They show substantial cost
remains without lighting. Raw results are under `work/citadel-fps/nvidia-fireice-{direct,material-only,lighting-only}`.

A no-quality-change scheduling prototype moved the first role's `rockNormal` call before the second role's
evaluation, shortening the relieved projection's lifetime. NVIDIA ABBA, 180 warm-up and 240 samples per view:

| View | Original A1 / A2 | Earlier normal B1 / B2 |
| --- | --- | --- |
| Overview | 9.0552 / 9.0573 ms | 9.0696 / 9.0624 ms |
| Close | 20.5978 / 21.3473 ms | 20.2793 / 21.3453 ms |
| Moving close | 20.0868 / 20.8589 ms | 19.6946 / 20.6868 ms |

This did not establish a substantial gain and was not applied to production. The prototype remains under
`work/shader-workload/register-probes/early-primary-normal`; results are in
`work/citadel-fps/nvidia-fireice-primary-abba`. Material construction needs a larger reduction in repeated work.

A close-cache experiment is prepared only under `work/shader-workload/close-cache-java`: storage uses .05 m base
texels while the .1 m direct-material reference and `GpuAssets.closeMip` remain unchanged. Using the existing
`materialCacheClosePixel` override alone would change all three and would not be a controlled cache experiment.
The prototype shifts the coarse-fill and wall level cutoffs to retain their physical thresholds, keeps the 544 MiB
budget, and has a fixed-time off/on/off image probe prepared for .075, .1, .15 and .25 m pixel footprints. Finer
storage still needs visual validation, particularly normal variance and roughness near the reference footprint.

**No fine-cache speedup or visual-equivalence result is established.** Its three attempted runs
(`nvidia-fireice-finecache`, `-fixed`, `-visible`) were stopped before obtaining valid comparisons. After the first
attempt, the redundant per-scenario setup above was fixed and independently reviewed; the long native stalls
persisted. Thread samples then found `glFinish` and `glGenBuffers`, with NVIDIA repeatedly reporting P8, 210 MHz and
0% utilization. Visible-window capture also timed out. Do not infer a cache regression or gain from those aborted
loads. The prototype is not installed, and the isolated build was restored to production Java sources.

Fire And Ice 2's earlier Iris direct run (`iris-fireice-direct-cached`, despite its label) used cache off only: an
incorrect comparison-property name was ignored. It measured 82.8410 / 207.6808 / 203.1657 ms whole-GPU medians for
overview/close/moving. The following visible cache-enabled run (`iris-fireice-visible-cached`) passed artwork and
GL checks: 57.2172 / 193.1053 / 186.1135 ms whole GPU and 17.06 / 5.12 / 5.32 average FPS. Its overview cached all
eligible top/wall chunks; both close views reported the cache inactive because their pixel footprint was too fine.
These unusually slow Iris figures remain provisional as representative user FPS: the subsequent native stalls and
window-capture failures need resolution, and the user reports approximately 30 FPS in usual play. They do not
establish the 60 FPS target or a successful optimization.

### Direct material evaluation: underground-material control flow

The user narrowed the work to direct opaque terrain; further cache experiments were stopped. All measurements
below render the complete Fire And Ice 2 view at 3840 x 2259 with the terrain cache disabled.

Skipping floor-contour noise above ground and on steep faces did not establish a gain. Its NVIDIA ABBA close GPU
medians were 20.6612 / 20.5240 ms for the original and 21.0483 / 20.2209 ms for the bounds shortcut. It was not
applied. A diagnostic that entirely removed underground-floor materials measured 19.1345 ms close and 18.2518 ms
moving, but changes the appearance and is not an implementation candidate.

The equivalent straight-line rewrite evaluates the selected ground/floor once, returns for a pure material, and
evaluates the second material only inside the actual transition. The two evaluators, material maps, final height
blend, authored finish channels and lighting remain unchanged. It removes the dynamic material-result loop from
the common path; it does not add shader variants. NVIDIA ABBA, 120 warm-up / 180 measured frames per view:

| View | Original A1 / A2 GPU median | Rewrite B1 / B2 GPU median |
| --- | --- | --- |
| Overview | 9.2570 / 9.2570 ms | 8.2964 / 8.3036 ms |
| Close | 20.7094 / 21.1620 ms | 17.7705 / 18.0449 ms |
| Moving close | 20.6029 / 20.9121 ms | 18.0296 / 18.4187 ms |

The paired averages improve by 3.0280 ms (14.5%) close and 2.5334 ms (12.2%) moving. These are whole-view GPU
timestamps, not serialized wall-time FPS. The same in-context edit replaced 82 existing programs in each
direction. Results: `work/citadel-fps/nvidia-fireice-material-straight-abba`.

Fixed-time captures of the same installed Fire And Ice 2 geometry at fit and .075 / .15 m pixel footprints have
zero differing RGBA bytes and identical depth for original/rewrite/restored. The work-only integration probe is
`work/shader-workload/GpuDirectMaterialProbeSmokeTest.java`; captures are in
`work/shader-workload/direct-straight-parity/family-programs`. These establish equivalence for those views;
additional underground/fungal/volcanic transitions, parallax and Intel remain separate validation requirements.
The rewrite introduces two evaluator call sites in place of one loop call site, so Intel compilation cost must
also be checked before treating it as the final design. Iris 60 FPS has not been established.

The straight-line version was subsequently removed from production: its Iris full-window load reached the 300 s
deadline with only 12/72 generic programs linked. The original shader was restored before testing the smaller
material prototypes below. This straight-line experiment did not demonstrate an Intel rendering improvement.

### Compact direct-material evaluation

`compact-floor-roles` replaces the outer ordinary ground/floor evaluator loop with one evaluation. A floor becomes
a composite cover role, leaving the other roles and their settlement calculation in that one evaluation. Ground
and fully floored endpoints retain their behavior. Intermediate floors intentionally differ: that includes deep
slopes whose normal Z remains between .55 and .85, not only the contour between levels 0 and -1. The legacy
volcanic ordinary evaluator remains unchanged. Authored finish maps, emission, metallic and scattering remain
supported. Family selection is scalar; mixed-palette compiler pruning needs separate attention.

The work-only `GpuDirectMaterialProbeSmokeTest` compares identical installed geometry, camera, animation time and
program counts. It now supports whole-scene GPU timestamp pairs and ABBA edits independently of the full window's
loading UI. It includes terrain, vegetation, shadows, AO, water and atmospheric composition, but omits labels/UI
and sun glare. Its timings therefore are not interchangeable with the earlier full-window measurements. Native
NVIDIA results, cache disabled, parallax enabled, 100 warm-up / 120 measured frames:

| View | Original A1 / A2 GPU median | Compact B1 / B2 GPU median |
| --- | --- | --- |
| Fit | 9.9748 / 9.9584 ms | 8.9661 / 9.0470 ms |
| Close, .089285714 m/pixel | 19.4580 / 19.6803 ms | 17.3804 / 17.3906 ms |
| Mid, .15 m/pixel | 12.8461 / 12.5635 ms | 11.2957 / 11.3183 ms |

This saves 2.1837 ms close and 1.3978 ms mid on NVIDIA. Each edit replaced the same 73 programs, with 28/28 family
programs ready. The largest native color-channel difference was 9/255; the close view had only 150 channel values
differing by more than 1/255, and mean absolute RGBA difference was .00002568 byte. All depths were identical and
restoring the original restored every pixel. Results: `work/shader-workload/direct-compact-native`.

At 1600 x 1000 with parallax enabled, fit and .075/.15 m pixel footprints had maximum differences 1/0/4, respectively,
and identical depth (`direct-compact-parity`). The underground multi-family check of the final source is below.
The native Iris comparison is separate; neither these NVIDIA measurements nor a warm binary reload proves fast
cold compilation or the Iris 60 FPS target.

Iris Xe completed the same native, parallax-enabled comparison with 13/28 family programs ready throughout (58
programs replaced); 60 warm-up / 90 measured frames per view. Fit GPU medians were original 94.9671/94.6050 ms versus
compact 83.5473/83.2321 ms. Close was 187.4053/186.8238 ms versus 163.3240/164.5157 ms, saving 23.1947 ms (12.4%).
These are isolated scene GPU timings, not normal window FPS, and remain far from 60 FPS. Intel had sparse
non-repeatable pixel outliers even after restoring the original (48 channel values over 1/255 close, maximum
100/255), with identical depth. Candidate close mean RGBA error was below .00006 byte. Results:
`work/shader-workload/iris-direct-compact-native`.

The first Intel edit took 292.46 s to replace 58 programs, followed by a long first draw in `glDrawElements`. This
probe links replacements after drawing to the window, unlike production's initial shared-context preparation;
the existing Intel framebuffer recompilation caveat applies. It nevertheless does not establish fast compilation.
Restoring the already compiled original took .464 s; that is a warm-binary load, not a fair cold-compile comparison.

The subsequent source-link check captured Fire And Ice 2's generic direct blended terrain program (`Terrain /
variant 2`, 229,761 fragment-source characters before replacement). It disabled application program binaries and
added a distinct unused uniform to each trial, giving the driver a previously unseen source key without changing
the executable behavior. Iris ABBA links took 12.145/16.168 s for the original and 11.921/11.658 s for the final
compact shader. This single-program check excludes the driver's possible first-draw preparation; it neither
measures complete map startup nor establishes a reliable percentage improvement given the original's variance.
Compilation is still slow. Source, log and warm-up capture: `work/shader-workload/iris-direct-source-compile`.

The single-evaluation ordinary ground/floor path is now the production candidate, with `floorRoleWeight` shared
with the unchanged legacy volcanic floor blend. It introduces no new programs, uniforms or resources. The
following larger source rewrites remain only in `work/shader-workload/register-probes`:

- `compact-deferred-surface`: shared projected sampling, with primary normal/finish reads moved after final role
  weights. Fire And Ice images matched the compact evaluator byte-for-byte, but NVIDIA close GPU time regressed
  from 17.4766 to 18.2400 ms (paired averages). Do not retain a smaller-looking source change that draws slower.
- `compact-streamed-covers`: final cover weights estimated before sequential material completion, replacing four
  simultaneously live complete materials. Native NVIDIA close paired averages were 17.5432 versus 17.8468 ms.
  It also changes contact pixels (mean .00281565 RGBA byte, maximum 31), including tropical authored mixtures.
  The existing volcanic evaluator remains. No NVIDIA benefit was established. Native Iris, parallax off, also
  failed to improve close rendering: final production baseline 170.7414/171.4851 ms versus streamed
  175.2192/173.7693 ms (60 warm-up / 90 samples, 4/28 family programs, 49 programs replaced). Fit medians drifted
  from 85.7016 to 91.2850 ms for the baseline, with candidate 87.0270/88.1687 ms between them; no reliable fit gain
  follows. This probe used production's shared-context preparation for offscreen hot edits, unlike the earlier
  Intel comparison, but still needed 185.97 s for the candidate edit. Different source, program counts and warmed
  binaries prevent a cold-compile comparison with the 292.46 s result above. Results: `iris-direct-streamed-native`.
- `compact-unified-natural`: ordinary material at volcanic contacts goes through the two-role evaluator too.
  Close GPU paired averages were 17.2053 versus 17.1864 ms, no meaningful improvement; maximum color difference
  was 72/255 across the views. It is not part of the production change.

The grass/dirt/fungus staircase fixture (levels -2 through +1, parallax enabled) first compared the deferred-surface
prototype against the original (`direct-floor-fixture`). The exact final production source was then tested
separately against the original at 1600 x 1000 (`work/shader-workload/direct-floor-production`). Both comparisons
had maximum channel differences 4/9/5 at fit/close/mid, with means below .0003 byte and identical depth. The final
source's close image had 271 of 6.4 million RGBA channel values differing by more than 1/255; restoring the original
restored every pixel in all three views. Visual inspection retained the distinct underground grass, dirt and fungal
surfaces. This bounds the tested transition differences; it is not proof for every map. Independent code review
found no actionable correctness issue. Rejected source rewrites, their generators and captures remain diagnostic
artifacts, not runtime configuration options.

### Direct mud, swamp and ice (2026-11-03)

The retained changes reduce work inside existing shaders; they add no shader variants, textures or cache behavior.
The reference for these comparisons already includes the compact direct-material evaluation above.

- Mud and non-sandy swamp interiors use the existing uniform-biome metadata to prove full coverage before
  evaluating the buried substrate. They skip its overwritten finish/emission reads, normal direction and final
  color treatment. Substrate height still supplies weathering and shadow receiver lift; filtered normal length
  remains at wider footprints. Edges, mixed sand and submerged banks retain the original evaluation. The visible
  material's authored finish/emission maps remain supported.
- Pure mud does not evaluate unused field/wetland noise. Fine wetland noise stops where its existing fade already
  reaches the constant value. A completely open swamp pool skips the soil/hummock normal that its ripple normal
  entirely replaces. Partial pools keep the original blend.
- Ice streams the nine neighboring hexes instead of retaining four nine-element arrays. A conservative bound on
  the broken-edge displacement skips the two floe fields only beyond the wet rim or safely inside complete ice.
  The interior proof includes the pixel footprint; broken edges, thickness, frost, reflection and authored detail
  retain their existing calculations where they contribute.

Native 3840 x 2259 Iris Xe ABBA measurements, cache disabled, normal maps enabled, parallax disabled:

| Close-view stress fixture | Original A1 / A2 median | Optimized B1 / B2 median | Paired average saving |
| --- | --- | --- | --- |
| Mud | 97.8359 / 88.6704 ms | 82.1151 / 91.2868 ms | 6.5522 ms (7.0%) |
| Swamp | 137.3756 / 149.9814 ms | 138.1356 / 118.6024 ms | 15.3095 ms (10.7%) |
| Ice | 108.7480 / 116.8628 ms | 104.4923 / 105.6381 ms | 7.7402 ms (6.9%) |

These are whole-scene GPU timestamps from the isolated probe described above, not normal window FPS or isolated
shader timings. The synthetic boards are 16 x 17, with broad interior material regions and boundaries; mud/swamp
use a .05 m/pixel camera setting and ice .089285714. Mud/swamp use 240 warm-up and 120 sampled frames per pass;
ice uses 60/90. Timings vary substantially, especially for swamp, so these paired averages do not establish stable
gains in every view or the 60 FPS target. Ice overview did not show a meaningful gain. NVIDIA ice close saved
.5473 ms (13.0171 to 12.4698 ms paired averages).

Mud and swamp comparisons differ by at most one 8-bit channel step, have identical depth, and restore exactly.
NVIDIA ice fit/close comparisons are byte-identical with identical depth. Iris ice has sparse color outliers also
present after restoring the original, with identical depth; it is not byte-identical on Intel. The mixed
mud/swamp/sand regression with parallax enabled differs by at most one channel step and restores exactly.

Results: `work/wet-terrain/iris-mud-proof-close.log`, `iris-swamp-production-close.log`, `iris-ice-stream.log`,
`nvidia-ice-stream.log` and `nvidia-wet-proof-mixed.log`. The mud/mixed runs precede the final conservative shoreline
guard, which does not affect their dry interiors. The final swamp run uses production sources. All GPU probes use
the same installed geometry and fixed animation time. Ground-cover arrivals additionally grow over 500 ms of wall
time; comparison captures now wait for that growth. A multi-view ice-boundary run changed grass/depth even with
an original-to-original reload (zero programs changed), so it is not evidence of a shader regression; boundary
validation uses separate single-view processes to avoid that camera-history confound.

The final production ice boundary fixture (land ice, detected black ice on pavement, frozen water and an elevation
step) passes those separate NVIDIA comparisons at .05 and 6 m/pixel: candidate and restored color/depth are exact.
Results: `nvidia-ice-boundary-production-close.log` and `nvidia-ice-boundary-production-far.log`.
The mixed wet-material fixture also passes with the final production shaders, parallax enabled and normal maps
disabled: maximum channel difference 1/255, identical depth and exact restoration
(`nvidia-wet-production-no-normals.log`).
The combined five production shaders also pass native Fire And Ice 2 close-view integration with parallax enabled:
candidate and restored images are byte-identical, depth is identical, and both edits replace the same 53 programs
(`nvidia-wet-production-fireice.log`). This comparison validates appearance/integration, not performance on that map.

Existing program counts are unchanged. Hot-edit compile times are not a controlled cold-start comparison, and no
startup-time improvement is claimed. Independent source review found no actionable correctness issue.

### Iris Xe: three maps at 1080p (2026-11-03)

These runs use the full board window at a verified physical 1920 x 1080 framebuffer on Intel Iris Xe, render scale
1, Enhanced graphics, terrain cache disabled, VSync disabled and no per-frame `glFinish`. Normal maps, tessellation,
specular antialiasing and AO are enabled; parallax and SMAA are disabled. Lighting is fixed at clear 13:00. Labels,
HUD, live scene capture and one fixture unit remain present. They measure actual window frame intervals as well as
independent GPU timestamps, unlike the isolated material probes above.

Each view has 120 warm-up and 240 sampled frames. The isometric camera uses azimuth 45 and tilt 54.74 degrees;
overview fits the board, close centres on the board at span 270 world units, and moving pans from that close view.
The exact inputs are `AGoAC Maps/16x17 Grassland 2.board`,
`unofficial/Drewbacca/16x17 Fire And Ice 2.board` and
`Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board`.

Initial baseline, including the preceding compact and wet-material improvements:

| Map | Fitted overview FPS | Close FPS | Moving FPS | Close GPU median | Close opaque median | Close depth pre-pass |
| --- | --- | --- | --- | --- | --- | --- |
| Grassland 2 | 35.95 | 23.31 | 25.21 | 42.2850 ms | 36.4084 ms | 2.1078 ms |
| Fire And Ice 2 | 23.87 | 17.61 | 17.75 | 54.6706 ms | 47.6544 ms | 3.4709 ms |
| Kozice Valley (CDS) | 33.47 | 25.92 | 27.15 | 38.2515 ms | 30.4421 ms | 4.3442 ms |

FPS is the average from measured frame intervals, not the inverse of median GPU time. Close frame-interval p95 is
46.771 / 64.278 / 41.195 ms respectively. Existing family programs available were 4/8, 4/40 and 8/8; the remainder
use the existing generic shader. These reflect this run's warm-binary availability, not identical cold-start states.
Terrain preparation took approximately 157, 408 and 40 seconds respectively; that difference is not a controlled
comparison of map loading. All runs reached the completed phase without a renderer or framebuffer mismatch.

A separate 24-frame family diagnostic follows each steady view. Grassland close spends about 18.57 ms in mixed
grass/desert ground draws. Fire And Ice close spends about 15.23 ms in ordinary grass draws, 10.01 ms in mixed
grass/dirt/rock/snow draws, 5.93 ms in curved ground overlays and 8.27 ms across two snow groups. Kozice close has
about 6.17 ms of grass/desert blends and 6.09 ms in its largest ordinary grass group. These are grouped draws,
including their vertex/raster work, not isolated material instruction timings. Timestamp instrumentation adds
overhead, and stage/group percentiles must not be summed. Ordinary hex labels are around .06-.10 ms; AO is around
1 ms. Mud, swamp and ice alone therefore do not explain the remaining cost on these maps.

Results and screenshots: `work/iris-1080/grassland-baseline`, `fireice-baseline`, `kozice-baseline` and their adjacent
`.log` files. `work/iris-1080/results.py` extracts frame intervals and GPU stages into `results.json`.

A work-only experiment prioritized the same four specialized family programs by estimated material evaluations
instead of index count. It did not provide a repeatable gain on Grassland: close FPS 24.95 versus 25.32, moving
26.34 versus 25.72. It is not retained; program count was fixed, and no cold-compilation benefit was established.

The useful material change skips `blendCovers`' first ordinary material when no retained coverage uses its family
and no sand-to-substrate transfer can expose it. Previously that first material was always fully evaluated, even
where another palette member completely replaced it. Active aliases and exposed sand substrate still evaluate it;
constructed panels and the volcanic path are unchanged. Material derivatives are captured before the branch. The
existing 2% coverage threshold is unchanged, and every contributing material retains its authored maps and detail.
This shared path includes grass, desert, Mars, tropical, cold basalt and other ordinary families. A uniform surface
still requires its own material; gains depend on otherwise unused work in mixed palettes, not the map's name.

Full-window ABBA comparisons use 120 warm-up / 180 sampled frames per view. Original A1, candidate B1/B2 and restored
original A2 share a GL context. Family-program capacity is fixed at 4 for Grassland and Fire And Ice, and 8 for Kozice,
keeping availability at 4/8, 4/40 and 8/8 for every pass; warm binaries cannot silently add specializations.

| Close view | Original A1 / A2 GPU median | Candidate B1 / B2 GPU median | Paired average saving | Mean of original / candidate run FPS |
| --- | --- | --- | --- | --- |
| Grassland 2 | 41.0526 / 39.5250 ms | 37.1542 / 37.0966 ms | 3.1634 ms (7.9%) | 24.43 / 26.40 |
| Fire And Ice 2 | 53.7612 / 52.5894 ms | 52.0850 / 52.1211 ms | 1.0723 ms (2.0%) | 18.37 / 19.07 |
| Kozice Valley (CDS) | 34.0309 / 33.4907 ms | 32.9163 / 32.6331 ms | .9861 ms (2.9%) | 29.25 / 30.21 |

Moving paired GPU savings are 2.5278, .2326 and .7935 ms respectively; Fire And Ice's moving result does not
establish a meaningful gain. Original runs also vary: comparing against the restored original alone gives close
savings of 2.3996, .4864 and .7160 ms. These are measured incremental gains, not stable 60 FPS or a blanket terrain
speedup. Do not compare the candidate directly with the earlier independent baseline to attribute a larger gain.

Kozice initially fits with 12 medium chunks; its first close view has six full/six medium chunks, and it then retains
eight full/four medium chunks after moving. Its overview ABBA average is therefore confounded by that geometry
history and is not a shader regression/gain claim. The restored-original comparisons above also control the retained
chunk detail. Grassland and Fire And Ice retain six full chunks throughout. Full logs, draw audits, stage
timings and screenshots are under `work/iris-1080/{grassland,fireice,kozice}-absent-base-abba`.

NVIDIA fixed-time image checks with parallax enabled compare the same installed scene, including original
restoration. All three maps and the mixed grass/desert/sand/dirt fixture (including sublevel floors) are pixel- and
depth-identical at fitted, close and mid views. Logs: `nvidia-{grassland,fireice,kozice}-absent-parity.log` and
`nvidia-absent-sand-floor-parity.log`. Independent source review found no actionable correctness issue. The tested
material change is retained in production, without new shader variants, resources or cache behavior. Hot recompiles
still take time; unchanged program count does not establish improved startup latency.

A second retained change skips sculpted-terrain shadow comparisons when the completed opaque surface has no
directional-light contribution: its geometric support faces away from the sun, and it has neither thin-leaf
transmission, positive scattering nor a volcanic contribution. The condition reads the actual completed material,
so future authored scattering maps remain supported. Receiver-plane derivatives and shared shadow setup stay
unconditional; the early return precedes only unused comparisons. Existing foliage/other callers retain their
original five-argument `sculptShadow` entry. This changes no shadow filtering, material detail, geometry or program
count on surfaces that receive directional light.

The same full-window ABBA settings test this separately, with the unused-material fix present in both versions:

| Close view | Original A1 / A2 GPU median | Shadow skip B1 / B2 GPU median | Paired average saving |
| --- | --- | --- | --- |
| Grassland 2 | 36.4196 / 35.8323 ms | 36.1436 / 36.1622 ms | -.0270 ms: no meaningful change |
| Fire And Ice 2 | 52.9321 / 52.5078 ms | 51.2764 / 51.5916 ms | 1.2860 ms (2.4%) |
| Kozice Valley (CDS) | 31.9506 / 31.8969 ms | 31.8926 / 31.7538 ms | .1006 ms: no meaningful change |

Fire And Ice still saves 1.0738 ms against the restored original alone; moving saves 1.0688 ms in the paired
average. This is a modest improvement, not the hoped-for 2 ms from this change alone. Kozice's overview retains
the geometry-history limitation described above. Results: `work/iris-1080/{grassland,fireice,kozice}-shadow-abba`.
Independent review found no actionable correctness issue. NVIDIA Fire And Ice checks with parallax enabled are
pixel- and depth-identical at fit/close/mid under 13:00 lighting, and at close view under 06:15 lighting, including
restoration: `nvidia-fireice-shadow-parity.log` and `nvidia-fireice-shadow-low-sun.log`.

Latest full-window FPS, averaging the two candidate runs with both retained changes:

| Map | Fitted overview FPS | Close FPS | Moving FPS |
| --- | --- | --- | --- |
| Grassland 2 | 40.2 | 27.4 | 29.3 |
| Fire And Ice 2 | 27.4 | 19.3 | 20.0 |
| Kozice Valley (CDS) | 33.6 | 31.1 | 29.1 |

These remain far from stable 60 FPS. Different runs drift, so the separate ABBA comparisons, not subtraction from
the first baseline table, establish the incremental savings. Source equality checks confirm that the three final
production shader files match the measured candidates. No additional shader variants or terrain-cache changes
are introduced; new changes remain unstaged.

### Iris Xe shader compilation survey (2026-11-03)

The current Fire And Ice 2 scene exported 93 distinct vertex/fragment program pairs, including tessellation
stages where present. A work-only raw GL harness compiles and links each program on the verified Iris Xe renderer.
It bypasses application program binaries and appends a unique unused uniform to each fragment source to avoid
reusing the same source key. It does not clear driver caches, render the scene, or measure texture/geometry loading.
Stage submissions, `glLinkProgram`, and final link-status waiting are timed separately; compile/link status is
validated for every stage and program. This is source-compilation evidence, not a complete startup benchmark.

| Exported program | Source compile/link time |
| --- | --- |
| General sculpted volcanic blend, cloud shadows | 83.255 s |
| Ordinary sculpted blend, cloud shadows, tessellated | 11.416 s |
| Ordinary sculpted blend, cloud shadows | 11.376 s |
| Grass / dirt / rock plus volcanic blend specializations | 10.821-10.854 s each |
| Existing blended wall / top material-fill programs | 6.651 / 6.590 s |
| Plain sculpted ground, with / without tessellation | 4.121 / 4.198 s |
| Unblended volcanic sculpted ground | 3.834 s |
| Water surface | .600 s |
| Ordinary model programs | .245-.446 s |
| Shadow depth programs | .011-.015 s |

Serial source-compilation total is 198.932 s, of which 198.691 s is inside `glLinkProgram`. That call includes the
driver's deferred compilation and optimization; the timings do not identify individual optimizer passes. The
general volcanic blend alone accounts for 41.85% of this total. Corresponding ordinary blended programs with and
without tessellation both cost about 11.4 s, so tessellation is not the main compilation difference in this sample.
These 93 exported programs are not every possible program in the game, nor do their serial timings equal startup
wall time. Production can reuse program binaries and compile asynchronously; first-draw driver work is also
outside this survey.

The worst program has four separate calls to `naturalMaterialFor` in `blendCovers`. Each reaches the general
material evaluator, including ground/sublevel blending and up to four material roles with their projection,
parallax and authored channels. A prototype gives that general volcanic path one draw-uniform loop and one
ordinary-evaluator call site. Its source text is slightly longer, yet compilation becomes much faster: source
length alone does not explain the cost. Repeated original measurements are 83.255 / 84.439 s; the first loop
prototype takes 19.448 / 21.078 s, about 76% less. This is a structural compiler improvement, not fewer texture
maps, lower texture resolution, reduced lighting, or an added shader specialization.

Survey sources, runner, raw timings and prototype snapshots are under `work/compile-survey`: `sources`,
`CompileSurveyMain.java`, `run.ps1`, `iris-current/timings.csv`, `iris-generic-repeat/timings.csv`,
`iris-loop/timings.csv` and `iris-loop-repeat/timings.csv`. The existing terrain cache is not modified.

The loop was initially held out of production because faster compilation did not establish faster rendering;
the deployment decision after the requested retest is recorded below. NVIDIA Fire And
Ice 2 comparisons at 1920 x 1080, with parallax enabled, fixed scene time and family availability 4/28, are
pixel- and depth-identical at fit/close/mid, including restoration. Its whole-scene GPU medians are effectively
unchanged: original 3.722/3.726, 4.757/4.757, 4.816/4.789 ms versus candidate 3.681/3.669, 4.773/4.689,
4.763/4.758 ms respectively (`work/iris-1080/nvidia-volcanic-loop.log`). These are isolated scene GPU timestamps,
not window FPS.

Iris comparisons use parallax off and the same fixed family availability. An occlusion-query audit explicitly
excludes the depth shader and verifies that the general volcanic color program contributes 1,327 / 14,064 /
4,989 samples in fit/close/mid. Color comparisons have isolated differences of a few pixels out of 2,073,600;
differences also occur between repeated candidates and after original restoration. Depth remains identical, but
Intel pixel identity is not claimed. The initial strict comparison aborted on those outliers; subsequent diagnostic
runs record them without relaxing the depth assertion. Source review found no alias, concrete, floor, sand,
derivative, authored-channel or evaluation-order mismatch.

A single-mid-view control with fixed render ordering reproduces a small rendering cost for the one-call-site
loop: original GPU medians 40.148 / 40.218 ms versus candidate 40.559 / 40.680 ms, about .437 ms more. Skipping
redundant copies into inactive slots did not establish an improvement. A final work-only experiment keeps A's
evaluation outside the secondary-slot loop, leaving two evaluator call sites. Per-draw GPU queries around the
affected color program give original medians 1.4753 / 1.4997 ms versus 1.5469 / 1.5427 ms: about .0573 ms more.
Whole-scene medians drift from 41.8012 to 40.4740 ms for the original, with candidate 42.0356 / 41.3345 ms between
them; these do not establish an FPS gain. There is no isolated source-compilation measurement for that final
two-call-site experiment, and its all-program hot-edit duration is not a cold-start comparison.

Logs are `work/iris-1080/iris-volcanic-loop-diagnostic.log`, `iris-volcanic-loop-mid-control.log`,
`iris-volcanic-loop-skip-mid.log` and `iris-volcanic-loop-peeled-mid.log`. The corresponding sources remain under
`work/compile-survey`. At this stage no loop prototype was promoted; the earlier retained material improvements
remained in place. The requested retest and subsequent deployment decision follow below.

The next blending design should reduce the material work itself, rather than only rearrange its control flow.
Current steep ordinary surfaces already choose two palette families, and ordinary material evaluation already
limits internal roles and skips provably absent work. Tops can still construct up to four family results before
final cover-height competition. The earlier `compact-streamed-covers` experiment, which estimated that competition
before completing materials, was slower on both tested GPUs; it is not an established optimization.

A larger visual approximation is to author compact surface profiles whose color/height, normal and finish maps
already contain fine soil/rock/deposit weathering, leaving runtime masks to select and blend those surfaces across
broad terrain contacts. This removes nested material recipes rather than just lowering a weight threshold.
Coverage/height selection would precede expensive detail, with one surface in interiors, typically two at a contact,
and meaningful junctions preserved. Animated lava, emission, scattering, metallic response and wetness must remain
represented. This is a proposed material-representation change, not implemented or benchmarked; repetition,
transition continuity, close-view detail, texture memory and Iris GPU cost need a focused before/after prototype.

### Single shared material loop: requested retest (2026-11-03)

This repeats the original one-call-site `loop-candidate`, not the later skip-copy or peeled-base experiments.
At the start of the retest, production `terrain-materials.glsl` matched `loop-baseline`. Both versions run in the same application build,
with the same installed scene and fixed family-program availability (4/28). Fire And Ice 2 renders on verified Iris
Xe at 1920 x 1080, cache off, parallax off, normal maps and AO on, clear 13:00 lighting, fixed animation time and
fixed render ordering. Close and mid views center on zero-based hex (10,13), at .089285714 and .15 m/pixel.

Each reported value below is the median of four run medians. Mid uses order ABBABAAB, 200 warm-up / 240 measured
frames per run. The final close comparison uses ABABABAB and shorter 60 / 90 frame blocks to reduce drift between
versions. Full-scene GPU timestamps and 24-frame per-draw GPU queries for the general volcanic color program are
separate measurements; their differences are not additive. The latter excludes the depth shader. Coverage queries
confirm 4,989 and 14,064 samples respectively. These are isolated GPU timings, not normal window FPS.

| Measurement | Original | Single loop | Change |
| --- | --- | --- | --- |
| Mid scene GPU | 40.21895 ms | 40.87145 ms | +.65250 ms (+1.62%) |
| Close scene GPU | 39.49030 ms | 40.16050 ms | +.67020 ms (+1.70%) |
| Mid affected color-program draws | 1.47805 ms | 1.59785 ms | +.11980 ms (+8.11%) |
| Close affected color-program draws | 2.47255 ms | 2.82000 ms | +.34745 ms (+14.05%) |

No runs are discarded from those reported groups. Mid original run medians range 40.1482-42.0919 ms and candidate
40.5984-44.8225 ms; close original ranges 39.4169-39.5507 ms and candidate 40.0135-40.4508 ms. This supports a small
rendering cost rather than treating the earlier .437 ms estimate as a universal constant. All captured depths are
identical. Candidate images differ from the initial baseline at 1-8 pixels by more than 1/255 out of 2,073,600;
restored-original captures also have 3-8 such pixels. Maximum channel differences reach 84/255 in both original
controls and candidate captures, so Intel byte identity is not claimed.

Two preliminary close tests are retained as diagnostics, not combined into the final table: the first overlapped
image analysis, and the next suffered severe drift even after restoring the original (38.9891 to 58.9403 ms).
The shorter alternating comparison above followed those failed controls with no concurrent image analysis or
compilation. Runtime graphics-counter and battery queries were unavailable in this environment, so the source
of the transient system-level variation was not identified.

After rendering finished, a fresh sequential source compile/link pair used the same raw GL harness and unique
unused source declarations as the survey. Application program binaries are bypassed; no driver cache is cleared.
The original took 97.056349 s, and the single loop took 20.215884 s: 76.840465 s saved, or 79.17%. Earlier pairs were
83.254717 -> 19.448018 s (76.64%) and 84.438943 -> 21.078295 s (75.04%). The large reduction is repeatable for this
general volcanic blend program. It is not a 75-79% reduction in total map loading or compilation of every program.

Results: `work/compile-survey/single-loop-retest-results.json`, `iris-single-loop-retest-before/timings.csv` and
`iris-single-loop-retest-after/timings.csv`; rendering logs/captures are
`work/iris-1080/iris-volcanic-single-loop-retest-mid` and `iris-volcanic-single-loop-retest-close-alternating` with
adjacent `.log` files. The retest itself changed no production shader and staged no files.

### Single shared material loop: retained implementation (2026-11-03)

Following explicit acceptance of the measured compilation/rendering trade-off, production `terrain-materials.glsl`
now contains the exact original one-call-site `loop-candidate` used in the comparisons above. The loop is limited
to the general volcanic blend (`volcanicFlag` without `VOLCANIC_ORDINARY_FAMILY`). It shares the ordinary material
evaluator call across palette slots, with a draw-uniform bound, while retaining the original slot order, aliases,
texture/scale overrides, constructed concrete behavior, sand substrate exposure and complete material channels.
Final blend weights and lighting remain unchanged. No shader variants or resources are added.

The accepted benefit is 75-79% less source compile/link time for this program; total map loading was not measured.
The measured Iris cost is approximately .65-.67 ms more whole-scene GPU time in the tested 1080p close/mid views.
NVIDIA comparisons match color/depth exactly; Iris depths match exactly with the isolated pixel differences
documented above. The skip-copy and peeled-base loop experiments remain work-only.

Integration verification compares the production shader with the tested candidate and checks the patch for
whitespace errors. The tested source is unchanged, so the completed native compilation, rendering, image and
independent-review checks above apply to this implementation. Changes remain unstaged.
