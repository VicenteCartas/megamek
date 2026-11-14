/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Surface cover at a world position. Rendering and grass placement query the same immutable board snapshot. */
public final class BoardSurfaceBlend {
    static final float WIDTH_METRES = 7f;
    /** The farthest the receiving ground reaches up a column above its foot; its tongues reach a fifth of it to all. */
    static final float FOOT_METRES = 2.7f;
    /** How far lava, molten or crusted, melts snow and burns turf beside it. */
    static final float HEAT_METRES = 5f;
    // Render-only covers derived from BoardLiquid; the board's terrain families and rules remain unchanged.
    static final int CRUST = BoardScene.Surface.values().length, BANK = CRUST + 1, FAMILIES = BANK + 1;

    record Cover(float grass, float dirt, float sand, float rock, float concrete, float snow, float lunar,
          float fungus, float desert, float mars, float volcano, float tropical, float crust, float bank, float interpolation) {
        Cover(float grass, float dirt, float sand, float rock, float concrete, float snow, float lunar,
              float fungus, float desert, float mars, float volcano, float tropical, float crust, float bank) {
            this(grass, dirt, sand, rock, concrete, snow, lunar, fungus, desert, mars, volcano, tropical, crust, bank, 0);
        }
        float weight(BoardScene.Surface family) { return weight(family.ordinal()); }

        float weight(int family) {
            return switch (family) {
                case 0 -> grass;
                case 1 -> dirt;
                case 2 -> sand;
                case 3 -> rock;
                case 4 -> concrete;
                case 5 -> snow;
                case 6 -> lunar;
                case 7 -> fungus;
                case 8 -> desert;
                case 9 -> mars;
                case 10 -> volcano;
                case 11 -> tropical;
                case 12 -> crust;
                case 13 -> bank;
                default -> throw new IllegalArgumentException("Surface cover " + family);
            };
        }

        int mask() {
            int mask = 0;
            for (int family = 0; family < FAMILIES; family++) {
                if (weight(family) > 0) { mask |= 1 << family; }
            }
            return mask;
        }
    }

    private static final Cover[] SOLID = new Cover[FAMILIES];
    static {
        for (int family = 0; family < FAMILIES; family++) {
            float[] weights = new float[FAMILIES];
            weights[family] = 1;
            SOLID[family] = cover(weights, 1, 0);
        }
    }

    private BoardSurfaceBlend() { }

    static Cover solid(BoardScene.Surface family) { return SOLID[family.ordinal()]; }
    static Cover solid(int family) { return SOLID[family]; }

    /** The 2D tileset's ground_fluff transitions: desert, grass, tropical grass, Mars and Moon. */
    public static boolean hasTransition(Hex hex) { return transitionFamily(hex) >= 0; }

    static int transitionFamily(Hex hex) {
        if (hex.getAppearance().containsKey("ground")) { hex = megamek.common.board.BoardEditorBlueprint.get().artwork(hex, "ground"); }
        var terrain = hex.getTerrain(Terrains.GROUND_FLUFF);
        if (terrain == null || !terrain.hasExitsSpecified() || terrain.getExits() < 1 || terrain.getExits() > 5) {
            return -1;
        }
        return switch (terrain.getLevel()) {
            case 1 -> BoardScene.Surface.DESERT.ordinal();
            case 2 -> BoardScene.Surface.GRASS.ordinal();
            case 3 -> BoardScene.Surface.TROPICAL.ordinal();
            case 4 -> BoardScene.Surface.MARS.ordinal();
            case 5 -> BoardScene.Surface.LUNAR.ordinal();
            default -> -1;
        };
    }

    /** Capture authored material proportions once; the renderer never reads or changes the source hex. */
    static Cover capture(Hex hex) {
        if (hex.getAppearance().containsKey("ground")) { hex = megamek.common.board.BoardEditorBlueprint.get().artwork(hex, "ground"); }
        int base = BoardFeatures.surface(hex).ordinal(), target = transitionFamily(hex);
        // Natural transitions describe the substrate; pavement covers it with concrete.
        if (base == BoardScene.Surface.CONCRETE.ordinal()) { return solid(base); }
        // Sand is one gameplay surface treatment, independent of the theme beneath it. Snow/paving/magma
        // retain their existing precedence. Authored theme gradients must not dilute the SAND gameplay cue.
        if (hex.containsTerrain(Terrains.SAND) && base != BoardScene.Surface.SNOW.ordinal()
              && base != BoardScene.Surface.CONCRETE.ordinal() && !hex.containsTerrain(Terrains.MAGMA)) {
            // Retain the supporting material in the palette. Small wind-scoured windows are resolved in the
            // shader, so a uniform sand flat still needs only six triangles rather than a mesh for every patch.
            float[] weights = new float[FAMILIES];
            weights[base] = .06f;
            weights[BoardScene.Surface.SAND.ordinal()] += .94f;
            return cover(weights, 1, 0);
        }
        if (target < 0 || target == base) { return solid(base); }
        // Five evenly spaced interior points leave some of both materials at every authored strength.
        float amount = hex.getTerrain(Terrains.GROUND_FLUFF).getExits() / 6f;
        float[] weights = new float[FAMILIES];
        weights[base] = 1 - amount;
        weights[target] += amount;
        return cover(weights, 1, 1);
    }

    private static Cover cover(BoardScene.Tile tile) {
        return tile.liquid().volcanic() ? solid(family(tile)) : tile.groundCover();
    }

    /** Same metre-based field as sandExposure in terrain-hexes.glsl. Sand stays dominant between sparse openings. */
    static float sandExposure(float xMetres, float yMetres) {
        float field = .75f * BoardRelief.noise(xMetres / 6, yMetres / 6)
              + .25f * BoardRelief.noise(xMetres / 1.7f + 19, yMetres / 1.7f - 7);
        return BoardRelief.smooth((field - .60f) / .14f);
    }

    /** The tongues the ground below climbs a column in: one world field, the same as footCover's in terrain-materials.glsl. */
    static float footTongue(float xMetres, float yMetres) {
        return .7f * BoardRelief.noise(xMetres / 5.3f + 37, yMetres / 5.3f - 23)
              + .3f * BoardRelief.noise(xMetres / 1.7f - 11, yMetres / 1.7f + 29);
    }

    /** How much of the ground below reaches this far (metres) above its foot; footCover in GLSL cuts the same per pixel. */
    static float footReach(float above, float tongue) {
        return 1 - BoardRelief.smooth(above / (FOOT_METRES * (.2f + .8f * tongue)));
    }

    /**
     * A column vertex's cover before the ground below is cut back by height, and that ground's share of it (unnormalized),
     * which the shader cuts per pixel (footCover).
     */
    record Footing(Cover cover, Cover foot) { }

    static final Cover NO_FOOT = new Cover(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Loose surface deposits do not turn the supporting cliff into sandstone or erase the theme's geology. */
    private static Cover cover(BoardScene.Tile tile, float z) {
        Cover top = cover(tile);
        if (top.sand() == 0 || tile.surface() == BoardScene.Surface.SAND || tile.liquid().present()) { return top; }
        float exposed = BoardRelief.smooth((BoardGeometry.groundZ(tile) - z) / BoardRelief.metres(1.2f));
        if (exposed <= 0) { return top; }
        float[] weights = new float[FAMILIES];
        for (int family = 0; family < FAMILIES; family++) { weights[family] = top.weight(family); }
        float mineral = top.sand() * exposed;
        weights[BoardScene.Surface.SAND.ordinal()] -= mineral;
        weights[tile.surface().ordinal()] += mineral;
        return cover(weights, 1, top.interpolation());
    }

    /** What lava's heat leaves of a family: snow melts off its rock, turf burns to its soil; the rest stays. */
    static int melted(int family) {
        if (family == BoardScene.Surface.SNOW.ordinal()) { return BoardScene.Surface.ROCK.ordinal(); }
        if (family == BoardScene.Surface.GRASS.ordinal() || family == BoardScene.Surface.TROPICAL.ordinal()) {
            return BoardScene.Surface.DIRT.ordinal();
        }
        return family;
    }

    static int family(BoardScene.Tile tile) {
        return tile.liquid().volcanic() ? tile.liquid().molten() ? BANK : CRUST : tile.surface().ordinal();
    }

    static boolean natural(BoardScene.Tile tile) {
        return tile != null && !tile.ultraSublevel() && !tile.backdrop().present()
              && !tile.liquid().present() && !tile.liquid().volcanic() && !tile.frozen()
              && (tile.roadExits() == 0 || BoardRoad.rendered(tile)) && tile.surface() != BoardScene.Surface.CONCRETE
              && !tile.slab();
    }

    /** Land ice is a coat over its ground (GpuIceShader), so that ground blends like any other. */
    private static boolean blendable(BoardScene.Tile tile) {
        return tile != null && !tile.ultraSublevel() && !tile.backdrop().present()
              && (!tile.liquid().present() || tile.liquid().volcanic() && !tile.frozen())
              && (tile.roadExits() == 0 || BoardRoad.rendered(tile)) && !tile.slab();
    }

    /** Uniform interiors retain the ordinary family material and incur no extra maps or vertex attributes. */
    static boolean boundary(BoardScene scene, BoardScene.Tile tile) {
        if (tile == null || tile.ultraSublevel() || tile.backdrop().present()) { return false; }
        Cover own = cover(tile);
        // A building or lake ice blocks neighbouring cover, but cannot erase its own authored ground treatment.
        if (!own.equals(solid(family(tile)))) { return true; }
        if (!blendable(tile)) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.neighbor(tile, direction);
            if (contact(tile, next) && !cover(next).equals(own)) { return true; }
        }
        return !spans(scene, tile).isEmpty();
    }

    /** A level rock span resting on a bank, and the family of its shell, which the bank's cover gives way to. */
    private record Span(BoardNaturalBridge.BankJoin join, int family) { }

    /** The level rock spans on the tile whose shell is not already its whole cover. */
    private static List<Span> spans(BoardScene scene, BoardScene.Tile tile) {
        if (!blendable(tile) || tile.liquid().present()) { return List.of(); }
        List<Span> spans = new ArrayList<>(0);
        for (int direction = 0; direction < 6; direction++) {
            var join = BoardNaturalBridge.bankApproach(scene, tile, direction);
            if (join == null) { continue; }
            var span = scene.neighbor(tile, direction);
            int family = BoardNaturalBridge.shell(BoardBridge.deck(scene, span).surface()).ordinal();
            if (cover(tile).weight(family) < 1) { spans.add(new Span(join, family)); }
        }
        return spans;
    }

    private static boolean contact(BoardScene.Tile a, BoardScene.Tile b) {
        if (!blendable(b)) { return false; }
        if (a.liquid().present() && !a.liquid().volcanic()) {
            // The bank includes the submerged continuation of higher land, including tall cliffs.
            return natural(b) && (b.elevation() >= a.elevation()
                  || BoardGeometry.tuning().stepsBetweenTops() && a.elevation() - b.elevation() == 1);
        }
        // Constructed faces keep their material all the way to the adjoining ground.
        return a == b || a.surface() != BoardScene.Surface.CONCRETE && b.surface() != BoardScene.Surface.CONCRETE;
    }

    static boolean cliffBoundary(BoardScene scene, BoardScene.Tile tile) { return boundary(scene, tile); }

    /** Cliff and ground agree at the foot. The lower cover reaches only a short way up the exposed column. */
    static Cover sampleCliff(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        return new Sampler(scene, owner).sampleCliff(x, y, z);
    }

    private static boolean reaches(BoardScene.Tile tile, float z) {
        return blendable(tile) && BoardGeometry.groundZ(tile) + BoardRelief.metres(.05f) >= z;
    }

    /** The footprint, rather than the emitting mesh, owns the query; shared positions therefore agree. */
    static Cover sample(BoardScene scene, BoardScene.Tile owner, float x, float y, float z) {
        return new Sampler(scene, owner).sample(x, y, z);
    }

    private static boolean water(BoardScene.Tile tile) {
        return tile != null && tile.liquid().present() && !tile.liquid().volcanic() && !tile.frozen();
    }

    /**
     * Cover queries for the vertices of one hex. Which tiles blend into a tile never depends on the queried point,
     * so each tile's contacts are derived once, and a position asked again (shared vertices, subdivision probes)
     * returns its earlier answer. Every result equals the single static query.
     */
    static final class Sampler {
        private record Position(float x, float y, float z) { }

        /** Point-independent facts about one tile; the arrays keep the query order: itself, then directions 0-5. */
        private record Contacts(boolean blendable, boolean water, boolean ownerContact, BoardScene.Tile[] touching,
              int bedFamily, BoardScene.Tile[] pools, BoardScene.Tile[] lava) { }

        private final BoardScene scene;
        private final BoardScene.Tile owner;
        private final BoardConcrete coast;
        private final boolean blendableOwner, waterOwner;
        private final List<Span> spans;
        private final Map<BoardScene.Tile, Contacts> contacts = new IdentityHashMap<>();
        private final Map<Position, Cover> ground = new HashMap<>(), cliffs = new HashMap<>();

        Sampler(BoardScene scene, BoardScene.Tile owner) {
            this.scene = scene;
            this.owner = owner;
            coast = BoardConcrete.of(scene);
            blendableOwner = blendable(owner);
            waterOwner = water(owner);
            spans = spans(scene, owner);
        }

        Cover sample(float x, float y, float z) {
            Position key = new Position(x, y, z);
            Cover cover = ground.get(key);
            if (cover == null) {
                cover = scorched(onto(sampleGround(x, y, z).cut(), x, y, z), x, y, z);
                ground.put(key, cover);
            }
            return cover;
        }

        /** sampleCliff before the ground below is cut back by height, with that ground's share (Footing). */
        Footing sampleFoot(float x, float y, float z) {
            var at = blendableOwner ? BoardGeometry.tile(scene, x, y) : null;
            if (at == null || at.liquid().present() && !at.liquid().volcanic()) {
                return new Footing(sampleCliff(x, y, z), NO_FOOT);
            }
            // A dry column takes the ground's query, as sampleColumn does.
            var sampled = sampleGround(x, y, z);
            return new Footing(scorched(onto(sampled.uncut(), x, y, z), x, y, z), scorched(sampled.foot(), x, y, z));
        }

        Cover sampleCliff(float x, float y, float z) {
            Position key = new Position(x, y, z);
            Cover cover = cliffs.get(key);
            if (cover == null) {
                // A dry column takes the ground's query (#sample), which lava's heat has already reached.
                cover = sampleColumn(x, y, z);
                cliffs.put(key, cover);
            }
            return cover;
        }

        private Sampled sampleGround(float x, float y, float z) {
            if (!blendableOwner && !waterOwner) { return Sampled.of(cover(owner, z)); }
            var at = BoardGeometry.tile(scene, x, y);
            if (at == null) { return Sampled.of(cover(owner, z)); }
            Contacts around = contacts(at);
            if (!around.blendable() && (!at.liquid().present() || at.frozen())) { return Sampled.of(cover(owner, z)); }
            if ((!at.liquid().present() || at.liquid().volcanic() || owner.surface() == BoardScene.Surface.CONCRETE)
                  && !around.ownerContact()) {
                return Sampled.of(cover(owner, z));
            }
            if (around.water()) { return Sampled.of(sampleWater(at, around, x, y, z)); }
            return sampleAt(at, around, cover(owner, z), x, y, z, false);
        }

        /**
         * Across a level rock span's passage the bank's cover gives way to the span's shell over a broken band,
         * entirely by the bank's edge where the span's top begins, so neither the ground nor its grass ends at a line.
         */
        private Cover onto(Cover cover, float x, float y, float z) {
            float metre = BoardRelief.metres(1);
            for (var span : spans) {
                var passage = span.join().passage();
                if (z < passage.level() - metre * .5f) { continue; }
                float lateral = span.join().weight(new Vector3(x, y, z));
                if (lateral <= 0) { continue; }
                // How far inside the bank's edge towards the span the point lies.
                float inside = passage.length() / 2 - ((x - passage.x()) * passage.nx() + (y - passage.y()) * passage.ny());
                float warp = (BoardRelief.noise(x / (metre * 2.7f) + 31, y / (metre * 2.7f) - 13) - .5f) * 3 * metre;
                float share = lateral * (1 - BoardRelief.smooth((inside - 1.5f * metre + warp) / (6 * metre)));
                if (share <= 0) { continue; }
                float[] weights = new float[FAMILIES];
                for (int family = 0; family < FAMILIES; family++) { weights[family] = cover.weight(family) * (1 - share); }
                weights[span.family()] += share;
                cover = cover(weights, 1, cover.interpolation() * (1 - share));
            }
            return cover;
        }

        private Cover sampleColumn(float x, float y, float z) {
            if (!blendableOwner) { return scorched(cover(owner, z), x, y, z); }
            var at = BoardGeometry.tile(scene, x, y);
            if (at == null) { return cover(owner, z); }
            if (!at.liquid().present() || at.liquid().volcanic()) { return sample(x, y, z); }
            BoardScene.Tile column = null;
            float nearest = Float.POSITIVE_INFINITY;
            for (int direction = -1; direction < 6; direction++) {
                var candidate = direction < 0 ? at : scene.neighbor(at, direction);
                if (!reaches(candidate, z)) { continue; }
                float distance = distance(candidate.coords(), x, y);
                if (distance < nearest) { column = candidate; nearest = distance; }
            }
            return scorched(column == null ? cover(owner, z)
                  : sampleAt(column, contacts(column), cover(column, z), x, y, z, true).cut(), x, y, z);
        }

        /**
         * Lava melts the snow beside it off its rock and burns turf to its soil (#melted): fully at the lava's edge, in
         * broken tongues to HEAT_METRES out, and less far up a column, which the heat reaches across less of.
         */
        private Cover scorched(Cover cover, float x, float y, float z) {
            float snow = cover.snow(), turf = cover.grass() + cover.tropical();
            if (snow + turf <= 0) { return cover; }
            var at = BoardGeometry.tile(scene, x, y);
            if (at == null) { return cover; }
            float metre = BoardRelief.metres(1), heat = 0;
            for (BoardScene.Tile lava : contacts(at).lava()) {
                float reach = metre * HEAT_METRES;
                float rise = Math.max(0, z - BoardGeometry.groundZ(lava));
                float distance = Math.max(0, distance(lava.coords(), x, y)) + rise * .5f;
                // The tongues fray the heat's reach, never its edge: nothing survives against the lava.
                float warp = (BoardRelief.noise(x / (metre * 2.3f) + 41, y / (metre * 2.3f) - 17) - .5f) * reach * .7f;
                distance += warp * BoardRelief.smooth(distance / reach);
                heat = Math.max(heat, 1 - BoardRelief.smooth(distance / reach));
            }
            if (heat <= 0) { return cover; }
            float[] weights = new float[FAMILIES];
            for (int family = 0; family < FAMILIES; family++) {
                float weight = cover.weight(family);
                int remains = melted(family);
                weights[family] += remains == family ? weight : weight * (1 - heat);
                if (remains != family) { weights[remains] += weight * heat; }
            }
            return cover(weights, 1, cover.interpolation());
        }

        private Contacts contacts(BoardScene.Tile at) {
            Contacts around = contacts.get(at);
            if (around == null) {
                around = derive(at);
                contacts.put(at, around);
            }
            return around;
        }

        private Contacts derive(BoardScene.Tile at) {
            boolean water = water(at);
            List<BoardScene.Tile> touching = new ArrayList<>(7), pools = new ArrayList<>(7), lava = new ArrayList<>(0);
            float[] banks = new float[FAMILIES];
            for (int direction = -1; direction < 6; direction++) {
                BoardScene.Tile tile = direction < 0 ? at : scene.neighbor(at, direction);
                if (contact(at, tile)) {
                    touching.add(tile);
                    if (water) {
                        Cover cover = cover(tile);
                        for (int family = 0; family < FAMILIES; family++) { banks[family] += cover.weight(family); }
                    }
                }
                if (water && water(tile) && tile.elevation() == at.elevation()) { pools.add(tile); }
                if (tile != null && tile.liquid().volcanic()) { lava.add(tile); }
            }
            int bedFamily = 0;
            for (int family = 1; family < FAMILIES; family++) {
                if (banks[family] > banks[bedFamily]) { bedFamily = family; }
            }
            return new Contacts(blendable(at), water, contact(owner, at), touching.toArray(BoardScene.Tile[]::new),
                  bedFamily, pools.toArray(BoardScene.Tile[]::new), lava.toArray(BoardScene.Tile[]::new));
        }

        /** Water hexes share their bank/bed fields across their boundaries, independently of triangle ownership. */
        private Cover sampleWater(BoardScene.Tile at, Contacts around, float x, float y, float z) {
            float[] weights = new float[FAMILIES];
            float total = 0, interpolation = 0;
            float width = BoardRelief.metres(WIDTH_METRES);
            for (BoardScene.Tile tile : around.pools()) {
                float amount = 1 - BoardRelief.smooth((distance(tile.coords(), x, y) + width) / (2 * width));
                if (amount <= .0001f) { continue; }
                var cover = sampleAt(tile, contacts(tile), cover(tile, z), x, y, z, false).cut();
                for (int family = 0; family < FAMILIES; family++) { weights[family] += amount * cover.weight(family); }
                interpolation += amount * cover.interpolation();
                total += amount;
            }
            return total > .0001f ? cover(weights, total, interpolation / total) : cover(at, z);
        }

        /** A cover query's result: as shown, before the ground below a column is cut back by height, and that ground's share. */
        private record Sampled(Cover cut, Cover uncut, Cover foot) {
            static Sampled of(Cover cover) { return new Sampled(cover, cover, NO_FOOT); }
        }

        private Sampled sampleAt(BoardScene.Tile at, Contacts around, Cover fallback, float x, float y, float z, boolean cliff) {
            float[] weights = new float[FAMILIES];
            float total = 0;
            float interpolation = 0;
            float width = BoardRelief.metres(WIDTH_METRES);
            float metre = BoardRelief.metres(1), level = BoardGeometry.level();
            float mx = x / metre, my = y / metre;
            float offset = 0;
            float bed = 0;
            int bedFamily = 0;
            boolean water = at.liquid().present() && !at.liquid().volcanic();
            if (water) {
                // Extend the neighbouring cover fields to the curved bank. Absolute hex distance would end the blend
                // before the waterline at a recessed corner, leaving the two banks with a hard radial seam.
                if (around.touching().length == 0) { return Sampled.of(fallback); }
                float nearest = Float.POSITIVE_INFINITY;
                for (BoardScene.Tile land : around.touching()) {
                    nearest = Math.min(nearest, distance(land.coords(), x, y));
                }
                offset = Math.max(0, nearest);
                bedFamily = around.bedFamily();
                float radius = (float) Math.hypot(x - BoardGeometry.centerX(at.coords()), y - BoardGeometry.centerY(at.coords()));
                float radial = radius / (BoardGeometry.height() * .5f);
                // A shallow pond can expose its bed. Let neighbouring bank covers meet a common central sediment,
                // chosen from those banks, instead of converging as six differently painted wedges at one point.
                bed = 1 - BoardRelief.smooth(radial / .5f);
                if (bed >= .9999f) { return Sampled.of(solid(bedFamily)); }
                // Keep the contact's angular reach bounded as the bank runs inward: distant shores must not bleed in.
                width *= Math.min(1, radial);
            }
            // A cliff's column reaches the point by height; ground contacts were settled with the tile.
            BoardScene.Tile[] tiles = cliff ? reaching(at, z) : around.touching();
            // The ground below a dry column, cut back by height once the column's own cover is known.
            Cover[] lower = new Cover[tiles.length];
            float[] lowerWeight = new float[tiles.length], lowerHeight = new float[tiles.length];
            int receivers = 0;
            for (BoardScene.Tile tile : tiles) {
                // A contact spreads and meanders down the exposed column; it starts at the plateau's own cover.
                // The bounded world-metre field is identical at every mesh LOD.
                float below = water ? 0 : Math.max(0, (BoardGeometry.groundZ(tile) - z) / metre);
                // The cliff's talus projects beyond its plateau footprint. Carry its broken material out with it,
                // so the receiving floor does not cut off the contact before the protruding foot is reached.
                float toe = BoardRelief.metres(Math.min(2.4f, below * .3f));
                float distance = distance(tile.coords(), x, y) - offset - toe;
                float contactWidth = width * (1 + .35f * BoardRelief.smooth(below / 4));
                float strength = .9f + .7f * BoardRelief.smooth(below / 3);
                if (distance >= contactWidth * (1 + strength * .5f)) { continue; }
                int family = family(tile);
                // Volcanic contacts keep their established noise when a new ordinary family is appended.
                if (tile.liquid().volcanic()) { family = tile.liquid().molten() ? 12 : 11; }
                // Family-anchored patches continue through neighbouring hexes of that family.
                float px = mx + below * .65f, py = my + below * .4f;
                float patch = .7f * BoardRelief.noise(px / 5.3f + family * 19.7f, py / 5.3f - family * 11.3f)
                      + .3f * BoardRelief.noise(px / 1.7f - family * 7.1f, py / 1.7f + family * 23.9f);
                float warp = (patch - .5f) * contactWidth * strength;
                float weight = 1 - BoardRelief.smooth((distance + contactWidth - warp) / (2 * contactWidth));
                if (water) {
                    // Land continues down into the basin; only cover from below this height is attenuated.
                    // The water surface is the reference, not its recessed bed or an absolute height difference.
                    float dz = Math.max(0, Math.max(z, at.elevation() * level) - BoardGeometry.groundZ(tile))
                          / level;
                    weight *= 1 - BoardRelief.smooth((dz - .35f) / .65f);
                }
                if (weight < .0001f) { continue; }
                Cover cover = water ? cover(tile) : cover(tile, z);
                float above = water ? 0 : (z - BoardGeometry.groundZ(tile)) / metre;
                if (above > 0) {
                    lower[receivers] = cover;
                    lowerWeight[receivers] = weight;
                    lowerHeight[receivers++] = above;
                    continue;
                }
                for (int material = 0; material < FAMILIES; material++) { weights[material] += weight * cover.weight(material); }
                interpolation += weight * cover.interpolation();
                total += weight;
            }
            // A column's material reaches the foot and nearby talus. The receiving ground creeps a bounded distance up
            // that column in tongues (footReach), then disappears; it cannot paint the whole cliff or the upper plateau.
            float tongue = footTongue(mx, my);
            float[] uncut = weights.clone(), foot = new float[FAMILIES];
            float uncutTotal = total, uncutInterpolation = interpolation;
            for (int i = 0; i < receivers; i++) {
                float kept = lowerWeight[i] * footReach(lowerHeight[i], tongue);
                for (int family = 0; family < FAMILIES; family++) {
                    float share = lowerWeight[i] * lower[i].weight(family);
                    weights[family] += kept * lower[i].weight(family);
                    uncut[family] += share;
                    foot[family] += share;
                }
                total += kept;
                interpolation += kept * lower[i].interpolation();
                uncutTotal += lowerWeight[i];
                uncutInterpolation += lowerWeight[i] * lower[i].interpolation();
            }
            if (uncutTotal <= .0001f) { return Sampled.of(fallback); }
            Cover cut = fallback;
            if (total > .0001f) {
                if (bed > 0) {
                    for (int family = 0; family < weights.length; family++) { weights[family] *= 1 - bed; }
                    weights[bedFamily] += bed * total;
                }
                cut = cover(weights, total, interpolation / total * (1 - bed));
            }
            return receivers == 0 ? Sampled.of(cut)
                  : new Sampled(cut, cover(uncut, uncutTotal, uncutInterpolation / uncutTotal), cover(foot, uncutTotal, 0));
        }

        /** The tile and the neighbours whose ground stands at or above the queried height, in query order. */
        private BoardScene.Tile[] reaching(BoardScene.Tile at, float z) {
            List<BoardScene.Tile> result = new ArrayList<>(7);
            for (int direction = -1; direction < 6; direction++) {
                BoardScene.Tile tile = direction < 0 ? at : scene.neighbor(at, direction);
                if (reaches(tile, z)) { result.add(tile); }
            }
            return result.toArray(BoardScene.Tile[]::new);
        }

        /** Signed distance to the hex's supporting edges, using the board's actual short/long dimensions. */
        private float distance(Coords coords, float x, float y) {
            float fitted = coast.distance(coords, x, y);
            if (Float.isFinite(fitted)) { return fitted; }
            float dx = Math.abs(x - BoardGeometry.centerX(coords)), dy = Math.abs(y - BoardGeometry.centerY(coords));
            float width = BoardGeometry.width(), height = BoardGeometry.height();
            float a = height / 2, b = width / 4;
            return Math.max(dy - a, (a * dx + b * dy - width * height / 4)
                  / (float) Math.sqrt(a * a + b * b));
        }
    }

    private static Cover cover(float[] weights, float total, float interpolation) {
        return new Cover(weights[0] / total, weights[1] / total, weights[2] / total,
              weights[3] / total, weights[4] / total, weights[5] / total, weights[6] / total, weights[7] / total,
              weights[8] / total, weights[9] / total, weights[10] / total, weights[11] / total, weights[12] / total,
              weights[13] / total, interpolation);
    }
}
