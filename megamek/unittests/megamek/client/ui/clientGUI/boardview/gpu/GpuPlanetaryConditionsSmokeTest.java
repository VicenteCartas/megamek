/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real Scene2D button, native conditions card and return to the GL thread, without changing game conditions. */
@Tag("on-demand")
class GpuPlanetaryConditionsSmokeTest {
    @Test
    void acceptedConditionsUpdateThePreviewAndCancellationIsSafe() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var opened = new AtomicInteger();
        try (var fixture = GpuBoardFixture.create()) {
            var initial = fixture.source.takeFrame().scenarioAtmosphere();
            var dusk = fixture.source.atmosphereFor(AtmospherePreset.DUSK.conditions(), false);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int step;
                private GpuAtmosphere.Options defaults;
                private BoardAtmosphere.Settings expected;
                private final long deadline = System.nanoTime() + 60_000_000_000L;

                /** The open conditions card, counted once, showing the {@code shown} conditions; null until it opens. */
                private LobbyConditionsView card(BoardAtmosphere.Settings shown) {
                    LobbyConditionsView view = cards().conditionsView();
                    if (view != null) {
                        opened.incrementAndGet();
                        assertEquals(shown, fixture.source.atmosphereFor(view.form().result(), false),
                              "Reopening shows the active conditions, including the latest preset or Defaults");
                    }
                    return view;
                }

                @Override
                public void render() {
                    try {
                        if (failure.get() != null) { throw new AssertionError(failure.get()); }
                        assertTrue(System.nanoTime() < deadline, "The card must answer without blocking rendering");
                        super.render();
                        GpuBoardTuning tuning = GpuBoardTestUi.tuning(this);
                        TextButton conditions = GpuBoardTestUi.tuning(this, "tuning-planetary-conditions");
                        var playbackField = GpuBattleView.class.getDeclaredField("playback");
                        playbackField.setAccessible(true);
                        var playback = (UnitPlayback) playbackField.get(this);
                        if (step == 0 && frames() >= 3) {
                            // The HUD's tuning panel shows the model's Atmosphere page in the capture below.
                            GpuBoardTestUi.click("tuning-button");
                            defaults = tuning.atmosphereOptions();
                            changeEffectControls(tuning);
                            step++;
                        } else if (step == 1) {
                            GpuBoardTestUi.click("tuning-atmosphere-tab");
                            GpuBoardTestUi.pressTuning(conditions);
                            assertTrue(conditions.isDisabled(), "One editor can be open at a time");
                            step++;
                        } else if (step == 2 && card(initial) != null) {
                            select(cards().conditionsView().form());
                            GpuBoardTestUi.click("conditions-cancel");
                            step++;
                        } else if (step == 3 && !conditions.isDisabled()) {
                            assertSettings(tuning, initial);
                            assertEquals(0.8f, tuning.atmosphereOptions().rays(), 0.00001f,
                                  "Cancel must preserve custom test controls");
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        } else if (step == 4 && card(initial) != null) {
                            PlanetaryConditionsForm form = cards().conditionsView().form();
                            select(form);
                            expected = fixture.source.atmosphereFor(form.result(), false);
                            GpuBoardTestUi.click("conditions-ok");
                            step++;
                        } else if (step == 5 && !conditions.isDisabled()) {
                            assertSettings(tuning, expected);
                            assertEquals(defaults, tuning.atmosphereOptions(), "Apply restores all effect constants");
                            assertEquals(0.5f, playback.gravityOverride,
                                  "The live renderer must pass preview gravity to jumps");
                            // The panel's button for the model's: its text fits.
                            TextButton shown = GpuBoardTestUi.stage().getRoot()
                                  .findActor("tuning-planetary-conditions");
                            assertTrue(shown.getWidth() >= shown.getLabel().getPrefWidth(), "Button text fits");
                            GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots"),
                                  "planetary-conditions-tuning.png"));
                            SwingUtilities.invokeAndWait(fixture.source::refresh);
                            changeEffectControls(tuning);
                            slider(tuning, "Ground fog").setValue(0.75f);
                            slider(tuning, "Time of day").setValue(12);
                            slider(tuning, "Gravity (g)").setValue(2);
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        } else if (step == 6 && card(expected) != null) {
                            GpuBoardTestUi.click("conditions-ok");
                            step++;
                        } else if (step == 7 && !conditions.isDisabled()) {
                            assertSettings(tuning, expected);
                            assertEquals(defaults, tuning.atmosphereOptions(),
                                  "Applying unchanged conditions must reset overrides again");
                            assertEquals(0.5f, playback.gravityOverride);
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(tuning, "atmosphere-DUSK"));
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        } else if (step == 8 && card(dusk) != null) {
                            GpuBoardTestUi.click("conditions-ok");
                            step++;
                        } else if (step == 9 && !conditions.isDisabled()) {
                            assertSettings(tuning, fixture.source.atmosphereFor(AtmospherePreset.DUSK));
                            GpuBoardTestUi.pressTuning(tuning.defaults());
                            assertSettings(tuning, initial);
                            assertEquals(0, fixture.clicks.get());
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        } else if (step == 10 && card(initial) != null) {
                            GpuBoardTestUi.click("conditions-cancel");
                            step++;
                        } else if (step == 11 && !conditions.isDisabled()) {
                            assertSettings(tuning, initial);
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Planetary conditions preview failed", failure.get()); }
            assertEquals(5, opened.get());
            SwingUtilities.invokeAndWait(() -> assertEquals(initial,
                  fixture.source.atmosphereFor(fixture.game.getPlanetaryConditions(), false),
                  "Previewing conditions never changes the game"));
        }
    }

    /** Picks a dusk lightning storm with fog, a moderate north-west gale and half gravity in the card's form. */
    private static void select(PlanetaryConditionsForm form) {
        assertNotNull(form);
        form.light(Light.DUSK_DAWN);
        form.weather(Weather.LIGHTNING_STORM);
        form.wind(Wind.MOD_GALE);
        form.windDirection(WindDirection.NORTHWEST);
        form.fog(Fog.FOG_LIGHT);
        form.gravity("0.5");
        assertTrue(form.valid(), "The picked conditions are valid");
    }

    private static void changeEffectControls(GpuBoardTuning tuning) {
        String[] names = { "God rays", "Cloud shadow min", "Cloud shadow max", "Sun glare", "Fog height variation",
              "Fog density variation", "Moon shadow contrast", "Taint strength", "Fog calm drift" };
        float[] values = { 0.8f, 0.3f, 0.9f, 0.2f, 0.5f, 0.4f, 0.3f, 1.5f, 0.12f };
        for (int index = 0; index < names.length; index++) { slider(tuning, names[index]).setValue(values[index]); }
        GpuBoardTestUi.<CheckBox>tuning(tuning, "tuning-fixed-sun").setChecked(true);
    }

    private static Slider slider(GpuBoardTuning tuning, String name) {
        return GpuBoardTestUi.tuning(tuning, name);
    }

    private static void assertSettings(GpuBoardTuning tuning, BoardAtmosphere.Settings settings) {
        String[] names = { "Time of day", "Cloud cover", "Ground fog", "Ground layer height", "Haze", "Exposure (EV)",
              "Rain", "Snow", "Hail", "Blowing sand", "Lightning", "Wind strength", "Wind direction", "Gravity (g)" };
        var effects = settings.effects();
        float[] values = { settings.hour(), settings.clouds(), settings.fog(), settings.groundLayerHeight(), settings.haze(),
              settings.exposure(), effects.rain(), effects.snow(), effects.hail(), effects.sand(), effects.lightning(),
              effects.wind(), effects.windDirection(), settings.gravity() };
        for (int index = 0; index < names.length; index++) {
            assertEquals(values[index], slider(tuning, names[index]).getValue(), .001f, names[index]);
        }
    }
}
