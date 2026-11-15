/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;

/** A render-thread-owned sample stage. Only immutable preview images cross to Swing; no game orders or live units. */
final class GpuShaderPreview implements Disposable {
    static final int WIDTH = 384, HEIGHT = 240;
    private static final int[] CAPABILITIES = { GL20.GL_SCISSOR_TEST, GL20.GL_DEPTH_TEST, GL20.GL_CULL_FACE, GL20.GL_BLEND };

    enum Preset {
        AUTO("Automatic", ""), EXPLOSION("Explosion", ""), ARTILLERY("Artillery blast", ""),
        LASER("Laser", "ISMediumLaser"), PPC("PPC", "ISPPC"),
        PROJECTILE("Autocannon", "ISAC5"), IMPACT("Ballistic impact", "ISAC5"),
        MISSILE("Missile salvo", "ISSRM6"), FLAME("Flamethrower", "ISFlamer"), SMOKE("Smoke particles", ""), JET("Jet flame", ""),
        FIRE("Ground fire / smoke", ""), RAIN("Rain", ""), SNOW("Snow", ""), HAIL("Hail", ""), MATERIAL("Material sphere", ""),
        NONE("Live board", "");

        final String title, weapon;
        Preset(String title, String weapon) { this.title = title; this.weapon = weapon; }
        @Override
        public String toString() { return title; }
        boolean ballistic() { return this == PROJECTILE || this == IMPACT; }
        boolean precipitation() { return this == RAIN || this == SNOW || this == HAIL; }
        /** Samples drawn over the sample board's hex rather than at the stage's origin. */
        boolean onBoardHex() { return this == EXPLOSION || this == ARTILLERY || this == FIRE || precipitation(); }

        static Preset forFile(String name) {
            if (name == null) { return NONE; }
            if (name.startsWith("explosion.")) { return EXPLOSION; }
            if (name.equals("beams.frag") || name.equals("effects.vert")) { return LASER; }
            if (name.startsWith("missile.")) { return MISSILE; }
            if (name.equals("projectiles.frag")) { return IMPACT; }
            if (name.equals("particles.frag") || name.equals("particles-fire.glsl")) { return FLAME; }
            if (name.equals("particles-smoke.glsl")) { return SMOKE; }
            if (name.equals("particles-jet.glsl")) { return JET; }
            if (name.startsWith("terrain-effects") || name.equals("camera-depth.glsl")) { return FIRE; }
            if (name.startsWith("weather-particles.") || name.equals("weather-rain.glsl")) { return RAIN; }
            if (name.equals("weather-snow.glsl")) { return SNOW; }
            if (name.equals("weather-hail.glsl")) { return HAIL; }
            if (name.startsWith("unit-material.") || name.startsWith("linear-") || name.equals("light-model.glsl")) { return MATERIAL; }
            return NONE;
        }
    }

    /** Presentation inputs for the isolated sample; the live board always uses its resolved attack. */
    record Inputs(int rackSize, int shots, int missiles, int missileHits, boolean hit) {
        static final Inputs DEFAULT = new Inputs(5, 1, 6, 6, true);
    }

    record Settings(String file, Preset preset, boolean visible, boolean playing, float speed,
          float yaw, float pitch, float zoom, int restart, Inputs inputs) {
        Preset resolved() { return preset == Preset.AUTO ? Preset.forFile(file) : preset; }
        Settings withInputs(Inputs values) { return new Settings(file, preset, visible, playing, speed, yaw, pitch, zoom, restart, values); }
    }
    /** The image is never modified after publication. */
    record Frame(BufferedImage image, String message, Preset preset, float seconds) { }

    private final OrthographicCamera camera = new OrthographicCamera();
    private final Environment light = new Environment();
    private final BoardAtmosphere.Lighting lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
    private final GpuEnvironmentLighting environmentLighting = new GpuEnvironmentLighting();
    private final GpuAttackEffects attacks = new GpuAttackEffects();
    private final GpuExplosionEffects artilleryVolumes = new GpuExplosionEffects();
    private final GpuArtilleryBlast artilleryBlast = new GpuArtilleryBlast();
    private final Vector3 artilleryGround = new Vector3();
    private final GpuEffectDepth depth = new GpuEffectDepth();
    private final BoardSurface.Cache surfaces = new BoardSurface.Cache();
    private final Vector3 focus = new Vector3();
    private final IntBuffer state = BufferUtils.newIntBuffer(24);
    private final ByteBuffer masks = BufferUtils.newByteBuffer(5);
    private final FloatBuffer floats = BufferUtils.newFloatBuffer(6);
    private FrameBuffer buffer;
    private FrameBuffer sceneBuffer;
    private GpuSceneDisplay display;
    private ModelBatch batch;
    private Model sphereModel, floorModel, sourceModel;
    private ModelInstance sphere, floor, source;
    private Texture checker;
    private GpuTerrainEffects groundFire;
    private GpuWeatherParticles weather;
    private final GpuEffectBatch sampleParticles = new GpuEffectBatch(3);
    private final Vector3 particleOrigin = new Vector3(), particleEnd = new Vector3();
    private BoardScene sampleScene;
    private UnitAttack attack;
    private Settings previous;
    private Preset preset = Preset.NONE;
    private long lastFrame, revision = -1;
    private float seconds;
    private String failure;

    /** At most 30 small readbacks per second. Hidden editors do no preview work. {@code switches}: the board's. */
    Frame render(Settings settings, BoardSwitches switches, long now, long shaderRevision) {
        if (!settings.visible()) { lastFrame = 0; return null; }
        boolean changed = !settings.equals(previous) || shaderRevision != revision;
        if (lastFrame != 0 && now - lastFrame < 33_333_333L) { return null; }
        if (!changed && (failure != null || !settings.playing() || settings.resolved() == Preset.NONE)) { return null; }
        float dt = lastFrame == 0 ? 0 : Math.min(.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        boolean restart = previous == null || settings.restart() != previous.restart() || settings.resolved() != preset || buffer == null;
        boolean inputsChanged = previous == null || !settings.inputs().equals(previous.inputs());
        previous = settings;
        revision = shaderRevision;
        failure = null;
        preset = settings.resolved();
        if (preset == Preset.NONE) {
            return new Frame(null, "This shader needs the live board.\nChoose a sample using the menu.", preset, 0);
        }
        saveState();
        try {
            initialize();
            if (restart) {
                seconds = preset == Preset.IMPACT ? .07f : .28f;
                if (preset == Preset.FIRE && groundFire != null) { groundFire.dispose(); groundFire = null; }
            }
            else if (settings.playing()) { seconds += dt * settings.speed(); }
            if (restart || inputsChanged) { attack = sampleAttack(preset, settings.inputs()); }
            if (attack != null) {
                attack.seconds = preset == Preset.IMPACT
                      ? attack.contactSeconds + seconds % (UnitAttack.RECOVERY_SECONDS + .15f)
                      : seconds % (attack.duration + .15f);
            }
            sceneBuffer.bind();
            Gdx.gl.glViewport(0, 0, WIDTH, HEIGHT);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glColorMask(true, true, true, true);
            Gdx.gl.glDepthMask(true);
            float exposure = lighting.adaptation();
            float backdropScale = GpuSceneRadiance.SCALE / exposure;
            ScreenUtils.clear(GpuColor.linear(.055f) * backdropScale,
                  GpuColor.linear(.065f) * backdropScale,
                  GpuColor.linear(.085f) * backdropScale, 1, true);
            positionCamera(settings);
            GpuEnvironmentLighting.applySwitches(light, switches);
            batch.begin(camera);
            batch.render(floor, light);
            if (preset == Preset.MATERIAL) {
                sphere.transform.setToTranslation(0, 0, 18).rotate(Vector3.Z, seconds * 22);
                batch.render(sphere, light);
            } else if (!preset.weapon.isEmpty()) {
                source.transform.setToTranslation(-55, 0, 7);
                sphere.transform.setToTranslation(55, 0, 12).scale(.65f, .65f, .65f);
                batch.render(source, light);
                batch.render(sphere, light);
            }
            batch.end();
            depth.begin();
            String title = preset.title;
            if (preset == Preset.FIRE) {
                if (groundFire == null) { groundFire = new GpuTerrainEffects(); }
                groundFire.update(sampleScene, surfaces, BoardAtmosphere.Effects.NONE, settings.playing() ? dt * settings.speed() : 0);
                groundFire.render(camera, depth, Color.WHITE, new Vector3(-.3f, .5f, -1).nor());
            } else if (preset == Preset.SMOKE || preset == Preset.JET) {
                sampleParticles.begin();
                if (preset == Preset.SMOKE) {
                    for (int i = 0; i < 3; i++) {
                        float age = (seconds * .3f + i / 3f) % 1;
                        particleOrigin.set(0, 0, 8 + age * 45);
                        sampleParticles.billboard(camera, particleOrigin, 9 + age * 12, 0, (1 - age) * .75f);
                    }
                } else {
                    particleOrigin.set(0, 0, 55);
                    particleEnd.set(0, 0, 8 + 3 * MathUtils.sin(seconds * 13));
                    sampleParticles.ribbon(camera, particleOrigin, particleEnd, 7, 1, .85f);
                }
                sampleParticles.render(camera, preset == Preset.SMOKE ? sampleParticles.size() : 0);
            } else if (preset == Preset.ARTILLERY) {
                title += " (" + renderArtillery(depth) + ")";
            } else if (attack != null) {
                attacks.update(attack, null, preset == Preset.EXPLOSION ? Map.of()
                      : Map.of("1:-1", source, "2:-1", sphere));
                attacks.render(camera, depth);
            }
            if (preset.precipitation()) {
                if (weather == null) { weather = new GpuWeatherParticles(); }
                var effects = new BoardAtmosphere.Effects(preset == Preset.RAIN ? .7f : 0,
                      preset == Preset.SNOW ? .7f : 0, preset == Preset.HAIL ? .7f : 0, 0, 0, .15f, 90);
                weather.render(camera, sampleScene, effects, Color.WHITE, seconds);
            }
            display.render(sceneBuffer, buffer, exposure);
            return new Frame(image(WIDTH, HEIGHT), title + " · drag to orbit · wheel to zoom", preset, seconds);
        } catch (RuntimeException error) {
            failure = error.toString();
            if (batch != null && batch.getCamera() != null) {
                try { batch.end(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            return new Frame(null, "Preview unavailable: " + failure, preset, seconds);
        } finally {
            restoreState();
        }
    }

    /**
     * Plays one blast of each shape in turn, with a short pause between them, and names the shape that is showing.
     */
    private GpuArtilleryBlast.Style renderArtillery(GpuEffectDepth snapshot) {
        float loop = GpuArtilleryBlast.SECONDS + .5f;
        GpuArtilleryBlast.Style[] styles = GpuArtilleryBlast.Style.values();
        GpuArtilleryBlast.Style style = styles[(int) (seconds / loop) % styles.length];
        artilleryVolumes.begin();
        artilleryGround.set(focus.x, focus.y, 0);
        // A Sniper or Arrow IV round: its debris lands across the ring of hexes around the target.
        artilleryBlast.emit(artilleryVolumes, artilleryGround, GpuArtilleryBlast.size(null), 1, style,
              seconds % loop, 23);
        artilleryVolumes.render(camera, snapshot);
        return style;
    }

    private void initialize() {
        if (buffer != null) { return; }
        try { createStage(); }
        catch (RuntimeException error) { dispose(); throw error; }
    }

    private void createStage() {
        buffer = GpuAtmosphere.buffer(WIDTH, HEIGHT, true);
        sceneBuffer = GpuHeatGlow.buffer(WIDTH, HEIGHT, true);
        display = new GpuSceneDisplay();
        batch = new ModelBatch(GpuShaderManager.provider("Preview material", GpuUnitShader::provider));
        Pixmap pixels = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
        try {
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    pixels.drawPixel(x, y, ((x / 8 + y / 8) & 1) == 0 ? 0xaeb7c5ff : 0x606b7bff);
                }
            }
            checker = GpuColorTexture.create(pixels, false);
        } finally { pixels.dispose(); }
        checker.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        var model = new ModelBuilder();
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates;
        sphereModel = model.createSphere(36, 36, 36, 32, 24, new Material(TextureAttribute.createDiffuse(checker)), attributes);
        floorModel = model.createBox(480, 320, 2, new Material(ColorAttribute.createDiffuse(.19f, .23f, .29f, 1)), attributes);
        sourceModel = model.createBox(8, 8, 14, new Material(ColorAttribute.createDiffuse(.35f, .4f, .46f, 1)), attributes);
        sphere = new ModelInstance(sphereModel);
        floor = new ModelInstance(floorModel);
        floor.transform.setToTranslation(0, 0, -1);
        source = new ModelInstance(sourceModel);
        light.clear();
        environmentLighting.set(lighting);
        light.set(environmentLighting);
        light.set(ColorAttribute.createAmbientLight(lighting.ambient()));
        light.add(new DirectionalLight().set(lighting.direct(), lighting.direction()));
        var tile = new BoardScene.Tile(new Coords(0, 0), BoardScene.Tile.Ground.plain(0, -1, BoardScene.Surface.DIRT),
              List.of(), BoardScene.Cover.NONE, false, false, List.of(), null, null, new BoardFireSmoke(1, 0), false, null);
        sampleScene = new BoardScene(-1, 1, 1, List.of(tile), List.of(), List.of(), -1, "Shader preview", List.of());
    }

    private void positionCamera(Settings settings) {
        float span = switch (preset) {
            case MATERIAL, SMOKE, JET -> 92;
            case IMPACT -> 65;
            case EXPLOSION -> 95;
            case ARTILLERY -> BoardGeometry.height() * 3.6f;
            case FIRE -> BoardGeometry.width() * 3;
            case RAIN, SNOW, HAIL -> 200;
            default -> 170;
        };
        focus.set(0, 0, switch (preset) {
            case MATERIAL, EXPLOSION -> 18;
            case ARTILLERY -> BoardGeometry.height() * .5f;
            case SMOKE, JET -> 30;
            case FIRE -> BoardGeometry.level() * 3;
            case RAIN, SNOW, HAIL -> 40;
            default -> 10;
        });
        if (preset == Preset.IMPACT) { focus.set(55, 0, 12); }
        if (preset.onBoardHex()) {
            focus.x = BoardGeometry.centerX(new Coords(0, 0));
            focus.y = BoardGeometry.centerY(new Coords(0, 0));
        }
        camera.viewportWidth = span * settings.zoom();
        camera.viewportHeight = camera.viewportWidth * HEIGHT / WIDTH;
        camera.near = 1;
        camera.far = 1600;
        camera.up.set(Vector3.Z);
        camera.position.set(MathUtils.cosDeg(settings.yaw()) * MathUtils.cosDeg(settings.pitch()),
              MathUtils.sinDeg(settings.yaw()) * MathUtils.cosDeg(settings.pitch()), MathUtils.sinDeg(settings.pitch()))
              .scl(600).add(focus);
        camera.lookAt(focus);
        camera.update();
    }

    /** Sample inputs go through the real attack presentation, including CPU-generated spark geometry. */
    private static UnitAttack sampleAttack(Preset preset, Inputs inputs) {
        if (preset.weapon.isEmpty() && preset != Preset.EXPLOSION) { return null; }
        var origin = new BoardScene.Waypoint(new Coords(0, 0), 0, 0);
        var target = new BoardScene.Waypoint(new Coords(2, 0), 0, 0);
        if (preset == Preset.EXPLOSION) { target = origin; }
        var attacker = new BoardScene.Unit(1, -1, "Preview emitter", origin, null, false, null, 1, false);
        var victim = new BoardScene.Unit(2, -1, "Preview target", target, null, false, null, 1, false);
        int missiles = preset == Preset.MISSILE ? inputs.missiles() : 0;
        int hits = preset == Preset.MISSILE ? inputs.missileHits() : 0;
        var shot = new ResolvedAttack.Shot("", Set.of(), false, false, preset.ballistic() ? inputs.shots() : 1, missiles, false, hits,
              null, null, preset.ballistic(), inputs.rackSize(), preset == Preset.PPC);
        boolean hit = preset == Preset.MISSILE ? hits > 0 : inputs.hit();
        var result = new ResolvedAttack(new UUID(23, 42), preset == Preset.EXPLOSION ? ResolvedAttack.Kind.DEATH : ResolvedAttack.Kind.SHOT,
              new UnitLocation(1, origin.coords(), 0, 0, 0), new UnitLocation(2, target.coords(), 0, 0, 0),
              Targetable.TYPE_ENTITY, 0, preset.weapon, 0, hit, List.of(new ResolvedAttack.Mount(1, 0)), shot);
        return new UnitAttack(new BoardScene.Combat(result, attacker, victim, target));
    }

    /** The bound framebuffer's displayed colour, upright, for Swing (also GpuMaterialSwatches). */
    static BufferedImage image(int width, int height) {
        byte[] rgba = ScreenUtils.getFrameBufferPixels(0, 0, width, height, true);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] rgb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < rgb.length; i++) {
            int offset = i * 4;
            rgb[i] = (rgba[offset] & 255) << 16 | (rgba[offset + 1] & 255) << 8 | rgba[offset + 2] & 255;
        }
        return image;
    }

    /** Preserve the shared context, including the UI's scissor, blend state and texture bindings. */
    private void saveState() {
        integer(GL20.GL_FRAMEBUFFER_BINDING, 0);
        int flags = 0;
        for (int i = 0; i < CAPABILITIES.length; i++) { if (Gdx.gl.glIsEnabled(CAPABILITIES[i])) { flags |= 1 << i; } }
        state.put(1, flags);
        state.position(4);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, state);
        integer(GL20.GL_ACTIVE_TEXTURE, 8);
        integer(GL20.GL_CURRENT_PROGRAM, 9);
        integer(GL20.GL_DEPTH_FUNC, 10);
        integer(GL20.GL_CULL_FACE_MODE, 11);
        integer(GL20.GL_BLEND_SRC_RGB, 12); integer(GL20.GL_BLEND_DST_RGB, 13);
        integer(GL20.GL_BLEND_SRC_ALPHA, 14); integer(GL20.GL_BLEND_DST_ALPHA, 15);
        for (int i = 0; i < 2; i++) {
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + i);
            integer(GL20.GL_TEXTURE_BINDING_2D, 16 + i);
        }
        integer(GL30.GL_READ_FRAMEBUFFER_BINDING, 18);
        integer(GL20.GL_RENDERBUFFER_BINDING, 19);
        integer(GL20.GL_ARRAY_BUFFER_BINDING, 20);
        integer(GL30.GL_VERTEX_ARRAY_BINDING, 21);
        integer(GL20.GL_PACK_ALIGNMENT, 22);
        integer(GL20.GL_UNPACK_ALIGNMENT, 23);
        masks.position(0); Gdx.gl.glGetBooleanv(GL20.GL_COLOR_WRITEMASK, masks);
        masks.position(4); Gdx.gl.glGetBooleanv(GL20.GL_DEPTH_WRITEMASK, masks);
        floats.clear(); Gdx.gl.glGetFloatv(GL20.GL_COLOR_CLEAR_VALUE, floats);
        floats.position(4); Gdx.gl.glGetFloatv(GL20.GL_DEPTH_RANGE, floats);
    }

    private void integer(int parameter, int index) {
        state.position(index); Gdx.gl.glGetIntegerv(parameter, state);
    }

    private void restoreState() {
        Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.get(0));
        Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, state.get(18));
        Gdx.gl.glViewport(state.get(4), state.get(5), state.get(6), state.get(7));
        for (int i = 0; i < CAPABILITIES.length; i++) {
            if ((state.get(1) & 1 << i) != 0) { Gdx.gl.glEnable(CAPABILITIES[i]); }
            else { Gdx.gl.glDisable(CAPABILITIES[i]); }
        }
        Gdx.gl.glUseProgram(state.get(9));
        Gdx.gl.glDepthFunc(state.get(10)); Gdx.gl.glCullFace(state.get(11));
        Gdx.gl.glBlendFuncSeparate(state.get(12), state.get(13), state.get(14), state.get(15));
        for (int i = 0; i < 2; i++) {
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + i);
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, state.get(16 + i));
        }
        Gdx.gl.glActiveTexture(state.get(8));
        Gdx.gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, state.get(19));
        Gdx.gl30.glBindVertexArray(state.get(21));
        Gdx.gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, state.get(20));
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, state.get(22));
        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, state.get(23));
        Gdx.gl.glColorMask(masks.get(0) != 0, masks.get(1) != 0, masks.get(2) != 0, masks.get(3) != 0);
        Gdx.gl.glDepthMask(masks.get(4) != 0);
        Gdx.gl.glClearColor(floats.get(0), floats.get(1), floats.get(2), floats.get(3));
        Gdx.gl.glDepthRangef(floats.get(4), floats.get(5));
    }

    @Override
    public void dispose() {
        attacks.dispose();
        artilleryVolumes.dispose();
        sampleParticles.dispose();
        depth.dispose();
        if (groundFire != null) { groundFire.dispose(); }
        if (weather != null) { weather.dispose(); }
        if (batch != null) { batch.dispose(); }
        environmentLighting.dispose();
        if (sphereModel != null) { sphereModel.dispose(); }
        if (floorModel != null) { floorModel.dispose(); }
        if (sourceModel != null) { sourceModel.dispose(); }
        if (checker != null) { checker.dispose(); }
        if (buffer != null) { buffer.dispose(); }
        if (sceneBuffer != null) { sceneBuffer.dispose(); sceneBuffer = null; }
        if (display != null) { display.dispose(); display = null; }
        groundFire = null; weather = null; batch = null; checker = null; buffer = null;
        sphereModel = floorModel = sourceModel = null;
    }
}
