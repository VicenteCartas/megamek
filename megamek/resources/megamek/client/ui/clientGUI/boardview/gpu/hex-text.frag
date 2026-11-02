#version 330 core
layout(location = 0) out vec4 fragColor;
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
#extension GL_EXT_frag_depth : require
precision highp float;
#define DEPTH gl_FragDepthEXT
#else
#define DEPTH gl_FragDepth
#endif
in vec4 v_color;
in vec2 v_uv;
in vec2 v_board;
flat in float v_ink;
flat in float v_headroom;
flat in vec3 v_surface;
uniform sampler2D u_texture; // SpriteBatch's first texture, followed by seven more borrowed atlas pages.
uniform sampler2D u_fonts[7]; // GpuHexText.TEXTURES; each retains its own filtering and mipmaps.
uniform sampler2D u_depth;
uniform sampler2D u_units;
uniform vec2 u_unitOptions; // This frame has a borrowed unit capture; matching-depth tolerance.
uniform vec4 u_viewport;
uniform int u_topView;
// GROUND_LAYER
// Constant sampler indices also work on GL 3.3; the page choice is flat across each glyph.
vec4 glyph() {
    // Derivatives precede the per-glyph branch, so mip selection does not depend on divergent sampler choices.
    vec2 dx = dFdx(v_uv), dy = dFdy(v_uv);
    int page = int(v_surface.z);
    if (page == 0) return textureGrad(u_texture, v_uv, dx, dy);
    if (page == 1) return textureGrad(u_fonts[0], v_uv, dx, dy);
    if (page == 2) return textureGrad(u_fonts[1], v_uv, dx, dy);
    if (page == 3) return textureGrad(u_fonts[2], v_uv, dx, dy);
    if (page == 4) return textureGrad(u_fonts[3], v_uv, dx, dy);
    if (page == 5) return textureGrad(u_fonts[4], v_uv, dx, dy);
    if (page == 6) return textureGrad(u_fonts[5], v_uv, dx, dy);
    return textureGrad(u_fonts[6], v_uv, dx, dy);
}
void main() {
    fragColor = v_color * glyph();
    if (v_ink >= 0.0) { fragColor.rgb = vec3(v_ink); }
    if (u_topView != 0 && v_surface.y == 0.0) {
        DEPTH = 0.0;
        return;
    }
    vec2 screen = (gl_FragCoord.xy - u_viewport.xy) / u_viewport.zw;
    float depth = texture(u_depth, screen).r;
    vec4 reconstructed = u_inverseView * vec4(screen * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 position = reconstructed.xyz / reconstructed.w;
    vec3 normal = cross(dFdx(position), dFdy(position));
    normal /= max(length(normal), 0.000001);
    // A cliff lies on a shared edge. Move inside the visible solid before deciding who owns it.
    vec2 inside = (position - normal * (min(u_groundBoard.z, u_groundBoard.w) * 0.002)).xy;
    vec2 occluder = boardHex(inside * vec2(1.0, -1.0) / u_groundBoard.zw);
    vec2 owner = boardHex(v_board * vec2(1.0, -1.0) / u_groundBoard.zw);
    bool ownHex = depth < 1.0 && distance(owner, occluder) < 0.1;
    float unit = u_unitOptions.x > 0.5 ? texture(u_units, screen).r : 1.0;
    bool onUnit = unit < 1.0 && behind(depth, unit, u_unitOptions.y) < 0.5;
    bool decoration = !onUnit && v_headroom > 0.0 && ownHex
          && position.z >= v_surface.x - u_groundBoard.z * 0.001
          && position.z <= v_surface.x + v_headroom;
    // HEIGHT can pierce any local content, including units, but other hexes still occlude it.
    DEPTH = (v_surface.y != 0.0 && ownHex) || decoration ? min(gl_FragCoord.z, depth) : gl_FragCoord.z;
}
