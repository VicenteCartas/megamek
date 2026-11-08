#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The faded texels of cutouts over the finished scene (GpuCutoutFade, cutout-fade.glsl).
layout(location = 0) out vec4 fragColor;
uniform sampler2D u_fadeColour, u_fadeOpticalDepth;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 sums = texelFetch(u_fadeColour, texel, 0);
    // Too many layers for the half floats would turn black: the scene shows instead.
    if (sums.a <= 0.0 || isinf(sums.a)) { discard; }
    fragColor = vec4(sums.rgb / sums.a, 1.0 - exp(-texelFetch(u_fadeOpticalDepth, texel, 0).r));
}
