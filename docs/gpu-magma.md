# Magma crust and lava

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `BoardLiquid` | Classify crust versus molten lava from captured terrain; preserve the game's level/depth. |
| `BoardSurface / BoardRelief` | Construct beds, banks and shared contacts. |
| `GpuMagmaShader` | Select solid or flowing terrain material programs and their textures/uniforms. |
| `GpuOcean` | Generate the separate lava wave field using the shared wave implementation. |
| `liquid-flow.glsl` | Shared water/lava advection phases, weights and material coordinates. |
| `magma-solid.glsl / magma-flow.glsl / magma-lighting.glsl` | Own crust relief, molten advection and heat/lighting; terrain boundaries reuse these evaluations. |
| `GpuAtmosphere / GpuHeatGlow` | Preserve molten HDR energy, select visible hot highlights and composite a reduced-resolution camera halo. |
| `GpuLavaLighting / lava-lighting.glsl` | The binned local lights for terrain, units, props and water: volcanic tiles, burning hexes, luminous fungi, and each frame's fireballs, muzzle blasts, laser and PPC beams and flamer streams (`GpuAttackEffects.lights`, the 64 brightest). Points, and segments for beams and streams. Diffuse light, plus a GGX highlight widened by the source's size on glossy pixels. |

MAGMA level 1 is solid basalt crust with incandescent fissures. Level 2 is opaque
flowing lava, including falling sheets and exposed board-edge cuts. The captured
`BoardLiquid` distinguishes the states; `present()` is false for crust. Neither
material supplies movement rules, water depth, support heights or new picking
geometry. Existing terrain edits rebuild the affected surfaces. Crust, lava banks
and cliffs use the shared sculpted terrain. Connected lava uses the same graded
surface as water for one/two-level descents, and falling sheets from three levels.
Volcanic banks and crust join ordinary ground through the shared
[terrain contact field](gpu-terrain-contacts.md). Marsh plants stop at volcanic ground.

`GpuMagmaShader` selects `terrain-magma-solid.frag` for crust/banks and
`terrain-magma-flow.frag` for pools/falls in the existing terrain pass. Edit solid
relief and fissures in `magma-solid.glsl`, and advection, convection and molten heat
in `magma-flow.glsl`. Both use `terrain-magma.glsl` for sampling/projection and
`magma-lighting.glsl` for reflected light and emission. Terrain-boundary palettes
use the same solid evaluation. World-space coordinates continue across adjoining
hexes. Each active triplanar axis evaluates one coherent material projection.
Flowing lava uses two modest, successive world-space shears to vary the source's
alignment without additional texture fetches or folded UVs. Their restrained
amplitudes keep downstream rafts readable; cooled crust retains
its gentle warp. Colour, heat, height and normals use the same coordinates. Normal gradients,
view directions and flow directions include the warp's Jacobian; mip derivatives are
taken after the warp. Smooth triplanar weights cover tops, slopes, curved fall lips
and vertical cuts without switching projection abruptly.

A flat flowing surface uses up to eight material-map reads at close range: four in each
of the two advection phases. Material LOD reduces this to six after fine normals
disappear, then four when the surface map is no longer sampled; colour
and heat remain. These counts exclude bank/current fields, the wave field, lighting
and shadows; surfaces using multiple projection axes cost more. Solid crust retains
its relief trace only at close range. See [material LOD](gpu-terrain-materials.md#material-detail-at-distance).
These counts describe material sampling work, not an FPS multiplier.

Lava reuses `GpuOcean`'s actual 128-square GPU inverse FFT with a separate, slow,
isotropic spectrum that suppresses short waves. `ocean-lava-finish.frag` retains
displacement; `ocean-water-finish.frag` computes water's compression and foam. Their
centering helper, spectrum and FFT stages stay shared. FFT horizontal displacement deforms
the cooling skin and hot channels together; FFT slopes light the folds. It is
independent of wind and only runs while loaded chunks contain lava. The same
`BoardFlow` currents and `GpuWaterShader.Field` sampler supply downhill flow, bank
distance and approach/junction currents. Lava and water have separate fields, so
currents cannot cross between them. Rebuilt chunks rebind reused materials to their
new field and dispose the old texture. Water and lava use `liquid-flow.glsl` for
their two staggered material advections, hiding each reset at zero weight; solid
crust remains stationary. Lava advances its source window at these hidden resets
so it does not repeat the same short loop. Each refresh moves up to about 2.3 metres
through the 24-metre source repeat. This refresh is separate from physical current
velocity. Source offsets stay bounded and do not accumulate distortion around
bends. Broad spatial staggering separates the phase handoffs across the river,
without another texture read.
Both phases use the local world-space current, transformed through the same warp
and projection as every material map. There is no fixed texture-axis scroll or
independent heat-map flow that could override a bend or run uphill after rotation.
On steep sheets, the downward gravity tangent replaces the horizontal current;
this is unchanged when viewing the back of a sheet. Ordinary lava reaches move at
approximately 2.4 metres per second at 1 g, with a four-second advection cycle.
The existing gravity-scaled clock controls this transport. Closed pools use the
existing FFT height gradient, rotated by 90 degrees, to circulate the complete
material locally. This is capped at 1.2 metres per second and fades out at banks,
on falls and where an established current takes over. It shares the same material
advection and adds no simulation pass or texture fetch. The small FFT displacement
continues to deform the skin alongside this transport; it is no longer the only
visible motion in zero-current hexes.
Each molten phase derives a cooling-skin mask from its existing heat sample.
That mask controls basalt reflectance, roughness and emitted RGB together: broad
cooled plates remain dark and rough, while exposed rifts retain the original HDR
peak. A small, height-modulated basalt reflectance floor gives cooled skin a
visible surface without adding an emission floor. The exposed-melt mask rises
between heat-map values .25 and .65; before the shore treatment, cool skin has
roughness at least .96 and fully exposed churned skin at least .92, retaining any
higher authored value. The same roughness reaches direct and environment reflection,
so the molten rifts do not use a separate smooth, water-like specular lobe. These
are artistic material estimates, not measured temperatures or roughness samples.
An Iris Xe component capture aligned with the clear 07:00 sun found that the
earlier .82/.68 floors left direct specular light responsible for about 83% of
cold horizontal lava's red radiance. Sky reflection contributed about 4%, and the
finite-source normalization stayed near one. The .96/.92 preview reduced that
polished appearance while retaining the same Fresnel model, mapped normals and
emitted radiance; it adds no texture reads or shader branches.
The appearance follows the broad plates and incandescent margins documented in
[USGS lava-lake observations](https://www.usgs.gov/media/images/radial-fracture-patterns-halemaumaus-lava-lake-surface-kilauea-volcano-summit).

This complete material, including emitted RGB, is calculated before the two phases
meet. A height-priority overlap selects the upper plate, with a narrow continuous
contact instead of a broad dissolve between two crack patterns. All material
channels use the same contact weight. Phase bias exceeds the full sampled height
range at either endpoint, keeping every source reset completely hidden. When
material LOD omits height samples, the already-filtered heat supplies a cold-is-higher
proxy, with no extra texture read. This is an opaque surface approximation, not
separate rigid plates. It still has a short blend at contacts, and transport gradients
retain the existing pre-advection approximation.

Blending each phase's emitted RGB avoids applying a nonlinear heat ramp to averaged
temperature, and the blended temperature does not make cooled skin glossy again.
Restrained FFT displacement and slopes deform the same material channels together.
The existing shore-distance field supplies a wider red thermal margin around the
hot interior, without adding geometry or texture reads.
This is a viscous surface approximation, not a volume-conserving or thermal fluid
solver. It introduces no separate board scene, animation thread or game state.

Basalt uses the board's linear lighting, shadows, normal-map toggle and GGX
roughness response. Heat and the local glow on fissure walls are added after
ambient, sunlight and shadowing. The molten heat ramp rises from red exposed margins
through orange melt to bright yellow cores, restoring the original .68-.94 transition
to the hottest colour. Exposed hot skin remains rough but can be smoother than its
cooling rafts. Solid basalt retains its dimmer red fissures.

All main 3D boards capture the scene in RGBA16F, preserving radiance above diffuse
white through linear material outputs and scene blending. Only boards containing
molten tiles run heat passes. The atmosphere composite separates
strong warm HDR highlights from ordinary surface lighting, so their energy
does not inherit the night surface tint/desaturation. Fog, sand and exposure still
affect their appearance before the common display shoulder and tactical FoV.

`GpuHeatGlow` extracts visible hot radiance at quarter resolution and blurs it in
two separable five-fetch passes using two reusable targets. It reads the completed
scene, so foreground objects occlude the source without another terrain draw.
Fog attenuates the source before blur; sand also attenuates the halo in the final
composite. With tactical FoV active, each source texel is attenuated by the shared
visibility opacity before downsampling, preventing hidden heat from bleeding into
a visible neighbour. The final composite retains its normal FoV treatment.

The halo models camera glare. Its warm-radiance selector is an approximation rather than a material
mask: sufficiently bright warm fire may also glow on a molten board, while ordinary
white reflections are excluded. Ordinary reflected highlights also retain values
above one until shared exposure and tone mapping, whether or not lava is present.
Scene alpha blending and heat extraction operate on linear radiance; see the
[scene color contract](gpu-atmosphere.md#scene-depth-fog-and-composition).

Local illumination is a separate diffuse contribution in the existing lit shaders.
`GpuLavaLighting` derives disk-like emitters from the installed terrain snapshot and
keeps those the captured client LOS shows. Molten tiles emit strongly; cooling crust
retains 3.5% source power. Every visible emitter, lava and fungal alike, is binned on
a board-plane grid of one hex width per cell, into each cell its reach overlaps. The
falloff reaches exactly zero at twice the source size, so the binning drops only
zero contributions. One RGBA32F texture holds each cell's first and end entry texel,
then the entries: two texels for a point, four for a segment, which flags itself by
a negative power and adds its far end and tube radius. `lavaLighting` reads its
pixel's cell with `texelFetch` and evaluates only those emitters; a point entry
takes its two fetches and its own maths, with only the sign test added. A segment (a
laser or PPC beam, or a flamer stream from its nozzle to its leading packet) is binned
into every cell its capsule touches. It lights a pixel from its nearest point, with
its power divided by 1 + 0.7 × its length in sizes: in all, a flamer stream lights
about as much as one point of that power, a thin laser beam about two thirds. It
glints from its point nearest the mirrored ray (Karis 2013, tube lights), widened
across by its tube and normalized along its length, so calm water mirrors a beam all
along it. A beam also has a point light where it strikes, both at 0.35 hex: 0.45 along
a medium laser and 0.5 at its strike (`GpuAttackEffects.BEAM_LIGHT`, `STRIKE_LIGHT`),
12 and 1.5 for a standard PPC's bolt (`PPC_BEAM_LIGHT`, `PPC_STRIKE_LIGHT`), a bright
band along it and a broad glow on its target. A charging PPC lights the ground round
it with 8 (`PPC_CHARGE_LIGHT`). A laser's or PPC's lights scale linearly with its
positive damage at the shot's range in hexes against these, 5 and 10, held between
0.4 and 3 times (`GpuAttackEffects.beamScale`); weapons without positive damage keep
the reference power. PPC arcs and TAG lines do not light. The list grows with the
emitters, within its packed texture capacity, and is rebuilt and uploaded only when
the visible emitters change. The
same environment attribute reaches terrain, units, buildings, props and water without
extra render passes or game state; water walks the list once for its irradiance and
its reflection. Changing terrain or board scale refreshes positions; removing all
visible emitters empties the list. This is a short-range lighting approximation, with no obstacle
shadow rays or global illumination; light can leak through nearby geometry. The
camera halo and local lighting both respect captured visibility, through their own
existing render paths.

Relief uses normals and bounded parallax, not geometric displacement, so silhouettes
and picking remain the existing board geometry. Crust traces two to twelve depth
layers according to the projected relief ray's screen footprint, with a refined
intersection to preserve its occluding slab edges. The full texture-coordinate
Jacobian accounts for anisotropic views; a conservative bound skips its inversion
when the twelve-layer budget is already required. Below half a pixel, crust uses
a single height offset; exactly zero parallax skips relief work. All aligned
colour, heat and normal samples remain. Flowing lava uses aligned, advected
maps without a height offset: its thin skin retains mapped normals and FFT folds,
while eliminating the shifted surface-map reread in each flow phase.
The crust bake concentrates relief in the thick plates and
gives fine rock grain only shallow relief. Fissure-wall glow stays close to the gaps.
The shader audit records measured costs and validation separately from
these implementation details: [magma material and timing](gpu-shader-audit.md#magma-material-and-timing).

The final native review verifies finite scene/material output, quiet rough
cooling skin alongside HDR rifts, gravity pause/recovery, motion continuity,
day/night rendering and live material edits. It also exposed a zero normal at
collapsed waterfall-lip endpoints: `GpuWaterfall` now uses the limiting outward
path tangent there, preserving mouth slope and mesh positions. The shared repair
applies to both water and molten falls. All 23 waterfall geometry tests pass.
The boundary palette shares its warped projection and finite derivatives before
coverage branches. It skips zero-weight volcanic overlays only after shore
coverage is resolved, and retains any volcanic substrate that can acquire sand
exposure. One surface sample supplies both crust and cooled-bank palette entries;
each retains its own emission strength. Ordinary palette evaluation no longer
contains a redundant volcanic relief path. Duplicate family slots also reuse their
contact noise. Shared bank lighting reuses its dielectric response and local
irradiance instead of evaluating them twice. Measured costs and remaining
limitations are recorded in the shader audit.

## Assets

The Material Maker projects `mm-data/tools/materials/terrain/lava/lava-crust` and `lava-flow`
export each material as a Godot 4 `ShaderMaterial` of its preview shader, `lava-flow.gdshader` or
`lava-crust.gdshader` ([board materials](gpu-board-materials.md#shadermaterials)), with heat kept apart
from the basalt reflectance as its emission. This is artistic relief, not
measured photogrammetry. The `lava-flow` project's molten-interior source contains broad irregular thin cooling plates
separated by connected molten rifts; riverbanks come from the board geometry and
shore field. The 2026-10-12 replacement removes the dense fiery thread patterns.
Its heat map has 36.5% coverage above .2; the molten exposure ramp uses .25–.65
to retain those rifts while leaving most plate faces cold and rough. Map count,
resolution, advection and texture-sample budget are unchanged.

Molten spill lighting is calibrated to the replacement artwork's average emitted
radiance, including its cold plates: approximately `(.75, .11, .0023)` in linear
RGB. The former `(1.8, .24, .025)` made a conspicuous pale pink bank wash in
daylight. The bounded disk/falloff model remains an approximation, not exact area
lighting or dynamic integration of each animated crack. Solid-crust and fungal
light retain their separate calibrations.

Each material (`lava-crust` and `lava-flow`) has aligned, repeating 1024px maps in
`mm-data/data/models/board/textures/terrain/lava/`. The bake's reference scale is 12 metres.
Solid crust retains that repeat; flowing lava uses 24 metres for larger readable
rafts, with normal gradients and motion adjusted to retain their physical scale.
Exported wider than 1024 pixels, both repeats widen in proportion (`u_magmaCoverage`);
the shared world warp softens the regular grid, but recognisable motifs can still
repeat. The maps are artistic materials, not a physical thermal simulation.

| Map | Channels |
| --- | --- |
| `NAME_albedo.png` | sRGB basalt albedo, with heat removed |
| `NAME_normal.png` | Linear tangent normal, OpenGL convention (the GPU layer flips it to V down) |
| `NAME_heightmap.png`, `NAME_orm.png` | Height; R cavity AO, G perceptual roughness. `heightmap_scale` is the relief UV × 100 |
| `lava-flow_emission.png` (`texture_emission`) | Linear emitted RGB, scaled by the material's `emission_energy` (5) |
| `lava-flow_coverage.png` (`texture_coverage`) | Exposed-melt coverage |
| `lava-crust_emission.png`, `lava-crust_glow.png` (`texture_emission`, `texture_glow`) | Grey heat intensity; local fissure glow |

On the GPU these become four layers: colour, normal, a surface map (R height,
G roughness, B cavity AO, A relief UV / 0.1) and heat (molten RGB with coverage in A;
crust intensity in R with glow in A).

Metalness is zero and opacity is one. Height, normals and the shader's relief range
use the same quantized field. The shader samples every material map at the same
advected/parallax coordinates. Mipmaps and supported anisotropic filtering handle
distance. `GpuAssets.fullSizeMaps` uploads each set's colour to `u_fullSizeColor` and its
normal, surface and heat to `u_fullSizeData`, with the other full-size maps, so they take
two texture units; `GpuMagmaShader` tells the program which layers are its set's
(`u_magmaLayers`). `GpuAssets` owns and disposes the arrays. Every map of
both sets is required.
Gradle's existing `stageDataFiles` copies the runtime maps into MegaMek's data folder.
