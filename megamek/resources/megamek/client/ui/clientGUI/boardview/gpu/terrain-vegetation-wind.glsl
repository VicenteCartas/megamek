// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// SHOCKS
uniform vec3 u_wind;
uniform float u_worldMetre;
uniform float u_vegetationPhase;

// All vegetation shares the same travelling gust. The renderer integrates speed so changing strength cannot jump phase.
float vegetationGust(vec2 root) {
    return sin(dot(root / u_worldMetre, vec2(.11, .07)) - u_vegetationPhase);
}
