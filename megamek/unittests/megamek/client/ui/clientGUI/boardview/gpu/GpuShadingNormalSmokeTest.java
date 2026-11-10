/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A visible geometric face must not go dark when its shading normal crosses the eye's tangent plane. */
@Tag("on-demand")
class GpuShadingNormalSmokeTest {
    private static final String PROBE = """
          uniform vec3 u_normal, u_face, u_view, u_light;
          uniform float u_roughness;
          uniform int u_mode;
          layout(location=0) out vec4 fragColor;
          void main() {
              vec3 n = u_mode == 0 ? u_normal : shadingNormal(u_normal,u_face,u_view);
              if (u_mode == 2) {
                  fragColor=vec4(n,dot(reflect(-u_view,n),u_face));
                  return;
              }
              float reflected=diffuseReflectance(n,u_light,u_view,u_roughness,vec3(.04)).r;
              float direct=surfaceReflectance(n,u_light,u_view,u_roughness,vec3(.04)).r;
              float environment=environmentSpecular(n,u_view,u_roughness,vec3(.04),vec3(1),vec3(1)).r;
              if (u_mode == 3) {
                  float through=diffuseReflectance(-n,u_light,-u_view,u_roughness,vec3(.04)).r;
                  fragColor=vec4(reflected*float(dot(u_face,u_light)>0.0),
                        through*float(dot(u_face,u_light)<0.0),direct*float(dot(u_face,u_light)>0.0),1);
              } else {
                  fragColor=vec4(reflected,direct,environment,1);
              }
          }
          """;

    @Test
    void visibleMappedFacesKeepContinuousReflectionAndSeparateTransmission() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(128, 128);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> {
                        try {
                            String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                            System.out.println("Grazing-normal GL_RENDERER=" + renderer);
                            String expected = System.getProperty("megamek.gpu.expectedRenderer");
                            if (expected != null) { assertEquals(expected, renderer, "Requested native adapter"); }
                            boolean baseline = Boolean.getBoolean("megamek.gpu.grazingBaseline");
                            mathematicalBoundary(baseline);
                            if (!baseline) { mappedModel(manager); terrainSurfaces(manager); }
                            proxyAngles(manager, baseline);
                            if (Boolean.getBoolean("megamek.gpu.proxyClumps")) { clumpFrames(manager); }
                        }
                        catch (Throwable error) { failure.set(error); }
                    });
                } finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Shading-normal boundary", failure.get()); }
    }

    @Test
    void bladeRootShelterPreservesPigmentAndSeparateVisibility() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(128, 128);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> {
                        try {
                            String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
                            System.out.println("Blade-root GL_RENDERER=" + renderer);
                            String expected = System.getProperty("megamek.gpu.expectedRenderer");
                            if (expected != null) { assertEquals(expected, renderer, "Requested native adapter"); }
                            bladeRootShelter(manager);
                        } catch (Throwable error) { failure.set(error); }
                    });
                } finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Blade-root material visibility", failure.get()); }
    }

    /** Observe the real material fields; root controls do not replace pigment, geometry, or either lighting equation. */
    private static void bladeRootShelter(GpuShaderManager manager) throws Exception {
        String source = GpuShaderSource.readDisk("terrain-vegetation.frag");
        source = source.replace("v_coverData.y", "reviewRoot").replace("void main() {", """
              uniform int u_grazingReview;
              void main() {
                  int reviewControl = u_grazingReview / 10;
                  float reviewRoot = reviewControl == 0 ? v_coverData.y : float(reviewControl - 1) * .5;
              """);
        int end = source.lastIndexOf('}');
        // Tens select original/zero/half/full root input; units select production/albedo/visibility/incoming pigment.
        source = source.substring(0, end) + """
              #ifdef lightingFlag
                  float reviewRole = .25;
              #ifdef biomeVegetationFlag
                  reviewRole = .75;
              #endif
                  int reviewChannel = u_grazingReview % 10;
                  if (reviewChannel == 1) fragColor = vec4(blade.albedo, reviewRole);
                  if (reviewChannel == 2) fragColor = vec4(blade.occlusion, blade.specularOcclusion, reviewRoot, reviewRole);
                  if (reviewChannel == 3) fragColor = vec4(v_color.rgb, reviewRole);
              #endif
              """ + source.substring(end);
        applySources(manager, Map.of("terrain-vegetation.frag", source), "blade-root-fields");
        var originalGeometry = BoardGeometry.tuning();
        var originalLod = TerrainLod.tuning();
        var terrain = new GpuTerrain();
        try (var output = new GpuSceneTestOutput(128, 128)) {
            BoardScenes.tune(.94f, true);
            TerrainLod.tune(new TerrainLod.Tuning(1, 1));
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            terrain.setGrass(true);
            terrain.setWetness(0);
            var field = GpuTerrain.class.getDeclaredField("batch"); field.setAccessible(true);
            ModelBatch batch = (ModelBatch) field.get(terrain);
            float dpi = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
            var camera = new BoardCamera(); camera.resize(Math.round(128 / dpi), Math.round(128 / dpi));
            camera.camera.viewportWidth = 128 / dpi; camera.camera.viewportHeight = 128 / dpi;
            camera.setIsometric(true);
            for (String kind : new String[] { "grass", "bank", "crop", "reed" }) {
                boolean bank = kind.equals("bank"), grass = kind.equals("grass");
                // A field's plants and a bank rim's are blades of the grass program, as the grass is.
                boolean blades = grass || bank || kind.equals("crop");
                BoardScene scene;
                camera.camera.zoom = BoardGeometry.width() * dpi / 560;
                camera.center(BoardGeometry.center(new megamek.common.board.Coords(4, 4), 0));
                if (grass || bank) {
                    scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                          BoardScene.Surface.GRASS, bank ? (c.getX() < 4 ? 3 : 1) : 0, -1, 0));
                    if (bank) {
                        var center = BoardGeometry.center(new megamek.common.board.Coords(3, 4), 3);
                        var outward = BoardGeometry.center(new megamek.common.board.Coords(4, 4), 3).sub(center).nor();
                        var focus = center.cpy().mulAdd(outward, BoardGeometry.width() * .38f);
                        focus.z -= BoardGeometry.level();
                        camera.camera.position.set(focus).mulAdd(outward.rotate(Vector3.Z, -50), 650).add(0, 0, 350);
                        camera.camera.up.set(Vector3.Z); camera.camera.lookAt(focus); camera.camera.update();
                    }
                } else {
                    var biome = kind.equals("crop") ? BoardScene.Biome.FIELD : BoardScene.Biome.MARSH;
                    scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                          c.getX() >= 3 && c.getX() <= 5 && c.getY() >= 3 && c.getY() <= 5
                                ? biome : BoardScene.Biome.MUD, 0));
                }
                terrain.update(scene, camera.camera);
                GpuReviewFrame.settle(terrain, null, scene, camera);
                terrain.animate(0, java.util.List.of());
                terrain.renderShadows(camera.camera, java.util.List.of());
                Runnable draw = () -> terrain.render(camera.camera);
                setProxyMode(batch, 0);
                specimenFrame(output, terrain, camera.camera, draw); // Install lazy production programs before uniforms.
                // Plants arriving on a hex the previous specimen showed bare grow in over half a second from this first
                // frame (GpuGroundCover.GROWTH_NANOS): every probe frame must see them grown.
                Thread.sleep(600);
                ProxyFrame full = specimenFrame(output, terrain, camera.camera, draw);
                setProxyMode(batch, 1);
                ProxyFrame pigment = specimenFrame(output, terrain, camera.camera, draw);
                setProxyMode(batch, 3);
                ProxyFrame incoming = specimenFrame(output, terrain, camera.camera, draw);
                setProxyMode(batch, 2);
                ProxyFrame original = specimenFrame(output, terrain, camera.camera, draw);
                for (ProxyFrame probe : new ProxyFrame[] { pigment, incoming, original }) {
                    org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), probe.depth(), "Field probes preserve depth");
                }
                float role = blades ? .25f : .75f;
                int covered = 0;
                float low = 1, high = 0;
                for (int pixel = 0; pixel < full.depth().length; pixel++) {
                    int at = pixel * 4;
                    if (pigment.rgba()[at + 3] != role) { continue; }
                    covered++;
                    assertTrue(full.depth()[pixel] < 1, "Actual " + kind + " owns the tested depth");
                    float root = original.rgba()[at + 2];
                    low = Math.min(low, root); high = Math.max(high, root);
                    assertEquals(root, original.rgba()[at + 1], 0, "Existing root reflection visibility");
                    if (blades) {
                        for (int c = 0; c < 3; c++) {
                            assertEquals(incoming.rgba()[at + c], pigment.rgba()[at + c], 0,
                                  "Dry actual blade retains its incoming linear pigment along its full height");
                        }
                    } else { assertEquals(1, original.rgba()[at], 0, "Clump diffuse visibility has no blade-root shelter"); }
                }
                assertTrue(covered > 0, "Requested actual provider must cover pixels: " + kind);
                if (blades) { assertTrue(high - low > .25f, "Real blades exercise distinct root and tip coordinates"); }
                float[] diffuseVisibility = { .8f, .9f, 1 };
                for (int control = 1; control <= 3; control++) {
                    float root = (control - 1) * .5f;
                    setProxyMode(batch, control * 10 + 1);
                    ProxyFrame controlledPigment = specimenFrame(output, terrain, camera.camera, draw);
                    org.junit.jupiter.api.Assertions.assertArrayEquals(pigment.rgba(), controlledPigment.rgba(),
                          "Root shelter cannot recolor any actual vegetation material: " + kind);
                    setProxyMode(batch, control * 10 + 2);
                    ProxyFrame visibility = specimenFrame(output, terrain, camera.camera, draw);
                    org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), visibility.depth(), "Visibility preserves depth");
                    org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), controlledPigment.depth(), "Pigment preserves depth");
                    for (int pixel = 0; pixel < full.depth().length; pixel++) {
                        int at = pixel * 4;
                        if (pigment.rgba()[at + 3] != role) { continue; }
                        assertEquals(role, visibility.rgba()[at + 3], 0, "The same actual provider owns every control");
                        assertEquals(blades ? diffuseVisibility[control - 1] : 1,
                              visibility.rgba()[at], .0005f, "Diffuse shelter belongs only to real blades");
                        assertEquals(root, visibility.rgba()[at + 1], 0, "Separate reflected-environment visibility is retained");
                        assertEquals(root, visibility.rgba()[at + 2], 0, "The requested root input reached the actual material");
                    }
                }
                setProxyMode(batch, 0);
                ProxyFrame restored = specimenFrame(output, terrain, camera.camera, draw);
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.rgba(), restored.rgba(), "Restore exact production color");
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), restored.depth(), "Restore exact production depth");
                System.out.printf("Blade-root %s covered=%d rootRange=%s..%s pigment/visibility/depth PASS%n",
                      kind, covered, low, high);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            try { terrain.dispose(); }
            finally {
                BoardGeometry.tune(originalGeometry); TerrainLod.tune(originalLod);
                applySources(manager, Map.of(), "restore-blade-root");
            }
        }
    }

    private static void mathematicalBoundary(boolean baseline) {
        var table = new GpuEnvironmentLighting();
        FrameBuffer target = GpuHeatGlow.buffer(1, 1, false);
        Mesh quad = GpuAtmosphere.screenQuad();
        ShaderProgram shader = GpuGlsl.compile("Shading-normal horizon probe", "#define environmentLightingFlag\n",
              GpuShaderSource.read("atmosphere.vert"), "#version 330 core\n"
                    + GpuShaderSource.read("light-model.glsl") + GpuShaderSource.read("environment-lighting.glsl") + (baseline ? PROBE.replace(
                          "u_mode == 0 ? u_normal : shadingNormal(u_normal,u_face,u_view)", "u_normal") : PROBE));
        try {
            table.texture().bind(0);
            shader.bind(); shader.setUniformi("u_environmentLut", 0);
            shader.setUniformf("u_environmentSky", 1, 1, 1);
            shader.setUniformf("u_environmentHorizon", 1, 1, 1);
            Vector3 n = new Vector3(0, 0, 1), g = new Vector3(.6f, 0, .8f);
            shader.setUniformf("u_directionalRadiusSine", GpuEnvironmentLighting.SOURCE_RADIUS_SINE);
            float[] oldAbove = draw(target, quad, shader, 0, n, g, eye(.000001f), n, .85f);
            float[] oldAt = draw(target, quad, shader, 0, n, g, eye(0), n, .85f);
            assertTrue(oldAbove[0] > .85, "The defect control must reproduce the actual bright diffuse limit");
            for (int c = 0; c < 3; c++) {
                assertTrue(oldAbove[c] > 0);
                assertEquals(0, oldAt[c], 0, "The unrepaired shading frame reproduces the old cutoff");
            }
            if (baseline) { return; } // Old shaders need no new helper to reproduce their cutoff.
            for (float radius : new float[] { 0, GpuEnvironmentLighting.SOURCE_RADIUS_SINE }) {
                shader.bind(); shader.setUniformf("u_directionalRadiusSine", radius);
                for (float roughness : new float[] { .045f, .2f, .85f, 1 }) {
                    float[] below = draw(target, quad, shader, 1, n, g, eye(-.0001f), n, roughness);
                    float[] at = draw(target, quad, shader, 1, n, g, eye(0), n, roughness);
                    float[] above = draw(target, quad, shader, 1, n, g, eye(.0001f), n, roughness);
                    for (int c = 0; c < 3; c++) {
                        assertTrue(at[c] > 0, "Each lobe remains active at a shading horizon: " + c);
                        assertEquals(at[c], below[c], Math.max(.00001, at[c] * .004), "Lower limit channel " + c);
                        assertEquals(at[c], above[c], Math.max(.00001, at[c] * .004), "Upper limit channel " + c);
                    }
                    System.out.printf("Grazing r=%s radius=%s correctedDiffuse=%s direct=%s environment=%s%n",
                          roughness, radius, at[0], at[1], at[2]);
                }
            }
            // The independent angular construction is pi/2 minus the angle from the geometric face to V,
            // halved. It does not repeat the shader's stable half-angle arithmetic.
            double theta = .5 * (Math.atan2(-.8, .6) + Math.PI / 2);
            Vector3 x = new Vector3(-.8f, 0, .6f);
            Vector3 expected = x.scl((float) Math.sin(theta)).mulAdd(g, (float) Math.cos(theta));
            float[] repaired = draw(target, quad, shader, 2, n, g, eye(0), n, .85f);
            assertEquals(expected.x, repaired[0], .0005);
            assertEquals(expected.y, repaired[1], .0005);
            assertEquals(expected.z, repaired[2], .0005);
            assertEquals(0, repaired[3], .000002, "Repaired mirror ray reaches the real face's horizon");
            float[] unchanged = draw(target, quad, shader, 2, n, n, eye(.6f), n, .85f);
            assertEquals(0, unchanged[0], 0); assertEquals(0, unchanged[1], 0); assertEquals(1, unchanged[2], 0);
            // The no-op/repaired boundary must itself be continuous, including both tangent orientations.
            for (float ix : new float[] { -.8f, .8f }) {
                var view = new Vector3(ix, 0, .6f);
                double limit = .5 * (Math.atan2(ix, .6) + Math.PI / 2);
                float[] boundary = null;
                for (double delta : new double[] { 0, -.00001, .00001 }) {
                    var input = new Vector3((float) Math.sin(limit + delta), 0, (float) Math.cos(limit + delta));
                    float[] pixel = draw(target, quad, shader, 2, input, Vector3.Z, view, Vector3.Z, .85f);
                    double bounded = Math.min(limit + delta, limit);
                    assertEquals(Math.sin(bounded), pixel[0], .0005, "Independent angular repair boundary");
                    assertEquals(Math.cos(bounded), pixel[2], .0005);
                    if (boundary == null) { boundary = pixel; }
                    else {
                        assertEquals(boundary[0], pixel[0], .001, "Fast-path tangent continuity");
                        assertEquals(boundary[2], pixel[2], .001, "Fast-path normal continuity");
                    }
                }
            }
            // Exact/near-opposing finite input is a numerical control, independent of real-instance routing.
            // Its tangent azimuth is undefined at exact opposition; require a valid unit frame, not an invented axis.
            var opposingView = new Vector3(.4284668f, .25732422f, .86572266f).nor();
            for (var face : new Vector3[] { new Vector3(.61621094f, .78466797f, -.061462402f).nor(),
                  new Vector3(.267f, .534f, .802f).nor(), new Vector3(-.843f, .213f, .493f).nor() }) {
                assertTrue(face.dot(opposingView) > 0, "Opposing-input control has visible geometry");
                var tangent = face.cpy().crs(opposingView).nor();
                for (float offset : new float[] { 0, -.00001f, .00001f }) {
                    var input = face.cpy().scl(-1).mulAdd(tangent, offset).nor();
                    float[] value = draw(target, quad, shader, 2, input, face, opposingView, face, .85f);
                    var fixed = new Vector3(value[0], value[1], value[2]);
                    assertEquals(1, fixed.len(), .002, "Opposing-input repair remains unit length after16F storage");
                    assertTrue(fixed.dot(opposingView) >= -.001, "Opposing-input repair faces the visible eye");
                    assertTrue(value[3] >= -.003, "Opposing-input mirror ray stays on the geometric side");
                    System.out.printf("Opposing offset=%s face=%s repaired=%s support=%s%n", offset, face, fixed, value[3]);
                }
            }
            Vector3 backlight = new Vector3(0, 0, -1);
            for (float z : new float[] { -.0001f, 0, .0001f }) {
                float[] through = draw(target, quad, shader, 3, n, g, eye(z), backlight, .85f);
                assertEquals(0, through[0], 0, "Opposite-side light is not opaque reflection");
                assertTrue(through[1] > .5, "Thin tissue retains its separate transmission lobe");
                assertEquals(0, through[2], 0);
                float[] opposite = draw(target, quad, shader, 1, n, g, eye(z), eye(z).scl(-1), .85f);
                assertEquals(0, opposite[1], 0, "Opposite rays are rejected before half-vector normalization");
            }
        } finally { shader.dispose(); quad.dispose(); target.dispose(); table.dispose(); }
    }

    private static Vector3 eye(float z) { return new Vector3(1, 0, z).nor(); }

    private static float[] draw(FrameBuffer target, Mesh quad, ShaderProgram shader, int mode,
          Vector3 normal, Vector3 face, Vector3 view, Vector3 light, float roughness) {
        target.begin();
        try {
            Gdx.gl.glDisable(GL20.GL_BLEND); Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            shader.bind(); shader.setUniformi("u_mode", mode);
            shader.setUniformf("u_normal", normal); shader.setUniformf("u_face", face);
            shader.setUniformf("u_view", view); shader.setUniformf("u_light", light);
            shader.setUniformf("u_roughness", roughness);
            quad.render(shader, GL20.GL_TRIANGLES);
            var pixels = BufferUtils.newFloatBuffer(4);
            Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
            float[] result = new float[4]; pixels.get(result);
            finite(result);
            return result;
        } finally { target.end(); }
    }

    /** Keep transactional reload failures visible, including the assembled variant when compilation failed. */
    private static void applySources(GpuShaderManager manager, Map<String, String> sources, String label) {
        var result = manager.apply(sources);
        if (!result.success()) {
            var directory = Gdx.files.absolute(new java.io.File(System.getProperty("megamek.gpu.screenshots",
                  "build/gpu-board-review"), "grazing-source-failures/" + label).getAbsolutePath());
            assertTrue(!directory.exists(), "Preserve previous shader failure evidence: " + label);
            directory.mkdirs();
            String details = label + ": " + result.message();
            if (result.failure() != null) {
                details += "\nVariant: " + result.failure().name();
                directory.child("source.vert").writeString(result.failure().vertex(), false, "UTF-8");
                directory.child("source.frag").writeString(result.failure().fragment(), false, "UTF-8");
            }
            directory.child("failure.txt").writeString(details, false, "UTF-8");
            System.err.println(details);
        }
        assertTrue(result.success(), label + ": " + result.message());
    }

    private static void mappedModel(GpuShaderManager manager) {
        // Models light through shadeSurface (surface-shading.glsl): keep the cloud-shaded direct light alone.
        String material = GpuShaderSource.readDisk("surface-shading.glsl");
        String result = "return albedo + s.emission;";
        assertEquals(1, material.split(java.util.regex.Pattern.quote(result), -1).length - 1);
        applySources(manager, Map.of("surface-shading.glsl", material.replace(result, "return s.albedo * direct + sheen;")),
              "controlled-model");
        Texture normal = normalTexture();
        var builder = new ModelBuilder();
        builder.begin();
        var plate = builder.part("normal-mapped-face", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
                    | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates,
              new Material(ColorAttribute.createDiffuse(Color.WHITE), TextureAttribute.createNormal(normal),
                    new GpuModelMaterial(.85f, 0, null)));
        plate.rect(vertex(-40, -40, 0, 0), vertex(40, -40, 1, 0),
              vertex(40, 40, 1, 1), vertex(-40, 40, 0, 1));
        var model = builder.end();
        var instance = new ModelInstance(model);
        instance.transform.setToRotation(Vector3.Y, (float) Math.toDegrees(Math.atan2(.6, .8)));
        var batch = new ModelBatch(GpuUnitShader.provider());
        var table = new GpuEnvironmentLighting();
        table.set(Color.BLACK);
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1), table);
        var sun = new DirectionalLight().set(1, 1, 1, 0, 0, -1);
        environment.add(sun);
        try (var output = new GpuSceneTestOutput(128, 128)) {
            for (boolean mirror : new boolean[] { false, true }) {
                if (mirror) { mirrorUv(model); }
                // Decode the actual authored texel and known tangent frame, rather than choosing view rays from
                // the shader's output. glTF normals use 2*texel-1, unlike the terrain's 128-centered data maps.
                float nx = (51 / 255f * 2 - 1) * (mirror ? -1 : 1), ny = 128 / 255f * 2 - 1;
                float nz = 230 / 255f * 2 - 1;
                var mapped = new Vector3(.8f * nx + .6f * nz, ny, -.6f * nx + .8f * nz).nor();
                var tangentView = new Vector3(mapped.z, 0, -mapped.x).nor();
                if (tangentView.dot(new Vector3(.6f, 0, .8f)) < 0) { tangentView.scl(-1); }
                float[] center = null;
                for (float incidence : new float[] { 0, -.0001f, .0001f }) {
                    var view = tangentView.cpy().mulAdd(mapped, incidence).nor();
                    assertTrue(view.dot(new Vector3(.6f, 0, .8f)) > .1, "Real face remains visible");
                    sun.set(1, 1, 1, -mapped.x, -mapped.y, -mapped.z);
                    float[] pixel = modelPixel(output, batch, instance, environment, view);
                    assertTrue(pixel[0] > .2, "The actual mapped provider retains direct light at the tangent plane");
                    if (center == null) { center = pixel; }
                    else { assertEquals(center[0], pixel[0], .003, "Actual model provider grazing continuity"); }
                }
                if (!mirror) {
                    sun.set(1, 1, 1, 2, 0, -1); // L=(-2,0,1): positive mapped NoL but negative geometric NoL.
                    float[] dark = modelPixel(output, batch, instance, environment, tangentView);
                    assertEquals(0, dark[0], 0, "No opaque direct-light leak through the actual reverse side");
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            batch.dispose(); model.dispose(); normal.dispose(); table.dispose();
            applySources(manager, Map.of(), "restore-model");
        }
    }

    private static float[] modelPixel(GpuSceneTestOutput output, ModelBatch batch, ModelInstance instance,
          Environment environment, Vector3 view) {
        return modelPixel(output, batch, instance, environment, view, null);
    }

    private static float[] modelPixel(GpuSceneTestOutput output, ModelBatch batch, ModelInstance instance,
          Environment environment, Vector3 view, GpuTerrain preview) {
        return modelPixel(output, batch, instance, environment, view, preview, true);
    }

    private static float[] modelPixel(GpuSceneTestOutput output, ModelBatch batch, ModelInstance instance,
          Environment environment, Vector3 view, GpuTerrain preview, boolean opaqueAlpha) {
        var camera = new OrthographicCamera(80, 80);
        camera.position.set(view).scl(200); camera.direction.set(view).scl(-1); camera.up.set(0, 1, 0);
        camera.near = 1; camera.far = 500; camera.update();
        output.begin(0, 0, 0, 0);
        // Match existing TreeLod provider fixtures: a new terrain pass invalidates cached camera/light globals.
        if (preview != null) { GpuTreeLodSmokeTest.newShadingPass(preview); }
        batch.begin(camera); batch.render(instance, environment); batch.end();
        float[] pixels = output.linearPixels();
        finite(pixels);
        int index = (64 * 128 + 64) * 4;
        if (opaqueAlpha) { assertEquals(1, pixels[index + 3], 0, "The known geometric face covers the sampled pixel"); }
        return java.util.Arrays.copyOfRange(pixels, index, index + 4);
    }

    private static void terrainSurfaces(GpuShaderManager manager) throws Exception {
        var preview = new GpuTerrain();
        var batchField = GpuTerrain.class.getDeclaredField("batch");
        batchField.setAccessible(true);
        ModelBatch batch = (ModelBatch) batchField.get(preview); // Borrowed; preview owns disposal.
        var table = new GpuEnvironmentLighting();
        table.set(Color.BLACK);
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0, 0, 0, 1), table);
        var sun = new DirectionalLight().set(1, 1, 1, 0, 0, -1);
        environment.add(sun);
        try (var output = new GpuSceneTestOutput(128, 128)) {
            // This isolated provider has no board pass; initialize its owned inactive biome sentinel.
            var biomesField = GpuTerrain.class.getDeclaredField("biomes");
            biomesField.setAccessible(true);
            ((GpuBiomeSurface) biomesField.get(preview)).update(null);
            String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
            // A controlled finished material isolates geometric sidedness in the actual cliff lighting path.
            // All map/provider setup remains real; this does not pretend to test a particular asset's normal value.
            String normalBoundary = "normal = shadingNormal(normal, geometric, view);";
            assertEquals(1, sculpt.split(java.util.regex.Pattern.quote(normalBoundary), -1).length - 1);
            sculpt = sculpt.replace(normalBoundary, "normal = vec3(0,0,1); film = 0.0; finish = Finish(.85, 0.0, 0.0, vec3(0.0));"
                  + " cavity = 1.0; volcanic = 0.0; " + normalBoundary);
            // shadeSurface (surface-shading.glsl) returns the direct light alone; this material emits nothing.
            String shading = GpuShaderSource.readDisk("surface-shading.glsl");
            String result = "return albedo + s.emission;";
            assertEquals(1, shading.split(java.util.regex.Pattern.quote(result), -1).length - 1);
            String controlledCliff = sculpt, directOnly = shading.replace(result, "return direct;");
            applySources(manager, Map.of("terrain-sculpt.frag", controlledCliff, "surface-shading.glsl", directOnly),
                  "controlled-cliff");
            var materialMethod = GpuTerrain.class.getDeclaredMethod("sculptMaterial", BoardScene.Surface.class);
            materialMethod.setAccessible(true);
            Material cliff = (Material) materialMethod.invoke(preview, BoardScene.Surface.ROCK);
            var rock = surfacePlate(cliff, true);
            try {
                var instance = tilted(rock);
                float[] center = modelPixel(output, batch, instance, environment, eye(0), preview);
                assertTrue(center[0] > .8, "Actual cliff provider remains lit at its mapped-normal horizon");
                for (float z : new float[] { -.0001f, .0001f }) {
                    float[] side = modelPixel(output, batch, instance, environment, eye(z), preview);
                    assertEquals(center[0], side[0], .003, "Actual cliff provider grazing continuity");
                }
                sun.set(1, 1, 1, 2, 0, -1);
                assertEquals(0, modelPixel(output, batch, instance, environment, eye(0), preview)[0], 0,
                      "Cliff reflection cannot use incident light behind its geometric face");

                // Same positions/material/camera, but the unmapped base frame describes a smooth curved surface.
                // After the instance's tilt, this authored normal is world +Z; the actual triangle stays (.6,0,.8).
                var mesh = rock.meshes.first();
                int stride = mesh.getVertexSize() / Float.BYTES;
                int normalOffset = mesh.getVertexAttribute(VertexAttributes.Usage.Normal).offset / Float.BYTES;
                int colorOffset = mesh.getVertexAttribute(VertexAttributes.Usage.ColorUnpacked).offset / Float.BYTES;
                float[] originalVertices = new float[mesh.getNumVertices() * stride];
                mesh.getVertices(originalVertices);
                float[] curvedVertices = originalVertices.clone();
                Vector3 curvedView = new Vector3(1, 0, 1).nor();
                for (int i = 0; i < curvedVertices.length; i += stride) {
                    curvedVertices[i + normalOffset] = -.6f;
                    curvedVertices[i + normalOffset + 1] = 0;
                    curvedVertices[i + normalOffset + 2] = .8f;
                }
                mesh.setVertices(curvedVertices);
                float[] curved = modelPixel(output, batch, instance, environment, curvedView, preview);
                assertTrue(curved[0] > .3, "A sun-facing curved cliff does not inherit its coarse triangle's cutoff");
                org.junit.jupiter.api.Assertions.assertArrayEquals(curved,
                      modelPixel(output, batch, instance, environment, curvedView, preview), "Fixed curved frame repeats");
                for (int i = colorOffset + 2; i < curvedVertices.length; i += stride) { curvedVertices[i] = .45f; }
                mesh.setVertices(curvedVertices);
                assertEquals(0, modelPixel(output, batch, instance, environment, curvedView, preview)[0], 0,
                      "A flat cast panel still rejects light behind its actual triangle");
                for (int i = colorOffset + 2; i < curvedVertices.length; i += stride) { curvedVertices[i] = .5f; }
                mesh.setVertices(curvedVertices);
                // A mapped normal facing the sun cannot illuminate the back of the unmapped curved surface.
                applySources(manager, Map.of("terrain-sculpt.frag", controlledCliff.replace(
                      "normal = vec3(0,0,1);", "normal = vec3(1,0,0);"), "surface-shading.glsl", directOnly),
                      "controlled-cliff-backlight");
                sun.set(1, 1, 1, -1, 0, .25f);
                assertEquals(0, modelPixel(output, batch, instance, environment, curvedView, preview)[0], 0,
                      "Mapped detail cannot move the curved cliff's incident hemisphere");
                mesh.setVertices(originalVertices);
                assertTrue(modelPixel(output, batch, instance, environment, curvedView, preview)[0] > .5,
                      "The same light and mapped response illuminate a base surface that actually faces it");
            } finally { rock.dispose(); }

            bushTransmission(manager, preview, batch, environment, sun, output);

            // The same direct-only response, after the production cloud insertion in shadeSurface.
            applySources(manager, Map.of("surface-shading.glsl", shading.replace(result, "return s.albedo * direct;")),
                  "controlled-foliage");
            // Reflectance .6 with transmission .5: R + T stays below one, so the thin-tissue bound keeps both sides lit.
            Texture normal = normalTexture(), pigment = texture(0x999999ff), tissue = texture(0xffda80ff);
            var leaf = surfacePlate(new Material("leaf", ColorAttribute.createDiffuse(Color.WHITE),
                  TextureAttribute.createDiffuse(pigment), TextureAttribute.createNormal(normal),
                  TextureAttribute.createAmbient(tissue), IntAttribute.createCullFace(GL20.GL_NONE)), false);
            var foliageMethod = GpuTerrain.class.getDeclaredMethod("foliage", com.badlogic.gdx.graphics.g3d.Model.class);
            foliageMethod.setAccessible(true);
            foliageMethod.invoke(null, leaf);
            try {
                var instance = tilted(leaf);
                for (boolean mirror : new boolean[] { false, true }) {
                    if (mirror) { mirrorUv(leaf); }
                    // Terrain foliage data uses the authored 128-centered normal convention.
                    float nx = ((51 - 128) / 127f) * (mirror ? -1 : 1), nz = (230 - 128) / 127f;
                    var original = new Vector3(.8f * nx + .6f * nz, 0, -.6f * nx + .8f * nz).nor();
                    var tangent = new Vector3(original.z, 0, -original.x).nor();
                    if (tangent.dot(new Vector3(.6f, 0, .8f)) < 0) { tangent.scl(-1); }
                    for (float side : new float[] { 1, -1 }) {
                        Vector3 mapped = original.cpy().scl(side), v = tangent.cpy().scl(side);
                        for (float incidentSide : new float[] { 1, -1 }) {
                            Vector3 light = mapped.cpy().scl(incidentSide);
                            sun.set(1, 1, 1, -light.x, -light.y, -light.z);
                            float[] center = modelPixel(output, batch, instance, environment, v, preview);
                            assertTrue(center[0] > .05,
                                  "Actual thin leaf retains reflection/transmission, side=" + side
                                        + " light=" + incidentSide + " mirrored=" + mirror);
                            for (float incidence : new float[] { -.0001f, .0001f }) {
                                Vector3 neighbor = v.cpy().mulAdd(mapped, incidence).nor();
                                float[] pixel = modelPixel(output, batch, instance, environment, neighbor, preview);
                                assertEquals(center[0], pixel[0], .003, "Thin-leaf mapped horizon continuity");
                            }
                        }
                    }
                }
            } finally { leaf.dispose(); normal.dispose(); pigment.dispose(); tissue.dispose(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            preview.dispose(); table.dispose();
            applySources(manager, Map.of(), "restore-terrain");
        }
    }

    /** Real sculpt provider, packed tissue vertices, two real sides, and the production hardware-depth caster. */
    private static void bushTransmission(GpuShaderManager manager, GpuTerrain preview, ModelBatch batch,
          Environment environment, DirectionalLight sun, GpuSceneTestOutput output) throws Exception {
        // shadeSurface (surface-shading.glsl) returns the direct light alone; these materials emit nothing.
        String source = GpuShaderSource.readDisk("surface-shading.glsl");
        String result = "return albedo + s.emission;";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(result), -1).length - 1);
        applySources(manager, Map.of("surface-shading.glsl", source.replace(result, "return direct;")),
              "controlled-bush-tissue");
        var materialMethod = GpuTerrain.class.getDeclaredMethod("sculptMaterial", BoardScene.Surface.class);
        materialMethod.setAccessible(true);
        var material = (Material) materialMethod.invoke(preview, BoardScene.Surface.GRASS);
        // A leaf-dark authored colour keeps R + T below one, so the bound leaves its .35 transmission share whole.
        var leaf = bushPlate(material, BoardRelief.Kind.LEAF, .3f);
        var wood = bushPlate(material, BoardRelief.Kind.PLANT, .3f);
        var white = bushPlate(material, BoardRelief.Kind.LEAF, 1);
        var leafInstance = new ModelInstance(leaf);
        var woodInstance = new ModelInstance(wood);
        var whiteInstance = new ModelInstance(white);
        var blocker = new ModelBuilder().createBox(100, 100, 4,
              new Material(ColorAttribute.createDiffuse(Color.WHITE)),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        var shadow = new GpuShadowLight(512, 512, 160, 160, 1, 500);
        var depthField = GpuTerrain.class.getDeclaredField("depthBatch");
        depthField.setAccessible(true);
        var depth = (ModelBatch) depthField.get(preview); // Borrowed from the real terrain shadow provider.
        try {
            for (float side : new float[] { 1, -1 }) {
                var view = new Vector3(0, 0, side);
                var light = new Vector3(.6f, 0, .8f * side);
                sun.set(1, 1, 1, -light.x, -light.y, -light.z);
                float opaque = modelPixel(output, batch, woodInstance, environment, view, preview)[0];
                float reflected = modelPixel(output, batch, leafInstance, environment, view, preview)[0];
                assertTrue(opaque > .4, "Front-lit wood is the independent opaque diffuse reference");
                assertEquals(1, reflected / opaque, .003, "A front-lit leaf reflects like the opaque reference");
                light.scl(-1);
                sun.set(1, 1, 1, -light.x, -light.y, -light.z);
                assertEquals(0, modelPixel(output, batch, woodInstance, environment, view, preview)[0], 0,
                      "Woody stems remain opaque under backlighting");
                float transmitted = modelPixel(output, batch, leafInstance, environment, view, preview)[0];
                assertEquals(.35, transmitted / opaque, .003, "A backlit leaf transmits its share from the other side");
                // The rendered term is T / R; a white leaf (R .92 to 1 with its grain) keeps at most (1 - R) / R.
                assertTrue(modelPixel(output, batch, whiteInstance, environment, view, preview)[0] / opaque < .09,
                      "R + T <= 1: a white leaf has no light left to transmit");

                // First include the leaf itself as caster, then add an independent upstream opaque box.
                var camera = shadow.getCamera();
                camera.position.set(light).scl(200); camera.direction.set(light).scl(-1);
                // A light from above takes the slab (GpuShadowLight.SlabCamera): heights down through the plates.
                camera.near = GpuShadowLight.slab(camera.direction) ? 100 : 1;
                camera.far = GpuShadowLight.slab(camera.direction) ? -100 : 500;
                camera.up.set(0, 1, 0); camera.update();
                var occluder = new ModelInstance(blocker);
                occluder.transform.setToTranslation(light.cpy().scl(40));
                environment.shadowMap = shadow;
                for (boolean blocked : new boolean[] { false, true }) {
                    Gdx.gl.glDepthMask(true);
                    shadow.begin();
                    try {
                        depth.begin(camera); depth.render(leafInstance);
                        if (blocked) { depth.render(occluder); }
                        depth.end();
                    } finally { shadow.end(); }
                    float value = modelPixel(output, batch, leafInstance, environment, view, preview)[0];
                    if (blocked) { assertEquals(0, value, .0001, "An independent opaque caster blocks leaf transmission"); }
                    else { assertEquals(transmitted, value, .003, "The reverse leaf face must not shadow its own transmission"); }
                }
                environment.shadowMap = null;
            }
        } finally {
            environment.shadowMap = null;
            shadow.dispose(); blocker.dispose(); leaf.dispose(); wood.dispose(); white.dispose();
            applySources(manager, Map.of(), "restore-bush-tissue");
        }
    }

    /** Both sides of a tissue plate in the authored linear RGB {@code colour} (UV and alpha carry it). */
    private static com.badlogic.gdx.graphics.g3d.Model bushPlate(Material material, BoardRelief.Kind kind,
          float colour) {
        var builder = new ModelBuilder();
        builder.begin();
        var mesh = builder.part("authored-bush-tissue", GL20.GL_TRIANGLES,
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
                    | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates, material);
        var corners = new Vector3[] { new Vector3(-40, -40, 0), new Vector3(40, -40, 0),
              new Vector3(40, 40, 0), new Vector3(-40, 40, 0) };
        for (int side : new int[] { 1, -1 }) {
            var shade = new BoardRelief.Shade(new Vector3(0, 0, side), kind, 1, 0, colour, colour, colour);
            var vertices = new MeshPartBuilder.VertexInfo[4];
            for (int index = 0; index < 4; index++) {
                vertices[index] = GpuTerrain.sculptVertex(corners[index], shade, Float.NaN, null);
            }
            if (side > 0) { mesh.rect(vertices[0], vertices[1], vertices[2], vertices[3]); }
            else { mesh.rect(vertices[3], vertices[2], vertices[1], vertices[0]); }
        }
        return builder.end();
    }

    private static com.badlogic.gdx.graphics.g3d.Model surfacePlate(Material material, boolean cliff) {
        var builder = new ModelBuilder();
        builder.begin();
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.TextureCoordinates | (cliff ? VertexAttributes.Usage.ColorUnpacked : 0);
        var plate = builder.part("surface", GL20.GL_TRIANGLES, attributes, material);
        if (cliff) { plate.setColor(1, .5f, .5f, .5f); }
        plate.rect(vertex(-40, -40, 0, 0), vertex(40, -40, 1, 0),
              vertex(40, 40, 1, 1), vertex(-40, 40, 0, 1));
        return builder.end();
    }

    private static ModelInstance tilted(com.badlogic.gdx.graphics.g3d.Model model) {
        var instance = new ModelInstance(model);
        instance.transform.setToRotation(Vector3.Y, (float) Math.toDegrees(Math.atan2(.6, .8)));
        return instance;
    }

    private static Texture normalTexture() {
        return texture(0x3380e6ff);
    }

    private static Texture texture(int rgba) {
        var pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        try { pixels.setColor(rgba); pixels.fill(); return new Texture(pixels); }
        finally { pixels.dispose(); }
    }

    private static MeshPartBuilder.VertexInfo vertex(float x, float y, float u, float v) {
        return new MeshPartBuilder.VertexInfo().setPos(x, y, 0).setNor(0, 0, 1).setUV(u, v);
    }

    private static void mirrorUv(com.badlogic.gdx.graphics.g3d.Model model) {
        for (var mesh : model.meshes) {
            int stride = mesh.getVertexSize() / Float.BYTES;
            int offset = mesh.getVertexAttribute(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride]; mesh.getVertices(vertices);
            for (int i = offset; i < vertices.length; i += stride) { vertices[i] = 1 - vertices[i]; }
            mesh.setVertices(vertices);
        }
    }

    private static void finite(float[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) { throw new AssertionError("Nonfinite native channel " + i + ": " + values[i]); }
        }
    }

    /** Actual generated tufts and shipped instanced foliage; no replacement of their normals or pigment. */
    private static void proxyAngles(GpuShaderManager manager, boolean baseline) throws Exception {
        var destination = java.nio.file.Path.of(System.getProperty("megamek.gpu.screenshots"), "grazing-proxies");
        assertTrue(java.nio.file.Files.notExists(destination), "Preserve previous proxy evidence");
        java.nio.file.Files.createDirectories(destination);
        String foliageDisk = GpuShaderSource.readDisk("terrain-foliage.frag");
        boolean virtualFrames = proxyFrames(foliageDisk);
        String foliage = proxySource(foliageDisk, false, virtualFrames);
        String vegetation = proxySource(GpuShaderSource.readDisk("terrain-vegetation.frag"), true, virtualFrames);
        applySources(manager, Map.of("terrain-foliage.frag", foliage, "terrain-vegetation.frag", vegetation),
              "proxy-diagnostics");
        var preview = new GpuTerrain();
        var assets = new GpuAssets();
        var field = GpuTerrain.class.getDeclaredField("batch"); field.setAccessible(true);
        ModelBatch batch = (ModelBatch) field.get(preview);
        var prepare = GpuTerrain.class.getDeclaredMethod("foliage", com.badlogic.gdx.graphics.g3d.Model.class);
        prepare.setAccessible(true);
        var instances = new GpuTreeInstances((name, level) -> {
            try { return (com.badlogic.gdx.graphics.g3d.Model) prepare.invoke(null, assets.lodModel(name, level)); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
        // Borrow the production grass template and upload path, including its seeded bend/normal construction.
        // Fixed roots avoid board arrival-time growth; this tests actual blade shading, not planting/density.
        Class<?> bladeClass = Class.forName(GpuGroundCover.class.getName() + "$Batch");
        var constructor = bladeClass.getDeclaredConstructor(float[][].class); constructor.setAccessible(true);
        var templates = GpuGroundCover.class.getDeclaredField("GRASS"); templates.setAccessible(true);
        Object blades = constructor.newInstance((Object) ((float[][][]) templates.get(null))[1]);
        var add = bladeClass.getDeclaredMethod("add", com.badlogic.gdx.utils.FloatArray.class); add.setAccessible(true);
        var roots = new com.badlogic.gdx.utils.FloatArray();
        int rank = 0;
        for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) roots.addAll(x * 1.5f, y * 1.5f, 0, rank++);
        add.invoke(blades, roots);
        var upload = bladeClass.getDeclaredMethod("upload", com.badlogic.gdx.utils.IntArray.class); upload.setAccessible(true);
        var targets = new com.badlogic.gdx.utils.IntArray(); targets.add(4096);
        ModelInstance tuft = (ModelInstance) upload.invoke(blades, targets);
        assertTrue(tuft != null, "The actual production blade batch must contain geometry");
        var rows = new java.util.ArrayList<Object>();
        int repairedSupport = 0, virtualCrossings = 0;
        try (var output = new GpuSceneTestOutput(128, 128)) {
            for (float hour : new float[] { 13, 6.5f }) {
                preview.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(hour, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
                var environment = new Environment();
                for (var attribute : preview.environment()) { environment.set(attribute); }
                assertTrue(environment.shadowMap == null, "Isolated specimens must not sample an unrendered shadow map");
                String[] kinds = virtualFrames ? new String[] { "tuft", "pine-lod2", "pine-lod3",
                      "tree-snow-lod2", "bark", "snow" } : new String[] { "tuft", "pine-lod2", "pine-lod3" };
                for (String kind : kinds) {
                    boolean extra = kind.equals("tree-snow-lod2") || kind.equals("bark") || kind.equals("snow");
                    if (extra && hour != 13) { continue; }
                    for (boolean mirrored : new boolean[] { false, true }) {
                        // Ground-cover roots are world-space; the production grass vertex path ignores instance transforms.
                        if (kind.equals("tuft") && mirrored) { continue; }
                        var placement = new com.badlogic.gdx.math.Matrix4();
                        // Production tree instances support one horizontal and a separate vertical scale, plus mirror.
                        if (mirrored) { placement.setToScaling(1.1f, -1.1f, 1.35f); }
                        boolean grass = kind.equals("tuft");
                        boolean solidRole = kind.equals("bark") || kind.equals("snow");
                        String species = kind.equals("tree-snow-lod2") ? "vegetation/tree-snow"
                              : kind.equals("snow") ? "vegetation/tree-dead-snow" : "vegetation/pine";
                        int level = kind.endsWith("3") ? 3 : 2;
                        boolean objectParts = mirrored || solidRole;
                        var observedRoles = new java.util.TreeSet<String>();
                        var stand = new GpuTreeInstances.Stand();
                        var mirroredParts = new com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g3d.Renderable>();
                        if (objectParts) {
                            assertTrue(GpuTreeInstances.instanceable(placement)
                                        && (!mirrored || !GpuTreeInstances.placeable(placement)),
                                  "Use supported object-part placement, including mirrored winding");
                            var model = (com.badlogic.gdx.graphics.g3d.Model) prepare.invoke(null, assets.lodModel(species, level));
                            var instance = new ModelInstance(model); // Borrowed model/maps; assets retains ownership.
                            instance.transform.set(placement);
                            mirroredParts.addAll(GpuTerrainDepth.snapshot(java.util.List.of(instance)));
                            if (solidRole) {
                                for (int i = mirroredParts.size - 1; i >= 0; i--) {
                                    if (!kind.equals(mirroredParts.get(i).material.id)) { mirroredParts.removeIndex(i); }
                                }
                            }
                            assertTrue(mirroredParts.size > 0, "The mirrored specimen must contain actual model parts");
                            for (var part : mirroredParts) {
                                assertTrue(GpuTreeInstances.instanceable(part.worldTransform)
                                            && GpuTreeInstances.mirrored(part.worldTransform) == mirrored,
                                      "Each transformed model part uses the supported mirrored instance batch");
                            }
                        } else {
                            assertTrue(GpuTreeInstances.placeable(placement), "Positive tree placement");
                            stand.add(species, placement, 0);
                        }
                        tuft.transform.set(placement);
                        var bounds = grass ? new com.badlogic.gdx.math.collision.BoundingBox(
                              new Vector3(-4, -4, -1), new Vector3(4, 4, 7))
                              : assets.lodModel(species, 0).calculateBoundingBox(new com.badlogic.gdx.math.collision.BoundingBox());
                        bounds.mul(placement);
                        Vector3 center = bounds.getCenter(new Vector3());
                        float diameter = bounds.getDimensions(new Vector3()).len();
                        float footprint = grass || solidRole ? 64 : 26.4f;
                        for (float tilt : new float[] { 30, 60, 85 }) {
                            if (extra && tilt != 60) { continue; }
                            float dpi = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
                            var camera = new OrthographicCamera(128 / dpi, 128 / dpi);
                            camera.zoom = diameter * dpi / footprint;
                            double inclination = Math.toRadians(tilt), azimuth = Math.toRadians(31);
                            var eye = new Vector3((float) (Math.sin(inclination) * Math.cos(azimuth)),
                                  (float) (Math.sin(inclination) * Math.sin(azimuth)), (float) Math.cos(inclination));
                            camera.position.set(center).mulAdd(eye, diameter * 5);
                            camera.up.set(Vector3.Z); camera.lookAt(center);
                            camera.near = .01f; camera.far = diameter * 12; camera.update();
                            Runnable draw = () -> {
                                batch.begin(camera);
                                if (grass) { batch.render(tuft, environment); }
                                else {
                                    instances.begin(GpuTreeInstances.Pass.COLOUR);
                                    if (objectParts) { instances.add(mirroredParts); }
                                    else { instances.add(stand, level, true); }
                                    var actual = new com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g3d.Renderable>();
                                    instances.getRenderables(actual, null); // Borrowed instance-owned parts; pool is unused.
                                    for (var part : actual) { observedRoles.add(part.material.id); }
                                    batch.render(instances, environment);
                                }
                                batch.end();
                            };
                            setProxyMode(batch, 0);
                            specimenFrame(output, preview, camera, draw); // Material/program first use.
                            ProxyFrame full = specimenFrame(output, preview, camera, draw);
                            ProxyFrame repeat = specimenFrame(output, preview, camera, draw);
                            org.junit.jupiter.api.Assertions.assertArrayEquals(full.rgba(), repeat.rgba(), "Frozen proxy radiance");
                            org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), repeat.depth(), "Frozen proxy depth");
                            String id = kind + "-" + (mirrored ? "mirrored-scaled" : "identity") + "-" + hour + "-" + tilt;
                            saveProxy(output, destination.resolve(id + ".png"));
                            setProxyMode(batch, 1);
                            ProxyFrame probe = specimenFrame(output, preview, camera, draw);
                            org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), probe.depth(),
                                  "Output-only normal diagnostics preserve the actual cutout silhouette");
                            ProxyFrame roles = null;
                            if (virtualFrames) {
                                setProxyMode(batch, 10);
                                roles = specimenFrame(output, preview, camera, draw);
                                org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), roles.depth(), "Role probe preserves depth");
                            }
                            setProxyMode(batch, 0);
                            org.junit.jupiter.api.Assertions.assertArrayEquals(full.rgba(),
                                  specimenFrame(output, preview, camera, draw).rgba(), "Restore full production output");
                            int proxyPixels = 0, carrierCrossings = 0, carrierBacks = 0;
                            int covered = 0, crossing = 0, changed = 0;
                            double sum = 0, maximum = 0, minBefore = 1, minAfter = 1, minSupport = 1;
                            byte[] depthMask = new byte[128 * 128];
                            for (int p = 0; p < depthMask.length; p++) {
                                if (full.depth()[p] >= 1) { continue; }
                                covered++; depthMask[p] = 1;
                                if (roles != null) {
                                    float role = roles.rgba()[p * 4];
                                    assertTrue(role == 0 || role == 1, "Actual draw-local role is binary");
                                    if (role == 1) {
                                        proxyPixels++;
                                        if (roles.rgba()[p * 4 + 1] < -.003) { carrierCrossings++; }
                                    }
                                    if (roles.rgba()[p * 4 + 2] == 0) { carrierBacks++; }
                                    if (solidRole || grass) { assertEquals(0, role, 0, "Real geometry must retain its frame: " + id); }
                                    if (level == 3 && !grass) { assertEquals(1, role, 0, "Actual instanced impostor must use its virtual frame"); }
                                }
                                for (int c = 0; c < 3; c++) {
                                    float value = full.rgba()[p * 4 + c];
                                    assertTrue(value >= 0, "Actual proxy radiance must not become negative");
                                    sum += value; maximum = Math.max(maximum, value);
                                }
                                float original = probe.rgba()[p * 4], corrected = probe.rgba()[p * 4 + 1];
                                float support = probe.rgba()[p * 4 + 2], geometricView = probe.rgba()[p * 4 + 3];
                                minBefore = Math.min(minBefore, original); minAfter = Math.min(minAfter, corrected);
                                minSupport = Math.min(minSupport, support);
                                if (geometricView < -.001 || geometricView > 1.001
                                      || !baseline && (corrected < -.001 || support < -.003)) {
                                    proxyFailure(output, preview, batch, camera, draw, destination, id, p, full, probe);
                                }
                                assertTrue(geometricView >= -.001 && geometricView <= 1.001,
                                      "Visible geometric hemisphere: " + id + " pixel=" + p + " NgV=" + geometricView);
                                if (original < -.001) { crossing++; }
                                if (Math.abs(corrected - original) > .002) { changed++; }
                                if (baseline) { assertEquals(original, corrected, 0, "No repaired frame exists in the baseline"); }
                                else {
                                    assertTrue(corrected >= -.001, "Actual proxy frame faces the visible eye: " + id
                                          + " pixel=" + p + " original=" + original + " corrected=" + corrected);
                                    assertTrue(support >= -.003, "Actual proxy central reflection stays on the geometric side: " + id
                                          + " pixel=" + p + " original=" + original + " corrected=" + corrected
                                          + " support=" + support + " NgV=" + geometricView);
                                }
                            }
                            assertTrue(covered > 0, "Actual proxy covers pixels: " + id);
                            if (virtualFrames && !grass) {
                                String expectedRole = solidRole ? kind : level == 3 ? "impostor"
                                      : species.equals("vegetation/tree-snow") ? "canopy-snow-cutout" : "canopy-cutout";
                                assertTrue(observedRoles.contains(expectedRole), "Actual copied instance role: " + expectedRole);
                                if (!solidRole) { assertTrue(proxyPixels > 0, "Actual virtual role covers pixels: " + id); }
                            }
                            virtualCrossings += carrierCrossings;
                            repairedSupport += changed;
                            var row = new java.util.LinkedHashMap<String, Object>();
                            row.put("id", id); row.put("image", id + ".png"); row.put("sha256", fileHash(destination.resolve(id + ".png")));
                            row.put("kind", kind); row.put("hour", hour); row.put("tilt", tilt); row.put("mirroredScaled", mirrored);
                            row.put("instanceRoute", grass ? "ground-cover" : objectParts ? "object-parts" : "tree-stand");
                            row.put("materialIds", observedRoles); row.put("virtualFrame", virtualFrames);
                            row.put("proxyPixels", proxyPixels); row.put("carrierNegativeSupportPixels", carrierCrossings);
                            row.put("carrierBackPixels", carrierBacks);
                            row.put("referenceDiameterPixels", footprint); row.put("camera", camera.combined.val.clone());
                            row.put("bounds", new float[] {bounds.min.x,bounds.min.y,bounds.min.z,bounds.max.x,bounds.max.y,bounds.max.z});
                            row.put("covered", covered); row.put("originalNegativeViewPixels", crossing); row.put("changedViewPixels", changed);
                            row.put("minimumOriginalViewCosine", minBefore); row.put("minimumViewCosine", minAfter);
                            row.put("minimumMirrorSupport", minSupport); row.put("meanRadiance", sum / (covered * 3));
                            row.put("peakRadiance", maximum); row.put("depthMaskSha256", bytesHash(depthMask));
                            rows.add(row);
                        }
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertEquals(virtualFrames ? 36 : 30, rows.size());
            if (!baseline) { assertTrue(repairedSupport > 0, "Real materials must exercise frame repair"); }
            if (virtualFrames) {
                assertTrue(virtualCrossings > 0, "Actual valid virtual reflections must cross a carrier plane");
                proxyRoleBoundary(preview, batch, prepare, output, destination);
            }
            var report = new java.util.LinkedHashMap<String, Object>();
            report.put("baseline", baseline); report.put("virtualFrames", virtualFrames);
            report.put("renderer", Gdx.gl.glGetString(GL20.GL_RENDERER));
            report.put("rows", rows);
            report.put("virtualCrossings", virtualCrossings);
            report.put("scope", "Actual seeded production grass blades and shipped pine LOD2/3 through the instanced foliage path. "
                  + "Positive trees use Stand; mirrored/scaled models use supported object-part batches with corrected front winding. Grass uses its world-space roots. "
                  + "Three inclinations, noon and low sun. "
                  + "Shadowless isolated materials, clock fixed; no claim of exact cross-LOD silhouette or arbitrary view equivalence. "
                  + "Tuple output records original/repaired NsV, independent represented-frame mirror support, and NgV. "
                  + "Real geometry retains carrier support; virtual-frame runs separately record carrier support and actual role. "
                  + "Continuity across a shading horizon is proven separately by the mathematical/controlled provider probes.");
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                  .writeValue(destination.resolve("review.json").toFile(), report);
        } finally {
            ((com.badlogic.gdx.utils.Disposable) blades).dispose(); instances.dispose(); preview.dispose(); assets.dispose();
            applySources(manager, Map.of(), "restore-proxies");
        }
    }



    /** Two real submitted reed clump views; no flags fabricated on grass or alternate plant templates. */
    private static void clumpFrames(GpuShaderManager manager) throws Exception {
        assertTrue(proxyFrames(GpuShaderSource.readDisk("terrain-foliage.frag")),
              "Clump follow-up requires installed represented frames");
        var path = java.nio.file.Path.of(System.getProperty("megamek.gpu.screenshots"), "grazing-clumps");
        assertTrue(java.nio.file.Files.notExists(path), "Preserve previous clump evidence");
        java.nio.file.Files.createDirectories(path);
        String source = proxySource(GpuShaderSource.readDisk("terrain-vegetation.frag"), true, true);
        int end = source.lastIndexOf('}');
        // Appended after the existing diagnostic modes: these two observations tag only actual flagged clumps.
        source = source.substring(0, end) + """
              #if defined(lightingFlag) && defined(biomeVegetationFlag)
              if(u_grazingReview==11) fragColor=vec4(dot(normal,-viewDirection()),
                    dot(reflect(viewDirection(),normal),reviewFrame),
                    dot(reflect(viewDirection(),normal),reviewGeometry),.375);
              if(u_grazingReview==12) fragColor=vec4(dot(reviewFrame,-viewDirection()),1.0,float(gl_FrontFacing),.375);
              #endif
              """ + source.substring(end);
        applySources(manager, Map.of("terrain-vegetation.frag", source), "actual-clump-diagnostics");
        var originalGeometry = BoardGeometry.tuning();
        var originalLod = TerrainLod.tuning();
        var terrain = new GpuTerrain();
        var rows = new java.util.ArrayList<Object>();
        try (var output = new GpuSceneTestOutput(128, 128)) {
            BoardScenes.tune(.94f, true);
            TerrainLod.tune(new TerrainLod.Tuning(1, 1)); // Same fully installed ground as the existing biome fixture.
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            terrain.setGrass(true);
            var batchField = GpuTerrain.class.getDeclaredField("batch"); batchField.setAccessible(true);
            ModelBatch batch = (ModelBatch) batchField.get(terrain);
            var plantsField = GpuTerrain.class.getDeclaredField("biomeVegetation"); plantsField.setAccessible(true);
            var plants = (GpuBiomeVegetation) plantsField.get(terrain);
            float dpi = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
            var camera = new BoardCamera(); camera.resize(Math.round(128 / dpi), Math.round(128 / dpi));
            camera.camera.viewportWidth = 128 / dpi; camera.camera.viewportHeight = 128 / dpi;
            camera.setIsometric(true);
            for (int specimen = 0; specimen < 2; specimen++) {
                boolean near = specimen == 0;
                float pixels = near ? 560 : 48;
                camera.camera.zoom = BoardGeometry.width() * dpi / pixels;
                // Exact real MARSH construction used by GpuBiomeLodSmokeTest.
                BoardScene scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                      c.getX() >= 3 && c.getX() <= 5 && c.getY() >= 3 && c.getY() <= 5
                            ? BoardScene.Biome.MARSH : BoardScene.Biome.MUD, 0));
                Vector3 focus = BoardGeometry.center(new megamek.common.board.Coords(4, 4), 0);
                camera.center(focus);
                String id = "MARSH-tier" + (near ? 0 : 2);
                float projected = BoardGeometry.width() * 128 / (camera.camera.viewportHeight * camera.camera.zoom);
                assertEquals(projected, BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera.camera, focus), .001f,
                      "Actual FBO footprint and production LOD selection agree");
                terrain.update(scene, camera.camera);
                GpuReviewFrame.settle(terrain, null, scene, camera);
                terrain.animate(0, java.util.List.of());
                terrain.renderShadows(camera.camera, java.util.List.of());
                Runnable draw = () -> terrain.render(camera.camera);
                setProxyMode(batch, 0);
                specimenFrame(output, terrain, camera.camera, draw); // Resolve actual lazy resources/programs first.
                boolean drawn = false;
                for (Object plantBatch : GpuBiomeSmokeTest.batches(plants)) {
                    var lod = plantBatch.getClass().getDeclaredField("lod"); lod.setAccessible(true);
                    var current = plantBatch.getClass().getDeclaredField("current"); current.setAccessible(true);
                    drawn |= lod.getInt(plantBatch) == (near ? 0 : 2) && !((java.util.List<?>) current.get(plantBatch)).isEmpty();
                }
                assertTrue(drawn, "Actual expected reed tier is submitted: " + id);
                ProxyFrame full = specimenFrame(output, terrain, camera.camera, draw);
                ProxyFrame repeat = specimenFrame(output, terrain, camera.camera, draw);
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.rgba(), repeat.rgba(), "Frozen actual clump radiance");
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), repeat.depth(), "Frozen actual clump depth");
                saveProxy(output, path.resolve(id + ".png"));
                setProxyMode(batch, 11);
                ProxyFrame support = specimenFrame(output, terrain, camera.camera, draw);
                setProxyMode(batch, 12);
                ProxyFrame roles = specimenFrame(output, terrain, camera.camera, draw);
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), support.depth(), "Clump support probe preserves depth");
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), roles.depth(), "Clump role probe preserves depth");
                setProxyMode(batch, 0);
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.rgba(),
                      specimenFrame(output, terrain, camera.camera, draw).rgba(), "Restore exact clump production output");
                int covered = 0, carrierNegative = 0, backs = 0;
                float minimumView = 1, minimumSupport = 1, minimumCarrier = 1;
                for (int pixel = 0; pixel < full.depth().length; pixel++) {
                    int at = pixel * 4;
                    assertEquals(support.rgba()[at + 3], roles.rgba()[at + 3], 0, "Only diagnostic output changes");
                    if (roles.rgba()[at + 3] != .375f) { continue; }
                    assertTrue(full.depth()[pixel] < 1, "Tagged actual clump owns depth");
                    assertEquals(1, roles.rgba()[at + 1], 0, "Actual shader role matches the requested specimen");
                    assertTrue(roles.rgba()[at] >= -.001 && roles.rgba()[at] <= 1.001, "Visible independent aggregate frame");
                    assertTrue(support.rgba()[at] >= -.001 && support.rgba()[at + 1] >= -.003,
                          "Actual clump reflection stays above its independent virtual frame: " + id + " pixel=" + pixel);
                    minimumView = Math.min(minimumView, support.rgba()[at]);
                    minimumSupport = Math.min(minimumSupport, support.rgba()[at + 1]);
                    minimumCarrier = Math.min(minimumCarrier, support.rgba()[at + 2]);
                    if (support.rgba()[at + 2] < -.003) { carrierNegative++; }
                    if (roles.rgba()[at + 2] == 0) { backs++; }
                    covered++;
                }
                assertTrue(covered > 0, "Requested actual clump, not bare ground, must cover pixels: " + id);
                var row = new java.util.LinkedHashMap<String, Object>();
                row.put("id", id); row.put("covered", covered); row.put("carrierNegative", carrierNegative); row.put("backFaces", backs);
                row.put("minimumView", minimumView); row.put("minimumVirtualSupport", minimumSupport); row.put("minimumCarrierSupport", minimumCarrier);
                row.put("camera", camera.camera.combined.val.clone()); row.put("pixelsPerHex", projected);
                row.put("imageSha256", fileHash(path.resolve(id + ".png"))); rows.add(row);
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                      .writeValue(path.resolve("review.json").toFile(), Map.of("renderer", Gdx.gl.glGetString(GL20.GL_RENDERER), "rows", rows));
            }
            assertEquals(2, rows.size());
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            try { terrain.dispose(); }
            finally {
                BoardGeometry.tune(originalGeometry); TerrainLod.tune(originalLod);
                applySources(manager, Map.of(), "restore-clumps");
            }
        }
    }

    /** Same provider/program, 1->0->1 local roles, carrier-only rotation and both visible sides. */
    private static void proxyRoleBoundary(GpuTerrain preview, ModelBatch batch,
          java.lang.reflect.Method prepare, GpuSceneTestOutput output, java.nio.file.Path destination) throws Exception {
        var environment = new Environment();
        for (var attribute : preview.environment()) { environment.set(attribute); }
        Texture white = texture(0xffffffff), flat = texture(0x8080ffff), tissue = texture(0xffda80ff);
        var models = new java.util.ArrayList<com.badlogic.gdx.graphics.g3d.Model>();
        var rows = new java.util.ArrayList<Object>();
        Object sharedShader = null;
        try {
            for (float side : new float[] { 1, -1 }) {
                var view = new Vector3(.8f, 0, .6f).scl(side);
                float[][] reference = new float[3][];
                for (int carrier = 0; carrier < 2; carrier++) {
                    int roleIndex = 0;
                    for (String role : new String[] { "canopy-cutout", "bark", "canopy-snow-cutout" }) {
                        var model = surfacePlate(new Material(role, ColorAttribute.createDiffuse(Color.WHITE),
                              TextureAttribute.createDiffuse(white), TextureAttribute.createNormal(flat),
                              TextureAttribute.createAmbient(tissue), IntAttribute.createCullFace(GL20.GL_NONE)), false);
                        models.add(model); prepare.invoke(null, model);
                        // Rotate positions only. The represented base/detail stay Z on both coverage carriers.
                        if (carrier == 1) {
                            for (Mesh mesh : model.meshes) {
                                int stride = mesh.getVertexSize() / Float.BYTES;
                                int pos = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
                                float[] vertices = new float[mesh.getNumVertices() * stride]; mesh.getVertices(vertices);
                                for (int at = pos; at < vertices.length; at += stride) {
                                    float x = vertices[at]; vertices[at] = .6f * x; vertices[at + 2] = -.8f * x;
                                }
                                mesh.setVertices(vertices);
                            }
                        }
                        var instance = new ModelInstance(model);
                        setProxyMode(batch, 0);
                        modelPixel(output, batch, instance, environment, view, preview); // First use.
                        float[] color = modelPixel(output, batch, instance, environment, view, preview);
                        org.junit.jupiter.api.Assertions.assertArrayEquals(color,
                              modelPixel(output, batch, instance, environment, view, preview), "Frozen role color");
                        var part = GpuTerrainDepth.snapshot(java.util.List.of(instance)).first();
                        part.environment = environment;
                        Object shader = batch.getShaderProvider().getShader(part);
                        if (sharedShader == null) { sharedShader = shader; }
                        else { org.junit.jupiter.api.Assertions.assertSame(sharedShader, shader, "Roles share one actual program"); }
                        assertEquals(role, part.material.id, "ModelInstance preserves the authored role");
                        setProxyMode(batch, 10);
                        float[] tag = modelPixel(output, batch, instance, environment, view, preview);
                        assertEquals(roleIndex == 1 ? 0 : 1, tag[0], 0, "Local setter 1->0->1 without stale state");
                        assertEquals(side > 0 ? 1 : 0, tag[2], 0, "Both actual carrier windings are exercised");
                        setProxyMode(batch, 1);
                        float[] support = modelPixel(output, batch, instance, environment, view, preview, false);
                        assertTrue(support[1] >= -.001 && support[2] >= -.003, "Represented/real support remains valid");
                        if (carrier == 0) { reference[roleIndex] = color; }
                        else if (roleIndex != 1) {
                            assertTrue(tag[1] < -.2, "Unchanged virtual lobe crosses the rotated carrier");
                            for (int c = 0; c < 3; c++) {
                                double a = reference[roleIndex][c], b = color[c];
                                double spacing = Math.scalb(1.0, Math.max(-24,
                                      Math.getExponent(Math.max(Math.abs(a), Math.abs(b)) * GpuSceneRadiance.SCALE) - 10))
                                      / GpuSceneRadiance.SCALE;
                                assertEquals(a, b, 2 * spacing, "Carrier orientation must not relight the represented surface");
                            }
                        } else {
                            assertTrue(tag[1] >= -.003, "Real bark retains carrier-sided reflection repair");
                        }
                        rows.add(Map.of("role", role, "side", side, "carrier", carrier,
                              "radiance", color, "roleCarrierSupportFront", tag, "representedSupport", support));
                        roleIndex++;
                    }
                }
            }
            assertEquals(12, rows.size());
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                  .writeValue(destination.resolve("role-boundary.json").toFile(), rows);
        } finally {
            setProxyMode(batch, 0);
            for (var model : models) { model.dispose(); }
            white.dispose(); flat.dispose(); tissue.dispose();
        }
    }

    /** Failure-only raw frame evidence; the original support/continuity tolerances remain unchanged. */
    private static void proxyFailure(GpuSceneTestOutput output, GpuTerrain preview, ModelBatch batch,
          OrthographicCamera camera, Runnable draw, java.nio.file.Path destination, String id, int pixel,
          ProxyFrame full, ProxyFrame probe) throws Exception {
        var path = destination.resolve(id + "-failure.json");
        assertTrue(java.nio.file.Files.notExists(path), "Preserve previous proxy failure evidence");
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("id", id); details.put("pixel", pixel);
        details.put("pixelX", pixel % 128); details.put("pixelYBottom", pixel / 128);
        details.put("pixelYTop", 127 - pixel / 128);
        details.put("tupleOriginalCorrectedSupportNgV", java.util.Arrays.copyOfRange(probe.rgba(), pixel * 4, pixel * 4 + 4));
        details.put("sceneDepth", full.depth()[pixel]); details.put("camera", camera.combined.val.clone());
        details.put("radiance", java.util.Arrays.copyOfRange(full.rgba(), pixel * 4, pixel * 4 + 4));
        details.put("scope", "Raw RGBA16F outputs in canonical units; RGB storage scale undone once. "
              + "Vectors incur half-float storage quantization; log2(length2) retains the tiny cancellation scale. Production and independent geometry are separate outputs. "
              + "Only the same covered3x3 neighborhood is recorded; these are diagnostics, not new material inputs.");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();
        mapper.writeValue(path.toFile(), details);
        System.err.println("Grazing proxy failure " + id + " pixel=" + pixel + " tuple="
              + java.util.Arrays.toString(java.util.Arrays.copyOfRange(probe.rgba(), pixel * 4, pixel * 4 + 4)));
        var vectors = new java.util.LinkedHashMap<String, Object>();
        details.put("vectors", vectors);
        String[] names = {"originalNormal", "correctedNormal", "productionGeometry", "diagnosticGeometry", "view",
              "inputNvNgGv", "acrossLog2LengthSquaredAlignmentNormalLengthFaceLength", "normalizedAcross"};
        try {
            for (int mode = 2; mode <= 9; mode++) {
                setProxyMode(batch, mode);
                ProxyFrame frame = specimenFrame(output, preview, camera, draw);
                org.junit.jupiter.api.Assertions.assertArrayEquals(full.depth(), frame.depth(),
                      "Failure-only vector output preserves depth");
                var samples = new java.util.ArrayList<Object>();
                for (int y = Math.max(0, pixel / 128 - 1); y <= Math.min(127, pixel / 128 + 1); y++) {
                    for (int x = Math.max(0, pixel % 128 - 1); x <= Math.min(127, pixel % 128 + 1); x++) {
                        int at = y * 128 + x;
                        if (full.depth()[at] < 1) {
                            samples.add(Map.of("pixel", at, "x", x, "yBottom", y, "yTop", 127 - y,
                                  "rgba", java.util.Arrays.copyOfRange(frame.rgba(), at * 4, at * 4 + 4)));
                        }
                    }
                }
                vectors.put(names[mode - 2], samples);
                mapper.writeValue(path.toFile(), details);
            }
        } finally {
            setProxyMode(batch, 0);
            org.junit.jupiter.api.Assertions.assertArrayEquals(full.rgba(),
                  specimenFrame(output, preview, camera, draw).rgba(), "Restore full output after failure diagnostics");
        }
    }

    /**
     * Whether the installed foliage shader has represented proxy frames: it declares the role uniform GpuTerrain sets.
     * Baseline shaders lack it, so a run follows whichever sources are installed.
     */
    private static boolean proxyFrames(String foliage) {
        return foliage.contains("uniform float u_foliageProxy;");
    }

    private static String proxySource(String source, boolean tuft, boolean virtualFrames) {
        // Old baseline shaders have no repaired geometric frame; the independent pre-discard probe still does.
        String frame = source.contains("vec3 geometric =") ? "geometric" : "reviewGeometry";
        String marker = tuft ? "    vec3 albedo = v_color.rgb" : "    vec3 view = -viewDirection();";
        int at = source.indexOf(marker); assertTrue(at >= 0 && source.indexOf(marker, at + 1) < 0);
        String virtual = !virtualFrames ? "" : tuft ? ""
              + "#ifdef biomeVegetationFlag\n"
              + "reviewProxy=true; reviewFrame=normal;\n#endif\n"
              : "#ifdef impostorFlag\nreviewProxy=true;\n#else\nreviewProxy=u_foliageProxy>.5;\n#endif\n"
              + "if(reviewProxy) reviewFrame=normalize(v_normal);\n";
        source = source.substring(0, at) + "    vec3 reviewOriginal = " + (tuft ? "normal" : "face") + ";\n"
              + virtual + "if(dot(reviewFrame,-viewDirection())<0.0) reviewFrame=-reviewFrame;\n" + source.substring(at);
        source = source.replace("void main() {", "uniform int u_grazingReview;\nvoid main() {\n"
              + "vec3 reviewGeometry = normalize(cross(dFdx(v_cloudPosition),dFdy(v_cloudPosition)));\n"
              + "if(dot(reviewGeometry,-viewDirection())<0.0) reviewGeometry=-reviewGeometry;\n"
              + "vec3 reviewFrame=reviewGeometry; bool reviewProxy=false;\n");
        at = source.lastIndexOf('}');
        String n = tuft ? "normal" : "face";
        return source.substring(0, at) + "\n#ifdef lightingFlag\nif(u_grazingReview==1) fragColor=vec4("
              + "dot(reviewOriginal,-viewDirection()),dot(" + n + ",-viewDirection()),"
              + "dot(reflect(viewDirection()," + n + "),reviewFrame),dot(reviewFrame,-viewDirection()));\n"
              + "else if(u_grazingReview==10) fragColor=vec4(float(reviewProxy),"
              + "dot(reflect(viewDirection()," + n + "),reviewGeometry),float(gl_FrontFacing),1);\n"
              + "else if(u_grazingReview==2) fragColor=vec4(reviewOriginal,1);\n"
              + "else if(u_grazingReview==3) fragColor=vec4(" + n + ",1);\n"
              + "else if(u_grazingReview==4) fragColor=vec4(" + frame + ",1);\n"
              + "else if(u_grazingReview==5) fragColor=vec4(reviewGeometry,1);\n"
              + "else if(u_grazingReview==6) fragColor=vec4(-viewDirection(),1);\n"
              + "else if(u_grazingReview==7) fragColor=vec4(dot(reviewOriginal,-viewDirection()),"
              + "dot(reviewOriginal," + frame + "),dot(" + frame + ",-viewDirection()),1);\n"
              + "else if(u_grazingReview>=8) { vec3 across=reviewOriginal-" + frame + "*dot(reviewOriginal," + frame + ");"
              + "float length2=dot(across,across); vec3 axis=length2>0.0?across*inversesqrt(length2):vec3(0);"
              + "fragColor=u_grazingReview==8?vec4(log2(max(length2,1e-30)),dot(axis," + frame + "),"
              + "length(reviewOriginal),length(" + frame + ")):vec4(axis,length(-viewDirection())); }\n#endif\n"
              + source.substring(at);
    }

    private static void setProxyMode(ModelBatch batch, int mode) throws Exception {
        var provider = batch.getShaderProvider();
        if (provider instanceof GpuShaderProvider editable) { provider = editable.active(); }
        var field = com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider.class.getDeclaredField("shaders");
        field.setAccessible(true);
        for (Object shader : (Iterable<?>) field.get(provider)) {
            var program = ((com.badlogic.gdx.graphics.g3d.shaders.BaseShader) shader).program;
            int location = program.getUniformLocation("u_grazingReview");
            if (location >= 0) { program.bind(); program.setUniformi(location, mode); }
        }
    }

    private record ProxyFrame(float[] rgba, float[] depth) { }

    private static ProxyFrame specimenFrame(GpuSceneTestOutput output, GpuTerrain preview,
          com.badlogic.gdx.graphics.Camera camera, Runnable draw) {
        GpuTreeLodSmokeTest.newShadingPass(preview); // Invalidate the actual provider's per-frame camera/light cache.
        output.begin(0, 0, 0, 0); draw.run();
        float[] rgba = output.linearPixels(); finite(rgba);
        float[] depth = new float[128 * 128];
        output.scene().begin();
        try {
            var buffer = BufferUtils.newFloatBuffer(depth.length);
            Gdx.gl.glReadPixels(0, 0, 128, 128, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, buffer); buffer.get(depth);
        } finally { output.scene().end(); }
        for (float value : depth) { assertTrue(Float.isFinite(value) && value >= 0 && value <= 1, "Valid scene depth"); }
        return new ProxyFrame(rgba, depth);
    }

    private static void saveProxy(GpuSceneTestOutput output, java.nio.file.Path path) {
        output.display();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, 128, 128);
        try { com.badlogic.gdx.graphics.PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(path.toFile()), pixels, -1, true); }
        finally { pixels.dispose(); }
    }

    private static String bytesHash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String fileHash(java.nio.file.Path path) throws Exception { return bytesHash(java.nio.file.Files.readAllBytes(path)); }
}
