// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Screen-space occlusion of the opaque scene (GpuAmbientOcclusion): x the share of the sky this pixel's surface sees,
// for the ambient term only; y whether the sun reaches it past nearby depth (contact shadows). Both are computed from
// the depth pre-pass at half resolution and upsampled here against the fragment's own depth, so a surface the pre-pass
// does not hold (a unit, water) reads 1. Without ambientOcclusionFlag they are the constant 1, which folds away.
#ifdef impostorFlag
// The depth an impostor's texel writes, its surface's, which the pre-pass holds; its quad's lies at the plant's front
// (terrain-foliage.frag sets it).
float impostorDepth = 0.0;
#endif
#ifdef ambientOcclusionFlag
// CAMERA_DEPTH
uniform sampler2D u_ambientOcclusion; // half resolution: R visibility, G camera distance less z below, B contact
// x, y: world size of one scene pixel at camera distance d, x + y * d; z: the distance origin of G (GpuAmbientOcclusion)
uniform vec3 u_ambientOcclusionPixel;

vec2 screenOcclusion() {
#ifdef impostorFlag
    float depth = cameraDepth(impostorDepth);
#else
    float depth = cameraDepth(gl_FragCoord.z);
#endif
    // The neighbouring half-resolution samples lie up to 1.5 scene pixels away; grazing surfaces change depth faster.
    float tolerance = 16.0 * (u_ambientOcclusionPixel.x + u_ambientOcclusionPixel.y * depth);
    float stored = depth - u_ambientOcclusionPixel.z;
    // Half-resolution texel i holds scene pixel 2i.
    vec2 position = gl_FragCoord.xy * .5 - .25;
    ivec2 base = ivec2(floor(position));
    vec2 fraction = position - vec2(base);
    ivec2 last = textureSize(u_ambientOcclusion, 0) - 1;
    vec2 visibility = vec2(0.0);
    float total = 0.0;
    for (int corner = 0; corner < 4; corner++) {
        ivec2 offset = ivec2(corner & 1, corner >> 1);
        vec3 texel = texelFetch(u_ambientOcclusion, clamp(base + offset, ivec2(0), last), 0).rgb;
        vec2 bilinear = mix(1.0 - fraction, fraction, vec2(offset));
        // A small floor lets a matching neighbour stand in where the nearest sample shows another surface.
        float weight = (bilinear.x * bilinear.y + .001) * max(0.0, 1.0 - abs(texel.g - stored) / tolerance);
        visibility += texel.rb * weight;
        total += weight;
    }
    return total > .0001 ? visibility / total : vec2(1.0);
}
#else
vec2 screenOcclusion() { return vec2(1.0); }
#endif
