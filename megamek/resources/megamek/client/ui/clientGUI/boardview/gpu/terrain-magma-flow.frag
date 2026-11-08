#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
layout(location = 0) out vec4 fragColor;
in vec3 v_normal;
uniform float u_metre;
uniform float u_normalMaps;
// 1 for a mirror image drawn as it is (a placed geyser), whose reversed winding turns its visible faces into back faces.
uniform float u_mirrored;
// magma-flow-functions
void main() {
    vec3 geometric = visibleFace(dFdx(v_cloudPosition), dFdy(v_cloudPosition), -viewDirection());
    vec3 face = normalize(v_normal);
    if (gl_FrontFacing == (u_mirrored > .5)) face = -face;
    vec3 position = v_cloudPosition / u_metre;
    vec3 eye = -viewDirection();
    vec2 current = vec2(0.0);
    float shore = .4, bank = 1.0;
    vec4 waves = vec4(0.0);
    if (u_magmaFieldMap.x > 0.0) {
        vec4 field = magmaField(v_cloudPosition.xy);
        current = (field.ba * 255.0 - 128.0) / 127.0;
        shore = magmaShore(field);
        bank = smoothstep(-.01, .08, shore);
    }
    if (u_magmaOceanScale > 0.0) {
        waves = texture(u_magmaOcean, v_cloudPosition.xy * u_magmaOceanScale);
        // Gentle convection moves skin and melt together without knotting the entire source into rope-like folds.
        // Geometry, bank silhouettes and picking remain on the shared terrain surface.
        position.xy -= waves.zw * .30 * bank;
    }
    Volcanic material = magmaSurface(position, face, eye, magmaDrift(face, current, bank, waves), bank, waves);
    fragColor = magmaOutput(magmaChill(material, shore), geometric, bank, 1.0);
}
