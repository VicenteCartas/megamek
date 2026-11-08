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

vec3 instancePosition(vec3 p) {
    return instanceTurn(p * instanceScale()) + a_instance0.xyz;
}

vec3 instanceNormal(vec3 n) {
    return normalize(instanceTurn(n / instanceScale()));
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
