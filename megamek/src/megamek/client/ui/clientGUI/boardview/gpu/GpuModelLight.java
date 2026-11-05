/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.DoubleSupplier;

import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** Immutable controls for a model material's authored emission; copied LODs and instance batches share the clock. */
final class GpuModelLight extends Attribute {
    static final long TYPE = register("boardModelLight");
    private final float intensity;
    private final float blinkSeconds;
    private final float offDiffuse;
    private final boolean nightOnly;

    /**
     * Nonnegative emission strength (zero is off) and blink period in seconds (zero is steady).
     * Blinking spends half the period on. Off diffuse is the fraction of albedo retained while off: use 1 to leave
     * the surface unchanged, or a smaller value for a dark lens. Emission colour and masks come from the material.
     */
    GpuModelLight(float intensity, float blinkSeconds, float offDiffuse) {
        this(intensity, blinkSeconds, offDiffuse, false);
    }

    /** Night windows use the atmosphere's daylight share; their unlit glass retains its albedo. */
    GpuModelLight(float intensity, float blinkSeconds, float offDiffuse, boolean nightOnly) {
        super(TYPE);
        this.intensity = intensity;
        this.blinkSeconds = blinkSeconds;
        this.offDiffuse = offDiffuse;
        this.nightOnly = nightOnly;
    }

    static void register(DefaultShader shader, DoubleSupplier clock) {
        shader.register("u_modelLight", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var light = attributes.get(GpuModelLight.class, TYPE);
                if (light == null) {
                    // Also the GL default in model renderers that do not register this control.
                    target.set(id, 0f, 0f, 0f);
                } else {
                    boolean on = light.intensity > 0 && (light.blinkSeconds == 0
                          || clock.getAsDouble() % light.blinkSeconds < light.blinkSeconds * .5);
                    var environment = GpuEnvironmentLighting.of(attributes);
                    float daylight = light.nightOnly && environment != null ? environment.daylight() : 0;
                    target.set(id, 1f, on ? light.intensity * (1 - daylight) : 0f, on ? 1f : light.offDiffuse);
                }
            }
        });
    }

    @Override
    public Attribute copy() { return new GpuModelLight(intensity, blinkSeconds, offDiffuse, nightOnly); }

    @Override
    protected boolean equals(Attribute other) { return compareTo(other) == 0; }

    @Override
    public int hashCode() {
        int result = 31 * super.hashCode() + Float.floatToIntBits(intensity);
        result = 31 * result + Float.floatToIntBits(blinkSeconds);
        result = 31 * result + Float.floatToIntBits(offDiffuse);
        return 31 * result + Boolean.hashCode(nightOnly);
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var light = (GpuModelLight) other;
        int order = Float.compare(intensity, light.intensity);
        if (order == 0) { order = Float.compare(blinkSeconds, light.blinkSeconds); }
        if (order == 0) { order = Float.compare(offDiffuse, light.offDiffuse); }
        if (order == 0) { order = Boolean.compare(nightOnly, light.nightOnly); }
        return order;
    }
}
