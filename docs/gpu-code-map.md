# GPU code responsibilities

[Docs index](README.md) · [Architecture and data flow](gpu-board.md)

This is the implementation map: each row identifies the code that owns a job and the
state it is allowed to change. Names below are Java files in
[boardview/gpu](../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/).
Use the feature guides for algorithms, asset contracts and coupled changes.

## Scene, window and capture

| Code | What it does |
| --- | --- |
| `GpuHost` | Owns the one native application and window for the whole run and switches its scenes (start screen, lobby, board, editor, preview, UI library). Its public part (`Scene`, `show`, `end`, `isShowing`, `post`, `focus`, `keepOpen`, `whenIdle`) is how other programs of the suite (MekHQ) host their own scenes. |
| `GpuSceneDialogs` | The public path for a scene's Swing dialogs: catches those of the scene's hidden frame, centres them over the window, owns them natively (`GpuNativeDialogOwner`) or keeps them on top, and blocks the scene's input while a modal one is open. The lobby uses it; so can another program's scene. |
| `GpuLobbyWindow`, `GpuLobbyScreen`, `GpuLobbyProbe` | The lobby scene: the Swing-thread controller that turns game events into `LobbySnapshot`s and runs actions through `LobbyCommands`, the libGDX screen that draws the snapshot, and the `MM_START_PAGE=lobby` probe. |
| `megamek.client.lobby` (`LobbySnapshot`, `LobbyCommands`, `LobbyUnitMenu`, `LobbyUnitOrder`, `LobbyUnitCleanup`, ...) | The toolkit-free lobby model: what the screen shows, the commands it sends, the unit and force menus with their permissions, the sort orders and unit clean-up. |
| `phaseDisplay/lobby` (`LobbyServices`, `LobbyBoardPreviews`) | What the lobby scene runs on the Swing thread: ready (with its GIF question and lobby save), the menu bar's burst-fire and hot-load items, and the lobby's 3D board preview. |
| `LobbyTopBar`, `LobbyColumns`, `LobbyLayout`, `LobbyCentre`, `LobbyPlayersPanel`, `LobbyForcesPanel`, `LobbyBattlefieldPanel` | The lobby screen's parts: the top bar, the three columns and their widths (the battlefield folding into a rail in a narrow window), the centre column that swaps the forces for a view with Back, and the columns' panels. |
| `GpuBoardWindow` | Runs a game board, editor or map preview as a `GpuHost` scene; coordinates preview reuse and the preview's Legacy 2D window. |
| `GpuBattleView` | Orchestrates each frame: consumes snapshots, advances playback, places units and submits scene/UI passes. |
| `BoardSource` | Defines captured frame data and the input callbacks crossing between the Swing and GL threads. |
| `BoardScene` | Stores terrain, visible units, annotations and resolved animation events as presentation snapshots. |
| `GpuBoardSource` | Captures gameplay from shared client state, applies visibility filtering and returns commands to phase controllers. |
| `GpuMapSource` | Captures preview/editor boards, listens for local edits and routes editor input with generation checks. |
| `BoardTiles` | The one tile capture of every 3D view (game, preview, editor; `captureAll` for the editor's samples and tests): one `BoardArtwork`, the tiles, the 3x3 invalidation rule, each hex drawn without the legacy tokens the conversion took, and the unconverted-board tripwire. |
| `TerrainSettings`, `GpuBoardTuning` | Hold immutable build settings and expose live controls; a build must use the same settings as its displayed result. |
| `GpuDisplayScale`, `GpuWindowBounds` | Convert logical UI/framebuffer sizes and retain native window placement. |
| `GpuGraphicsCard` | Handles the Windows NVIDIA Optimus graphics-card preference at native startup. |

## Camera and geometric queries

| Code | What it does |
| --- | --- |
| `BoardCamera` | Owns tactical orbit/Free Flight controls, framing, zoom and camera transitions. |
| `BoardProjectionCamera` | Changes perspective/orthographic projection while retaining a common camera pose. |
| `BoardCameraCollision` | Sweeps the Free Flight eye against finished terrain triangles. |
| `BoardGeometry` | Defines board/world coordinate conversion, shared geometry queries and picking bounds. |
| `BoardSurface` | Owns a hex's finished ground, ramps, banks and beds used by rendering and support queries. |
| `BoardRelief` | Computes canonical shared corners, cliff profiles, transition bands, shores and decorative rock placement. |
| `BoardConcrete` | Caches the scene's fitted concrete outline (`common/board/ConcreteFit`, which the legacy conversion reads too), shared with adjoining terrain, and pins the hexes of placed objects it would cut. |
| `BoardRoadGrade` | Reserves a carriageway's ground and gives its height: edge levels, ramp shares, Classic/Enhanced profiles. |
| `BoardRoadCut` | Meshes reserved road ground and removes the terrain over it, with cut faces; builds containment walls. |
| `BoardRoadBank` | Enhanced: moves natural ground near roads vertically into banks and embankments. |
| `BoardRim` | Builds CPU cliff-rim shading masks on the Classic columns' art during bounded terrain preparation. |

## Roads, bridges and authored terrain

| Code | What it does |
| --- | --- |
| `BoardRoad` | Shared ground/deck road and rail footprints, corridor A/B widths, shifted unpaved wear, crossing insets, joins and clearance. Physical heights come from the same supporting surface used for geometry and picking. |
| `BoardRail` | The one builder of both route kinds (`common/board/RailRoute`, MAGLEV and RAIL): each hex's gentle pieces (straights and 60-degree arcs), stranded stubs and dead ends as `RailRoute.lines` composes them, along `RailRoute.course`; the maglev beam at its Hermite levels, the rail's generated ballast, sleepers and rails draped on the faces road paint lies on or on the drawn deck (`BoardBridge.drawnTop`), only the rails on a drawn road; `railTop` stands rail rolling stock and buffer stops on that drawn rail top (model px, added in `GpuTerrain.decorationHeight`). `joint` gives the beam's underside on a shared edge for maglev piers; `flat` draws the palette card and the junction card's layouts. |
| `GpuRoads` | Turns footprints into material masks/atlases carried by existing terrain or bridge triangles. |
| `BoardBridge` | Reads stored built, natural or implicit deck types and the hex's own deck ways. Legacy conversion infers old art once; native decks are read as authored. An implicit maglev-only deck uses the beam as its support/picking surface. |
| `BoardBridgeFooting` | Shared bridge bank support and joint-pier placement, including corridor width. Bridge appearance owns Pillars; a maglev-only implicit deck uses the slim maglev kit and beam underside, ordinary built decks use the bridge pier kit. |
| `BoardBridgeMesh` | Generates manufactured slabs and kerbs from the live road and on-deck rail footprint during chunk preparation. |
| `BoardBridgeTruss` | Generates continuous steel chords, support panels and joined or terminal ends along the manufactured carriageway boundary. |
| `BoardBridgeSlope` | Grades generated slabs and rails to connected decks one level apart; shares complete flat and sloped shapes with drawing, paint and picking; eases Enhanced solid ramps. |
| `BoardNaturalBridge` | Builds hollow rock spans, bank contacts and underpass geometry. |
| `BoardTunnel` | Detects cosmetic portals at eligible cliff-facing road/deck exits and supplies their placement. |
| `BoardRocks`, `BoardShape` | Load reusable rock/scatter CPU mesh data; shape loading is separate from placement policy. |
| `BoardRough` | Places authored rough variants and supplies matching occupied geometry for drawing, picking and support. |
| `BoardGroundCut` | Digs authored scenery's `cut` group into a hex's Enhanced top as DUG ground, places its `stands` for resting troops and parked transports, fits earthworks to the hex's top, and tints their earth to its ground (a dug-in platoon's holes, a fortified hex's strongpoint). |
| `BoardElevator` | A Solaris elevator's platform (diamond-plate top in a hazard-striped band) and, raised, its open lift (deck, scissor table and its cylinders, machinery pit), which the terrain draws in place of the hex's top and walls, and at each height while the platform moves. |
| `BoardScatter`, `GpuScatter` | Choose stable small-prop positions, then bake their textured drawing data into chunk batches. |
| `GpuBuilding` | Uploads a building kit's meshes, stores assembly recipes (modules and connection heights) and exposes per-piece picking data. |
| `ConnectedKit` | Reads and checks a kit's tagged pieces and places them for a hex's exits; one source for drawing, picking and clearance of buildings, fuel tanks and walls. |
| `GpuBuildingInterior` | Builds floor sheets and columns clipped to the roof's actual occupied footprint. |

## Surface fields and vegetation

| Code | What it does |
| --- | --- |
| `BoardFeatures` | Captures terrain appearance, feature sizes, species, deterministic prop placement, placed objects and the terrain-owned scenery rows (geysers, rubble, fortified) from live hexes; a terrain-owned decal row (elevator plates, cleared-rubble gravel) becomes a decal feature with a non-stored object, and a rail's buffer stops are objects the rail owns (ids `terrain:...`, never stored or picked: `terrainOwned`). |
| `BoardLiquid`, `BoardFireSmoke` | Capture liquid/crust classification and existing fire/smoke facts without retaining mutable game objects. |
| `BoardBiome` | Provides world-space visual fields shared by ground shading and vegetation. |
| `BoardVegetation` | Supplies placement inputs; roots are supported by the installed finished terrain. |
| `BoardSurfaceBlend` | Evaluates cross-family material coverage at a world position, including height-limited cliff contacts. |
| `GpuSurfaceBlend` | Refines shading attributes on boundary faces and groups their material palettes while preserving surface planes. |
| `GpuBiomeSurface` | Packs per-hex biome data into a texture consumed by the terrain shaders. |
| `GpuBiomeVegetation` | Plants crop plants and marsh roots; draws reed tiers with shared instanced buffers. |
| `GpuGroundCover` | Prepares deterministic grass roots, retains supported surfaces and draws GPU-shaped grass blades and crop plants. |
| `GpuTreeInstances` | Instances trees, modular building parts and placed objects in every view (a recolourable model's slot colours per instance; mirror images in batches of their own); reuses buffers when placements and pass selections agree. |
| `TerrainLod`, `TreeLod` | Choose terrain sampling and tree mesh detail from projected size, with stable transitions; `TreeLod` also holds the Object LoD size class and zoom threshold for small placed objects. |

## Terrain installation, batching and lifetime

| Code | What it does |
| --- | --- |
| `GpuTerrain` | Owns chunk preparation/install/replacement (the 3D relief, or with the Classic graphics the tileset columns: `setColumns`), terrain resources, queries and terrain render passes, and the Tactical View's draw rule (`setFluffHidden`). |
| `GpuTilesetTerrain` | The Classic graphics' geometry: each hex's tileset column (tops, walls, banks, shallows, falls, deck art) and the writer that puts it into a GpuTerrain chunk's layers; no GL state of its own. |
| `GpuAssets` | Owns shared board models, material maps and liquid assets; instances borrow these resources. |
| `GpuTextures` | Owns shared atlas allocation and texture lifetime. |
| `GpuTerrainPages`, `GpuMeshPage` | Pack nearby static ranges into spatial pages; visibility changes select ranges rather than rebuilding the page. |
| `GpuTerrainBatch` | Reuses compatible terrain material state between consecutive draws. |
| `GpuPropBatch` | Combines opaque prop ranges from visible chunks. |
| `GpuTerrainDepth` | Exposes depth-only ranges over installed opaque meshes for depth/shadow passes. |
| `GpuOpaqueSorter` | Orders opaque submissions while preserving required grass/transparent ordering. |
| `GpuInstancedMesh` | Owns the instanced-mesh binding behavior, including core-profile instance-attribute cleanup and the flipped front face of a batch of mirror images. |

## Atmosphere, weather and visibility

| Code | What it does |
| --- | --- |
| `BoardAtmosphere` | Maps settings to linear light, sun/moon direction, fog/sky colors, exposure and visual condition parameters. |
| `AtmospherePreset`, `GpuAtmosphereControls` | Describe complete condition previews and manage their window-local overrides/default reset. |
| `GpuAtmosphere` | Owns scene color/depth targets, fog/shafts, grading, glare and final composition. |
| `GpuDepthCues` | Measures and eases the distance the view centres on, behind which the composite hazes the scene. |
| `GpuSkyVisibility` | Searches the board's horizons into a sky-visibility volume that scales every lit surface's sky light. |
| `GpuTessellation` | Generates the Phong tessellation stages of sculpted relief's programs where the context offers them. |
| `GpuClouds` | Generates and advects the common cloud-transmission atlas. |
| `GpuCloudShadow` | Shares the cloud atlas and its coordinates with every lit material. |
| `GpuWeatherParticles` | Maintains the bounded precipitation pool and supplies weather-specific particle programs. |
| `GpuFieldOfView` | Uploads client-derived visibility per hex; it shades visibility already decided by game/client rules. |

## Water, ice and lava

| Code | What it does |
| --- | --- |
| `BoardRiver`, `BoardFlow` | Derive shared stream paths and visual current directions from connected liquid surfaces. |
| `GpuWaterShader` | Binds water appearance and flow/depth fields, including receiving-pool interaction inputs. |
| `GpuMagmaShader` | Chooses solid/flowing magma programs and binds their common volcanic material inputs. |
| `GpuIceShader` | Applies the optical ice coat; hidden black ice must stay absent from its visible material data. |
| `GpuLiquidShader` | Animates authored liquid-frame textures for the artwork fallback path. |
| `GpuOcean` | Runs shared GPU FFT stages with separate water/lava spectra and finish programs. |
| `GpuWaterfall` | Builds/draws falling sheets, exposed cuts and landing spray from captured liquid geometry. |
| `GpuWaders` | Describes where posed units intersect water, supplying collars, ripples and wakes. |
| `GpuWaterImpacts` | Produces bounded spray and foam-ring effects for observed unit impacts. |

## Unit identity, assets and assembly

| Code | What it does |
| --- | --- |
| `UnitModelSelection` | Uses the tileset's model identity and captures the appropriate variant/figure count; sensor contacts expose no model identity. |
| `UnitModelState` | Captures immutable loadout, anatomy, surviving members, appearance and pose state after visibility filtering. |
| `UnitModelDescriptor` | Reads/validates the JSON body, rig, mount, emitter and recipe contract. |
| `RigidGlb` | Decodes rigid GLB geometry on the CPU and converts coordinates/colors while preserving named parts; also builds the shared colour-slot mesh of a recolourable model, which placed objects draw in every view, packs a placement's slot colours, and writes a label on a model's label quads. |
| `MeshLod` | Resolves asset node names and missing-level fallback at load time. |
| `GpuUnitModels`, `GpuUnitModel` | Own the shared unit asset library and each loaded model's geometry/placement metadata. |
| `ModelTextures` | Caches textures for its owning asset library; borrowed models do not independently dispose them. |
| `GpuUnitInstance` | Holds one unit's posed nodes/material state; depth and outline views borrow the same buffers. |
| `UnitRig` | Resolves authored joint roles for a body or formation member. |
| `UnitEquipmentModels`, `UnitEquipmentAssembly` | Resolve equipment art and fit the captured loadout to every supported body family. |
| `UnitModelAttachment`, `UnitModelMountArea` | Hold per-instance equipment attachments and deterministically pack them onto mounting faces. |

## Unit shapes, support and appearance

| Code | What it does |
| --- | --- |
| `MekVisual`, `FamilyVisual` | Apply Mek anatomy or the common vehicle/aircraft/naval/ProtoMek/static-body presentation. |
| `InfantryVisual`, `BattleArmorVisual`, `SquadronVisual` | Compose troop formations, living suits or actual fighter members using shared body/equipment assets. |
| `InfantryMotion`, `InfantryFootprint` | Place cosmetic formation members and fit their positions inside the owning hex footprint. |
| `UnitFootprint`, `UnitBounds` | Translate game occupancy to model axes and compute bounds from posed imported parts. |
| `UnitFamilyScale` | Applies family-specific visual scale adjustments on top of board/world dimensions. |
| `UnitGroundContact`, `UnitLandingSupports` | Fit the placed body and landing gear to shared terrain support surfaces. |
| `UnitPicking` | Intersects the current posed rigid parts, including attachments. |
| `UnitDamageDisplay` | Hides lost locations or applies damage artwork to one unit instance. |
| `UnitCamouflage`, `GpuUnitCamouflage` | Resolve visible camouflage images on Swing, then own native texture/per-instance paint data. |
| `GpuUnitShader` | Shades unit paint/damage with the common lighting and shadows. |
| `GpuUnitBases` | The Tactical View's base under each meeple (side ring, facing and twist notches, glows) and its badges (heat, elevation, armour, TMM, conditions, E(C)CM source, wreck cross), rebuilt only when the unit's spec changes. |
| `GpuHexGrid` | The hex grid; in the Tactical View the ground layer (cliffs, steps, woods, roofs with CF, water by depth, smoke and fire, rear arcs) in one instanced draw. |
| `GpuCutout` | Extrudes the alpha silhouette of raised sensor and location-symbol artwork. |
| `GpuUnitVisibility` | Draws occluded portions of authorized visible units/markers; it does not discover hidden entities. |
| `FormationLod` | Chooses body/figure mesh detail from projected size while preserving the instance's animation state. |

## Playback and effects

| Code | What it does |
| --- | --- |
| `UnitPlayback` | Orders resolved movement, attacks, scene checkpoints, scene changes in motion (`TileTransition`: moving elevators), concealment and attachment events on one GL-owned timeline. |
| `UnitMotion` | Interpolates a captured movement route and jump/fall motion without writing back to game paths. |
| `UnitAnimator` | Evaluates rigid poses from authored rest transforms, captured state and the playback clock. |
| `UnitAttack`, `UnitVolley` | Track attack timing and grouped target passes within that timeline. |
| `UnitConversion` | Orders fold/form-change/deploy presentation for transforming units. |
| `ArmFlip`, `UpperBodyTurn` | Track the displayed arm-flip and torso-turn interpolation. |
| `UnitAttachments` | Places exterior riders/swarmers after the carrier's final pose, shared by drawing, picking and effects. |
| `UnitAttachmentMotion` | Tracks boarding/release transforms and motion on the same playback clock. |
| `MeepleCasualties` | Plays meeples dying after the view stops drawing them: destroyed units' meeples and formations' lost vehicles explode, lost soldiers fall on their backs; then they lie, sink and go. |
| `MeepleShatter` | Cuts a meeple's mesh along several planes into closed rigid pieces, each with its outermost points as contacts. |
| `MeepleDebris` | Rigid-body physics of the thrown pieces against a height field sampled from the board's picking, with a time limit that always stops them. |
| `GpuAttackEffects` | Chooses visual attacks and their emitters from the posed loadout. |
| `GpuMissileEffects`, `GpuExplosionEffects`, `GpuJumpJets` | Render batched missiles, bounded fire/smoke volumes (a collapse's dust cloud among them) and visible jump exhaust. |
| `GpuEffectLayer` | The half-resolution layer soft volumes draw into, joined to the scene along the opaque depth's edges; shared by `GpuExplosionEffects` and `GpuTerrainEffects`. |
| `GpuPilotFlames` | Renders the pilot flame every working flamer keeps at its `flame` emitter: a blue gas core and flickering fire tongues in the flamer stream's shader. |
| `GpuTerrainEffects` | Maintains board fire/smoke emitters and their projected-size detail. |
| `GpuEffectBatch`, `GpuEffectDepth` | Batch effect geometry and lazily provide opaque scene depth for soft intersections. |

## Native controls and tactical graphics

| Code | What it does |
| --- | --- |
| `GpuBoardActions`, `GpuMenuCommands` | Describe existing phase/global commands and dispatch them after live-state checks. |
| `GpuBoardUi`, `GpuAttackPanel` | Present contextual actions, general controls and phase-owned weapon/physical-attack state. |
| `GpuBoardSkin`, `GpuPanelDock` | Own native skin/font assets and shared dock layout/visibility. |
| `GpuReportLog`, `GpuReportPanel`, `GpuReportText` | Capture already-filtered reports on Swing, display them and retain glyph-based link hit areas. |
| `BoardTacticalGeometry` | Tessellates/clips client-supplied tactical shapes and defines common hex-marker placement. |
| `BoardFiringGeometry`, `BoardDeploymentGeometry` | Build visual range/attack/deployment geometry from captured legality and boundaries. |
| `BoardEditorTerrain`, `BoardImpassable` | Describe editor/restriction markers without changing physical terrain or legality. |
| `GpuTactical`, `GpuFireControl` | Own native tactical meshes/text and depth-tested firing/range volumes. |
| `GpuMarkers`, `GpuHexText` | Place shared marker artwork and readable captured hex labels over the scene. |
| `GpuHexMasks`, `GpuHexSurface` | Apply regular hex masks to shared terrain carriers without duplicating clipped geometry per command. |

## Shader editing and compilation

| Code | What it does |
| --- | --- |
| `GpuShaderSource` | Resolves draft/disk sources and save destinations: installed overrides, checkout files, bundled resources. |
| `GpuShaderManager` | Tracks drafts/dependencies and prepares/commits live shader changes on the GL thread. |
| `GpuShaderProvider` | Keeps material shader handles stable while replacing compiled programs and their uniform caches. |
| `GpuShaderInputs`, `GpuShaderUniforms`, `GpuShaderValue` | Validate and apply typed uniform overrides through the shared upload path. |
| `GpuShaderEditor`, `GpuShaderInputPanel` | Provide Swing source editing and uniform-inspection controls. |
| `GpuShaderPreview`, `GpuShaderPreviewPanel` | Render isolated samples and exchange immutable preview requests/images with Swing. |
| `GpuGlsl` | Controls shader-language setup and the compatibility adapter for libGDX's built-in programs. |
| `GpuGlslTokenMaker` | Supplies GLSL syntax classification to the editor lexer. |

## Shared code outside the GPU package

| Code | What it does |
| --- | --- |
| `boardview/BoardClientState` | Owns client presentation state shared by renderers: tactical state (the game's tactical capture pass), annotations, focus and client integration. |
| `boardview/BoardView` | Paints the Legacy 2D board and bridges its input to the shared client state. |
| `boardview/BoardArtwork` | Paints the Classic art from the Classic tileset's resolved layers (`TilesetScenery`) and maps selected tileset art to native assets; also serves printable board output. |
| `common/board/BoardModelIds` | The board models' stable ids for saved boards (`models/board/uids.json`, opaque id → path): a `.board2` stores a model's path and id and loads it by its id. |
| `common/board/BoardSceneryLayouts` | The one legacy conversion (`convert`, image-free, run where a board is loaded: `BoardFile.loadSheet`, old saves, previews, the editor), the post-combine seam pass (`joinSheets`), the one statement of what is taken (`takenTokens`: objects, a design or nothing, for every legacy art token), the taken-token strip (`withoutTakenTokens`) and the editor's stamps. |
| `common/board/TilesetMatcher`, `TilesetScenery` | Text-only tileset matching shared by the 2D tileset and the 3D artwork (the Classic tileset, `data/models/board/classic/tileset`, whose entries name a Classic image or a 3D key: `TilesetMatcher.image`); the one "taken" predicate (`TilesetScenery.Layer.taken`); designed layers in tileset order (`appearanceLayers`). |
| `common/board/RailRoute` | Terrain-backed ground/deck rail and maglev, the shared writer, gentle pieces, junction layouts and buffer stops. Layer-aware joins and corridor courses serve both rendering and editor snapping/riders; road and rail logic never reads stored route objects. |
| `tileset/HexTileset` | The 2D tileset's images; the game's (with a game) matches a hex's designs as the tokens they stand for (`BoardEditorBlueprint.artwork`), so the 2D board of a `.board2` draws building art, transitions, kerbs, orchards and rough. |
| `common/board/BridgeSpan` | The span unit of bridges and the one bridge-type decode the conversion stores. |
| `boardview/BoardTactical`, `BoardGlyphContext`, `UnitAnnotations` | Carry client-derived tactical shapes and annotation content into renderer-specific presentation. |
| `clientGUI/MapMenu`, `panels/phaseDisplay/*` | Own real contextual commands, orders and phase-specific availability. |
| `boardeditor/BoardEditorSession` | Owns the editor document, commands, selection and undo history, and converts a typed legacy token at stroke commit; the Legacy 2D editor is gone, its Legacy 2D Preview is a `BoardView` on the session's game. |

Rules changes belong in the game/client/server owners described in [Codebase](codebase.md).
The GPU classes above receive the resulting state or execute the existing command.
