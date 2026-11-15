// field-morph.glsl: the height field's change of detail without pops or cracks (TerrainField; design in
// work/terrain-field/design.md P3). Inserted before main() of field programs only (TerrainField#vertex, with
// fieldMorphFlag): no other program's text holds it. The prefix defines FIELD_ROLE_LOCATION and FIELD_PARENT_LOCATION
// (fixed vertex locations of the one numbering) and may define FIELD_MORPH_LAYOUT (the block's explicit binding at
// 4.20+ and in Vulkan; GL 4.1 binds the block by name, TerrainField#bindBlock).
#ifndef FIELD_MORPH_LAYOUT
#define FIELD_MORPH_LAYOUT layout(std140)
#endif

// x: step role, 0 the top's cover .. 1 a step's face; y: rockiness, 0 soil bank .. 1 rock. For the ground's evaluator.
layout(location = FIELD_ROLE_LOCATION) in vec4 a_fieldRole;
// xyz: the coarse parent's position minus this vertex's, in the position's units; w: an exact integer: the parent's
// normal (octahedral, 10 + 10 bits) and in bits 20-23 the neighbouring chunk this vertex borders (0 none, 1-8).
layout(location = FIELD_PARENT_LOCATION) in vec4 a_fieldParent;

// Per draw (binding 7; Part 2 folds it into BoardDraw). fieldMorphWeights[0]: x the chunk's weight with an
// orthographic camera (1 its own detail, 0 its parent's); y above 0 when the weight comes per vertex from distance
// instead, z and w the distances where it reaches 1 and 0. fieldMorphWeights[1], [2]: what a border vertex takes from
// each of the eight neighbouring chunks: at this detail its weight (the lower one wins), 0 for a coarser one, above 1
// for a finer one (the border stays at this detail).
FIELD_MORPH_LAYOUT uniform FieldMorph {
    vec4 fieldMorphWeights[3];
};

#ifdef cameraPositionFlag
#define FIELD_EYE u_cameraPosition.xyz
#else
#define FIELD_EYE vec3(0.0)
#endif

vec3 fieldParentNormal(uint bits) {
    vec2 e = vec2(float(bits & 1023u), float((bits >> 10) & 1023u)) * (2.0 / 1023.0) - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

/** The vertex's morph weight: the chunk's, on a chunk border held to what the neighbour it names draws there. */
float fieldMorphWeight(vec3 position, vec3 eye) {
    uint bits = uint(a_fieldParent.w);
    vec4 own = fieldMorphWeights[0];
    float m = own.y > 0.0 ? clamp((own.w - distance(position, eye)) / (own.w - own.z), 0.0, 1.0) : own.x;
    uint side = (bits >> 20) & 15u;
    if (side > 0u) {
        float cap = fieldMorphWeights[1u + (side - 1u) / 4u][(side - 1u) & 3u];
        m = cap > 1.0 ? 1.0 : min(m, cap);
    }
    return m;
}
