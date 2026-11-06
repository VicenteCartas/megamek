/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.MapSetupForm.Entry;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RandomMapForm.Feature;
import megamek.client.lobby.RandomMapForm.Level;
import megamek.client.lobby.RandomMapForm.Param;
import megamek.client.ui.Messages;
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
    void unchangedMapSetupRefreshKeepsTheInspectionOpen() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            try {
                click(ui, fixture.body.findActor("map-board-inspect-1"));
                LobbyBoardPreview preview = fixture.body.findActor("map-inspection-panel");
                var picture = fixture.view.shown();
                fixture.view.show(picture, fixture.form.themes(-1));
                assertTrue(preview.isVisible());
                assertSame(picture, fixture.view.shown());
                assertEquals(1, fixture.inspections.size(), "a refresh must not reload the inspected board");
                assertEquals(List.of(0), fixture.view.pickedSlots());
            } finally {
                fixture.view.dispose();
                fixture.body.remove();
            }
        });
    }

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
            UiButton generate = body.findActor("map-generate");
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
            int listeners = generate.getListeners().size;
            for (int refresh = 0; refresh < 20; refresh++) {
                fixture.view.show(fixture.form.picture(), fixture.form.themes(-1));
            }
            assertEquals(listeners, generate.getListeners().size,
                  "refreshing the setup reuses Generate's tooltip rather than stacking new ones");

            click(ui, body.findActor("map-load"));
            click(ui, body.findActor("map-random-settings"));
            click(ui, body.findActor("map-board-editor"));
            assertTrue(calls.containsAll(List.of("run LOAD", "run RANDOM_MAP", "run BOARD_EDITOR")), calls.toString());

            click(ui, body.findActor("map-medium-space"));
            assertEquals(MapSettings.MEDIUM_SPACE, fixture.form.settings().getMedium(), "the segmented control sets the map type");
            assertFalse(body.findActor("map-drawer-scroll").isVisible(), "space has no boards to pick");

            body.remove();
        });
    }

    @Test
    void regenerationAppearsOnlyForActiveGeneratedBoards() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            try {
                Table body = fixture.body;
                UiButton generate = body.findActor("map-generate");
                assertNotNull(generate);
                click(ui, generate);
                assertTrue(calls.contains("generate"));
                UiButton preview = body.findActor("map-preview-3d");
                assertEquals("PREVIEW", preview.getText().toString());
                click(ui, preview);
                assertTrue(calls.contains("run PREVIEW_3D"));

                fixture.act(form -> form.assign(1, LAKE));
                ui.draw();
                assertNull(body.findActor("map-generate"), "fixed maps have nothing to regenerate");
                assertEquals(preview.getPrefWidth(), ((Table) preview.getParent()).getPrefWidth(), .1f,
                      "hiding Regenerate leaves no empty button space");
                fixture.act(form -> form.addToStack(1, List.of(MapSettings.BOARD_GENERATED)));
                assertNull(body.findActor("map-generate"), "an unused stack alternative is not an active generated map");
                fixture.act(form -> form.toggleSurprise(1, new java.util.Random(1)));
                assertNull(body.findActor("map-generate"), "surprise candidates are not active generated maps");
                fixture.act(form -> form.removeFromStack(1, LAKE, new java.util.Random(1)));
                ui.draw();
                assertSame(generate, body.findActor("map-generate"));
                assertFalse(generate.isDisabled());
                assertEquals(MapSetupForm.Kind.GENERATED, fixture.view.shown().slots().get(1).kind());

                click(ui, body.findActor("map-medium-space"));
                assertNull(body.findActor("map-generate"), "space has no generated terrain");
                click(ui, body.findActor("map-medium-ground"));
                assertSame(generate, body.findActor("map-generate"));
            } finally {
                fixture.view.dispose();
                fixture.body.remove();
            }
        });
    }

    @Test
    void stackControlsAddWithoutDuplicatesRollHideAndRemoveBoards() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            Table body = fixture.body;
            click(ui, body.findActor("map-slot-0"));
            List<Entry> boards = fixture.view.shown().boards();
            fixture.view.pick(boards.get(1), false);
            fixture.view.pick(boards.get(2), true);
            fixture.body.validate();
            assertNull(body.findActor("map-random"));
            assertNull(body.findActor("map-surprise"));
            assertNull(body.findActor("map-stack-0"), "a single map has no stack controls");
            assertNull(body.findActor("map-stack-roll-0"));
            assertNull(body.findActor("map-stack-surprise-0"));
            click(ui, body.findActor("map-add-to-stack"));
            assertEquals(2, fixture.form.settings().getBoardStack(0).size());
            assertEquals(boards.get(1).name(), fixture.form.settings().getBoardsSelectedVector().getFirst());
            UiButton add = body.findActor("map-add-to-stack");
            assertTrue(add.isDisabled());
            assertEquals("ALREADY ADDED", add.getText().toString());
            click(ui, body.findActor("map-stack-roll-0"));
            assertEquals(boards.get(2).name(), fixture.form.settings().getBoardsSelectedVector().getFirst());
            click(ui, body.findActor("map-stack-surprise-0"));
            String surprise = fixture.form.settings().getBoardsSelectedVector().get(0);
            assertTrue(surprise.startsWith(MapSettings.BOARD_SURPRISE), surprise);
            assertEquals(MapSetupForm.Kind.SURPRISE, fixture.view.shown().slots().get(0).kind());
            assertNull(body.<Image>findActor("map-slot-picture-0").getDrawable());
            fixture.view.pick(boards.get(3), false);
            click(ui, body.findActor("map-add-to-stack"));
            assertEquals(3, fixture.form.settings().getBoardStack(0).size());
            assertEquals(MapSetupForm.Kind.SURPRISE, fixture.view.shown().slots().get(0).kind());
            UiButton stack = body.findActor("map-stack-0");
            assertEquals("3", stack.getText().toString());
            assertTrue(stack.details.isEmpty(), "the stack count must not have a text subtitle");
            assertEquals(1, stack.icons.size());
            body.validate();
            assertTrue(stack.icons.getFirst().getX() >= stack.getLabel().getX() + stack.getLabel().getWidth(),
                  "the stack icon follows the number in the same button");
            click(ui, body.findActor("map-stack-0"));
            ui.draw();
            assertTrue(ui.stage.getRoot().findActor("map-stack-menu").isVisible());
            for (int index = 0; index < 3; index++) {
                assertFalse(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-" + index).isChecked(),
                      "a surprise has no currently selected map to highlight");
            }
            click(ui, ui.stage.getRoot().findActor("map-stack-select-0-2"));
            assertEquals(boards.get(3).name(), fixture.form.picture().slots().getFirst().file());
            assertEquals(3, fixture.form.settings().getBoardStack(0).size());
            assertFalse(ui.stage.getRoot().findActor("map-stack-menu").isVisible(), "selecting a map closes the dropdown");
            click(ui, body.findActor("map-stack-0"));
            assertTrue(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-2").isChecked());
            click(ui, ui.stage.getRoot().findActor("map-stack-select-0-0"));
            assertEquals(boards.get(1).name(), fixture.form.picture().slots().getFirst().file());
            assertEquals(3, fixture.form.settings().getBoardStack(0).size());
            assertFalse(ui.stage.getRoot().findActor("map-stack-menu").isVisible());
            click(ui, body.findActor("map-stack-0"));
            assertTrue(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-0").isChecked());
            assertFalse(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-2").isChecked());
            ui.draw();
            UiTestStage.capture("map-stack-menu", 1600, 1080).dispose();
            click(ui, ui.stage.getRoot().findActor("map-stack-remove-0-1"));
            assertEquals(List.of(boards.get(1).name(), boards.get(3).name()), fixture.form.settings().getBoardStack(0));
            assertEquals(boards.get(1).name(), fixture.form.picture().slots().getFirst().file(),
                  "the remove action must not select the map it removes");
            assertTrue(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-0").isChecked());
            assertTrue(ui.stage.getRoot().findActor("map-stack-menu").isVisible());
            click(ui, ui.stage.getRoot().findActor("map-stack-remove-0-0"));
            assertEquals(boards.get(3).name(), fixture.form.picture().slots().getFirst().file(),
                  "removing the current map selects a remaining member");
            assertTrue(ui.stage.getRoot().findActor("map-stack-menu").isVisible(),
                  "removing a map keeps the dropdown open even when only one map remains");
            assertTrue(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-0").isChecked());
            assertNull(body.findActor("map-stack-0"));
            assertNull(body.findActor("map-stack-roll-0"));
            assertNull(body.findActor("map-stack-surprise-0"));
            click(ui, ui.stage.getRoot().findActor("map-stack-select-0-0"));
            assertFalse(ui.stage.getRoot().findActor("map-stack-menu").isVisible(),
                  "choosing the current map also closes the dropdown");
            fixture.view.pick(boards.get(1), false);
            click(ui, body.findActor("map-add-to-stack"));
            assertNotNull(body.findActor("map-stack-0"), "adding a second map restores the stack controls");
            click(ui, body.findActor("map-stack-surprise-0"));
            click(ui, body.findActor("map-stack-0"));
            click(ui, ui.stage.getRoot().findActor("map-stack-remove-0-1"));
            assertEquals(boards.get(3).name(), fixture.view.shown().slots().getFirst().file(),
                  "the last surprise candidate is revealed before its eye button disappears");
            assertEquals(MapSetupForm.Kind.FIXED, fixture.view.shown().slots().getFirst().kind());
            assertNull(body.findActor("map-stack-surprise-0"));
            assertTrue(ui.stage.getRoot().findActor("map-stack-menu").isVisible());
            assertTrue(ui.stage.getRoot().<UiButton>findActor("map-stack-select-0-0").isChecked());
            click(ui, ui.stage.getRoot().findActor("map-stack-select-0-0"));
            click(ui, body.findActor("map-add-to-stack"));
            fixture.view.pick(boards.get(3), false);
            click(ui, body.findActor("map-place"));
            assertEquals(List.of(boards.get(3).name()), fixture.form.settings().getBoardStack(0));
            assertEquals(MapSetupForm.Kind.FIXED, fixture.view.shown().slots().getFirst().kind());
            assertNull(body.findActor("map-stack-0"), "Place removes the stack controls along with its alternatives");
            fixture.view.pick(boards.get(1), false);
            click(ui, body.findActor("map-add-to-stack"));
            body.setSize(1050, 800);
            ui.draw();
            for (int index = 0; index < 2; index++) {
                Actor slot = body.findActor("map-slot-" + index);
                Actor picture = body.findActor("map-slot-picture-" + index);
                float previewTop = picture.localToAscendantCoordinates(slot, new Vector2(0, picture.getHeight())).y;
                for (String name : List.of("map-stack-", "map-stack-roll-", "map-stack-surprise-", "map-theme-",
                      "map-slot-rotate-", "map-level-offset-reset-")) {
                    Actor control = body.findActor(name + index);
                    if (name.startsWith("map-stack-") && index == 1) {
                        assertNull(control, "single maps do not reserve space for stack controls");
                        continue;
                    }
                    assertNotNull(control, name);
                    Vector2 at = control.localToAscendantCoordinates(slot, new Vector2());
                    assertTrue(at.y >= previewTop, name + " must stay above the board image when controls wrap");
                    assertTrue(at.x >= 0 && at.x + control.getWidth() <= slot.getWidth() + .5f, name);
                }
            }
            UiTestStage.capture("map-stack-small", 1600, 1080).dispose();
            fixture.view.dispose();
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
    void theRandomMapSettingsKeepTheirSizeAcrossTheTabs() {
        UiTestStage.run(ui -> {
            server();
            RandomMapForm form = new RandomMapForm(server, settings -> { });
            LobbyNativeDialogs dialogs = new LobbyNativeDialogs(ui.kit, ui.stage, false);
            LobbyRandomMapView view = dialogs.randomMap(form, List.of("", "snow"), new RandomHost());
            for (String mode : List.of("random-normal", "random-advanced")) {
                click(ui, view.control(mode));
                ui.draw();
                Actor card = view.control("random-tabs");
                while (!"dialog".equals(card.getName())) {
                    card = card.getParent();
                }
                float width = card.getWidth();
                float height = card.getHeight();
                Actor theme = view.control("random-theme");
                float right = theme.localToActorCoordinates(card, new Vector2(theme.getWidth(), 0)).x;
                assertTrue(right <= width, mode + " keeps the theme inside the dialog");
                for (String tab : List.of("civilization", "elevation", "natural", "water", "effects")) {
                    Actor control = view.control("random-tab-" + tab);
                    assertNotNull(control, tab);
                    click(ui, control);
                    ui.draw();
                    assertEquals(width, card.getWidth(), .5f, mode + " " + tab + " keeps the dialog's width");
                    assertEquals(height, card.getHeight(), .5f, mode + " " + tab + " keeps the dialog's height");
                }
            }
            assertTrue(dialogs.isOpen());
            ui.stage.keyDown(Input.Keys.ESCAPE);
            ui.draw();
            assertFalse(dialogs.isOpen(), "Escape closes the random map settings");
            dialogs.dispose();
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
                UiButton preview = fixture.body.findActor("map-board-inspect-1");
                assertEquals("PREVIEW", preview.getText().toString());
                assertTrue(preview.icons.isEmpty());
                ui.draw();
                UiTestStage.capture("map-preview-buttons-" + mode, 1600, 1080).dispose();
                ScrollPane list = fixture.body.findActor("map-board-list");
                float scroll = list.getScrollY();
                Actor search = fixture.body.findActor("map-search-field");
                click(ui, search);
                assertSame(search, ui.stage.getKeyboardFocus());
                calls.clear();
                click(ui, fixture.body.findActor("map-board-inspect-1"));
                assertSame(fixture.body.findActor("map-inspection-image"), ui.stage.getKeyboardFocus(),
                      "opening a preview transfers focus from the search field to its camera");
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
    void doubleClicksSwitchInspectionUntilReturningToTheLayoutInBothBrowserViews() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            try {
                click(ui, fixture.body.findActor("map-slot-1"));
                for (int mode = 0; mode < 2; mode++) {
                    List<String> placed = List.copyOf(fixture.form.settings().getBoardsSelectedVector());
                    click(ui, fixture.body.findActor("map-board-inspect-1"));
                    Consumer<BoardSource> previous = fixture.inspections.getLast();
                    calls.clear();
                    Entry next = fixture.view.shown().boards().get(2);
                    click(ui, fixture.body.findActor("map-board-2"));
                    click(ui, fixture.body.findActor("map-board-2"));
                    assertEquals(List.of("inspect " + next.name()),
                          calls.stream().filter(call -> !call.startsWith("preview ")).toList());
                    assertEquals(placed, fixture.form.settings().getBoardsSelectedVector(),
                          "double-clicking while inspecting must never assign a board");
                    assertEquals(List.of(1), fixture.view.pickedSlots());
                    assertFalse(fixture.body.findActor("map-layout-panel").isVisible());
                    GpuLoadingPanel loading = fixture.body.findActor("board-loading-panel");
                    assertTrue(loading.isVisible());
                    BoardSource stale = mock(BoardSource.class);
                    previous.accept(stale);
                    verify(stale).close();
                    assertEquals(next.label(), fixture.body.<Label>findActor("map-inspection-title").getText().toString());
                    assertEquals(Messages.getString("GpuBoard.previewLoading"),
                          loading.<Label>findActor("board-loading-message").getText().toString(),
                          "a previous inspection's late result must not replace the current loading screen");

                    calls.clear();
                    click(ui, fixture.body.findActor("map-board-0"));
                    click(ui, fixture.body.findActor("map-board-0"));
                    assertTrue(calls.stream().allMatch(call -> call.startsWith("preview ")),
                          "Generated has no saved board to inspect and must not be assigned by a double-click here");
                    assertEquals(placed, fixture.form.settings().getBoardsSelectedVector());

                    fixture.view.pick(next, false);
                    click(ui, fixture.body.findActor("map-place"));
                    assertEquals(next.name(), fixture.form.settings().getBoardsSelectedVector().get(1),
                          "Place still explicitly assigns a board while inspecting");
                    click(ui, fixture.body.findActor("map-inspection-close"));
                    Entry assigned = fixture.view.shown().boards().get(3);
                    click(ui, fixture.body.findActor("map-board-3"));
                    click(ui, fixture.body.findActor("map-board-3"));
                    assertEquals(assigned.name(), fixture.form.settings().getBoardsSelectedVector().get(1),
                          "returning to the layout restores double-click placement");
                    click(ui, fixture.body.findActor("map-board-view"));
                }
            } finally {
                fixture.view.dispose();
                fixture.body.remove();
            }
        });
    }

    @Test
    void filtersNarrowByTagAndFolderAndAnySizeMakesAPlacedBoardTheWholeMap() {
        UiTestStage.run(ui -> {
            Fixture fixture = new Fixture(ui);
            Table body = fixture.body;
            assertNull(body.<Container<?>>findActor("map-filter-holder").getActor(),
                  "the filters start shut");
            click(ui, body.findActor("map-filters"));
            assertNotNull(body.findActor("map-filter-panel"), "Filters opens the panel");
            assertEquals(MapSetupForm.Library.READING, fixture.form.libraryState(),
                  "opening the filters asks for the library");
            assertNotNull(body.findActor("map-filter-tags-reading"));
            assertTrue(body.<Button>findActor("map-filter-scope-any").isDisabled(),
                  "a map of two boards cannot browse any size");

            fixture.form.library(List.of(
                  new MapSetupForm.LibraryBoard("16x17/Desert 1.board2", 16, 17, List.of("Desert")),
                  new MapSetupForm.LibraryBoard("16x17/City Hills.board2", 16, 17, List.of("Urban")),
                  new MapSetupForm.LibraryBoard(LAKE, 16, 17, List.of("Water")),
                  new MapSetupForm.LibraryBoard("32x34/Big City.board2", 32, 34, List.of("Urban"))));
            fixture.show();
            ui.draw();
            click(ui, body.findActor("map-filter-tag-Urban"));
            assertEquals(List.of("16x17/City Hills.board2"),
                  fixture.view.shown().boards().stream().map(Entry::name).toList(),
                  "a tag keeps the boards of this size that have it, without Generated");
            assertEquals(1, fixture.view.shown().filterCount(),
                  "the button counts the filters at work");
            click(ui, body.findActor("map-filter-clear"));
            assertEquals(MapSetupForm.Filter.NONE, fixture.form.filter());
            click(ui, body.findActor("map-filter-folder-16x17"));
            assertEquals(3, fixture.view.shown().boards().size(), "the folder keeps its three boards");
            click(ui, body.findActor("map-filter-folder-16x17"));

            fixture.form.mapWidth(1);
            fixture.show();
            ui.draw();
            click(ui, body.findActor("map-filter-scope-any"));
            assertTrue(fixture.view.shown().anySize(), "a one-board map browses any size");
            assertNotNull(body.findActor("map-filter-width"), "any size shows the size ranges");
            List<Entry> boards = fixture.view.shown().boards();
            int big = boards.indexOf(boards.stream().filter(entry -> entry.name().startsWith("32x34"))
                  .findFirst().orElseThrow());
            click(ui, body.findActor("map-board-" + big));
            assertTrue(body.<UiButton>findActor("map-add-to-stack").isDisabled(),
                  "a board of another size cannot join the slot's stack");
            calls.clear();
            click(ui, body.findActor("map-place"));
            assertTrue(calls.contains("single 32x34/Big City.board2 32x34"), calls.toString());
            assertEquals(32, fixture.form.settings().getBoardWidth(), "the map takes the board's own size");
            fixture.view.dispose();
            body.remove();
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
        final List<Consumer<BoardSource>> inspections = new ArrayList<>();
        final List<List<String>> previewRequests = new ArrayList<>();

        Fixture(UiTestStage ui) {
            server();
            form = new MapSetupForm(() -> server, () -> Set.of(new BoardDimensions(16, 17)), new Commands(),
                  board -> "", () -> List.of("", "snow"));
            view = new LobbyMapSetupView(ui.kit, this, false);
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
        public void inspect(String board, Consumer<BoardSource> ready) {
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
                  board.contains("\n") && !board.startsWith(MapSettings.BOARD_SURPRISE) ? MapSettings.BOARD_SURPRISE + board : board);
        }

        @Override
        public void selectSingleBoard(MapSettings settings, String board, BoardDimensions size) {
            calls.add("single " + board + " " + size.width() + "x" + size.height());
            settings.setMapSize(1, 1);
            settings.setBoardSize(size.width(), size.height());
            settings.setBoardsSelectedVector(new ArrayList<>(List.of(board)));
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
