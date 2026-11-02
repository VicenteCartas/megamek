#version 330 core
// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
in vec2 a_position; // Shared quad corners (0 or 1).
in vec4 a_bounds; // Per-glyph lower/upper corners, retaining the font cache's exact coordinates.
in vec4 a_color;
in vec4 a_uv;
in float a_contrast; // 1: adaptive fill; 2: white fill; negative: its ring; 0: authored color.
in vec3 a_hex; // Owning column, row and decorative headroom; independent of glyph/roof position.
in vec3 a_surface; // Label elevation, roof-overlay flag and bound font texture index.
uniform mat4 u_projTrans;
out vec4 v_color;
out vec2 v_uv;
out vec2 v_board;
flat out float v_ink;
flat out float v_headroom;
flat out vec3 v_surface;
// OVERLAY_CONTRAST
void main() {
    v_color = a_color;
    v_ink = -1.0;
    if (a_contrast != 0.0 && (abs(a_contrast) > 1.5 || u_contrastEnabled != 0.0)) {
        float ink = abs(a_contrast) > 1.5 || u_contrastEnabled < 0.0 ? 1.0 : overlayInk(a_hex.xy);
        v_ink = a_contrast > 0.0 ? ink : 1.0 - ink;
    }
    v_color.a *= 255.0 / 254.0;
    v_uv = vec2(a_position.x == 0.0 ? a_uv.x : a_uv.z, a_position.y == 0.0 ? a_uv.y : a_uv.w);
    v_board = vec2(a_position.x == 0.0 ? a_bounds.x : a_bounds.z,
          a_position.y == 0.0 ? a_bounds.y : a_bounds.w);
    v_headroom = a_hex.z;
    v_surface = a_surface;
    gl_Position = u_projTrans * vec4(v_board, a_surface.x, 1.0);
}
