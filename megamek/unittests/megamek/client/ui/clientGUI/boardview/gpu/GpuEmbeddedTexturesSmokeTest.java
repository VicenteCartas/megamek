/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real image upload, shared ownership, sampler state and restoration data for embedded maps. */
@Tag("on-demand")
class GpuEmbeddedTexturesSmokeTest {
    @TempDir
    Path directory;

    @Test
    void embeddedPngAndJpegSurviveModelDisposalAndCanBeUploadedAgain() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var textures = new HashMap<String, Texture>();
                var board = new GpuAssets();
                try {
                    for (String format : new String[] { "png", "jpg" }) {
                        var fixture = new RigidGlbTest();
                        fixture.directory = Files.createDirectories(directory.resolve(format));
                        var levels = RigidGlb.loadLods(fixture.embedded(format));
                        var lod0 = ModelTextures.create(levels.getFirst(), textures, (key, color) -> {
                            throw new AssertionError("An embedded image must not ask for an external file");
                        });
                        var lod2 = ModelTextures.create(levels.get(2), textures, (key, color) -> {
                            throw new AssertionError("An embedded image must not ask for an external file");
                        });
                        try {
                            var texture = lod0.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse)
                                  .textureDescription.texture;
                            assertSame(texture, lod2.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse)
                                  .textureDescription.texture);
                            Texture surface = lod0.materials.first().get(GpuModelMaterial.class, GpuModelMaterial.TYPE).map;
                            assertNotSame(texture, surface, "One image used as color and data needs distinct GPU storage");
                            assertTrue(GpuColorTexture.isColor(texture));
                            assertFalse(GpuColorTexture.isColor(surface));
                            assertSame(surface, lod2.materials.first().get(GpuModelMaterial.class, GpuModelMaterial.TYPE).map,
                                  "Packed surface maps share the data-role image/sampler ownership across LODs");
                            assertEquals(2, texture.getWidth());
                            assertEquals(Texture.TextureWrap.ClampToEdge, texture.getUWrap());
                            assertEquals(Texture.TextureWrap.MirroredRepeat, texture.getVWrap());
                            assertEquals(Texture.TextureFilter.Nearest, texture.getMagFilter());
                            assertTrue(texture.isManaged());
                            var data = texture.getTextureData();
                            assertFalse(data.isPrepared());
                            texture.load(data);
                            assertEquals(2, texture.getHeight(), "Encoded bytes support a second upload");
                            assertTrue(surface.isManaged());
                            surface.load(surface.getTextureData());
                            assertEquals(2, surface.getHeight(), "Data-role copies also retain independent restoration data");
                        } finally {
                            lod0.dispose();
                            lod2.dispose();
                        }
                        Files.writeString(fixture.directory.resolve("component.json"), """
                              {"schema":2,"kind":"equipment","family":"laser","mesh":"mesh.glb",
                               "bounds":{"min":[0,0,0],"max":[4,4,4]},"rig":"rigid-v1",
                               "joints":{"root":"root"},"locations":{},"hardpoints":[],"emitters":[]}
                              """);
                        var units = new GpuUnitModels(fixture.directory);
                        try {
                            var component = units.modular("component.json");
                            org.junit.jupiter.api.Assertions.assertNotNull(component, "Textured unit components must load");
                            assertEquals(2, component.model().materials.first()
                                  .get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture.getWidth());
                            assertSame(component.model(), component.model(1), "Missing LOD1 shares LOD0 buffers");
                        } finally {
                            units.dispose();
                        }
                    }
                    for (var texture : textures.values()) {
                        texture.bind();
                        assertTrue(Gdx.gl.glIsTexture(texture.getTextureObjectHandle()), "Only the cache disposes images");
                    }
                    var first = board.model("buildings/authored/hpg-headquarters");
                    var second = board.model("buildings/authored/xenobiology-station");
                    assertSame(first.getMaterial("structures/common/weathered-paint")
                                .get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture,
                          second.getMaterial("structures/common/weathered-paint")
                                .get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    board.dispose();
                    textures.values().forEach(Texture::dispose);
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Embedded texture integration failed", failure.get()); }
    }
}
