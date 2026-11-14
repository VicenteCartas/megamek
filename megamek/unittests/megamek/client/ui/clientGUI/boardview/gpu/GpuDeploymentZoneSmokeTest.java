/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A deployment zone tints its complete terrain once, including tall slopes and vertical map-edge cliffs. */
@Tag("on-demand")
class GpuDeploymentZoneSmokeTest {
    private static final int WIDTH = 1000, HEIGHT = 700, SIZE = 16;
    private record Fixture(BoardScene scene, Map<Coords, BoardTacticalGeometry.Surface> surfaces, ModelInstance base) { }

    @Test
    void sixteenBySixteenZoneTintsTopsAndCliffInteriorsWithoutDepthFighting() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { verify(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Deployment zone cliffs", failure.get()); }
    }

    private static void verify() {
        assertNotNull(Gdx.gl30);
        String before = Mesh.getManagedStatus().replace(" 0", "");
        coplanarCurtain();
        Fixture fixture = fixture();
        GpuTactical tactical = new GpuTactical(fixture.surfaces()::get);
        GpuHexMasks tint = new GpuHexMasks();
        ModelBatch batch = new ModelBatch();
        try {
            tactical.update(fixture.scene());
            tint.updateDeployment(fixture.scene(), BoardDeploymentGeometry.zoneFills(fixture.scene()).values(),
                  fixture.surfaces()::get);
            assertTrue(tactical.deploymentActive());
            assertEquals(SIZE * SIZE, BoardDeploymentGeometry.zoneFills(fixture.scene()).size());
            for (boolean perspective : new boolean[] { false, true }) {
                for (float tilt : new float[] { 0, 14, 16, 35, 55, 75 }) {
                    BoardCamera view = new BoardCamera();
                    view.resize(WIDTH, HEIGHT);
                    view.fit(fixture.scene());
                    view.setPerspective(perspective);
                    view.setIsometric(false);
                    view.orbit(20, tilt);
                    view.fit(fixture.scene());
                    Camera camera = view.camera;
                    Runnable base = () -> { batch.begin(camera); batch.render(fixture.base()); batch.end(); };
                    frame(base);
                    byte[] plain = frame(base);
                    byte[] tinted = frame(() -> {
                        base.run(); batch.begin(camera); tint.submit(batch, camera); batch.end();
                    });
                    // The Tactical View, the flat top view, lays the zone's walls flat; the 3D view keeps them upright.
                    byte[] deployed = frame(() -> { base.run(); tactical.render(camera, 0, tilt == 0); });
                    int cliffPixels = 0, tintedCliffs = 0, faintTops = 0;
                    List<Point> seams = new ArrayList<>();
                    for (int y = 1; y < HEIGHT - 1; y++) {
                        for (int x = 1; x < WIDTH - 1; x++) {
                            int at = (y * WIDTH + x) * 4;
                            if (cliffInterior(plain, x, y)) {
                                cliffPixels++;
                                if (Byte.toUnsignedInt(tinted[at]) >= 40 && Byte.toUnsignedInt(tinted[at]) <= 55
                                      && Byte.toUnsignedInt(tinted[at + 1]) >= 40 && Byte.toUnsignedInt(tinted[at + 1]) <= 55
                                      && Byte.toUnsignedInt(tinted[at + 2]) >= 200 && Byte.toUnsignedInt(tinted[at + 2]) <= 215) {
                                    tintedCliffs++;
                                } else {
                                    assertTrue(Byte.toUnsignedInt(tinted[at]) >= 40,
                                          "Vertical cliffs must not have untinted depth-fighting holes");
                                    seams.add(new Point(x, y));
                                }
                            }
                            // The unlit top is neutral gray; this range identifies a faint yellow tint, not a bright outline.
                            if (Byte.toUnsignedInt(plain[at]) == 51 && Byte.toUnsignedInt(plain[at + 1]) == 51
                                  && Byte.toUnsignedInt(plain[at + 2]) == 51
                                  && Byte.toUnsignedInt(tinted[at]) >= 80 && Byte.toUnsignedInt(tinted[at]) <= 105
                                  && Byte.toUnsignedInt(tinted[at + 1]) >= 80 && Byte.toUnsignedInt(tinted[at + 1]) <= 105
                                  && Byte.toUnsignedInt(tinted[at + 2]) < 51) { faintTops++; }
                        }
                    }
                    String name = perspective + "-" + tilt;
                    System.out.printf("DEPLOYMENT-ZONE %s cliffInterior=%d tintedCliffs=%d faintTopPixels=%d%n",
                          name, cliffPixels, tintedCliffs, faintTops);
                    if (tilt == 0 || tilt == 55 || tintedCliffs != cliffPixels || cliffPixels < 1000 || faintTops < 1000) {
                        save("zone-" + name + "-plain", plain);
                        save("zone-" + name + "-tinted", tinted);
                        save("zone-" + name + "-deployed", deployed);
                    }
                    if (tilt > 0) {
                        assertTrue(cliffPixels > 1000, "The camera must expose substantial tall cliff interiors");
                    }
                    assertTrue(seams.size() <= Math.max(4, cliffPixels / 1000),
                          "Only sparse triangle-intersection pixels may blend twice under the small depth bias");
                    assertTrue(faintTops > 1000, "The interior tint must be visible and faint across the real zone");
                    if (tilt == 0) {
                        Vector3 band = camera.project(new Vector3(42 * BoardGeometry.HEX_SCALE,
                              -6 * BoardGeometry.HEX_SCALE, 0), 0, 0, WIDTH, HEIGHT);
                        int at = ((int) band.y * WIDTH + (int) band.x) * 4;
                        assertTrue(Byte.toUnsignedInt(deployed[at]) > 220 && Byte.toUnsignedInt(deployed[at + 1]) > 220
                              && Byte.toUnsignedInt(deployed[at + 2]) < 20,
                              "Top view keeps a broad yellow band six board pixels inside the perimeter");
                    }
                    long builds = tactical.builds();
                    byte[] animated = frame(() -> { base.run(); tactical.render(camera, .75f); });
                    assertTrue(!java.util.Arrays.equals(deployed, animated), "The perimeter's white dashes must move");
                    assertEquals(builds, tactical.builds(), "Scrolling the dash texture must not rebuild geometry");
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            tactical.dispose();
            tint.dispose();
            batch.dispose();
            fixture.base().model.dispose();
        }
        assertEquals(before, Mesh.getManagedStatus().replace(" 0", ""));
    }

    /** A curtain on the map's vertical skirt must win depth ties without showing through a foreground object. */
    private static void coplanarCurtain() {
        Coords coords = new Coords(0, 0);
        var tile = BoardScenes.newTile(coords, 0, -1, false, 0,
              BoardScene.Surface.GRASS, List.of(), List.of());
        var wall = new BoardTactical.Wall(coords, new BoardTactical.Point(BoardGeometry.TILE_WIDTH / 4, 0),
              new BoardTactical.Point(BoardGeometry.TILE_WIDTH * 3 / 4, 0),
              .5f, 0xA6FFFF00, null, 0, BoardTactical.Playback.LIVE);
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), new BoardTactical(List.of(), List.of(), List.of(wall), List.of()));
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        var skirt = builder.part("coplanar-cliff", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position,
              new Material(ColorAttribute.createDiffuse(Color.BLUE), IntAttribute.createCullFace(GL20.GL_NONE)));
        float scale = BoardGeometry.HEX_SCALE;
        float width = BoardGeometry.TILE_WIDTH;
        skirt.rect(new Vector3(0, 0, -5).scl(scale), new Vector3(width, 0, -5).scl(scale),
              new Vector3(width, 0, 20).scl(scale), new Vector3(0, 0, 20).scl(scale), new Vector3(0, 1, 0));
        var occluder = builder.part("foreground", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position,
              new Material(ColorAttribute.createDiffuse(Color.RED), IntAttribute.createCullFace(GL20.GL_NONE)));
        occluder.rect(new Vector3(45, 2, 3).scl(scale), new Vector3(53, 2, 3).scl(scale),
              new Vector3(53, 2, 6).scl(scale), new Vector3(45, 2, 6).scl(scale), new Vector3(0, 1, 0));
        var base = new ModelInstance(builder.end());
        var tactical = new GpuTactical();
        var batch = new ModelBatch();
        try {
            tactical.update(scene);
            for (float angle : new float[] { -.4f, 0, .4f }) {
                var camera = new OrthographicCamera(100 * scale, 70 * scale);
                camera.position.set(42 + 100 * angle, 100, 7).scl(scale);
                camera.up.set(0, 0, 1);
                camera.lookAt(42 * scale, 0, 7 * scale);
                camera.near = .1f;
                camera.far = 500 * scale;
                camera.update();
                Runnable draw = () -> { batch.begin(camera); batch.render(base); batch.end(); };
                byte[] plain = frame(draw);
                byte[] marked = frame(() -> { draw.run(); tactical.render(camera, 0); });
                int checked = 0;
                for (int x = 25; x <= 59; x++) {
                    for (int z = 2; z <= 8; z++) {
                        Vector3 p = camera.project(new Vector3(x, 0, z).scl(scale), 0, 0, WIDTH, HEIGHT);
                        int at = ((int) p.y * WIDTH + (int) p.x) * 4;
                        if (plain[at] == 0 && plain[at + 1] == 0 && Byte.toUnsignedInt(plain[at + 2]) == 255) {
                            assertTrue(Byte.toUnsignedInt(marked[at]) > 150 && Byte.toUnsignedInt(marked[at + 1]) > 150,
                                  "The coplanar curtain must not lose pixels to the cliff");
                            checked++;
                        } else if (Byte.toUnsignedInt(plain[at]) == 255 && plain[at + 1] == 0 && plain[at + 2] == 0) {
                            assertEquals(0, marked[at + 1], "A real foreground object must still occlude the curtain");
                        }
                    }
                }
                assertTrue(checked > 100);
            }
        } finally {
            tactical.dispose(); batch.dispose(); base.model.dispose();
        }
    }

    /** Exclude antialiased seam pixels before checking the exact translucent cliff color. */
    private static boolean cliffInterior(byte[] pixels, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int at = ((y + dy) * WIDTH + x + dx) * 4;
                if (pixels[at] != 0 || pixels[at + 1] != 0 || Byte.toUnsignedInt(pixels[at + 2]) != 255) { return false; }
            }
        }
        return true;
    }

    private static byte[] frame(Runnable draw) {
        Gdx.gl.glViewport(0, 0, WIDTH, HEIGHT);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearColor(.06f, .08f, .1f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        draw.run();
        return ScreenUtils.getFrameBufferPixels(0, 0, WIDTH, HEIGHT, false);
    }

    private static void save(String name, byte[] pixels) {
        Pixmap image = new Pixmap(WIDTH, HEIGHT, Pixmap.Format.RGBA8888);
        try {
            image.getPixels().put(pixels).flip();
            PixmapIO.writePNG(Gdx.files.local("build/gpu-performance/review/" + name + ".png"), image);
        } finally { image.dispose(); }
    }

    private static Fixture fixture() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        var graphics = new BoardTacticalGraphics();
        BoardTactical commands;
        try {
            graphics.setColor(java.awt.Color.YELLOW);
            BoardTacticalGraphics.drawDeployment(graphics, local -> {
                for (int x = 0; x < SIZE; x++) {
                    for (int y = 0; y < SIZE; y++) {
                        int level = ((x / 4 + y / 4) & 1) == 0 ? 0 : 12;
                        tiles.add(BoardScenes.newTile(new Coords(x, y), level, -1, false, 0,
                              BoardScene.Surface.GRASS, List.of(), List.of()));
                        var hex = BoardTacticalGraphics.onHexPlane(local, new Coords(x, y));
                        hex.fillHexBorder(0, 1);
                        hex.dispose();
                    }
                }
            });
            commands = graphics.snapshot();
        } finally { graphics.dispose(); }
        BoardScene scene = new BoardScene(0, SIZE, SIZE, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), commands);
        Map<Coords, BoardTacticalGeometry.Surface> surfaces = new HashMap<>();
        for (var tile : tiles) {
            List<BoardSurface.Face> top = new ArrayList<>(), walls = new ArrayList<>();
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            for (int edge = 0; edge < 6; edge++) {
                Vector3 a = BoardGeometry.corner(tile.coords(), tile.elevation(), edge);
                Vector3 b = BoardGeometry.corner(tile.coords(), tile.elevation(), edge + 1);
                top.add(new BoardSurface.Face(new Vector3(center), a, b, BoardSurface.Finish.TOP));
                var neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                int lower = neighbor == null ? -2 : neighbor.elevation();
                if (lower >= tile.elevation()) { continue; }
                // Include genuinely vertical board edges as well as slopes with projected area.
                float skirt = neighbor == null ? 1 : 1.08f;
                Vector3 c = new Vector3(a).sub(center).scl(skirt).add(center), d = new Vector3(b).sub(center).scl(skirt).add(center);
                c.z = d.z = lower * BoardGeometry.LEVEL;
                walls.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP));
                walls.add(new BoardSurface.Face(b, d, c, BoardSurface.Finish.TOP));
            }
            // Nonvertical slopes deliberately have projected area: merely rejecting vertical faces is insufficient.
            surfaces.put(tile.coords(), new BoardTacticalGeometry.Surface(top, walls, top, List.of(), walls, List.of()));
        }
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        var topMesh = builder.part("tops", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position,
              new Material(ColorAttribute.createDiffuse(new Color(.2f, .2f, .2f, 1)), IntAttribute.createCullFace(GL20.GL_NONE)));
        for (var surface : surfaces.values()) {
            for (var face : surface.top()) { topMesh.triangle(face.a(), face.b(), face.c()); }
        }
        var wallMesh = builder.part("cliffs", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position,
              new Material(ColorAttribute.createDiffuse(Color.BLUE), IntAttribute.createCullFace(GL20.GL_NONE)));
        for (var surface : surfaces.values()) {
            for (var face : surface.walls()) { wallMesh.triangle(face.a(), face.b(), face.c()); }
        }
        return new Fixture(scene, surfaces, new ModelInstance(builder.end()));
    }
}
