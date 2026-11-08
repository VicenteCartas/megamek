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
// The direction an impostor card's renders looked from (its vertex normal), placed as a direction of the tree and divided
// by its horizontal scale: times its texels' depth in world units of the card's width, the way to the surface they show.
out vec3 v_impostorDepth;
out float v_impostorAspect;
#endif

vec3 instanceTurn(vec3 v) {
    return vec3(a_instance1.x * v.x - a_instance1.y * v.y, a_instance1.y * v.x + a_instance1.x * v.y, v.z);
}

// The horizontal, horizontal and vertical scales; a mirror image (a negative a_instance0.w) reverses its model's y axis.
// Its batch also flips the front face, as the reflection reverses the winding (GpuInstancedMesh#mirrored).
vec3 instanceScale() {
    return vec3(abs(a_instance0.w), a_instance0.w, a_instance1.z);
}

vec3 instancePosition(vec3 p) {
    return instanceTurn(p * instanceScale()) + a_instance0.xyz;
}

vec3 instanceNormal(vec3 n) {
#ifdef impostorFlag
    v_impostorAspect = abs(a_instance0.w) / a_instance1.z;
#endif
    return normalize(instanceTurn(n / instanceScale()));
}
