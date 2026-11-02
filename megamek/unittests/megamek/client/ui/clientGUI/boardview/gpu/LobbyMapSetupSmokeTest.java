/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.MapSetupForm.Entry;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RandomMapForm.Feature;
import megamek.client.lobby.RandomMapForm.Level;
import megamek.client.lobby.RandomMapForm.Param;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.BoardDimensions;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MapSetup;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The map setup's war table and the random map settings clicked through the stage. */
@Tag("on-demand")
class LobbyMapSetupSmokeTest {
    private static final String LAKE = "16x17/Lake Shore.board2";

    private final List<String> calls = new ArrayList<>();
    private MapSettings server;

    @Test
    void aBoardPickedFromTheBrowserGoesOnThePickedSlotAndTheMapGrows() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            Table body = fixture.body;
            click(ui, body.findActor("map-slot-1"));
            assertEquals(List.of(1), fixture.view.pickedSlots(), "a click on a tile picks its slot");

            List<Entry> boards = fixture.view.shown().boards();
            int lake = boards.indexOf(boards.stream().filter(entry -> entry.name().equals(LAKE)).findFirst()
                  .orElseThrow());
            click(ui, body.findActor("map-board-" + lake));
            assertEquals(List.of("preview " + LAKE), calls, "a click on a board previews it");
            click(ui, body.findActor("map-place"));
            assertEquals(LAKE, fixture.form.settings().getBoardsSelectedVector().get(1), "Place puts the board on the slot");
            assertEquals(LAKE, fixture.view.shown().slots().get(1).board());

            click(ui, body.findActor("map-width-up"));
            assertEquals(3, fixture.form.settings().getMapWidth(), "the stepper grows the map");
            assertEquals(3, fixture.view.shown().slots().size());
            assertNotNull(body.findActor("map-slot-2"), "a new tile shows");
            click(ui, body.findActor("map-height-up"));
            assertEquals(6, fixture.view.shown().slots().size());
            int listeners = body.findActor("map-generate").getListeners().size;
            for (int refresh = 0; refresh < 20; refresh++) {
                fixture.view.show(fixture.form.picture(), fixture.form.themes(-1));
            }
            assertEquals(listeners, body.findActor("map-generate").getListeners().size,
                  "refreshing the setup reuses Generate's tooltip rather than stacking new ones");

            click(ui, body.findActor("map-load"));
            click(ui, body.findActor("map-random-settings"));
            assertTrue(calls.containsAll(List.of("run LOAD", "run RANDOM_MAP")), calls.toString());

            click(ui, body.findActor("map-medium-space"));
            assertEquals(MapSettings.MEDIUM_SPACE, fixture.form.settings().getMedium(), "the segmented control sets the map type");
            assertFalse(body.findActor("map-drawer-scroll").isVisible(), "space has no boards to pick");

            body.remove();
        });
    }

    @Test
    void severalPickedBoardsMakeASurpriseBoard() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            Table body = fixture.body;
            click(ui, body.findActor("map-slot-0"));
            List<Entry> boards = fixture.view.shown().boards();
            fixture.view.pick(boards.get(1), false);
            fixture.view.pick(boards.get(2), true);
            fixture.body.validate();
            click(ui, body.findActor("map-surprise"));
            String surprise = fixture.form.settings().getBoardsSelectedVector().get(0);
            assertTrue(surprise.startsWith(MapSettings.BOARD_SURPRISE), surprise);
            assertEquals(MapSetupForm.Kind.SURPRISE, fixture.view.shown().slots().get(0).kind());
            body.remove();
        });
    }

    @Test
    void theRandomMapSettingsSendTheLevelsAndFieldsClicked() {
        UiTestStage.run(ui -> {
            server();
            List<MapSettings> sent = new ArrayList<>();
            RandomMapForm form = new RandomMapForm(server, sent::add);
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyRandomMapView view = dialogs.randomMap(form, List.of("", "snow"), new RandomHost());
            ui.draw();
            click(ui, view.control("random-tab-water"));
            click(ui, view.control("random-lakes-high"));
            assertEquals(Level.HIGH, form.level(Feature.LAKES), "the level bar sets the feature");

            click(ui, view.control("random-advanced"));
            int lakes = form.value(Param.LAKES_MAX);
            click(ui, view.control("random-lakes-max-up"));
            assertEquals(lakes + 1, form.value(Param.LAKES_MAX), "the advanced stepper sets the field");

            click(ui, view.control("random-ok"));
            assertEquals(1, sent.size(), "OK sends the settings");
            assertEquals(lakes + 1, sent.get(0).getMaxWaterSpots());
            assertFalse(dialogs.isOpen(), "OK closes the dialog");
        });
    }

    @Test
    void theOddBoardWarningRemembersDontShowAgain() {
        UiTestStage.run(ui -> {
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            boolean[] silenced = new boolean[1];
            UiDialog nag = dialogs.oddBoardNag(() -> silenced[0] = true);
            ui.draw();
            click(ui, nag.card().findActor("nag-dont-show"));
            click(ui, nag.card().findActor("nag-ok"));
            assertTrue(silenced[0], "the ticked box turns the warning off");
            assertFalse(dialogs.isOpen());
        });
    }

    private void server() {
        server = MapSettings.getInstance();
        server.setBoardSize(16, 17);
        server.setMapSize(2, 1);
        server.setBoardsSelectedVector(List.of("16x17/Desert 1.board2", MapSettings.BOARD_GENERATED));
        server.setBoardsAvailableVector(List.of("16x17/Desert 1.board2", "16x17/City Hills.board2", LAKE));
    }

    private final class Fixture implements LobbyMapSetupView.Host {
        final MapSetupForm form;
        final LobbyMapSetupView view;
        final Table body;

        Fixture(UiTestStage ui) {
            server();
            form = new MapSetupForm(() -> server, () -> Set.of(new BoardDimensions(16, 17)), new Commands(),
                  board -> "", () -> List.of("", "snow"));
            view = new LobbyMapSetupView(ui.kit, this);
            body = view.body();
            ui.window.addActor(body);
            body.setBounds(20, 20, 1500, 1000);
            show();
            ui.draw();
        }

        private void show() {
            view.show(form.picture(), form.themes(-1));
            body.validate();
        }

        @Override
        public void act(Consumer<MapSetupForm> action) {
            action.accept(form);
            show();
        }

        @Override
        public void mapWidth(int width) {
            form.mapWidth(width);
            show();
        }

        @Override
        public void boardWidth(int width) {
            form.boardWidth(width);
            show();
        }

        @Override
        public void preview(String board) {
            calls.add("preview " + board);
        }

        @Override
        public void run(LobbyMapSetupView.Action action) {
            calls.add("run " + action);
        }
    }

    /** Does to the settings what the lobby's commands do, without a client. */
    private final class Commands implements MapSetupForm.Commands {
        @Override
        public void medium(MapSettings settings, int medium) {
            settings.setMedium(medium);
        }

        @Override
        public void resizeMap(MapSettings settings, int width, int height) {
            settings.setMapSize(width, height);
        }

        @Override
        public void resizeBoards(MapSettings settings, int width, int height) {
            settings.setBoardSize(width, height);
        }

        @Override
        public void changeBoard(MapSettings settings, int slot, String board) {
            settings.getBoardsSelectedVector().set(slot,
                  board.contains("\n") ? MapSettings.BOARD_SURPRISE + board : board);
        }

        @Override
        public void selectSingleBoard(MapSettings settings, String board, BoardDimensions size) {
        }

        @Override
        public void theme(MapSettings settings, int slot, String theme) {
            settings.setBoardTheme(slot, theme);
        }

        @Override
        public void levelOffset(MapSettings settings, int slot, int offset) {
            settings.setBoardLevelOffset(slot, offset);
        }

        @Override
        public void applySetup(MapSettings settings, MapSetup setup) {
        }

        @Override
        public void generate() {
        }
    }

    private static final class RandomHost implements LobbyRandomMapView.Host {
        @Override
        public void advanced(boolean advanced) {
        }

        @Override
        public void load(RandomMapForm form, Runnable then) {
            then.run();
        }

        @Override
        public void save(RandomMapForm form, Consumer<Boolean> then) {
            then.accept(false);
        }

        @Override
        public void edt(Runnable work, Runnable then) {
            work.run();
            then.run();
        }
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor, "the control is in the view");
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
