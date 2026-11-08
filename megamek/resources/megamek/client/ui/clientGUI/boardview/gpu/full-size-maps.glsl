// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The maps that repeat over several hexes, at their full size: the tundra carpets, the ice sheet and both lavas share
// two arrays (GpuAssets.fullSizeMaps), so ground that reads several of them keeps within its texture units.
#ifndef FULL_SIZE_MAPS_DECLARED
#define FULL_SIZE_MAPS_DECLARED
uniform sampler2DArray u_fullSizeColor; // sRGB
uniform sampler2DArray u_fullSizeData;  // raw
// FULL_SIZE_LAYERS
#endif
