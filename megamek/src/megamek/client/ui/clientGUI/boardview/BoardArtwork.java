/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;
import static megamek.client.ui.tileset.HexTileset.HEX_W;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.BoardSurfaceBlend;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.tileset.TilesetManager;
import megamek.client.ui.util.UIUtil;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetMatcher;
import megamek.common.board.TilesetScenery;
import megamek.common.board.TilesetScenery.Scenery;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** EDT-owned artwork composition. Game and map sources share this service; it never constructs a view. */
public final class BoardArtwork implements AutoCloseable {
    /**
     * {@code tileset} is the hex's Classic art, which the Classic graphics draw on its plain hex column; a bridge has
     * no art there: both graphics draw the same 3D bridge.
     */
    public record HexImage(Coords coords, BufferedImage decals, BufferedImage decalsWithoutLimbs,
          List<BoardHexText> text, Map<Integer, String> structureModels, BufferedImage tileset,
          Set<Integer> blankTerrains, Scenery scenery, BufferedImage tilesetDecals, BufferedImage tilesetScenery) {
        public HexImage(Coords coords, BufferedImage decals, BufferedImage decalsWithoutLimbs,
              List<BoardHexText> text, Map<Integer, String> structureModels, BufferedImage tileset) {
            this(coords, decals, decalsWithoutLimbs, text, structureModels, tileset, Set.of(), Scenery.EMPTY, null,
                  null);
        }
        public HexImage { blankTerrains = Set.copyOf(blankTerrains); }
    }
    private record DecalArtwork(BufferedImage full, BufferedImage withoutLimbs, BufferedImage tileset,
          Scenery scenery, BufferedImage tilesetDecals, BufferedImage tilesetScenery) { }
    private final Map<Coords, DecalArtwork> featureArtwork = new HashMap<>();
    /** The images of the 3D board's tileset; which images a hex selects is {@link TilesetScenery}'s text. */
    private HexTileset gpuTileset;
    /** Its ground art as each level shows it. */
    private TilesetLevels levels;
    private Image hexMask;
    private static final Font LABEL_FONT = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 10);

    /** Printable whole-board image, independent of either viewport, its zoom, or a live GL context. */
    public static BufferedImage printable(Board board, TilesetManager tileset,
          Function<Coords, BufferedImage> overlay) {
        int width = board.getWidth() * HEX_W * 3 / 4 + HEX_W / 4;
        int height = board.getHeight() * HEX_H + HEX_H / 2;
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var tile = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        var graphics = result.createGraphics();
        var local = tile.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            UIUtil.setHighQualityRendering(local);
            // The paper around the staggered board edge, as the 3D print has.
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            for (int y = 0; y < board.getHeight(); y++) {
                for (int parity = 0; parity < 2; parity++) {
                    for (int x = parity; x < board.getWidth(); x += 2) {
                        Coords coords = new Coords(x, y);
                        Hex hex = board.getHex(coords);
                        local.setComposite(AlphaComposite.Clear);
                        local.fillRect(0, 0, HEX_W, HEX_H);
                        local.setComposite(AlphaComposite.SrcOver);
                        Image base = tileset.baseFor(hex);
                        drawBaseTerrain(hex, local, base, base, tileset.getHexMask(), 1);
                        for (Image image : tileset.supersFor(hex)) { local.drawImage(image, 0, 0, null); }
                        for (Image image : tileset.orthographicFor(hex)) { local.drawImage(image, 0, 0, null); }
                        if (GUIPreferences.getInstance().getLevelHighlight()) {
                            var outline = HexDrawUtilities.rasterHex();
                            int[] starts = {0, 1, 3, 4, 5, 7};
                            local.setColor(Color.BLACK);
                            for (int direction = 0; direction < 6; direction++) {
                                if (HexDrawUtilities.hasElevationBorder(board, coords, direction)) {
                                    int start = starts[direction], end = (start + 1) % outline.npoints;
                                    local.drawLine(outline.xpoints[start], outline.ypoints[start],
                                          outline.xpoints[end], outline.ypoints[end]);
                                }
                            }
                        }
                        local.drawImage(overlay.apply(coords), 0, 0, HEX_W, HEX_H, null);
                        // Coordinates and hex types whatever the views show, printed black on a white outline.
                        BoardHexText.draw(local, new Point(), HEX_W,
                              BoardHexText.capture(coords, hex, board, 1, LABEL_FONT, LABEL_FONT, true, true, true),
                              true);
                        Point position = largeTileLocation(x, y, 1);
                        graphics.drawImage(tile, position.x, position.y, null);
                    }
                }
            }
            return result;
        } finally {
            local.dispose();
            graphics.dispose();
        }
    }

    public BoardArtwork() {
        install(TilesetScenery.tileset());
    }

    /** Native terrain themes exclude the legacy tileset's synthetic transparent-image selector. */
    public static java.util.List<String> themes() {
        return TilesetScenery.tileset().getThemes().stream()
              .filter(theme -> !TilesetMatcher.TRANSPARENT_THEME.equals(theme)).toList();
    }

    /** Reread the definitions and source images, which ordinary hex invalidation retains. */
    public void reload() {
        install(TilesetScenery.reload());
    }

    /** Images for the 3D board's tileset {@code definitions}, the one parse per JVM that the scenery text reads too. */
    private void install(TilesetMatcher definitions) {
        var replacement = new HexTileset(definitions.root(), definitions);
        if (gpuTileset != null) { gpuTileset.close(); }
        gpuTileset = replacement;
        levels = new TilesetLevels(definitions.root());
        clear();
        hexMask = null;
    }

    /**
     * The art of {@code hex} at {@code coords} of {@code board}, whose labels read the board. {@code BoardTiles} passes
     * each hex as the 3D board draws it, without the legacy tokens the conversion took. Cached per coords until
     * {@link #invalidate}.
     */
    public HexImage capture(Board board, Coords coords, Hex hex) {
        // Bound the transient raster cache; immutable snapshots intern and retain the pixels they actually use.
        if (featureArtwork.size() > 256) { clear(); }
        Map<Integer, String> models = TilesetScenery.structureModels(hex,
              TilesetScenery.buildingConnected(hex, coords, board::getHex));
        Set<Integer> blank = gpuTileset.blankTerrainTypes(hex);
        DecalArtwork decals = captureDecals(coords, hex, models, blank);
        gpuTileset.clearHex(hex);
        return new HexImage(coords, decals.full(), decals.withoutLimbs(),
              BoardHexText.capture(coords, hex, board, 1, LABEL_FONT, LABEL_FONT), models, decals.tileset(),
              blank, decals.scenery(), decals.tilesetDecals(), decals.tilesetScenery());
    }

    /** Only the hex's labels, which the game relabels as its state changes; the terrain is BoardTiles'. */
    public static List<BoardHexText> labels(Board board, Coords coords) {
        return BoardHexText.capture(coords, board.getHex(coords), board, 1, LABEL_FONT, LABEL_FONT);
    }

    public void invalidate(Coords coords) {
        featureArtwork.remove(coords);
    }

    public void clear() {
        featureArtwork.clear();
        gpuTileset.clearAllHexes();
    }

    private Image mask() {
        if (hexMask == null) { hexMask = TilesetManager.loadHexMask(); }
        return hexMask;
    }

    public static Point largeTileLocation(int x, int y, float scale) {
        int yPosition = (int) (y * HEX_H * scale) + ((x & 1) == 1 ? (int) ((HEX_H / 2.0f) * scale) : 0);
        return new Point((int) (x * HEX_W * 0.75f * scale), yPosition);
    }

    public static void drawBaseTerrain(Hex hex, Graphics2D graphics2D, Image baseImage, Image scaledImage,
          Image hexMask, float scale) {

        // check if this is a standard tile image 84x72 or something different
        boolean standardTile = (baseImage.getHeight(null) == HEX_H) && (baseImage.getWidth(null) == HEX_W);
        // do not make larger than hex images even when the input image is big
        int origImgWidth = scaledImage.getWidth(null); // save for later, needed for large tiles
        int origImgHeight = scaledImage.getHeight(null);

        if (standardTile) { // is the image hex-sized, 84*72?
            graphics2D.drawImage(scaledImage, 0, 0, null);
            return;
        }

        // Draw image for a texture larger than a hex
        Point p1SRC = largeTileLocation(hex.getCoords().getX(), hex.getCoords().getY(), scale);
        p1SRC.x = p1SRC.x % origImgWidth;
        p1SRC.y = p1SRC.y % origImgHeight;
        Point p2SRC = new Point((int) (p1SRC.x + HEX_W * scale), (int) (p1SRC.y + HEX_H * scale));
        Point p2DST = new Point((int) (HEX_W * scale), (int) (HEX_H * scale));

        // hex mask to limit drawing to the hex shape
        // TODO : this is not ideal yet but at least it draws without leaving gaps at any zoom
        graphics2D.drawImage(hexMask, 0, 0, null);
        Composite svComp = graphics2D.getComposite();
        graphics2D.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 1f));

        // paint the right slice from the big pic
        graphics2D.drawImage(scaledImage, 0, 0, p2DST.x, p2DST.y, p1SRC.x, p1SRC.y, p2SRC.x, p2SRC.y, null);

        // Handle wrapping of the image
        if (p2SRC.x > origImgWidth && p2SRC.y <= origImgHeight) {
            graphics2D.drawImage(scaledImage,
                  origImgWidth - p1SRC.x,
                  0,
                  p2DST.x,
                  p2DST.y,
                  0,
                  p1SRC.y,
                  p2SRC.x - origImgWidth,
                  p2SRC.y,
                  null); // paint additional slice on the left side
        } else if (p2SRC.x <= origImgWidth && p2SRC.y > origImgHeight) {
            graphics2D.drawImage(scaledImage,
                  0,
                  origImgHeight - p1SRC.y,
                  p2DST.x,
                  p2DST.y,
                  p1SRC.x,
                  0,
                  p2SRC.x,
                  p2SRC.y - origImgHeight,
                  null); // paint additional slice on the top
        } else if (p2SRC.x > origImgWidth) {
            graphics2D.drawImage(scaledImage,
                  origImgWidth - p1SRC.x,
                  0,
                  p2DST.x,
                  p2DST.y,
                  0,
                  p1SRC.y,
                  p2SRC.x - origImgWidth,
                  p2SRC.y,
                  null); // paint additional slice on the top
            graphics2D.drawImage(scaledImage,
                  0,
                  origImgHeight - p1SRC.y,
                  p2DST.x,
                  p2DST.y,
                  p1SRC.x,
                  0,
                  p2SRC.x,
                  p2SRC.y - origImgHeight,
                  null); // paint additional slice on the left side
            // paint additional slice on the top left side
            graphics2D.drawImage(scaledImage,
                  origImgWidth - p1SRC.x,
                  origImgHeight - p1SRC.y,
                  p2DST.x,
                  p2DST.y,
                  0,
                  0,
                  p2SRC.x - origImgWidth,
                  p2SRC.y - origImgHeight,
                  null);
        }

        graphics2D.setComposite(svComp);
    }

    private DecalArtwork captureDecals(Coords coords, Hex hex, Map<Integer, String> structures, Set<Integer> blank) {
        return featureArtwork.computeIfAbsent(coords, key -> {
            Hex flat = hex.duplicate();
            for (int terrain : GROUND_TERRAINS) {
                flat.removeTerrain(terrain);
            }
            for (int terrain : MODEL_TERRAINS) {
                flat.removeTerrain(terrain);
            }
            for (int terrain : TilesetScenery.SCENERY_TERRAINS) { flat.removeTerrain(terrain); }
            for (int terrain : RAIL_TERRAINS) { flat.removeTerrain(terrain); }
            BufferedImage full = drawDecals(flat);
            BufferedImage tilesetDecals = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            BufferedImage tilesetScenery = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
            Set<String> placedSources = new HashSet<>();
            Scenery scenery = drawScenery(hex, structures, blank, full, tilesetDecals, tilesetScenery, placedSources);
            BufferedImage tileset = drawTileset(hex, structures, placedSources);
            BufferedImage withoutLimbs = null;
            if (flat.containsAnyTerrainOf(Terrains.ARMS, Terrains.LEGS)) {
                flat.removeTerrain(Terrains.ARMS);
                flat.removeTerrain(Terrains.LEGS);
                withoutLimbs = drawDecals(flat);
                drawScenery(hex, structures, blank, withoutLimbs, null, null, null);
            }
            // The GPU chooses the filtered image only after successfully loading the replacement mesh.
            return new DecalArtwork(full, withoutLimbs, tileset, scenery, tilesetDecals, tilesetScenery);
        });
    }

    /**
     * Paints the hex's scenery layers as {@link TilesetScenery#resolve} classifies them, the one classification of the
     * legacy conversion and of this paint: model and other drawn layers into the Classic graphics' overlays (tileset
     * decals and object sprites), untranslated paint onto the surface decals. The pixel-only work stays here:
     * {@code blank} terrain and the native ground transition. Every board drawn is converted, so ROAD level 2 is
     * the Alley finish; and its hex comes without the tokens the conversion took ({@code BoardTiles}), so no layer here
     * is one the conversion took.
     */
    private Scenery drawScenery(Hex hex, Map<Integer, String> structures, Set<Integer> blank,
          BufferedImage decals, BufferedImage tilesetDecals, BufferedImage tilesetScenery, Set<String> placedSources) {
        var resolved = TilesetScenery.resolve(hex, structures, true);
        List<TilesetScenery.Layer> paint = new ArrayList<>();
        Graphics2D graphics = decals.createGraphics();
        Graphics2D original = tilesetDecals == null ? null : tilesetDecals.createGraphics();
        Graphics2D objects = tilesetScenery == null ? null : tilesetScenery.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            if (original != null) { UIUtil.setHighQualityRendering(original); }
            if (objects != null) { UIUtil.setHighQualityRendering(objects); }
            for (var layer : resolved.layers()) {
                if (layer.kind() == TilesetScenery.Kind.SKIPPED || layer.kind() == TilesetScenery.Kind.ROAD_SURFACE) { continue; }
                if (layer.kind() == TilesetScenery.Kind.QUAY) {
                    if (placedSources != null) { placedSources.add(layer.file()); }
                    continue;
                }
                boolean model = layer.kind() == TilesetScenery.Kind.MODEL;
                // Keep ground paint in the tileset's authored order, below structured pavement edges.
                if (original != null && (model || !layer.terrains().contains(Terrains.GROUND_FLUFF))) {
                    // Rows that are all decals (an elevator's level plate) are paint the scene draws in both views.
                    if (!model || !BoardSceneryLayouts.layout(layer.key()).paint()) {
                        (model ? objects : original).drawImage(gpuTileset.image(layer.pick()), 0, 0, null);
                    }
                    placedSources.add(layer.file());
                }
                if (layer.kind() == TilesetScenery.Kind.PAINT) { paint.add(layer); }
            }
            boolean nativeTransition = BoardSurfaceBlend.replacesTransition(hex, structures, blank, resolved.scenery());
            for (var layer : paint) {
                if (!nativeTransition || !layer.pick().has(Terrains.GROUND_FLUFF)) {
                    graphics.drawImage(image(layer.pick(), hex.getLevel()), 0, 0, null);
                }
            }
            return resolved.scenery();
        } finally {
            graphics.dispose();
            if (original != null) { original.dispose(); }
            if (objects != null) { objects.dispose(); }
        }
    }

    private BufferedImage drawDecals(Hex flat) {
        BufferedImage image = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            drawSupers(flat, graphics);
        } finally {
            graphics.dispose();
            gpuTileset.clearHex(flat);
        }
        return image;
    }

    /**
     * The hex's Classic art, as its column shows it: its base, its supers and its orthographic images, without
     * the drops' terrains, whose rims BoardRim shades from the levels, without the bridge, which both graphics
     * draw as geometry, and without roads or rails (their shared native surfaces are drawn), structures or scenery.
     * Scenery retains its original
     * selected artwork in a separate overlay, so both views can put it on roofs and lakebeds instead of baking it into the
     * column top.
     * Pavement uses its full variant: the shared concrete geometry supplies its outline in both GPU views.
     * Water also fills its footprint: a painted shore on the lakebed would duplicate the column's bank wall.
     * Where the woods design draws no trees ({@link BoardTreeDistribution#noTrees}, the 3D woods' rule: No trees or a
     * container yard's skin) there is no woods sprite either.
     */
    private BufferedImage drawTileset(Hex source, Map<Integer, String> structures, Set<String> placedSources) {
        Hex hex = source.duplicate();
        for (int terrain : DROP_TERRAINS) { hex.removeTerrain(terrain); }
        if (BoardTreeDistribution.noTrees(hex)) { BoardTreeDistribution.TREE_TERRAINS.forEach(hex::removeTerrain); }
        for (int terrain : BRIDGE_TERRAINS) { hex.removeTerrain(terrain); }
        for (int terrain : RAIL_TERRAINS) { hex.removeTerrain(terrain); }
        hex.removeTerrain(Terrains.ROAD);
        hex.removeTerrain(Terrains.ROAD_FLUFF);
        if (hex.getAppearance().containsKey("road")) {
            Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
            appearance.remove("road");
            hex.setAppearance(appearance);
        }
        for (int structure : structures.keySet()) {
            for (int terrain : STRUCTURE_TERRAINS.get(structure)) { hex.removeTerrain(terrain); }
        }
        if (hex.containsTerrain(Terrains.PAVEMENT)) {
            hex.addTerrain(new Terrain(Terrains.PAVEMENT, hex.terrainLevel(Terrains.PAVEMENT), true, 63));
            if (BoardSurfaceBlend.hasTransition(hex)) {
                // Concrete covers the substrate's transition, its token's or its ground design's.
                hex.removeTerrain(Terrains.GROUND_FLUFF);
                Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
                appearance.remove("ground");
                hex.setAppearance(appearance);
            }
        }
        if (hex.containsTerrain(Terrains.WATER)) {
            hex.addTerrain(new Terrain(Terrains.WATER, hex.terrainLevel(Terrains.WATER), true, 63));
        }
        BufferedImage image = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            Image base = image(TilesetScenery.tileset().select(hex).base(), hex.getLevel());
            drawBaseTerrain(hex, graphics, base, base, mask(), 1);
            // The hex's designs draw the art their tokens drew, in the tileset's order.
            for (var layer : TilesetScenery.appearanceLayers(hex, true)) {
                if (!placedSources.contains(layer.source())) {
                    graphics.drawImage(image(layer, hex.getLevel()), 0, 0, null);
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** A picked tileset image as a hex at {@code level} shows it ({@link TilesetLevels}); blank without a pick. */
    private Image image(TilesetMatcher.Pick pick, int level) {
        Image image = gpuTileset.image(pick);
        return pick == null ? image : levels.image(image, pick.source(), level);
    }

    /** Draws the tileset's super images for a hex, which it layers over the base terrain. */
    private void drawSupers(Hex hex, Graphics2D graphics) {
        if (hex == null) {
            return;
        }
        List<Image> supers = gpuTileset.getSupers(hex);
        if (supers == null) {
            return;
        }
        for (Image image : supers) {
            if (image != null) {
                graphics.drawImage(image, 0, 0, null);
            }
        }
    }

    private static final int[] DROP_TERRAINS = { Terrains.CLIFF_TOP, Terrains.CLIFF_BOTTOM, Terrains.INCLINE_TOP,
          Terrains.INCLINE_BOTTOM, Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM };

    private static final int[] BRIDGE_TERRAINS = { Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV,
                                                   Terrains.BRIDGE_MUD, Terrains.BRIDGE_ROAD };

    /** Rails: the 3D views draw their track (BoardRail), which their tileset sprites would duplicate. */
    private static final int[] RAIL_TERRAINS = { Terrains.RAIL, Terrains.BRIDGE_RAIL };

    /** Each structure with the terrains describing it, any of which its tileset images may match. */
    private static final Map<Integer, int[]> STRUCTURE_TERRAINS = Map.of(
          Terrains.BUILDING, new int[] { Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_FLUFF,
                Terrains.BLDG_ARMOR, Terrains.BLDG_CLASS },
          Terrains.FUEL_TANK, new int[] { Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV,
                Terrains.FUEL_TANK_MAGN },
          Terrains.INDUSTRIAL, new int[] { Terrains.INDUSTRIAL });

    private static final int[] GROUND_TERRAINS = { Terrains.ROAD, Terrains.ROAD_FLUFF, Terrains.PAVEMENT, Terrains.SAND,
          Terrains.SNOW, Terrains.TUNDRA, Terrains.MUD, Terrains.SWAMP, Terrains.ICE, Terrains.MAGMA, Terrains.FIELDS,
          Terrains.RUBBLE };

    /** These have geometry or tactical markings in 3D; their painted symbols would duplicate that presentation. */
    private static final int[] MODEL_TERRAINS = { Terrains.WATER, Terrains.WATER_FLUFF, Terrains.RAPIDS, Terrains.HAZARDOUS_LIQUID,
          Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_FLUFF, Terrains.BLDG_ARMOR,
          Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV, Terrains.FUEL_TANK_MAGN,
          Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV, Terrains.BRIDGE_REPAIRED,
          Terrains.WOODS, Terrains.JUNGLE, Terrains.FOLIAGE_ELEV, Terrains.INDUSTRIAL, Terrains.ROUGH,
          Terrains.CLIFF_TOP, Terrains.CLIFF_BOTTOM, Terrains.INCLINE_TOP, Terrains.INCLINE_BOTTOM,
          Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM, Terrains.FIRE, Terrains.SMOKE, Terrains.SCREEN,
          Terrains.METAL_CONTENT, Terrains.IMPASSABLE,
          Terrains.ULTRA_SUBLEVEL };

    /** Closing again does nothing. */
    @Override
    public void close() {
        if (gpuTileset == null) { return; }
        clear();
        gpuTileset.close();
        gpuTileset = null;
        levels = null;
        hexMask = null;
    }

}
