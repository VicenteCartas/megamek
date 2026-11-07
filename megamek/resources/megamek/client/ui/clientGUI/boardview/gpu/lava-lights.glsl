// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The local lights' texture (GpuLavaLighting), shared by the lights (lava-lighting.glsl) and the board's hex table
// (terrain-hexes.glsl), which takes its last rows, so that ground and water keep within their texture units.
#ifndef LAVA_LIGHTS_DECLARED
#define LAVA_LIGHTS_DECLARED
uniform sampler2D u_lavaLights; // RGBA32F, 1024 texels a row: per cell (first entry texel, end texel), then per point
                                // world position + emitted power, linear emission RGB + source size; a segment
                                // negates its power and adds its far end + its tube radius in sizes, and a spare texel
ivec2 lavaTexel(int index) { return ivec2(index & 1023, index >> 10); }
#endif
