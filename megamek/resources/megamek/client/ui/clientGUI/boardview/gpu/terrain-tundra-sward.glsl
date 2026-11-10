// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// Earth tundra's grass sward, shared by its blades (terrain-grass.glsl) and the ground under them (terrain-tundra.glsl).
// Earth tundra's sward grows in stands several metres across: straw for the most part, from bleached to ochre (z), with
// russet sedge (x) and green (y), each its stand's share of the sward there, 0 to 1. A stand has no outline: it thins out
// over metres into the next. The ground under the sward takes these shares as tones (terrain-tundra.glsl); each blade
// belongs to one stand or the other, by its own chance (terrain-grass.glsl), so the blades average to the ground's tones.
vec3 tundraSwardStands(vec2 metres) {
    vec2 warp = vec2(sin(metres.y * .23), cos(metres.x * .19)) * .008;
    vec3 broad = texture(u_surfaceNoise, rainNoiseAt(metres * .0015 + warp + .71)).rgb;
    vec3 middle = texture(u_surfaceNoise, rainNoiseAt(metres * .0047 - warp + .29)).rgb;
    vec3 field = broad * .58 + middle.gbr * .42;
    return vec3(smoothstep(.55, .75, field.x), smoothstep(.51, .73, field.y), smoothstep(.3, .7, field.z));
}

// The sward's colour at a height up its blades, from the thatch at their roots (0) to their sunlit tips (1); `tone`
// is each blade's own, 0 to 1.
vec3 tundraSwardColor(vec3 stands, float height, float tone) {
    vec3 straw = mix(mix(toLinear(vec3(.45, .40, .24)), toLinear(vec3(.86, .78, .52)), height),
          mix(toLinear(vec3(.42, .33, .14)), toLinear(vec3(.83, .68, .33)), height), stands.z);
    vec3 russet = mix(toLinear(vec3(.33, .21, .11)), toLinear(vec3(.70, .40, .19)), height);
    vec3 green = mix(toLinear(vec3(.22, .27, .11)), toLinear(vec3(.56, .59, .26)), height);
    return mix(mix(straw, russet, stands.x), green, stands.y * (1.0 - stands.x)) * mix(.84, 1.12, tone);
}
