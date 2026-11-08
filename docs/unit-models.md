# Unit models

[Docs index](README.md) · [GPU architecture](gpu-board.md).
Runtime classes are in `client/ui/clientGUI/boardview/gpu`.

Units are assembled from a body and the captured equipment loadout. A stock Atlas
and a custom refit can share the same body while drawing different fitted weapons.
The sections below identify the selection, import, assembly and animation owners.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `MekTileset`, `UnitModelSelection` | Choose model identity and capture the unit's visible appearance/loadout. |
| `UnitModelDescriptor`, `RigidGlb`, `MeshLod` | Decode the descriptor, rigid geometry and compatible mesh levels. |
| `GpuUnitModels`, `GpuUnitModel`, `ModelTextures` | Cache shared model/texture resources. |
| `UnitEquipmentAssembly`, `UnitEquipmentModels` | Attach captured equipment through the descriptor's hardpoints/recipes. |
| `GpuUnitInstance`, `UnitRig`, `UnitAnimator` | Hold per-unit instances and pose named rigid parts. |
| `UnitPlayback`, `UnitMotion` | Sequence resolved events and evaluate the displayed movement route. |
| `MekVisual`, `FamilyVisual`, `InfantryVisual`, `BattleArmorVisual`, `SquadronVisual` | Apply body-family, troop-formation and squadron presentation. |
| `UnitGroundContact`, `UnitLandingSupports`, `UnitPicking` | Ground and intersect the actual posed models. |
| `UnitDamageDisplay`, `UnitCamouflage`, `GpuUnitCamouflage` | Apply an instance's visible damage and paint without changing shared geometry. |
| `FormationLod` | Choose projected detail with hysteresis while retaining the same animation state. |

## Model selection and assembly

`MekTileset` resolves model identity alongside sprite identity. The optional fourth
field in `mm-data/data/images/units/mekset.txt` names a recipe relative to
`data/models`:

```text
chassis "Atlas" "meks/Atlas.png" "units/modular/meks/atlas.json"
exact "default_medium" "defaults/default_medium.png" "units/modular/meks/fallback-biped-{weightClass}.json"
```

Exact model entries take precedence over chassis models; sprite-only overrides
can still inherit the chassis model. Generic Mek recipes expand `{weightClass}`
to light, medium, heavy, assault or superheavy. `GpuUnitModels.get` tries the
selected recipe, then the type-specific fallback; if both fail, the flat sprite
remains available.

`UnitModelSelection` captures the selected model and the unit's visible appearance
data: loadout, surviving personnel, facing and damage. A sensor-only contact has no
model identity. `GpuBoardSource` performs visibility filtering before this presentation
data reaches the renderer.

[GpuUnitModels](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuUnitModels.java)
loads and caches the shared assets. `UnitModelDescriptor` reads the JSON contract;
`UnitEquipmentAssembly` and `UnitEquipmentModels` assemble equipment using the
captured loadout. `MekVisual` filters the body for its actual anatomy and attaches
alternate detail meshes to the same animated joints. Equipment uses the recipe
and `equipment.json`, with weapon-family fallbacks for unmapped weapons.
`FamilyVisual` selects the appropriate vehicle/aircraft form and removes absent
turrets. Invalid descriptors or parts are logged and cached as failed for that
asset library; a loadout-specific failure does not invalidate the shared recipe.
`GpuUnitModels.ENABLED` controls authored models; disabling them retains the flat
sprite path in both camera views. Raised sensor symbols use `GpuCutout`
independently and do not reveal a concealed unit's model.

## Asset format and validation

Unit recipes and part descriptors require `"schema": 2`. Older pre-built
descriptor formats and G3DJ unit meshes are rejected.

- `GpuUnitModels.load` reads recipes whose `kind` is `mek`, `family`,
  `squadron` or `formation`, delegating to the corresponding visual assembler.
- `UnitModelDescriptor` validates parts whose `kind` is `body`, `troop` or
  `equipment`; their `mesh` must name a GLB. Every detail level needs the
  shared rig. LOD0 also owns the named locations, hardpoints, emitters and
  landing-support nodes. Mesh data must contain position, normal, color and UVs;
  accepted material roles are `paint`, `detail` and `bark`.

`RigidGlb` decodes binary glTF 2.0 on the CPU through JglTF, before allocating
GPU resources. It converts glTF's Y-up coordinates to the board's Z-up convention
and preserves linear material factors and vertex colors. Preserve node and part names: descriptors,
damage and animation use them to find rigid geometry.

The importer rejects skins, animation clips, morph targets, external geometry
buffers, required extensions and blended materials other than board glass (a board material whose `.tres`
blends). Opaque and alpha-tested materials are supported; double-sided faces get reversed geometry and normals.
Textures may be embedded PNG/JPEG data or local relative images inside the
permitted model directory. `ModelTextures` shares them by image, sampler and color/data role;
color images decode from sRGB during sampling, while numerical maps remain linear data.
`paint` receives runtime camouflage while `detail` retains authored artwork.

Imported models in the main 3D board use authored glTF roughness and metallic
factors, plus an optional metallic-roughness map (linear G/B channels multiplied
by the factors). Defaults follow glTF: both factors are one when absent. The
generic kit writer defaults to roughness one / metalness zero; the unit builder
authors Atlas and Warhammer paint at roughness 0.68, with mixed detail still at one.
Normal maps and occlusion maps (linear R) use the original mesh UVs, including on camouflaged
units; only `TEXCOORD_0`, normal scale one and occlusion strength one are accepted.
Normals use a derivative tangent frame; degenerate UVs retain the geometric
normal. Occlusion shades indirect light, not direct sunlight or emission.

`GpuModelMaterial` carries these values through instance copies and static prop
batching. `model-surface.glsl` shares correlated Smith GGX reflection and reciprocal
diffuse energy coupling with terrain and foliage. Metal reflections take their
colour from albedo; dielectric highlights remain neutral. Orthographic top and
isometric cameras use parallel view rays. Perceptual roughness is bounded to
0.045–1.0. Environment reflection uses a prefiltered analytic sky/ground gradient
and the matching integrated BRDF, shared with the visible sky and diffuse light.
It has no nearby-object reflections or global illumination and uses single-scattering
GGX. See the [light and color contract](gpu-atmosphere.md#light-and-color-contract)
for its approximations; this remains a deliberately limited glTF material implementation.

The sibling `mm-data/tools/glb_geometry.py` writer/reader preserves `roughness`,
`metallic` and a `METALLIC_ROUGHNESS` texture role in its authoring dictionaries.
These controls do not retune existing assets automatically. Keep paint
nonmetallic even when the underlying object is metal, and author exposed metal
as a separate material where its geometry already permits it.

`mm-data/tools/model_surfaces.py` owns the generated kit's starting values.
The first deployed calibration covers the Atlas and Warhammer bodies only;
their paint stays nonmetallic and `detail` stays matte because it combines
metal, rubber, glass and other surfaces. The shared selection also scopes complete
kit rebuilds: other bodies, equipment, troops and vehicles retain their existing
defaults. All LODs of a reviewed body share its surface settings.

`RigidGlb.loadLods` requires top-level identity groups named after the component:
`atlas.glb` contains `atlas-lod0`, optionally `atlas-lod1` and `atlas-lod2`.
Each group contains child rig/mesh nodes rather than its own mesh. LOD0 is required;
malformed declared levels produce an asset error. `MeshLod.load` resolves missing
levels toward the preceding available mesh once at load time, sharing its buffers.
A missing LOD1 does not prevent an authored LOD2 from being used. Separate-level
references in older custom schema-2 recipes remain readable. The scatter kit's
per-shape groups are handled separately by `BoardShape.loadKit`.

The full format and authoring instructions are in
[mm-data/data/models/units/README.md](../../mm-data/data/models/units/README.md).
The `:megamek:exportEquipmentModelCatalog` task exports the current equipment data;
[build_modular_unit_models.py](../../mm-data/tools/build_modular_unit_models.py)
builds the assets with Python from the mm-data root. Gameplay loads the generated
assets directly. Data staging copies them from the sibling `mm-data` checkout.

## Triangle budgets and hard limits

`UnitModelDescriptor.UNIT_TRIANGLE_BUDGETS` defines the assembled-unit art budgets:
5,000 triangles at LOD0, 2,000 at LOD1 and 500 at LOD2, including body and fitted
equipment. After assembly, `UnitEquipmentAssembly.warnOverUnitBudget` checks
the body's distinct authored levels and logs `[UnitBudget]` with the body and
equipment counts. An over-budget unit is still drawn in full. This checks assembled
bodies and equipment, rather than an aggregate formation or squadron budget.

The separate `UnitModelDescriptor.MAX_TRIANGLES` sanity ceiling rejects an
asset level above one million triangles. `RigidGlb` also limits each imported
level's combined mesh to 65,535 vertices for its 16-bit indices. These checks
catch invalid exports; they do not trim a loadout to its art budget.

## Scale and formation layout

Single-hex models share the Atlas authoring conversion:
`2 * LEVEL / 54.858`, uniformly on all axes. Bodies retain authored relative
sizes rather than each being normalized to its own height. Board level height,
unit scale and `UnitFamilyScale` size multipliers resize all axes; only the
explicit height multipliers stretch Z. Multi-hex units use occupied-footprint
fitting and their own uniform scale.

`UnitFamilyScale` is the source of family defaults and Mek weight-class
multipliers. Infantry and Battle Armor are authored at canonical scale and
enlarged for readability, with vehicles and formation spacing scaled alongside
them. Changing a body asset's height to compensate would affect all instances
using that asset.

`InfantryFootprint.layout` fits the authored formation onto the finished rim,
including slope room and shifted river corners. It contracts/shifts the layout
while retaining figure size and relative spacing. Troop clearance accounts for
all watch headings; vehicles retain their parked heading. A changed support
surface updates standing and arrival layouts.

## Animation and damage

`UnitPlayback` is the shared render timeline for movement, attacks and attachment
changes. It accepts resolved game events and controls when their visual consequences
appear. This lets a movement or salvo play coherently while the client continues to
receive newer game state.

`UnitMotion` evaluates movement along the captured route. `UnitAnimator` calculates
per-unit rigid poses from rest transforms and the playback clock. `UnitRig` supplies
the joint structure, while `MekVisual`, `InfantryVisual` and `BattleArmorVisual`
handle the corresponding body/formation presentation. Facing, torso twist, prone poses
and equipment effects are derived from the captured state and animation sequence.

A descriptor's `upperBodyNode` lets `UpperBodyTurn` twist the torso while legs
retain the unit's facing; without it the body turns as one piece. Walking gait
follows distance traveled, including acceleration/braking. Hull-down, prone,
forced falls and jump posture use the shared timeline, including pause/skip. A
falling Mek loses its balance: its knees give, it flails, then topples over its
planted feet and braces before it lands (`UnitAnimator.fallenPose`), lying inside
its hex. A fallen body lies on the side it fell on, turned as a meeple is
(`UnitMotion.Posture.turn`): under Total Warfare its head lies along its facing,
under Core its body keeps the facing. Getting up, a body whose head lies along
its facing rolls onto its front about its length, braces and stands; one that
fell the other way gets up the way it fell.
Troops share articulated base meshes, with stable member identities for idle
variation and casualties. Each member rises and settles around its own movement.

Lost-location node names match game location abbreviations. Damage overlays
prioritize destroyed over structure over armor and are applied after baked vertex
color; intact cockpit glazing keeps its authored appearance. Patterns are seeded
by unit and part so they remain attached through movement and instance rebuilds.
Tuning's damage preview changes presentation only and must restore live state
when disabled.

`UnitDamageDisplay` applies lost locations and damaged artwork to an individual
instance. Shared model buffers remain reusable by other units. Camouflage follows the
same ownership distinction through `UnitCamouflage` and `GpuUnitCamouflage`.

## Ground contact, formations and attachments

After board placement, `UnitGroundContact` and `UnitLandingSupports` adjust the model
against the shared terrain surfaces. `UnitPicking` intersects the current rigid parts
and their pose. Both cameras draw and pick the same instances.

Infantry and Battle Armor are represented by formations derived from surviving
personnel. Movement type can select troop/vehicle arrangements; these figures remain
a visual representation of one game entity. Infantry descriptors can provide
`movementFormations` keyed by `EntityMovementMode`; motorized/mechanized forms
use their corresponding vehicles, jump forms use articulated jump-equipped troops,
and unsupported modes retain the foot formation. Surviving personnel determine
the occupied slots, including an empty formation at zero survivors.

For exterior passengers and hostile swarmers, `GpuBoardSource` captures the carrier
relationship into `BoardScene`. `UnitAttachments` places figures on the carrier's
posed parts, and `UnitAttachmentMotion` supplies boarding/release motion.
`UnitPlayback` orders those transitions with carrier movement and combat so a packet
arrival does not detach riders at the wrong point in the displayed route.
[Battle Armor attachments](battle-armor-attachments.md) describes grip assignment,
visibility and the packet-order constraints for boarding, unloading and swarming.

## Detail and resource ownership

`FormationLod` selects a unit's mesh level from projected size with hysteresis;
formations measure an individual figure. Detail changes reuse the same animation
state and shared buffers. Selected units and attack-playback participants retain
the detailed representation. Meks and formations switch among LOD0, LOD1 and LOD2.
Current nominal LOD1/LOD2 boundaries are 96/32 pixels for Meks and 48/16 for
individual formation members, with 10% hysteresis. Vehicles and other bodies
assembled through `FamilyVisual` retain their LOD0 path.
Missing optional meshes fall back independently; a detail switch must retain
lost parts and the current pose.

Small attached equipment has its own projected-diameter culling in
`GpuUnitInstance`, shared by color, shadow and outline passes. Selection and
attack playback retain full equipment; culled drawing does not discard emitter
transforms or damage state.

`GpuUnitInstance` holds per-unit pose/material state while borrowing model geometry.
`ModelTextures` owns shared texture caching, and the asset library owns disposal.
Changing one unit's pose or damage must therefore operate on its instance, while
reloading or closing the renderer is responsible for shared resource lifetime.
