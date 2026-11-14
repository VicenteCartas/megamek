/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Hover and editor-brush outlines stay complete where relief crosses their flat annotation plane. */
@Tag("on-demand")
class GpuHoverRingSmokeTest {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 640;
    private static final Coords HOVER = new Coords(0, 0);
    private static final List<Coords> BRUSH = List.of(HOVER, new Coords(1, 0));

    @Test
    void hoverAndBrushKeepHiddenSegmentsWithoutChangingSceneDepth() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { verify(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Hover outline through relief", failure.get()); }
    }

    private static void verify() throws Exception {
        BoardSource source = mock(BoardSource.class);
        when(source.editorBrush(HOVER, 0)).thenReturn(BRUSH);
        var view = new GpuBattleView(source);
        var scene = new BoardScene(0, 2, 1, BRUSH.stream().map(coords -> BoardScenes.newTile(coords, 0, -1,
              false, 0, BoardScene.Surface.GRASS, List.of(), List.of())).toList(),
              List.of(), List.of(), -1, "", List.of());
        set(view, "scene", scene);
        set(view, "hovered", HOVER);
        set(view, "ui", mock(GpuBoardHud.class));
        set(view, "lines", new ShapeRenderer());
        var draw = GpuBattleView.class.getDeclaredMethod("renderHoverRings");
        draw.setAccessible(true);
        ModelInstance slope = slope();
        var batch = new ModelBatch();
        var target = new FrameBuffer(Pixmap.Format.RGBA8888, WIDTH, HEIGHT, true);
        Input input = Gdx.input;
        Gdx.input = mock(Input.class);
        BoardCamera camera = view.boardCamera;
        camera.resize(WIDTH, HEIGHT);
        camera.setIsometric(true);
        camera.camera.zoom = .24f;
        camera.center(BoardGeometry.center(HOVER, 0).lerp(BoardGeometry.center(BRUSH.get(1), 0), .5f));
        try {
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                for (boolean editor : List.of(false, true)) {
                    when(source.isEditor()).thenReturn(editor);
                    when(Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(editor);
                    target.begin();
                    try {
                        Gdx.gl.glDepthMask(true);
                        ScreenUtils.clear(0, 0, 0, 1, true);
                        batch.begin(camera.camera);
                        batch.render(slope);
                        batch.end();
                        FloatBuffer before = depth();
                        draw.invoke(view);
                        FloatBuffer after = depth();
                        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                            assertEquals(before.get(pixel), after.get(pixel), "Hover must preserve scene depth");
                        }
                        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, WIDTH, HEIGHT);
                        try {
                            checkOutline(pixels, camera, scene, editor ? BRUSH : List.of(HOVER));
                            String name = "hover-slope-" + (perspective ? "perspective" : "orthographic")
                                  + (editor ? "-brush" : "") + ".png";
                            String directory = System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review");
                            PixmapIO.writePNG(new FileHandle(new File(directory, name)), pixels, -1, true);
                        } finally { pixels.dispose(); }
                    } finally { target.end(); }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            Gdx.input = input;
            view.dispose();
            slope.model.dispose();
            batch.dispose();
            target.dispose();
        }
    }

    /** A slope cuts through the hover plane: one side of the outline is buried, the other exposed. */
    private static ModelInstance slope() {
        float span = BoardGeometry.width() * 4;
        Vector3 center = BoardGeometry.center(HOVER, 0);
        var builder = new ModelBuilder();
        builder.begin();
        var part = builder.part("relief", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position,
              new Material(ColorAttribute.createDiffuse(new Color(.2f, .2f, .2f, 1)),
                    IntAttribute.createCullFace(GL20.GL_NONE)));
        part.rect(center.x - span, center.y - span, -span * .5f,
              center.x + span, center.y - span, span * .5f,
              center.x + span, center.y + span, span * .5f,
              center.x - span, center.y + span, -span * .5f, 0, 0, 1);
        return new ModelInstance(builder.end());
    }

    private static void checkOutline(Pixmap pixels, BoardCamera camera, BoardScene scene, List<Coords> hexes) {
        int hidden = 0, visible = 0;
        for (Coords coords : hexes) {
            Vector3 center = BoardGeometry.center(coords, 0);
            for (int edge = 0; edge < 6; edge++) {
                Vector3 a = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge), center,
                      GpuBattleView.HOVER_HEX_INSET);
                Vector3 b = BoardGeometry.inset(BoardGeometry.corner(coords, 0, edge + 1), center,
                      GpuBattleView.HOVER_HEX_INSET);
                for (int step = 1; step < 10; step++) {
                    Vector3 point = a.cpy().lerp(b, step / 10f);
                    point.z = BoardTacticalGeometry.floatingZ(scene, coords);
                    camera.camera.project(point, 0, 0, WIDTH, HEIGHT);
                    int brightest = 0;
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = -2; dy <= 2; dy++) {
                            brightest = Math.max(brightest, pixels.getPixel((int) point.x + dx,
                                  (int) point.y + dy) >>> 24);
                        }
                    }
                    assertTrue(brightest > 120, "Every hover edge must remain visible: " + coords + ", " + edge
                          + ", sample " + step + ", brightness " + brightest);
                    if (brightest > 245) { visible++; }
                    else { hidden++; }
                }
            }
        }
        assertTrue(hidden > 10, "Exercise terrain-hidden outline segments");
        assertTrue(visible > 10, "Exposed outline segments remain fully bright");
    }

    private static FloatBuffer depth() {
        FloatBuffer result = BufferUtils.newFloatBuffer(WIDTH * HEIGHT);
        Gdx.gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, result);
        return result;
    }

    private static void set(GpuBattleView view, String name, Object value) throws Exception {
        var field = GpuBattleView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }
}
