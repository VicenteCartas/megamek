/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;

/**
 * The compact ground material's table (ground-material.glsl, block {@value #BLOCK}, std140, binding {@value #BINDING}): a
 * row per surface family, its four roles' layers, repeats and mean roughness, and its rules as numbers. Adding a family is
 * adding a row; nothing in the shader names a family. Owns the table's uniform buffer; GL thread. In development: behind
 * {@code -Dmegamek.gpu.compactGround} (off by default), not a Display switch until it ships with its place in the presets.
 */
final class GroundFamilies {
    /** {@code -Dmegamek.gpu.compactGround}: sculpt programs compile the compact material in ({@code compactGroundFlag}). */
    static final boolean ON = Boolean.getBoolean("megamek.gpu.compactGround");
    static final String BLOCK = "BoardMaterials";
    /** The block's binding: 1 and 2 are the material cache's page tables (GpuMaterialCache). */
    static final int BINDING = 3;
    /** The table's rows (GROUND_FAMILIES in the shader): one a surface family. */
    static final int COUNT = BoardScene.Surface.values().length;
    /** vec4s a row: layers, tiles, roughness, then the 16 of the rules (Rules). */
    private static final int ROW = 19;
    private static final int RULES = 16;

    /** One family's rules, in ground-material.glsl's GroundFamily order from {@code cover} on. */
    private static final class Rules {
        final float[] values = new float[RULES * 4];

        private Rules put(int vec4, float x, float y, float z, float w) {
            values[vec4 * 4] = x; values[vec4 * 4 + 1] = y; values[vec4 * 4 + 2] = z; values[vec4 * 4 + 3] = w;
            return this;
        }

        /** The cover's smoothstep of the slope (low, high), its jitter by the ground's variation, its lift by a deposit. */
        Rules cover(float low, float high, float jitter, float lift) { return put(0, low, high, jitter, lift); }
        /** What thins the cover: rim scour, thin patches (only on exposed rims when {@code onCrest}), a crest shoulder. */
        Rules trim(float scour, float thin, boolean onCrest, float shoulder) { return put(1, scour, thin, onCrest ? 1 : 0, shoulder); }
        /** The bare share's mantle: a bank's (and how it fades on level ground), at rims, and capping crests. */
        Rules mantle(float bank, float levelFade, float rimShare, float crestCap) { return put(2, bank, levelFade, rimShare, crestCap); }
        /** The foot deposit's share, its share on banks, the dust left on level stone, how far level cover wears through. */
        Rules deposit(float share, float onBanks, float dust, float wear) { return put(3, share, onBanks, dust, wear); }
        /** A two-level bank's turf lip, a crest's cornice, where a soil river bank ends the cover, soil river banks. */
        Rules edges(boolean turfLip, boolean cornice, float coverEnd, boolean soilBanks) {
            return put(4, turfLip ? 1 : 0, cornice ? 1 : 0, coverEnd, soilBanks ? 1 : 0);
        }
        /** Sediment's exponent on shallow coasts, the shallow arid profile, wall weathering, rim abrasion. */
        Rules shore(float sedimentExponent, boolean shallowArid, float wallWeathering, float rimAbrasion) {
            return put(5, sedimentExponent, shallowArid ? 1 : 0, wallWeathering, rimAbrasion);
        }
        /**
         * A tone patch ({@code patch} 0 or 1): its gain where whole and amount; its field's weights of the broad, regional
         * and fine noise and smoothstep; its rim and foot weights.
         */
        Rules tone(int patch, float r, float g, float b, float amount, float broad, float region, float fine, float low,
              float high, float rim, float foot) {
            put(6 + 3 * patch, r, g, b, amount);
            put(7 + 3 * patch, broad, region, fine, low);
            values[(8 + 3 * patch) * 4] = high; values[(8 + 3 * patch) * 4 + 1] = rim; values[(8 + 3 * patch) * 4 + 2] = foot;
            return this;
        }
        /** Drifts' ripples across loose sand's tone. */
        Rules ripples(float amount) { values[8 * 4 + 3] = amount; return this; }
        /** Crust colonies: lowland skin, crust above level zero and along raised rims. */
        Rules colonies() { values[12 * 4 + 2] = 1; return this; }
        /** The root mat a two-level lip's sod leaves below it. */
        Rules rootMat(float r, float g, float b) { return put(13, r, g, b, 0); }
        /** A rim's abraded rock below its crest and at it. */
        Rules abrasion(float lowR, float lowG, float lowB, float highR, float highG, float highB) {
            put(14, lowR, lowG, lowB, 0);
            return put(15, highR, highG, highB, 0);
        }
    }

    /** Rock and the families without rules of their own: no mantle, the full deposit, weathered walls. */
    private static Rules plain() {
        return new Rules().cover(.4f, .96f, .55f, 0).trim(0, .92f, false, 0).deposit(1, 1, 0, 0).edges(false, false, .9f, false)
              .shore(1, false, 1, 0);
    }

    /** Turf over a soil mantle, worn through on level ground, with a two-level lip and soil river banks. */
    private static Rules turf() {
        return new Rules().cover(.4f, .96f, .55f, 0).trim(0, 0, false, 0).mantle(.9f, 0, .5f, 0).deposit(1, .25f, 0, .48f)
              .edges(true, false, .6f, true).shore(3, false, 1, 0).rootMat(.45f, .41f, .37f);
    }

    /** Dry ground: scoured, thin rims with a shoulder, cap plates on crests, dust on level stone. */
    private static Rules arid() {
        return new Rules().cover(.38f, .96f, .22f, 0).trim(.9f, .92f, false, .85f).mantle(0, 0, 0, 1).deposit(.55f, 1, .24f, 0)
              .edges(false, false, .9f, false);
    }

    /** The families' rules: today's cliff roles (terrain-materials.glsl cliffFamily and its kin) as numbers. */
    private static final Map<BoardScene.Surface, Rules> RULES_OF = rules();

    private static Map<BoardScene.Surface, Rules> rules() {
        Map<BoardScene.Surface, Rules> rules = new EnumMap<>(BoardScene.Surface.class);
        rules.put(BoardScene.Surface.GRASS, turf()
              .tone(0, 1.25f, 1.12f, .7f, .5f, .7f, 0, .3f, .55f, .85f, .45f, 0)
              .tone(1, .78f, .95f, .82f, .4f, -1, 0, 0, -.45f, -.2f, 0, .6f));
        rules.put(BoardScene.Surface.TROPICAL, turf());
        rules.put(BoardScene.Surface.DIRT, new Rules().cover(.28f, .9f, .16f, 0).trim(0, .9f, false, 0).mantle(1, 1, 0, 0)
              .deposit(.6f, 1, .18f, 0).edges(false, false, .9f, true).shore(1, false, 1, 0));
        rules.put(BoardScene.Surface.SAND, new Rules().cover(.38f, .94f, .45f, .15f).trim(.92f, 0, false, 0)
              .deposit(.55f, 1, 0, 0).edges(false, false, .9f, true).shore(1, false, 1, 0).ripples(.04f));
        rules.put(BoardScene.Surface.SNOW, new Rules().cover(.36f, .92f, .5f, .18f).trim(.85f, .88f, true, 0)
              .deposit(.35f, 1, 0, 0).edges(false, true, .9f, true).shore(1, false, 1, 0));
        rules.put(BoardScene.Surface.ROCK, plain().deposit(1, 1, .12f, 0));
        rules.put(BoardScene.Surface.LUNAR, plain().deposit(1, 1, .12f, 0).shore(1, false, 0, 0));
        rules.put(BoardScene.Surface.CONCRETE, plain());
        rules.put(BoardScene.Surface.FUNGUS, new Rules().cover(.76f, .97f, 0, 0).trim(0, .9f, false, 0).mantle(1, 1, 0, 0)
              .deposit(.3f, 1, 0, 0).edges(false, false, .9f, false).shore(1, false, 0, 0).colonies());
        rules.put(BoardScene.Surface.DESERT, arid().shore(1, true, 0, 1)
              .tone(0, 1.03f, .91f, .85f, .45f, .6f, .4f, 0, .4f, .75f, 0, 0)
              .abrasion(.98f, .91f, .85f, 1.14f, 1.15f, 1.13f));
        rules.put(BoardScene.Surface.MARS, arid().shore(1, true, 0, 0)
              .tone(0, .92f, .82f, .77f, .3f, 0, .6f, .4f, .55f, .8f, 0, 0));
        rules.put(BoardScene.Surface.VOLCANO, arid().shore(1, false, 1, 0));
        for (BoardScene.Surface family : BoardScene.Surface.values()) { rules.putIfAbsent(family, plain()); }
        return rules;
    }

    private int buffer;
    /** The finish table the uploaded table took its roughness from: a new one (assets reloaded) uploads again. */
    private Object uploadedFor;

    /**
     * The table's std140 contents. {@code materials}: each family's cover, debris, wall and mantle names (GpuTerrain's
     * SCULPT_MATERIALS), {@code floors} its floor or null; layers are indices into {@code layers}; {@code tile} metres a
     * repeat; {@code surface} the finish arrays' surface table (roughness first, four floats a layer).
     */
    static FloatBuffer table(List<String> layers, String[][] materials, String[] floors, ToDoubleFunction<String> tile,
          float[] surface, String bankSoil, String bankGravel, String sharedCliff, String rockTops) {
        FloatBuffer table = BufferUtils.createFloatBuffer(COUNT * ROW * 4 + 2 * 4);
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            String[] roles = materials[family.ordinal()];
            // Role order cover, mantle, deposit, rock; the sculpt materials list ground, debris, wall, mantle.
            String[] ordered = { roles[0], roles[3], roles[1], roles[2] };
            for (String name : ordered) { table.put(layers.indexOf(name)); }
            for (String name : ordered) { table.put((float) tile.applyAsDouble(name)); }
            for (String name : ordered) { table.put(surface[layers.indexOf(name) * 4]); }
            float[] rules = RULES_OF.get(family).values.clone();
            String floor = floors[family.ordinal()];
            rules[12 * 4] = floor == null ? -1 : layers.indexOf(floor);
            rules[12 * 4 + 1] = floor == null ? 0 : (float) tile.applyAsDouble(floor);
            table.put(rules);
        }
        table.put(new float[] { layers.indexOf(bankSoil), (float) tile.applyAsDouble(bankSoil), layers.indexOf(bankGravel),
              (float) tile.applyAsDouble(bankGravel) });
        table.put(new float[] { layers.indexOf(sharedCliff), layers.indexOf(rockTops), (float) tile.applyAsDouble(rockTops), 0 });
        return table.flip();
    }

    /** Uploads the table once a finish table (an asset load), binds it at its binding; GL thread. */
    void bind(Object finishes, FloatBuffer contents) {
        if (buffer == 0) { buffer = GL15C.glGenBuffers(); }
        if (uploadedFor != finishes) {
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, buffer);
            GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, contents, GL15C.GL_STATIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
            uploadedFor = finishes;
        }
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING, buffer);
    }

    boolean uploaded(Object finishes) { return buffer != 0 && uploadedFor == finishes; }

    /** A program's table block at its binding, if it has one; after each link or binary load (neither keeps it). */
    static boolean bindBlock(int program) {
        int block = GL31C.glGetUniformBlockIndex(program, BLOCK);
        if (block == GL31C.GL_INVALID_INDEX) { return false; }
        GL31C.glUniformBlockBinding(program, block, BINDING);
        return true;
    }

    void dispose() {
        if (buffer != 0) { GL15C.glDeleteBuffers(buffer); buffer = 0; }
        uploadedFor = null;
    }
}
