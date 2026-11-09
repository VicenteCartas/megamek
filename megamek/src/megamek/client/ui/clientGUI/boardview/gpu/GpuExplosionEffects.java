/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Bounded, depth-clipped fire/smoke volumes. Owns its reusable coverage mesh; the caller owns the attack clock. */
final class GpuExplosionEffects implements Disposable {
    static final int CAPACITY = 512;
    private static final Comparator<Volume> BACK_TO_FRONT = Comparator.comparingDouble((Volume v) -> v.depth).reversed();
    private final Volume[] volumes = new Volume[CAPACITY];
    /** A fireball's light: hotter than a burning hex's. */
    private static final Vector3 FIRE = new Vector3(2.6f, 1.15f, .35f);
    private final Vector3 burstCenter = new Vector3();
    private final Vector3 axisScratch = new Vector3();
    private final Color light = new Color(Color.WHITE);
    private final Vector3 wind = new Vector3(0, 1, 0);
    private final Vector3 sun = new Vector3(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS).direction()).scl(-1);
    private final GpuEffectDepth ownDepth = new GpuEffectDepth();
    private ShaderProgram shader;
    private Mesh proxy;
    private int count;

    private static final class Volume {
        final Vector3 center = new Vector3();
        final Vector3 axis = new Vector3();
        float radius, age, fire, alpha, seed, depth, stretch, elongation;
        Look look = Look.PLAIN;
    }

    /**
     * A volume's palette beyond the plain fireball. Each value runs from 0 to 1, soot from -1.
     *
     * @param soot  how dark the smoke is: 0 is the plain grey fireball smoke, 1 is the charcoal of a heavy shell; below
     *              0 it pales toward dust that scatters the light, bright even where it shades itself, as a collapse's
     *              concrete dust is
     * @param ember how long fire keeps glowing inside the smoke after the blast has cooled
     * @param dust     how far the smoke is browned toward thrown earth
     * @param violence how violently it churns, as a fuel-air blast does: its billows start small and grow large as it
     *                 ages, and blinding pockets of burning metal flicker through its fire. 0 for every other blast
     */
    record Look(float soot, float ember, float dust, float violence) {
        static final Look PLAIN = new Look(0, 0, 0);

        Look(float soot, float ember, float dust) {
            this(soot, ember, dust, 0);
        }
    }

    void begin() { count = 0; }

    /**
     * This frame's burning volumes as local lights (GpuLavaLighting): a fireball lights the ground around it, up to
     * four of its radii away, as long as its fire lasts and fades.
     */
    void lights(List<GpuLavaLighting.Source> into) {
        for (int i = 0; i < count; i++) {
            Volume volume = volumes[i];
            float glow = volume.fire * volume.alpha * (1 - volume.age);
            if (glow < .05f) { continue; }
            into.add(new GpuLavaLighting.Source(-1, volume.center.cpy(), .8f * glow, FIRE, 2 * volume.radius));
        }
    }
    int size() { return count; }
    void setSmokeLight(Color color) { light.set(color); }
    void setLightDirection(Vector3 direction) { sun.set(direction).scl(-1).nor(); }

    /** The board's travel bearing and normalized visual strength, not a second weather simulation. */
    void setWind(BoardAtmosphere.Effects weather) {
        wind.set(MathUtils.sinDeg(weather.windDirection()), MathUtils.cosDeg(weather.windDirection()), weather.wind());
    }

    /**
     * Carries {@code point} as far downwind as the board's wind takes free smoke in {@code seconds}: nothing in calm
     * air, and most of a hex a second at full strength, along the same bearing the weather gives the shader.
     */
    Vector3 downwind(Vector3 point, float seconds) {
        float distance = wind.z * BoardGeometry.height() * .8f * seconds;
        return point.add(wind.x * distance, wind.y * distance, 0);
    }

    /** One expanding, buoyant fire-to-smoke volume, sampled entirely from the caller's normalized attack clock. */
    void burst(Vector3 center, float radius, float age, int seed) {
        if (age < 0 || age >= 1) { return; }
        float expansion = .24f + .76f * (1 - (float) Math.exp(-age * 6));
        float fade = MathUtils.clamp((age - .65f) / .35f, 0, 1);
        burstCenter.set(center).add(0, 0, radius * age * (.3f + age * .2f));
        add(burstCenter, radius * expansion, age, 1, 1 - fade * fade * (3 - 2 * fade), seed);
    }

    void add(Vector3 center, float radius, float age, float fire, float alpha, int seed) {
        add(center, radius, age, fire, alpha, seed, Look.PLAIN, Vector3.Zero);
    }

    /**
     * One volume with a palette, drawn out along {@code elongation}: its direction is the axis and its length how far
     * the volume is stretched along it, so a zero vector keeps it round. The leading end tapers to a tip.
     */
    void add(Vector3 center, float radius, float age, float fire, float alpha, int seed, Look look, Vector3 elongation) {
        float length = elongation.len();
        add(center, radius, age, fire, alpha, seed, look, length > 0 ? axisScratch.set(elongation).scl(1 / length) : Vector3.Z,
              length);
    }

    /**
     * One round volume squashed flat to {@code height} of its width, as a low cloud or sheet of fire spread over the
     * ground is; 1 keeps it round.
     */
    void addFlat(Vector3 center, float radius, float age, float fire, float alpha, int seed, Look look, float height) {
        // A squash is a stretch below zero along the vertical.
        add(center, radius, age, fire, alpha, seed, look, Vector3.Z, MathUtils.clamp(height, .1f, 1) - 1);
    }

    /** One volume drawn out along the unit {@code axis} by {@code elongation}: above 0 stretches, below 0 squashes. */
    private void add(Vector3 center, float radius, float age, float fire, float alpha, int seed, Look look,
          Vector3 axis, float elongation) {
        if (count == CAPACITY || radius <= 0 || alpha <= 0) { return; }
        Volume volume = volumes[count];
        if (volume == null) { volume = new Volume(); volumes[count] = volume; }
        volume.radius = radius;
        volume.age = MathUtils.clamp(age, 0, 1);
        // The pressure-driven initial blast resists wind; the cooling plume is entrained progressively.
        float drift = radius * wind.z * volume.age * volume.age * 1.4f;
        volume.center.set(center).add(wind.x * drift, wind.y * drift, 0);
        volume.stretch = wind.z * volume.age * .8f;
        volume.fire = MathUtils.clamp(fire, 0, 1);
        volume.alpha = MathUtils.clamp(alpha, 0, 1) / (1 + volume.stretch);
        volume.seed = Math.floorMod(seed, 4096) * .17f;
        volume.look = look;
        volume.elongation = elongation;
        volume.axis.set(axis);
        count++;
    }

    /** Links its program now (GpuBattleView's warm-up), not at the first blast in play. */
    void prepare() {
        if (shader != null) { return; }
        shader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU explosion",
              GpuShaderSource.read("explosion.vert"), GpuEffectBatch.fragment("explosion.frag")), next -> shader = next);
        // The same unit cube bounds every analytic volume. Allocate/upload once, then move it with uniforms.
        proxy = new Mesh(true, 8, 36, VertexAttribute.Position());
        proxy.setVertices(new float[] {
              -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1,
              -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1 });
        proxy.setIndices(new short[] {
              0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
              0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5,
              2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7 });
    }

    void render(Camera camera) {
        ownDepth.begin();
        render(camera, ownDepth);
    }

    void render(Camera camera, GpuEffectDepth snapshot) {
        if (count == 0) { return; }
        prepare();
        Texture depth = snapshot.capture();
        depth.bind(0);
        for (int i = 0; i < count; i++) { volumes[i].depth = volumes[i].center.dot(camera.direction); }
        Arrays.sort(volumes, 0, count, BACK_TO_FRONT);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        // Back faces cover the volume even when the free-flight camera is inside its proxy.
        Gdx.gl.glCullFace(GL20.GL_FRONT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            shader.bind();
            shader.setUniformMatrix("u_projView", camera.combined);
            shader.setUniformMatrix("u_inverseProjView", camera.invProjectionView);
            shader.setUniformf("u_viewport", snapshot.x, snapshot.y, snapshot.width, snapshot.height);
            shader.setUniformf("u_light", light.r, light.g, light.b);
            shader.setUniformf("u_sun", sun);
            shader.setUniformf("u_wind", wind);
            shader.setUniformi("u_depth", 0);
            for (int i = 0; i < count; i++) {
                Volume volume = volumes[i];
                float reach = volume.radius * (1 + volume.stretch) * (1 + Math.max(0, volume.elongation));
                if (!camera.frustum.sphereInFrustum(volume.center, reach)) { continue; }
                shader.setUniformf("u_centerRadius", volume.center.x, volume.center.y, volume.center.z, volume.radius);
                shader.setUniformf("u_stretch", volume.stretch);
                shader.setUniformf("u_effect", volume.age, volume.fire, volume.alpha, volume.seed);
                shader.setUniformf("u_axis", volume.axis.x, volume.axis.y, volume.axis.z, volume.elongation);
                shader.setUniformf("u_look", volume.look.soot(), volume.look.ember(), volume.look.dust(),
                      volume.look.violence());
                proxy.render(shader, GL20.GL_TRIANGLES);
            }
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glCullFace(GL20.GL_BACK);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    @Override
    public void dispose() {
        ownDepth.dispose();
        if (proxy != null) { proxy.dispose(); proxy = null; }
        if (shader != null) { GpuShaderManager.dispose(shader); shader = null; }
        begin();
    }
}
