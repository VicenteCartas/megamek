/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The unit panel's actions on the local firing turn against a real FiringDisplay (unit panel design 9, 14 U4): a heat
 * sink change goes through the client's menu bar as the Unit Display's control does, so the display clears its unsent
 * attacks; the actor's weapon loads a bin by its number, its queued attack declared again with it; and the field of
 * fire switch leaves the display's actor and weapon selection alone.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuUnitActionsTest {

    @Test
    void aHeatSinkChangeInTheFiringPhaseClearsTheUnsentAttacksAsTheUnitDisplaysControlDoes() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            // The client's menu bar hands every action to the current phase display, which ClientGUI registers there
            CommonMenuBar menu = firing.gui.getMenuBar();
            doAnswer(invocation -> {
                firing.display.actionPerformed(invocation.getArgument(0));
                return null;
            }).when(menu).actionPerformed(any());
            GpuBoardSource source = source(firing);
            try {
                Mek atlas = (Mek) firing.attacker;
                onSwing(() -> {
                    firing.fire(GpuFiringFixture.weapon(atlas, "Medium Laser", Mek.LOC_LEFT_ARM), firing.ahead);
                    return null;
                });
                assertEquals(1, (int) onSwing(() -> firing.display.getAttacks().size()));
                int sinks = atlas.getActiveSinksNextRound();

                source.record().setSystem(atlas.getId(), GpuUnitRecord.HEAT_SINKS, sinks - 1);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(List.of(), onSwing(firing.display::getAttacks), "Cleared, as the control clears them");
                verify(firing.client).sendSinksChange(atlas.getId(), sinks - 1);
                assertEquals(sinks - 1, atlas.getActiveSinksNextRound());
            } finally {
                SwingUtilities.invokeAndWait(source::close);
            }
        }
    }

    @Test
    void theActorLoadsTheSecondOfTwoBinsWithOneLabelAndItsQueuedAttackFiresIt() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            GpuBoardSource source = source(firing);
            try {
                Entity atlas = firing.attacker;
                WeaponMounted lrm = GpuFiringFixture.weapon(atlas, "LRM 20", Mek.LOC_LEFT_TORSO);
                List<AmmoMounted> bins = atlas.getAmmo().stream()
                      .filter(bin -> bin.getType().getAmmoType() == lrm.getType().getAmmoType()).toList();
                assertEquals(List.of("[LT] LRM 20  (6)", "[LT] LRM 20  (6)"), bins.stream()
                      .map(bin -> WeaponDisplayData.formatAmmo(atlas, bin)).toList(), "One label for both bins");
                onSwing(() -> {
                    firing.fire(lrm, firing.ahead);
                    return source.fire().capture(firing.display, null);
                });
                assertSame(bins.get(0), lrm.getLinkedAmmo());

                // The second bin, by its number: the Unit Display's list could never pick it by its label
                source.fire().setAmmo(atlas.getEquipmentNum(lrm), GpuUnitRecord.AmmoChoice.of(atlas, bins.get(1)));
                SwingUtilities.invokeAndWait(() -> { });
                verify(firing.client).sendAmmoChange(1, 10, 13, 1, 0);
                assertSame(bins.get(1), onSwing(lrm::getLinkedAmmo));
                List<WeaponAttackAction> attacks = onSwing(() -> firing.display.getAttacks().stream()
                      .filter(WeaponAttackAction.class::isInstance).map(WeaponAttackAction.class::cast).toList());
                assertEquals(List.of("10 fires 13"), attacks.stream()
                      .map(attack -> attack.getWeaponId() + " fires " + attack.getAmmoId()).toList(),
                      "The queued attack is declared again with the new bin (H30)");
            } finally {
                SwingUtilities.invokeAndWait(source::close);
            }
        }
    }

    @Test
    void theFieldOfFireSwitchKeepsTheFiringDisplaysActorAndWeapon() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean shown = preferences.getShowFieldOfFire();
        try (GpuFiringFixture firing = GpuFiringFixture.create()) {
            // The View menu's switch, which the sheet's field of fire button runs for any weapon but the actor's
            doCallRealMethod().when(firing.gui).actionPerformed(any());
            Field views = AbstractClientGUI.class.getDeclaredField("boardViews");
            views.setAccessible(true);
            views.set(firing.gui, Map.of(0, firing.board.view));
            List<Integer> before = onSwing(() -> {
                firing.unitDisplay.selectWeapon(GpuFiringFixture.weapon(firing.attacker, "AC/20",
                      Mek.LOC_RIGHT_TORSO));
                return selection(firing);
            });
            List<Integer> after = onSwing(() -> {
                firing.gui.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED,
                      ClientGUI.VIEW_TOGGLE_FIELD_OF_FIRE));
                return selection(firing);
            });
            assertEquals(!shown, preferences.getShowFieldOfFire());
            assertEquals(before, after, "The display's actor, the Unit Display's unit and its weapon");
        } finally {
            preferences.setShowFieldOfFire(shown);
        }
    }

    /** EDT: the firing display's actor, the unit its Unit Display shows and the weapon selected there. */
    private static List<Integer> selection(GpuFiringFixture firing) {
        return List.of(firing.display.currentEntity().getId(), firing.unitDisplay.getSelectedEntityId(),
              firing.unitDisplay.getSelectedWeaponNum());
    }

    /** A board source over the fixture's view whose client is the fixture's, as the native window's source. */
    private static GpuBoardSource source(GpuFiringFixture firing) throws Exception {
        BoardClientState view = spy(firing.board.view);
        doReturn(firing.gui).when(view).getClientgui();
        return onSwing(() -> {
            firing.board.source.close();
            return new GpuBoardSource(view, () -> firing.display);
        });
    }
}
