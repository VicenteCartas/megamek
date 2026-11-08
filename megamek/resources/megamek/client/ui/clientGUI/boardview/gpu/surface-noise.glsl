// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The tiling noises of rain, water and ground in one array (GpuWaterShader.surfaceNoise), so a surface that reads both
// keeps within its texture units.
#ifndef SURFACE_NOISE_DECLARED
#define SURFACE_NOISE_DECLARED
uniform sampler2DArray u_surfaceNoise;
// RG ripple slope, B foam noise, A caustic network; every channel tiles.
const float WATER_DETAIL = 0.0;
// Independent noise in R, G and B: 64 texels repeated four times across the layer's 256, so a quarter of the coordinates
// (and of a read's gradients) finds the same texels and mip levels a 64-texel texture has.
const float RAIN_NOISE = 1.0, RAIN_NOISE_SCALE = .25;
#define rainNoiseAt(uv) vec3((uv) * RAIN_NOISE_SCALE, RAIN_NOISE)
#endif
