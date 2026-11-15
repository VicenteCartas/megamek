/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.Locale;

import megamek.client.ui.clientGUI.GUIPreferences;

/**
 * One set of {@link BoardSwitch} values: a view's, a preset's or a saved Custom set. Immutable: a view's terrain holds the
 * set its tuning hands it and passes it on to what it owns; a build reads the set its request captured (TerrainSettings).
 * The preset is derived from the values, never kept beside them.
 */
final class BoardSwitches {
    /** The Display page's Quality choices: Low, Medium and High are sets of switch values; Custom is any other set. */
    enum Preset {
        LOW("Low"), MEDIUM("Medium"), HIGH("High"), CUSTOM("Custom");

        private final String label;

        Preset(String label) { this.label = label; }

        /** The preset a saved preference names; an unknown or missing one is Medium, the game's default. */
        static Preset parse(String saved) {
            for (Preset preset : values()) {
                if (preset.name().equalsIgnoreCase(saved)) { return preset; }
            }
            return MEDIUM;
        }

        /** As the Quality choice lists it. */
        @Override
        public String toString() { return label; }
    }

    /**
     * {@code -Dmegamek.gpu.switches=MEDIUM} or {@code HIGH;AMBIENT_OCCLUSION=1} or {@code MEDIUM;DETAIL_SCALE=1}: the set
     * of every view and of a terrain given none, in place of the saved choice, for tests and harnesses; never saved.
     */
    static final String PROPERTY = "megamek.gpu.switches";
    private static final BoardSwitch[] SWITCHES = BoardSwitch.values();
    /**
     * The program key's bits for keyed switches: the key rides in the top of an int above the lighting's own feature bits
     * (GpuEnvironmentLighting.flags), which keeps the bottom 8.
     */
    static final int KEY_BITS = 24;
    /** Each keyed switch's bit in {@link #programKey}, in registry order; -1 for the others. */
    private static final int[] KEY_BIT = keyBits();
    /** Read only: the set of a terrain given none (High, as before a view applies its own), or the test override. */
    static final BoardSwitches BASE = override() != null ? override() : of(Preset.HIGH);

    private final float[] values;

    private BoardSwitches(float[] values) { this.values = values; }

    static BoardSwitches of(Preset preset) {
        if (preset == Preset.CUSTOM) { throw new IllegalArgumentException("Custom is no preset's set"); }
        float[] values = new float[SWITCHES.length];
        for (BoardSwitch s : SWITCHES) { values[s.ordinal()] = snap(s, s.presets[preset.ordinal()]); }
        return new BoardSwitches(values);
    }

    /** A value within the switch's range on its steps, as its slider holds it, so equal choices compare equal. */
    private static float snap(BoardSwitch s, float value) {
        double steps = Math.round((Math.clamp(value, s.min, s.max) - s.min) / (double) s.step);
        return (float) (s.min + steps * s.step);
    }

    float get(BoardSwitch s) { return values[s.ordinal()]; }

    boolean on(BoardSwitch s) { return get(s) > 0; }

    /** These values with {@code s} at {@code value}, kept within its range and on its steps. */
    BoardSwitches with(BoardSwitch s, float value) {
        float[] next = values.clone();
        next[s.ordinal()] = snap(s, value);
        return new BoardSwitches(next);
    }

    BoardSwitches with(BoardSwitch s, boolean on) { return with(s, on ? 1 : 0); }

    /** The preset whose values these are, or Custom. */
    Preset preset() {
        for (Preset preset : Preset.values()) {
            if (preset != Preset.CUSTOM && equals(of(preset))) { return preset; }
        }
        return Preset.CUSTOM;
    }

    /** One bit per switch that selects programs (BoardSwitch#keyed) and is on: sets with the same key share programs. */
    int programKey() {
        int key = 0;
        for (BoardSwitch s : SWITCHES) {
            if (s.keyed() && on(s)) { key |= 1 << KEY_BIT[s.ordinal()]; }
        }
        return key;
    }

    /** A keyed switch's bit in {@link #programKey}. */
    static int keyBit(BoardSwitch s) { return KEY_BIT[s.ordinal()]; }

    /** The keyed switches' bits, one after another; more than {@link #KEY_BITS} of them fails here, at class load. */
    private static int[] keyBits() {
        int[] bits = new int[SWITCHES.length];
        int next = 0;
        for (BoardSwitch s : SWITCHES) { bits[s.ordinal()] = s.keyed() ? next++ : -1; }
        if (next > KEY_BITS) {
            throw new IllegalStateException(next + " keyed switches: the program key holds at most " + KEY_BITS
                  + " (GpuEnvironmentLighting.flags packs them into an int above its own feature bits)");
        }
        return bits;
    }

    /** "NAME=value;..." for every switch, in order: what a Custom set and the shader warm-up's settings store. */
    String format() {
        StringBuilder text = new StringBuilder();
        for (BoardSwitch s : SWITCHES) {
            if (!text.isEmpty()) { text.append(';'); }
            float value = get(s);
            text.append(s.name()).append('=')
                  .append(value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value));
        }
        return text.toString();
    }

    /**
     * A set written by {@link #format}, or a preset's name followed by changes ("HIGH;AMBIENT_OCCLUSION=1"). Switches it
     * does not name take Medium's values (or the named preset's); names it does not know are ignored.
     */
    static BoardSwitches parse(String text) {
        BoardSwitches set = of(Preset.MEDIUM);
        for (String part : text.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) { continue; }
            int equals = entry.indexOf('=');
            if (equals < 0) {
                Preset preset = Preset.parse(entry);
                if (preset != Preset.CUSTOM && preset.name().equalsIgnoreCase(entry)) { set = of(preset); }
                continue;
            }
            String name = entry.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            for (BoardSwitch s : SWITCHES) {
                if (s.name().equals(name)) {
                    try { set = set.with(s, Float.parseFloat(entry.substring(equals + 1).trim())); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        return set;
    }

    /** The test override ({@link #PROPERTY}), or null. */
    private static BoardSwitches override() {
        String text = System.getProperty(PROPERTY);
        return text == null || text.isBlank() ? null : parse(text);
    }

    /** Whether the test override replaces the saved choice: the Display page then saves nothing. */
    static boolean overridden() { return override() != null; }

    /**
     * The saved choice: a preset's current values, or the saved Custom set. Saved before the switches existed (no Custom
     * set stored), High took the player's five options, so it still does; Low and Medium did not, and keep theirs.
     */
    static BoardSwitches saved(GUIPreferences preferences) {
        if (overridden()) { return BASE; }
        Preset preset = Preset.parse(preferences.getBoardQuality());
        String custom = preferences.getBoardQualitySwitches();
        if (preset == Preset.CUSTOM) { return custom.isBlank() ? of(Preset.MEDIUM) : parse(custom); }
        if (preset == Preset.HIGH && custom.isBlank()) { return legacyHigh(preferences); }
        return of(preset);
    }

    /** The set picking Custom restores, or null: the saved one, or before the switches existed, High as the player had it. */
    static BoardSwitches savedCustom(GUIPreferences preferences) {
        if (overridden()) { return null; }
        String custom = preferences.getBoardQualitySwitches();
        if (!custom.isBlank()) { return parse(custom); }
        BoardSwitches legacy = legacyHigh(preferences);
        return legacy.preset() == Preset.CUSTOM ? legacy : null;
    }

    /** High with the five Display options of before the switches, as their keys hold them (or their defaults). */
    private static BoardSwitches legacyHigh(GUIPreferences preferences) {
        return of(Preset.HIGH)
              .with(BoardSwitch.HIGH_SHADOWS, "HIGH".equalsIgnoreCase(preferences.getString(GUIPreferences.BOARD_SHADOWS)))
              .with(BoardSwitch.SPECULAR_ANTI_ALIASING,
                    preferences.getBoolean(GUIPreferences.BOARD_SPECULAR_ANTI_ALIASING))
              .with(BoardSwitch.TERRAIN_PARALLAX, preferences.getBoolean(GUIPreferences.BOARD_TERRAIN_PARALLAX))
              .with(BoardSwitch.TESSELLATION, preferences.getBoolean(GUIPreferences.BOARD_TESSELLATION))
              .with(BoardSwitch.AMBIENT_OCCLUSION, preferences.getBoolean(GUIPreferences.BOARD_AMBIENT_OCCLUSION));
    }

    /**
     * Saves {@code shown} as the choice: its preset's name, and the Custom set {@code custom} picking Custom restores (or
     * {@code shown} itself, when there is none). Both keys are written, so the old options are read only before the first
     * save. Swing thread, as every preference.
     */
    static void save(GUIPreferences preferences, BoardSwitches shown, BoardSwitches custom) {
        if (overridden()) { return; }
        preferences.setBoardQuality(shown.preset().name());
        preferences.setBoardQualitySwitches((custom != null ? custom : shown).format());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoardSwitches set && Arrays.equals(values, set.values);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(values); }

    @Override
    public String toString() { return format(); }
}
