/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Renders a TAG's laser designator on a hit and a miss, and the tagged marker's symbol, for review. */
@Tag("on-demand")
class GpuTagDesignatorSmokeTest {
    private static final int SIZE = 320;

    @Test
    void drawsTheDesignatorAndTheTaggedMarker() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuSceneTestOutput buffer = null;
                var batch = new ModelBatch(GpuUnitShader.provider());
                Model groundModel = null;
                try {
                    EquipmentType.initializeTypes();
                    File folder = new File(System.getProperty("megamek.gpu.screenshots"));
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    ImageIO.write(symbol(GpuMarkers.artwork(BoardMarker.Kind.TAGGED)), "png",
                          new File(folder, "tagged-marker-symbol.png"));
                    buffer = new GpuSceneTestOutput(SIZE, SIZE);
                    groundModel = new ModelBuilder().createBox(4000, 4000, 1,
                          new Material(ColorAttribute.createDiffuse(.36f, .38f, .33f, 1)),
                          VertexAttributes.Usage.Position);
                    var ground = new ModelInstance(groundModel);
                    ground.transform.setToTranslation(0, 0, -.5f);
                    for (boolean hit : new boolean[] { true, false }) {
                        UnitAttack shot = shot(hit);
                        var effects = new GpuAttackEffects();
                        try {
                            Vector3 from = BoardGeometry.center(shot.event.attacker().location().coords(), 0);
                            Vector3 to = BoardGeometry.center(shot.event.destination().coords(), 0);
                            // Framed on the target end, where the designator puts its spot.
                            Vector3 middle = from.cpy().lerp(to, .85f);
                            float hex = BoardGeometry.height();
                            var camera = new PerspectiveCamera(40, SIZE, SIZE);
                            camera.position.set(middle.cpy().add(hex * 2.2f, 0, hex * 1.1f));
                            camera.up.set(Vector3.Z);
                            camera.lookAt(middle);
                            camera.near = 1;
                            camera.far = 5000;
                            camera.update();
                            File frames = new File(folder, hit ? "tag-hit-frames" : "tag-miss-frames");
                            assertTrue(frames.isDirectory() || frames.mkdirs());
                            int count = Math.round(shot.duration * 15);
                            for (int index = 0; index <= count; index++) {
                                shot.seconds = Math.min(shot.duration, index / 15f);
                                buffer.begin(.62f, .66f, .72f, 1);
                                try {
                                    batch.begin(camera);
                                    batch.render(ground);
                                    batch.end();
                                    effects.update(List.of(shot), null, Map.of());
                                    effects.render(camera);
                                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                                    buffer.display();
                                    ImageIO.write(snapshot(), "png",
                                          new File(frames, String.format("frame-%03d.png", index)));
                                } finally {
                                    buffer.pixels().end();
                                }
                            }
                        } finally {
                            effects.dispose();
                        }
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    if (groundModel != null) {
                        groundModel.dispose();
                    }
                    if (buffer != null) {
                        buffer.close();
                    }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("TAG designator review failed", failure.get());
        }
    }

    private static UnitAttack shot(boolean hit) {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var original = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, hit).result();
        var event = new ResolvedAttack(UUID.randomUUID(), original.kind(), original.attacker(), original.target(),
              original.targetType(), 0, "ISTAG", 4, hit, original.mounts(), original.shot());
        return new UnitAttack(new BoardScene.Combat(event, source, target, target.location()));
    }

    /** The marker's white artwork, tinted with its kind's red as the board tints it, on a dark backing. */
    private static BufferedImage symbol(BoardScene.Pixels art) {
        int tint = BoardMarker.Kind.TAGGED.rgb();
        BufferedImage image = new BufferedImage(art.width(), art.height(), BufferedImage.TYPE_INT_RGB);
        for (int index = 0; index < art.width() * art.height(); index++) {
            int rgba = art.rgba(index);
            float alpha = (rgba & 255) / 255f;
            int red = (int) (((rgba >>> 24) & 255) * ((tint >> 16) & 255) / 255f * alpha + 40 * (1 - alpha));
            int green = (int) (((rgba >>> 16) & 255) * ((tint >> 8) & 255) / 255f * alpha + 40 * (1 - alpha));
            int blue = (int) (((rgba >>> 8) & 255) * (tint & 255) / 255f * alpha + 40 * (1 - alpha));
            image.setRGB(index % art.width(), index / art.width(), (red << 16) | (green << 8) | blue);
        }
        return image;
    }

    private static BufferedImage snapshot() {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
        try {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    image.setRGB(x, SIZE - 1 - y, pixels.getPixel(x, y) >>> 8);
                }
            }
            return image;
        } finally {
            pixels.dispose();
        }
    }
}
