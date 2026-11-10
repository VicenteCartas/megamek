/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real Scene2D button, the native conditions card and the preview, without changing game conditions. */
@Tag("on-demand")
class GpuPlanetaryConditionsSmokeTest {
    /** Frames the opening card gets before it is answered: its show animation settles the buttons' places. */
    private static final int CARD_FRAMES = 10;

    @Test
    void acceptedConditionsUpdateThePreviewAndCancellationAndClosingAreSafe() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var opened = new AtomicInteger();
        var selected = new PlanetaryConditions();
        selected.setLight(Light.DUSK_DAWN);
        selected.setWeather(Weather.LIGHTNING_STORM);
        selected.setFog(Fog.FOG_LIGHT);
        selected.setWind(Wind.MOD_GALE);
        selected.setWindDirection(WindDirection.NORTHWEST);
        selected.setGravity(0.5f);
        try (var fixture = GpuBoardFixture.create()) {
            var expected = fixture.source.atmosphereFor(selected, false);
            var initial = fixture.source.takeFrame().scenarioAtmosphere();
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int step;
                private GpuAtmosphere.Options defaults;
                private LobbyConditionsView card;
                private LobbyConditionsView answered;
                private long cardFrame;
                private final long deadline = System.nanoTime() + 60_000_000_000L;

                @Override
                public void render() {
                    try {
                        if (failure.get() != null) { throw new AssertionError(failure.get()); }
                        assertTrue(System.nanoTime() < deadline, "The editor must return without blocking rendering");
                        super.render();
                        if (fixture.source.isClosed()) { return; }
                        GpuBoardTuning tuning = GpuBoardTestUi.tuning(this);
                        TextButton conditions = GpuBoardTestUi.tuning(this, "tuning-planetary-conditions");
                        answerCard();
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
                        } else if (step == 2 && opened.get() == 1 && !conditions.isDisabled()) {
                            assertSettings(tuning, initial);
                            assertEquals(0.8f, tuning.atmosphereOptions().rays(), 0.00001f,
                                  "Cancel must preserve custom test controls");
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        } else if (step == 3 && opened.get() == 2 && !conditions.isDisabled()) {
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
                        } else if (step == 4 && opened.get() == 3 && !conditions.isDisabled()) {
                            assertSettings(tuning, expected);
                            assertEquals(defaults, tuning.atmosphereOptions(),
                                  "Applying unchanged conditions must reset overrides again");
                            assertEquals(0.5f, playback.gravityOverride);
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(tuning, "atmosphere-DUSK"));
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        } else if (step == 5 && opened.get() == 4 && !conditions.isDisabled()) {
                            assertSettings(tuning, fixture.source.atmosphereFor(AtmospherePreset.DUSK));
                            GpuBoardTestUi.pressTuning(tuning.defaults());
                            assertSettings(tuning, initial);
                            assertEquals(0, fixture.clicks.get());
                            GpuBoardTestUi.pressTuning(conditions);
                            step++;
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }

                /** Answers each card the Tuning opens: what it shows, then a choice for the request it is. */
                private void answerCard() throws Exception {
                    LobbyNativeDialogs cards = cards();
                    LobbyConditionsView view = cards == null ? null : field(cards, "conditionsView");
                    if (view == null || view == answered) { return; }
                    if (view != card) {
                        card = view;
                        cardFrame = frames();
                        return;
                    }
                    if (frames() < cardFrame + CARD_FRAMES) { return; }
                    answered = view;
                    int request = opened.incrementAndGet();
                    PlanetaryConditionsForm form = field(view, "form");
                    var editorExpected = switch (request) {
                        case 3 -> expected;
                        case 4 -> fixture.source.atmosphereFor(AtmospherePreset.DUSK.conditions(), false);
                        default -> initial;
                    };
                    assertEquals(editorExpected, fixture.source.atmosphereFor(form.result(), false),
                          "Reopening shows the active conditions, including the latest preset or Defaults");
                    if (request == 5) {
                        // Closing the window's source under an open editor: its OK changes nothing and the view ends.
                        SwingUtilities.invokeAndWait(fixture.source::close);
                        GpuBoardTestUi.click("conditions-ok");
                        return;
                    }
                    if (request < 3) {
                        choose(form, selected);
                        assertEquals(expected, fixture.source.atmosphereFor(form.result(), false));
                    }
                    GpuBoardTestUi.click(request == 1 ? "conditions-cancel" : "conditions-ok");
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Planetary conditions preview failed", failure.get()); }
            assertEquals(5, opened.get());
            SwingUtilities.invokeAndWait(() -> assertEquals(initial,
                  fixture.source.atmosphereFor(fixture.game.getPlanetaryConditions(), false),
                  "Previewing conditions never changes the game"));
        }
    }

    /** Enters {@code conditions}'s light, weather, fog, wind and gravity in the card's form. */
    private static void choose(PlanetaryConditionsForm form, PlanetaryConditions conditions) {
        form.light(conditions.getLight());
        form.weather(conditions.getWeather());
        form.fog(conditions.getFog());
        form.wind(conditions.getWind());
        form.windDirection(conditions.getWindDirection());
        form.gravity(Float.toString(conditions.getGravity()));
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
