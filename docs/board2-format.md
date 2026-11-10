# Native board format, version 3

`.board2` is JSON with `"format": "megamek-board"` and `"version": 3`. The reader uses the current native schema.
There is no migration between `.board2` versions. Legacy `.board` files still use the existing converter; saving
writes the current `.board2` schema. Unused fields are ignored; the version label does not select a reader or trigger
conversion. Lunar Base and the repository examples/fixtures were rewritten once during development.

The schema is a file boundary, not a second game model. `BoardComponents` translates components into the existing
`Hex` and `Terrain` values. Changing appearance never changes gameplay type, class, or construction factor. Moving or assembling an authored
structural placement explicitly rebakes its covered cells and derived heights in the editor.

## Hexes and buildings

Every hex appears once, with one-based `[column, row]` coordinates and its ground `elevation`. Empty hexes can omit
`components`. Document metadata (`name`, `description`, `tags`, `boardType`, `notes`, `sourceHeader`) and
placed `decorations` retain their existing fields. Gameplay and appearance are read from `components`.

```json
{
  "format": "megamek-board",
  "version": 3,
  "size": { "width": 1, "height": 2 },
  "hexes": [
    {
      "at": [1, 1],
      "elevation": 0,
      "components": {
        "building": {
          "type": "building",
          "weight": "heavy",
          "class": "fortress",
          "cf": 90,
          "height": 2,
          "basement": "none",
          "connections": ["s"],
          "appearance": {
            "footprint": "square",
            "colours": ["#72949b"]
          }
        }
      }
    },
    {
      "at": [1, 2],
      "elevation": 0,
      "components": {
        "building": {
          "type": "building",
          "weight": "heavy",
          "class": "fortress",
          "cf": 90,
          "height": 2,
          "basement": "none",
          "connections": ["n"],
          "appearance": {
            "footprint": "square",
            "colours": ["#72949b"]
          }
        }
      }
    }
  ]
}
```

Structure `type`: `building` or `wall`. Both have a separate `weight`: `civilian`, `light`, `medium`, `heavy`,
`hardened`, or `castleBrian`. The rules know the four in the middle: to them a civilian building is light and a
Castle Brian hardened.
Building `class`: `standard` (the default), `hangar`, `fortress`, or `gunEmplacement`.
Thus a medium hangar stores `"type":"building","weight":"medium","class":"hangar"`;
a heavy wall stores `"type":"wall","weight":"heavy"`. A building's kit and a wall's look follow the `weight`, never
the `cf`. Walls always have no interiors and no basement,
including when a file requests otherwise. `cf`, `height`, and optional `armor` are independent integers.
`basement` is `unknown`, `none`, `twoDeepFeet`, `oneDeepFeet`, `oneDeepNormal`, `oneDeepInfantry`, `oneDeepHead`, or
`twoDeepHead`. Missing `basement` means `unknown`; saving omits `unknown`. A building whose resolved interiors
setting is `no` always has `basement: "none"`, including domes using their default. Conflicting basement and
`basementCollapsed` values are ignored, and no basement roll or collapse event is possible. `noFloors` buildings
can still have basements. `basementCollapsed` otherwise retains the game's integer terrain value.

`connections` lists directions clockwise from north: `n`, `ne`, `se`, `s`, `sw`, `nw`. `[]` means isolated.
Saving records calculated sides explicitly, without modifying the live board. Loading never infers extra sides for
a version 3 component. Missing connections mean none. Automatic joining is an editor operation. Each hex stores
its own sides; the format preserves asymmetric connections so the editor can diagnose and repair them.
Building connections beyond the final game or preview map are closed only after all sheets and rotations are assembled.
Connections across sheet seams remain; source files and editor documents keep their authored edge connections.

## Appearance and asset identity

Appearance is optional and lives inside its component. A generic building needs no appearance: its weight and class
select its modular kit. Building appearance supports:

- Optional `variant`: an existing catalog identifier, such as `building/glass-dome` or `building/lunar-habitat`.
- Alternatively, an explicit `asset` path and/or stable `uid`. An appearance cannot contain both an asset and a variant.
- With neither reference, gameplay weight/class selects the generic modular kit. There is no `mode` field.
- Optional `footprint`: `angled` (default) or `square`.
- Optional `profile`: `stepped` makes upper storeys step back above shorter connected neighbours. Omitted (or `straight`)
  keeps the ground footprint through the full height. The editor saves only `stepped`, separately for each hex.
- Optional `kitVariant`: a named variant inside a generated kit, such as `parapet`.
- Optional `roof`: `vaulted` or `flat`, for a hangar-class building whose kit variant has both roofs. Omitted, a
  hangar is vaulted. Other building classes ignore it.
- Optional `rotation`: clockwise whole degrees, 0–359; 0 is omitted. Connected buildings ignore rotation.
- Optional `colours`: the existing array of up to four `#rrggbb` slot overrides; `null` retains a slot's original color.
  Buildings currently use slot 0 for a whole-building multiplicative tint; white restores the original surfaces.

An isolated authored building can store:

```json
{
  "type": "building",
  "weight": "light",
  "cf": 15,
  "height": 2,
  "connections": [],
  "appearance": {
    "uid": "<registered stable asset ID>",
    "asset": "buildings/colony/operations",
    "rotation": 45,
    "colours": ["#72949b"]
  }
}
```

The path and UID above illustrate the format, not a shipped asset. Loading resolves a known UID to the catalog's
current path. An absent or unknown UID falls back to the saved path. Saving includes the UID when the asset is
registered. Module names (`base#`, `floor#`, `roof#`) and material slots belong to the asset, not the board document.
An unknown saved UID is retained on load/save, clipboard copies, and transforms, including when its asset pack is
unavailable. Changing the referenced asset clears that old identity. The same rule applies to placed decorations.

Catalog variants keep their existing identifiers; the slashes in `building/glass-dome` are namespacing, not a file path.
Lunar Base's domes use `{"variant":"building/glass-dome"}` and its biodomes use
`{"variant":"building/biodome"}`. Its habitats use `building/lunar-habitat`. A generic building
omits `variant`. Explicitly chosen historical art also retains its catalog identifier.

The catalog or asset contract determines how geometry is assembled: connected panels, a generated dome, or a
complete single-hex GLB. The board does not duplicate that classification. Connecting a core-only GLB switches its
look to the generic modular kit.

Isolated generated buildings use a hex outline for `footprint: "angled"` (the default), or a compact square for
`footprint: "square"`, including room for the curb. Their appearance may also store `"padding": 20`: an extra
20% inset of the wall outline. Panels tile the remaining wall space, retaining their original proportions;
roof fittings and curb widths keep their dimensions. Padding defaults to zero and is omitted at zero. It is ignored while
the building has connections, keeping joined hexes continuous. Rotation and padding affect rendering and picking
together. A held building paint stroke stores explicit connections only across the borders followed by that stroke.

Other components keep their existing appearance fields (`variant`, `asset`, `uid`, `material`, `strength`, `colours`,
`rotation`, `tracks`, `beside`, `trafficControl`, `lanes`, `inbound`) subject to owner validation. Only the ground `road`
appearance holds `lanes`: `1` is a single, one-way lane, and two lanes, the default, are stored as none (a saved `2` reads
as none). Its `inbound` lists the sides traffic enters the hex by, by name (`"inbound": ["nw"]`), and needs a single lane;
without it, every hex of a run derives one direction: as its stored hexes say, else from its end with the least
coordinates (`SingleLane.inbound`). On a two-lane road that single-lane exits leave, `exitFrom` lists, by
each exit's side, the sides whose traffic may take it (`"exitFrom": {"se": ["n"]}`: only traffic arriving from the north
may take the exit leaving south-east); an exit not listed, or no `exitFrom`, may be taken from every side
(`SingleLane.feeds`), and a single lane stores none. A board flip reflects both with the road's exits. Both are
cosmetic: rules and the 2D views ignore them. The internal `footprint` and `kitVariant`
appearance owners are folded into the building's appearance when saving.

## Other components

`water` and `pavement` store no connections: their sides derive from the neighbours when a board is read. Their
`appearance.separated` lists, as directions, the sides the author parted from the same terrain beside them, for example
`"water": {"depth": 1, "appearance": {"separated": ["se", "s"]}}`. Absent means every side joins. It is cosmetic: the 3D
views draw a strip of ground along those sides, between hexes at one level; rules and the 2D views ignore it. The editor
stores a side on both hexes, and either one parts it. A legacy file's hand-set exits on these terrains stay in
`legacyExits`, where they choose 2D tiles and part nothing.

| Component | Fields |
|---|---|
| `bridge` | `weight`, `cf`, `height`, `mud`, `connections` |
| `fuelTank` | `weight`, `cf`, `height`, `magnitude`, `connections` |
| `road`, `bridgeRoad` | `type` (`paved`, `alley`, `dirt`, `gravel`), `connections` |
| `rail`, `bridgeRail` | `type` (`rail`, `maglev`), `connections` |
| `water` | `depth`, `rapids` |
| `vegetation` | `woods`, `jungle` (`light`, `heavy`, `ultra`), `height` |
| `ground` | `sand`, `tundra`, `fields`, `swamp`, `mud` |
| `pavement` | `level` |
| `rough` | `level` (`normal`, `ultra`) |
| `rubble` | `type` (what fell: `building`, `wall`, `ultra`), `weight` of a building's (`light`, `medium`, `heavy`, `hardened`) |
| `industry` | `height` |
| `cliff` | `level`, `connections` |
| `weather` | `ice`, `snow`, `blackIce` |
| `hazards` | `fire`, `smoke`, `magma`, `geyser`, `hazardousLiquid`, `ultraSublevel` |
| `scenario` | `impassable`, `fortified`, `metalDeposit`, `deploymentZones` (array of IDs 1–31) |
| `salvage` | `arms`, `legs` |
| `elevators` | `solaris` and `industrial` objects, below |
| `space` | `level`, `screen` |
| `scorch` | `appearance` only |

A fortified hex's `scenario` appearance may store `"fortification": "sandbags"`: its strongpoint is a sandbag emplacement
instead of dug into the ground. `dug`, the default, is omitted. Pavement and roads always take sandbags, whatever is stored.
The value is written only while the hex is fortified; rules and the 2D views ignore it.

Industrial elevators store `"industrial":{"bottom":-2,"top":4,"capacity":100}`. Bottom and top are relative to
the hex elevation; capacity is tons, in steps of 10. Solaris elevators store
`"solaris":{"alternateElevation":7,"rolls":[1,3]}`: the alternate elevation is absolute, and the list names d6
results that trigger a move. These values are not hexside connections. Their encoding bounds are checked separately
from game validity; the editor can still open a shaft whose bottom/top relationship needs repair.

Unnamed values remain integers with their existing gameplay meaning. Numeric values outside a named enumeration
are retained for editor repair; unknown strings are rejected. Missing fields stay absent, rather than being filled
with competing defaults. Derived terrain (automatic inclines, cliff bottoms, sky, repaired-bridge markers) is rebuilt
by the game and is not saved.

Legacy art tokens that conversion has not consumed retain numeric `legacyArt` fields on their owner, or `fluff`/`none`
in `legacyAppearance`. For lossless import of historical selectors, `legacyExits` maps a component field to its raw
integer selector when that integer is not an ordinary six-side connection mask. It also retains non-directional
uses of the old exits field. Ordinary elevators and deployment zones use the named fields above; only malformed or
nonstandard historical payloads need the escape. It cannot coexist with the same field's named connections, rolls,
shaft data, or zone IDs. Normal new components need no `legacyExits`.

Clipboard hexes and editor change comparisons retain their existing internal representation. They do not determine
the version 3 disk schema. This preserves the distinction between direct edits and automatically derived neighbour
changes for undo and appearance inheritance.


## Authored multi-hex buildings

A structural GLB uses the existing placement list, with one instance owned by an anchor hex. Its UID, asset path,
XY offsets, rotation, colours and placement are ordinary object fields. An assembled building additionally stores
`levels`; a fixed whole model omits it. The distinction comes from the GLB, with no `mode` flag. Placements may overlap:
a contested hex goes to the placement with the most of its roof over it, on equal shares to the taller, and returns
to the other if the winner moves away or goes. Each occupied hex retains its normal building component and a reference to that single placement:

```json
{
  "at": [5, 6], "elevation": 0,
  "components": {
    "building": {
      "type": "building", "weight": "medium", "cf": 60, "height": 4,
      "connections": ["n", "ne", "s", "sw"],
      "basement": "none", "interiors": "noFloors"
    }
  },
  "structure": { "id": "hpg-instance", "anchor": [5, 6] },
  "decorations": [{
    "id": "hpg-instance", "kind": "prop",
    "uid": "hpghq001", "asset": "buildings/authored/hpg-headquarters",
    "rotation": 19,
    "placement": { "mode": "surface", "receiver": { "terrain": "ground", "surface": "top" } }
  }]
}
```

Other occupied hexes reference the same ID and anchor and store their own height, CF and connection sides; they do
not repeat the GLB placement. A collapsed hex loses its building component but keeps its reference. The anchor's
placement survives even when that hex collapses. Loading uses saved gameplay state without recomputing coverage or
restoring destroyed sections. Cosmetic edits preserve collapse state. Board flips, resizing and sheet combination
transform references with their placements. Cropping an anchor transfers ownership to a remaining member at the
same position.

`interiors` can be omitted/`default`, `yes`, `noFloors`, or `no`. The default is `yes`, except hangars use `noFloors`
and domes use `no`. `noFloors` leaves a ground hall and usable roof without intermediate usable floors; it does not
shorten the exterior or remove repeated wall modules. `no` prevents entry, keeps roof access, and disables interior
cutaways. This gameplay setting is retained by headless loading.

Invisible GLB structural solids define coverage and heights; visual ornaments do not. At least 50% projected hex
coverage makes a building hex. The largest exposed roof area determines its single height; equal areas choose the
taller roof. Areas are unioned, with higher solids masking lower ones at the same XY location. Interior floors follow
horizontal sections of the solids, preserving courtyards. The renderer slices the assembled artwork and interiors
into hex-owned pieces. Outer visual overhangs belong to their nearest occupied hex and disappear with it. Derived
meshes are never serialized.

### Flags

`scenery/flag` is one generated visual `prop` (stable UID `clothflg`). It uses the ordinary
`scale`, `stretch`, XYZ rotations, placement and four-entry `colours` array. The local X=0
edge is attached; the cloth extends along +X, with +Z up. Rotate the object to change the
attachment direction, including a vertically hung banner. It has no pole or gameplay effect.

The optional `flag` object contains:

```json
"flag": {
  "pattern": "horizontal-3",
  "emblem": "decal/emblems/star-league",
  "x": 0,
  "y": 0,
  "scale": 1,
  "rotation": 0
}
```

Patterns: `solid`, `horizontal-2`, `vertical-2`, `horizontal-3`, `vertical-3`, `sectors-3`,
`diagonal`, `diagonal-reverse`, `cross`, `nordic-cross`, `saltire`, `quarters`, `chevron`, `canton`.
Colours default to red, white, blue and gold, in slot order. Stripes run top-to-bottom or
left-to-right; quarters run top-left, top-right, bottom-left, bottom-right. Crosses use
slot 1 over slot 0; chevron/canton use slot 2 over a two-colour field.

The default pattern is solid. Empty `emblem` means none. An emblem must name a decal in
`decals/emblems`. Its offset is measured in cloth widths/heights from the centre (positive
Y is up), with default `(0, 0)`. Scale 1 fits the emblem to half the cloth height while
preserving its aspect ratio; rotation is counterclockwise degrees. The reverse side shows
the same cloth artwork from behind, including the naturally mirrored emblem.

The 3D editor and game deform the same subdivided cloth mesh, using the shared weather
wind direction and animation clock. Gravity and wind act in world space, independent of
rotation, mirroring and scale. Calm weather retains a 0.15 breeze; vacuum suppresses flutter.
Minimum downwind spread and shallow folds keep calm cloth visible and pickable instead of
collapsing along the attachment. A horizontal attachment still lets a banner hang down.
The fixed local left edge stays attached while the free cloth hangs down and lifts downwind.
Swept cloth segments slide against nearby buildings, props and units using coarse world
bounding boxes. Contact preserves the cloth's connected rows and columns instead of stretching
them into spikes. When coarse bounds enclose the rigid attachment, their nearest face is
trimmed to a common attachment plane for that flag. Ground contact reads a height grid, sampled
once from the installed terrain support per pose and terrain install, along cloth segments and
across triangle edges/interiors, including ice, slopes and height transitions, with a
scale-aware clearance; no floor is invented outside the board. Cloth outside the view holds
its pose, and at a steady frame rate each flag updates every other frame. This is a procedural drape, not a full cloth solver: no self-collision or detailed
concave contact; an attachment inside a solid or below ground remains
where authored. Identical artwork shares one texture while each flag keeps its own mesh.
Colour, animated shadow and picking passes use the current mesh. The editor's pattern/emblem controls work
on both the placement brush and selected objects; sampling, copying and undo preserve them.

`scenery/steel-stick` is a separate steel prop (24 units tall, 0.7 diameter), available in
Parks with the flag; ordinary object scaling, stretching, rotation and placement apply.

### Billboards

`scenery/billboard` is one generated visual `prop` (stable UID `billbord`), with no gameplay effect. The optional
`billboard` object contains:

```json
"billboard": {
  "decals": ["decal/billboards/3_1/some-picture", ""],
  "ratio": "3_1",
  "scale": 1,
  "legs": true,
  "lights": true
}
```

`ratio` is the panels' aspect ratio, width to height: `3_1`, `2_1` or `2_3`. Each is a folder of pictures drawn at that
ratio, `models/board/decals/billboards/<ratio>`, and `decals` holds one picture per panel from that folder, 1 to 8, from
the top of the stack down; panels stack 0.4 m apart in one structure. An empty entry is a blank panel. A picture fills
its panel as drawn, so author it at its folder's ratio. `scale` sizes the panels: at 1 a panel is 4.27 m (14 feet, a US
bulletin's height) tall, so the default `3_1` panel is 12.8 m wide. Only the panels take the size: frames, catwalk deck
and rails run along them at the same section, and legs, stiffeners, brackets, rail posts and lamps keep their real size
and repeat at their real spacing.

The local origin is the middle of the lowest panel's lower edge, on the structure's rear plane. The front faces -Y. Rotate
the object to turn it. The placement's height lifts the stack and is the legs' height: `legs` reach from there down to the
supporting surface and their footings run on below it, so a roof or a slope needs no other setting. Without legs nothing lies behind the rear
plane: put the origin against a wall and raise it to hang the billboard there. A board stores no random art: the editor
picks a picture when Random is chosen or a Random brush places a billboard.

`lights` adds the catwalk and its lamps. After dusk their lenses and the pictures light up by night emission, which costs
no light per pixel, and the whole row of lamps is one finite segment light that fades with daylight, as a building's
`mmNightLights` do.
