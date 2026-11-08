/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11;

/** Actual texture filtering, upload domains and atlas updates, read back as floating-point radiance. */
@Tag("on-demand")
class GpuColorTextureSmokeTest {
    @TempDir
    Path directory;

    @Test
    void squareArtworkKeepsTrimmedGougeProportions() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            var scars = directory.resolve("models/board/textures/terrain/damage");
            Files.createDirectories(scars);
            // Other brush styles remain the real independent round-impact controls.
            try (var files = Files.list(previous.toPath().resolve("models/board/textures/terrain/damage"))) {
                for (var file : files.filter(path -> path.toString().endsWith(".png") || path.toString().endsWith(".tres")).toList()) {
                    Files.copy(file, scars.resolve(file.getFileName()));
                }
            }
            try {
                megamek.common.Configuration.setDataDir(directory.toFile());
                for (boolean square : new boolean[] { false, true }) {
                    int height = square ? 256 : 64;
                    var image = new BufferedImage(256, height, BufferedImage.TYPE_INT_ARGB);
                    for (int y = height / 4; y < height * 3 / 4; y++) {
                        for (int x = 32; x < 224; x++) { image.setRGB(x, y, 0xff606060); }
                    }
                    for (String name : List.of("laser-gouge", "ppc-gouge", "ballistic-gouge")) {
                        javax.imageio.ImageIO.write(image, "png", scars.resolve(name + "_albedo.png").toFile());
                    }
                    var damage = new GpuGroundDamage();
                    try {
                        damage.update(BoardScenes.scene(c ->
                              BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0)));
                        for (var style : List.of(GpuGroundDamage.Style.LASER, GpuGroundDamage.Style.PPC, GpuGroundDamage.Style.BALLISTIC)) {
                            for (float radius : new float[] { .3f, 10 }) {
                                var brush = damage.brush(style, radius, 1, 0);
                                // A 4:1 canvas, half its height and three quarters its width: a 6:1 visible mark.
                                assertEquals(1f / 6, brush.aspect(), .00001f,
                                      "Trimming and mip selection must preserve the physical gouge footprint");
                                assertEquals(255, brush.sample(0, 0) >>> 24, "The real loaded mask must remain present");
                            }
                        }
                    } finally { damage.dispose(); }
                }
            } finally { megamek.common.Configuration.setDataDir(previous); }
        });
    }

    @Test
    void modelGhostsBlendLinearDisplayLightAndRestoreHudFramebufferAndDepth() {
        nativeCheck(() -> {
            var query = BufferUtils.newIntBuffer(4);
            Gdx.gl.glGetFramebufferAttachmentParameteriv(GL30.GL_DRAW_FRAMEBUFFER, 0x0402,
                  GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, query);
            System.out.println("Linear overlay renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER)
                  + "; default encoding=" + query.get(0));
            var builder = new com.badlogic.gdx.graphics.glutils.GLFrameBuffer.FrameBufferBuilder(32, 32);
            builder.addColorTextureAttachment(GL30.GL_SRGB8_ALPHA8, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE);
            builder.addDepthRenderBuffer(GL30.GL_DEPTH_COMPONENT24);
            FrameBuffer srgb = builder.build(), ordinary = GpuAtmosphere.buffer(32, 32, true);
            var linear = new com.badlogic.gdx.graphics.g3d.ModelBatch(GpuUnitShader.linearDisplayProvider());
            var encoded = new com.badlogic.gdx.graphics.g3d.ModelBatch(GpuUnitShader.displayProvider());
            var material = new com.badlogic.gdx.graphics.g3d.Material(
                  com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.createDiffuse(1, 1, 1, 1),
                  new com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute(.5f),
                  new com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute(GL20.GL_ALWAYS, false));
            var model = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createRect(
                  -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0, 0, 0, 1, material,
                  com.badlogic.gdx.graphics.VertexAttributes.Usage.Position
                        | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
            var instance = new com.badlogic.gdx.graphics.g3d.ModelInstance(model);
            var camera = new com.badlogic.gdx.graphics.OrthographicCamera(2, 2);
            camera.position.set(0, 0, 3); camera.near = .1f; camera.far = 10;
            camera.lookAt(0, 0, 0); camera.update();
            var fallbackField = GpuLinearOverlay.class.getDeclaredField("fallback");
            fallbackField.setAccessible(true);
            try {
                // Prove the real window's encoding and depth-copy contract, not only explicit FBOs.
                FrameBuffer.unbind();
                Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                Gdx.gl.glDepthMask(true); Gdx.gl.glClearDepthf(.37f);
                Gdx.gl.glClearColor(0, 0, 0, 1);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                float windowDepth = overlayDepth();
                var windowBlend = new GpuLinearOverlay();
                try {
                    windowBlend.begin();
                    try { linear.begin(camera); linear.render(instance); linear.end(); }
                    finally { windowBlend.end(); }
                    int[] windowPixel = overlayPixel();
                    for (int channel = 0; channel < 3; channel++) {
                        assertEquals(188, windowPixel[channel], 1, "Real window blends half-white in linear light");
                    }
                    assertEquals(windowDepth, overlayDepth(), 0, "Real window scene depth remains unchanged");
                    assertEquals(0, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                    assertEquals(0, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
                    assertFalse(Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB));
                    assertFalse(Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST));
                    query.clear(); Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query);
                    assertEquals(0, query.get(0)); assertEquals(0, query.get(1));
                    assertEquals(Gdx.graphics.getBackBufferWidth(), query.get(2));
                    assertEquals(Gdx.graphics.getBackBufferHeight(), query.get(3));
                } finally { windowBlend.dispose(); }
                for (FrameBuffer target : List.of(srgb, ordinary)) {
                    var overlay = new GpuLinearOverlay();
                    try {
                        target.begin();
                        Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
                        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                        Gdx.gl.glDepthMask(true);
                        Gdx.gl.glClearDepthf(.37f);
                        Gdx.gl.glClearColor(0, 0, 0, .4f);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                        float depth = overlayDepth();
                        overlay.begin();
                        try {
                            query.clear();
                            Gdx.gl.glGetFramebufferAttachmentParameteriv(GL30.GL_DRAW_FRAMEBUFFER,
                                  GL20.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, query);
                            assertEquals(GL30.GL_SRGB, query.get(0), "The actual blend attachment must encode sRGB");
                            assertTrue(Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB));
                            linear.begin(camera); linear.render(instance); linear.end();
                        } finally { overlay.end(); }
                        assertFalse(Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB), "HUD conversion state restored");
                        assertEquals(target.getFramebufferHandle(), GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                        assertEquals(depth, overlayDepth(), 0, "A ghost never rewrites scene/picking depth");
                        int[] first = overlayPixel();
                        for (int channel = 0; channel < 3; channel++) {
                            assertEquals(188, first[channel], 1, "Half white over black must blend linear, not encoded 128");
                        }
                        assertEquals(115, first[3], 1, "Display alpha retains the existing blend factors");
                        assertEquals(target == ordinary, fallbackField.get(overlay) != null,
                              "An actual sRGB target must not allocate a copy target");
                        Object cached = fallbackField.get(overlay);
                        // A second source blends against decoded destination RGB, not against its encoded bytes.
                        overlay.begin();
                        try { linear.begin(camera); linear.render(instance); linear.end(); }
                        finally { overlay.end(); }
                        assertSame(cached, fallbackField.get(overlay), "Fallback allocation is reused");
                        int[] second = overlayPixel();
                        for (int channel = 0; channel < 3; channel++) { assertEquals(225, second[channel], 1); }
                        assertEquals(121, second[3], 1);
                        // Scene2D and legacy tactical colors retain their encoded-space presentation afterward.
                        encoded.begin(camera); encoded.render(instance); encoded.end();
                        int[] hud = overlayPixel();
                        for (int channel = 0; channel < 3; channel++) { assertEquals(240, hud[channel], 1); }

                        // A colored source over a nonblack background catches wrong-domain RGB factor uploads.
                        instance.materials.first().set(com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
                              .createDiffuse(.25f, .5f, .75f, 1));
                        Gdx.gl.glClearColor(.2f, .35f, .6f, 1); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                        overlay.begin();
                        try { linear.begin(camera); linear.render(instance); linear.end(); }
                        finally { overlay.end(); }
                        int[] colored = overlayPixel();
                        // Independently evaluated standard sRGB inverse/forward around a half-alpha linear mix.
                        int[] expected = { 58, 110, 173 };
                        for (int channel = 0; channel < 3; channel++) { assertEquals(expected[channel], colored[channel], 1); }
                        instance.materials.first().set(com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
                              .createDiffuse(1, 1, 1, 1));

                        // Editor previews select hidden/visible passes against the copied opaque scene depth.
                        Gdx.gl.glDepthMask(true); Gdx.gl.glClearDepthf(.1f); Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
                        instance.materials.first().set(new com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute
                              (GL20.GL_LEQUAL, false));
                        overlay.begin();
                        try { linear.begin(camera); linear.render(instance); linear.end(); }
                        finally { overlay.end(); }
                        int[] occluded = overlayPixel();
                        for (int channel = 0; channel < 4; channel++) { assertEquals(colored[channel], occluded[channel]); }
                        instance.materials.first().set(new com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute
                              (GL20.GL_GREATER, false));
                        overlay.begin();
                        try { linear.begin(camera); linear.render(instance); linear.end(); }
                        finally { overlay.end(); }
                        assertTrue(overlayPixel()[0] > occluded[0] + 50, "Hidden stand-in pass uses the same opaque depth");
                        instance.materials.first().set(new com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute
                              (GL20.GL_ALWAYS, false));

                        // Viewport/scissor/read-target restoration is required even when an overlay draw fails.
                        Gdx.gl.glViewport(3, 4, 20, 16);
                        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); Gdx.gl.glScissor(5, 6, 9, 8);
                        Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
                        try {
                            overlay.begin();
                            try { throw new IllegalStateException("deliberate overlay failure"); }
                            finally { overlay.end(); }
                        } catch (IllegalStateException expectedFailure) {
                            assertEquals("deliberate overlay failure", expectedFailure.getMessage());
                        }
                        assertEquals(0, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
                        assertEquals(target.getFramebufferHandle(), GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                        query.clear(); Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query);
                        assertEquals(3, query.get(0)); assertEquals(4, query.get(1));
                        assertEquals(20, query.get(2)); assertEquals(16, query.get(3));
                        query.clear(); Gdx.gl.glGetIntegerv(GL20.GL_SCISSOR_BOX, query);
                        assertEquals(5, query.get(0)); assertEquals(6, query.get(1));
                        assertEquals(9, query.get(2)); assertEquals(8, query.get(3));
                        assertTrue(Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST));
                        assertFalse(Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB));
                    } finally { overlay.dispose(); }
                }
            } finally {
                Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST); Gdx.gl.glClearDepthf(1);
                FrameBuffer.unbind();
                model.dispose(); linear.dispose(); encoded.dispose(); ordinary.dispose(); srgb.dispose();
            }
        });
    }

    private static int[] overlayPixel() {
        var pixel = BufferUtils.newByteBuffer(4);
        Gdx.gl.glReadPixels(16, 16, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixel);
        return new int[] { pixel.get(0) & 255, pixel.get(1) & 255, pixel.get(2) & 255, pixel.get(3) & 255 };
    }

    private static float overlayDepth() {
        var depth = BufferUtils.newFloatBuffer(1);
        Gdx.gl.glReadPixels(16, 16, 1, 1, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        return depth.get(0);
    }

    @Test
    void filteringAndGeneratedMipsAverageLinearRgbWithoutTransformingAlphaOrData() {
        nativeCheck(() -> {
            Pixmap pixels = pattern();
            Texture color = GpuColorTexture.create(pixels, true);
            pixels.dispose();
            pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0x80808080);
            Texture data = new Texture(pixels);
            pixels.dispose();
            var radiance = BufferUtils.newFloatBuffer(4);
            radiance.put(new float[] { 2, .18f, .5f, .25f }).flip();
            Texture hdr = GpuColorTexture.linear(radiance, 1, 1, false);
            try (var sample = new Sampler(false)) {
                color.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                assertPixel(sample.read(color, .5f, .5f, 0, 0), .5, .5, .5, 128.0 / 255, .001,
                      "Bilinear color filtering must decode before averaging");
                assertPixel(sample.read(color, .5f, .5f, 0, 1), .5, .5, .5, 128.0 / 255, .004,
                      "Generated sRGB mips average linear light, with only storage quantization");
                assertPixel(sample.read(data, .5f, .5f, 0, 0), 128.0 / 255, 128.0 / 255, 128.0 / 255,
                      128.0 / 255, .001, "Data textures retain all numerical channels");
                assertTrue(GpuColorTexture.isColor(hdr), "Direct linear uploads share the scene color contract");
                assertPixel(sample.read(hdr, .5f, .5f, 0, 0), 2, .18, .5, .25, .001,
                      "CPU linear radiance uploads preserve HDR without encoding or alpha conversion");
                // Grayscale source art expands channel layout before its core-GL sRGB upload.
                Pixmap grey = new Pixmap(1, 1, Pixmap.Format.LuminanceAlpha);
                // Seed the file-decoder's L/A byte layout directly: this libGDX version's drawPixel conversion
                // swaps these channels, which would test a malformed fixture instead of the texture uploader.
                grey.getPixels().put(0, (byte) 128).put(1, (byte) 64);
                assertEquals(0x80808040, grey.getPixel(0, 0), "The fixture contains gray RGB and independent alpha");
                Texture greyColor = GpuColorTexture.create(grey, false);
                try {
                    assertPixel(sample.read(greyColor, .5f, .5f, 0, 0), .21586050, .21586050, .21586050,
                          64.0 / 255, .001, "Grayscale artwork keeps its decoded RGB and raw coverage");
                } finally { greyColor.dispose(); grey.dispose(); }
                Pixmap mask = new Pixmap(1, 1, Pixmap.Format.Alpha);
                mask.getPixels().put(0, (byte) 64);
                Texture maskColor = GpuColorTexture.create(mask, false);
                try {
                    assertPixel(sample.read(maskColor, .5f, .5f, 0, 0), 1, 1, 1, 64.0 / 255, .001,
                          "Caller-created alpha-only artwork remains a coverage mask");
                } finally { maskColor.dispose(); mask.dispose(); }
            } finally {
                hdr.dispose(); data.dispose(); color.dispose();
            }
        });
    }

    @Test
    void grayscaleModelImagesPreserveColorAndDataForEmbeddedFilesAndManagedReloads() {
        nativeCheck(() -> {
            try (var sample = new Sampler(false)) {
                for (String name : List.of("gray.png", "gray-alpha.png", "gray.jpg")) {
                    boolean alpha = name.contains("alpha");
                    String format = name.endsWith("png") ? "png" : "jpg";
                    byte[] encoded = grayscaleImage(format, alpha);
                    Pixmap decoded = new Pixmap(encoded, 0, encoded.length);
                    try {
                        assertEquals(alpha ? Pixmap.Format.LuminanceAlpha : Pixmap.Format.Alpha, decoded.getFormat(),
                              "The encoded fixture really exercises a grayscale decoder layout");
                        assertEquals(128, Byte.toUnsignedInt(decoded.getPixels().get(0)));
                    } finally { decoded.dispose(); }
                    for (boolean external : new boolean[] { false, true }) {
                        var fixture = new RigidGlbTest();
                        fixture.directory = Files.createDirectories(directory.resolve(name + (external ? "-file" : "-embedded")));
                        var data = (RigidGlb.Data) RigidGlb.loadLods(fixture.embedded(format, encoded)).getFirst();
                        if (external) {
                            Path image = fixture.directory.resolve(name);
                            Files.write(image, encoded);
                            data.images.replaceAll((key, value) -> new RigidGlb.Image(key, null, image.toString(),
                                  value.minFilter(), value.magFilter(), value.wrapS(), value.wrapT()));
                        }
                        var cache = new HashMap<String, Texture>();
                        var model = ModelTextures.create(data, cache, (key, color) -> {
                            throw new AssertionError("The GLB image descriptor resolves its own file or embedded bytes");
                        });
                        try {
                            Texture color = model.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse)
                                  .textureDescription.texture;
                            Texture numerical = model.materials.first().get(GpuModelMaterial.class, GpuModelMaterial.TYPE).map;
                            double coverage = alpha ? 64.0 / 255 : 1;
                            for (int upload = 0; upload < 2; upload++) {
                                assertTrue(GpuColorTexture.isColor(color));
                                assertFalse(GpuColorTexture.isColor(numerical));
                                for (int mip = 0; mip < 2; mip++) {
                                    assertPixel(sample.read(color, .5f, .5f, 0, mip), .21586050, .21586050, .21586050,
                                          coverage, .001, name + " color luminance and coverage");
                                    assertPixel(sample.read(numerical, .5f, .5f, 0, mip), 128.0 / 255, 128.0 / 255, 128.0 / 255,
                                          coverage, .001, name + " numerical luminance and coverage");
                                }
                                if (upload == 0) {
                                    assertTrue(color.isManaged());
                                    assertTrue(numerical.isManaged());
                                    color.load(color.getTextureData());
                                    numerical.load(numerical.getTextureData());
                                }
                            }
                        } finally {
                            model.dispose();
                            cache.values().forEach(Texture::dispose);
                        }
                    }
                }
            }
        });
    }

    private static byte[] grayscaleImage(String format, boolean alpha) throws Exception {
        var model = new java.awt.image.ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY),
              alpha, false, alpha ? java.awt.Transparency.TRANSLUCENT : java.awt.Transparency.OPAQUE, java.awt.image.DataBuffer.TYPE_BYTE);
        var raster = java.awt.image.Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, 2, 2, alpha ? 2 : 1, null);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                raster.setSample(x, y, 0, 128);
                if (alpha) { raster.setSample(x, y, 1, 64); }
            }
        }
        var image = new BufferedImage(model, raster, false, null);
        var encoded = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, format, encoded));
        return encoded.toByteArray();
    }

    @Test
    void oddSizedPaintMipsPreserveEdgeColorAndCoverage() {
        nativeCheck(() -> {
            var load = GpuAssets.class.getDeclaredMethod("paintTexture", java.io.File.class);
            load.setAccessible(true);
            try (var sample = new Sampler(false)) {
                for (boolean vertical : new boolean[] { false, true }) {
                    for (boolean transparent : new boolean[] { false, true }) {
                        var pixels = new Pixmap(vertical ? 1 : 3, vertical ? 3 : 1, Pixmap.Format.RGBA8888);
                        pixels.setBlending(Pixmap.Blending.None);
                        pixels.setColor(transparent ? 0x00000000 : 0x000000FF);
                        pixels.fill();
                        pixels.drawPixel(vertical ? 0 : 2, vertical ? 2 : 0, 0xFFFFFFFF);
                        var file = directory.resolve("edge-" + vertical + "-" + transparent + ".png").toFile();
                        try { PixmapIO.writePNG(new FileHandle(file), pixels); } finally { pixels.dispose(); }
                        Texture texture = (Texture) load.invoke(null, file);
                        try {
                            double color = transparent ? 1 : 1.0 / 3, alpha = transparent ? 1.0 / 3 : 1;
                            assertPixel(sample.read(texture, .5f, .5f, 0, 1), color, color, color, alpha, .004,
                                  "The odd edge contributes its full area to straight-alpha linear paint mips");
                        } finally { texture.dispose(); }
                    }
                }
            }
        });
    }

    @Test
    void sceneAtlasUpdatesAndReloadsKeepSrgbWhileUiPagesStayNumeric() {
        nativeCheck(() -> {
            var scene = new GpuTextures<String>(true, true);
            var ui = new GpuTextures<String>(true);
            var first = pixels(0x80808080);
            var second = pixels(0x40404040);
            try (var sample = new Sampler(false)) {
                var uploads = new java.util.ArrayList<Integer>();
                int[] mipBuilds = { 0 };
                var originalGl = Gdx.gl20;
                try (var watch = new GpuGlWatch((proxy, method, arguments) -> {
                    if (method.getName().equals("glTexImage2D")) { uploads.add((Integer) arguments[2]); }
                    if (method.getName().equals("glGenerateMipmap")) { mipBuilds[0]++; }
                    return method.invoke(originalGl, arguments);
                })) {
                    scene.update(Map.of("a", first));
                }
                assertEquals(List.of(com.badlogic.gdx.graphics.GL30.GL_SRGB8_ALPHA8), uploads,
                      "The color page uses sRGB storage from its one initial upload");
                assertEquals(1, mipBuilds[0], "Generate the final page mip chain once");
                ui.update(Map.of("a", first));
                TextureRegion region = scene.region("a");
                assertTrue(GpuColorTexture.isColor(region.getTexture()));
                assertFalse(GpuColorTexture.isColor(ui.region("a").getTexture()));
                assertEquals(.21586050, sample.read(region)[0], .001);
                assertEquals(128.0 / 255, sample.read(ui.region("a"))[0], .001);

                assertFalse(scene.update(Map.of("a", second)), "An in-place repaint keeps its UVs");
                assertSame(region, scene.region("a"));
                assertPixel(sample.read(region), .05126946, .05126946, .05126946, 64.0 / 255, .001,
                      "Partial page upload retains sRGB storage and straight alpha");
                region.getTexture().load(region.getTexture().getTextureData());
                assertEquals(.05126946, sample.read(region)[0], .001, "Managed reupload uses the updated page backing pixels");

                // A new slot shares the existing page; its upload must retain the color storage contract.
                scene.update(Map.of("a", second, "b", first));
                assertEquals(.05126946, sample.read(scene.region("a"))[0], .001);
                assertEquals(.21586050, sample.read(scene.region("b"))[0], .001);
                assertTrue(GpuColorTexture.isColor(scene.region("b").getTexture()));
            } finally {
                ui.dispose(); scene.dispose();
            }
        });
    }

    @Test
    void appendedAtlasPagesKeepManagedPixelsUntilReplacementPublishes() {
        nativeCheck(() -> {
            var scene = new GpuTextures<String>(true, true);
            scene.retainReplacedPages();
            var first = pixels(0x80808080, 100);
            var second = pixels(0x40404040, 100);
            try (var sample = new Sampler(false)) {
                scene.update(Map.of("a", first));
                scene.publish();
                TextureRegion original = scene.region("a");
                // Two 100px slots cannot share the original 128px page. Adding a key appends a page,
                // rather than repainting or rebuilding the page already borrowed by displayed meshes.
                scene.update(Map.of("a", first, "b", second));
                assertSame(original, scene.region("a"));
                TextureRegion added = scene.region("b");
                assertTrue(original.getTexture() != added.getTexture(), "Exercise a genuinely new atlas page");
                var oldPages = List.of(original.getTexture(), added.getTexture());
                var handles = oldPages.stream().map(Texture::getTextureObjectHandle).toList();
                // An oversized replacement forces a new atlas while published old pages stay alive.
                scene.update(Map.of("a", pixels(0x80808080, 130), "b", second));
                for (Texture texture : oldPages) { texture.load(texture.getTextureData()); }
                assertEquals(.21586050, sample.read(original)[0], .001,
                      "Retired original page still owns its managed backing pixels");
                assertEquals(.05126946, sample.read(added)[0], .001,
                      "Retired appended page still owns its managed backing pixels");
                scene.releaseRetiredPages();
                for (int handle : handles) { assertTrue(org.lwjgl.opengl.GL11.glIsTexture(handle)); }
                scene.publish();
                scene.releaseRetiredPages();
                for (Texture texture : oldPages) { assertEquals(0, texture.getTextureObjectHandle()); }
                for (int handle : handles) { assertFalse(org.lwjgl.opengl.GL11.glIsTexture(handle)); }
                TextureRegion current = scene.region("a");
                current.getTexture().load(current.getTexture().getTextureData());
                assertEquals(.21586050, sample.read(current)[0], .001);
            } finally { scene.dispose(); }
        });
    }

    @Test
    void mixedMaterialArraysDecodeOnlyColorRgbAndResizeInLinearSpace() {
        nativeCheck(() -> {
            FileHandle constant = new FileHandle(directory.resolve("constant.png").toFile());
            FileHandle varying = new FileHandle(directory.resolve("varying.png").toFile());
            FileHandle gray = new FileHandle(directory.resolve("gray.png").toFile());
            Files.write(gray.file().toPath(), grayscaleImage("png", false));
            Pixmap pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0x80808080);
            try { PixmapIO.writePNG(constant, pixels); } finally { pixels.dispose(); }
            pixels = pattern();
            try { PixmapIO.writePNG(varying, pixels); } finally { pixels.dispose(); }
            FileHandle wide = new FileHandle(directory.resolve("wide.png").toFile());
            pixels = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.setColor(0x80808080);
            pixels.fill();
            try { PixmapIO.writePNG(wide, pixels); } finally { pixels.dispose(); }
            // Exercise the actual owned pair, including its sized storage and compact material indices. The pair takes
            // its largest layer's grid (2 by 2), so the constant layer is resized up to it.
            var factory = GpuAssets.class.getDeclaredMethod("repeatingArray", List.class, int.class);
            factory.setAccessible(true);
            var arrays = (GpuAssets.MaterialArrays) factory.invoke(null, List.of(layer(constant), layer(constant),
                  layer(varying), layer(varying), layer(gray), layer(gray)), 2);
            try (var sample = new Sampler(true)) {
                arrays.color().bind();
                assertEquals(GL30.GL_SRGB8_ALPHA8, GL11.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D_ARRAY, 0,
                      GL11.GL_TEXTURE_INTERNAL_FORMAT), "Colour decodes in hardware before filtering");
                arrays.data().bind();
                assertEquals(GL30.GL_RGBA8, GL11.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D_ARRAY, 0,
                      GL11.GL_TEXTURE_INTERNAL_FORMAT), "Numerical storage has no colour transfer");
                assertEquals(3, arrays.color().getDepth());
                assertEquals(3, arrays.data().getDepth());
                assertEquals(2, arrays.color().getWidth(), "The largest layer sets the grid");
                assertPixel(sample.read(arrays.color(), .5f, .5f, 0, 0), .21586050, .21586050, .21586050,
                      128.0 / 255, .001, "Only colour RGB is decoded; height remains raw");
                assertPixel(sample.read(arrays.data(), .5f, .5f, 0, 0), 128.0 / 255, 128.0 / 255, 128.0 / 255,
                      128.0 / 255, .001, "Normal/data layers retain their numerical values");
                arrays.color().bind();
                var stored = BufferUtils.newByteBuffer(3 * 4 * 4);
                GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, stored);
                for (int index = 0; index < 4 * 4; index++) {
                    assertEquals(128, Byte.toUnsignedInt(stored.get(index)), "A uniform layer keeps its bytes when resized");
                }
                assertPixel(sample.read(arrays.color(), .5f, .5f, 2, 0), .21586050, .21586050, .21586050, 1, .001,
                      "File-decoded one-channel artwork is opaque grayscale");
                assertPixel(sample.read(arrays.data(), .5f, .5f, 2, 0), 128.0 / 255, 128.0 / 255, 128.0 / 255, 1, .001,
                      "File-decoded one-channel data remains numerical grayscale");
            } finally { arrays.dispose(); }
            assertEquals(0, arrays.color().getTextureObjectHandle(), "Pair disposal releases colour");
            assertEquals(0, arrays.data().getTextureObjectHandle(), "Pair disposal releases data");
            // Black and white columns widened from 2 to 4 texels by a wider layer: colour resizes in linear light
            // (0, .25, .75, 1 store sRGB bytes 0, 137, 225, 255), numerical data and coverage in their raw values.
            arrays = (GpuAssets.MaterialArrays) factory.invoke(null, List.of(layer(varying), layer(varying),
                  layer(wide), layer(wide)), 2);
            try (var sample = new Sampler(true)) {
                int[] colour = { 0, 137, 225, 255 }, data = { 0, 64, 191, 255 }, coverage = { 64, 96, 160, 192 };
                for (boolean isColour : new boolean[] { true, false }) {
                    (isColour ? arrays.color() : arrays.data()).bind();
                    var stored = BufferUtils.newByteBuffer(2 * 16 * 4);
                    GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, stored);
                    for (int x = 0; x < 4; x++) {
                        for (int channel = 0; channel < 4; channel++) {
                            int expected = channel == 3 ? coverage[x] : isColour ? colour[x] : data[x];
                            assertEquals(expected, Byte.toUnsignedInt(stored.get(x * 4 + channel)),
                                  (isColour ? "Linear colour" : "Raw data") + " resize, texel " + x + " channel " + channel);
                        }
                    }
                }
                // Use the same hardware decoder, whose permitted precision need not match CPU sRGB exactly.
                Pixmap referencePixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
                Texture reference;
                try {
                    referencePixels.setBlending(Pixmap.Blending.None);
                    referencePixels.drawPixel(0, 0, 0x89898960);
                    reference = GpuColorTexture.create(referencePixels, false);
                } finally { referencePixels.dispose(); }
                try (var referenceSample = new Sampler(false)) {
                    float[] expected = referenceSample.read(reference, .5f, .5f, 0, 0);
                    float[] actual = sample.read(arrays.color(), 1.5f / 4, .5f, 0, 0);
                    assertPixel(actual, expected[0], expected[1], expected[2], expected[3], 0,
                          "Resized array and fixed-byte colour texture sample identically");
                } finally { reference.dispose(); }
                // Halfway between the stored bytes 137 and 225, which decode to .2501 and .7529.
                double between = (GpuColor.linear(137 / 255f) + GpuColor.linear(225 / 255f)) / 2;
                assertPixel(sample.read(arrays.color(), .5f, .5f, 0, 0), between, between, between, 128.0 / 255, .001,
                      "Array bilinear filtering decodes before averaging");
                assertPixel(sample.read(arrays.color(), .5f, .5f, 0, 1), .5, .5, .5, 128.0 / 255, .004,
                      "Array mip generation averages linear light within sRGB8 storage precision");
                assertPixel(sample.read(arrays.data(), .5f, .5f, 0, 1), .5, .5, .5, 128.0 / 255, .003,
                      "Numerical mips average raw channels within UNORM8 storage precision");
            } finally { arrays.dispose(); }
        });
    }

    /** A repeating-array layer of one image, at the dimensions its header declares. */
    private static GpuAssets.Layer layer(FileHandle file) {
        int[] size = GpuMaterialMaps.size(file.file());
        return new GpuAssets.Layer(size[0], size[1], () -> GpuColorTexture.decode(file));
    }

    private static Pixmap pattern() {
        Pixmap pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < 2; y++) {
            pixels.drawPixel(0, y, 0x00000040);
            pixels.drawPixel(1, y, 0xFFFFFFC0);
        }
        return pixels;
    }

    @Test
    void composedArtworkResizesLinearRgbAndPreservesCoverage() {
        nativeCheck(() -> {
            String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
            System.out.println("Material storage renderer: " + renderer);
            String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "");
            assertTrue(renderer.toLowerCase(java.util.Locale.ROOT)
                  .contains(expectedRenderer.toLowerCase(java.util.Locale.ROOT)), renderer);
            Pixmap pixels = new Pixmap(2, 1, Pixmap.Format.RGBA8888);
            pixels.setBlending(Pixmap.Blending.None);
            pixels.drawPixel(0, 0, 0x00000000);
            pixels.drawPixel(1, 0, 0xFFFFFFFF);
            var source = new GpuLinearImage(pixels);
            pixels.dispose();
            var resized = new GpuLinearImage(1, 1);
            resized.draw(source, 0, 0, 2, 1, 0, 0, 1, 1);
            Texture texture = resized.texture(false);
            Texture mipmapped = source.texture(true);
            try (var sample = new Sampler(false)) {
                for (Texture composed : new Texture[] { texture, mipmapped }) {
                    assertTrue(GpuColorTexture.isColor(composed));
                    composed.bind();
                    assertEquals(GL30.GL_SRGB8_ALPHA8,
                          GL11.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT),
                          "Bounded composed artwork occupies four bytes per texel");
                }
                var linear = resized.buffer();
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(.5f, linear.get(channel), "Composition averages linear RGB and independent coverage");
                }
                // Linear .5 rounds to sRGB byte 188; coverage .5 rounds independently to byte 128.
                texture.bind();
                var stored = BufferUtils.newByteBuffer(4);
                GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, stored);
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(channel == 3 ? 128 : 188, Byte.toUnsignedInt(stored.get(channel)),
                          "CPU composition precedes the one sRGB storage encode channel=" + channel);
                }
                // Compare identical stored bytes through the same hardware decoder and RGBA16F target.
                Pixmap referencePixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
                Texture reference;
                try {
                    referencePixels.setBlending(Pixmap.Blending.None);
                    referencePixels.drawPixel(0, 0, 0xBCBCBC80);
                    reference = GpuColorTexture.create(referencePixels, false);
                } finally { referencePixels.dispose(); }
                try {
                    float[] expected = sample.read(reference, .5f, .5f, 0, 0);
                    float[] actual = sample.read(texture, .5f, .5f, 0, 0);
                    assertPixel(actual, expected[0], expected[1], expected[2], expected[3], 0,
                          "Composed and fixed-byte artwork have exactly the same sampling result");
                    assertEquals(128.0 / 255, actual[3], .001, "Sampling preserves independent coverage");
                } finally { reference.dispose(); }
                mipmapped.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                assertPixel(sample.read(mipmapped, .5f, .5f, 0, 1), .5, .5, .5, .5, .004,
                      "Composed texture mips still average linear RGB and independent alpha within storage quantization");
            } finally { mipmapped.dispose(); texture.dispose(); }
        });
    }

    private static final List<String> SURFACES = List.of("terrain/surfaces/mapped", "terrain/surfaces/scalar",
          "terrain/surfaces/rough");

    @Test
    void finishArraysKeepRawValuesMetadataAndOwnership() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            megamek.common.Configuration.setDataDir(directory.toFile());
            var assets = new GpuAssets();
            int unpack = GL11.glGetInteger(GL20.GL_UNPACK_ALIGNMENT);
            try (var sample = new Sampler(true)) {
                Path sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
                // The finish layers share the sculpt data array's grid: these (square) sculpt maps take the ORM's 3 x 3.
                for (String name : List.of("mapped", "scalar", "rough")) { sculptPair(sculpt, name, 3, 3); }
                // Godot's rule: a varying map is a map (sent with its mean), the factor alone without a map is a scalar,
                // and the default (roughness 1 over white, metallic 0) is fully rough and dielectric.
                int[] values = { 17, 61, 103, 149, 193, 239, 127, 0, 254 };
                roughnessMap(sculpt.resolve("mapped_orm.png"), 3, 3, values);
                Path mapped = sculpt.resolve("mapped.tres");
                Files.writeString(mapped, Files.readString(mapped)
                      + "metallic = 0.5\nmetallic_texture = ExtResource( 2 )\nmetallic_texture_channel = 2\n");
                Path scalar = sculpt.resolve("scalar.tres");
                Files.writeString(scalar, Files.readString(scalar).replace("roughness = 1.0", "roughness = 0.27")
                      .replace("roughness_texture = ExtResource( 2 )\n", "")
                      .replace("[resource]", "[ext_resource path=\"scalar_emission.png\" type=\"Texture\" id=5]\n[resource]")
                      + "emission_enabled = true\nemission_energy_multiplier = 2.0\nemission_texture = ExtResource( 5 )\n");
                var glow = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
                glow.setRGB(0, 0, 0xff8000);
                javax.imageio.ImageIO.write(glow, "png", sculpt.resolve("scalar_emission.png").toFile());
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 8);
                var material = assets.finishArrays(SURFACES, SURFACES.size());
                assertEquals(8, GL11.glGetInteger(GL20.GL_UNPACK_ALIGNMENT), "Odd rows must restore unpack state");
                assertSame(material, assets.finishArrays(SURFACES, SURFACES.size()));
                // The ORM's blue channel is 0 in the mapped material: its metal is uniform, the factor times black.
                // The finish layers follow the three normal/AO layers in the sculpt data array.
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                      new float[] { 127 / 255f, 0, 0, 3, .27f, 0, 0, -1, 1, 0, 0, -1 }, material.surface(), 1e-6f);
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                      new float[] { 0, 0, 0, -1, 2, 2, 2, 4, 0, 0, 0, -1 }, material.tint(), 1e-6f,
                      "The emission layer follows the surface layers in the one array");
                org.junit.jupiter.api.Assertions.assertArrayEquals(new float[9], material.glow(), 1e-6f,
                      "Godot's default emission colour is black");
                material.maps().bind();
                assertEquals(GL30.GL_RGBA8, GL11.glGetTexLevelParameteri(GL30.GL_TEXTURE_2D_ARRAY, 0,
                      GL11.GL_TEXTURE_INTERNAL_FORMAT), "The finish is raw data; emission's sRGB bytes decode in the shader");
                assertEquals(3, material.maps().getWidth());
                assertEquals(3, material.maps().getHeight());
                assertSame(assets.sculptArray(List.of("mapped", "scalar", "rough")).data(), material.maps(),
                      "The finish rides the sculpt data array: no texture unit of its own");
                assertEquals(5, material.maps().getDepth(), "Three normal/AO layers, one surface and one emission layer");
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 3; x++) {
                        assertEquals(values[y * 3 + x] / 255.0,
                              sample.read(material.maps(), (x + .5f) / 3, (y + .5f) / 3, 3, 0)[0], .0006,
                              "Raw roughness must not be sRGB-decoded, resized or row-padded");
                    }
                }
                assertAuthoredFinish(material);
                int maps = material.maps().getTextureObjectHandle();
                assertTrue(GL11.glIsTexture(maps));
                assets.dispose();
                assertEquals(0, material.maps().getTextureObjectHandle());
                assertFalse(GL11.glIsTexture(maps), "The asset owner releases the array");
                var reloaded = assets.finishArrays(SURFACES, SURFACES.size());
                org.junit.jupiter.api.Assertions.assertNotSame(material, reloaded);
                assertEquals(103 / 255.0, sample.read(reloaded.maps(), 2.5f / 3, .5f / 3, 3, 0)[0], .0006);
            } finally {
                assets.dispose();
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, unpack);
                megamek.common.Configuration.setDataDir(previous);
            }
        });
    }

    /** Use the actual shared shader with provider metadata: an authored map, a scalar and a fully rough default. */
    private static void assertAuthoredFinish(GpuAssets.FinishArrays material) {
        var quad = GpuAtmosphere.screenQuad();
        var shader = GpuGlsl.compile("Authored finish contract", GpuShaderSource.read("atmosphere.vert"),
              "#version 330 core\n#define FINISH_COUNT 3\n#define ROAD_FINISH 3\n" + GpuShaderSource.read("material-finish.glsl")
                    + GpuShaderSource.read("finish-arrays.glsl") + """
                    out vec4 fragColor;
                    uniform float u_material;
                    void main() {
                        Finish finish = finishTexel(u_material, vec2(.5 / 3.0, .5 / 3.0), mat2(0.0));
                        fragColor = vec4(finish.roughness, finish.emission.r / 4.0, finish.emission.g / 4.0, 1.0);
                    }
                    """);
        try {
            GpuAtmosphere.screenState();
            material.maps().bind(0);
            shader.bind();
            shader.setUniformi("u_surfaceLayers", 0);
            shader.setUniform4fv("u_finishSurface[0]", material.surface(), 0, material.surface().length);
            shader.setUniform4fv("u_finishEmission[0]", material.tint(), 0, material.tint().length);
            shader.setUniform3fv("u_finishGlow[0]", material.glow(), 0, material.glow().length);
            // The scalar's emission map is black but for its first texel: orange, decoded, times the energy 2.
            float[][] expected = { { 17 / 255f, 0, 0 }, { .27f, .5f, GpuColor.linear(128 / 255f) * .5f }, { 1, 0, 0 } };
            for (int i = 0; i < expected.length; i++) {
                shader.setUniformf("u_material", i * 2f);
                quad.render(shader, GL20.GL_TRIANGLES);
                var actual = BufferUtils.newByteBuffer(4);
                Gdx.gl.glReadPixels(1, 1, 1, 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, actual);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(expected[i][channel] * 255, Byte.toUnsignedInt(actual.get(channel)), 1,
                          "Actual shader: the authored map, else the scalar; emission its map times its tint");
                }
            }
        } finally { shader.dispose(); quad.dispose(); }
    }

    @Test
    void finishArraysResampleAndRejectIncompleteAuthoring() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            megamek.common.Configuration.setDataDir(directory.toFile());
            try {
                Path sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
                sculptPair(sculpt, "first", 3, 3);
                sculptPair(sculpt, "second", 2, 2);
                roughnessMap(sculpt.resolve("first_orm.png"), 3, 3, new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
                roughnessMap(sculpt.resolve("second_orm.png"), 2, 2, new int[] { 1, 2, 3, 4 });
                // A smaller map takes the largest grid, as the colour arrays resample theirs.
                var assets = new GpuAssets();
                try {
                    var mixed = assets.finishArrays(List.of("terrain/surfaces/first", "terrain/surfaces/second"), 2);
                    assertEquals(3, mixed.maps().getWidth());
                    assertEquals(3, mixed.maps().getHeight());
                    assertEquals(4, mixed.maps().getDepth(), "Two normal/AO layers, two surface layers, no emission layer");
                } finally { assets.dispose(); }
                // Built first by the sculpt arrays, without a road material they could not find: no shifted indices.
                var built = new GpuAssets();
                try {
                    built.sculptArray(List.of("first", "second"));
                    var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                          () -> built.finishArrays(List.of("terrain/surfaces/first", "terrain/surfaces/second",
                                "roads/asphalt"), 2));
                    assertTrue(error.getMessage().contains("roads/asphalt"), error.getMessage());
                } finally { built.dispose(); }
                Files.delete(sculpt.resolve("second_orm.png"));
                assertFinishRejected("second_orm.png");
                roughnessMap(sculpt.resolve("second_orm.png"), 3, 3, new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
                Path first = sculpt.resolve("first.tres");
                Files.writeString(first, Files.readString(first).replace("roughness = 1.0", "roughness = 1.5"));
                assertFinishRejected("Invalid roughness");
                Files.delete(first);
                assertFinishRejected("first.tres");
            } finally { megamek.common.Configuration.setDataDir(previous); }
        });
    }

    private static void assertFinishRejected(String message) {
        var assets = new GpuAssets();
        try {
            var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                  () -> assets.finishArrays(List.of("terrain/surfaces/first", "terrain/surfaces/second"), 2));
            assertTrue(error.getMessage().contains(message), error.getMessage());
        } finally { assets.dispose(); }
    }

    @Test
    void arrayBackedEarthMatchesTheCompleteSeparateMaterialAndRejectsMissingFiles() {
        nativeCheck(() -> {
            var previous = megamek.common.Configuration.dataDir();
            megamek.common.Configuration.setDataDir(directory.toFile());
            try (var array = new Sampler(true); var texture = new Sampler(false)) {
                Path sculpt = Files.createDirectories(directory.resolve("models/board/textures/terrain/surfaces"));
                for (String missing : List.of("none", "earth_albedo.png", "earth_heightmap.png", "earth_normal.png",
                      "earth_orm.png", "earth.tres")) {
                    sculptPair(sculpt, "earth");
                    if (!missing.equals("none")) { Files.delete(sculpt.resolve(missing)); }
                    var assets = new GpuAssets();
                    try {
                        if (!missing.equals("none")) {
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                  () -> assets.sculpt("earth"));
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                  () -> assets.sculptArray(List.of("earth")));
                            continue;
                        }
                        var separate = assets.sculpt("earth");
                        var combined = assets.sculptArray(List.of("earth"));
                        float[] color = texture.read(separate.color(), .5f, .5f, 0, 0);
                        float[] normal = texture.read(separate.normal(), .5f, .5f, 0, 0);
                        org.junit.jupiter.api.Assertions.assertArrayEquals(color,
                              array.read(combined.color(), .5f, .5f, 0, 0), .001f, "Complete color pair");
                        org.junit.jupiter.api.Assertions.assertArrayEquals(normal,
                              array.read(combined.data(), .5f, .5f, 0, 0), .001f, "Complete normal pair");
                        assertEquals(3, assets.sculptTile("earth"));
                    } finally { assets.dispose(); }
                }
            } finally { megamek.common.Configuration.setDataDir(previous); }
        });
    }

    /** A sculpted material repeating every 3 m: colour 0x1080e0 at height 0x40, the board's normal 0x40c0f0 with AO 0x60. */
    private static void sculptPair(Path sculpt, String name) throws Exception { sculptPair(sculpt, name, 64, 64); }

    private static void sculptPair(Path sculpt, String name, int width, int height) throws Exception {
        BoardMaterialFiles.sculpt(sculpt.getParent().getParent(), name, filled(0x401080e0, width, height),
              filled(0x6040c0f0, width, height), 3);
    }

    private static BufferedImage filled(int argb, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) { image.setRGB(x, y, argb); }
        return image;
    }

    /** An ORM map of full occlusion whose roughness (G) holds {@code values}. */
    private static void roughnessMap(Path path, int width, int height, int[] values) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { image.setRGB(x, y, 0xff0000 | values[y * width + x] << 8); }
        }
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }

    private static BoardScene.Pixels pixels(int rgba) { return pixels(rgba, 4); }

    private static BoardScene.Pixels pixels(int rgba, int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int argb = rgba >>> 8 | (rgba & 255) << 24;
        for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) { image.setRGB(x, y, argb); } }
        return new BoardScene.Pixels(image);
    }

    private static float centerU(TextureRegion region) { return (region.getU() + region.getU2()) * .5f; }
    private static float centerV(TextureRegion region) { return (region.getV() + region.getV2()) * .5f; }

    private static void assertPixel(float[] pixel, double r, double g, double b, double a, double tolerance, String message) {
        double[] expected = { r, g, b, a };
        for (int channel = 0; channel < 4; channel++) { assertEquals(expected[channel], pixel[channel], tolerance, message + " channel=" + channel); }
    }

    private static final class Sampler implements AutoCloseable {
        private final FrameBuffer target = GpuHeatGlow.buffer(4, 4);
        private final Mesh quad = GpuAtmosphere.screenQuad();
        private final ShaderProgram shader;

        Sampler(boolean array) {
            String fragment = """
                  #version 330 core
                  layout(location = 0) out vec4 fragColor;
                  uniform SAMPLER u_texture;
                  uniform vec3 u_uv;
                  uniform float u_lod;
                  void main() { fragColor = textureLod(u_texture, COORDINATE, u_lod); }
                  """.replace("SAMPLER", array ? "sampler2DArray" : "sampler2D")
                  .replace("COORDINATE", array ? "u_uv" : "u_uv.xy");
            shader = GpuGlsl.compile("Color texture sampling reference", GpuShaderSource.read("atmosphere.vert"), fragment);
        }

        float[] read(TextureRegion region) { return read(region.getTexture(), centerU(region), centerV(region), 0, 0); }

        float[] read(GLTexture texture, float u, float v, float layer, float lod) {
            target.begin();
            try {
                GpuAtmosphere.screenState();
                texture.bind(0);
                shader.bind();
                shader.setUniformi("u_texture", 0);
                shader.setUniformf("u_uv", u, v, layer);
                shader.setUniformf("u_lod", lod);
                quad.render(shader, GL20.GL_TRIANGLES);
                var pixels = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(2, 2, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
                float[] result = new float[4]; pixels.get(result); return result;
            } finally { target.end(); }
        }

        @Override public void close() { shader.dispose(); quad.dispose(); target.dispose(); }
    }

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static void nativeCheck(Check check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(320, 240);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { check.run(); assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError()); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Color texture integration", failure.get()); }
    }
}
