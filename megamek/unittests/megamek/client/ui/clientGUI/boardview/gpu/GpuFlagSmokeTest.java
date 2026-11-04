/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFlag;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuFlagSmokeTest {
    @Test void flagsRenderAndAnimateInTheSharedBoardScene() throws Exception {
        Coords at = new Coords(1, 1);
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(3, 3);
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) { board.setHex(x, y, new Hex(0, "", "lunar")); }
            }
            board.setHex(1, 2, new Hex(1, "", "lunar"));
            var flag = new BoardDecoration("flag", "prop", BoardFlag.ASSET, null, 0, 0, 0, false, 3,
                  BoardDecoration.Placement.surface("ground", "top", 4), 0)
                  .withFlag(new BoardFlag("horizontal-3", "decal/emblems/star-league", 0, 0, 1, 0));
            var pole = new BoardDecoration("pole", "prop", "scenery/steel-stick", null, -.025, 0, 0, false, 3.5,
                  BoardDecoration.Placement.ground(), 0);
            board.getHex(at).setDecorations(List.of(flag, pole));
            editor.game().setBoard(board);
            editor.game().getPlanetaryConditions().setWind(megamek.common.planetaryConditions.Wind.STRONG_GALE);
            editor.game().getPlanetaryConditions().setWindDirection(megamek.common.planetaryConditions.WindDirection.NORTH);
            editor.pointer(at, 0, 0, false);
            editor.command(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_OBJECT, "", "flag"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        var failure = new AtomicReference<Throwable>();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1400, 1000); config.useVsync(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                int step;
                long after;

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Flag render timed out at step " + step);
                        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                        GpuTerrain terrain = (GpuTerrain) field.get(this);
                        if (GpuBoardTestUi.loading(this) || terrain.busy() || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        assertEquals(2, terrain.editorObjects(at).size());
                        if (step == 0) {
                            checkCloth(source.editorState().objects().getFirst());
                            checkSharedPaint(source.editorState().objects().getFirst());
                            checkShadows(terrain);
                            checkSceneContact(terrain);
                            checkTerrainContact(terrain);
                            var stick = new GpuAssets();
                            try {
                                assertTrue(megamek.common.board.BoardGenerator.exists("scenery/steel-stick",
                                      megamek.common.board.BoardGenerator.OBJECT));
                                var model = stick.decoration("scenery/steel-stick", BoardDecoration.Colours.NONE);
                                assertFalse(model.materials.first().id.equals(GpuAssets.MISSING));
                                assertEquals(24, model.calculateBoundingBox(new com.badlogic.gdx.math.collision.BoundingBox()).getDepth(), .001f);
                            } finally { stick.dispose(); }
                            GpuBoardTestUi.capture(new File(output, "flag-horizontal.png"));
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.OBJECT_VALUE,
                                  "rotationY", "90"), source.takeFrame().boardGeneration());
                        } else if (step == 1) {
                            assertEquals(90, source.editorState().objects().getFirst().rotationY());
                            GpuBoardTestUi.capture(new File(output, "flag-vertical.png"));
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.OBJECT_VALUE,
                                  "rotation", "180"), source.takeFrame().boardGeneration());
                        } else if (step == 2) {
                            GpuBoardTestUi.capture(new File(output, "flag-back.png"));
                            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SAMPLE,
                                  "", ""), source.takeFrame().boardGeneration());
                        } else {
                            assertEquals("horizontal-3", source.editorState().activeBrush().object().flag().pattern());
                            GpuBoardTestUi.capture(new File(output, "flag-brush.png"));
                            Gdx.app.exit(); return;
                        }
                        step++; after = frames() + 12;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Flag integration", failure.get()); }
    }

    private static void checkCloth(BoardDecoration object) {
        var paint = GpuFlag.texture(object.flag(), object.colours());
        var flag = new GpuFlag(paint);
        try {
            var mesh = flag.model.meshes.first();
            float[] before = new float[mesh.getNumVertices() * 8], after = before.clone();
            flag.animate(new Matrix4(), 0, new Vector3(0, 1, 1), false, List.of(), GpuFlag.NO_GROUND); mesh.getVertices(before);
            flag.animate(new Matrix4(), 1, new Vector3(0, 1, 1), false, List.of(), GpuFlag.NO_GROUND); mesh.getVertices(after);
            assertFalse(java.util.Arrays.equals(before, after), "The actual render mesh must move");
            for (int row = 0; row <= GpuFlag.ROWS; row++) {
                int at = row * (GpuFlag.COLUMNS + 1) * 8;
                assertEquals(before[at], after[at]); assertEquals(before[at + 1], after[at + 1]);
                assertEquals(before[at + 2], after[at + 2]);
            }
            flag.animate(new Matrix4(), 0, new Vector3(0, 1, 1), true, List.of(), GpuFlag.NO_GROUND); mesh.getVertices(before);
            flag.animate(new Matrix4(), 1, new Vector3(0, 1, 1), true, List.of(), GpuFlag.NO_GROUND); mesh.getVertices(after);
            assertArrayEquals(before, after, "Vacuum has no wind response");
        } finally { flag.model.dispose(); paint.dispose(); }
    }

    private static void checkSharedPaint(BoardDecoration object) {
        var assets = new GpuAssets();
        try {
            var first = assets.flag(object);
            var second = assets.flag(object.duplicate());
            var texture = first.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture;
            assertSame(texture, second.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture);
            assertNotSame(first.meshes.first(), second.meshes.first(), "Separate placements need independent cloth meshes");
            var changed = assets.flag(object.withFlag(object.flag().with("pattern", "solid")));
            var changedPaint = changed.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture;
            assertNotSame(texture, changedPaint);
            Object owner = new Object();
            assets.retain(owner, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(second));
            assertNotEquals(0, texture.getTextureObjectHandle(), "Deleting one flag must preserve the other's shared paint");
            assertEquals(0, changedPaint.getTextureObjectHandle(), "Unused artwork must be released");
            assets.retain(owner, java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
            assertEquals(0, texture.getTextureObjectHandle(), "The last flag releases shared paint");
        } finally { assets.dispose(); }
    }

    private static void checkShadows(GpuTerrain terrain) throws Exception {
        var field = GpuTerrain.class.getDeclaredField("shadowMap"); field.setAccessible(true);
        var shadows = (GpuTerrainShadows) field.get(terrain);
        terrain.animate(.1f, List.of()); terrain.renderShadows(List.of());
        byte[] before = GpuFamilyAssemblyReview.shadowDepth(terrain);
        long revision = shadows.revision();
        terrain.animate(1, List.of()); terrain.renderShadows(List.of());
        assertTrue(shadows.revision() > revision, "Cloth motion must invalidate the caster even without a transform change");
        assertFalse(java.util.Arrays.equals(before, GpuFamilyAssemblyReview.shadowDepth(terrain)), "Animated cloth must change shadow pixels");
        var cache = GpuTerrainShadows.class.getDeclaredField("shadowBase"); cache.setAccessible(true);
        Object staticDepth = cache.get(shadows);
        assertNotNull(staticDepth, "Cloth shadows use the dynamic caster path");
        terrain.animate(1, List.of()); terrain.renderShadows(List.of());
        assertSame(staticDepth, cache.get(shadows), "Cloth animation reuses the static terrain shadow cache");
    }

    private static void checkSceneContact(GpuTerrain terrain) throws Exception {
        var field = GpuTerrain.class.getDeclaredField("flagRevisions"); field.setAccessible(true);
        var flags = (java.util.Map<?, ?>) field.get(terrain);
        var flag = (com.badlogic.gdx.graphics.g3d.ModelInstance) flags.keySet().iterator().next();
        Vector3 root = new Vector3().mul(flag.transform);
        var wallModel = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createBox(200, .05f, 200,
              new com.badlogic.gdx.graphics.g3d.Material(),
              com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
        var wall = new com.badlogic.gdx.graphics.g3d.ModelInstance(wallModel);
        wall.transform.setToTranslation(root.x, root.y - 5, root.z);
        var windField = GpuTerrain.class.getDeclaredField("wind"); windField.setAccessible(true);
        Vector3 wind = (Vector3) windField.get(terrain), original = new Vector3(wind);
        try {
            terrain.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 1, 180));
            terrain.animate(1, List.of(wall));
            float[] vertices = new float[flag.model.meshes.first().getNumVertices() * 8];
            flag.model.meshes.first().getVertices(vertices);
            for (int at = 0; at < vertices.length; at += 8) {
                Vector3 point = new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]).mul(flag.transform);
                assertTrue(point.y > root.y - 5, "The scene must feed nearby solid bounds into the rendered cloth");
            }
        } finally { wind.set(original); wallModel.dispose(); }
    }

    private static void checkTerrainContact(GpuTerrain terrain) throws Exception {
        var field = GpuTerrain.class.getDeclaredField("flagRevisions"); field.setAccessible(true);
        var flags = (java.util.Map<?, ?>) field.get(terrain);
        var flag = (com.badlogic.gdx.graphics.g3d.ModelInstance) flags.keySet().iterator().next();
        Matrix4 original = new Matrix4(flag.transform);
        var windField = GpuTerrain.class.getDeclaredField("wind"); windField.setAccessible(true);
        Vector3 wind = (Vector3) windField.get(terrain), originalWind = new Vector3(wind);
        Vector3 root = new Vector3().mul(original);
        float floor = terrain.flagGroundHeight(root);
        assertTrue(Float.isFinite(floor), "Cloth must query the installed terrain support");
        try {
            for (float strength : new float[] { 0, 1 }) {
                wind.set(0, -1, strength);
                for (float tilt : new float[] { 0, -90, 90 }) {
                    flag.transform.setToTranslation(root.x, root.y, floor + 1).rotate(Vector3.Y, tilt).scale(-3, 3, 3);
                    terrain.animate(1, List.of());
                    float[] vertices = new float[flag.model.meshes.first().getNumVertices() * 8];
                    flag.model.meshes.first().getVertices(vertices);
                    int touching = 0;
                    for (int at = 0; at < vertices.length; at += 8) {
                        Vector3 point = new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]).mul(flag.transform);
                        float ground = terrain.flagGroundHeight(point);
                        assertTrue(point.z >= ground - .001f, "Rendered cloth must not penetrate installed terrain");
                        if (point.z < ground + .1f) { touching++; }
                    }
                    assertTrue(touching > 0, "The low flag must actually contact the ground");
                }
            }
        } finally { flag.transform.set(original); wind.set(originalWind); terrain.animate(0, List.of()); }
    }
}
