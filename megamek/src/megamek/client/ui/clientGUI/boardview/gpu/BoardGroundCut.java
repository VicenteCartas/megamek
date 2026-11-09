/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardModelBounds;

/**
 * Ground that authored scenery digs out of its hex: a dug-in platoon's fighting holes ({@link BoardFeatures#withTrench})
 * or a fortified hex's strongpoint. The model's {@code cut} group lies where the ground was dug
 * ({@link RigidGlb#groundwork}): the hex's top over its triangles gives way to them, so drawing, picking, ground support
 * and plants all see the hole, and the model draws its floor and walls ({@link BoardSurface.Finish#DUG}). Its
 * {@code stands} group marks where troops resting in the hex stand, where a vehicle parks in it ({@link InfantryMotion})
 * and where a vehicle that could go hull down there waits ({@link #hide}). A model made of numbered parts, as a platoon's
 * fighting holes, digs and draws only those its feature shows ({@link BoardScene.Feature#parts}).
 * Only the Enhanced ground is dug: a Classic column keeps its flat top, which hides what the model has below it. Scenery
 * on a bridge or on ice digs nothing. Rubble shares the earthworks' footprint fit without digging ground.
 */
final class BoardGroundCut {
    /**
     * What a stand is for, by its empty's name: a trooper's place ({@code stand-N}), a transport's in a vehicle pit
     * ({@code vehicle-N}), or where a vehicle that could go hull down in the pit, but has not, waits ({@code hide-N}).
     */
    enum Kind { TROOP, VEHICLE, HIDE }

    /**
     * A place to stand: its offset from the hex centre in world units, the heading a member faces there, its kind and
     * its number among them; a trooper's is the formation slot it is for ({@link InfantryVisual#slot}).
     */
    record Stand(float x, float y, float heading, Kind kind, int number) { }

    private static final Map<String, RigidGlb.Groundwork> MODELS = new ConcurrentHashMap<>();
    private static final String RUBBLE = "scenery/rubble/";
    /** Metres of clearance from a receding rim, and burial of a fitted pile's thin base into the ground. */
    private static final float RUBBLE_MARGIN = .15f, RUBBLE_EMBED = .1f;
    /** Grounds whose dug earth is the model's own: soil, under grass, snow, jungle or pavement as on bare dirt. */
    private static final Set<BoardScene.Surface> SOIL = EnumSet.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT,
          BoardScene.Surface.SNOW, BoardScene.Surface.TROPICAL, BoardScene.Surface.CONCRETE);
    /** The default of a model's earth colour slot ({@code #808080}): earth in that colour stays as built. */
    private static final String EARTH_DEFAULT = "#808080";
    private static final float EARTH = GpuColor.linear(128 / 255f);
    /** Each ground family's colour as dirt's, per linear channel: its mean albedo over dirt's; soil's is dirt's own. */
    private static final Map<BoardScene.Surface, float[]> GROUNDS = new ConcurrentHashMap<>();

    private BoardGroundCut() { }

    static void reload() {
        MODELS.clear();
        GROUNDS.clear();
    }

    /**
     * The colours of scenery dug into {@code tile}: its first colour slot, the earth, takes the colour of the ground drawn
     * there, each family by its share of the hex's cover (sand over another theme, a desert transition), as that
     * family's mean albedo stands to dirt's. A trench digs red earth on Mars, grey on the Moon and black on a volcano,
     * without a texture of its own; on soil it keeps the model's own colours.
     */
    static BoardDecoration.Colours earth(BoardScene.Tile tile) {
        var cover = tile.groundCover() == null ? BoardSurfaceBlend.solid(tile.surface()) : tile.groundCover();
        float[] colour = new float[3];
        float total = 0;
        for (var family : BoardScene.Surface.values()) {
            float weight = cover.weight(family);
            if (weight <= 0) { continue; }
            float[] ground = GROUNDS.computeIfAbsent(family, BoardGroundCut::ground);
            for (int channel = 0; channel < 3; channel++) { colour[channel] += weight * ground[channel]; }
            total += weight;
        }
        if (total <= 0) { return BoardDecoration.Colours.NONE; }
        StringBuilder hex = new StringBuilder("#");
        for (int channel = 0; channel < 3; channel++) {
            hex.append(String.format("%02x", Math.round(GpuColor.display(Math.min(1, EARTH * colour[channel] / total)) * 255)));
        }
        return hex.toString().equals(EARTH_DEFAULT) ? BoardDecoration.Colours.NONE : BoardDecoration.Colours.of(hex.toString());
    }

    /** The colours {@code feature} draws in on {@code tile}: its own, or, an earthwork's without any, its ground's. */
    static BoardDecoration.Colours colours(BoardScene.Tile tile, BoardScene.Feature feature) {
        return feature.colours().equals(BoardDecoration.Colours.NONE) && feature.decoration() == null
              && digs(feature.asset()) ? earth(tile) : feature.colours();
    }

    private static float[] ground(BoardScene.Surface family) {
        if (SOIL.contains(family)) { return new float[] { 1, 1, 1 }; }
        float[] dirt = albedo(GpuTerrain.groundMaterial(BoardScene.Surface.DIRT));
        float[] ground = albedo(GpuTerrain.groundMaterial(family));
        return new float[] { ground[0] / dirt[0], ground[1] / dirt[1], ground[2] / dirt[2] };
    }

    /** A terrain surface material's mean albedo, linear RGB. */
    private static float[] albedo(String material) {
        File file = new File(GpuMaterialMaps.boardTextures(), "terrain/surfaces/" + material + "_albedo.png");
        BufferedImage image;
        try {
            image = ImageIO.read(file);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read " + file, error);
        }
        float[] sum = new float[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                for (int channel = 0; channel < 3; channel++) {
                    sum[channel] += GpuColor.linear((rgb >> (16 - 8 * channel) & 255) / 255f);
                }
            }
        }
        for (int channel = 0; channel < 3; channel++) { sum[channel] /= image.getWidth() * image.getHeight(); }
        return sum;
    }

    /**
     * Digs {@code tile}'s scenery into its {@code faces}: the faces {@code ground} accepts give way over each cut
     * triangle, which joins them as DUG ground.
     *
     * @return the stands of the scenery dug in, in the tile's feature order
     */
    static List<Stand> dig(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces, BoardRelief relief,
          Predicate<BoardSurface.Face> ground) {
        if (tile.frozen() || BoardBridge.standing(tile) != null) { return List.of(); }
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        List<Stand> stands = new ArrayList<>();
        for (var feature : tile.features()) {
            if (!feature.authoredPlacement() || feature.decoration() != null) { continue; }
            var model = model(feature.asset());
            if (model.cut().isEmpty() && model.stands().isEmpty()) { continue; }
            // Where the terrain stands the model (GpuTerrain#placeAuthored): on its hex's ground at the centre.
            Matrix4 placement = BoardFeatures.authoredTransform(cx + feature.x() * BoardGeometry.hexScale(),
                  cy + feature.y() * BoardGeometry.hexScale(), base(faces, cx, cy, BoardGeometry.groundZ(tile)),
                  fitted(scene, tile, feature));
            List<BoardSurface.Face> open = new ArrayList<>(), kept = new ArrayList<>(), dug = new ArrayList<>();
            faces.forEach(face -> (ground.test(face) ? open : kept).add(face));
            for (int t = 0; t < model.cut().size(); t++) {
                if (!shown(model.cutParts()[t], feature.parts())) { continue; }
                Vector3[] triangle = model.cut().get(t);
                Vector3 a = new Vector3(triangle[0]).mul(placement), b = new Vector3(triangle[1]).mul(placement);
                Vector3 c = new Vector3(triangle[2]).mul(placement);
                // Counter-clockwise seen from above, so each side's outward normal is on its right.
                if ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) < 0) { Vector3 swap = b; b = c; c = swap; }
                subtract(open, List.of(side(a, b), side(b, c), side(c, a)), relief, a, b, c);
                dug.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.DUG));
            }
            faces.clear();
            faces.addAll(kept);
            faces.addAll(open);
            faces.addAll(dug);
            model.stands().forEach((name, frame) -> {
                Kind kind = name.startsWith("vehicle-") ? Kind.VEHICLE : name.startsWith("hide-") ? Kind.HIDE : Kind.TROOP;
                int number = Integer.parseInt(name.substring(name.lastIndexOf('-') + 1));
                // A numbered part's trooper stands with it: a hidden fighting hole holds no one.
                if (kind == Kind.TROOP && !shown(number, feature.parts())) { return; }
                Matrix4 stand = new Matrix4(placement).mul(frame);
                Vector3 position = stand.getTranslation(new Vector3());
                Vector3 facing = new Vector3(Vector3.Y).rot(stand);
                stands.add(new Stand(position.x - cx, position.y - cy, (float) Math.toDegrees(Math.atan2(facing.x, facing.y)),
                      kind, number));
            });
        }
        return List.copyOf(stands);
    }

    /** The height an earthwork stands at, at (x, y): its hex's ground there as it was before any of it was dug. */
    static float base(List<BoardSurface.Face> faces, float x, float y, float fallback) {
        return BoardSurface.sampleHeight(faces.stream().filter(face -> face.finish() != BoardSurface.Finish.DUG).toList(),
              x, y, fallback);
    }

    /**
     * {@code feature} fitted to its hex's top: an earthwork or rubble whose model reaches past where the top stops short of a
     * step, cliff or shore ({@link BoardRelief#topInset}) shrinks across, about its anchor, until it keeps inside, as
     * deep as before; a level hex's top takes it whole. Rubble leaves a small rim margin and beds its base into the
     * ground only when fitted; ice and bridge decks keep its authored placement. Every view, ground cut and plant
     * clearance uses this fit.
     */
    static BoardScene.Feature fitted(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        float[] outline = feature.authoredPlacement() && feature.decoration() == null
              ? model(feature.asset()).outline() : new float[0];
        if (outline.length == 0) { return feature; }
        boolean rubble = feature.asset().startsWith(RUBBLE);
        if (rubble && (tile.frozen() || BoardBridge.standing(tile) != null)) { return feature; }
        BoardRelief relief = new BoardRelief(scene, tile, BoardSurface.ramps(scene, tile));
        Vector3 anchor = new Vector3(feature.x(), feature.y(), 0).scl(BoardGeometry.hexScale());
        Matrix4 frame = BoardFeatures.authoredTransform(anchor.x, anchor.y, 0, feature);
        float fit = 1;
        Vector3[] edges = edges(tile.coords());
        for (int e = 0; e < 6; e++) {
            float inset = relief.topInset(e);
            // A level neighbour supports the original pile; only a receding rim calls for a smaller footprint.
            if (rubble && inset <= 0) { continue; }
            float apothem = edges[e].len();
            Vector3 middle = new Vector3(edges[e]).scl(1 / apothem);
            float reach = 0;
            for (int i = 0; i < outline.length; i += 2) {
                Vector3 point = new Vector3(outline[i], outline[i + 1], 0).mul(frame).sub(anchor);
                reach = Math.max(reach, point.x * middle.x + point.y * middle.y);
            }
            float room = apothem - inset - (anchor.x * middle.x + anchor.y * middle.y)
                  - (rubble ? BoardRelief.metres(RUBBLE_MARGIN) : 0);
            if (reach > 0) { fit = Math.min(fit, room / reach); }
        }
        if (fit >= 1) { return feature; }
        var stretch = feature.stretch();
        fit = Math.max(.2f, fit);
        float elevation = feature.elevation() - (rubble ? BoardRelief.metres(RUBBLE_EMBED)
              / (BoardGeometry.MODEL_LEVEL_HEIGHT * BoardGeometry.hexScale()) : 0);
        return new BoardScene.Feature(feature.asset(), feature.x(), feature.y(), feature.rotation(), feature.scale(),
              feature.height(), elevation, feature.kind(), feature.bridgeExits(), true, null,
              new BoardDecoration.Stretch(stretch.x() * fit, stretch.y() * fit, stretch.z()), feature.colours(),
              feature.parts());
    }

    /** Whether numbered part {@code part} ({@link RigidGlb#part}; -1 for none) is among {@code parts}: 0 shows all. */
    static boolean shown(int part, int parts) {
        return parts == 0 || part < 0 || (parts >> part & 1) != 0;
    }

    /** Hides the numbered parts of {@code instance}'s model that {@code parts} does not show. */
    static void showParts(com.badlogic.gdx.graphics.g3d.ModelInstance instance, int parts) {
        if (parts == 0) { return; }
        for (var node : instance.nodes) {
            boolean shown = shown(RigidGlb.part(node.id), parts);
            for (var part : node.parts) { part.enabled = shown; }
        }
    }

    /** How far either side of its hide's bearing a waiting vehicle may park, degrees, and in what steps. */
    private static final int HIDE_FAN = 40, HIDE_STEP = 5;

    /**
     * Where a vehicle that could go hull down in its hex's vehicle pit, but has not, waits: parked as near the hex's
     * border as its hull's corners ({@code hull}, world x and y with the vehicle at the hex centre) keep inside the hex,
     * on a bearing within {@link #HIDE_FAN} of the earthwork's hide ({@link Kind#HIDE}), never further out than the hide.
     * It keeps its facing; what of it the hide cannot take hangs over the pit. Null where the hex has no hide.
     *
     * @return the vehicle's offset from the hex centre, world units
     */
    static Vector3 hide(BoardScene.Tile tile, List<Stand> stands, List<Vector3> hull) {
        Stand hide = stands.stream().filter(stand -> stand.kind() == Kind.HIDE).findFirst().orElse(null);
        if (hide == null) { return null; }
        Vector3 centre = BoardGeometry.center(tile.coords(), 0);
        float limit = (float) Math.hypot(hide.x(), hide.y()), bearing = MathUtils.atan2(hide.y(), hide.x());
        Vector3 best = new Vector3();
        for (int offset = 0; offset <= HIDE_FAN; offset += HIDE_STEP) {
            for (int side : offset == 0 ? new int[] { 1 } : new int[] { 1, -1 }) {
                float angle = bearing + side * offset * MathUtils.degreesToRadians;
                Vector3 toward = new Vector3(MathUtils.cos(angle), MathUtils.sin(angle), 0);
                float reach = limit;
                for (Vector3 edge : edges(tile.coords())) {
                    float apothem = edge.len();
                    Vector3 normal = new Vector3(edge).scl(1 / apothem);
                    float approach = toward.dot(normal);
                    if (approach <= 1e-4f) { continue; }
                    for (Vector3 corner : hull) {
                        float inside = apothem - (corner.x - centre.x) * normal.x - (corner.y - centre.y) * normal.y;
                        reach = Math.min(reach, inside / approach);
                    }
                }
                // The furthest out; nearer the hide's own bearing where two reach as far.
                if (reach > best.len() + 1e-3f) { best.set(toward).scl(Math.max(0, reach)); }
            }
        }
        return best;
    }

    /** From a hex's centre to the middle of each of its edges, world units. */
    private static Vector3[] edges(megamek.common.board.Coords coords) {
        Vector3 centre = BoardGeometry.center(coords, 0);
        Vector3[] edges = new Vector3[6];
        for (int e = 0; e < 6; e++) {
            edges[e] = BoardGeometry.corner(coords, 0, e).add(BoardGeometry.corner(coords, 0, e + 1)).scl(.5f).sub(centre);
        }
        return edges;
    }

    /** Subtracts one cut triangle from the faces near it; the rest never meet it. */
    private static void subtract(List<BoardSurface.Face> faces, List<BoardSurface.Boundary> sides, BoardRelief relief,
          Vector3 a, Vector3 b, Vector3 c) {
        float minX = Math.min(a.x, Math.min(b.x, c.x)), maxX = Math.max(a.x, Math.max(b.x, c.x));
        float minY = Math.min(a.y, Math.min(b.y, c.y)), maxY = Math.max(a.y, Math.max(b.y, c.y));
        List<BoardSurface.Face> near = new ArrayList<>(), far = new ArrayList<>();
        for (var face : faces) {
            boolean apart = Math.max(face.a().x, Math.max(face.b().x, face.c().x)) < minX
                  || Math.min(face.a().x, Math.min(face.b().x, face.c().x)) > maxX
                  || Math.max(face.a().y, Math.max(face.b().y, face.c().y)) < minY
                  || Math.min(face.a().y, Math.min(face.b().y, face.c().y)) > maxY;
            (apart ? far : near).add(face);
        }
        if (near.isEmpty()) { return; }
        BoardSurface.subtract(near, sides, relief);
        faces.clear();
        faces.addAll(far);
        faces.addAll(near);
    }

    /** The vertical plane through a counter-clockwise triangle's side from a to b, positive outside. */
    private static BoardSurface.Boundary side(Vector3 a, Vector3 b) {
        float nx = b.y - a.y, ny = a.x - b.x;
        return point -> nx * (point.x - a.x) + ny * (point.y - a.y);
    }

    /** Whether {@code asset}'s model digs into its hex: whether it has a cut. */
    static boolean digs(String asset) {
        return !model(asset).cut().isEmpty();
    }

    /** A model's cut and stands, none where the asset has no readable model file (it draws the marked box). */
    private static RigidGlb.Groundwork model(String asset) {
        return MODELS.computeIfAbsent(asset, key -> {
            File file = BoardModelBounds.file(new File(Configuration.dataDir(), "models/board"), key);
            try {
                return file.isFile() ? RigidGlb.groundwork(file, key.startsWith(RUBBLE)) : RigidGlb.Groundwork.NONE;
            } catch (RuntimeException unreadable) {
                // A worker interrupted by a closing board fails its read: that is no answer to keep.
                if (Thread.currentThread().isInterrupted()) { throw unreadable; }
                return RigidGlb.Groundwork.NONE;
            }
        });
    }
}
