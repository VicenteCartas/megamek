# Grass and shared vegetation wind

`GpuGroundCover` draws the blades that stand on finished terrain: a meadow's
grass, earth tundra's sward and tussocks, and a field's wheat and maize. None of
them is planted or stored. Every blade places itself in the vertex stage, on a
map of the ground that the GPU draws from the installed terrain
(`GpuGroundMap`). `GpuBiomeVegetation` draws the wetland reeds, the one kind
terrain workers still plant with each chunk ([fields and marsh](gpu-fields-marsh.md));
trees belong to `GpuTreeInstances`.

## Where to change what

| Owner | Responsibility |
| --- | --- |
| `GpuGroundMap`, `ground-map.vert`, `ground-map.frag`, `ground-map-cover.frag` | The ground under the view, drawn top-down: its height and how much of each kind grows on it. |
| `GpuGroundCover` | The tiles each kind draws for the view, where on the board each kind can grow at all, projected density and blade templates. |
| `terrain-grass.glsl` | A blade's placement on the map (`groundCover`), blade shape, bending, a lip blade's hang, a tussock's tuft. |
| `terrain-crops.glsl` | The crops' colours and sizes, and a field's parcels (`fieldParcel`), shared by the ground and the plants. |
| `terrain-meadow.glsl` | Growth variation shared by ground color and grass shape. |
| `terrain-tundra-sward.glsl` | Earth tundra's sward: its stands' colours for blades and ground. |
| `terrain-vegetation-wind.glsl` | World-space gust used by grass, crops and marsh plants. |
| `terrain-vegetation.frag` | Plant surface lighting, root darkening and wetness. |
| `GpuBiomeSurface` | The board's hex table, whose blue channel also marks hexes that grow no cover. |
| `BoardPlants`, `BoardPlanting` | The reeds a chunk plants, and a game's record of what was planted. |
| `GpuTerrain` | Wind phase, pass ordering, the map's draws (`gatherGroundMap`) and its redraw triggers. |

The **Grass blades** control under Tuning → Display hides the grass and the
tussocks; the crops' plants stand either way. The choice is saved across
sessions (`GUIPreferences.BOARD_GRASS`, on until chosen).

The **Grass height** slider under Tuning → Terrain (50-200%, not saved)
multiplies the height of every blade but a field's plants (`u_grassHeight`).

## The ground map

One 1024 x 1024 target for the whole view, 6 MB whatever the board's size:
RGBA8 for the shares and 16-bit depth for the height. It is drawn straight down
from the chunks' installed meshes, at whatever detail they have, by two small
programs of its own, compiled with the terrain (so the program cache and the
shader warm-up hold them) and first used in a board's first frame.

- The sculpted ground writes its height and four shares by what it is drawn
  as. Red, the meadow: the grass in its family or in a blended triangle's
  weights, with the turf's thresholds for mixtures and slopes, less the biome
  stencil's cover (`biomeCoverage`). Green, earth tundra's sward: the tundra's
  coverage on earth families, between the sward's openings
  (`swardOpenings`) and clear of the Snow theme's remnants.
  Blue, the ground a tussock can stand on: the same without the openings.
  Alpha, a field's crops: the field's coverage on level ground.
- A hex the board's hex table marks grows none: frozen land, and bit 2 of the
  table's blue channel (`GpuBiomeSurface`) where a building stands or stood and
  on scorched, airless, zero-gravity or new-formed ground. This is how a game's
  history holds without stored plants.
- What lies on the ground takes its alpha's share of every kind away: a road by
  its mask, paint by its image, water (raised 15 cm, which leaves the wet toe
  bare), and what stands in a chunk (authored buildings, placed objects, bridge
  decks, rails) where it is within a blade's height of the ground, so a raised
  deck or a wire leaves its ground alone. A kit building's pieces are not
  drawn: its hex grows none by the hex table.

Texels are world-aligned and a power of two of 12.5 cm, chosen so that the map
is 1.5 to 3.2 times as wide as the seen ground: a blade reads the same texels
wherever the view moves at one size. The whole map is drawn again when the view
leaves it, when it grew too coarse for the view, and when the board's tiles
change. An installed chunk or paint (`GpuGroundCover.installed`, called where
`GpuTerrain` installs them) redraws its own rectangle alone, under a scissor.
A scorch or a scar needs no redraw: the vertex stage reads the damage directory
per blade. Moving lifts are not in the map.

## Blades

Each kind is one instanced draw of a blade template with no instance data
(`GpuInstancedMesh.drawRuns`). Tiles are squares of 8 m fixed in the world; a
frame lists runs of tiles along each tile row the camera sees, and an
instance's index says which tile of its run and which of the tile's candidates
it is. A run draws the instances its nearest tile needs; the vertex stage
thins the farther ones, so a perspective view splits a row into runs where the
need changes by a quarter. Per board, a bit a tile and kind says where the kind
can grow at all (`survey`; for a field, the crops of the parcels that reach the
tile, `BoardFields.parcels`), so no instance is drawn over ground that grows
none of it. Nothing else is kept per tile or hex, and nothing per blade.

A blade shows in the first frame at its size: there is no worker, no chunk
detail to wait for and no grow-in. A zoom draws a longer prefix of the same
candidates and a pan other tiles, so neither moves a blade.

| Kind | Candidates a tile | Grows where |
| --- | --- | --- |
| Grass (part 0) | 448, 7 a square metre, a low-discrepancy sequence from the tile's own start | The map's red exceeds the blade's chance: a meadow's blade. Else its green exceeds the far end of the chance: a sward's blade. |
| Tussock (part 10) | 2 tufts of 112 blades, a blade of each in turn | A tuft stands anywhere in its half of the tile, carried up to 5 m by a slow drift (`coverNoise`) that gathers tufts into loose clusters, where the map's blue is over one half. |
| Wheat (part 1) | 448, as grass | The map's alpha is .6 or more, in a wheat parcel, clear of its headland. |
| Maize (parts 2 to 8) | 50: two a cell of 1.6 m, the cells in an order of the tile's own | The two plants of its parcel's planted rows nearest the cell's centre, each if it lies in the cell. |

Every blade stands at the map's height, and none on a step between two texels
(a cliff's face, an object's side). Density rises with the projected hex size:
grass from 120 to 500 px, tussocks from 100 px (fewer and wider below 400), wheat
and maize all the way out to 24 to 40 px, fewer and wider as they shrink
(`GpuBiomeVegetation.cropDensity`, `terrain-crops.glsl`). The two templates of a
kind switch at 280 px.

A meadow blade on a lip, where the ground a metre and a half on falls away by
more than the slope under it, leans out that way and hangs as far as the ground
falls: standing behind the lip, arching over it, hanging down a face. This
replaces the planted rim blades; there are no blades on a bank's earth face.

Earth tundra's sward lies over, combed one way, in stands of straw, russet and
green (`terrain-tundra-sward.glsl`); its tussocks are tufts of finer blades that
stand at their centre and lean out towards their rim.

## Lighting

Meadow, sward and tussock blades are lit by a normal the vertex stage gives
them: their ground's slope, from the map's height, tilted a quarter each blade's
own way, or a tussock's mound. The fragment stage keeps that normal on the
viewer's side (`terrain-vegetation.frag`). Crops keep their own faces. Grass
receives shadows and casts none.

Blades draw in their own batch (`GpuTerrain.renderCover`) after the depth
pre-pass and before the terrain's colour flush: the ground they hide fails the
depth test instead of being shaded.

## Wind

`GpuTerrain` integrates a wrapped gust phase; changing strength changes its
speed without jumping its position. Zero strength leaves the plants still.
Grass blends angular poses around its root; crop plants keep their leaves on
their swaying stalks; marsh plants form a deeper arch. A blast's wind flattens
blades away from ground zero as its front passes (`terrain-shocks.glsl`).

## Limits

- The map's height is the installed mesh's, without tessellation's
  displacement.
- Every vertex of a candidate places it again, and a candidate that grows
  nothing still runs that far: a kind costs the Iris Xe no more than stored
  roots only while most of its candidates stand.
- Sand's turf windows and mineral soil patches do not thin the meadow.
- The old thinning band towards open water is gone: blades stand wherever the
  ground draws turf.
- At 3840 x 2160 the farthest grass zoom uses 2 m texels; a capture pair there shows no difference from planted roots.
