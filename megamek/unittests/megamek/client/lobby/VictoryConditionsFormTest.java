/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Vector;

import megamek.client.lobby.VictoryConditionsForm.Card;
import megamek.client.lobby.VictoryConditionsForm.Kind;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VictoryConditionsFormTest {
    private GameOptions options;
    private VictoryConditionsForm form;

    @BeforeEach
    void setUp() {
        options = new GameOptions();
        options.getOption(OptionsConstants.VICTORY_USE_KILL_COUNT).setValue(false);
        options.getOption(OptionsConstants.VICTORY_GAME_KILL_COUNT).setValue(4);
        form = new VictoryConditionsForm(options);
    }

    private Card card(String id) {
        return form.cards().stream().filter(card -> card.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void cardsPutGeneralFirstAndHoldDependentOptionsUnderTheirSwitch() {
        assertEquals(VictoryConditionsForm.GENERAL, form.cards().get(0).id());
        assertNull(form.cards().get(0).master());
        Card kills = card(OptionsConstants.VICTORY_USE_KILL_COUNT);
        assertNotNull(kills.master());
        assertEquals(OptionsConstants.VICTORY_USE_KILL_COUNT, kills.master().name());
        assertEquals(1, kills.fields().size());
        assertEquals(OptionsConstants.VICTORY_GAME_KILL_COUNT, kills.fields().get(0).name());
        assertEquals(Kind.NUMBER, kills.fields().get(0).kind());
        assertFalse(card(OptionsConstants.VICTORY_USE_OBJECTIVES).note().isBlank());
    }

    @Test
    void dependentNumbersOnlyChangeWhileTheirSwitchIsOn() {
        form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT, 9);
        assertEquals(4, form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT));
        assertFalse(form.active(card(OptionsConstants.VICTORY_USE_KILL_COUNT)));

        form.bool(OptionsConstants.VICTORY_USE_KILL_COUNT, true);
        form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT, 9);
        assertEquals(9, form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT));
        assertTrue(form.active(card(OptionsConstants.VICTORY_USE_KILL_COUNT)));
        assertEquals("9", form.summary(card(OptionsConstants.VICTORY_USE_KILL_COUNT)));
    }

    @Test
    void numbersStayWithinTheirBounds() {
        form.bool(OptionsConstants.VICTORY_USE_KILL_COUNT, true);
        form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT, 0);
        assertEquals(1, form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT));
    }

    @Test
    void activeObjectivesCountSwitchedOnCards() {
        int before = form.activeObjectives();
        form.bool(OptionsConstants.VICTORY_USE_KILL_COUNT, true);
        assertEquals(before + 1, form.activeObjectives());
    }

    @Test
    void changedOptionsHoldOnlyEditsAndTheGameOptionsStayUntouched() {
        assertFalse(form.modified());
        assertTrue(form.changedOptions().isEmpty());
        form.bool(OptionsConstants.VICTORY_USE_KILL_COUNT, true);
        form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT, 6);
        assertTrue(form.modified());
        Vector<IBasicOption> changed = form.changedOptions();
        assertEquals(2, changed.size());
        assertFalse(options.booleanOption(OptionsConstants.VICTORY_USE_KILL_COUNT));

        form.number(OptionsConstants.VICTORY_GAME_KILL_COUNT, 4);
        assertEquals(1, form.changedOptions().size());
    }

    @Test
    void allOptionsCarryTheEditsAmongEveryGameOption() {
        form.bool(OptionsConstants.VICTORY_USE_KILL_COUNT, true);
        Vector<IBasicOption> all = form.allOptions();
        assertTrue(all.stream().anyMatch(option -> option.getName().equals(OptionsConstants.BASE_FRIENDLY_FIRE)));
        IBasicOption kills = all.stream().filter(option -> option.getName().equals(OptionsConstants.VICTORY_USE_KILL_COUNT))
              .findFirst().orElseThrow();
        assertEquals(true, kills.getValue());
    }

    @Test
    void passwordIsNeverNull() {
        form.password(null);
        assertEquals("", form.password());
        form.password("secret");
        assertEquals("secret", form.password());
    }
}
