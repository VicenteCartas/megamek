/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.HexTile;

/**
 * A billboard's model, built to its stack of panels in the object frame of {@link BoardBillboard}: x along the panels seen
 * from the front, the front toward -y, z up. Only the panels, their frames and the catwalk's deck and rails run with the
 * size; every member keeps its section, and legs, stiffeners, brackets, posts and lamps repeat at their real spacing.
 */
final class BoardBillboardMesh {
    static final int LODS = 3;
    private static final float METRE = BoardBillboard.UNITS_PER_METRE;
    private static final String STEEL = "structures/common/steel", GRATING = "structures/common/grille",
          CONCRETE = "structures/common/cast-concrete", BLANK = "structures/common/weathered-paint";
    /** A picture's flat normal and occlusion maps: it shows the printed image, no surface of its own. */
    private static final String PICTURE_FINISH = "structures/buildings/fortress-far";
    private static final String PICTURE = "billboard-picture:", LENS = "billboard-lens";
    /** Planes along y in metres: the structure's rear, the panel's back sheet, its face, its frame's front. */
    private static final float SHEET = -.34f, FACE = -.5f, FRAME_FRONT = -.56f;
    private static final float FRAME = .12f, LEG_Y = .33f, LEG_RADIUS = .35f, DECK_FRONT = -1.45f, DECK_TOP = -.35f;
    private static final float RAIL_TOP = .75f, LAMP_Y = -2.3f, LAMP_Z = -.25f;
    /** Metres between the repeated members; a real bulletin's lamps stand about 12 feet apart. */
    private static final float LEG_SPACING = 7.3f, LAMP_SPACING = 3.66f, STIFFENER_SPACING = 2.44f, BRACKET_SPACING = 3f,
          POST_SPACING = 1.83f;
    /** A footing runs on below its support, in model units, so that it meets a sloping ground. */
    private static final float EMBED = HexTile.LEVEL_HEIGHT;
    private static final Vector3 LIGHT = new Vector3(1, .93f, .8f);
    /** The lit picture's share of its colours after dusk: the lamps' one light adds the shading and the spill around it. */
    private static final float PICTURE_GLOW = .1f;

    private BoardBillboardMesh() { }

    /**
     * How far the placed billboard's legs reach below its anchor, in its model's units: its height over its support, or
     * for a fixed level its height over its hex at {@code level}; none without legs.
     */
    static float legs(BoardDecoration object, int level) {
        var placement = object.placement();
        double levels = placement.offset() != null ? placement.offset() : placement.level() - level;
        return object.billboard().legs() ? (float) Math.max(0, levels * BoardGeometry.level()
              / (BoardGeometry.hexScale() * object.scale() * object.stretch().z())) : 0;
    }

    /** The palette's billboard: blank, a bulletin at its usual height. */
    static RigidGlb.Data preview() {
        return data(BoardBillboard.DEFAULT, (float) BoardBillboard.DEFAULT_LEVELS * HexTile.LEVEL_HEIGHT, 0);
    }

    /**
     * Level {@code lod} of the billboard whose legs reach {@code legs} model units below its anchor (none: no legs); a
     * missing image leaves its panel blank. Triangles of a bulletin on two legs: about 920, 310 and 90; each further
     * panel adds about 85, 85 and 60.
     */
    static RigidGlb.Data data(BoardBillboard billboard, float legs, int lod) {
        float width = (float) billboard.width() * METRE, panel = (float) billboard.height() * METRE;
        float stack = (float) billboard.stackHeight() * METRE;
        var mesh = new GeneratedMesh();
        panels(mesh, billboard, width, panel, stack, lod);
        if (billboard.legs() && legs > 0) { legs(mesh, billboard, width, panel, stack, legs, lod); }
        if (billboard.lights()) { catwalk(mesh, billboard, width, stack, lod); }
        var materials = new RigidGlb.Data();
        for (String part : mesh.parts.keySet()) {
            if (part.startsWith(PICTURE)) {
                float glow = billboard.lights() ? PICTURE_GLOW : 0;
                RigidGlb.pictureMaterial(materials, part, BoardDecalArt.image(part.substring(PICTURE.length())), PICTURE_FINISH);
                RigidGlb.nightEmission(materials, part, glow, glow, glow);
            }
            else if (part.equals(LENS)) { lens(materials); }
            else { RigidGlb.boardMaterial(materials, part, part); }
        }
        return mesh.data(BoardBillboard.ASSET + "-lod" + lod, materials);
    }

    /**
     * One finite light for the whole row of lamps, however many, reaching the stack's top; the night system fades it. It
     * lies out in front of the stack, a little up it, as the lamps' beams wash it, so that little of it reaches the ground.
     */
    static List<RigidGlb.NightLight> lights(BoardBillboard billboard) {
        if (!billboard.lights()) { return List.of(); }
        float half = (float) billboard.width() * METRE / 2, height = (float) billboard.stackHeight() * METRE;
        float y = (FACE - 3) * METRE, z = height * .15f;
        return List.of(new RigidGlb.NightLight(new Vector3(-half, y, z), new Vector3(half, y, z), LIGHT, .6f, height * 1.25f));
    }

    /** The image's rectangle that covers the panel: u, v of its first corner then of its opposite one, the overflow cropped evenly. */
    static float[] cover(double imageWidth, double imageHeight, double panelWidth, double panelHeight) {
        double image = imageWidth / imageHeight, panel = panelWidth / panelHeight;
        float u = (float) Math.min(1, panel / image), v = (float) Math.min(1, image / panel);
        return new float[] { (1 - u) / 2, (1 - v) / 2, (1 + u) / 2, (1 + v) / 2 };
    }

    private static int repeats(float length, float spacing) { return Math.max(1, Math.round(length / spacing)); }

    /** The centre of the {@code index}-th of {@code count} equal bays along a panel of {@code width}. */
    private static float along(float width, int index, int count) { return width * ((index + .5f) / count - .5f); }

    /** The panels from the top down, a padding between them, tied by the stiffeners that run up the whole stack. */
    private static void panels(GeneratedMesh mesh, BoardBillboard billboard, float width, float panel, float stack, int lod) {
        int count = billboard.panels();
        float pitch = panel + (float) BoardBillboard.PADDING * METRE;
        for (int i = 0; i < count; i++) { panel(mesh, billboard.decals().get(i), width, panel, (count - 1 - i) * pitch, lod); }
        if (lod > 0) { return; }
        int stiffeners = repeats((float) billboard.width(), STIFFENER_SPACING);
        for (int i = 0; i <= stiffeners; i++) {
            float x = width * ((float) i / stiffeners - .5f), thin = .04f * METRE;
            block(mesh, STEEL, x - thin, SHEET * METRE, 0, x + thin, -.14f * METRE, stack);
        }
    }

    /** One framed panel whose lower edge stands at {@code bottom}, its picture covering it. */
    private static void panel(GeneratedMesh mesh, String decal, float width, float height, float bottom, int lod) {
        float half = width / 2, frame = FRAME * METRE, face = FACE * METRE, sheet = SHEET * METRE, front = FRAME_FRONT * METRE;
        float top = bottom + height;
        boolean art = decal.startsWith("decal/") && BoardDecalArt.image(decal).isFile();
        float[] uv = { 0, 0, width / 18, height / 18 };
        if (art) {
            try {
                int[] size = BoardDecalArt.imageSize(decal);
                uv = cover(size[0], size[1], width, height);
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        String picture = art ? PICTURE + decal : BLANK;
        // The image's first row is its top: v runs down the panel.
        mesh.quad(picture, vertex(-half, face, bottom, 0, -1, 0, uv[0], uv[3]), vertex(half, face, bottom, 0, -1, 0, uv[2], uv[3]),
              vertex(half, face, top, 0, -1, 0, uv[2], uv[1]), vertex(-half, face, top, 0, -1, 0, uv[0], uv[1]));
        block(mesh, STEEL, -half, sheet, bottom, half, face + .02f * METRE, top);
        block(mesh, STEEL, -half - frame, front, bottom - frame, -half, sheet, top + frame);
        block(mesh, STEEL, half, front, bottom - frame, half + frame, sheet, top + frame);
        block(mesh, STEEL, -half, front, bottom - frame, half, sheet, bottom);
        block(mesh, STEEL, -half, front, top, half, sheet, top + frame);
        if (lod > 1) { return; }
        // Rear beams. Their back is the structure's rear plane, which a wall meets when the billboard hangs on one.
        for (float share : new float[] { .22f, .78f }) {
            float z = bottom + height * share, beam = .13f * METRE;
            block(mesh, STEEL, -half, sheet, z - beam, half, 0, z + beam);
        }
    }

    private static void legs(GeneratedMesh mesh, BoardBillboard billboard, float width, float panel, float stack, float legs, int lod) {
        int count = repeats((float) billboard.width(), LEG_SPACING);
        float y = LEG_Y * METRE, bottom = -legs - EMBED, footing = -legs + .4f * METRE, top = stack * .85f;
        for (int i = 0; i < count; i++) {
            float x = along(width, i, count);
            tube(mesh, STEEL, x, y, bottom, top, LEG_RADIUS * METRE, lod == 0 ? 14 : lod == 1 ? 8 : 5);
            if (lod > 1) { continue; }
            tube(mesh, CONCRETE, x, y, bottom, footing, .85f * METRE, lod == 0 ? 16 : 8);
            if (lod > 0) { continue; }
            tube(mesh, STEEL, x, y, footing, footing + .06f * METRE, .55f * METRE, 12);
            float reach = Math.min(1.8f * METRE, width / count * .4f), low = -Math.min(legs * .5f, 2.4f * METRE);
            for (int side = -1; side <= 1; side += 2) {
                beam(mesh, STEEL, x, y, low, x + side * reach, 0, panel * .22f, .07f * METRE);
            }
        }
    }

    /** A service catwalk under the stack and the lamps on its arms, each aimed up the stack. */
    private static void catwalk(GeneratedMesh mesh, BoardBillboard billboard, float width, float stack, int lod) {
        float half = width / 2 + FRAME * METRE, front = DECK_FRONT * METRE, deck = DECK_TOP * METRE;
        if (lod < 2) {
            block(mesh, GRATING, -half, front, deck - .05f * METRE, half, FRAME_FRONT * METRE, deck);
            rail(mesh, -half, half, front, RAIL_TOP * METRE);
        }
        if (lod == 0) {
            rail(mesh, -half, half, front, .2f * METRE);
            for (int side = -1; side <= 1; side += 2) {
                float x = side * half, thin = .025f * METRE;
                block(mesh, STEEL, x - thin, front, RAIL_TOP * METRE - thin, x + thin, FRAME_FRONT * METRE, RAIL_TOP * METRE + thin);
            }
            int posts = repeats(2 * half / METRE, POST_SPACING);
            for (int i = 0; i <= posts; i++) {
                float x = 2 * half * ((float) i / posts - .5f), thin = .025f * METRE;
                block(mesh, STEEL, x - thin, front - thin, deck, x + thin, front + thin, RAIL_TOP * METRE);
            }
            int brackets = repeats((float) billboard.width(), BRACKET_SPACING);
            for (int i = 0; i < brackets; i++) {
                float x = along(width, i, brackets), hanger = -.42f * METRE, under = deck - .08f * METRE, foot = -1.1f * METRE;
                float thick = .05f * METRE;
                beam(mesh, STEEL, x, hanger, 0, x, hanger, foot, thick);
                beam(mesh, STEEL, x, hanger, under, x, front, under, thick);
                beam(mesh, STEEL, x, hanger, foot, x, front, under, thick);
            }
        }
        if (lod > 1) { return; }
        int lamps = repeats((float) billboard.width(), LAMP_SPACING);
        // Aimed at the stack's upper part, to wash it evenly; so their glass also shows from above.
        var aim = new Vector3(0, (FACE - LAMP_Y) * METRE, stack * .7f - LAMP_Z * METRE).nor();
        var across = new Vector3(1, 0, 0);
        var up = new Vector3(aim).crs(across);
        for (int i = 0; i < lamps; i++) {
            float x = along(width, i, lamps), y = LAMP_Y * METRE, z = LAMP_Z * METRE;
            beam(mesh, STEEL, x, front, deck - .03f * METRE, x, y, z, .035f * METRE);
            var head = frame(across, up, aim, x, y, z);
            box(mesh, STEEL, head, .33f * METRE, .23f * METRE, .07f * METRE);
            lensQuad(mesh, head, .3f * METRE, .2f * METRE, .075f * METRE);
        }
    }

    private static void rail(GeneratedMesh mesh, float from, float to, float y, float z) {
        float thin = .025f * METRE;
        block(mesh, STEEL, from, y - thin, z - thin, to, y + thin, z + thin);
    }

    private static void lensQuad(GeneratedMesh mesh, Matrix4 frame, float halfX, float halfY, float z) {
        var normal = new Vector3(0, 0, 1).rot(frame).nor();
        float[][] corners = { { -halfX, -halfY }, { halfX, -halfY }, { halfX, halfY }, { -halfX, halfY } };
        float[][] vertices = new float[4][];
        for (int i = 0; i < 4; i++) {
            var point = new Vector3(corners[i][0], corners[i][1], z).mul(frame);
            vertices[i] = vertex(point.x, point.y, point.z, normal.x, normal.y, normal.z, i % 3 == 0 ? 0 : 1, i < 2 ? 0 : 1);
        }
        mesh.quad(LENS, vertices[0], vertices[1], vertices[2], vertices[3]);
    }

    /** A lamp's glass: the fortress windows' glass, in the lamps' colour. */
    private static void lens(RigidGlb.Data materials) {
        RigidGlb.boardMaterial(materials, LENS, "structures/buildings/fortress-glass");
        RigidGlb.nightEmission(materials, LENS, 3 * LIGHT.x, 3 * LIGHT.y, 3 * LIGHT.z);
    }

    private static float[] vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        return new float[] { x, y, z, nx, ny, nz, 1, 1, 1, 1, u, v };
    }

    /** An axis-aligned box between two corners. */
    private static void block(GeneratedMesh mesh, String material, float x0, float y0, float z0, float x1, float y1, float z1) {
        box(mesh, material, new Matrix4().setToTranslation((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2),
              Math.abs(x1 - x0) / 2, Math.abs(y1 - y0) / 2, Math.abs(z1 - z0) / 2);
    }

    /** A square bar of half width {@code thick} from one point to another. */
    private static void beam(GeneratedMesh mesh, String material, float ax, float ay, float az, float bx, float by, float bz,
          float thick) {
        var axis = new Vector3(bx - ax, by - ay, bz - az);
        float half = axis.len() / 2;
        axis.nor();
        var side = Math.abs(axis.x) < .9f ? new Vector3(1, 0, 0) : new Vector3(0, 1, 0);
        side.mulAdd(axis, -side.dot(axis)).nor();
        var other = new Vector3(axis).crs(side);
        box(mesh, material, frame(side, other, axis, (ax + bx) / 2, (ay + by) / 2, (az + bz) / 2), thick, thick, half);
    }

    /** The frame with these axes as its columns and its origin at the point. */
    private static Matrix4 frame(Vector3 x, Vector3 y, Vector3 z, float px, float py, float pz) {
        return new Matrix4(new float[] { x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, px, py, pz, 1 });
    }

    /** A box about {@code frame}'s origin, textured along its faces at one repeat a level. */
    private static void box(GeneratedMesh mesh, String material, Matrix4 frame, float hx, float hy, float hz) {
        float[] half = { hx, hy, hz };
        for (int axis = 0; axis < 3; axis++) {
            int a = (axis + 1) % 3, b = (axis + 2) % 3;
            for (int sign = -1; sign <= 1; sign += 2) {
                var normal = new Vector3(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0).rot(frame).nor();
                float[][] corners = new float[4][];
                for (int corner = 0; corner < 4; corner++) {
                    float[] local = new float[3];
                    local[axis] = sign * half[axis];
                    local[a] = (corner == 1 || corner == 2 ? 1 : -1) * half[a];
                    local[b] = (corner >= 2 ? 1 : -1) * half[b];
                    var point = new Vector3(local[0], local[1], local[2]).mul(frame);
                    corners[corner] = vertex(point.x, point.y, point.z, normal.x, normal.y, normal.z, local[a] / 18, local[b] / 18);
                }
                if (sign > 0) { mesh.quad(material, corners[0], corners[1], corners[2], corners[3]); }
                else { mesh.quad(material, corners[3], corners[2], corners[1], corners[0]); }
            }
        }
    }

    /** An upright cylinder with smooth sides and a closed top; its foot stands in the ground or on a footing. */
    private static void tube(GeneratedMesh mesh, String material, float x, float y, float bottom, float top, float radius, int segments) {
        float[] centre = vertex(x, y, top, 0, 0, 1, x / 18, y / 18);
        for (int i = 0; i < segments; i++) {
            double a0 = i * 2 * Math.PI / segments, a1 = (i + 1) * 2 * Math.PI / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float u0 = (float) (a0 * radius / 18), u1 = (float) (a1 * radius / 18);
            float x0 = x + radius * c0, y0 = y + radius * s0, x1 = x + radius * c1, y1 = y + radius * s1;
            mesh.quad(material, vertex(x0, y0, bottom, c0, s0, 0, u0, bottom / 18), vertex(x1, y1, bottom, c1, s1, 0, u1, bottom / 18),
                  vertex(x1, y1, top, c1, s1, 0, u1, top / 18), vertex(x0, y0, top, c0, s0, 0, u0, top / 18));
            mesh.triangle(material, centre, vertex(x0, y0, top, 0, 0, 1, x0 / 18, y0 / 18), vertex(x1, y1, top, 0, 0, 1, x1 / 18, y1 / 18));
        }
    }
}
