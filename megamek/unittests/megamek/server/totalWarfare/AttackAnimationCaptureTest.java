/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import megamek.common.HitData;
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
     * consequence of the attack, a fuel tank's explosion, brings down.
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
            capture.uncredited(() -> capture.collapse(BoardLocation.of(new Coords(5, 6), 0)));
        }
        var sent = new ArrayList<Packet>();
        capture.flush(sent::add, (recipient, packet) -> sent.add(packet));
        var collapses = sent.stream().map(packet -> ((ResolvedAttack) packet.getObject(0)).collapses()).toList();
        assertEquals(List.of(List.of(BoardLocation.of(new Coords(5, 5), 0)), List.of()), collapses,
              "The scattered shell takes its own hex; the dud and the tank's explosion take nothing");
    }
}
