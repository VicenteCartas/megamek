/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.MapSetupForm.Entry;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RandomMapForm.Feature;
import megamek.client.lobby.RandomMapForm.Level;
import megamek.client.lobby.RandomMapForm.Param;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiSelect;
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
    void unchangedRefreshesKeepDropdownsOpenAndChoicesStillReachTheForm() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            click(ui, fixture.body.findActor("map-slot-1"));
            UiSelect theme = fixture.body.findActor("map-theme-1");
            int snow = fixture.form.themes(-1).stream().map(MapSetupForm.Theme::theme).toList().indexOf("snow");
            click(ui, theme);
            assertTrue(theme.isOpen());
            for (int refresh = 0; refresh < 3; refresh++) {
                fixture.show();
                Vector2 point = theme.popup().label(snow).localToStageCoordinates(new Vector2(5, 5));
                ui.stage.stageToScreenCoordinates(point);
                ui.stage.mouseMoved(Math.round(point.x), Math.round(point.y));
                ui.draw();
                assertTrue(theme.isOpen(), "an unchanged lobby refresh must not dismiss the theme list");
                assertSame(theme, fixture.body.findActor("map-theme-1"));
            }
            assertNull(fixture.form.settings().getBoardTheme(1), "hovering makes no choice");
            click(ui, theme.popup().label(snow));
            assertFalse(theme.isOpen());
            assertEquals("snow", fixture.form.settings().getBoardTheme(1));

            UiSelect size = fixture.body.findActor("map-board-size");
            click(ui, size);
            fixture.show();
            ui.draw();
            assertTrue(size.isOpen(), "the size selector must also survive an unchanged lobby refresh");
            click(ui, size.popup().label(size.count() - 1));
            assertTrue(fixture.view.shown().customSize(), "the choice still updates the map form");
            fixture.body.remove();
        });
    }

    @Test
    void changedThemeOptionsStillRefreshTheSelectorWithoutAMapChange() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            UiSelect theme = fixture.body.findActor("map-theme-0");
            click(ui, theme);
            List<MapSetupForm.Theme> themes = new ArrayList<>(fixture.form.themes(-1));
            themes.add(new MapSetupForm.Theme("test-theme", "Test theme"));
            fixture.view.show(fixture.form.picture(), themes);
            ui.draw();
            assertFalse(theme.isOpen(), "changed options dismiss the obsolete list");

            UiSelect updated = fixture.body.findActor("map-theme-0");
            assertEquals(themes.size(), updated.count());
            click(ui, updated);
            ui.stage.keyDown(Input.Keys.END);
            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals("test-theme", fixture.form.settings().getBoardTheme(0));
            fixture.body.remove();
        });
    }

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
            assertTrue(calls.contains("preview " + LAKE), "the visible board has a preview requested");
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

    @Test
    void mapSetupUsesTheBattlefieldSpaceAndBackRestoresTheColumnOrRail() {
        UiTestStage.run(ui -> {
            Table players = new Table();
            Table battlefield = new Table();
            Actor rail = new Actor();
            LobbyCentre centre = new LobbyCentre(ui.kit, new Table(), false);
            LobbyColumns columns = new LobbyColumns(players, centre, battlefield, rail, 12);
            centre.onChange(key -> columns.expandCentre(LobbyMapSetupView.KEY.equals(key)));
            ui.window.addActor(columns);
            columns.setBounds(20, 20, 1600, 1000);
            ui.draw();
            float normalWidth = centre.getWidth();
            for (boolean folded : List.of(false, true)) {
                columns.fold(folded);
                centre.show(LobbyMapSetupView.KEY, "Map setup", new Table());
                ui.draw();
                assertTrue(players.isVisible());
                assertFalse(battlefield.isVisible());
                assertFalse(rail.isVisible());
                assertEquals(columns.getWidth(), centre.getX() + centre.getWidth(), .1f);
                assertTrue(centre.getWidth() > normalWidth);
                centre.back();
                ui.draw();
                assertEquals(!folded, battlefield.isVisible());
                assertEquals(folded, rail.isVisible());
            }
            columns.remove();
        });
    }

    @Test
    void browserViewsKeepTheSelectionAndDoubleClickWorksWhenAPreviewArrives() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            try {
                Table body = fixture.body;
                assertNull(body.findActor("map-select-all"));
                assertNull(body.findActor("map-place-rotated"));
                assertNull(body.findActor("map-rotate"));
                assertNull(body.findActor("map-preview"));
                Entry board = fixture.view.shown().boards().get(1);
                click(ui, body.findActor("map-slot-1"));
                click(ui, body.findActor("map-board-1"));
                fixture.view.picture(LobbyMapSetupController.pictureKey(board.name(), false, 16, 17, null, 0),
                      new BufferedImage(32, 34, BufferedImage.TYPE_INT_ARGB));
                click(ui, body.findActor("map-board-1"));
                assertEquals(board.name(), fixture.form.settings().getBoardsSelectedVector().get(1),
                      "a preview arriving between clicks must not break double-click placement");
                for (int toggle = 0; toggle < 2; toggle++) {
                    click(ui, body.findActor("map-board-view"));
                    UiButton row = body.findActor("map-board-1");
                    assertTrue(row.isChecked(), "switching views keeps the picked board");
                    assertNotNull(((Image) row.findActor("map-board-picture")).getDrawable(),
                          "both browser views use the loaded preview");
                }
            } finally {
                fixture.view.dispose();
                fixture.body.remove();
            }
        });
    }

    @Test
    void doubleClickingGeneratedAssignsThenGeneratesInBothBrowserViews() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            for (int mode = 0; mode < 2; mode++) {
                calls.clear();
                click(ui, fixture.body.findActor("map-board-0"));
                assertFalse(calls.contains("generate"), "a single click only selects Generated");
                click(ui, fixture.body.findActor("map-board-0"));
                assertEquals(MapSettings.BOARD_GENERATED, fixture.form.settings().getBoardsSelectedVector().get(0));
                assertEquals(MapSetupForm.Kind.GENERATED, fixture.view.shown().slots().get(0).kind());
                List<String> changes = calls.stream().filter(call -> !call.startsWith("preview ")).toList();
                assertEquals(List.of("board 0 " + MapSettings.BOARD_GENERATED, "generate"), changes,
                      "the server receives the board assignment before generation, once per double-click");
                click(ui, fixture.body.findActor("map-board-view"));
            }
            fixture.body.remove();
        });
    }

    @Test
    void aSlotsRotationButtonTurnsOnlyThatBoardWithoutChangingThePickedSlots() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            click(ui, fixture.body.findActor("map-slot-1"));
            Actor rotate = fixture.body.findActor("map-slot-rotate-0");
            Actor slot = fixture.body.findActor("map-slot-0");
            Vector2 at = rotate.localToAscendantCoordinates(slot, new Vector2());
            assertTrue(at.x >= 0 && at.x < slot.getWidth() / 2);
            assertTrue(at.y > slot.getHeight() / 2 && at.y + rotate.getHeight() <= slot.getHeight());
            Actor image = fixture.body.findActor("map-slot-picture-0");
            Vector2 previewTop = image.localToAscendantCoordinates(slot, new Vector2(0, image.getHeight()));
            assertTrue(previewTop.y <= at.y, "the taller card reserves separate room for controls above the preview");
            click(ui, rotate);
            assertSame(rotate, fixture.body.findActor("map-slot-rotate-0"));
            assertTrue(fixture.view.shown().slots().get(0).rotated());
            assertFalse(fixture.view.shown().slots().get(1).rotated());
            assertEquals(List.of(1), fixture.view.pickedSlots());
            click(ui, fixture.body.findActor("map-slot-rotate-0"));
            assertFalse(fixture.view.shown().slots().get(0).rotated());
            assertEquals(List.of(1), fixture.view.pickedSlots());
            fixture.body.remove();
        });
    }

    @Test
    void slotControlsRemainIndependentAndOpenWhileGeneratedPicturesArrive() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            try {
                UiSelect theme = fixture.body.findActor("map-theme-1");
                assertEquals(UiSelect.Kind.INLINE, theme.kind());
                click(ui, theme);
                String key = LobbyMapSetupController.pictureKey(fixture.view.shown().slots().get(1), fixture.view.shown());
                fixture.view.picture(key, new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB));
                ui.draw();
                assertSame(theme, fixture.body.findActor("map-theme-1"));
                assertTrue(theme.isOpen(), "loading a thumbnail must not interrupt editing");
                int snow = fixture.form.themes(-1).stream().map(MapSetupForm.Theme::theme).toList().indexOf("snow");
                click(ui, theme.popup().label(snow));
                assertEquals("snow", fixture.form.settings().getBoardTheme(1));
                assertNull(fixture.form.settings().getBoardTheme(0));
                click(ui, fixture.body.findActor("map-level-offset-1-up"));
                assertEquals(1, fixture.form.settings().getBoardLevelOffset(1));
                assertEquals(0, fixture.form.settings().getBoardLevelOffset(0));
                assertEquals(List.of(0), fixture.view.pickedSlots(), "editing a slot doesn't change the placement target");
                Image image = fixture.body.findActor("map-slot-picture-1");
                assertNotNull(image.getDrawable(), "Generated uses the server's painted slot image");
                fixture.view.picture(key, null);
                assertNull(image.getDrawable(), "invalidated generated boards stop showing their old terrain");
            } finally {
                fixture.view.dispose();
                fixture.body.remove();
            }
        });
    }

    @Test
    void inspectionInBothBrowserViewsRestoresTheLayoutWithoutChangingSelection() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            fixture.view.pick(fixture.view.shown().boards().get(2), false);
            for (int mode = 0; mode < 2; mode++) {
                assertNull(fixture.body.findActor("map-board-inspect-0"), "Generated cannot be inspected");
                ScrollPane list = fixture.body.findActor("map-board-list");
                float scroll = list.getScrollY();
                calls.clear();
                click(ui, fixture.body.findActor("map-board-inspect-1"));
                assertEquals(1, calls.stream().filter(call -> call.startsWith("inspect ")).count());
                assertTrue(calls.stream().noneMatch(call -> call.startsWith("board ")));
                assertFalse(fixture.body.findActor("map-layout-panel").isVisible());
                assertTrue(fixture.body.findActor("map-inspection-panel").isVisible());
                assertTrue(((UiButton) fixture.body.findActor("map-board-2")).isChecked());
                assertEquals(List.of(0), fixture.view.pickedSlots());
                click(ui, fixture.body.findActor("map-inspection-close"));
                fixture.inspections.getLast().accept(null);
                assertTrue(fixture.body.findActor("map-layout-panel").isVisible());
                assertFalse(fixture.body.findActor("map-inspection-panel").isVisible());
                assertSame(list, fixture.body.findActor("map-board-list"));
                assertEquals(scroll, list.getScrollY());
                click(ui, fixture.body.findActor("map-board-view"));
            }
            fixture.view.dispose();
            fixture.body.remove();
        });
    }

    @Test
    void scrollingToTheEndRequestsOnlyTheVisibleThumbnailsInBothViews() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            List<String> boards = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                boards.add(String.format("16x17/Scroll board %03d.board2", index));
            }
            server.setBoardsAvailableVector(boards);
            fixture.form.reload();
            fixture.show();
            for (int mode = 0; mode < 2; mode++) {
                ScrollPane scroll = fixture.body.findActor("map-board-list");
                scroll.setScrollPercentY(0);
                scroll.updateVisualScroll();
                ui.draw();
                List<String> top = fixture.previewRequests.getLast();
                assertTrue(top.contains(boards.getFirst()));
                assertTrue(top.size() < 25, "unseen rows aren't requested at the top");
                scroll.setScrollPercentY(1);
                scroll.updateVisualScroll();
                ui.draw();
                List<String> bottom = fixture.previewRequests.getLast();
                assertTrue(bottom.contains(boards.getLast()), "the current frame's viewport is sent immediately");
                assertTrue(bottom.size() < 25, "only the visible rows are requested at the bottom");
                assertTrue(bottom.stream().noneMatch(top::contains));
                click(ui, fixture.body.findActor("map-board-view"));
            }
            fixture.view.dispose();
            fixture.body.remove();
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
        final List<Consumer<BoardScene>> inspections = new ArrayList<>();
        final List<List<String>> previewRequests = new ArrayList<>();

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
        public void previews(List<String> boards) {
            previewRequests.add(boards);
            boards.forEach(board -> calls.add("preview " + board));
        }

        @Override
        public void inspect(String board, Consumer<BoardScene> ready) {
            calls.add("inspect " + board);
            inspections.add(ready);
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
            calls.add("board " + slot + " " + board);
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
            calls.add("generate");
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
