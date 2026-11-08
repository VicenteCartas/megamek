// Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
// A tree impostor's frames, as mm-data/tools/prepare_tree_lods.py renders them (the two must match): its far mesh seen
// from IMPOSTOR_AZIMUTHS directions around the plant at each of four elevations, a row of azimuths per elevation, the
// lowest row on top of the atlas. Directions and axes are in the plant's own unscaled axes; a frame's view points from
// the plant toward the camera, its right and up axes span the image.
const float IMPOSTOR_AZIMUTHS = 8.0;
const float IMPOSTOR_ROWS = 4.0;
// 10, 35.26 (the board's isometric view), 60 and 90 degrees above the horizon, in radians: the top row looks straight
// down, as the board's top view does (its azimuths are turns of that one view), so no side of an upright part shows.
const vec4 IMPOSTOR_ELEVATIONS = vec4(.17453293, .61540585, 1.04719755, 1.57079633);
const float IMPOSTOR_TURN = 6.28318531;

vec3 impostorView(float azimuth, float elevation) {
    return vec3(cos(elevation) * cos(azimuth), cos(elevation) * sin(azimuth), sin(elevation));
}

vec3 impostorRight(float azimuth) {
    return vec3(-sin(azimuth), cos(azimuth), 0.0);
}

vec3 impostorUp(float azimuth, float elevation) {
    return vec3(-sin(elevation) * cos(azimuth), -sin(elevation) * sin(azimuth), cos(elevation));
}

// A view's place among the frames: its column (its azimuth in frame spacings, which may be negative) and its row
// (between the two elevations it lies between, held at the first and the last).
vec2 impostorPlace(vec3 view) {
    float azimuth = atan(view.y, view.x);
    float elevation = asin(clamp(view.z, -1.0, 1.0));
    vec4 e = IMPOSTOR_ELEVATIONS;
    float row = elevation <= e.x ? 0.0
          : elevation < e.y ? (elevation - e.x) / (e.y - e.x)
          : elevation < e.z ? 1.0 + (elevation - e.y) / (e.z - e.y)
          : elevation < e.w ? 2.0 + (elevation - e.z) / (e.w - e.z) : 3.0;
    return vec2(azimuth / (IMPOSTOR_TURN / IMPOSTOR_AZIMUTHS), row);
}
