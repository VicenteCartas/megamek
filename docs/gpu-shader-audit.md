# GPU shader audit — 12 October 2026

This review covers every shader resource in
`megamek/resources/megamek/client/ui/clientGUI/boardview/gpu`, including shared
includes and the libGDX programs assembled by the Java shader providers. The
128-resource inventory is in [gpu-shader-audit.csv](gpu-shader-audit.csv). Includes
are charged to their calling draw; counting source lines or texture expressions
does not establish GPU time.

## Deployment review follow-up

The follow-up review checked shader/material integration, scene composition,
resource ownership, texture roles and asset provenance independently. It found:

- The previous native run bypassed `stageDataFiles` and used stale copies of 32
  rebuilt contact/cliff albedos and their two manifests. This was a verification
  gap: the approved files were in `mm-data`, but not the runtime data directory.
  Only these 34 files were staged, after checking they still matched the approved
  bake. All destination SHA-256 hashes now match; all 64 numerical channel hashes
  remain unchanged. Before/after inventories and the old files are preserved in
  `build/shader-audit/deployment-review/`. Earlier material comparisons remain
  evidence for their recorded assets, not visual validation of these later bakes.
- Imported model normal sampling used implicit texture derivatives inside
  varying tangent-validity branches. `model-surface.glsl` now passes the existing
  pre-branch UV gradients to `textureGrad`. This fixes undefined mip selection
  at threshold-crossing quads without changing the regular-face calculation.
- Camera-driven terrain refinement was unreachable once the initial build
  settled: `GpuTerrain.refine` returned before the camera could select another
  detail tier. The idle shortcut now applies only to calls without a camera.
  The existing native detail lifecycle test reproduced this defect; its focused
  rerun verifies zoom changes, cached tiers and superseding edits together.
- Shadow-map rebuilds inherited disabled depth writes from the final display
  pass. libGDX clears the shadow target before its model batch restores depth
  writes, leaving stale depth beside cleared packed color. `renderShadows` now
  restores depth writes before either rebuild path clears the target. Other
  scene, water, visibility and preview depth clears already restore that state.
- Unit-size changes queued terrain preparation even when the scene had no
  fallen limbs, the only terrain props that use that scale. The request now
  checks for limbs, sharing the existing dirty-tile predicate. The lifecycle
  regression checks unchanged geometry and immediate settings publication for
  ordinary terrain, and queued/latest-scale installation for a salvage arm.
- Older model, terrain, river, effects and playback fixtures still rendered
  scene output directly to display pixels. They now reuse the existing
  RGBA16F/display test boundary. Packed depth and other numerical probes retain
  their data formats; liquid interpolation is checked in canonical linear RGB.
  The model's mirrored-UV fixture now uses a 45-degree normal tilt so both
  orientations face its isometric camera; its original contrast threshold is
  retained. The former 60-degree tilt correctly faced away in one orientation
  under the current BRDF's positive-view-hemisphere guard.
- The wider native run exposed obsolete fixture contracts: the tuning test
  selected a removed thirteenth family, and a terrain-wall test required every
  source to be 128 square even though three existing sculpt walls are authored
  at 512 square. They now check the supported family set and the actual authored
  image dimensions. Building facade checks still require their original 128
  square assets. Neither correction changes production behavior.
- The resources fixture expected an incoming terrain edit to change picking
  before its mesh was installed. Picking intentionally follows the displayed
  terrain snapshot. The fixture now checks both sides of the installation
  boundary rather than asking picking to get ahead of the visible geometry.
- Appearance fixtures also conflated material properties with outgoing light.
  Rain coverage now reads the production puddle value, and absorption reads
  completed linear pigment, retaining visible rain animation checks. Bank
  continuity checks the material weights across the seam and a source-captured
  sand-to-snow edit; differing slope normals need not produce identical RGB.
  The waterfall motion probe now samples the actual thrown sheet, with a frozen
  clock control, instead of the vertical rock plane behind it. Atlas-normal
  checks isolate their ground from unrelated scenery introduced by the source.
- The damage/model review now checks authored colors after their linear import,
  explicitly selects model display, and verifies the current board-owned arm
  and leg salvage assets. Damage, pickup stability, model ownership and disposal
  assertions remain in place.

No additional confirmed defect was found in HDR scaling, alpha composition,
framebuffer disposal, managed image reloads or shared texture ownership. No
unnecessary color conversion roundtrip or new harmful duplication was identified
in the reviewed helpers. Bilinear sprite minification remains an existing image
quality limitation; source repetition alone does not establish a GPU bottleneck.

For future native verification, use the normal data staging dependency. If
staging is deliberately skipped to preserve concurrent asset work, compare and
archive the exact source/runtime asset hashes before accepting the captures.
Code and data must be packaged together; a passing shader test does not establish
that the installed texture assets are current.
The JAR builds successfully; all 128 packaged shader resources match the source
SHA-256 hashes and the new color/environment classes are present. All 43 changed
board texture/manifest files match between `mm-data` and the staged runtime.

The final model rerun passes, including damage, salvage, camouflage, animation
and shared-asset ownership checks. Taking the latest result for each suite across
the broad run and focused reruns gives **40 native smoke checks and three
benchmark tests, with zero failures, errors or skips**. The benchmark set includes
the 34-view landscape capture. Main and test style checks scoped to the changed
rendering files pass. This is targeted rendering validation, not a full repository
test run. The per-suite result inventory is preserved in
`build/shader-audit/deployment-review/validation-summary.json`; the individual
run archives retain failures that were subsequently corrected and rerun.

**Release assessment: the confirmed correctness defects from this review are
fixed, but performance signoff remains open.** The 36.7 ms dense-forest steady
frame, 175.5 ms close-zoom submission and 1,107 ms opening refinement below are
measured problems on the tested Iris Xe adapter. Fire/smoke and magma also remain
expensive. No universal frame-time or platform guarantee follows from the passing
checks. A full-game workload and a cold launch on the intended deployment
hardware remain necessary release qualifications; no deployment was performed.

## Findings and priorities

| Priority | Shader / workload | Evidence | Action |
| --- | --- | --- | --- |
| High | `terrain-biome-mask.glsl`, `terrain-biome.glsl`, sculpt ground on marsh | Pre-change Intel Iris Xe, 1600×900, 50×50 marsh: opaque ground **13.13 ms** without plants, **14.09 ms** with plants at 110 px/hex. Full frames 13.43 / 14.39 ms. | Uniform interiors bypass the stencil; boundaries and height changes retain it. Pool-only work is gated, texture gradients are explicit, and the wetness field supplies its analytic gradient. |
| High, improved | `terrain-ice.glsl` | Final paired 1200×900 Iris Xe overhead lake transparent stage: **2.44 ms before**, **1.79 ms after**, with stable surrounding stages. Other views have clock confounds; frozen repeats match exactly. | Removed unused base shading and deferred floe/snow/map work by specialized surface mode; cut faces use their own material. Cold shader linking remains separate from draw cost. |
| High | `terrain-effects.frag` | Fresh Iris Xe 1920×1080 effects-only GPU medians: **16.94 / 10.09 / 4.19 ms** at close/mid/far LOD, 80 samples each without drops. Includes depth copy, half-resolution volume and upsampling. | The 24/12/6-step volume march and overlap remain the cost drivers. The close fixture alone exceeds a 16.67 ms frame budget. |
| High, improved | `explosion.frag` | RTX 4070 Laptop, a 12-hex building collapsing, dust-only frames, uncapped with a `glFinish`: 1920×1080 **64 ms before, 10 ms after** (9.4 ms without dust); 3840×2160 **203 ms before, 20-21 ms after** (16.5 ms without). Before: 28-56 overlapping volumes at full resolution, 32 steps, hashed noise. | Volumes draw into a half-resolution layer (`GpuEffectLayer`), noise comes from the shared texture, steps follow the ray's length; a collapse's dust is one volume per cloud, marched by its distance bound. Not measured on Iris Xe. |
| High, improved | `terrain-magma.glsl`, `magma-solid.glsl`, `magma-flow.glsl` | Latest 1280×900 paired opaque-stage means: **16.54 → 14.81 ms top**, **13.56 → 12.45 ms orbit**, **13.42 → 12.11 ms close crust**, **7.83 → 7.43 ms close lava**. Includes other opaque geometry; excludes FFT updates. | Shared volcanic evaluation, lighting reuse and cheaper flowing skin give about 5–10% savings in this fixture. The complete shipped lava scene still reaches 29.46 ms at 1440×900; it is not a cheap complete frame on this adapter. |
| Medium, partly improved | `terrain-materials.glsl` | Cross-family blending evaluates several complete materials; later sand transfer means a raw zero weight cannot simply be skipped. | Volcanic slots now share one surface evaluation and skip unused coverage after shore adjustment, retaining sand-substrate evaluation. Duplicate contact noise is reused. Ordinary material blending remains expensive. |
| Medium, unmeasured | `terrain-foliage.frag` | Tree alpha rejection occurs after normal/shadow/BRDF work. This can waste work in transparent canopy texels. Reeds already reject early. | Profile forests; move required derivatives before any earlier discard. |
| Medium, unmeasured | `unit-visibility.frag` | Up to 17 neighborhood classifications, each potentially adding depth-span and smoke-depth checks. | Existing unit screen-bounds scissor limits coverage. Profile many spread-out occluded units before adding intermediate masks. |
| Medium, unmeasured | `water-pool.glsl` | Multiple wave cascades, two-phase flow/detail sampling, shore queries and up to 12 wakes plus 12 waterfall impacts per material. | Existing zero-count branches and explicit gradients help; isolated water timing is needed before another rewrite. |

The fresh marsh result identifies ground shading as the bottleneck. It does not
support blaming reeds for the full cost. Timing the whole opaque stage does not
isolate one include, and GPU results from this integrated GPU are not predictions
for other machines.

The deployment review also measured a 40-by-40 board with 5,499 trees at
1440-by-1080 on Iris Xe: total steady frames were 17.3 / 36.7 / 17.8 ms at
zoom 3.2 / 1.0 / .25. Corresponding opaque GPU medians were 13.75 / 33.98 /
16.06 ms; display conversion cost .36–.41 ms. Each view used 24 warmup and
40 measured frames, with no dropped stage queries. These are scene costs,
not an isolated foliage-shader attribution or full-game FPS. Raw output:
`build/shader-audit/deployment-review/fixtures.log`.

The board-dependent asynchronous test now also runs against the shipped
16-by-17 Wide River board. Opening, editing, tuning and both zoom transitions
complete correctly, but first-use stalls remain: an opening refinement call
took 1,107 ms, and one close-zoom opaque submission took 175.5 ms (179.6 ms for
that frame's measured work). These are observed CPU-side submission intervals;
the report does not isolate shader compilation, upload, driver waits or GPU
execution as the cause. The edit phase's largest measured submission was
8.5 ms. This is a performance qualification issue even though the lifecycle
assertions pass. Raw stages: `build/shader-audit/deployment-review/focused.log`.

The final marsh comparison uses the same 960×720 scene, frozen clock and current
lighting, alternating the saved material/shader implementation and the completed
revision. Each of the 48 blocks contains 24 warmup and 40 measured frames, with
zero dropped GPU queries. Stable opaque-stage pairs in milliseconds include:

| Marsh view | Saved blocks | Revised blocks |
| --- | --- | --- |
| Windy bank, 560 px/hex | 8.511 / 8.550 | 7.231 / 7.194 |
| Windy bank, 160 px/hex | 7.097 / 7.234 | 6.271 / 6.169 |
| Rained plateau, 160 px/hex | 6.671 / 6.641 | 5.838 / 5.828 |

Other views show substantial clock variation, including the unchanged composite;
these results do not support a single universal speedup. They measure the complete
material revision, not the derivative fix alone. Every repeated reference and
revised image matched exactly at the frozen clock. The new material intentionally
differs from the old glossy surface. Its raw output gives peat roughness minima
of approximately .7852 dry and .6890 in rain, while exposed pools remain below .068. Analytic
wetness gradients passed 128² GPU/independent CPU comparisons; plateau masks
also matched the full stencil. Reports: `build/shader-audit/final-materials-6/`.

The historical first ice rewrite preceded the later appearance changes.
Subsequent fixed-mode specialization was byte-exact in all eight views;
the perspective transparent stage was 1.366/1.387 ms versus 1.995/2.006 ms.
Other views were noisier and the overview opaque stage slightly regressed.
Five ice source pairs were linked, versus four after removing the mode prefix.
First source replacements took 35–38 seconds, with later reloads around .33 seconds;
driver-cache state was not controlled. These are separate preparation costs.
See [ice implementation and limits](gpu-ice.md) and
`build/shader-audit/precision-ice-5/ice-benchmark/timings.txt`.

The final ice comparison includes the cut-face material change, with both
versions using the current texture assets and shared lighting. All 32 alternating
blocks collected 20 samples per stage with zero drops; all 16 frozen repeats
were byte-exact. In the stable overhead view, the transparent stage falls from
2.4386/2.4346 to 1.7957/1.7890 ms (about 26.4%), while opaque terrain and the
unchanged composite stay effectively constant. The land-border view also shows
a benefit, but broader clock variation prevents a universal percentage or
whole-frame claim. The changed cut faces intentionally differ from the original
shader; oblique views retain the top's snow/crack structure and continuous edges.
First source replacements took 19.4–22.2 seconds, later actual rebuilds .195–.733
seconds. Cache state was uncontrolled. The report's per-view preparation excludes
the initial terrain update/animation and must not be read as complete startup
time. Raw results: `build/shader-audit/final-benchmarks-10/ice-benchmark/`.

The final sampler check rendered seven shipped-board/condition cases under day, dusk, night,
rain, snow and fog conditions. The highest linked count was 14, below the
16-sampler budget. This was checked on the named Intel driver; it is not a claim
of a native macOS run.

## Magma material and timing

### Latest cheaper-material revision

The new molten surface keeps mapped normals, two-phase advection and FFT folds,
but removes its thin-skin height offset and the shifted surface reread. This
reduces close material sampling from ten to eight reads per active projection.
Thick crust retains its bounded relief trace. A narrower per-phase exposure mask
and later yellow ramp produce more quiet basalt between orange incandescent rifts;
the scene remains linear RGBA16F with the same final display conversion.

Terrain boundaries prepare warped finite derivatives before coverage branches.
Shore adjustment happens before deciding whether a volcanic overlay is unused;
a zero-weight volcanic substrate still evaluates when sand can expose it. One
sample supplies crust and bank, preserving their different emission strengths.
Ordinary palette helpers no longer contain redundant volcanic sampling paths.
Duplicate family slots reuse contact noise, and mixed bank lighting reuses its
dielectric response and lava irradiance instead of computing them twice.

Final paired medians on Intel Iris Xe, 1280×900, fixed scene/camera and a frozen
three-second animation clock (20 warmup + 60 measured frames per ABBA block):

| View | Saved pre-revision blocks (ms) | Revised blocks (ms) |
| --- | --- | --- |
| Top | 16.3841 / 16.6866 | 14.6894 / 14.9343 |
| Orbit | 13.5667 / 13.5598 | 12.3648 / 12.5343 |
| Close crust | 13.3020 / 13.5397 | 12.4179 / 11.7933 |
| Close lava | 7.8716 / 7.7976 | 7.3697 / 7.4968 |

These are whole opaque-stage timings, not isolated lava draw time or full-game
FPS. Both repeats of each version are byte-exact in all four views; comparing
only the palette changes against the saved evaluator is also byte-exact. Every
stage collected 60 GPU samples without drops. The block means improve by about
5–10%; within-version timing variation and some high p95 values remain.

Output-only diagnostics on the intermediate revision retain geometry and depth.
Top controls cost 15.09 / 15.30 ms; replacing flow shading costs 14.36 ms,
replacing sculpt shading 2.28 ms, and disabling local emissive-light evaluation
12.91 ms. These marginal costs overlap and cannot be added. They establish that
sculpted terrain dominates the broad view; flowing lava dominates its close-up.

The shipped `16x17 Lava Tubes 1.board` passes eight top/isometric lighting views
at 1440×900, including picking and GL checks. Complete terrain GPU medians span
21.07–29.46 ms (30 warmup / 60 samples, no drops), with no units or HUD and frozen
animation. This is still a costly landscape. The result is not a paired
before/after comparison of that board, and it does not include animated FFT cost.
Shader preparation also remains a limitation: the first final source replacement
took 75.7 seconds for 14 programs; subsequent replacements took roughly .28 s.
A preceding worker capture places a preparation delay inside native
`glLinkProgram`. These are measured preparation delays, separate from warm frames.

The material probe covers 338,220 molten pixels: 65.90% quiet cooling skin,
minimum cold roughness .7993 and peak red emission 8.9063 in canonical HDR units.
Day/night and perspective captures retain crust detail and hot rifts; fine glowing
threads and repeated motifs remain stylized. No claim of photorealism is implied.

Focused native validation passes material finiteness/HDR, liquid-only motion,
six current directions, 24 fall orientations/sides, phase continuity, gravity
pause/recovery, live cooling/reheating, shader reload/ownership and material LOD.
The new saved-source oracle compares complete material channels in RGBA16F for
zero/positive neighboring coverage, shoreline activation, sand exposure onto a
zero-weight volcanic substrate, and simultaneous crust/bank with different heat.
All four cases match exactly, including across a derivative quad. Its initial
fixture used board units as metres and failed to expose the substrate; the final
30-metre fixture checks its sample coverage against the CPU sand field and passes.
The scoped test style check and JAR build also pass. No thresholds were relaxed.

Evidence: `build/shader-audit/magma-cheap/final/` (paired timings and shipped-map
captures), `paired-1/` (cost diagnostics), `correctness/` (flow, lifecycle and LOD),
and `palette-final/` (focused coverage regression). All nine magma map/manifest
hashes remain unchanged and match the staged runtime; no asset rebake, extra
render target, RGBA32F buffer or color conversion was introduced.
All five changed shader resources also match the built JAR by SHA-256;
`package-verification.json` records the artifact hash and resource checks.

### Earlier iterations

The first paired optimization used one fixed scene and clock on Intel Iris Xe,
1280×900, with 20 warmup and 60 measured frames per ABBA block. The opaque stage
includes other ground geometry; it excludes FFT updates. Baseline/current GPU
medians in milliseconds were:

| View | Baseline blocks | Optimized blocks |
| --- | --- | --- |
| Top | 16.12 / 17.47 | 15.91 / 15.89 |
| Orbit | 14.02 / 13.39 | 13.24 / 13.24 |
| Close crust | 12.63 / 12.62 | 12.81 / 12.79 |
| Close lava | 7.63 / 7.80 | 7.53 / 7.61 |

These are small gains with a roughly 1.4% close-crust regression, not evidence of
a general large speedup. Reusing duplicate terrain palette slots matched every
pixel exactly in all four separately checked views. The adaptive crust relief
retains all maps, but reduces height marching when the displacement is poorly
resolved. A subsequent conservative screen-footprint bound skips its Jacobian
inversion when the full twelve-step budget is already certain; its completed
material comparison is below. One first native draw spent several minutes in the
Intel driver before completing. Warm-frame GPU medians exclude that observed
cold-start cost; its precise compiler/driver cause is not established.

The next material revision uses the existing advected heat and height samples to
form rough, dark cooling skin around exposed incandescent rifts. One per-phase
mask controls reflectance, roughness and emission together; the original peak
HDR heat remains. Emission is calculated before the two source windows blend,
avoiding a nonlinear temperature ramp on their average. Smaller source-window
refreshes and weaker invertible coordinate shears aim to make downstream
transport easier to read without changing currents, gravity or the FFT. No new
texture samples or intermediate targets are added.

A strict floating-point readback exposed five invalid magma pixels at a
waterfall's free bank. The lip tapers to zero there: coincident mesh rows supplied
a zero path tangent, which the vertex shader normalized into NaN. The geometry
now uses the crest's limiting outward tangent at that collapsed endpoint, retaining
the mouth's slope and winding. Mesh positions are unchanged. Production and
material-probe readbacks are finite after the repair; the probe covers 338,220
molten pixels, with 42.70% quiet cooling skin, minimum cold roughness .7988 and
peak red emission 9.2969 in canonical HDR units.

This appearance follows the broad dark plates and incandescent margins in
[USGS observations of lava-lake crust](https://www.usgs.gov/media/images/radial-fracture-patterns-halemaumaus-lava-lake-surface-kilauea-volcano-summit).
Coverage thresholds and basalt roughness/reflectance remain artistic estimates;
this is not a temperature, solidification or fluid-dynamics simulation. Native
heat, motion, phase-continuity, live-cooling and material-LOD checks passed for
the first optimization. The revised cooling material also passes the complete
native magma check: frozen-clock repeatability, liquid-only motion, gravity
pause/recovery, day/night and perspective views, HDR emission and live cooling
edits. All 23 waterfall geometry cases pass, including the collapsed-bank normal
regression. The focused material probe checks coexistence of quiet rough skin
and strong HDR fissures independently of lighting and display exposure.
Evidence: `build/shader-audit/validation/final-geometry-9/`.

Raw paired reports are in `build/shader-audit/performance-3/magma-timing/`.
The complete final comparison uses `build/shader-audit/magma-before-optimization/`,
which preserves all seven material sources, including the repeated palette work.
Sources from immediately before the realism revision are also preserved under
`build/shader-audit/magma-before-realism/` to isolate later changes if needed.

The completed material and relief revision uses the same 1280×900 ABBA fixture.
All 16 blocks collected 60 samples for every stage without drops; saved PNGs
match exactly for both repeats of each version in all four views. Duplicate-only
palette reuse also remains pixel-exact. Final opaque-stage GPU medians:

| View | Saved original blocks (ms) | Final blocks (ms) |
| --- | --- | --- |
| Top | 15.741 / 15.827 | 15.541 / 15.482 |
| Orbit | 13.412 / 13.604 | 12.798 / 13.252 |
| Close crust | 12.581 / 12.997 | 12.693 / 13.162 |
| Close lava | 7.634 / 7.629 | 8.664 / 7.533 |

Top/orbit show small improvements, not a major magma speedup. Close crust retains
roughly 1% overhead. The first close-lava revision block slows together with the
unchanged composite (.457 versus approximately .372 ms); the other revision block
is near the original. That result is clock-confounded and must not be summarized
as a consistent lava gain or regression. Magma remains an expensive workload.
Linking the saved reference took several minutes before timing; a worker-stack
capture places the delay in native `glLinkProgram` during reference replacement.
This does not measure the revised shader's cold startup or identify the driver's
internal cause. Reports/captures and that diagnostic are under
`build/shader-audit/final-benchmarks-10/`.

## Shipped landscape measurements

The deployment follow-up's 1440×900 Iris Xe review covers 34 top/isometric lighting conditions on
five shipped boards, with 44 saved images including overview captures. Every
view collected 60 GPU samples after 30 warmup frames; all 68 total/stage reports
have zero dropped queries. Geometry picking and GL-error checks pass throughout.
The range below spans each board's tested views/conditions, not repeated-run
uncertainty or a before/after comparison.

| Board family | Views | Complete terrain-scene GPU median range (ms) | Highest view p95 (ms) |
| --- | ---: | ---: | ---: |
| Forest | 6 | 18.25–22.51 | 23.50 |
| Canyon | 6 | 14.74–24.87 | 26.31 |
| Lake | 6 | 14.05–16.15 | 16.81 |
| Lava | 8 | 20.79–28.17 | 32.67 |
| Fungus | 8 | 16.69–23.20 | 25.57 |

These frozen terrain scenes exclude HUD, unit models and ongoing simulation
updates. They do not establish complete-game FPS or a 60 FPS result on this GPU.
Opaque terrain/plant rendering dominates these captures; the measurements do
not isolate individual material includes from geometry and overdraw. CPU
submission and synchronized frame intervals are recorded separately. A prior
landscape report with dropped queries is superseded. This run also supersedes
`final-benchmarks-10/landscapes` for the latest contact/cliff color bakes: its
runtime assets were checked against the approved source hashes first.
Evidence: `build/shader-audit/deployment-review/native/landscapes/`.

The capture review found no new material seams or broad color discontinuity in
the tested forest, canyon, lake, lava and fungus views. Night emission stays
distinct from dark reflected surfaces. Source-texture repetition, polygonal
fungal glow and the busy close molten pattern remain visual/art limitations;
these captures do not establish photorealistic assets or temporal antialiasing.

## Other reviewed programs

- **Ocean simulation:** 128² cascades, batched horizontally; 14 butterfly stages
  plus spectrum/finish, with foam feedback on water. Fixed-size cost is separate
  from screen-space water/magma shading. Initial-spectrum transcendental work
  occurs on wind/gravity changes. Wave intermediates now use RGBA16F; the two-value
  feedback controller retains RG32F to prevent small updates rounding to zero.
  The static two-channel Gaussian seed texture also retains RG32F; neither is an
  RGBA32F color target.
- **Shared lighting:** GGX, diffuse energy coupling and analytic sky environment
  use the same roughness/Fresnel contract. Some callers repeat identical lookup
  coordinates; compiler elimination is possible, so source repetition alone is
  not evidence of a gain from refactoring.
- **Fog/clouds:** fog runs at quarter width and height, with four density samples
  and optional 12-step shafts. Clouds use a bounded 192² atlas with 12 steps.
  The screen composite combines texture reads with position reconstruction,
  atmospheric evaluation and display grading; its limiting hardware resource has
  not been isolated. Disabled features have guarded paths. Fresh 1280×800
  synthetic scene medians span 2.14–3.36 ms across
  clear, fog, precipitation and storm conditions (synchronized frame wall time,
  not isolated shader GPU time). The 64×64 cloud/caster fixture spans 3.15–5.18 ms.
- **Heat:** quarter-width/height extraction plus two five-tap blurs. Tactical
  extraction deliberately checks all 16 source texels before averaging so hidden
  lava cannot leak into visible pixels.
- **Roads/damage:** mapped coats, wetness, transition and wear work can be costly
  with overlapping decals, but no fresh isolated bottleneck is established.
  Packed mask channels are data, so their one explicit tint decode is necessary.
- **Units/models:** mapped materials and overlays add texture reads to common
  lighting. Ordinary sRGB color textures decode when sampled; prepared linear
  textures, material factors and glTF vertex colors stay linear.
  Shadow/depth/color-only captures are separate programs.
- **Shadows:** depth shader is small, with optional alpha sampling. Shadow-map
  geometry, resolution and cache invalidation can dominate independently of the
  fragment shader. The packed depth representation is numerical data.
- **Tactical/UI:** hex masks, labels, grid, ECM, outline colors and wireframe use
  bounded analytic work. ECM reads up to six neighboring cells; labels reconstruct
  depth and write fragment depth. Screen coverage and submitted geometry matter
  more than their short source length. These use authored unlit palettes rather
  than physical material shading. Scene overlays write linear scene color;
  UI drawn after the composite stays in display space.
- **Particles/weather:** ordinary projectiles, beams, splashes, rain, snow and
  hail use short analytic masks. Sprite fire, spores and geysers have procedural noise,
  but no volume march. Overlap and full-resolution accumulation are the relevant
  risks; no evidence currently labels each of these individually slow.

## Format and validation evidence

Scene color, preview color, weather, fog, heat and mixed material arrays remain
RGBA16F. No RGBA32F allocation remains. Ordinary color textures use sRGB storage;
normal, height, roughness, metallic, coverage and depth data are not gamma colors.
RGBA16F costs eight bytes per texel and preserves alpha; RGB-only packed HDR
formats cannot replace targets whose alpha stores coverage/transmittance.

An independent double-precision separable DFT of the stored spectrum checks all three water cascades at
three wind strengths and gravity from 0.01g to 10g. Half-float wave-height RMS
error was **0.38–0.60%** relative to that reference on this driver. Existing foam,
travel direction, wind-transition and gravity recovery checks also passed.
This establishes tested simulation precision, not universal numerical equivalence.

Native PBR reciprocity/energy checks, scene storage/lighting integration and the
atmosphere regression suite pass with the completed RGBA16F pipeline.
Scene RGB uses a fixed 1/32
linear scale at its first writer, restored once at the display composite.
Alpha and numerical data are unchanged. The sharp directional-light case and
storage contract are described in [gpu-atmosphere.md](gpu-atmosphere.md).
The bright-range, dark-adjacent-half and display probes pass. A separate native
regression reproduces a one-ULP normalized-direction overshoot: clamping the
Schlick cosine to its physical range avoids a negative GLSL `pow` base. The
projectile cap likewise uses multiplication for its signed coordinate's square.
Core shader and particle checks pass. These changes fix the numerical inputs;
there is no final-color NaN masking.

The finite-source probe independently integrates a circular sun with 65,536
samples and checks directional energy with a different 16,384-sample sequence.
The measured maximum absolute energy residual is .003157; mirror-peak error is
1.14%. Off-peak error reaches 34.04% for the sharpest lobe: the single-lobe
approximation is deliberately documented rather than called exact area lighting.

Raw reports and paired captures for this checkout are under
`build/shader-audit/`. Benchmarks use fixed camera/clock/renderer and GPU timestamps
where reported. Synchronized frame wall times include CPU work and are labeled
separately. Warm-up, repeated baselines and reversed ordering distinguish shader
changes from shader compilation and clock variation.

## Terrain color and material follow-up (2026-10-12)

The ordinary palette now prepares projection and shared noise once and skips
zero-weight non-base materials. A substrate that can receive sand exposure still
evaluates. Derivatives and implicit noise reads stay outside varying coverage
branches; material-map reads retain explicit gradients. Eight native palette
cases, including overrides, slopes, volcanic shore activation and sand transfer,
matched the former implementation exactly in every half-float output channel.

An ABBA comparison on Intel Iris Xe at 1440×900 used 30 warmup and 60 measured
frames per block, frozen animation, identical terrain and stable draw sorting.
Only `terrain-materials.glsl` changed between each reference/current pair; the
daylight correction was common to both. All three scenes produced byte-identical
images before/after optimization and in repeated controls, with no dropped GPU
queries. These are complete terrain-scene GPU medians, excluding HUD and units:

| Morning top view | Reference repeats (ms) | Optimized repeats (ms) |
| --- | ---: | ---: |
| Canyon | 14.90 / 15.16 | 14.80 / 15.02 |
| Lava | 20.91 / 20.70 | 21.06 / 20.92 |
| Fungus | 18.82 / 18.17 | 17.30 / 16.89 |

Fungus improved by about 1.40 ms (7.6% comparing repeat means) in this run.
Canyon and lava differences overlap run variation and do not establish a gain.
The simpler shared evaluation is retained without claiming a universal speedup.

Separate lava ablations retained geometry, depth and the shadow prepass. Complete
terrain controls bracketed the run at 21.12 and 20.98 ms. Replacing material
evaluation with neutral inputs gave 10.35 ms; disabling receiver shadow sampling
gave 19.60 ms; disabling local lava/fungus lighting gave 18.40 ms. Thus material
evaluation is the largest measured component (roughly 10.7 ms), followed by local
lighting (2.6 ms) and receiver shadows (1.5 ms). These marginal costs are not
additive or predictions of achievable optimization: the material probe removes
normal/relief/albedo/heat evaluation, not just memory fetch latency. The fungal
ablation controls drifted from 16.88 to 18.56 ms, so that run does not support a
precise fungal shadow or local-light cost.

Evidence: `build/shader-audit/terrain-color-performance/optimized/landscapes/`.
Source replacement/link time is logged separately and excluded from GPU timing.
This comparison precedes the replacement lava artwork and molten spill-light
calibration. The earlier `color-first/landscapes/` captures isolate the elevation
grade correction, before the separate daylight white-point correction.

### Matched HEAD and frozen-current color comparison

The renderer at `08857e942cdb38e243517c72078cb7993e6d90f5` and a frozen working
copy captured at 2026-10-12 20:06 UTC were built independently. Both used the
same frozen current assets, so this isolates renderer changes rather than
reconstructing historical artwork. A shared external harness rendered five
flat material families at levels zero and three, clear 13:00 daylight, a fixed
top camera and a frozen animation clock. Every frame repeated byte-exactly.
All versions used Intel Iris Xe at 1440×900 and the central 320×320 physical
pixels. Grass blades were disabled; deterministic terrain shrubs/stones remained.

The comparison confirmed that ordinary terrain was less saturated even at
level zero. It also identified inherited display-space tint/contrast controls
whose strength had been weakened by applying their coefficients directly to
linear pigments. The subsequent calibration converts those authored gains to
their equivalent linear strength, using a shared cubic approximation to
`gain^2.2`. Its maximum relative error is 0.164% over the supported gain range
`[.6,1.4]`; current controls remain inside that range. It adds no texture reads,
pixel encode/decode cycle or lighting changes. Weathering still uses its original
control strength for roughness.

| Level-zero material | HEAD saturation | Frozen snapshot | After gain calibration |
| --- | ---: | ---: | ---: |
| Grass | 47.68% | 38.69% | 39.82% |
| Dirt | 28.77% | 18.89% | 22.03% |
| Sand | 46.08% | 40.95% | 40.93% |
| Rock | 15.27% | 8.80% | 9.11% |
| Fungus | 24.83% | 25.29% | 25.68% |

These are mean displayed HSV saturation values, not intrinsic pigment chroma.
The calibration restores part of the appearance difference, most visibly in
dirt. Correct linear filtering, the exact sRGB boundary, the new lighting and
physical reflections still differ from HEAD; the remaining gap must not be
called fully resolved or removed with a blanket saturation control. Encoded RGB
brightness is reported separately and is not physical scene luminance.

Texture contrast changed more than mean saturation. In the same level-zero
crops, display-brightness standard deviation divided by mean increased from
`.06251` to `.08375` for grass (HEAD `.09686`), `.14692` to `.17316` for dirt
(HEAD `.18624`), and `.08543` to `.11023` for rock (HEAD `.12189`). This ratio
accounts for the changed average brightness. It includes material-region,
shading and grid variation, so greater contrast alone is not a correctness
criterion. Independent visual review found clearer soil/rock separation and
no new seams or clipped pale patches. No RGB channel reached 255 in any of the
30 measured crops. Full metric definitions and image hashes are saved under
`head-comparison/contrast/`.

Paired full-resolution images and measurements are in
`build/shader-audit/terrain-color-performance/head-comparison/`: `comparison.html`
preserves the original two-version comparison and `calibration.html` adds the
later primary checkout. Both frozen worktrees retain their original production
sources and assets. Relevant current terrain inputs were rechecked against the
snapshot: 185 files, with no filename or SHA256 differences.

The first gain-calibration validation passed the native linear-color/BRDF,
authored-gain/elevation and isolated fungus checks, plus all four clear-midday
ABBA landscape cases (forest, canyon, lava and fungus). Each same-source repeat
was byte-exact, with 60 GPU samples per block. Scoped test style also passed.
The archived run is `tint-verified/validation-73755/`. Timing drift and other
machine activity prevent a universal performance claim: canyon's repeat means
increased from 21.64 to 22.57 ms in that run. Shader preparation is excluded
from those frame timings; one replacement took 77.86 seconds.

The final source combines bed tint with its family/abrasion coefficient before
one authored-gain evaluation. Existing cliff/shore callers still receive one
calibration through the shared wrapper. This removes a repeated cubic response
for dirt, rock, lunar and desert materials without another helper, texture read
or color transfer. The combined domain is `[.702,1.2744]`; independent review
confirmed all callers and unchanged non-color channels. Since the polynomial
only approximates the exact multiplicative power, the regrouping is not claimed
to be bit-identical. Its largest sampled relative coefficient difference from
the first calibration is 0.02833%.

Final native validation passed all ten matched flat-material captures, the
linear-color/BRDF and authored-gain/elevation checks, and a canyon clear-midday
ABBA comparison. Both source repeats were byte-exact. Regrouping changed the
canyon frame by at most one 8-bit RGB step, with no channel difference above two.
Reference GPU medians were 21.32 / 18.75 ms and combined-gain medians were
19.83 / 21.21 ms; this drift does not establish a speedup. The simpler single
evaluation is retained without making that claim. Evidence and validation XML
are in `tint-combined-verified/`; `head-comparison/calibration-final.html`
compares HEAD, the frozen snapshot and the final sources, while the original
calibration report remains intact.

The earlier extended landscape sweep twice reached the 60-second terrain-preparation
limit on the first lava-overcast overview, after clear morning/midday captures
completed. This transition introduces cloud-enabled shader variants; teardown
was observed waiting for the shared shader compiler. That trace did not identify
the exact program or driver stage. The targeted `Lava Tubes 1` transition now
passes with the unchanged timeout, as recorded below. This resolves the observed
targeted failure; it is not a rerun of the entire extended landscape matrix.

## Terrain preparation and lava follow-up — 13 October 2026

The retained implementation specializes volcanic blends by their one distinct
ordinary material family, rather than every slot arrangement. Identical
ordinary inputs share a complete material evaluation; all-volcanic blends
omit it. Concrete, multiple ordinary identities and unsupported inputs keep
the generic path. Map and scale overrides participate in identity, including
the existing fourth-slot alias rule. Program selection and preparation share
the same derived key. Preparation also distinguishes ice modes and color
domains and includes the eligible alpha-tested scar variants.

The native material oracle passed all 14 cases against the saved evaluator:
every completed channel was identical after half-float storage. Coverage
includes absent weights, same-quad boundaries, shore activation, sand exposing
the underlying material, map overrides, repeated identities and all-volcanic
palettes. Different slot layouts with the same ordinary family reuse a program.
The five-phase preparation regression passed board, ice, night, cloud and
volcanic transitions, including exactly one ordinary and one scar program per
tested specialized family before drawing. All 36 logged sculpt source hashes
matched exported fragments. These are correctness checks, not startup timing
comparisons.

Lava now uses height-priority contact between moving opaque plates. Filtered
heat preserves their ordering when distant relief maps fade out. Cold and
exposed surfaces use roughness floors of .96 and .92; emission remains a
separate contribution. The final production core/motion test passed with
minimum cold roughness .9536, overall minimum .9180 and peak canonical red
radiance 9.5. It produced 121 paired close/far frames over four seconds and two
sun-aligned grazing views. Smaller animation steps reduced both linear and
display differences; frame-delta metrics alone are not a perceptual smoothness
guarantee. Independent visual review of the sun-aligned 07:00/17:00 captures
found readable incandescent rifts where reflections had previously washed them
nearly white. Selected adjacent frames retained opaque plate interiors without
doubled translucent crack patterns or a whole-board reset flash. Plate coverage
still changes during the bounded handoff; this is not a rigid-raft simulation.
Existing flat-to-fall shading joins remain visible in both versions.

Orthographic views now retain a vertical horizon-to-zenith sky backdrop;
perspective sky still follows world directions. Native compositor checks passed
synthetic directions and 24 actual atmosphere palettes through linear
interpolation and final display encoding. Real-board checks retain visible
cloud changes and sky gradients. See [gpu-atmosphere.md](gpu-atmosphere.md).

### Measured frame cost

The final ABBA comparison used Intel Iris Xe, driver 32.0.101.7092, at 1440x900,
with top views and clear 13:00 lighting. Only `terrain-materials.glsl` changed
between reference and current; lava appearance and sky were identical on both
sides. Each of twelve blocks had 30 warmup frames and 60 measured samples, with
no drops. All six same-source repeats were byte-exact. Cross-source images were
exact for Box Canyon and fungus; Dome Vent differed by at most one display byte.

| Shipped board | Reference GPU medians, ms | Current GPU medians, ms |
| --- | ---: | ---: |
| Dome Vent 1 | 22.6418 / 24.6689 | 20.6044 / 20.5501 |
| Box Canyon | 12.7107 / 12.4054 | 11.9357 / 11.8815 |
| Fungal Crevasse | 17.2851 / 19.2363 | 17.2513 / 17.7413 |

Both Dome current blocks were faster than both references. The means of block
medians differ by 3.0781 ms (13.0%); opaque terrain accounts for 3.0733 ms.
Reference drift of 2.0271 ms limits the precision of that percentage. Box's
ordinary-material preprocessed token sequence is unchanged, so its lower
observed timings are not attributed to this optimization. Fungus drift also
prevents a reliable improvement claim. These are synchronized terrain-only GPU
durations, not pipelined application frame rates or every camera/weather case.

Earlier loop and deferred-sampling candidates were rejected after measured
regressions. A slot-mask candidate reduced Dome frame cost but multiplied
preparation variants; the common-family implementation supersedes it. Initial
Dome ablations identified material evaluation as the dominant removable work:
about 12.95 ms of opaque time, compared with 2.33 ms for receiver shadows and
1.66 ms for local lava light. These are marginal, non-additive diagnostics; the
material bypass removed more than texture fetches, and shadow casting remained.

### Preparation outcome and remaining cost

Production-only runs then passed the relevant weather transitions without
source replay or a longer refinement timeout:

| Board and sequence | Initial blocking preparation | Overcast overview refinement | GPU medians by condition |
| --- | ---: | ---: | --- |
| Dome Vent 1: midday, overcast | 26.308 s | 24.792 s | 20.0406 / 22.6588 ms |
| Lava Tubes 1: morning, midday, overcast | 26.476 s | 26.844 s | 20.0400 / 19.6713 / 21.9747 ms |

The Lava Tubes sequence covers the previously failing transition and stays
within its unchanged 60-second refinement deadline. All five views retained
60 GPU samples with no drops. Subsequent close-view refinement took about
24–27 ms. Preparation still takes tens of seconds when new variants are needed;
the result does not establish instant startup or pass the entire landscape
matrix.

Named shader timing places much of that delay in driver calls. In the matched
benchmark, Dome's initial 28.526-second blocking update included eight shared
worker link intervals totaling 25.145 seconds. First generic source replay
took 62.695 seconds for twelve retained programs; current replay took 19.012
seconds. Replay uses current Java variant keys and is not historical startup.
Cache state was uncontrolled, and the same source has linked quickly in one
context and slowly in another; these wall intervals do not establish a
cold-versus-warm cache mechanism. No timing instrumentation changes GL
synchronization or scheduling policy.

Evidence is retained under `build/shader-audit/stall-lava/`: `first-dome-evidence/`,
`native-validation/evidence/`, `common-validation/evidence/`,
`common-performance/evidence/`, `dome-readiness/evidence/`,
`lava-readiness/evidence/` and `native-final-motion/evidence/`. Reports separate
GPU samples, preparation walls and source replay. The final fingerprint in
`provenance/final/source-manifest.json` contains 391 files and remained unchanged
through final validation. The terrain atlas remains immutable before-change
visual evidence. The pipeline retains RGBA16F scene storage; no RGBA32F buffers
were introduced.

The follow-up comparison contains 24 matched pairs across Dome Vent 3,
Moonscape 1, Fungal Crevasse and Box Canyon: top/angled views at dawn/day/dusk.
Each native 1920x1200 Iris Xe capture repeats exactly and matches the original
camera, board, manifest, weather, exposure and animation-clock inputs. The
original 13-map HEAD/current atlas is preserved. The separate follow-up includes
four-second compressed motion previews, native low-sun stills, validation hashes
and the measured timing reports. Its builder and evidence are under
`build/shader-audit/stall-lava/review/`.

### Cloud reuse and unused-work follow-up

Terrain now retains its cloud-shadow attribute in clear and covered weather.
A draw-uniform enable flag bypasses the atlas sample while inactive; atlas
updates still run only for active clouds. Fog receives the same enable state.
This removes a shader-key change at the clear-to-overcast transition. It also
creates the small cloud resources earlier and retains a sampler in clear-weather
programs; it does not remove the cost of rendering active clouds.

The atmosphere native test passed in 37.405 seconds, including unchanged
program count and attribute identity across clear/covered/clear, exact restored
clear pixels, disabled stale-atlas behavior and the existing fog/lighting checks.
The palette oracle selected in that first job was skipped because its saved-source
baseline was missing. The subsequent correctly configured rerun passed both
tests with no skips: all 14 material cases were identical after half-float
storage, and the linked-program sampler survey stayed below its 16-unit budget.
Across seven battle-view scenarios, the maximum was 14 active samplers in water
surface programs including cloud shadows; volcanic sculpt programs used at
most 11. These are actual linked-uniform counts on Intel Iris Xe, driver
32.0.101.7092, not a native macOS compatibility run.

A current-only Iris Xe run then covered morning, midday and overcast on both
Lava Tubes 1 and Dome Vent 1, with 60 samples per view and no drops:

| Board | First blocking update | Overcast overview / close refinement | Full GPU medians: morning / midday / overcast |
| --- | ---: | ---: | ---: |
| Lava Tubes 1 | 27.215 s | 24.845 / 23.892 ms | 19.2254 / 19.6935 / 20.5133 ms |
| Dome Vent 1, second board in the process | 3.289 s | 24.009 / 24.165 ms | 20.1356 / 20.3405 / 21.3851 ms |

The shader log contains no events between either midday capture and its
overcast capture. The first Lava update still required sixteen-stage/eight-program
sculpt preparation; the second board reused the same source identities, so its
shorter update is not evidence of a cold-start speedup. These runs configure
camera, lighting and clouds before initial terrain preparation, matching the
application. Earlier preparation figures used the original fixture order.
The blocking-update interval excludes preceding camera/compositor setup and
is not total application startup.

All four comparable Lava Tubes PNGs, including the midday overview, are
byte-identical to the earlier `lava-readiness` captures. Both runs use the same
three conditions and frozen animation clocks. Dome's older run omitted morning,
so its later clocks differ and its PNGs are not used as a parity comparison.

A combined receiver-shadow and unused-sand-work candidate was rejected: its
mean full-frame GPU medians increased by 1.9414 ms on Dome, 0.2710 ms on Box
Canyon and 0.6881 ms on fungus despite identical pixels. The isolated sand
guard is retained: palettes without sand skip two irrelevant noise fields.
Its Dome medians were 20.4743/20.5026 ms before and 20.2950/20.3873 ms after,
a modest 0.1473 ms (0.72%) difference between means. Box and fungus results
overlapped or drifted, so no improvement is claimed there. Both comparisons
retained all 720 samples, zero drops and exact frozen repeats.

Immutable evidence and parsed summaries are under
`build/shader-audit/next-pass/{unused-work-performance,sand-only-performance,
cloud-validation,cloud-readiness,cloud-samplers}/evidence/`. The cloud readiness evidence also
contains `cloud-transition-analysis.json`, with timestamp intervals, input
qualifications and full-image hashes. Initial preparation remains a material
latency limitation; eliminating the weather-triggered links does not resolve it.

A two-worker shared-compiler experiment was also rejected. With the same six
views, shader source identities and preparation order, the first Lava update
took 28.177 seconds versus 27.215 seconds with one worker. Each worker linked
four of the eight sculpt programs. Their logged link-call intervals overlapped
for about 4.215 seconds, but that overlap did not reduce total preparation time;
it does not establish parallel execution inside the driver. The second-board
Dome update decreased from 3.289 to 2.780 seconds in a reused-source process,
which does not establish a fresh-start benefit. Both runs retained all 360
samples with zero drops, and all eight complete PNG files were SHA256-identical.
There were no new shader events at either overcast transition. This evidence
does not justify retaining two compiler contexts. The comparison, per-worker
intervals and source/image hashes are archived in
`build/shader-audit/next-pass/compiler-readiness/evidence/compiler-comparison.json`.

The final implementation retains one compiler worker. Only its isolated teardown
fix survives the experiment: the executor shuts down even if context cleanup
throws, and the hidden window is destroyed only after confirmed detachment.
This does not change compilation scheduling or remove first-load latency.
Final scoped Checkstyle, 62 pipeline unit tests and the native one-worker shader
ownership test passed without skips. Their archived results and a verified
391-file rendering-source fingerprint are under
`build/shader-audit/next-pass/final-validation/`. Independent final review found
no further correctness defect in the retained cloud, sand or preparation-order
changes. The measured 19–21 ms terrain-only GPU cost still exceeds the 16.7 ms
budget; these results are not a 60 FPS or deployment-readiness certification.

### Material cost breakdown — 13 October 2026

The next investigation isolates material work on shipped **Dome Vent 1**, using
top and angled views at clear 13:00 on Iris Xe, 1440x900. Each run passed all
17 blocks: seven source-only ablations and ten production controls, with 30
warmup frames and 60 measured samples per block, zero drops. Every restored
production frame matched the native full-frame RGBA oracle and the decoded
full-resolution RGB PNG. Production material code was unchanged by these probes.

Each value below is the mean of the two adjacent production GPU medians minus
the probe median; brackets give the two individual differences. Positive means
the ablation was faster. These are temporal-control brackets, not confidence
intervals. Values cover the complete terrain scene; opaque terrain accounts for
nearly all of each difference.

| Removed work | Top delta, ms [bracket] | Angled delta, ms [bracket] |
| --- | ---: | ---: |
| Complete ordinary material evaluator | +7.657 [7.618, 7.695] | +7.431 [7.125, 7.738] |
| Ordinary normal/AO sampling and reconstruction | +3.080 [2.733, 3.427] | +2.955 [2.797, 3.114] |
| Ordinary color/height sampling and pigment treatment | +3.565 [3.174, 3.956] | +2.845 [2.652, 3.039] |
| Static crust/bank relief tracing and displaced reread | -0.152 [-0.418, 0.114] | -0.593 [-0.600, -0.586] |
| Complete static volcanic material | +0.989 [0.698, 1.280] | +1.049 [0.936, 1.162] |
| Second flowing-lava phase and plate contact | +0.446 [0.247, 0.646] | -0.089 [-0.190, 0.012] |
| Palette contact-noise fields | -0.097 [-0.244, 0.049] | +0.323 [0.076, 0.571] |

The ordinary evaluator is the dominant measured removable material work:
about 7.4–7.7 ms at the bracket midpoints. Color/height and normal/AO each account
for roughly 3 ms in their separate probes, **not additive component costs**.
Neutral heights change material competition and which normal reads survive;
neutral normals/cavity change downstream lighting. Constant outputs also let
the compiler remove dependent calculations. These measurements do not separate
texture bandwidth from arithmetic or register pressure.

Static volcanic evaluation is about 1 ms in both views. Disabling its relief
path was slower by about 0.59 ms in the angled view and unresolved in the top
view: this does not establish that parallax is free, or that removing it would
improve production performance. The second flowing phase had a modest top-view
effect but no resolved angled benefit. Contact noise was unresolved from above;
its small angled difference has 0.495 ms control drift and is not a strong
optimization target from this single sequence.

The source explains where to investigate next:

* `GpuTerrain.SCULPT_MATERIALS` supplies Dome's ordinary `VOLCANO` family with
  `volcano-ground` for ground/debris, `volcano-cap` for caps and `volcano-cliff`
  for walls. Lunar uses `lunar-ground`, `lunar-debris` and `lunar-cliff`; the measured
  Dome mixed program specializes ordinary family 10 (volcanic ground), not lunar.
* In `terrain-materials.glsl`, `naturalMaterialFor` samples candidate role
  color/height at lines 400–408, calculates height competition, then samples
  normal/AO only for surviving weights at lines 430–433. `sampleMaterial`
  (line 173), `materialNormal` (209), `translatedTexel` (158) and `cliffTexel`
  (168) implement the projection and repeat suppression. For one active
  nonfungal role with full normal detail, the nominal color-plus-normal read
  counts are **4 on a top projection, 8 on walls, 12 where both contribute**.
  A non-single-scale top role can add one coarse color sample. These counts
  are conditional shader texture calls, not hardware anisotropic taps; role
  pruning, detail fade and compiler reuse change actual work.
* `terrain-magma.glsl::magmaSample` starts with four aligned maps at full
  detail: color, heat, surface and normal. Static `magma-solid.glsl::magmaRelief`
  can add a displaced surface reread and a bounded height march, reaching
  7–18 nominal reads per retained projection when marching. Flowing
  `magma-flow.glsl::magmaPhaseSample` evaluates two complete phases, normally
  eight reads per retained projection before detail fades. `magmaSurface`
  retains one to three triplanar axes; map/detail/axis conditions prevent these
  figures from being unconditional per-fragment totals.

The best next diagnostic target is ordinary role evaluation, repeated scale
sampling and projection overlap, while preserving material boundaries, normals
and texture filtering. No new optimization is implemented or recommended as a
proven speedup by this breakdown. The separate
[terrain calibration board](gpu-terrain-calibration.md) supplies named materials,
slopes and contacts for visual checks; it is not the source of these Dome timings.

Evidence: [top summary](../build/shader-audit/material-breakdown/top/evidence/material-summary.txt)
and [angled summary](../build/shader-audit/material-breakdown/angled/evidence/material-summary.txt),
with JSON, native XML, shader snapshots and original captures in the same run
trees. `summarize_materials.py` requires completed archives, adjacent controls,
the default seven probes or an explicitly selected subset, complete samples
and exact restored images. It records source
replacement wall times separately from GPU time. A concurrent CPU-only board
validation initialized the shared logger at 07:06:26–28 UTC (09:06 local) during
the top run; that shared log is not an exclusive, complete benchmark startup
trace. GPU tables and frame oracles come from the dedicated native report/XML,
and source-replacement walls come from its report, not a sum of shared log entries.

### Focused calibration cliffs — 13 October 2026

A separate run uses the expanded
[32x34 calibration board](gpu-terrain-calibration.md#cliff-section-rows-19-34),
whose lower half contains direct four-to-six-level cliffs. The angled camera
fits rows 19–34 before close zoom; the complete board remains installed. The
captured view contains substantial Mars, volcanic-ground, tropical-ground and
crust faces, with sand/rock and concrete above and cropped snow nearby. Molten
lava occupies a small corner; the full fall is outside the frame. This is a
mixed-material cliff workload, not coverage of every bay or a matched
cliffs-versus-flat comparison with Dome Vent.

The Iris Xe run at 1440x900, clear 13:00, passed one native test with no skips
or failures. All eleven blocks retained 60 GPU samples after 30 warmup frames,
with zero drops. Seven production controls were byte-exact in the native RGBA
oracle and decoded full-resolution RGB images. Their full-scene medians range
from 31.8733 to 33.4026 ms. The same adjacent-control calculation gives:

| Removed work | Full-scene delta, ms [bracket] | Adjacent control drift, ms |
| --- | ---: | ---: |
| Complete ordinary material evaluator | +16.753 [16.746, 16.760] | +0.013 |
| Ordinary normal/AO sampling and reconstruction | +3.363 [3.267, 3.460] | -0.193 |
| Ordinary color/height sampling and pigment treatment | +4.008 [3.832, 4.185] | +0.354 |
| Static crust/bank relief tracing and displaced reread | +0.934 [0.205, 1.663] | -1.458 |

Opaque terrain accounts for nearly all these differences. The full ordinary
evaluator is again the largest measured removable bundle. Its 16.75 ms result
does not mean texture reads alone cost that much: removing the evaluator also
removes role/height calculations and enables dependent compiler simplifications.
The normal/AO and color/height results are separate, overlapping ablations;
their sum or subtraction from the full bypass cannot isolate an arithmetic
remainder. Relief removal is faster than both neighboring controls here, but
the 1.458 ms control drift makes its precise contribution uncertain. This does
not contradict the different Dome result or establish a general relief-removal
speedup. The run identifies ordinary roles, repeated sampling and wall
projections as the next diagnostic focus; it implements no optimization.

Preparation remains a separate limitation. The initial blocking terrain update
took **115.858 s**, including CPU/driver preparation. Replacing sources for the
first probe took 60.298 s across 31 retained programs; restoring production
first took 111.276 s, while later restores took about 0.56 s. These are source
replacement wall times, not per-frame GPU costs, and uncontrolled driver cache
state prevents a cold-cache interpretation. The larger board, material mix and
camera differ from Dome, so the preparation figures are not an A/B comparison
either.

The [cliff summary](../build/shader-audit/material-breakdown/cliffs/evidence/material-summary.txt)
and matching JSON contain each block, adjacent controls and separate wall
measurements. The same evidence directory archives native XML, benchmark
source, map manifest, CPU geometry validation and a 391-file rendering-source
verification with no changes. Original captures remain under
`build/shader-audit/material-breakdown/cliffs/landscapes/`; earlier Dome evidence
is unchanged.

### Optimization follow-up — translated-sample endpoints

The first candidate skipped a translated texture read when its existing
`smoothstep` weight was exactly zero or one. Coordinates, explicit gradients
and blend weights were unchanged. An Iris Xe reference/current/current/reference
run covered Dome Vent and the calibration cliff close-up at 1440x900, clear
13:00, with 30 warmup and 60 measured frames per block. All 480 samples were
retained without drops; every complete image was identical across versions.

The candidate was **rejected and reverted**. Dome's mean of the two full-scene
GPU medians increased from 22.2579 to 23.0513 ms (+0.7934 ms, 3.56%). The cliff
view decreased from 33.2386 to 33.0968 ms, but its 0.1419 ms difference was
smaller than the roughly 0.21 ms control drift. Fewer source-level texture
calls did not establish a rendering improvement. The retained shader is
byte-identical to the saved pre-candidate source.

Evidence, the rejected source and full-resolution pixel comparisons are in
`build/shader-audit/optimization-round/translated-endpoints/evidence/`.

### Native upload lifetime correction — 13 October 2026

The follow-up sampler survey crashed in Intel's `igxelpicd64.dll` during a
road-array `glTexSubImage3D` upload on Fire And Ice 2. The array dimensions,
float-buffer size and unpack alignment were consistent. Inspection of the
pinned libGDX/LWJGL bindings found an unsafe lifetime boundary: temporary
cleaner-owned direct buffers were converted to raw native addresses with no
subsequent Java use retaining their backing allocations through the upload.
The crash's native-call stack map also contained no live object reference.
This establishes a lifetime defect; it does not prove garbage collection was
the cause of that particular driver crash.

The native consumers now retain each affected buffer with
`Reference.reachabilityFence` after the call, including exception paths.
The correction covers material/environment array layers, composed linear
textures, decal mips, hex parameters, ocean seeds and temporary matrix-editor
uploads. It adds no copies, GL synchronization, cache or texture-format change.
RGBA16F remains unchanged; no RGBA32F resource was introduced. Buffers with
existing post-call reads or explicit ownership were left alone.

The rerun passed all eight native tests on Iris Xe: six color/upload tests,
the seven-scene sampler survey, and the shader-core test. Fire And Ice 2
completed, and the largest surveyed program used 14 of the portable 16 texture
units. These checks cover regression behavior, not a proof of the original
crash's cause. Scoped main/test style checks also passed. The original failure
is retained in `build/shader-audit/optimization-round/shared-scar-native/batch-evidence/`;
the correction's successful evidence is in
`build/shader-audit/optimization-round/upload-lifetime-native/evidence/`.

### Rejected common terrain/scar source

A second candidate gave ordinary sculpt terrain and projected damage scars
identical shader sources, selected by a draw uniform, while preserving separate
program handles and ownership. Preparation, scar geometry, damage controls,
shader editing and sampler checks passed. Four separate Iris Xe processes
(reference/current/current/reference) then tested the original two Java
assembly files and three GLSL resources against the candidate, with identical
boards, stable draw ordering, camera, clock and runtime assets. Initial shader
drafts were installed before terrain construction: a classpath-only reference
would incorrectly have loaded the live checkout shaders.

The candidate was **rejected and reverted**. Mean initial terrain-update wall
time improved 40.60% on Dome and 45.80% on the cliff scene, with driver cache
state uncontrolled. However, mean full-scene GPU medians increased from
21.7345 to 25.0943 ms on Dome (+3.3598 ms, 15.46%) and from 32.8251 to
36.0815 ms on cliffs (+3.2565 ms, 9.92%). The opaque stage accounts for the
regression. The shared program retained a discard path even for ordinary
terrain; the measurements do not isolate the driver's precise cause.

All 480 measured frames completed without dropped queries. Every saved close
and overview image was byte-identical across both versions and repeats. The
saved five production files and original preparation fixture were restored;
the temporary process-comparison fixture changes were also removed. The
independent native-upload lifetime correction remains. Results, effective
sources, frozen input hashes, native XML and logs are retained under
`build/shader-audit/optimization-round/shared-scar-benchmark/`.

### Rejected top-only ground/debris sample reuse

A third candidate reused the near/far color samples when ground and debris
shared the same texture, scale and top projection. Their independent material
weights, coarse blend and ground tint were retained. The Iris Xe ABBA run
passed all 960 measured frames and repeated images exactly within each version.
Cross-version images were exact except for one one-byte channel difference in
the cliff view.

The candidate was **rejected and reverted** because it did not establish a
repeatable benefit. Dome's angled medians were effectively unchanged
(21.6721 versus 21.6748 ms). Calibration top improved 0.3810 ms, comparable
to its 0.3732 ms candidate drift; cliffs improved 0.1059 ms with 0.4347 ms
candidate drift. Dome top's apparent improvement was confounded by a
5.2822 ms reference drift. Fewer source-level texture reads did not justify
the additional helper and control flow. Full source, logs, images and native
results remain in `build/shader-audit/optimization-round/top-pair-native/`.
The requested pre-UI-merge visual reference intentionally preserves this
candidate's captured state and identifies it as subsequently rejected.
