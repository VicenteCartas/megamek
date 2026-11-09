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
// SHOCKS
uniform vec4 u_destruct;       // the falls' clock in playback seconds, gravity, a level's height, a hex's reach
uniform ivec2 u_destructSlots; // the first slot's texel, and 1 while anything falls
// The batch's piece: its node's box centre in the model's frame, and what moves its instances (GpuInstancedMesh#piece):
// w 1 a kit's pieces falling, 2 trees falling, 3 trees standing, 0 nothing.
uniform vec4 u_piece;
uniform vec4 u_pieceHalf;      // that box's half size; w: the fragments the part broke into (KitFragments), 0 whole
// A broken part's fragments, in the order its batch's copy holds them: [2k] the box's centre and its first vertex,
// [2k + 1] its half size. A vertex belongs to the last fragment whose first vertex it has reached.
uniform vec4 u_fragments[2 * FALL_FRAGMENTS];

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

// Poses this vertex's piece: the foot gives way first, the top last; a blast's front takes each piece as it reaches it.
// The crushed foot bursts out, the storeys above spill outward onto its wreck and tumble the more the higher they stood,
// a strong hit pushes them away from it, a unit's weight drives them down. A piece flies, lands on the pile at its
// lowest corner, skids and stops turning, and from its slot's sink time sinks into the rubble the hex shows.
// A standing tree leans in a blast's wind about its foot and springs back (terrain-shocks.glsl): some 17 degrees near
// ground zero, where trees the blast does not fell stand.
void swayBegin() {
    vec2 wind = shockWind(a_instance0.xy);
    float strength = length(wind);
    if (strength < 1e-4) { return; }
    falling = true;
    fallTurn = fallRotation(vec3(-wind.y, wind.x, 0.0) / strength, strength * .1);
    fallPivot = a_instance0.xyz;
    fallShift = a_instance0.xyz;
}

// A felled tree falls away from ground zero about its foot once the front reaches it, slowly at first, faster as it
// goes, rocks once on landing and, from its slot's sink time, sinks into the ground. Its slot: when, which way (the push),
// its hex's floor and tallest tree, when it sinks and the blast's ground zero and front.
void treeFall(vec4 fall, vec4 site, vec4 tail) {
    bool blast = (int(fall.y + 0.5) & FALL_BLAST) != 0;
    vec2 fromBlast = a_instance0.xy - tail.yz;
    float t = u_destruct.x - fall.x - (blast ? tail.w * length(fromBlast) : 0.0);
    if (t <= 0.0) { return; }
    uint seed = fallMix(floatBitsToUint(a_instance0.x) ^ fallMix(floatBitsToUint(a_instance0.y)));
    float strength = length(fall.zw);
    vec2 pushed = blast && dot(fromBlast, fromBlast) > 1e-4 ? normalize(fromBlast)
          : strength > 1e-4 ? fall.zw / strength : vec2(1.0, 0.0);
    float veer = (fallNoise(seed, 1u) - .5) * .7;
    vec2 away = vec2(pushed.x * cos(veer) - pushed.y * sin(veer), pushed.x * sin(veer) + pushed.y * cos(veer));
    float duration = .5 + .3 * fallNoise(seed, 2u);
    float share = min(t / duration, 1.0), after = max(0.0, t - duration);
    float angle = 1.45 * share * share - .1 * exp(-after * 9.0) * sin(after * 22.0);
    float sinking = clamp((u_destruct.x - tail.x) / FALL_SINK, 0.0, 1.0);
    falling = true;
    fallTurn = fallRotation(vec3(-away.y, away.x, 0.0), angle);
    fallPivot = a_instance0.xyz;
    fallShift = a_instance0.xyz - vec3(0.0, 0.0, (site.w - site.z) * .7 * sinking * sinking * (3.0 - 2.0 * sinking));
}

// Rubble the rules left where a structure fell (GpuDestruction#rubble): out of sight under the ground until its pieces
// are down, then it rises into place as they settle, before they sink into it. Its slot's sink time says when.
void riseBegin(float sinks) {
    float rise = clamp((u_destruct.x - sinks + FALL_RISE_LEAD) / FALL_RISE, 0.0, 1.0);
    if (rise >= 1.0) { return; }
    falling = true;
    fallTurn = mat3(1.0);
    fallPivot = vec3(0.0);
    float depth = 2.0 * abs(u_pieceHalf.z * instanceScale().z) + .25 * u_destruct.z;
    fallShift = vec3(0.0, 0.0, -depth * (1.0 - rise * rise * (3.0 - 2.0 * rise)));
}

// A tree's LOD dissolve (GpuTreeInstances.Stand#detail): a falling tree's instance holds its slot instead, and none.
float instanceDissolve() {
    return abs(u_piece.w - 2.0) < .5 ? 0.0 : a_instance1.w;
}

void fallBegin() {
    if (abs(u_piece.w - 3.0) < 0.5) { swayBegin(); return; }
    if (u_destructSlots.y == 0 || u_piece.w < 0.5 || a_instance1.w < 0.5) { return; }
    int texel = u_destructSlots.x + (int(a_instance1.w + 0.5) - 1) * FALL_TEXELS;
    if (u_piece.w > 3.5) { riseBegin(texelFetch(u_lavaLights, lavaTexel(texel + 2), 0).x); return; }
    vec4 fall = texelFetch(u_lavaLights, lavaTexel(texel), 0);     // start, flags, push away from the hit
    float clock = u_destruct.x;
    if (clock < fall.x) { return; }
    vec4 site = texelFetch(u_lavaLights, lavaTexel(texel + 1), 0); // the hex's middle, its floor and its top
    // When it sinks; a blast's ground zero and its front's playback seconds per world unit here (GpuDestruction#blastFrom).
    vec4 tail = texelFetch(u_lavaLights, lavaTexel(texel + 2), 0);
    float sinks = tail.x;
    if (u_piece.w > 1.5) { treeFall(fall, site, tail); return; }
    // A broken part's vertex moves with its fragment (not 'half': a reserved word).
    vec3 pivot = u_piece.xyz, size = u_pieceHalf.xyz;
    int fragments = int(u_pieceHalf.w + 0.5);
    if (fragments > 0) {
        int k = 0;
        for (int i = 1; i < fragments; i++) { k += gl_VertexID >= int(u_fragments[2 * i].w + 0.5) ? 1 : 0; }
        pivot = u_fragments[2 * k].xyz;
        size = u_fragments[2 * k + 1].xyz;
    }
    vec3 centre = instancePlace(pivot);
    // Raw inputs only, so that every pass's program draws the same numbers.
    uint seed = fallMix(floatBitsToUint(a_instance0.x) ^ fallMix(floatBitsToUint(a_instance0.y)
          ^ fallMix(floatBitsToUint(a_instance0.z) ^ fallMix(floatBitsToUint(a_instance1.x)
          ^ fallMix(floatBitsToUint(pivot.x) ^ fallMix(floatBitsToUint(pivot.y) ^ floatBitsToUint(pivot.z)))))));
    float level = u_destruct.z, reach = u_destruct.w, height = max(site.w - site.z, level);
    float share = clamp((centre.z - site.z) / height, 0.0, 1.0), foot = (1.0 - share) * (1.0 - share);
    vec2 outward = centre.xy - site.xy;
    float offset = length(outward);
    float bearing = 6.2831853 * fallNoise(seed, 0u);
    vec2 away = offset > 1e-3 ? outward / offset : vec2(cos(bearing), sin(bearing));
    float strength = length(fall.zw);
    vec2 pushed = strength > 1e-4 ? fall.zw / strength : vec2(0.0);
    bool loaded = (int(fall.y + 0.5) & FALL_LOADED) != 0;
    // A blast's front takes each piece as it reaches it and throws it out from ground zero: the slot starts when the front
    // would have taken a piece at ground zero, so that a city falls in one wave, not hex by hex.
    float begins = fall.x;
    if ((int(fall.y + 0.5) & FALL_BLAST) != 0) {
        vec2 fromBlast = centre.xy - tail.yz;
        begins += tail.w * length(fromBlast);
        if (dot(fromBlast, fromBlast) > 1e-4) { pushed = normalize(fromBlast); }
    }
    // The foot first, the top last, within FALL_RELEASE_SPAN, as fast as a mek's death whatever the height.
    float release = FALL_RELEASE_SPAN * share * (0.8 + 0.4 * fallNoise(seed, 7u))
          * (1.0 - 0.5 * min(strength, 1.0)) * (loaded ? 0.3 : 1.0);
    float t = clock - begins - release;
    if (t <= 0.0) { return; }
    vec3 velocity = vec3(away * (FALL_CRUSH * foot + FALL_SPILL * share) * (0.6 + 0.8 * fallNoise(seed, 1u)),
          FALL_CRUSH * 0.3 * foot * fallNoise(seed, 9u));
    // The side facing the hit and the top take most of it: the structure leans away and falls over.
    float facing = clamp(0.5 - dot(outward, pushed) / reach, 0.0, 1.0);
    velocity.xy += pushed * FALL_PUSH * strength * (0.5 + 0.3 * facing + 0.4 * share);
    // A blast stronger than a heavy hit (near its epicentre) also flings the pieces up and out.
    velocity.z += FALL_PUSH * .35 * max(0.0, strength - 1.0);
    if (loaded) { velocity.z = min(velocity.z, -FALL_LOAD_DROP); }
    vec3 tumble = vec3(fallNoise(seed, 2u), fallNoise(seed, 3u), fallNoise(seed, 4u)) - 0.5;
    vec3 spin = (dot(tumble, tumble) > 1e-6 ? normalize(tumble) : vec3(0.0, 0.0, 1.0))
          * FALL_TUMBLE * share * (0.3 + 0.7 * fallNoise(seed, 5u))
          + vec3(-away.y, away.x, 0.0) * FALL_TOPPLE_OUT * (0.5 + foot) * (0.5 + fallNoise(seed, 8u))
          + vec3(-pushed.y, pushed.x, 0.0) * FALL_TOPPLE * strength * (0.5 + share);
    // It comes to rest on the floor, or on the wreck of the storeys below it, higher in the middle.
    float ground = site.z + min(.12 * height, FALL_PILE_LEVELS * level) * share
          * max(0.0, 1.0 - offset * offset / (reach * reach));
    vec3 extent = abs(size * instanceScale());
    // Gravity grows with the drop: every piece is down within FALL_SECONDS (GpuDestruction#settles agrees).
    float gravity = max(u_destruct.y, 2.0 * height / (FALL_SECONDS * FALL_SECONDS));
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
    // It rests on its lowest corner where it lies, not where it stood: a slab that toppled does not hover at its standing
    // half height, nor a piece thrown off the pile at the pile's height. Landed, it settles there as it skids.
    vec2 lies = position.xy - site.xy;
    float rest = site.z + below + min(.12 * height, FALL_PILE_LEVELS * level) * share
          * max(0.0, 1.0 - dot(lies, lies) / (reach * reach));
    position.z = max(slide > 0.0 ? mix(position.z, rest, 1.0 - exp(-FALL_GROUND_DRAG * slide)) : position.z, rest);
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
    vec3 placed = instanceTurn((vec3(0.0, 0.0, corner.z) + v_impostorPoint) * scale) + a_instance0.xyz;
    // A far tree leans or falls with its quad: its frames, made from above its horizon, still read at a few pixels.
    return falling ? fallShift + fallTurn * (placed - fallPivot) : placed;
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
