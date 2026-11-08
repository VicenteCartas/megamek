#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A coarser level of the sun's depth map (GpuShadowLight#levels): one texel of every two by two of the level above,
// kept as it is. A minimum would turn a tree crown's scattered leaves solid and an average would set their depth in
// mid-air; one picked texel keeps how much of the area casts.
uniform sampler2D u_depth;

void main() {
    gl_FragDepth = texelFetch(u_depth, ivec2(gl_FragCoord.xy) * 2, 0).r;
}
