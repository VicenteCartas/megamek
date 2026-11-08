// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A tree impostor's quad, in its fragment stages (terrain-foliage.frag, shadow-depth.frag): each pixel shows one of the
// four frames about the view, so the plant looks as its far mesh does from that side (tree-instances.glsl turns the
// quad).
// IMPOSTOR_FRAMES
// The alpha test's inputs: the pixel's point on the quad in the plant's unscaled axes about the impostor's centre, and
// the view's place among the frames (column, row) with the sphere's radius. Invariant where they are computed, so a
// colour program and its depth twin keep the same texels; declared to match it below GLSL 4.20 (terrain-foliage.frag).
#if __VERSION__ < 420
invariant in vec3 v_impostorPoint;
invariant flat in vec3 v_impostorFrame;
#else
in vec3 v_impostorPoint;
flat in vec3 v_impostorFrame;
#endif
// The tree's turn (cosine, sine), its horizontal scale (negative for a mirror image, tree-instances.glsl) and its
// vertical scale.
flat in vec4 v_impostorTree;

// A vector in the plant's unscaled axes, placed as the tree stands: scaled, then turned (instancePosition).
vec3 impostorPlaced(vec3 v) {
    v *= vec3(abs(v_impostorTree.z), v_impostorTree.z, v_impostorTree.w);
    return vec3(v_impostorTree.x * v.x - v_impostorTree.y * v.y, v_impostorTree.y * v.x + v_impostorTree.x * v.y, v.z);
}

// From the quad's point to the surface its texel shows, in world units, as the tree stands: `along` is the texel's
// depth along the frame's view as its normal atlas's alpha stores it, in sphere diameters about .5. The quad's point
// lies off the frame's plane by its own offset along the frame's view, unless the frame is the view.
vec3 impostorOffset(vec3 frameView, float along) {
    return impostorPlaced(frameView * ((along * 2.0 - 1.0) * v_impostorFrame.z - dot(v_impostorPoint, frameView)));
}

// A normal in the plant's unscaled axes, as the tree stands: the inverse scale, then the turn (instanceNormal).
vec3 impostorNormal(vec3 n) {
    n /= vec3(abs(v_impostorTree.z), v_impostorTree.z, v_impostorTree.w);
    return normalize(vec3(v_impostorTree.x * n.x - v_impostorTree.y * n.y, v_impostorTree.y * n.x + v_impostorTree.x * n.y, n.z));
}

// The atlas coordinate this pixel shows and its gradients, and the view of the frame it comes from: one of the four
// frames about the view, by two screen thresholds against the view's place between them (interleaved gradient noise,
// at offsets apart from the tree dissolve's), and the pixel's point on the quad projected into that frame. A frame's
// square holds the impostor's sphere, so its border texels are empty.
vec2 impostorUV(out vec2 gradX, out vec2 gradY, out vec3 frameView) {
    vec2 cell = floor(v_impostorFrame.xy), share = v_impostorFrame.xy - cell;
    float across = fract(52.9829189 * fract(dot(gl_FragCoord.xy + vec2(19.0, 47.0), vec2(.06711056, .00583715))));
    float along = fract(52.9829189 * fract(dot(gl_FragCoord.xy + vec2(71.0, 13.0), vec2(.06711056, .00583715))));
    float column = mod(cell.x + (across < share.x ? 1.0 : 0.0), IMPOSTOR_AZIMUTHS);
    float row = min(cell.y + (along < share.y ? 1.0 : 0.0), IMPOSTOR_ROWS - 1.0);
    float azimuth = column * (IMPOSTOR_TURN / IMPOSTOR_AZIMUTHS), elevation = IMPOSTOR_ELEVATIONS[int(row)];
    frameView = impostorView(azimuth, elevation);
    vec3 right = impostorRight(azimuth), up = impostorUp(azimuth, elevation);
    // The image's v grows downward; a frame's square is the sphere's diameter wide.
    vec2 size = 2.0 * v_impostorFrame.z * vec2(IMPOSTOR_AZIMUTHS, IMPOSTOR_ROWS);
    vec3 dx = dFdx(v_impostorPoint), dy = dFdy(v_impostorPoint);
    gradX = vec2(dot(dx, right), -dot(dx, up)) / size;
    gradY = vec2(dot(dy, right), -dot(dy, up)) / size;
    vec2 inFrame = clamp(vec2(dot(v_impostorPoint, right), -dot(v_impostorPoint, up)) / (2.0 * v_impostorFrame.z) + .5,
          0.0, 1.0);
    return (vec2(column, row) + inFrame) / vec2(IMPOSTOR_AZIMUTHS, IMPOSTOR_ROWS);
}
