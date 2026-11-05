/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.Configuration;
import megamek.common.CriticalSlot;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.NarcPod;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Aero;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import megamek.common.units.Tank;

/**
 * Real units for the unit panel's tests (unit panel design 14 U3), in a board fixture's game: the local player's Atlas
 * AS7-K and Centurion CN11-OD in the approved mockup's damage states, a Triskelion with a shield on its right arm, a
 * VTOL, battle armor, a foot platoon, the Shilone SL-17, a Union and an Aegis, a destroyed Archer, and an enemy
 * Demolisher Heavy Tank (Mk. I); plus a sensor contact's status. The board source captures them as the HUD's status
 * and each unit's record with the sheet open.
 */
final class GpuUnitPanelFixture implements AutoCloseable {
    static final int ATLAS = 101;
    static final int CENTURION = 102;
    static final int TRIPOD = 103;
    static final int TANK = 104;
    static final int VTOL = 105;
    static final int BATTLE_ARMOR = 106;
    static final int INFANTRY = 107;
    static final int FIGHTER = 108;
    static final int DROPSHIP = 109;
    static final int WARSHIP = 110;
    static final int WRECK = 111;
    static final int CONTACT = 112;
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final String OFFICIAL = "../../mm-data/data/mekfiles/";

    final GpuBoardFixture board;
    private final GpuBoardSource source;
    private final Map<Integer, GpuBoardSource.Frame> frames = new HashMap<>();
    private final File originalData;

    private GpuUnitPanelFixture(GpuBoardFixture board, GpuBoardSource source, File originalData) {
        this.board = board;
        this.source = source;
        this.originalData = originalData;
    }

    /**
     * The fixture, with every unit added and damaged on the EDT. The paperdoll geometry is read from mm-data when the
     * sibling checkout has it (unit tests run without staging, so the staged copy may be stale).
     */
    static GpuUnitPanelFixture create() throws Exception {
        File originalData = Configuration.dataDir();
        Configuration.setDataDir(null);
        File images = new File("../../mm-data/data/images");
        if (new File(images, "paperdolls/biped-armor.json").isFile()) {
            Configuration.setImagesDir(images);
        }
        GpuBoardFixture board = GpuBoardFixture.create();
        Client client = mock(Client.class);
        ClientGUI gui = mock(ClientGUI.class);
        when(client.getGame()).thenReturn(board.game);
        when(client.getLocalPlayer()).thenReturn(board.player);
        when(gui.getClient()).thenReturn(client);
        CommonMenuBar menu = mock(CommonMenuBar.class);
        when(menu.getComponents()).thenReturn(new Component[0]);
        when(gui.getMenuBar()).thenReturn(menu);
        GpuBoardSource source = GpuTestSupport.onSwing(() -> {
            BoardClientState view = spy(board.view);
            doReturn(gui).when(view).getClientgui();
            board.source.close();
            return new GpuBoardSource(view, () -> board.panel);
        });
        GpuUnitPanelFixture fixture = new GpuUnitPanelFixture(board, source, originalData);
        GpuTestSupport.onSwing(() -> {
            fixture.addUnits();
            return null;
        });
        return fixture;
    }

    private void addUnits() throws Exception {
        board.game.setRoundCount(4);
        Player enemy = new Player(2, "Enemy");
        enemy.setTeam(2);
        board.game.addPlayer(enemy.getId(), enemy);
        Player own = board.player;

        Entity atlas = add(official("meks/3050U/Atlas AS7-K.mtf"), ATLAS, own, new Coords(4, 5));
        atlas.setArmor(0, Mek.LOC_CENTER_TORSO);
        atlas.setInternal(24, Mek.LOC_CENTER_TORSO);
        atlas.setArmor(29, Mek.LOC_RIGHT_TORSO);
        atlas.setArmor(4, Mek.LOC_RIGHT_TORSO, true);
        atlas.setArmor(18, Mek.LOC_LEFT_TORSO);
        atlas.setArmor(22, Mek.LOC_RIGHT_ARM);
        atlas.setArmor(12, Mek.LOC_RIGHT_LEG);
        atlas.setArmor(38, Mek.LOC_LEFT_LEG);
        atlas.destroyLocation(Mek.LOC_LEFT_ARM);
        atlas.applyDamage();
        atlas.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_ENGINE, Mek.LOC_CENTER_TORSO, 1);
        atlas.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.ACTUATOR_LOWER_LEG, Mek.LOC_RIGHT_LEG, 1);
        CriticalSlot rearLaser = atlas.getCritical(Mek.LOC_CENTER_TORSO, 11);
        rearLaser.setHit(true);
        rearLaser.getMount().setHit(true);
        rearLaser.getMount().setDestroyed(true);
        ((AmmoMounted) atlas.getCritical(Mek.LOC_LEFT_TORSO, 8).getMount()).setShotsLeft(4);
        List<AmmoMounted> gauss = new ArrayList<>();
        for (int slot = 0; slot < atlas.getNumberOfCriticalSlots(Mek.LOC_RIGHT_ARM); slot++) {
            CriticalSlot critical = atlas.getCritical(Mek.LOC_RIGHT_ARM, slot);
            if (critical != null && critical.getMount() instanceof AmmoMounted bin) {
                gauss.add(bin);
            }
        }
        gauss.getLast().setShotsLeft(5);
        atlas.getCrew().setHits(1, 0);
        atlas.attachNarcPod(new NarcPod(enemy.getTeam(), Mek.LOC_CENTER_TORSO));
        // The next round attaches the pod, as the server's round start does.
        atlas.newRound(2);
        atlas.heat = 6;
        moved(atlas, 2, 1);

        Entity centurion = add(official("meks/3145/Davion/Centurion CN11-OD.mtf"), CENTURION, own, new Coords(6, 5));
        centurion.setArmor(15, Mek.LOC_CENTER_TORSO);
        centurion.setArmor(9, Mek.LOC_RIGHT_TORSO);
        centurion.setArmor(14, Mek.LOC_LEFT_LEG);
        ((MiscMounted) centurion.getCritical(Mek.LOC_LEFT_ARM, 4).getMount()).setDamageTaken(7);
        centurion.setLocationStatus(Mek.LOC_LEFT_LEG, ILocationExposureStatus.BREACHED);
        for (int slot = 0; slot < centurion.getNumberOfCriticalSlots(Mek.LOC_LEFT_LEG); slot++) {
            if (centurion.getCritical(Mek.LOC_LEFT_LEG, slot) != null) {
                centurion.getCritical(Mek.LOC_LEFT_LEG, slot).setBreached(true);
            }
        }
        centurion.heat = 3;
        moved(centurion, 4, 0);

        Entity tripod = official("meks/XTRs/Republic III/Triskelion TRK-4V.mtf");
        tripod.addEquipment(EquipmentType.get("ISSmallShield"), Mek.LOC_RIGHT_ARM);
        add(tripod, TRIPOD, own, new Coords(8, 5));

        Entity tank = add(official("vehicles/3039u/Demolisher Heavy Tank (Mk. I).blk"), TANK, enemy,
              new Coords(5, 2));
        tank.setArmor(14, Tank.LOC_FRONT);
        tank.setArmor(27, Tank.LOC_LEFT);
        tank.setArmor(0, Tank.LOC_RIGHT);
        tank.setInternal(5, Tank.LOC_RIGHT);
        tank.setArmor(25, ((Tank) tank).getLocTurret());
        ((Tank) tank).addMovementDamage(2);
        moved(tank, 3, 4);

        Entity vtol = add(unit("SOAR VTOL.blk"), VTOL, own, new Coords(3, 8));
        ((Tank) vtol).setMotiveDamage(1);
        Entity armor = add(unit("Elemental BA [Laser] (Sqd5).blk"), BATTLE_ARMOR, own, new Coords(5, 8));
        armor.destroyLocation(2);
        armor.applyDamage();
        add(unit("Foot Platoon (AFFS) (Laser 3067+).blk"), INFANTRY, own, new Coords(7, 8));
        Aero fighter = (Aero) add(official("fighters/TRO3039u/Shilone SL-17.blk"), FIGHTER, own, new Coords(9, 8));
        fighter.setArmor(38, Aero.LOC_NOSE);
        fighter.setArmor(21, Aero.LOC_RIGHT_WING);
        add(unit("Union (3055).blk"), DROPSHIP, own, null);
        add(unit("Aegis Heavy Cruiser (2372).blk"), WARSHIP, own, null);
        Entity wreck = add(unit("Archer ARC-2R.mtf"), WRECK, own, new Coords(11, 5));
        wreck.destroyLocation(Mek.LOC_CENTER_TORSO);
        wreck.applyDamage();
        wreck.setDestroyed(true);
    }

    /**
     * Adds a unit to the game for {@code owner}, deployed at {@code position} (null: not deployed), with its battle
     * value before any damage as its initial one, at the start of the first round, which sets its sensors and active
     * heat sinks as a game does.
     */
    private Entity add(Entity entity, int id, Player owner, Coords position) {
        entity.setId(id);
        entity.setOwner(owner);
        entity.setPosition(position);
        entity.setDeployed(position != null);
        board.game.addEntity(entity, false);
        entity.setInitialBV(entity.calculateBattleValue(false, false));
        entity.newRound(1);
        return entity;
    }

    /** The unit walked {@code hexes} hexes this round and faces {@code facing}. */
    private static void moved(Entity entity, int hexes, int facing) {
        entity.moved = EntityMovementType.MOVE_WALK;
        entity.delta_distance = hexes;
        entity.mpUsed = hexes;
        entity.setFacing(facing);
    }

    /** EDT edits of the fixture's units, captured by the next {@link #frame}. */
    void edit(Runnable change) throws Exception {
        GpuTestSupport.onSwing(() -> {
            change.run();
            frames.clear();
            return null;
        });
    }

    Entity entity(int id) {
        return board.game.getEntity(id);
    }

    /**
     * The HUD's frame with {@code unit} on the card: the game's status, with the sensor contact appended, and the
     * unit's record with the sheet open (the contact's record is empty).
     */
    GpuBoardSource.Frame frame(int unit) throws Exception {
        if (!frames.containsKey(unit)) {
            source.setCardUnit(unit == CONTACT ? Entity.NONE : unit);
            source.record().setSheetOpen(true);
            SwingUtilities.invokeAndWait(source::refresh);
            GpuBoardSource.Frame captured = source.takeFrame();
            List<GpuBattleStatus.UnitStatus> units = new ArrayList<>(captured.status().units());
            units.add(contact());
            GpuBattleStatus.Snapshot status = captured.status();
            frames.put(unit, GpuHudFixtures.frame(new GpuBattleStatus.Snapshot(status.round(), status.phase(),
                  status.myTurn(), status.localPlayerId(), status.actorId(), status.turns(), status.turnIndex(), units,
                  status.initiative(), status.turnOrderHidden()), captured.panels()));
        }
        return frames.get(unit);
    }

    /** A sensor contact's status: only its id, side and hex (GpuBattleStatus's anonymous unit). */
    private static GpuBattleStatus.UnitStatus contact() {
        return new GpuBattleStatus.UnitStatus(CONTACT, GpuBattleStatus.Side.ENEMY, true, "", "", "", 0, "", "", "",
              0, 0, -1, -1, 0, 0, "", 0, "", 0, "", 0, 0, -1, 0, false, false, false, false, Entity.DMG_NONE,
              List.of(), "", new Coords(12, 2), 0, null, List.of(), 0, 0, Player.PLAYER_NONE, List.of());
    }

    /** An official unit of mm-data's mekfiles: the sibling checkout's file, else the staged unit_files.zip entry. */
    static Entity official(String file) throws Exception {
        File local = new File(OFFICIAL + file);
        return local.isFile() ? new MekFileParser(local).getEntity()
              : new MekFileParser(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"), file).getEntity();
    }

    private static Entity unit(String file) throws Exception {
        return new MekFileParser(new File(UNITS + file)).getEntity();
    }

    @Override
    public void close() throws Exception {
        try {
            SwingUtilities.invokeAndWait(source::close);
            board.close();
        } finally {
            Configuration.setImagesDir(null);
            Configuration.setDataDir(originalData);
        }
    }
}
