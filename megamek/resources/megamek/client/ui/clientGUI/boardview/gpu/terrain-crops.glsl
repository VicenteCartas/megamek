// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// The crops (BoardFields): wheat and maize. Their plants (terrain-grass.glsl) and the ground they fade into
// (terrain-biome.glsl) take their colours from here. Colours are linear; a tone picks within each part's range.
// GpuBiomeVegetation.cropSource defines the plants' mean height in levels, CROP_HEIGHT; a hex's width in metres,
// CROP_HEX_METRES; and the projected pixels per hex below which their plants thin out, CROP_KEEP, wheat's in x and
// maize's in y, and over which they fade into the ground, CROP_FADE.

// Where a wheat stalk's ear begins and ends up the stalk.
const vec2 WHEAT_EAR = vec2(.81, .97);

vec3 cropStalk(bool maize, float tone) {
    return maize ? mix(toLinear(vec3(.40, .48, .22)), toLinear(vec3(.50, .56, .28)), tone)
          : mix(toLinear(vec3(.52, .44, .26)), toLinear(vec3(.64, .55, .33)), tone);
}

// A maize leaf, yellowing toward its tip (0 at its node, 1 at its tip).
vec3 cropLeaf(float tone, float tip) {
    return mix(mix(toLinear(vec3(.33, .45, .17)), toLinear(vec3(.46, .55, .25)), tone), toLinear(vec3(.62, .58, .28)),
          smoothstep(.7, 1.0, tip) * .6);
}

// Wheat's ear; maize's tassel.
vec3 cropHead(bool maize, float tone) {
    return maize ? mix(toLinear(vec3(.58, .48, .30)), toLinear(vec3(.70, .58, .36)), tone)
          : mix(toLinear(vec3(.66, .55, .32)), toLinear(vec3(.80, .68, .42)), tone);
}

// The tilled earth between a field's plants (terrain-biome.glsl).
vec3 cropEarth(float tone) { return mix(toLinear(vec3(.20, .14, .075)), toLinear(vec3(.33, .255, .14)), tone); }

// A stand's mean colour from above once its plants are a pixel or less (terrain-biome.glsl): maize's leaves, darker
// between the rows, with a few specks of tassel; wheat's ears over its stalks and the shadowed earth between them, which
// shows less as the view leaves the vertical.
vec3 cropCanopy(bool maize, float tone, float tilt) {
    return maize ? mix(cropLeaf(tone, .3) * .86, cropHead(true, tone), .04)
          : mix(mix(toLinear(vec3(.15, .11, .06)), cropStalk(false, tone) * .62, mix(.3, .6, tilt)), cropHead(false, tone),
                .27);
}

// The share of a field's plants standing at this projected size: all of them above CROP_KEEP, fewer as they shrink,
// each survivor widening by as much (terrain-grass.glsl) so that the stand keeps its cover.
float cropShare(bool maize, float pixels) { return clamp(pixels / (maize ? CROP_KEEP.y : CROP_KEEP.x), .001, 1.0); }

// How many of a field's plants stand as they fade into the ground's crop colour (terrain-biome.glsl).
float cropPlants(float pixels) {
#ifdef mediumQualityFlag
    pixels /= CROP_MEDIUM_SCALE; // They stand on the detail levels it takes at larger hexes (TerrainLod.select).
#endif
    return smoothstep(CROP_FADE.x, CROP_FADE.y, pixels);
}
