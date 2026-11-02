/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.UnitConfigForm;
import megamek.client.lobby.UnitEquipmentForm;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the unit bay through the stage, as a pointer and keyboard would: the gunnery's + steps the skill, a zone of
 * the grid is chosen, a blank toughness disables OK until a number is typed, Next applies and asks for the next unit, and
 * OK applies the bay to the unit and closes it; on the equipment tab a munition is chosen from its list and a shot taken
 * out, which OK loads into the bin.
 */
@Tag("on-demand")
class UnitBaySmokeTest {

    @Test
    void theBayIsClickedThroughAndApplied() throws Exception {
        EquipmentType.initializeTypes();
        Game game = new Game();
        Player local = new Player(1, "Local");
        game.addPlayer(1, local);
        Entity first = atlas(game, local, "First");
        atlas(game, local, "Second");
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setMedium(MapSettings.MEDIUM_GROUND);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
        game.getOptions().getOption(OptionsConstants.RPG_TOUGHNESS).setValue(true);
        UnitConfigForm form = new UnitConfigForm(client, client, List.of(first), true, null);

        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            LobbyUnitBayView.Host host = new LobbyUnitBayView.Host() {
                @Override
                public void edt(Runnable work, Runnable then) {
                    work.run();
                    then.run();
                }

                @Override
                public boolean apply(UnitConfigForm applied) {
                    said.add("apply");
                    return applied.apply();
                }

                @Override
                public void next(UnitConfigForm applied, boolean forward) {
                    said.add(forward ? "next" : "previous");
                }

                @Override
                public BufferedImage portrait(UnitConfigForm shown, int slot) {
                    return null;
                }

                @Override
                public void close() {
                    said.add("close");
                }
            };
            BufferedImage sprite = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            LobbyUnitBayView bay = new LobbyUnitBayView(ui.kit, form, List.of(sprite), List.of(), host);
            bay.body().setBounds(100, 100, 900, 800);
            ui.window.addActor(bay.body());
            bay.body().validate();
            ui.draw();

            click(ui, control(bay, "bay-gunnery-0-up"));
            assertEquals("5", form.member(0).text(UnitConfigForm.Skill.GUNNERY), "+ steps the gunnery");
            click(ui, control(bay, "bay-piloting-0-down"));
            assertEquals("4", form.member(0).text(UnitConfigForm.Skill.PILOTING), "- steps the piloting");

            click(ui, control(bay, "bay-tab-deployment"));
            click(ui, control(bay, "bay-zone-" + Board.START_NE));
            assertEquals(Board.START_NE, form.zone(), "the grid chooses the zone");
            assertTrue(((Button) control(bay, "bay-zone-" + Board.START_NE)).isChecked());

            click(ui, control(bay, "bay-offset-up"));
            click(ui, control(bay, "bay-offset-up"));
            assertEquals("2", form.text(UnitConfigForm.Field.OFFSET), "the offset steps");
            click(ui, control(bay, "bay-prone"));
            assertTrue(form.prone(), "the stance chip lays the unit down");
            assertTrue(((Button) control(bay, "bay-prone")).isChecked());
            click(ui, control(bay, "bay-prone"));
            assertFalse(form.prone(), "and a second click stands it up");

            click(ui, control(bay, "bay-tab-pilot"));
            Actor toughness = control(bay, "bay-toughness-0");
            click(ui, toughness);
            ((TextField) toughness).setCursorPosition(((TextField) toughness).getText().length());
            for (int i = 0; i < 3; i++) {
                ui.stage.keyTyped('\b');
            }
            ui.draw();
            assertEquals("", form.member(0).text(UnitConfigForm.Skill.TOUGHNESS));
            assertTrue(((Button) control(bay, "bay-ok")).isDisabled(), "a blank toughness disables OK");
            assertTrue(((Button) control(bay, "bay-apply")).isDisabled());
            click(ui, control(bay, "bay-ok"));
            assertTrue(said.isEmpty(), "a disabled OK applies nothing");
            ui.stage.keyTyped('1');
            ui.draw();
            assertFalse(((Button) control(bay, "bay-ok")).isDisabled(), "a number enables OK again");
            click(ui, control(bay, "bay-next"));
            assertEquals(List.of("apply", "next"), said, "Next applies, then asks for the next unit");
            assertEquals(5, first.getCrew().getGunnery(0));
            assertEquals(Board.START_NE, first.getStartingPos(false));
            assertEquals(2, first.getStartingOffset(false));

            click(ui, control(bay, "bay-tab-pilot"));
            click(ui, control(bay, "bay-gunnery-0-up"));
            click(ui, control(bay, "bay-ok"));
            assertEquals(List.of("apply", "next", "apply", "close"), said, "OK applies and closes");
            assertEquals(6, first.getCrew().getGunnery(0));
            bay.dispose();
        });
    }

    @Test
    void aMunitionIsChosenOnTheEquipmentTab() throws Exception {
        EquipmentType.initializeTypes();
        Game game = new Game();
        Player local = new Player(1, "Local");
        game.addPlayer(1, local);
        Entity archer = new MekFileParser(new File("testresources/megamek/common/units/Archer ARC-2R.mtf"))
              .getEntity();
        archer.setGame(game);
        archer.setCrew(new Crew(CrewType.SINGLE));
        archer.setOwner(local);
        game.addEntity(archer);
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setMedium(MapSettings.MEDIUM_GROUND);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
        UnitConfigForm form = new UnitConfigForm(client, client, List.of(archer), true, null);
        UnitEquipmentForm.AmmoBin bin = form.equipment().orElseThrow().ammo().stream()
              .filter(row -> row.munitions().size() > 1).findFirst().orElseThrow();
        int other = (bin.munition() == 0) ? 1 : 0;

        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            LobbyUnitBayView bay = new LobbyUnitBayView(ui.kit, form,
                  List.of(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)), List.of(), host(said));
            bay.body().setBounds(100, 100, 900, 800);
            ui.window.addActor(bay.body());
            bay.body().validate();
            ui.draw();

            click(ui, control(bay, "bay-tab-equipment"));
            String prefix = "bay-ammo-" + bin.number();
            UiSelect munition = (UiSelect) control(bay, prefix + "-munition");
            click(ui, munition);
            assertTrue(munition.isOpen(), "the munition list opens");
            click(ui, munition.popup().label(other));
            assertEquals(other, bin.munition(), "the list chooses the munition");
            String chosen = bin.selected().getInternalName();
            int full = bin.shots();
            click(ui, control(bay, prefix + "-shots-down"));
            assertEquals(full - 1, bin.shots(), "- takes a shot out");

            click(ui, control(bay, "bay-ok"));
            assertEquals(List.of("apply", "close"), said);
            AmmoMounted mount = (AmmoMounted) archer.getEquipment(bin.number());
            assertEquals(chosen, mount.getType().getInternalName(), "OK loads the munition");
            assertEquals(full - 1, mount.getBaseShotsLeft());
            bay.dispose();
        });
    }

    @Test
    void anAbilityIsFoundAndTakenOnTheAbilitiesTab() throws Exception {
        EquipmentType.initializeTypes();
        Game game = new Game();
        game.getOptions().getOption(OptionsConstants.RPG_PILOT_ADVANTAGES).setValue(true);
        Player local = new Player(1, "Local");
        game.addPlayer(1, local);
        Entity atlas = atlas(game, local, "Atlas");
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setMedium(MapSettings.MEDIUM_GROUND);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
        UnitConfigForm form = new UnitConfigForm(client, client, List.of(atlas), true, null);
        String dodge = "bay-ability-" + OptionsConstants.PILOT_DODGE_MANEUVER;
        String jumper = "bay-ability-" + OptionsConstants.PILOT_JUMPING_JACK;

        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            LobbyUnitBayView bay = new LobbyUnitBayView(ui.kit, form,
                  List.of(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)), List.of(), host(said));
            bay.body().setBounds(100, 100, 900, 800);
            ui.window.addActor(bay.body());
            bay.body().validate();
            ui.draw();

            click(ui, control(bay, "bay-tab-abilities"));
            UiButton jumping = (UiButton) control(bay, jumper);
            click(ui, jumping);
            assertTrue(jumping.isChecked(), "a click takes the ability");
            click(ui, jumping);
            assertFalse(jumping.isChecked(), "a second click drops it");
            click(ui, control(bay, "bay-ability-search"));
            for (char typed : "dodge".toCharArray()) {
                ui.stage.keyTyped(typed);
            }
            ui.draw();
            assertNull(bay.body().findActor(jumper), "the search hides the others");
            click(ui, control(bay, dodge));
            assertTrue(((UiButton) control(bay, dodge)).isChecked());

            click(ui, control(bay, "bay-ok"));
            assertEquals(List.of("apply", "close"), said);
            assertTrue(atlas.hasAbility(OptionsConstants.PILOT_DODGE_MANEUVER), "OK teaches the pilot");
            assertFalse(atlas.hasAbility(OptionsConstants.PILOT_JUMPING_JACK));
            bay.dispose();
        });
    }

    @Test
    void theBoardPictureChoosesTheZone() throws Exception {
        EquipmentType.initializeTypes();
        Game game = new Game();
        Player local = new Player(1, "Local");
        local.setStartingPos(Board.START_N);
        game.addPlayer(1, local);
        Entity atlas = atlas(game, local, "Atlas");
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setMedium(MapSettings.MEDIUM_GROUND);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
        UnitConfigForm form = new UnitConfigForm(client, client, List.of(atlas), true, new Board(16, 17));

        UiTestStage.run(ui -> {
            List<String> said = new ArrayList<>();
            LobbyUnitBayView bay = new LobbyUnitBayView(ui.kit, form,
                  List.of(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)), List.of(), host(said));
            bay.body().setBounds(100, 100, 1100, 900);
            ui.window.addActor(bay.body());
            bay.body().validate();
            ui.draw();

            click(ui, control(bay, "bay-tab-deployment"));
            assertTrue(control(bay, "bay-edge-only").isVisible(), "the owner's zone says why there is no offset");
            assertTrue(form.zoneHexes()[5][0], "the picture shows the owner's north edge");
            click(ui, control(bay, "bay-zone-" + Board.START_S));
            assertEquals(Board.START_S, form.zone(), "a click on the board's south third chooses the south");
            assertTrue(form.zoneHexes()[5][16]);
            assertTrue(form.enabled(UnitConfigForm.Field.OFFSET), "an edge takes an offset");
            assertEquals(!form.enabled(UnitConfigForm.Field.WIDTH), control(bay, "bay-edge-only").isVisible(),
                  "the hint stays only to say the rules fix the width");
            click(ui, control(bay, "bay-offset-up"));
            assertEquals("1", form.text(UnitConfigForm.Field.OFFSET), "the offset steps");
            assertFalse(form.zoneHexes()[5][16], "and the zone moves in from the edge");
            assertTrue(form.zoneHexes()[5][15]);
            click(ui, control(bay, "bay-zone-" + Board.START_CENTER));
            assertEquals(Board.START_CENTER, form.zone(), "the middle third is the centre");
            click(ui, control(bay, "bay-zone-" + Board.START_EDGE));
            assertEquals(Board.START_EDGE, form.zone(), "the chips below choose the rest");
            assertTrue(((Button) control(bay, "bay-zone-" + Board.START_EDGE)).isChecked());
            bay.dispose();
        });
    }

    private static LobbyUnitBayView.Host host(List<String> said) {
        return new LobbyUnitBayView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                work.run();
                then.run();
            }

            @Override
            public boolean apply(UnitConfigForm applied) {
                said.add("apply");
                return applied.apply();
            }

            @Override
            public void next(UnitConfigForm applied, boolean forward) {
                said.add(forward ? "next" : "previous");
            }

            @Override
            public BufferedImage portrait(UnitConfigForm shown, int slot) {
                return null;
            }

            @Override
            public void close() {
                said.add("close");
            }
        };
    }

    private static Entity atlas(Game game, Player owner, String chassis) throws Exception {
        Entity unit = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        unit.setGame(game);
        unit.setChassis(chassis);
        Crew crew = new Crew(CrewType.SINGLE);
        crew.setGunnery(4, 0);
        crew.setPiloting(5, 0);
        unit.setCrew(crew);
        unit.setOwner(owner);
        game.addEntity(unit);
        return unit;
    }

    private static Actor control(LobbyUnitBayView bay, String name) {
        Actor actor = bay.control(name);
        if (actor == null) {
            actor = bay.body().findActor(name);
        }
        assertNotNull(actor, name);
        return actor;
    }

    private static void reveal(UiTestStage ui, Actor actor) {
        Actor scroll = actor;
        while (scroll != null && !(scroll instanceof ScrollPane)) {
            scroll = scroll.getParent();
        }
        if (scroll instanceof ScrollPane pane) {
            pane.layout();
            Vector2 spot = actor.localToAscendantCoordinates(pane.getActor(), new Vector2());
            pane.scrollTo(spot.x, spot.y, actor.getWidth(), actor.getHeight(), false, true);
            pane.updateVisualScroll();
        }
        ui.draw();
    }

    private static void click(UiTestStage ui, Actor actor) {
        reveal(ui, actor);
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
