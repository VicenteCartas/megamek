/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Optional;

import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.UnitConfigForm.Field;
import megamek.client.lobby.UnitConfigForm.Member;
import megamek.client.lobby.UnitConfigForm.Problem;
import megamek.client.lobby.UnitConfigForm.Skill;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.enums.Gender;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnitConfigFormTest {
    private Game game;
    private Player local;
    private Player enemy;
    private Client client;

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        local = new Player(1, "Local");
        local.setTeam(1);
        game.addPlayer(1, local);
        enemy = new Player(2, "Enemy");
        enemy.setTeam(2);
        game.addPlayer(2, enemy);
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setMedium(MapSettings.MEDIUM_GROUND);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
    }

    private Entity mek(Player owner, String chassis) {
        Entity mek;
        try {
            mek = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        } catch (EntityLoadingException e) {
            throw new IllegalStateException(e);
        }
        mek.setGame(game);
        mek.setChassis(chassis);
        Crew crew = new Crew(CrewType.SINGLE);
        crew.setName("Ana", 0);
        crew.setGunnery(4, 0);
        crew.setGunneryL(4, 0);
        crew.setGunneryM(4, 0);
        crew.setGunneryB(4, 0);
        crew.setPiloting(5, 0);
        mek.setCrew(crew);
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    private UnitConfigForm form(Entity... units) {
        return new UnitConfigForm(client, client, List.of(units), true, null);
    }

    private void option(String name, boolean value) {
        game.getOptions().getOption(name).setValue(value);
    }

    @Test
    void itStartsFromTheUnit() {
        Entity mek = mek(local, "Hunchback");
        mek.setDeployRound(3);
        mek.setStartingPos(Board.START_SE);
        UnitConfigForm form = form(mek);

        assertTrue(form.showsCrew());
        assertFalse(form.multiple());
        Member pilot = form.member(0);
        assertEquals("Ana", pilot.name());
        assertEquals("4", pilot.text(Skill.GUNNERY));
        assertEquals("5", pilot.text(Skill.PILOTING));
        assertTrue(pilot.shows(Skill.GUNNERY));
        assertFalse(pilot.shows(Skill.GUNNERY_L));
        assertEquals(Board.START_SE, form.zone());
        assertEquals(3, form.round());
        assertEquals(16, form.boardWidth());
        assertTrue(form.valid());
    }

    @Test
    void theBoardInThirdsIsTheCompass() {
        assertEquals(Board.START_NW, UnitConfigForm.compassZone(0, 0));
        assertEquals(Board.START_N, UnitConfigForm.compassZone(.5f, .1f));
        assertEquals(Board.START_NE, UnitConfigForm.compassZone(.99f, .2f));
        assertEquals(Board.START_W, UnitConfigForm.compassZone(.1f, .5f));
        assertEquals(Board.START_CENTER, UnitConfigForm.compassZone(.5f, .5f));
        assertEquals(Board.START_E, UnitConfigForm.compassZone(.9f, .4f));
        assertEquals(Board.START_SW, UnitConfigForm.compassZone(.2f, .9f));
        assertEquals(Board.START_S, UnitConfigForm.compassZone(.5f, 1f));
        assertEquals(Board.START_SE, UnitConfigForm.compassZone(1.5f, 2f), "a point past the edge is the nearest");
    }

    @Test
    void theZoneHexesFollowTheZoneItsOffsetAndWidth() {
        local.setStartingPos(Board.START_W);
        Entity mek = mek(local, "Hunchback");
        assertNull(form(mek).zoneHexes(), "no board, no picture");
        UnitConfigForm form = new UnitConfigForm(client, client, List.of(mek), true, new Board(16, 17));

        form.zone(Board.START_NONE);
        boolean[][] owners = form.zoneHexes();
        assertTrue(owners[0][8], "the owner's zone is the owner's west edge");
        assertFalse(owners[15][8]);
        form.zone(Board.START_E);
        form.text(Field.OFFSET, "2");
        form.text(Field.WIDTH, "3");
        boolean[][] east = form.zoneHexes();
        assertFalse(east[15][8], "the offset keeps it off the edge");
        assertTrue(east[13][8]);
        assertTrue(east[11][8], "three hexes wide");
        assertFalse(east[10][8]);
    }

    @Test
    void wrongNumbersShowAProblemAndBlockTheApply() {
        Entity mek = mek(local, "Hunchback");
        UnitConfigForm form = form(mek);
        Member pilot = form.member(0);

        pilot.text(Skill.GUNNERY, "");
        assertEquals(Optional.of(new Problem("Lobby.settings.wholeNumber", List.of())), pilot.error(Skill.GUNNERY));
        assertFalse(form.valid());
        assertFalse(form.apply());
        pilot.text(Skill.GUNNERY, "9");
        assertEquals(Optional.of(new Problem("Lobby.bay.range", List.of(0, UnitConfigForm.MAX_SKILL))),
              pilot.error(Skill.GUNNERY));
        pilot.text(Skill.GUNNERY, "2");
        assertTrue(form.valid());

        form.zone(Board.START_N);
        form.text(Field.OFFSET, "-1");
        assertEquals("Lobby.settings.notNegative", form.error(Field.OFFSET).orElseThrow().key());
        assertFalse(form.apply());
        assertEquals(4, mek.getCrew().getGunnery(0));
    }

    @Test
    void theApplyWritesTheCrewAndDeployment() {
        Entity mek = mek(local, "Hunchback");
        UnitConfigForm form = form(mek);
        Member pilot = form.member(0);
        pilot.text(Skill.GUNNERY, "2");
        pilot.text(Skill.PILOTING, "3");
        pilot.name("Bea");
        pilot.nickname("Ace");
        form.zone(Board.START_W);
        form.round(4);
        form.text(Field.OFFSET, "2");
        form.text(Field.WIDTH, "3");

        assertTrue(form.apply());

        Crew crew = mek.getCrew();
        assertEquals(2, crew.getGunnery(0));
        assertEquals(2, crew.getGunneryL(0));
        assertEquals(2, crew.getGunneryB(0));
        assertEquals(3, crew.getPiloting(0));
        assertEquals(2, crew.getArtillery(0));
        assertEquals("Bea", crew.getName(0));
        assertEquals("Ace", crew.getNickname(0));
        assertEquals(Board.START_W, mek.getStartingPos(false));
        assertEquals(4, mek.getDeployRound());
        assertEquals(2, mek.getStartingOffset(false));
        assertEquals(3, mek.getStartingWidth(false));
    }

    @Test
    void theRpgGunneryIsAveraged() {
        option(OptionsConstants.RPG_RPG_GUNNERY, true);
        Entity mek = mek(local, "Hunchback");
        UnitConfigForm form = form(mek);
        Member pilot = form.member(0);
        assertFalse(pilot.shows(Skill.GUNNERY));
        assertTrue(pilot.shows(Skill.GUNNERY_M));
        pilot.text(Skill.GUNNERY_L, "2");
        pilot.text(Skill.GUNNERY_M, "3");
        pilot.text(Skill.GUNNERY_B, "5");

        assertTrue(form.apply());

        assertEquals(2, mek.getCrew().getGunneryL(0));
        assertEquals(3, mek.getCrew().getGunneryM(0));
        assertEquals(5, mek.getCrew().getGunneryB(0));
        assertEquals(3, mek.getCrew().getGunnery(0));
    }

    @Test
    void aRandomGenderKeepsTheCrewsGender() {
        Entity mek = mek(local, "Hunchback");
        mek.getCrew().setGender(Gender.FEMALE, 0);
        UnitConfigForm form = form(mek);

        assertTrue(form.apply());

        assertEquals(Gender.FEMALE, mek.getCrew().getGender(0));
    }

    @Test
    void proneAndHullDownExcludeEachOther() {
        Entity mek = mek(local, "Hunchback");
        UnitConfigForm form = form(mek);
        form.prone(true);
        form.hullDown(true);
        assertFalse(form.prone());
        assertTrue(form.hullDown());
        form.prone(true);
        assertFalse(form.hullDown());

        assertTrue(form.apply());

        assertTrue(mek.isProne());
        assertFalse(mek.isHullDown());
    }

    @Test
    void theNextUnitIsTheSameOwnersAndWraps() {
        Entity first = mek(local, "Hunchback");
        mek(enemy, "Atlas");
        Entity third = mek(local, "Locust");
        UnitConfigForm form = form(first);

        assertTrue(form.hasNext());
        assertTrue(form.hasPrevious());
        assertSame(third, form.next(true));
        assertSame(third, form.next(false));
    }

    @Test
    void aLoneUnitHasNoNext() {
        Entity only = mek(local, "Hunchback");
        mek(enemy, "Atlas");
        option(OptionsConstants.BASE_BLIND_DROP, true);
        UnitConfigForm form = form(only);

        assertFalse(form.hasNext());
        assertNull(form.next(true));
    }

    @Test
    void severalUnitsShareOnlyTheirDeployment() {
        Entity first = mek(local, "Hunchback");
        Entity second = mek(local, "Locust");
        first.getCrew().setGunnery(4, 0);
        UnitConfigForm form = form(first, second);

        assertTrue(form.multiple());
        assertFalse(form.showsCrew());
        assertTrue(form.members().isEmpty());
        form.zone(Board.START_E);
        form.round(2);

        assertTrue(form.apply());

        assertEquals(Board.START_E, second.getStartingPos(false));
        assertEquals(2, second.getDeployRound());
        assertEquals(4, first.getCrew().getGunnery(0));
    }

    @Test
    void theAnyBoxIsShownFromOneAndWrittenFromZero() {
        Entity mek = mek(local, "Hunchback");
        UnitConfigForm form = form(mek);
        form.zone(Board.START_ANY);
        form.anyBox(0, 5);
        form.anyBox(1, 6);
        form.anyBox(2, 3);
        form.anyBox(3, 2);

        assertTrue(form.apply());

        assertEquals(2, mek.getStartingAnyNWx(false));
        assertEquals(1, mek.getStartingAnyNWy(false));
        assertEquals(4, mek.getStartingAnySEx(false));
        assertEquals(5, mek.getStartingAnySEy(false));
    }

    @Test
    void theWidthIsOnlyEnteredForEdgeZones() {
        Entity mek = mek(local, "Hunchback");
        UnitConfigForm form = form(mek);
        form.zone(Board.START_NONE);
        assertFalse(form.enabled(Field.OFFSET));
        form.text(Field.OFFSET, "x");
        assertTrue(form.valid());
        form.zone(Board.START_N);
        assertTrue(form.enabled(Field.OFFSET));
        assertFalse(form.valid());
    }

    @Test
    void theCommandBonusesShowWithTheirOptions() {
        Entity mek = mek(local, "Hunchback");
        assertFalse(form(mek).shows(Field.INIT));
        option(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE, true);
        UnitConfigForm form = form(mek);
        assertTrue(form.shows(Field.INIT));
        form.text(Field.INIT, "2");
        form.commander(true);

        assertTrue(form.apply());

        assertEquals(2, mek.getCrew().getInitBonus());
        assertTrue(mek.isCommander());
    }
}
