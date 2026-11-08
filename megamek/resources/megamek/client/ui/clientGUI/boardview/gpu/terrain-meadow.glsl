// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Shared continuous variation for the ground colour and blade shape, without cutting bare holes in a meadow.
float meadowField(vec2 metres) {
    vec2 warp = vec2(sin(metres.y * .31), cos(metres.x * .29)) * .025;
    float broad = texture(u_surfaceNoise, rainNoiseAt(metres * .009 + warp)).g;
    float detail = texture(u_surfaceNoise, rainNoiseAt(metres * .027 + .317)).b;
    return broad * .7 + detail * .3;
}

float meadowCover(vec2 metres) {
    return smoothstep(.20, .66, meadowField(metres));
}
