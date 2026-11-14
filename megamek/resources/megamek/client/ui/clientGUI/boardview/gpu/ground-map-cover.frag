#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// What lies on the ground map's ground (GpuGroundMap): a road, paint, water, an object's footprint. Nothing grows
// through it: blended over the map, its alpha takes that share of every kind's growth away, so a road's shoulder or
// a decal's soft edge thins the cover as it is drawn.
uniform int u_mask;
uniform sampler2D u_maskTexture;
uniform float u_opacity;
in vec2 v_maskUV;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(0.0, 0.0, 0.0, u_opacity * (u_mask == 0 ? 1.0 : texture(u_maskTexture, v_maskUV).a));
}
