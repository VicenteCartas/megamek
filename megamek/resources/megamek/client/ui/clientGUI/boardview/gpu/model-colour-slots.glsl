// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// RigidGlb.recolour on the GPU, for a recolourable model's one shared mesh (RigidGlb.colourSlotMesh): a_colourSlot is a
// vertex's shade relative to its slot's default colour (linear rgb; 1 where the default channel is 0) and its slot (w,
// 0 for none). Uniform replacements arrive as linear RGB; compact per-instance replacements remain packed sRGB
// 0xRRGGBB. A negative value keeps the model's own. Model colors and shade factors stay linear, including values above 1.
// KEEP IN SYNC with RigidGlb#recolour (the CPU copies of layout rows, truss decks and editor previews): the same packed
// 0xRRGGBB decode (channel / 255), the same negative = own colour rule and shade * linear(replacement).
// RigidGlbTest compares the two per vertex; GpuSceneryColourSmokeTest checks the drawn colours of placed objects.
// v_slotGlow is what a replacement does to the vertex's colour, 1 without one: model.frag tints the vertex's glow by it.
// The CPU copies keep the model's own glow.
#ifdef colourSlotsFlag
in vec4 a_colourSlot;
uniform vec3 u_slotColours[4];
out vec3 v_slotGlow;

// Ordinary draws decode once during upload; instanced draws use the packed overload below.
vec3 slotColour(vec3 colour, vec4 slot, vec3 colours[4]) {
    int index = int(slot.w + .5) - 1;
    if (index < 0 || colours[index].r < 0.0) return colour;
    return slot.rgb * colours[index];
}

vec3 slotColour(vec3 colour, vec4 slot, vec4 colours) {
    int index = int(slot.w + .5) - 1;
    if (index < 0 || colours[index] < 0.0) return colour;
    uint rgb = uint(colours[index]); // an exact integer: + .5 rounds odd values above 2^23 up a step
    vec3 replacement = vec3((rgb >> 16) & 255u, (rgb >> 8) & 255u, rgb & 255u) / 255.0;
    return slot.rgb * toLinear(replacement);
}
#endif
