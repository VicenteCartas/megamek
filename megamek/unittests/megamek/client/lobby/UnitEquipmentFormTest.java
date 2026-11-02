/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Vector;

import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.UnitEquipmentForm.AmmoBin;
import megamek.client.lobby.UnitEquipmentForm.BombBay;
import megamek.client.lobby.UnitEquipmentForm.InfantryArmor;
import megamek.client.lobby.UnitEquipmentForm.Toggle;
import megamek.common.Player;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.enums.BombType.BombTypeEnum;
import megamek.common.game.Game;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.common.units.Mek;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnitEquipmentFormTest {
    private Game game;
    private Player local;
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
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setMedium(MapSettings.MEDIUM_GROUND);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getSkillGenerator()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE.getGenerator());
        when(client.getEntitiesVector()).thenAnswer(invocation -> new Vector<>(game.getEntitiesVector()));
    }

    private Entity unit(String file) {
        Entity unit;
        try {
            unit = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        } catch (EntityLoadingException e) {
            throw new IllegalStateException(e);
        }
        unit.setGame(game);
        unit.setCrew(new Crew(unit.defaultCrewType()));
        unit.setOwner(local);
        game.addEntity(unit);
        return unit;
    }

    private UnitEquipmentForm form(Entity unit) {
        return UnitEquipmentForm.of(unit, client, true).orElseThrow();
    }

    private static AmmoBin changeable(UnitEquipmentForm form) {
        return form.ammo().stream().filter(bin -> bin.munitions().size() > 1).findFirst().orElseThrow();
    }

    @Test
    void aMunitionChangeReachesTheBin() {
        Entity archer = unit("Archer ARC-2R.mtf");
        UnitEquipmentForm form = form(archer);
        AmmoBin bin = changeable(form);
        int other = (bin.munition() == 0) ? 1 : 0;
        bin.munition(other);
        String chosen = bin.selected().getInternalName();
        assertEquals(bin.selected().getShots(), bin.shots(), "a new munition fills the bin");
        bin.shots(3);

        assertTrue(form.apply(archer, client).isEmpty());

        AmmoMounted mount = (AmmoMounted) archer.getEquipment(bin.number());
        assertEquals(chosen, mount.getType().getInternalName());
        assertEquals(3, mount.getBaseShotsLeft());
    }

    @Test
    void aHalfLoadHalvesTheShots() {
        Entity archer = unit("Archer ARC-2R.mtf");
        UnitEquipmentForm form = form(archer);
        AmmoBin bin = form.ammo().get(0);
        int full = bin.shots();
        bin.half(true);
        assertEquals(full / 2, bin.shots());

        form.apply(archer, client);

        AmmoMounted mount = (AmmoMounted) archer.getEquipment(bin.number());
        assertTrue(mount.isHalfLoadAmmo());
        assertEquals(full / 2, mount.getBaseShotsLeft());
    }

    @Test
    void theFeedsNameThePendingMunition() {
        Entity archer = unit("Archer ARC-2R.mtf");
        UnitEquipmentForm form = form(archer);
        assertFalse(form.feeds().isEmpty(), "the launchers are fed");
        AmmoBin bin = changeable(form);
        bin.munition((bin.munition() == 0) ? 1 : 0);
        String pending = bin.selected().getName();
        assertTrue(form.feeds().stream().anyMatch(feed -> feed.bins().stream()
              .anyMatch(label -> label.endsWith(pending))), "a feed names the bin's new munition");
    }

    @Test
    void aWeaponFedFromItsSecondBinKeepsItOnAnUntouchedOk() {
        Entity archer = unit("Archer ARC-2R.mtf");
        UnitEquipmentForm.WeaponFeed feed = form(archer).feeds().stream().filter(row -> row.bins().size() > 1)
              .findFirst().orElseThrow();
        WeaponMounted weapon = (WeaponMounted) archer.getEquipment(feedNumber(archer, feed));
        AmmoMounted second = (AmmoMounted) archer.getAmmo().stream()
              .filter(bin -> AmmoType.canSwitchToAmmo(weapon, bin.getType())).skip(1).findFirst().orElseThrow();
        archer.loadWeapon(weapon, second);

        UnitEquipmentForm form = form(archer);
        UnitEquipmentForm.WeaponFeed read = form.feeds().stream()
              .filter(row -> row.label().equals(feed.label())).findFirst().orElseThrow();
        assertEquals(1, read.index(), "the list opens on the bin feeding the weapon");
        form.apply(archer, client);

        assertSame(second, weapon.getLinked());
    }

    private static int feedNumber(Entity unit, UnitEquipmentForm.WeaponFeed feed) {
        return unit.getWeaponList().stream()
              .filter(weapon -> feed.label().equals("(%s) %s".formatted(unit.getLocationAbbr(weapon.getLocation()),
                    weapon.getShortName())))
              .mapToInt(unit::getEquipmentNum).findFirst().orElseThrow();
    }

    @Test
    void aC3SlaveKeepsItsMasterAndAFreeSlaveStaysFreeOnAnUntouchedOk() throws Exception {
        Entity master = unit("Atlas AS7-D.mtf");
        master.addEquipment(EquipmentType.get("ISC3MasterUnit"), Entity.LOC_NONE);
        Entity slave = unit("Archer ARC-2R.mtf");
        slave.addEquipment(EquipmentType.get("ISC3SlaveUnit"), Entity.LOC_NONE);
        Entity free = unit("Archer ARC-2R.mtf");
        free.addEquipment(EquipmentType.get("ISC3SlaveUnit"), Entity.LOC_NONE);
        when(client.getEntity(anyInt()))
              .thenAnswer(invocation -> game.getEntity(invocation.getArgument(0)));
        slave.setC3Master(master, true);
        assertTrue(slave.C3MasterIs(master));

        UnitEquipmentForm linked = form(slave);
        assertEquals(master.getId(), linked.c3().get(linked.c3Index()).id(), "the list opens on the master");
        linked.apply(slave, client);
        assertTrue(slave.C3MasterIs(master), "an untouched OK keeps the link");

        UnitEquipmentForm unlinked = form(free);
        assertEquals(0, unlinked.c3Index());
        assertEquals(Entity.NONE, unlinked.c3().get(0).id(), "a free slave opens on Not connected");
        unlinked.apply(free, client);
        assertNull(free.getC3Master(), "and an untouched OK does not join the first network");

        UnitEquipmentForm joining = form(free);
        joining.c3Index(joining.c3().stream().map(UnitEquipmentForm.C3Choice::id).toList().indexOf(master.getId()));
        joining.apply(free, client);
        assertTrue(free.C3MasterIs(master), "choosing the master joins it");
    }

    @Test
    void twoEcmSuitesInUseAskWhichStaysOn() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");
        atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Entity.LOC_NONE);
        atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Entity.LOC_NONE);
        UnitEquipmentForm form = form(atlas);
        List<UnitEquipmentForm.ModeRow> inUse = form.ecmInUse();
        assertEquals(2, inUse.size(), "both suites start in their ECM mode");
        assertTrue(form.ecmDefault().isPresent(), "OK keeps one unless the player chooses");

        UnitEquipmentForm.ModeRow second = inUse.get(1);
        form.keepEcm(second);
        assertTrue(form.ecmInUse().isEmpty());
        assertEquals(Mounted.MODE_OFF, inUse.get(0).mode());
        form.apply(atlas, client);

        List<MiscMounted> suites = atlas.getMisc().stream()
              .filter(mount -> mount.getType().hasFlag(MiscType.F_ECM)).toList();
        assertEquals(Mounted.MODE_OFF, startMode(suites.get(0)));
        assertEquals(second.mode(), startMode(suites.get(1)), "the chosen suite stays on");

        UnitEquipmentForm.ModeRow off = inUse.get(0);
        off.index(off.choices().indexOf(MiscType.MODE_ECM));
        assertEquals(2, form.ecmInUse().size(), "switching the other back on asks again rather than choosing");
    }

    /** The mode the suite starts the game in: its pending mode, which the game switches to, or else its current. */
    private static String startMode(Mounted<?> suite) {
        return suite.pendingMode().equals(Mounted.MODE_NONE) ? suite.curMode().getName()
              : suite.pendingMode().getName();
    }

    @Test
    void autoEjectIsWrittenAsTheSwingCheckBoxReads() {
        Entity atlas = unit("Atlas AS7-D.mtf");
        UnitEquipmentForm form = form(atlas);
        assertTrue(form.shows(Toggle.AUTO_EJECT));
        form.value(Toggle.AUTO_EJECT, true);

        form.apply(atlas, client);

        assertFalse(((Mek) atlas).isAutoEject(), "the box disables automatic ejection");
    }

    @Test
    void theBombsStayWithinThePoints() {
        Entity cheetah = unit("Cheetah F-11.blk");
        UnitEquipmentForm form = form(cheetah);
        BombBay external = form.bombs().stream().filter(bombs -> !bombs.internal()).findFirst().orElseThrow();
        external.count(BombTypeEnum.HE, 100);
        int points = external.maxPoints();
        assertEquals(points / BombTypeEnum.HE.getCost(), external.count(BombTypeEnum.HE));
        assertEquals(0, external.max(BombTypeEnum.CLUSTER) - external.count(BombTypeEnum.CLUSTER),
              "no room is left");
        external.count(BombTypeEnum.HE, 2);

        form.apply(cheetah, client);

        assertEquals(2, ((IBomber) cheetah).getExtBombChoices().getCount(BombTypeEnum.HE));
    }

    @Test
    void anApMountMayBeEmptiedAndRearmed() {
        Entity elemental = unit("Elemental BA [Laser] (Sqd5).blk");
        UnitEquipmentForm form = form(elemental);
        assertEquals(1, form.apMounts().size());
        UnitEquipmentForm.ApMount mount = form.apMounts().get(0);
        assertTrue(mount.index() > 0, "it carries a rifle");
        int number = elemental.getEquipmentNum(elemental.getMisc().stream()
              .filter(misc -> misc.getType().hasFlag(megamek.common.equipment.MiscType.F_AP_MOUNT)).findFirst()
              .orElseThrow());
        mount.index(0);
        form.apply(elemental, client);
        assertNull(elemental.getEquipment(number).getLinked());

        UnitEquipmentForm again = form(elemental);
        UnitEquipmentForm.ApMount empty = again.apMounts().get(0);
        assertEquals(0, empty.index());
        empty.index(1);
        String weapon = empty.choices().get(1);
        again.apply(elemental, client);
        Mounted<?> linked = elemental.getEquipment(number).getLinked();
        assertNotNull(linked);
        assertTrue(weapon.contains(linked.getType().getName()) || weapon.contains(linked.getName()), weapon);
    }

    @Test
    void aCustomDivisorMustBeANumber() {
        game.getOptions().getOption(OptionsConstants.ALLOWED_TECH_LEVEL).setValue("Experimental");
        Entity platoon = unit("Foot Platoon (AFFS) (Laser 3067+).blk");
        UnitEquipmentForm form = form(platoon);
        InfantryArmor armor = form.infantryArmor().orElseThrow();
        assertTrue(armor.showsArmor());
        armor.kit(0);
        assertTrue(armor.custom());
        armor.divisor("x");
        assertFalse(form.valid());
        assertFalse(armor.divisorError().isEmpty());
        armor.divisor("2");
        assertTrue(form.valid());
        armor.dest(true);

        form.apply(platoon, client);

        assertEquals(2, ((ConvInfantry) platoon).calcDamageDivisor(), 0.001);
        assertTrue(((ConvInfantry) platoon).hasDEST());
    }

    @Test
    void aDropShipShowsItsBayMunitions() {
        Entity union = unit("Union (3055).blk");
        UnitEquipmentForm form = form(union);
        assertFalse(form.bays().isEmpty());
        assertTrue(form.ammo().isEmpty(), "the bays hold the dropship's ammunition");
        UnitEquipmentForm.BayRow row = form.bays().get(0);
        int shots = row.shots(0);
        assertTrue(row.used() > 0);
        row.shots(0, Math.max(0, shots - 1));
        assertTrue(form.valid());
    }

    @Test
    void theBayEditsOneUnitsEquipment() {
        Entity archer = unit("Archer ARC-2R.mtf");
        Entity atlas = unit("Atlas AS7-D.mtf");
        assertTrue(new UnitConfigForm(client, client, List.of(archer), true, null).equipment().isPresent());
        assertTrue(new UnitConfigForm(client, client, List.of(archer, atlas), true, null).equipment().isEmpty(),
              "the Swing dialog applies no equipment to several units");

        UnitConfigForm bay = new UnitConfigForm(client, client, List.of(archer), true, null);
        AmmoBin bin = changeable(bay.equipment().orElseThrow());
        bin.munition((bin.munition() == 0) ? 1 : 0);
        String chosen = bin.selected().getInternalName();
        when(client.getEntity(archer.getId())).thenReturn(archer);
        assertTrue(bay.apply());
        assertEquals(chosen, ((AmmoMounted) archer.getEquipment(bin.number())).getType().getInternalName());
        assertEquals(chosen, bay.equipment().orElseThrow().ammo().stream()
              .filter(row -> row.number() == bin.number()).findFirst().orElseThrow().selected().getInternalName(),
              "the equipment is read afresh after an apply");
    }
}
