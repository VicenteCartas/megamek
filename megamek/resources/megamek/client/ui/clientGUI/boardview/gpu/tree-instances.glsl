// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared by the colour and depth vertex shaders. Fixed instance locations keep their vertex array compatible.
layout(location = 14) in vec4 a_instance0;
layout(location = 15) in vec4 a_instance1;
#ifdef colourSlotsFlag
// A recolourable model's placement colours (model-colour-slots.glsl).
layout(location = 13) in vec4 a_instance2;
#endif
// x: its run's piece record (GpuIndirectInstances#Pieces): what moves it, 0 for nothing (a standing prop); y, z, w
// unused.
layout(location = 12) in vec4 a_instance3;
#ifdef levelDissolveFlag
// When the tree or kit structure began dissolving between detail levels: positive at the level it takes, negative at
// the one it leaves, 0 at rest (GpuTreeInstances.Stand#detail, GpuTerrain.Prop#dissolve).
flat out float v_levelDissolve;
#endif
#ifdef impostorFlag
// IMPOSTOR_FRAMES
// impostor-sample.glsl reads these.
invariant out vec3 v_impostorPoint;
invariant flat out vec3 v_impostorFrame;
flat out vec4 v_impostorTree;
#endif

// This instance's placement (instance-place.glsl, read before this file).
vec3 instanceTurn(vec3 v) {
    return placeTurn(a_instance1, v);
}

vec3 instanceScale() {
    return placeScale(a_instance0, a_instance1);
}

// A structure's pieces falling where the board drew them (GpuDestruction): rigid bodies thrown in closed form, handed
// to box bodies on the CPU as they touch down. A run of falling pieces (its record's mode 1) draws one piece of a kit at
// every falling hex; each instance names what moves it (a_instance1.w): its hex's slot + 1, whose texels in the local lights' texture hold
// its fall (GpuDestruction#write), as a felled tree's and rising rubble's do; or, from FALL_ROW_OWNER on, its first pose
// row plus FALL_ROW_OWNER, a row a fragment, negative where one row moves the whole part. A row either poses its piece,
// landed as a body, or names the slot whose closed form still moves it (poseRow). Every pass, colour, depth and shadow,
// poses alike from the same inputs.
// LAVA_LIGHTS
// SHOCKS
uniform vec4 u_destruct;       // the falls' clock in playback seconds, gravity, a level's height, a hex's reach
uniform ivec2 u_destructSlots; // the first slot's texel, and 1 while anything falls
uniform vec4 u_destructBoard;  // the board's columns and rows, a hex's width and height
// Pieces posed on the CPU (GpuPoses): a row a piece in a texture of its own, two texels, the piece's shift from where
// it stood with its scale, then its turn as a quaternion, both about its instance's origin.
#if __VERSION__ >= 420
layout(binding = POSE_UNIT) uniform sampler2D u_poses; // RGBA32F, POSE_ROW texels a row
// The runs' piece records (GpuIndirectInstances): three texels each, the run's piece and its half size (below), then
// its fragment table's first texel and its first vertex in its pool, from which gl_VertexID counts.
layout(binding = PIECE_RECORD_UNIT) uniform samplerBuffer u_pieceRecords;
#else
uniform sampler2D u_poses;
uniform samplerBuffer u_pieceRecords;
#endif

// This vertex's piece (pieceBegin), from its instance's record: its node's box centre in the model's frame and what
// moves it (w: 1 a kit's pieces falling, 2 trees falling, 3 trees standing, 4 rubble rising, 0 nothing); that box's half
// size and the fragments the part broke into (KitFragments; 0 whole); where its fragments lie, in the order its pool's
// copy holds them, among the pose texture's table rows (GpuPoses#table: [2k] a fragment box's centre and its first
// vertex, [2k + 1] its half size and its turn about the vertical, in radians; a vertex belongs to the last fragment
// whose first vertex it has reached); and its run's first vertex.
vec4 piece = vec4(0.0), pieceHalf = vec4(0.0);
int pieceTable = 0, pieceBase = 0;

void pieceBegin() {
    if (a_instance3.x > 0.5) {
        int at = 3 * int(a_instance3.x + 0.5);
        piece = texelFetch(u_pieceRecords, at);
        pieceHalf = texelFetch(u_pieceRecords, at + 1);
        vec4 more = texelFetch(u_pieceRecords, at + 2);
        pieceTable = int(more.x + 0.5);
        pieceBase = int(more.y + 0.5);
    }
}

// A texel of the run's fragment table.
vec4 fallTable(int texel) {
    int at = pieceTable + texel;
    return texelFetch(u_poses, ivec2(at % POSE_ROW, POSE_TABLE_ROW + at / POSE_ROW), 0);
}

// The fall's motion, p' = fallShift + fallScale fallTurn (p - fallPivot), set once a vertex by fallBegin: rigid but for
// a piece shrinking away past the board's edge.
bool falling = false;
mat3 fallTurn = mat3(1.0);
vec3 fallPivot = vec3(0.0);
vec3 fallShift = vec3(0.0);
float fallScale = 1.0;

vec3 instancePlace(vec3 p) {
    return placePoint(a_instance0, a_instance1, p);
}

vec3 instancePosition(vec3 p) {
    return falling ? fallShift + fallScale * (fallTurn * (instancePlace(p) - fallPivot)) : instancePlace(p);
}

vec3 instanceNormal(vec3 n) {
    vec3 placed = instanceTurn(n / instanceScale());
    return normalize(falling ? fallTurn * placed : placed);
}

// Where a posed point stood: materials mapped along the board (v_modelUV) keep their place on a falling piece.
vec3 fallRest(vec3 p) {
    return falling ? fallPivot + transpose(fallTurn) * (p - fallShift) / max(fallScale, 1e-4) : p;
}

// Whether the ground point lies past the board's edge, by the hex that holds it (HexTile#centerX, #centerY): nothing to
// land on there.
bool offBoard(vec2 p) {
    if (u_destructBoard.x < 0.5) { return false; }
    float q = (p.x - .5 * u_destructBoard.z) / (.75 * u_destructBoard.z);
    float r = (-p.y - .5 * u_destructBoard.w) / u_destructBoard.w - .5 * q;
    vec3 cube = vec3(q, r, -q - r), hex = floor(cube + .5), off = abs(hex - cube);
    if (off.x > off.y && off.x > off.z) { hex.x = -hex.y - hex.z; } else if (off.y > off.z) { hex.y = -hex.x - hex.z; }
    float row = hex.y + floor(.5 * hex.x);
    return hex.x < 0.0 || hex.x >= u_destructBoard.x || row < 0.0 || row >= u_destructBoard.y;
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

// Rubble the rules left where a structure fell (GpuDestruction#rubble): out of sight under the ground until the dust
// stands at the structure's foot, FALL_RISE_DELAY after its hex gives way (a blast's front reaches the hex's middle
// then), when it rises into place, before the pieces sink into it.
void riseBegin(vec4 fall, vec4 site, vec4 tail) {
    bool blast = (int(fall.y + 0.5) & FALL_BLAST) != 0;
    float t = u_destruct.x - fall.x - (blast ? tail.w * length(site.xy - tail.yz) : 0.0);
    float rise = clamp((t - FALL_RISE_DELAY) / FALL_RISE, 0.0, 1.0);
    if (rise >= 1.0) { return; }
    falling = true;
    fallTurn = mat3(1.0);
    fallPivot = vec3(0.0);
    float depth = 2.0 * abs(pieceHalf.z * instanceScale().z) + .25 * u_destruct.z;
    fallShift = vec3(0.0, 0.0, -depth * (1.0 - rise * rise * (3.0 - 2.0 * rise)));
}

// A tree's or kit structure's level dissolve (GpuTreeInstances.Stand#detail, GpuTerrain.Prop#dissolve): a falling
// piece's, felled tree's or rising rubble's instance holds its slot instead, and none; only a standing one (or none of
// the falls') dissolves.
float instanceDissolve() {
    return piece.w > 0.5 && abs(piece.w - 3.0) > 0.5 ? 0.0 : a_instance1.w;
}

// Poses this vertex's piece from its row: p' = origin + shift + scale * turn(p - origin), and returns 0. A row with a
// negative scale poses nothing: its piece still moves in closed form, and the row's first float, returned, names its
// fall's slot + 1 (GpuPoses#analytic).
float poseRow(int row, vec3 origin) {
    ivec2 at = ivec2(2 * row % POSE_ROW, 2 * row / POSE_ROW);
    vec4 place = texelFetch(u_poses, at, 0);
    if (place.w < 0.0) { return place.x; }
    vec4 q = texelFetch(u_poses, at + ivec2(1, 0), 0);
    falling = true;
    fallTurn = mat3(1.0 - 2.0 * (q.y * q.y + q.z * q.z), 2.0 * (q.x * q.y + q.z * q.w), 2.0 * (q.x * q.z - q.y * q.w),
          2.0 * (q.x * q.y - q.z * q.w), 1.0 - 2.0 * (q.x * q.x + q.z * q.z), 2.0 * (q.y * q.z + q.x * q.w),
          2.0 * (q.x * q.z + q.y * q.w), 2.0 * (q.y * q.z - q.x * q.w), 1.0 - 2.0 * (q.x * q.x + q.y * q.y));
    fallPivot = origin;
    fallShift = origin + place.xyz;
    fallScale = place.w;
    return 0.0;
}

void fallBegin() {
    pieceBegin();
    if (abs(piece.w - 3.0) < 0.5) { swayBegin(); return; }
    float owner = a_instance1.w;
    if (u_destructSlots.y == 0 || piece.w < 0.5 || abs(owner) < 0.5) { return; }
    // A broken part's vertex moves with its fragment: the last whose first vertex it has reached (the first vertices
    // never decrease, so a halving search finds it, in as many steps as the count has bits).
    int fragment = 0, fragments = int(pieceHalf.w + 0.5), step = 1;
    while (2 * step < fragments) { step *= 2; }
    for (; fragments > 1 && step > 0; step /= 2) {
        int probe = fragment + step;
        if (probe < fragments && gl_VertexID - pieceBase >= int(fallTable(2 * probe).w + 0.5)) { fragment = probe; }
    }
    // A piece with rows moves by its row, or by the slot its row names.
    if (abs(owner) >= float(FALL_ROW_OWNER)) {
        owner = poseRow(int(abs(owner) + 0.5) - FALL_ROW_OWNER + (owner > 0.0 ? fragment : 0), a_instance0.xyz);
        if (owner < 0.5) { return; }
    }
    int texel = u_destructSlots.x + (int(owner + 0.5) - 1) * FALL_TEXELS;
    vec4 fall = texelFetch(u_lavaLights, lavaTexel(texel), 0);     // start, flags, push away from the hit
    float clock = u_destruct.x;
    bool rubble = piece.w > 3.5;
    if (!rubble && clock < fall.x) { return; }
    vec4 site = texelFetch(u_lavaLights, lavaTexel(texel + 1), 0); // the hex's middle, its floor and its top
    // When it sinks; a blast's ground zero and its front's playback seconds per world unit here (GpuDestruction#blastFrom).
    vec4 tail = texelFetch(u_lavaLights, lavaTexel(texel + 2), 0);
    if (rubble) { riseBegin(fall, site, tail); return; }
    float sinks = tail.x;
    if (piece.w > 1.5) { treeFall(fall, site, tail); return; }
    // The piece's box, its fragment's where the part broke (not 'half': a reserved word).
    vec3 pivot = piece.xyz, size = pieceHalf.xyz;
    float turned = 0.0;
    if (fragments > 0) {
        vec4 box = fallTable(2 * fragment), side = fallTable(2 * fragment + 1);
        pivot = box.xyz;
        size = side.xyz;
        turned = side.w;
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
    // A fuel tank bursts in its own explosion (GpuDestruction#burst): every piece at once, scattering, not piling up.
    bool burst = (int(fall.y + 0.5) & FALL_BURST) != 0;
    float pile = burst ? 0.0 : 1.0;
    // A blast's front takes each piece as it reaches it and throws it out from ground zero: the slot starts when the front
    // would have taken a piece at ground zero, so that a city falls in one wave, not hex by hex.
    float begins = fall.x;
    if ((int(fall.y + 0.5) & FALL_BLAST) != 0) {
        vec2 fromBlast = centre.xy - tail.yz;
        begins += tail.w * length(fromBlast);
        if (dot(fromBlast, fromBlast) > 1e-4) { pushed = normalize(fromBlast); }
    }
    // The foot first, the top last, within FALL_RELEASE_SPAN, as fast as a mek's death whatever the height.
    float release = burst ? 0.0 : FALL_RELEASE_SPAN * share * (0.8 + 0.4 * fallNoise(seed, 7u))
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
    if (burst) {
        // Thrown up and out from its middle at 31 to 56 degrees, and away from a hit that pushes it (trees an attack
        // breaks), at most FALL_BURST_SPEED (GpuDestruction#settles), tumbling.
        vec2 thrown = strength > 1e-4 ? normalize(pushed * 1.5 + away) : away;
        velocity = normalize(vec3(thrown, 0.6 + 0.9 * fallNoise(seed, 9u))) * FALL_BURST_SPEED
              * (0.5 + 0.5 * fallNoise(seed, 1u));
        spin = (dot(tumble, tumble) > 1e-6 ? normalize(tumble) : vec3(0.0, 0.0, 1.0)) * FALL_TUMBLE
              * (0.3 + 0.7 * fallNoise(seed, 5u));
    }
    // It comes to rest on the floor, or on the wreck of the storeys below it, higher in the middle.
    float ground = site.z + min(.12 * height, FALL_PILE_LEVELS * level) * share * pile
          * max(0.0, 1.0 - offset * offset / (reach * reach));
    vec3 extent = abs(size * instanceScale());
    // Gravity grows with the drop: every piece is down within FALL_SECONDS (GpuDestruction#settles agrees).
    float gravity = max(u_destruct.y, 2.0 * height / (FALL_SECONDS * FALL_SECONDS));
    float drop = max(0.0, centre.z - extent.z - ground);
    // Without gravity (a zero-g board) a piece drifts on: it lands only when it moves down.
    float landing = gravity > 1e-4 ? (velocity.z + sqrt(velocity.z * velocity.z + 2.0 * gravity * drop)) / gravity
          : velocity.z < -1e-4 ? drop / -velocity.z : 1e9;
    // A piece that would come to rest past the board's edge has nothing to land on: it falls on, and shrinks to nothing
    // once below its floor's height.
    float floored = landing;
    bool beyond = offBoard(centre.xy + velocity.xy * (landing + 1.0 / FALL_GROUND_DRAG));
    if (beyond) { landing = 1e9; }
    float flight = min(t, landing), slide = max(0.0, t - landing);
    vec3 position = centre + velocity * flight;
    position.z -= 0.5 * gravity * flight * flight;
    position.xy += velocity.xy * (1.0 - exp(-FALL_GROUND_DRAG * slide)) / FALL_GROUND_DRAG;
    float rate = length(spin);
    float angle = rate * (flight + (1.0 - exp(-FALL_GROUND_SPIN_DRAG * slide)) / FALL_GROUND_SPIN_DRAG);
    fallTurn = rate > 1e-4 ? fallRotation(spin / rate, angle) : mat3(1.0);
    // The box's first side as the piece stood, level (a mirror image's turn reversed); its second lies across it.
    vec2 along = instanceTurn(vec3(cos(turned), (a_instance0.w < 0.0 ? -1.0 : 1.0) * sin(turned), 0.0)).xy;
    // Landed, it topples onto its flattest side as it skids to rest: no slab stays standing on an edge.
    vec3 thin = fallTurn * (extent.z <= min(extent.x, extent.y) ? vec3(0.0, 0.0, 1.0)
          : extent.x < extent.y ? vec3(along, 0.0) : vec3(-along.y, along.x, 0.0));
    vec3 over = cross(thin, vec3(0.0, 0.0, thin.z < 0.0 ? -1.0 : 1.0));
    float lean = length(over);
    if (slide > 0.0 && lean > 1e-4) {
        fallTurn = fallRotation(over / lean, atan(lean, abs(thin.z)) * (1.0 - exp(-FALL_GROUND_SPIN_DRAG * slide))) * fallTurn;
    }
    // The turned box's reach below its middle: the piece rests on its lowest corner.
    vec3 up = vec3(fallTurn[0].z, fallTurn[1].z, fallTurn[2].z);
    float below = abs(dot(up.xy, along)) * extent.x + abs(dot(up.xy, vec2(-along.y, along.x))) * extent.y
          + abs(up.z) * extent.z;
    // It rests on its lowest corner where it lies, not where it stood: a slab that toppled does not hover at its standing
    // half height, nor a piece thrown off the pile at the pile's height. Landed, it settles there as it skids.
    vec2 lies = position.xy - site.xy;
    float rest = site.z + below + min(.12 * height, FALL_PILE_LEVELS * level) * share * pile
          * max(0.0, 1.0 - dot(lies, lies) / (reach * reach));
    if (beyond) {
        fallScale = 1.0 - smoothstep(0.0, FALL_EDGE, t - floored);
    } else {
        position.z = max(slide > 0.0 ? mix(position.z, rest, 1.0 - exp(-FALL_GROUND_DRAG * slide)) : position.z, rest);
    }
    float sinking = smoothstep(0.0, 1.0, (clock - sinks) / FALL_SINK);
    position.z -= 2.0 * length(extent) * sinking;
    // It shrinks as it sinks: at the board's edge nothing pokes out through the board's side (FallForm agrees).
    fallScale *= max(1e-3, 1.0 - sinking);
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
