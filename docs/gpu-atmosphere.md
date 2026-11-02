# Lighting, atmosphere and weather

`BoardAtmosphere` turns scenario conditions into a visual snapshot.
`GpuAtmosphere` applies that snapshot to the rendered scene. The GPU window can
preview different conditions without changing gameplay visibility, orders or
the scenario's planetary conditions.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `BoardAtmosphere` | Scenario mapping, sampled time, sun/moon direction, colors, pressure and lighting values. |
| `AtmospherePreset`, `GpuAtmosphereControls` | Visual presets and the Tuning → Atmosphere controls. |
| `GpuBattleView` | Applies current lighting/camera state and orders scene, atmosphere and overlay passes. |
| `GpuAtmosphere` | Scene framebuffer/depth, fog, sun shafts, exposure, grading and composition. |
| `GpuClouds`, `GpuCloudShadow` | Animated cloud shadow field and bindings used by lit surfaces. |
| `GpuWeatherParticles` | Bounded rain, snow and hail populations and their shader programs. |
| `GpuTerrain` | Shared wetness/noise inputs and static terrain shadow resources. |

For cloud density, projection and shadow sampling, use [cloud shadows](gpu-clouds.md).
Shader file ownership is listed in [shaders](gpu-shaders.md).

## Scenario values and visual overrides

Opening a window initializes its preview from the game's effective conditions.
Each scenario-derived field continues following game updates until the user
overrides that visual control. Applying a complete preset or the conditions
dialog replaces the visual selection. **Defaults** restores the current game
conditions plus the geometry and additional effect defaults.

Presets create private `PlanetaryConditions` values and call
`BoardAtmosphere.fromScenario`, sharing the game's mapping. The conditions dialog
retains the selected conditions; arbitrary slider mixtures do not create a second
weather classification. Apply resets overrides even when the conditions are
unchanged; Cancel preserves the preview.

Lighting categories supply a time window rather than an exact clock.
The window samples a time once and reuses that choice through refreshes and
category changes. Manual time overrides persist through routine updates.
The preview is window-local and is not persisted across reopening. Time and
weather classifications do not advance automatically; their clouds, particles,
fog banks and lightning still animate.

## What the controls affect

| Controls | Effect and code to inspect |
| --- | --- |
| Time, moonlight, exposure, moon shadow contrast | `BoardAtmosphere.lighting()` determines the light; the composite applies user exposure. |
| Shadows | Geometry shadow strength, 0–100%, independent of moon contrast and cloud shadows; default 100%. |
| Ambient light | Ambient/direct balance, default 22%; 0 selects direct only, 100 ambient only. |
| Sky contribution | Scales environment diffuse illumination and reflections, 0–200%, default 100%; leaves the visible sky unchanged. |
| Lighting environment | Optional 2:1 panoramic PNG/JPEG or Radiance HDR, up to 8192×4096. Used only for lighting/reflections; clearing it restores generated sky lighting. |
| Fixed sun/moon | Uses the camera basis for a stable screen direction; terrain, units, clouds and shafts must receive that same direction. |
| Air pressure, temperature, taint | Scenario mapping controls weather eligibility, rain wetness and the atmospheric palette. |
| Gravity | Changes newly started jump arcs; an airborne jump finishes its captured arc. Movement rules remain with the game. |
| Cloud cover and shadow strength | `GpuClouds` changes the direct-light shadow field and the atmosphere's sky veil. |
| Ground fog, layer height, variation and calm drift | `GpuAtmosphere` controls height-dependent density, banks and movement. |
| Rain, snow, hail, sand, lightning and wind | Select effect populations/intensity and motion; sand uses the atmosphere rather than a precipitation particle type. |
| Haze, god rays, glare | Control uniform extinction, depth-limited shafts and the lens-facing sun effect. |

Lighting controls are window-local and reset with Defaults or an atmosphere preset.
Manual lighting changes keep the scenario's exposure adaptation, so it cannot
immediately cancel the chosen strength. The optional panorama is decoded and GGX
filtered on a background CPU task; a failed load keeps the previous lighting.
PNG/JPEG RGB is decoded from sRGB once; HDR keeps linear radiance above one.
Six 128×64 roughness levels share the existing standalone RGBA16F environment atlas,
adding no material sampler or per-frame filtering pass. The directional sun remains
separate. Imported panoramas replace the generated environment only when selected.

Generated lighting uses a 128×64 linear panorama based on the current light direction,
time, pressure, haze/clouds and taint type/strength. It is independent of the illustrated
background. Clear-air diffuse illumination retains the calibrated lighting budget;
taint changes its chroma and directional distribution through an approximate optical
medium. Moonless readability uses the same medium without a directional source,
relative to its uniform clear-air fill; its integrated luminance stays unchanged.
Vacuum readability remains explicit uniform gameplay fill. The panorama
contains no sun/moon disk, and receiving materials retain their local ground bounce.
Generation and compact filtering run synchronously when relevant settings change;
camera movement, exposure and fog-only changes reuse the result. This is a calibrated
approximation, not measured atmospheric radiometry or a per-frame sky simulation.

The normal visible background retains the UI palette. Tainted/toxic horizon colors
remain visible at night, sunrise and sunset without increasing that palette's luminance.
**Show lighting sky** displays the environment used for lighting instead. The toggle
does not change the board's illumination. The old whole-scene taint is removed; light colors
and the user's explicit display controls remain.

**Night vision.** Moonlight is deliberately far brighter than real moonlight so the board
stays readable. To still read as night, the composite applies a Purkinje-style shift
(`atmosphere-grade.glsl` `nightVision`): dim exposed pixels turn toward a cool blue-grey at
their own luminance, so brightness and readability are unchanged. A pixel with any channel
above .25 (exposed) keeps its colour, so fires, lamps, flashes and coloured emitters stay
saturated. The share is .65 at a moonlit full night and .8 without a moon (Moonless, Pitch
Black), eases in only once the sun has set (daylight below .6, `GpuAtmosphere.nightVision`)
so dusk and dawn keep their warmth, and is zero by day and in the Tactical View. Selected heat, heat glow, sun glare and flashes are added after it;
overlays and the HUD draw after the composite. Raising the scenario's exposure lifts pixels
out of the band, so at +2 EV the shift mostly disappears. The night presets set only the
brightness, in even steps: Full Moon 0 EV, Moonless -0.3 EV, Pitch Black -1 EV (lit ground
about .028, .017 and .011 linear on a grassland board).

Ground wetness follows liquid rain automatically. Freezing temperature and very
thin/airless conditions suppress it. `rain-surface.glsl` uses the same wetness for
ground and other receiving materials; puddle response also depends on the surface.

At zero gravity, `BoardAtmosphere.Settings` forces visual pressure to VACUUM.
The existing vacuum limits remove clouds, fog, haze, precipitation, sand, lightning,
wind and rain wetness for both scenario settings and local previews. Tuning shows
VACUUM and disables incompatible weather controls; pressure is locked while gravity is zero.

## Light and color contract

`BoardAtmosphere.lighting()` supplies canonical linear direct and ambient
colors. Its adaptation meters ambient plus direct light weighted by
`max(0.5, -direction.z)`, so grazing light on slopes contributes alongside level
ground. The meter includes vacuum readability and the daylight white point,
before camera-relative light placement or manual light controls. Composition applies
that one gain with user EV compensation to the complete scene, including emission,
local spill and selected heat. Scene material evaluation, interpolation and blending stay
linear. Exact piecewise sRGB conversion is shared by `GpuColor` and
`color-space.glsl`; the final composite encodes display RGB once.

Color-only images use `GL_SRGB8_ALPHA8`: hardware decodes RGB before filtering,
while alpha remains numerical. Repeating materials use separate compact arrays:
`GL_SRGB8_ALPHA8` for color/height and `GL_RGBA8` for numerical maps. The separate
authored sculpt roughness array uses `GL_R8`. Required CPU resizing uses `GpuLinearImage` to filter
color RGB in linear light, then re-encodes it for sRGB storage. Numerical channels
remain linear throughout preparation. Normal, roughness, metallic,
height, mask and coordinate channels never pass through a color transfer curve.
An image used for both color and data has a separate cached texture for each role.
File decoding also resolves grayscale channel layout before upload: libGDX's
one-channel `Alpha` result means opaque grayscale for PNG/JPEG files, whereas
caller-created alpha Pixmaps still mean coverage. Numerical grayscale maps use
ordinary RGBA storage and retain their samples without an sRGB transfer.
Imported glTF factors and vertex colors are already linear; `GpuLinearColors`
marks that contract. Procedural display-authored vertex colors decode before
interpolation. Packed vertex metadata is explicitly excluded.

The illustrated background retains UI-authored color interpolation in
`BoardAtmosphere.skyPalette`. Its display mapping is confined to the background;
it is decoded for linear scene composition and encoded with the final frame.
Both camera modes use the screen-height gradient around the finite board.
Time and clouds change its endpoints; taint keeps its lower band at night and
twilight while preserving endpoint luminance. These colors do not illuminate
materials. The native compositor checks the authored palette in both cameras.

The generated incident environment instead uses world directions and linear
radiance. Its upper hemisphere supplies diffuse and roughness-filtered specular
lighting. Its lower hemisphere is zero; the receiving material contributes the
existing local ground reflection once. Imported panoramas retain their own
lower hemisphere. Vacuum has no atmospheric scattering, but retains the explicit
readability allowance.

The clear-noon sun plus sky is calibrated to a neutral unit white on level
ground. A single RGB gain, derived from that reference, transforms direct light,
diffuse fill together. Its strength follows daylight;
full night keeps its existing palette. This corrects the overly blue illumination
introduced when the visible gradient began lighting materials, including terrain
at level zero. Cloud and pressure changes do not renormalize the sun. The generated
sky receives this white-point-adjusted diffuse budget: clear air retains its RGB,
and optical taint changes chroma while preserving its luminance. No terrain saturation multiplier,
extra color-space conversion or framebuffer format change is involved.

Direct reflection uses correlated Smith GGX and Schlick Fresnel. Directional
colors are irradiance divided by pi, so the specular helper includes the matching
pi factor. The matte lobe uses reciprocal directional-albedo energy coupling.
`GpuEnvironmentLighting` loads a precomputed 256×129 GGX sky/ground basis and
split-sum BRDF table; its right half contains the finite directional source's
energy correction. This table occupies the top-left of one standalone 512×512
RGBA16F atlas. `GpuLightingEnvironment` places six filtered 128×64 environment
tiles with seam/pole gutters below it, starting at row 130. Both share
`u_environmentLut` and are sampled at mip zero; neither occupies a material-array layer.
The directional sun/moon is excluded from the environment, avoiding double light.
Specular anti-aliasing (`light-model.glsl`, Display tab, on by default) adds the pixel's normal variance to GGX
alpha², at most .18: once per pixel, from the screen derivatives of the final shading normal (sculpted ground and
models include their mapped detail; foliage, roads and water use their base normal before any discard), plus
water's footprint term. It is
compiled in (`specularAntialiasingFlag`); off, its helpers are constants. The board's environment lighting carries
it while it is on (`GpuEnvironmentLighting.antialiasing`, part of every program key), so a switch is a lighting
change: terrain materials and family programs rebuild behind the loading stage, while water, plant, prop and unit
programs compile at their first draw, from the program cache after the first time. Movement and editor ghosts, lit
without the board's environment lighting, never compile it.
See [Filament's image-based lighting](https://google.github.io/filament/Filament.html#lighting/imagebasedlights)
and the [energy-coupled matte model](https://diglib.eg.org/bitstream/handle/10.2312/egs20011003/short16.pdf).

Sun/moon handover uses one directional shadow source. Direct illumination and
shadow contrast fade with its visibility. Full Moon enables directional night
lighting; Moonless and Pitch Black use ambient light without a geometry shadow
pass. Fixed sun/moon must be resolved before both shadow and color draws.
Free flight ignores Fixed sun/moon and keeps the world-space light. Ordinary
orbit poses retain the optional screen bearing. The generated environment stays
world-oriented, as imported panoramas do; fixing the direct light is a presentation
override, not a rotation of the environment.

Taint changes the incident environment, illustrated horizon and existing fog.
It does not add a whole-scene display tint, emission or weather. Pressure controls
scattering; time changes illumination and background brightness. Tactical labels
and UI retain their own contrast controls. Palette mapping belongs in
`BoardAtmosphere`; optical-medium coefficients belong in `BoardLightingSky`.

## Scene depth, fog and composition

GPU Tuning → Atmosphere → Lighting places four local color controls beside
Exposure: **White balance** (-1 cool to +1 warm), **Tint** (-1 green to +1
magenta), **Saturation** (0 grayscale, 1 unchanged, 2 stronger color), and
**Contrast** (0.5–1.5, with 1 unchanged). White balance and tint default to zero.
Defaults and atmosphere presets restore these neutral values; the controls are
local to the GPU window, like the other atmosphere rendering options.

The existing final composite applies white balance and saturation in linear
light to the complete scene, including sky, weather and emissive effects.
White balance uses relative RGB gains normalized to preserve neutral-gray
luminance; it is a cool/warm adjustment, not a calibrated Kelvin setting.
Contrast acts around display mid-gray after the highlight shoulder and sRGB
conversion, before the optional lens vignette. Tactical overlays and HUD colors stay
unchanged. These controls add arithmetic and two uniforms, with no additional
render pass, texture lookup, framebuffer, or terrain/shadow rebuild. The balance
gains are calculated on the CPU only when white balance or tint changes.

Verified on 2026-10-13 with `GpuColorGradingSmokeTest` and the native tuning and
atmosphere smoke tests: neutral output, both white-balance/tint directions,
saturation including grayscale sky and HDR colors, contrast, transparent
coverage, framebuffer/light reuse, preset/default reset, and the displayed
slider readings. The optional `compositeColorAdjustmentTimings` benchmark takes
a saved earlier `atmosphere-grade.glsl` via `megamek.gpu.colorBaseline`.
On an NVIDIA GeForce RTX 4070 Laptop GPU, 160 samples per variant after 64 warmup
samples (eight draws per sample, alternating variant order, GPU timestamps)
gave the following medians per composite draw:

| Resolution | Original shader | Neutral controls | Adjusted controls |
| --- | ---: | ---: | ---: |
| 1920×1080 | 0.076 ms | 0.076 ms | 0.076 ms |
| 3840×2160 | 0.302 ms | 0.302 ms | 0.302 ms |

This isolated clear-scene composite comparison found no measurable increase;
these are pass timings, not whole-game frame times or a guarantee for other GPUs.

Every main 3D scene is captured into an RGBA16F color target with sampleable depth,
including boards without lava. Values above diffuse white survive until the
composite applies exposure and the shared highlight shoulder. Adding or removing
molten terrain enables or disables the heat halo without replacing scene color
or depth. Top and isometric cameras use this same path.

Material outputs, alpha blending and additive scene effects now remain linear in
the RGBA16F target. Fog, heat extraction, grading and exposure consume
that radiance directly. Water/ice combine linear reflection, transmission and
scattering. The reduced-resolution terrain fire/smoke target also uses RGBA16F
to preserve bright emission. Weather draws into a separate, lazily allocated
RGBA16F layer sharing the opaque scene depth. The composite blends it over
fogged terrain in linear space before the common grade/display conversion;
foreground drops no longer inherit the distant terrain's fog column. Weather
itself retains the existing approximation without per-particle volumetric fog.
Tactical overlays and UI retain display output. Shader samples,
model previews and the editor's section widget
use a linear target and the shared final display conversion before presentation.
Ordinary scene transparency accumulates alpha as coverage, including particles
and faded models; additive light preserves that coverage so the uncovered sky
gradient remains visible behind the effect.

`GpuLightingConsistencySmokeTest` checks raw HDR radiance, material alpha blending,
and the sky gradient's endpoints and midpoint against independent linear-light
references. `GpuParticleShaderSmokeTest` checks overlapping smoke coverage and
additive emission above one in the floating-point target.

RGBA16F is appropriate for bounded HDR, but its maximum finite value is 65,504.
The original point-source model produced a supported larger value: at clear 06:30, an open ice lead with
roughness .045 and F0 .02037 reflects the sun toward an orbit camera tilted
78.39 degrees (within its 80-degree limit). The GGX response times the adapted
sun gives approximately (180380, 137941, 73770), before exposure. These are
reflection values, not invalid input or artificially increased emission. The
sun/moon now have a .266-degree angular radius. A normalized anisotropic GGX
lobe approximates convolution with that source; a preintegrated correction
retains the material's directional energy budget. This is an approximation to
a circular source, not exact disk quadrature. Independent numerical checks
measure its peak and off-peak errors.

Scene radiance and heat extraction/blur retain RGBA16F. Scene writers store RGB
in fixed units of one thirty-second of canonical radiance, extending finite storage
to 2,096,128 canonical units. `GpuSceneRadiance` owns the power-of-two constant;
`scene-radiance.glsl` supplies the matching shader operations. Material, BRDF,
environment and palette calculations remain canonical linear values. There is
no intermediate gamma encoding or tone mapping. RGB blending, the fire/smoke
upsample and heat blur operate directly on those same stored units; alpha is
never scaled. The display composite restores each scene/fog/weather/heat input
once, then applies the common exposure and display boundary. Heat extraction
restores only the radiance used for its threshold and keeps its output scaled.
Previews follow the same contract; tactical overlays retain display units.
Below canonical radiance .001953125, half-float storage is subnormal and its
step is about 1.91e-6. Relative precision is therefore not unchanged at the
darkest values. OpenGL permits conversion to either adjacent representable value
([OpenGL 4.6, section 17.3.8](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf)).
Adaptation is capped at `112^.6` (about 16.9641); together with +2 EV its maximum
gain is about 67.8564. This bounded readability policy is not a calibrated camera
or measured starlight model. A full subnormal step then changes the neutral sRGB
output by at most .4265 of an 8-bit display level. The current Iris Xe probe
preserves exact positive subnormals and selects the lower adjacent half for
intermediate inputs. Both Iris Xe and NVIDIA regressions check all 26 inputs from
zero through .004 at noon and maximum-gain moonless vacuum, including +2 EV:
storage selects an adjacent half and display output remains within one byte of
the independent input-radiance reference. Neither check requires nearest rounding.

The configured-light sweep includes every atmosphere/taint, five-minute time
steps, moon modes and camera pitches, together with a dense view/roughness grid.
For those sampled states, incident direction and every Fresnel F0 are bounded
analytically, including the normalization clamp/floor transitions. Independently
rounding each lookup coefficient toward the more conservative adjacent half
gives 49,098.43 stored units with the current canonical sources, below 65,504.
This bounds the specified isolated directional contribution over sampled states,
not continuous time, every material input or arbitrary overlapping emitters.
The native bright-light regression also exercises 1.5 million
canonical units through blending and heat extraction without clipping or
altering the source scene. These checks bound the supported renderer inputs;
they are not permission for arbitrary unbounded user emission values.

R11G11B10F lacks the alpha required by this compositor. Fog, weather, terrain
fire/smoke and the standalone environment atlas also use RGBA16F. The atlas
stores canonical lighting radiance and BRDF coefficients, not scaled scene
radiance. Material arrays retain compact sRGB or raw numerical storage.
See [Unity's HDR precision tradeoff](https://docs.unity3d.com/Packages/com.unity.render-pipelines.universal@14.0/api/UnityEngine.Rendering.Universal.HDRColorBufferPrecision.html).

The scene uses eight bytes per pixel, and the two quarter-size
heat targets use the same format. Each color or numerical RGBA material-array
texel uses four bytes; scalar roughness uses one. The standalone environment
atlas uses eight bytes per texel and has no mip chain; its roughness levels are
explicit tiles. Fog and terrain fire/smoke each use eight bytes per reduced-resolution
pixel, four more than their former RGBA8 targets. Active weather adds one eight-byte-per-pixel color layer, with no extra
depth allocation; isolated previews own a scene and display target. No performance gain or
cross-platform result is implied by the format choice.

The environment is a generated or imported distant-light approximation, not nearby-object
reflection probes or global illumination. GGX uses single scattering (rough
metals can lose energy); split-sum reflection assumes N=V=R during prefiltering.
Diffuse environment coupling is exact for a constant environment and approximates
the directional matte kernel under a varying sky. Local lava/fungus light remains
a diffuse illumination approximation. Terrain roughness is calibrated by material
family but remains an artistic estimate rather than measured scan data.

The fog pass reconstructs world positions from that depth and the camera.
It integrates the height-dependent layer at reduced resolution, then
depth-aware upsampling avoids leaking foreground fog into unrelated surfaces.

Fog and blowing sand share the ground-layer height control. Fog measures it
from the board's lowest terrain level. Sand uses the lowest nonnegative level
when at least 75% of the border hexes are level 0 or higher; otherwise it uses
the lowest level. Each border hex counts once, including corners. Ultra-sublevels
never supply the sand baseline. The selected baseline is also the bottom of the
sand volume: sublevels beneath it are sheltered. A view from above can still see
the storm over a depression's opening, but no sand is integrated below the baseline.
Plinth rejection remains relative to the actual terrain minimum.
Cloud altitude is separate. Spatial density/height
variation forms banks, and an integrated wind/calm-drift offset moves them
without a phase jump when wind changes. Ground fog's opacity is capped.

The board's display plinth stays clear of fog and sand. `groundBaseSide` in
`ground-layer.glsl` treats a surface as plinth when its derivative normal is
steep and it lies outside the hex footprint, or deeper below the lowest level
than any sculpted hollow (`BoardRelief.headroom`). Grass blades and silhouette
edges also give steep derivative normals. With a smaller allowance, blades in
hollows below the lowest level lost their fog and flickered as dark specks in
wind (`GpuGrassFogSmokeTest`).

Sun shafts sample the cloud field and the sun's shadow map along the light
direction: air reads lit unless the map holds a surface more than one world unit
nearer the sun (`GEOMETRY_SHADOW_BIAS`). The map (`GpuShadowLight`, a 16-bit
depth texture) measures height across the casters' slab, 0 a world unit above the
highest caster and 1 a world unit below the lowest; depth clamping keeps casters
outside the slab. A light lower than the world-fixed sun ever is (only Fixed
sun/moon) keeps depth along the light instead.
Shafts reuse the scene depth rather than drawing the board into a second
camera-depth pass. The composite combines scene color, fog, shafts,
grading, exposure and glare, then restores scene depth for later draws.
Weather particles and tactical/UI passes must retain the intended depth/order.

**Shadows** (Tuning → Display, Normal until switched, saved). The sun's one shadow map is fitted to the view and
its texels follow the rendered pixels: 1.2 a pixel along the scene's longer side, from 2304 (1080p) to 4608 (4K);
High doubles that, up to 8192. Steady frames cost the same at every size (the map is cached; lookups ~3 ms on an
Iris Xe at 1080p); only a redraw (a zoom or refit) grows with it: on a wooded 76x113 board an Iris Xe takes 28 ms at
2304, 61 ms at 4608 and 82 ms at 8192. Prints and previews keep 2304.

**Vignette** (Tuning → Display, off until switched on, saved) darkens the composited view toward its corners, as a
camera lens does: the display colour times 1 - .045 times the squared distance from the centre (corners 9%, edge
middles 4.5%). Prints never take it.

**Depth cues** (Tuning → Display, off until switched on, saved) separate nearer terrain from farther terrain in
clear air, in either projection; the Tactical View keeps clear air. `GpuDepthCues` measures the focus, the mean camera
distance of the surfaces in the middle two fifths of the view less the orbit pivot's, in a one-texel pass over the
scene depth, eased over 0.3 s. Before fog, the composite hazes what lies behind the focus towards the fog's light in
the visible horizon's hue (`BoardAtmosphere.depthHaze`; neutral without air), approaching `GpuDepthCues.STRENGTH`
(.08) over the depth the view spans (its height times the tangent of the tilt, and at least `GpuDepthCues.MIN_VIEW_DEPTH` its height, so from
straight above, where the only depth is the relief's, a drop of a few levels hazes little); the focus and all in front
of it keep their colour. Clear air veils rock a few hundred metres away by only a few percent: this is a board-scale
exaggeration, kept low because the sunlit haze is far brighter than dark rock. Each pixel depends only on its own
depth, so nothing slides across the terrain as the camera moves; panning across a cliff edge refocuses the haze over
that 0.3 s.

The sky occlusion by the board's relief (`GpuSkyVisibility`, `sky-visibility.glsl`) is lighting, on in the board at
`GpuSkyVisibility.STRENGTH` (0 to 1: 1 the full darkening, .5 half of it; 0 leaves it out of every program and searches
nothing), independent of that option. It counts the board's tops: the hexes' surfaces, raised to the tops of the
buildings, fuel tanks, industrial plants and military walls standing on them where their drawn bodies stand
(`BoardObstacles.structures`, the footprints ground cover grows to; a civilian wall's open fencing does not count). Trees
do not count.

- The volume: on a worker, when the tops change, they are sampled on a 4 m grid (at most 256 cells a side; a cell takes
  a structure's top where its body comes within half a cell of the centre, so a thin wall still counts) and the worker
  searches 16 horizons to 300 m for each cell of a volume up to two levels above the highest top (half-level
  layers, at most 64) and stores, for each quarter of the compass, a level receiver's share of that quarter's sky, the
  mean cos² of the horizon's elevation; the render thread uploads it as an RGBA8 3D texture. A cell inside the ground
  or a structure searches from the lowest floor around it (a hex's surface, or a structure's top where its body covers
  the cell's centre, without the half cell of reach), so behind a wall's foot it holds the foot's shade and filtering
  there does not pull in the open sky on top, under a roof away from its edges it holds the roof's values, and an
  alley that reach fills holds its floor's shade.
- The sky light (`skyLight`): a surface reads the volume one cell out along its normal, so a wall reads the air in
  front of it. Level ground takes the four quarters' shares. A face weighs the quarters it faces, and converts each
  share to the horizon h it stands for (cos h = √share) and to a vertical face's share, 1 − (2h + sin 2h) / π: a wall
  takes most of its sky near the horizon, so the same horizon hides far more of it (h = 45°: .18 against .5). Slopes
  blend the two by the normal's height.
- The hidden sky is not black (`hiddenSky`): seen from shade, the faces that hide it mostly turn away from the sun,
  so it takes the light of ground lit by the sky alone (albedo × sky). Fully sunlit ground, about as bright as the sky,
  erased the occlusion in review captures. Of a face's light the sky is the share (1 + nz) / 2; the ground below keeps
  its own bounce.
- Reflections (`skyReflection`: `shadeSurface`, `surfaceEnvironment`, ice; water's own reflection) read the
  visibility along the reflection, and the hidden share takes the same light, so a wet street or a pool in a canyon
  mirrors the walls' shade rather than black or the open sky.

Sunlit ground changes by a few percent; shade deep in canyons, in streets and at the feet of walls, lit by the sky
alone, darkens most. The volume rides on the environment's `GpuEnvironmentLighting`, as the clouds, ambient
occlusion, local lights, biomes and specular anti-aliasing do. Its lit programs compile it while `STRENGTH` is above
0, with a neutral volume until the first upload; its strength is a uniform, so a new volume changes no program.
Sculpted ground with both ice and volcanic crust draws without it (the sampler budget); other volcanic crust keeps it
and draws without the screen-space ambient occlusion instead.

The strength was chosen from `GpuDepthCuesSmokeTest` captures of Crystalline Canyon on NVIDIA; the passes are not
timed on Iris Xe. Board prints (`GpuBoardPrint`) never draw the haze: only the battle view sets the cues. They do
draw the sky occlusion, their first tile waiting for its volume; the editor's terrain samples do not (their cut-out
hexes would take the shade of neighbours they do not draw). The haze's
program links during loading when the option is on, otherwise at its first frame.

Relevant helpers are `camera-depth.glsl`, `ground-layer.glsl`,
`scattering-phase.glsl`, `sun-visibility.glsl`, `weather-sand.glsl`,
`atmosphere-fov.glsl`, `atmosphere-grade.glsl` and `atmosphere-glare.glsl`.
`GpuAtmosphere` assembles them into `atmosphere-fog.frag` and
`atmosphere-composite.frag`.

## Weather and lifetime

`GpuWeatherParticles` selects `weather-rain.glsl`, `weather-snow.glsl` or
`weather-hail.glsl` inside the shared particle vertex/fragment programs.
Its bounded pools and projected coverage belong to the view; they are not
per-hex weather simulations. The atmosphere handles sand extinction and the
composite handles lightning brightness.

Atmosphere targets resize with the physical framebuffer and are disposed with
their owner. Cloud motion, exposure and weather intensity update their existing
resources; they should not rebuild terrain geometry. Geometry shadow invalidation
is separate from an animated cloud atlas.
