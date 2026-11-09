/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import megamek.common.HitData;
import megamek.common.NuclearDetonation;
import megamek.common.ResolvedAttack;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;

class AttackAnimationCaptureTest {
    @Test
    void locationsAreCopiedWithoutChangingDamageOrReorderingLaunchAndBoardPackets() {
        var capture = new AttackAnimationCapture();
        var target = new BipedMek();
        target.setId(2);
        target.initializeArmor(20, Mek.LOC_LEFT_ARM);
        target.initializeInternal(10, Mek.LOC_LEFT_ARM);
        var attacker = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var victim = new UnitLocation(2, new Coords(1, 1), 3, 0, 0);
        var first = new ResolvedAttack(new UUID(1, 1), ResolvedAttack.Kind.SHOT, attacker, victim,
              Targetable.TYPE_ENTITY, 0, "LRM20", Mek.LOC_LEFT_ARM, true);
        capture.attack(first, List.of(7));
        var hit = new HitData(Mek.LOC_LEFT_ARM);
        hit.setAttackerId(1);
        capture.hit(target, hit, 5);
        hit.setLocation(Mek.LOC_RIGHT_TORSO);
        capture.hit(target, hit, 5);
        hit.makeFallDamage(true);
        capture.hit(target, hit, 10);
        hit.makeFallDamage(false);
        var update = new Packet(PacketCommand.ENTITY_UPDATE, "board checkpoint");
        capture.packet(null, update);
        var second = new ResolvedAttack(new UUID(1, 2), ResolvedAttack.Kind.SHOT, attacker, victim,
              Targetable.TYPE_ENTITY, 1, "Laser", Mek.LOC_LEFT_ARM, true);
        capture.attack(second, List.of(7));
        hit.setLocation(Mek.LOC_HEAD);
        capture.hit(target, hit, 3);
        hit.setLocation(Mek.LOC_CENTER_TORSO);

        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> { assertEquals(7, recipient); sent.add(packet); });
        assertEquals(List.of(new ResolvedAttack.Impact("LA", false, 5), new ResolvedAttack.Impact("RT", false, 5)),
              ((ResolvedAttack) sent.getFirst().getObject(0)).impacts());
        assertEquals(update, sent.get(1));
        assertEquals(List.of(new ResolvedAttack.Impact("HD", false, 3)), ((ResolvedAttack) sent.get(2).getObject(0)).impacts());
        assertTrue(first.impacts().isEmpty(), "The previously captured immutable result is unchanged");
        assertEquals(20, target.getArmor(Mek.LOC_LEFT_ARM));
        assertEquals(10, target.getInternal(Mek.LOC_LEFT_ARM));
    }

    @Test
    void aLaterMissCannotAttributeItsSecondaryDamageToAnEarlierHit() {
        var capture = new AttackAnimationCapture();
        var target = new BipedMek();
        target.setId(2);
        var attacker = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var victim = new UnitLocation(2, new Coords(1, 1), 3, 0, 0);
        capture.attack(new ResolvedAttack(new UUID(1, 1), ResolvedAttack.Kind.SHOT, attacker, victim,
              Targetable.TYPE_ENTITY, 0, "Laser", Mek.LOC_LEFT_ARM, true), List.of(7));
        capture.attack(new ResolvedAttack(new UUID(1, 2), ResolvedAttack.Kind.SHOT, attacker, victim,
              Targetable.TYPE_ENTITY, 1, "Laser", Mek.LOC_LEFT_ARM, false), List.of(7));
        capture.hit(target, new HitData(Mek.LOC_HEAD), 5);
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        assertTrue(sent.stream().map(packet -> (ResolvedAttack) packet.getObject(0)).allMatch(attack -> attack.impacts().isEmpty()));
    }

    /** A structure hex brought down goes to the attack being resolved: past the deaths it caused, never to a miss. */
    @Test
    void aCollapseGoesToTheHitBeingResolved() {
        var capture = new AttackAnimationCapture();
        var attacker = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var building = new UnitLocation(Entity.NONE, new Coords(1, 1), 0, 0, 0);
        capture.attack(new ResolvedAttack(new UUID(1, 1), ResolvedAttack.Kind.SHOT, attacker, building,
              Targetable.TYPE_BUILDING, 0, "AC20", Entity.LOC_NONE, true), List.of(7));
        capture.attack(new ResolvedAttack(new UUID(1, 2), ResolvedAttack.Kind.DEATH, attacker, attacker,
              Targetable.TYPE_ENTITY, -1, "", Entity.LOC_NONE, true), List.of(7));
        capture.collapse(BoardLocation.of(new Coords(1, 1), 0));
        capture.attack(new ResolvedAttack(new UUID(1, 3), ResolvedAttack.Kind.SHOT, attacker, building,
              Targetable.TYPE_BUILDING, 1, "Laser", Entity.LOC_NONE, false), List.of(7));
        capture.collapse(BoardLocation.of(new Coords(2, 2), 0));
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        var collapses = sent.stream().map(packet -> ((ResolvedAttack) packet.getObject(0)).collapses()).toList();
        assertEquals(List.of(List.of(BoardLocation.of(new Coords(1, 1), 0)), List.of(), List.of()), collapses);
    }

    /**
     * A shell that missed but landed and went off, scattered, takes what it brings down; a dud does not, nor does what a
     * consequence of the attack, a fuel tank's explosion, brings down: that is the explosion's.
     */
    @Test
    void aShellThatLandedTakesWhatItBringsDownButNotWhatItsConsequencesDo() {
        var capture = new AttackAnimationCapture();
        var launcher = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var landing = new UnitLocation(Entity.NONE, new Coords(5, 5), 0, 0, 0);
        UUID[] ids = { new UUID(2, 1), new UUID(2, 2) };
        for (int index = 0; index < 2; index++) {
            var shell = new ResolvedAttack.Shot("", java.util.Set.of(), true, false, 1, 0, true, null, launcher, landing,
                  false, 25, false, false, false, null, index == 0 ? 25 : 0);
            capture.attack(new ResolvedAttack(ids[index], ResolvedAttack.Kind.SHOT, launcher, landing,
                  Targetable.TYPE_HEX_ARTILLERY, 0, "Long Tom", Entity.LOC_NONE, false, null, shell), List.of(7));
            capture.collapse(BoardLocation.of(new Coords(5, 5), 0));
            explode(capture, explosion(new UUID(2, 3 + index), new Coords(5, 6)),
                  () -> capture.collapse(BoardLocation.of(new Coords(5, 6), 0)));
        }
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        var collapses = sent.stream().filter(packet -> packet.command() == PacketCommand.ENTITY_ATTACK_RESOLVED)
              .map(packet -> ((ResolvedAttack) packet.getObject(0)).collapses()).toList();
        assertEquals(List.of(List.of(BoardLocation.of(new Coords(5, 5), 0)), List.of()), collapses,
              "The scattered shell takes its own hex; the dud and the tank's explosion take nothing");
        assertEquals(List.of(List.of(BoardLocation.of(new Coords(5, 6), 0)), List.of(BoardLocation.of(new Coords(5, 6), 0))),
              completions(sent).stream().map(NuclearDetonation::collapses).toList(), "Each explosion names its own");
    }

    /** A scheduled strike's completion names everything it brings down, the explosions it sets off too; the order stays. */
    @Test
    void aStrikesCompletionNamesEverythingItBringsDown() {
        var capture = new AttackAnimationCapture();
        var strike = new NuclearDetonation(new UUID(3, 1), 0, new Coords(4, 4), 0, 1000, 23, 86, 1);
        capture.packet(null, new Packet(PacketCommand.NUCLEAR_DETONATION, strike, false));
        capture.packet(null, new Packet(PacketCommand.ENTITY_UPDATE));
        capture.collapse(BoardLocation.of(new Coords(4, 5), 0));
        // A fuel tank the strike sets off is the strike's: never sent, what it brings down falls with the front.
        explode(capture, explosion(new UUID(3, 2), new Coords(8, 9)),
              () -> capture.collapse(BoardLocation.of(new Coords(9, 9), 0)));
        capture.collapse(BoardLocation.of(new Coords(4, 5), 0));
        capture.packet(null, new Packet(PacketCommand.NUCLEAR_DETONATION, strike, true));
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        assertEquals(List.of(PacketCommand.NUCLEAR_DETONATION, PacketCommand.ENTITY_UPDATE, PacketCommand.NUCLEAR_DETONATION),
              sent.stream().map(Packet::command).toList());
        assertEquals(List.of(), ((NuclearDetonation) sent.getFirst().getObject(0)).collapses());
        assertEquals(true, sent.getLast().getObject(1));
        assertEquals(List.of(BoardLocation.of(new Coords(4, 5), 0), BoardLocation.of(new Coords(9, 9), 0)),
              ((NuclearDetonation) sent.getLast().getObject(0)).collapses());
    }

    /**
     * A fuel tank an AC/20 bursts: the explosion takes the tank and what it brings down, the attack neither. A tank it
     * sets off elsewhere is its own explosion, sent and completed inside the first; the first tank's next hex going off
     * again from the same centre is the first explosion, not another; an explosion that brings nothing down is not sent.
     */
    @Test
    void anExplosionTakesWhatItBringsDownAndEachChainedTankIsItsOwn() {
        var capture = new AttackAnimationCapture();
        var attacker = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var tank = new Coords(5, 5);
        capture.attack(new ResolvedAttack(new UUID(4, 1), ResolvedAttack.Kind.SHOT, attacker,
              new UnitLocation(Entity.NONE, tank, 0, 0, 0), Targetable.TYPE_BUILDING, 0, "AC20", Entity.LOC_NONE, true),
              List.of(7));
        var first = explosion(new UUID(4, 2), tank);
        var chained = explosion(new UUID(4, 3), new Coords(7, 5));
        explode(capture, first, () -> {
            capture.collapse(BoardLocation.of(tank, 0));
            explode(capture, chained, () -> {
                capture.collapse(BoardLocation.of(new Coords(7, 5), 0));
                explode(capture, explosion(new UUID(4, 4), tank),
                      () -> capture.collapse(BoardLocation.of(new Coords(5, 6), 0)));
            });
            capture.collapse(BoardLocation.of(new Coords(4, 5), 0));
        });
        explode(capture, explosion(new UUID(4, 5), new Coords(9, 9)), () -> { });
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        assertEquals(List.of(), ((ResolvedAttack) sent.getFirst().getObject(0)).collapses(), "The AC/20 takes nothing");
        assertEquals(List.of(first.id(), chained.id(), chained.id(), first.id()), sent.stream().skip(1)
              .map(packet -> ((NuclearDetonation) packet.getObject(0)).id()).toList(), "Nested in order, none folded or empty");
        assertEquals(List.of(List.of(BoardLocation.of(new Coords(7, 5), 0), BoardLocation.of(new Coords(5, 6), 0)),
              List.of(BoardLocation.of(tank, 0), BoardLocation.of(new Coords(4, 5), 0))),
              completions(sent).stream().map(NuclearDetonation::collapses).toList(), "Each hex named once, by the innermost");
    }

    /** A fuel tank a nuclear round sets off is the round's: never sent, and the round takes what it brings down. */
    @Test
    void aNuclearRoundTakesTheTanksItSetsOff() {
        var capture = new AttackAnimationCapture();
        var launcher = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var landing = new UnitLocation(Entity.NONE, new Coords(5, 5), 0, 0, 0);
        var shell = new ResolvedAttack.Shot("", java.util.Set.of(), true, false, 1, 0, true, null, launcher, landing,
              false, 0, false, false, false, null, 100, false, 1);
        capture.attack(new ResolvedAttack(new UUID(5, 1), ResolvedAttack.Kind.SHOT, launcher, landing,
              Targetable.TYPE_HEX_ARTILLERY, 0, "Davy Crockett-M", Entity.LOC_NONE, true, null, shell), List.of(7));
        explode(capture, explosion(new UUID(5, 2), new Coords(6, 5)),
              () -> capture.collapse(BoardLocation.of(new Coords(6, 5), 0)));
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        assertEquals(List.of(PacketCommand.ENTITY_ATTACK_RESOLVED), sent.stream().map(Packet::command).toList());
        assertEquals(List.of(BoardLocation.of(new Coords(6, 5), 0)), ((ResolvedAttack) sent.getFirst().getObject(0)).collapses());
    }

    private static NuclearDetonation explosion(UUID id, Coords at) {
        return NuclearDetonation.explosion(id, 0, at, 0, 80, 8);
    }

    /** As TWGameManager#detonation sends it: the blast between the explosion's start and completion. */
    private static void explode(AttackAnimationCapture capture, NuclearDetonation explosion, Runnable blast) {
        capture.packet(null, new Packet(PacketCommand.NUCLEAR_DETONATION, explosion, false));
        blast.run();
        capture.packet(null, new Packet(PacketCommand.NUCLEAR_DETONATION, explosion, true));
    }

    private static List<NuclearDetonation> completions(List<Packet> sent) {
        return sent.stream().filter(packet -> packet.command() == PacketCommand.NUCLEAR_DETONATION
              && Boolean.TRUE.equals(packet.getObject(1))).map(packet -> (NuclearDetonation) packet.getObject(0)).toList();
    }

    /**
     * Woods an attack's damage thinned are named on it, as what it brings down is; a miss names none, and what an
     * explosion it sets off clears is the explosion's, whose front fells its trees.
     */
    @Test
    void theWoodsAnAttackClearsAreNamedOnIt() {
        var capture = new AttackAnimationCapture();
        var attacker = new UnitLocation(1, new Coords(1, 2), 0, 0, 0);
        var woods = new UnitLocation(Entity.NONE, new Coords(5, 5), 0, 0, 0);
        capture.attack(new ResolvedAttack(new UUID(6, 1), ResolvedAttack.Kind.SHOT, attacker, woods,
              Targetable.TYPE_HEX_CLEAR, 0, "AC20", Entity.LOC_NONE, true), List.of(7));
        capture.clear(BoardLocation.of(new Coords(5, 5), 0));
        explode(capture, explosion(new UUID(6, 2), new Coords(5, 6)), () -> capture.clear(BoardLocation.of(new Coords(5, 7), 0)));
        capture.clear(BoardLocation.of(new Coords(5, 5), 0));
        capture.attack(new ResolvedAttack(new UUID(6, 3), ResolvedAttack.Kind.SHOT, attacker, woods,
              Targetable.TYPE_HEX_CLEAR, 1, "Laser", Entity.LOC_NONE, false), List.of(7));
        capture.clear(BoardLocation.of(new Coords(6, 6), 0));
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        assertEquals(List.of(List.of(BoardLocation.of(new Coords(5, 5), 0)), List.of()), sent.stream()
              .filter(packet -> packet.command() == PacketCommand.ENTITY_ATTACK_RESOLVED)
              .map(packet -> ((ResolvedAttack) packet.getObject(0)).cleared()).toList());
    }
}
