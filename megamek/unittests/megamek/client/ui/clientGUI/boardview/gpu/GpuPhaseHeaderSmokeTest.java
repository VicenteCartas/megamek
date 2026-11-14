/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.util.PlayerColour;
import megamek.common.enums.GamePhase;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The G1 components: in the component harness, the phase header and the initiative card beside the hud-v3 shots 01,
 * 02, 04, 14 and 15; in GpuHud's own layout of every component, the three G1 panels inside the window and clear of
 * the other panels at 900 x 600, 1280 x 720 and 1920 x 1080; and the conditions card over a real capture, whose close
 * button runs MegaMek's View-menu item.
 */
@Tag("on-demand")
class GpuPhaseHeaderSmokeTest {
    /** The Timber Wolf of the mock roster (GpuHudFixtures), the enemy unit named by the turn of shot 04. */
    private static final int TIMBER_WOLF = 6;
    private static final String SHOT_01 = "01-initiative.jpg";
    private static final List<String> CONDITIONS = List.of("Temperature:  60\u00B0C", "Gravity:  0.5g",
          "Light:  Full Moon Night", "Fog:  Heavy Fog");
    private static final List<String> G1_PANELS = List.of("phase-header", "conditions-card", "initiative-card");

    @Test
    void headerAndInitiativeCardBesideTheMock() {
        GpuHudTestStage.run(hud -> {
            GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
            GpuPhaseHeader header = new GpuPhaseHeader(hud.kit, null, state);
            GpuInitiativeCard card = new GpuInitiativeCard(hud.kit, null, state);
            GpuConditionsCard conditions = new GpuConditionsCard(hud.kit, null, state);
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                List<GpuBattleStatus.Slot> turns = moving.turns();
                GpuBattleStatus.Snapshot initiative = status(GamePhase.INITIATIVE_REPORT, false, Entity.NONE, turns,
                      0, sides(List.of(2, 1), List.of(5, 5)));
                List<GpuBattleStatus.Slot> opponent = new ArrayList<>(turns);
                GpuBattleStatus.Slot second = turns.get(1);
                opponent.set(1, new GpuBattleStatus.Slot(second.playerId(), second.playerName(), second.rgb(), ENEMY,
                      TIMBER_WOLF));

                // Shot 01: round 3, the initiative report with the mock's dice; Princess wins, you move first.
                Pixmap shot = render(hud, state, header, conditions, card, initiative, List.of(), "g1-initiative");
                hud.compare("g1-header-01", shot, header.actor(), SHOT_01, 20, 20);
                hud.compare("g1-initiative-card-01", shot, card.actor(), SHOT_01, 680, 216);
                shot.dispose();
                Group root = (Group) card.actor();
                assertEquals(List.of(List.of("YOUR FORCE", "2", "1", "=", "3", "MOVES FIRST"),
                      List.of("PRINCESS", "5", "5", "=", "10", "WINS")), sideTexts(root));
                // The pager: the second page holds activations 9 and 10, and the first page comes back.
                click(hud.stage, root.findActor("turn-order-next"));
                assertEquals(List.of("9\u201310", "2"), page(root));
                click(hud.stage, root.findActor("turn-order-previous"));
                assertEquals(List.of("1\u20138", "8"), page(root));
                // Double blind: the server reports no turn order, and neither does the card, not even who moves first.
                render(hud, state, header, conditions, card, new GpuBattleStatus.Snapshot(initiative.round(),
                      initiative.phase(), false, initiative.localPlayerId(), Entity.NONE, turns, 0,
                      initiative.units(), initiative.initiative(), true), List.of(), "g1-initiative-double-blind")
                      .dispose();
                assertEquals(List.of(List.of("YOUR FORCE", "2", "1", "=", "3"),
                      List.of("PRINCESS", "5", "5", "=", "10", "WINS")), sideTexts(root));
                assertEquals(0, count(root, "turn-order-slot"));
                // Team initiative with three teams, two sides per row: the best roll wins, and the team of the
                // player who holds the first activation moves first, though another enemy team is listed before it.
                render(hud, state, header, conditions, card, status(GamePhase.INITIATIVE_REPORT, false, Entity.NONE,
                      teamTurns(), 0, teams()), List.of(), "g1-initiative-teams").dispose();
                assertEquals(List.of(List.of("YOUR FORCE", "7", "roll 7 \u00B7 bonus +0"),
                      List.of("TEAM 2", "9", "WINS", "roll 9 \u00B7 bonus +0"),
                      List.of("TEAM 3", "5", "MOVES FIRST", "roll 5 \u00B7 bonus +0")), sideTexts(root));
                // The client's form: the server reports each side's roll and bonus but keeps its dice.
                shot = render(hud, state, header, conditions, card, status(GamePhase.INITIATIVE_REPORT, false,
                      Entity.NONE, turns, 0, sides(List.of(), List.of())), List.of(), "g1-initiative-total");
                hud.compare("g1-initiative-card-total-01", shot, card.actor(), SHOT_01, 680, 216);
                shot.dispose();
                // Shot 02: the local movement turn of the Atlas, first of ten activations.
                shot = render(hud, state, header, conditions, card, moving, CONDITIONS, "g1-movement");
                hud.compare("g1-header-02", shot, header.actor(), "02-movement-fire-preview.jpg", 20, 20);
                shot.dispose();
                // Shot 04: the opponent's turn names its identified unit; the first activation is done.
                shot = render(hud, state, header, conditions, card, status(GamePhase.MOVEMENT, false, Entity.NONE,
                      opponent, 1, List.of()), List.of(), "g1-opponent");
                hud.compare("g1-header-04", shot, header.actor(), "04-opponent-turn.jpg", 20, 20);
                shot.dispose();
                // The playback's speeds at the round line's right outside the local turn, as on the opponent's turn;
                // the history's speed is pressed.
                Group head = (Group) header.actor();
                assertTrue(head.<UiButton>findActor("phase-speed-normal").isChecked(), "1x is the history's speed");
                state.history.speed(UnitMotion.Speed.DOUBLE);
                render(hud, state, header, conditions, card, status(GamePhase.MOVEMENT, false, Entity.NONE,
                      opponent, 1, List.of()), List.of(), "g1-opponent-2x").dispose();
                assertTrue(head.<UiButton>findActor("phase-speed-double").isChecked(), "2x is pressed");
                assertEquals("ROUND 03", head.<Label>findActor("phase-round").getText().toString(),
                      "with room the round reads in full");
                state.history.speed(UnitMotion.Speed.NORMAL);
                // Shot 14: the dense ribbon of a 100 v 100 battle, 48 of its 200 activations from the current first
                // one; then the window that has scrolled on to start eight before activation 21.
                List<GpuBattleStatus.Slot> battle = new ArrayList<>();
                for (int turn = 0; turn < 200; turn++) {
                    battle.add(turns.get(turn % turns.size()));
                }
                shot = render(hud, state, header, conditions, card, status(GamePhase.MOVEMENT, true,
                      GpuHudFixtures.ATLAS, battle, 0, List.of()), List.of(), "g1-large-battle");
                hud.compare("g1-header-14", shot, header.actor(), "14-large-battle-100v100.jpg", 20, 20);
                shot.dispose();
                render(hud, state, header, conditions, card, status(GamePhase.MOVEMENT, true, GpuHudFixtures.ATLAS,
                      battle, 20, List.of()), List.of(), "g1-large-battle-turn-21").dispose();
                // Shot 15 at 1280 x 720: declaring attacks with five own units pending, narrow metrics.
                hud.size(1280, 720);
                shot = render(hud, state, header, conditions, card, status(GamePhase.FIRING, true,
                      GpuHudFixtures.ATLAS, turns, 0, List.of()), List.of(), "g1-firing-1280x720");
                hud.compare("g1-header-15", shot, header.actor(), "15-compact-1280x720.jpg", 16, 16);
                shot.dispose();
                // The round text follows the line's width of the frame before: a second frame settles it. The speeds
                // show outside the local turn only, so the opponent's turn fills the narrow line.
                render(hud, state, header, conditions, card, status(GamePhase.FIRING, false, Entity.NONE,
                      turns, 0, List.of()), List.of(), "g1-firing-1280x720-opponent").dispose();
                render(hud, state, header, conditions, card, status(GamePhase.FIRING, false, Entity.NONE,
                      turns, 0, List.of()), List.of(), "g1-firing-1280x720-opponent").dispose();
                Rectangle frame = GpuHudTestStage.bounds(header.actor());
                Rectangle last = GpuHudTestStage.bounds(((Group) header.actor()).findActor("phase-speed-instant"));
                assertTrue(last.x + last.width <= frame.x + frame.width - 2, "The speeds fit the narrow header");
                Label round = ((Group) header.actor()).findActor("phase-round");
                assertEquals("03", round.getText().toString(), "without room only the round's number");
                assertTrue(round.getPrefWidth() <= round.getWidth() + .5f, "which fits");
            } finally {
                header.dispose();
                card.dispose();
                conditions.dispose();
            }
        });
    }

    /**
     * E rule 6 over GpuHud's own layout of all its components, with the client's form of the initiative card (no
     * dice, a roll line per side): in the initiative report and in movement at 900 x 600, 1280 x 720 and 1920 x 1080,
     * each shown G1 panel lies in the window, overlaps no other shown panel and lets no child run out of it sideways;
     * the initiative card shows in the initiative report only, and from 1280 x 720 it fits without scrolling.
     */
    @Test
    void theThreePanelsStayInTheWindowAndClearOfTheOtherPanelsInTheHudLayout() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuHud hud = new GpuHud(mock(GpuBoardSource.class), harness.theme.skin, batch, new BoardCamera(),
                  mock(GpuBoardTuning.class), new GpuPlaybackHistory(new UnitPlayback()));
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                GpuBattleStatus.Snapshot initiative = status(GamePhase.INITIATIVE_REPORT, false, Entity.NONE,
                      moving.turns(), 0, sides(List.of(), List.of()));
                float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                    harness.size(size[0], size[1]);
                    // One stage unit per back-buffer pixel, drawn into the emulated window at the lower left.
                    hud.resize(Math.round(size[0] / density), Math.round(size[1] / density), 1 / density);
                    for (GpuBattleStatus.Snapshot status : List.of(initiative, moving)) {
                        String name = "g1-layout-" + (status == moving ? "movement-" : "initiative-") + size[0] + "x"
                              + size[1];
                        GpuBoardSource.Frame frame = GpuHudFixtures.frame(status, panels(CONDITIONS));
                        // Two frames: the initiative card flows its sides by the width of the previous layout.
                        hud.update(frame, GpuHud.HudView.EMPTY, null, preferences(true));
                        hud.update(frame, GpuHud.HudView.EMPTY, null, preferences(true));
                        ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                        hud.draw();
                        harness.capture(name).dispose();
                        List<Rectangle> panels = hud.panelBounds();
                        for (String panel : G1_PANELS) {
                            Actor actor = hud.stage.getRoot().findActor(panel);
                            assertEquals(status == initiative || !panel.equals("initiative-card"), shown(actor),
                                  panel + " in " + name);
                            if (shown(actor)) {
                                Rectangle area = GpuHudTestStage.bounds(actor);
                                harness.assertLayout(actor, panels.stream().filter(other -> !other.equals(area))
                                      .toArray(Rectangle[]::new));
                            }
                        }
                        ScrollPane body = hud.stage.getRoot().findActor("initiative-body");
                        assertTrue(status == moving || size[0] == 900 || !body.isScrollY(),
                              "The initiative card scrolls in " + name);
                    }
                }
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    @Test
    void conditionsCardShowsTheCapturedOverlayLinesAndItsCloseButtonRunsTheViewMenuItem() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean overlay = preferences.getShowPlanetaryConditionsOverlay();
        boolean defaults = preferences.getPlanetaryConditionsShowDefaults();
        boolean labels = preferences.getPlanetaryConditionsShowLabels();
        boolean values = preferences.getPlanetaryConditionsShowValues();
        boolean indicators = preferences.getPlanetaryConditionsShowIndicators();
        // Mocks are created on the test thread; the inline mock maker may fail to attach on the EDT.
        ClientGUI gui = mock(ClientGUI.class);
        AtomicReference<CommonMenuBar> menus = new AtomicReference<>();
        AtomicReference<GpuBoardSource.Frame> frame = new AtomicReference<>();
        AtomicReference<GpuBoardSource.Frame> indicatorFrame = new AtomicReference<>();
        AtomicReference<List<String>> texts = new AtomicReference<>();
        AtomicReference<String> strip = new AtomicReference<>();
        AtomicReference<List<String>> indicatorTexts = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setValue(GUIPreferences.SHOW_PLANETARY_CONDITIONS_OVERLAY, true);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_DEFAULTS, false);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_LABELS, true);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_VALUES, true);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_INDICATORS, true);
                PlanetaryConditions conditions = fixture.game.getPlanetaryConditions();
                conditions.setTemperature(60);
                conditions.setGravity(.5f);
                conditions.setLight(Light.FULL_MOON);
                conditions.setFog(Fog.FOG_HEAVY);
                menus.set(CommonMenuBar.getMenuBarForGame());
                menus.get().setPhase(GamePhase.MOVEMENT);
                when(gui.getMenuBar()).thenReturn(menus.get());
                BoardClientState view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                List<BoardScene.Command> commands = new GpuBoardActions(view, () -> fixture.panel, () -> false,
                      () -> { }).globalCommands();
                fixture.source.refresh();
                GpuBoardSource.Frame captured = fixture.source.takeFrame();
                frame.set(new GpuBoardSource.Frame(captured.scene(), captured.timeline(), captured.context(),
                      commands, captured.tooltip(), captured.centerRequest(),
                      captured.boardGeneration(), captured.actorName(), captured.scenarioAtmosphere(),
                      captured.reports(), captured.status(), captured.panels()));
                // The same conditions with the overlay's labels and values off: its indicators alone.
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_LABELS, false);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_VALUES, false);
                fixture.source.refresh();
                indicatorFrame.set(fixture.source.takeFrame());
            });
            GpuHudTestStage.run(hud -> {
                GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
                GpuConditionsCard card = new GpuConditionsCard(hud.kit, null, state);
                // Closed, the card is a strip of the unusual conditions; a click on it opens the card.
                card.update(inputs(hud, frame.get(), true));
                place(hud, card.actor(), 332, 20, card.stripWidth(600));
                hud.draw();
                hud.capture("g1-conditions-strip").dispose();
                strip.set(((Label) ((Group) card.actor()).findActor("conditions-summary")).getText().toString());
                click(hud.stage, ((Group) card.actor()).findActor("conditions-strip"));
                assertTrue(state.conditionsExpanded, "The strip opens the card");
                card.update(inputs(hud, indicatorFrame.get(), true));
                List<String> shown = new ArrayList<>();
                labels(card.actor(), shown);
                indicatorTexts.set(shown);
                card.update(inputs(hud, frame.get(), true));
                place(hud, card.actor(), 332, 20, 260);
                hud.draw();
                hud.capture("g1-conditions").dispose();
                shown = new ArrayList<>();
                labels(card.actor(), shown);
                texts.set(shown);
                click(hud.stage, ((Group) card.actor()).findActor("conditions-close"));
            });
            // The click posted the View-menu item to the EDT; this call returns after it ran.
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals("Full Moon Night \u00B7 Heavy Fog \u00B7 60\u00B0C \u00B7 0.5g", strip.get(),
                  "The strip names only what is unusual");
            // The overlay's lines without their outer spaces and without the indicators the hud fonts have no glyph
            // for: the flame of the extreme heat (outside the Basic Multilingual Plane) and the fog's full blocks.
            assertEquals(List.of("CONDITIONS", "Temperature:  60\u00B0C", "Gravity:  0.5g   \u2B71",
                  "Light:  Full Moon Night  \u26AB", "Fog:  Heavy Fog"), texts.get());
            // With the indicators alone, the fog's line has nothing left to draw and is left out.
            assertEquals(List.of("CONDITIONS", "60\u00B0C", "0.5g  \u2B71", "\u26AB"), indicatorTexts.get());
            assertFalse(preferences.getShowPlanetaryConditionsOverlay(),
                  "The close button toggles the overlay preference through the View menu");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (menus.get() != null) {
                    menus.get().die();
                }
                preferences.setValue(GUIPreferences.SHOW_PLANETARY_CONDITIONS_OVERLAY, overlay);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_DEFAULTS, defaults);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_LABELS, labels);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_VALUES, values);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_INDICATORS, indicators);
            });
        }
    }

    /** The mock's two sides of shot 01 (2 + 1 = 3 against 5 + 5 = 10), with the given kept dice. */
    private static List<GpuBattleStatus.InitiativeSide> sides(List<Integer> own, List<Integer> enemy) {
        return List.of(new GpuBattleStatus.InitiativeSide("Player", PlayerColour.BLUE.getColour().getRGB(), OWN, 3,
                    3, 0, own, List.of(), List.of(GpuHudFixtures.LOCAL_PLAYER)),
              new GpuBattleStatus.InitiativeSide("Princess", PlayerColour.RED.getColour().getRGB(), ENEMY, 10, 10, 0,
                    enemy, List.of(), List.of(GpuHudFixtures.ENEMY_PLAYER)));
    }

    /** Three teams as a client captures them: the local player's team 1, then team 2 and team 3 (players 3 and 4). */
    private static List<GpuBattleStatus.InitiativeSide> teams() {
        return List.of(new GpuBattleStatus.InitiativeSide("Team 1", 0, OWN, 7, 7, 0, List.of(), List.of(),
                    List.of(GpuHudFixtures.LOCAL_PLAYER)),
              new GpuBattleStatus.InitiativeSide("Team 2", 0, ENEMY, 9, 9, 0, List.of(), List.of(),
                    List.of(GpuHudFixtures.ENEMY_PLAYER)),
              new GpuBattleStatus.InitiativeSide("Team 3", 0, ENEMY, 5, 5, 0, List.of(), List.of(), List.of(3, 4)));
    }

    /** The teams' turns: player 4 of team 3 first, then the local player, team 2 and player 3. */
    private static List<GpuBattleStatus.Slot> teamTurns() {
        int red = PlayerColour.RED.getColour().getRGB();
        GpuBattleStatus.Slot own = new GpuBattleStatus.Slot(GpuHudFixtures.LOCAL_PLAYER, "Player",
              PlayerColour.BLUE.getColour().getRGB(), OWN, Entity.NONE);
        return List.of(new GpuBattleStatus.Slot(4, "Kerensky", red, ENEMY, Entity.NONE), own,
              new GpuBattleStatus.Slot(GpuHudFixtures.ENEMY_PLAYER, "Princess", red, ENEMY, Entity.NONE),
              new GpuBattleStatus.Slot(3, "Ward", red, ENEMY, Entity.NONE), own);
    }

    /** The mock roster's round 3 in another phase, turn and initiative. */
    private static GpuBattleStatus.Snapshot status(GamePhase phase, boolean myTurn, int actor,
          List<GpuBattleStatus.Slot> turns, int turnIndex, List<GpuBattleStatus.InitiativeSide> sides) {
        GpuBattleStatus.Snapshot mock = GpuHudFixtures.status();
        return new GpuBattleStatus.Snapshot(mock.round(), phase, myTurn, mock.localPlayerId(), actor, turns,
              turnIndex, mock.units(), sides, false);
    }

    private static GpuBoardSource.UiPreferences preferences(boolean conditions) {
        return GpuHudFixtures.with(GpuHudFixtures.preferences(),
              Map.of("conditionsVisible", conditions, "binds", List.of()));
    }

    /** The panels of a frame whose phase display reports the given planetary condition lines. */
    private static GpuHudData panels(List<String> lines) {
        return new GpuHudData(new GpuBoardActions.PhaseInfo("", false, "", "", "", List.of(), lines),
              GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY,
              GpuUnitRecord.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY,
              GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
    }

    private static GpuHud.Inputs inputs(GpuHudTestStage hud, GpuBoardSource.Frame frame, boolean conditions) {
        return new GpuHud.Inputs(frame, GpuHud.HudView.EMPTY, null, preferences(conditions),
              GpuHud.Metrics.of(hud.width(), hud.height()), List.of());
    }

    /**
     * Shows one status (and condition lines, which also switch the conditions card on) in the harness: each
     * component updates, then goes to its slot's anchor (plan C.1): the header at the top of the left column, the
     * conditions card right of it, the initiative card centred at 20 % of the height. Twice, so that the components
     * can use the width of the previous layout, as they do from one frame to the next. Draws and captures {name}.png.
     */
    private static Pixmap render(GpuHudTestStage hud, GpuHudState state, GpuPhaseHeader header,
          GpuConditionsCard conditions, GpuInitiativeCard card, GpuBattleStatus.Snapshot status, List<String> lines,
          String name) {
        state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
        GpuHud.Inputs inputs = inputs(hud, GpuHudFixtures.frame(status, panels(lines)), !lines.isEmpty());
        GpuHud.Metrics m = inputs.metrics();
        for (int pass = 0; pass < 2; pass++) {
            header.update(inputs);
            conditions.update(inputs);
            card.update(inputs);
            hud.window.clearChildren();
            place(hud, header.actor(), m.gap(), m.gap(), m.left());
            place(hud, conditions.actor(), m.gap() + m.left() + 12, m.gap(), 260);
            place(hud, card.actor(), (m.width() - 560) / 2, m.height() * .2f, 560);
        }
        hud.draw();
        return hud.capture(name);
    }

    /**
     * Puts the actor in a top-left, fill-width slot, as GpuHud does, at ({@code x}, {@code top}) of the emulated
     * window (y down, the prototype's CSS), as tall as its content.
     */
    private static void place(GpuHudTestStage hud, Actor actor, float x, float top, float width) {
        Container<Actor> slot = new Container<>(actor).top().left().fillX();
        hud.window.addActor(slot);
        // A wrapped label knows its height once it has its width: lay out until the height settles.
        for (int pass = 0; pass < 3; pass++) {
            float height = actor.isVisible() ? slot.getPrefHeight() : 0;
            slot.setBounds(x, hud.height() - top - height, width, height);
            slot.validate();
        }
    }

    /** True when the actor and every group above it are visible. */
    private static boolean shown(Actor actor) {
        for (Actor node = actor; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** The initiative card's page range and the number of activations it shows. */
    private static List<String> page(Group card) {
        return List.of(((Label) card.findActor("turn-order-range")).getText().toString(),
              String.valueOf(count(card, "turn-order-slot")));
    }

    private static int count(Actor actor, String name) {
        int count = name.equals(actor.getName()) ? 1 : 0;
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                count += count(child, name);
            }
        }
        return count;
    }

    /** The label texts of each side box of the initiative card, in order. */
    private static List<List<String>> sideTexts(Actor card) {
        List<List<String>> sides = new ArrayList<>();
        sideTexts(card, sides);
        return sides;
    }

    private static void sideTexts(Actor actor, List<List<String>> sides) {
        if ("initiative-side".equals(actor.getName())) {
            List<String> texts = new ArrayList<>();
            labels(actor, texts);
            sides.add(texts);
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> sideTexts(child, sides));
        }
    }

    /** The texts of every label under the actor, in drawing order. */
    private static void labels(Actor actor, List<String> texts) {
        if (actor instanceof Label label) {
            texts.add(label.getText().toString());
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> labels(child, texts));
        }
    }

    private static void click(Stage stage, Actor actor) {
        Vector2 point = stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
}
