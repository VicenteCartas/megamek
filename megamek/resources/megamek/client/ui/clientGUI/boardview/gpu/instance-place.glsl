// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Where an instance (GpuTreeInstances' format) places a point of its model: the instanced vertex stages
// (tree-instances.glsl) and the GPU-driven path's culling (GpuIndirectInstances) alike. place: x, y, z and the
// horizontal scale, negative for a mirror image; turn: the turn's cosine and sine, and the vertical scale.
vec3 placeTurn(vec4 turn, vec3 v) {
    return vec3(turn.x * v.x - turn.y * v.y, turn.y * v.x + turn.x * v.y, v.z);
}

// The horizontal, horizontal and vertical scales; a mirror image (a negative place.w) reverses its model's y axis.
// Its group also flips the front face, as the reflection reverses the winding (GpuIndirectInstances#draw).
vec3 placeScale(vec4 place, vec4 turn) {
    return vec3(abs(place.w), place.w, turn.z);
}

vec3 placePoint(vec4 place, vec4 turn, vec3 p) {
    return placeTurn(turn, p * placeScale(place, turn)) + place.xyz;
}
