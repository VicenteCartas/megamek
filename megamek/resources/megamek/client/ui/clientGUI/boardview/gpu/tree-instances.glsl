// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by the colour and depth vertex shaders. Fixed instance locations keep their vertex array compatible.
layout(location = 14) in vec4 a_instance0;
layout(location = 15) in vec4 a_instance1;
#ifdef colourSlotsFlag
// A recolourable model's placement colours (model-colour-slots.glsl).
layout(location = 13) in vec4 a_instance2;
#endif
#ifdef treeDissolveFlag
// When the tree began dissolving between detail levels: positive at the level it takes, negative at the one it leaves,
// 0 at rest (GpuTreeInstances.Stand#detail).
flat out float v_treeDissolve;
#endif
#ifdef impostorFlag
// IMPOSTOR_FRAMES
// impostor-sample.glsl reads these.
invariant out vec3 v_impostorPoint;
invariant flat out vec3 v_impostorFrame;
flat out vec4 v_impostorTree;
#endif

vec3 instanceTurn(vec3 v) {
    return vec3(a_instance1.x * v.x - a_instance1.y * v.y, a_instance1.y * v.x + a_instance1.x * v.y, v.z);
}

// The horizontal, horizontal and vertical scales; a mirror image (a negative a_instance0.w) reverses its model's y axis.
// Its batch also flips the front face, as the reflection reverses the winding (GpuInstancedMesh#mirrored).
vec3 instanceScale() {
    return vec3(abs(a_instance0.w), a_instance0.w, a_instance1.z);
}

// A structure's pieces falling where the board drew them (GpuDestruction): rigid bodies thrown in closed form, the
// collapse MeepleDebris simulated on the CPU. A debris batch (u_piece.w 1) draws one piece of a kit at every falling hex;
// each instance names its hex's slot (a_instance1.w, from 1), whose texels in the local lights' texture hold its fall
// (GpuDestruction#write). Every pass, colour, depth and shadow, poses alike from the same inputs.
// LAVA_LIGHTS
uniform vec4 u_destruct;       // the falls' clock in playback seconds, gravity, a level's height, a hex's reach
uniform ivec2 u_destructSlots; // the first slot's texel, and 1 while anything falls
uniform vec4 u_piece;          // the batch's piece: its node's box centre in the model's frame; w 1 for debris
uniform vec3 u_pieceHalf;      // that box's half size

// The fall's rigid motion, p' = fallShift + fallTurn (p - fallPivot), set once a vertex by fallBegin.
bool falling = false;
mat3 fallTurn = mat3(1.0);
vec3 fallPivot = vec3(0.0);
vec3 fallShift = vec3(0.0);

vec3 instancePlace(vec3 p) {
    return instanceTurn(p * instanceScale()) + a_instance0.xyz;
}

vec3 instancePosition(vec3 p) {
    return falling ? fallShift + fallTurn * (instancePlace(p) - fallPivot) : instancePlace(p);
}

vec3 instanceNormal(vec3 n) {
    vec3 placed = instanceTurn(n / instanceScale());
    return normalize(falling ? fallTurn * placed : placed);
}

// Where a posed point stood: materials mapped along the board (v_modelUV) keep their place on a falling piece.
vec3 fallRest(vec3 p) {
    return falling ? fallPivot + transpose(fallTurn) * (p - fallShift) : p;
}

uint fallMix(uint h) {
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    return h ^ (h >> 16u);
}

float fallNoise(uint seed, uint salt) {
    return float(fallMix(seed + salt * 0x9e3779b9u) & 0xffffffu) / 16777216.0;
}

mat3 fallRotation(vec3 axis, float angle) {
    float c = cos(angle), s = sin(angle), k = 1.0 - c;
    return mat3(c + axis.x * axis.x * k, axis.y * axis.x * k + axis.z * s, axis.z * axis.x * k - axis.y * s,
          axis.x * axis.y * k - axis.z * s, c + axis.y * axis.y * k, axis.z * axis.y * k + axis.x * s,
          axis.x * axis.z * k + axis.y * s, axis.y * axis.z * k - axis.x * s, c + axis.z * axis.z * k);
}

// Poses this vertex's piece: each storey gives way FALL_STOREY_RELEASE after the one below it; the crushed foot bursts
// out, the storeys above spill outward onto its wreck and tumble the more the higher they stood, a strong hit pushes
// them away from it, a unit's weight drives them down. A piece flies, lands on the pile at its lowest corner, skids and
// stops turning, and from its slot's sink time sinks into the rubble the hex shows.
void fallBegin() {
    if (u_destructSlots.y == 0 || u_piece.w < 0.5 || a_instance1.w < 0.5) { return; }
    int texel = u_destructSlots.x + (int(a_instance1.w + 0.5) - 1) * FALL_TEXELS;
    vec4 fall = texelFetch(u_lavaLights, lavaTexel(texel), 0);     // start, flags, push away from the hit
    float clock = u_destruct.x;
    if (clock < fall.x) { return; }
    vec4 site = texelFetch(u_lavaLights, lavaTexel(texel + 1), 0); // the hex's middle, its floor and its top
    float sinks = texelFetch(u_lavaLights, lavaTexel(texel + 2), 0).x;
    vec3 centre = instancePlace(u_piece.xyz);
    // Raw inputs only, so that every pass's program draws the same numbers.
    uint seed = fallMix(floatBitsToUint(a_instance0.x) ^ fallMix(floatBitsToUint(a_instance0.y)
          ^ fallMix(floatBitsToUint(a_instance0.z) ^ fallMix(floatBitsToUint(a_instance1.x)
          ^ fallMix(floatBitsToUint(u_piece.x) ^ fallMix(floatBitsToUint(u_piece.y) ^ floatBitsToUint(u_piece.z)))))));
    float level = u_destruct.z, reach = u_destruct.w, height = max(site.w - site.z, level);
    float share = clamp((centre.z - site.z) / height, 0.0, 1.0), foot = (1.0 - share) * (1.0 - share);
    vec2 outward = centre.xy - site.xy;
    float offset = length(outward);
    float bearing = 6.2831853 * fallNoise(seed, 0u);
    vec2 away = offset > 1e-3 ? outward / offset : vec2(cos(bearing), sin(bearing));
    float strength = length(fall.zw);
    vec2 pushed = strength > 1e-4 ? fall.zw / strength : vec2(0.0);
    bool loaded = (int(fall.y + 0.5) & FALL_LOADED) != 0;
    float release = FALL_STOREY_RELEASE * max(0.0, centre.z - site.z) / level * (0.8 + 0.4 * fallNoise(seed, 7u))
          * (1.0 - 0.5 * min(strength, 1.0)) * (loaded ? 0.3 : 1.0);
    float t = clock - fall.x - release;
    if (t <= 0.0) { return; }
    vec3 velocity = vec3(away * (FALL_CRUSH * foot + FALL_SPILL * share) * (0.6 + 0.8 * fallNoise(seed, 1u)),
          FALL_CRUSH * 0.3 * foot * fallNoise(seed, 9u));
    // The side facing the hit and the top take most of it: the structure leans away and falls over.
    float facing = clamp(0.5 - dot(outward, pushed) / reach, 0.0, 1.0);
    velocity.xy += pushed * FALL_PUSH * strength * (0.5 + 0.3 * facing + 0.4 * share);
    if (loaded) { velocity.z = min(velocity.z, -FALL_LOAD_DROP); }
    vec3 tumble = vec3(fallNoise(seed, 2u), fallNoise(seed, 3u), fallNoise(seed, 4u)) - 0.5;
    vec3 spin = (dot(tumble, tumble) > 1e-6 ? normalize(tumble) : vec3(0.0, 0.0, 1.0))
          * FALL_TUMBLE * share * (0.3 + 0.7 * fallNoise(seed, 5u))
          + vec3(-away.y, away.x, 0.0) * FALL_TOPPLE_OUT * (0.5 + foot) * (0.5 + fallNoise(seed, 8u))
          + vec3(-pushed.y, pushed.x, 0.0) * FALL_TOPPLE * strength * (0.5 + share);
    // It comes to rest on the floor, or on the wreck of the storeys below it, higher in the middle.
    float ground = site.z + 0.25 * height * share * max(0.0, 1.0 - offset * offset / (reach * reach));
    vec3 extent = abs(u_pieceHalf * instanceScale());
    float gravity = u_destruct.y;
    float drop = max(0.0, centre.z - extent.z - ground);
    // Without gravity (a zero-g board) a piece drifts on: it lands only when it moves down.
    float landing = gravity > 1e-4 ? (velocity.z + sqrt(velocity.z * velocity.z + 2.0 * gravity * drop)) / gravity
          : velocity.z < -1e-4 ? drop / -velocity.z : 1e9;
    float flight = min(t, landing), slide = max(0.0, t - landing);
    vec3 position = centre + velocity * flight;
    position.z -= 0.5 * gravity * flight * flight;
    position.xy += velocity.xy * (1.0 - exp(-FALL_GROUND_DRAG * slide)) / FALL_GROUND_DRAG;
    float rate = length(spin);
    float angle = rate * (flight + (1.0 - exp(-FALL_GROUND_SPIN_DRAG * slide)) / FALL_GROUND_SPIN_DRAG);
    fallTurn = rate > 1e-4 ? fallRotation(spin / rate, angle) : mat3(1.0);
    // The turned box's reach below its middle: the piece rests on its lowest corner.
    vec3 up = vec3(fallTurn[0].z, fallTurn[1].z, fallTurn[2].z);
    float below = abs(dot(up.xy, a_instance1.xy)) * extent.x + abs(dot(up.xy, vec2(-a_instance1.y, a_instance1.x))) * extent.y
          + abs(up.z) * extent.z;
    position.z = max(position.z, ground + below);
    float sinking = clamp((clock - sinks) / FALL_SINK, 0.0, 1.0);
    position.z -= 2.0 * length(extent) * sinking * sinking * (3.0 - 2.0 * sinking);
    falling = true;
    fallPivot = centre;
    fallShift = position;
}
#ifdef impostorFlag

// The direction toward the camera: the projection's right and up rows span its image, in every pass (colour, depth
// twin, shadow map), so each turns the quad toward its own camera.
vec3 impostorToward() {
    vec3 right = vec3(u_projViewTrans[0][0], u_projViewTrans[1][0], u_projViewTrans[2][0]);
    vec3 up = vec3(u_projViewTrans[0][1], u_projViewTrans[1][1], u_projViewTrans[2][1]);
    return normalize(cross(right, up));
}

// The camera's direction with the tree's turn undone (instanceTurn).
vec3 impostorTowardUnturned() {
    vec3 toward = impostorToward();
    return vec3(a_instance1.x * toward.x + a_instance1.y * toward.y, a_instance1.x * toward.y - a_instance1.y * toward.x,
          toward.z);
}

// An impostor quad's corner turned toward the camera (prepare_tree_lods.py stores the quad as a square: x and y its
// corner's offsets across it, z the height of its centre on the plant's axis). The view is taken into the plant's
// unscaled axes (the turn undone, then the scale), where the frames were rendered; the corner then stands in the plane
// across that view and is placed as the tree stands. Viewing the scaled tree along a direction shows what viewing the
// unscaled one along that unscaled view does, so each frame's image lines up with the quad.
vec3 impostorPosition(vec3 corner) {
    vec3 scale = instanceScale();
    vec3 view = normalize(impostorTowardUnturned() / scale);
    float azimuth = atan(view.y, view.x), elevation = asin(clamp(view.z, -1.0, 1.0));
    v_impostorPoint = impostorRight(azimuth) * corner.x + impostorUp(azimuth, elevation) * corner.y;
    v_impostorFrame = vec3(impostorPlace(view), abs(corner.x));
    v_impostorTree = vec4(a_instance1.xy, a_instance0.w, a_instance1.z);
    return instanceTurn((vec3(0.0, 0.0, corner.z) + v_impostorPoint) * scale) + a_instance0.xyz;
}

// The clip depth for an impostor corner at `clip`: the depth of the front of the sphere that holds the plant, scaled as
// the tree stands, toward the camera, alike at every corner. Every texel's surface lies at or behind it, while the quad
// still shows where it stands (terrain-foliage.frag's conservative depth).
float impostorFrontZ(vec3 corner, vec4 clip) {
    // Scaled as the tree stands, the sphere reaches its radius times this far toward the camera.
    float reach = length(impostorTowardUnturned() * instanceScale());
    vec3 front = instancePosition(vec3(0.0, 0.0, corner.z)) + impostorToward() * abs(corner.x) * reach;
    vec4 frontClip = u_projViewTrans * vec4(front, 1.0);
    // Never before the near plane, which would clip the whole quad.
    return frontClip.w > 0.0 ? max(frontClip.z / frontClip.w, -1.0) * clip.w : -clip.w;
}
#endif
