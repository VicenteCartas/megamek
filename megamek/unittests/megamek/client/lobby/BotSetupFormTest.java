/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.PrincessException;
import megamek.client.lobby.BotSetupForm.Flag;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.lobby.BotSetupForm.Preset;
import megamek.client.lobby.BotSetupForm.PresetKind;
import megamek.client.lobby.BotSetupForm.Retreat;
import megamek.client.lobby.BotSetupForm.Slider;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotSetupFormTest {
    private final Map<String, BehaviorSettings> stored = new TreeMap<>();
    private final BotSetupForm.Presets presets = new BotSetupForm.Presets() {
        @Override
        public List<String> names() {
            return List.copyOf(stored.keySet());
        }

        @Override
        public BehaviorSettings get(String name) {
            return stored.get(name);
        }

        @Override
        public void save(BehaviorSettings preset) {
            stored.put(preset.getDescription(), preset);
        }

        @Override
        public void remove(String name) {
            stored.remove(name);
        }
    };
    private BehaviorSettings previous;
    private BehaviorSettings other;

    @BeforeEach
    void setUp() throws PrincessException {
        stored.put("BERSERK", settings("BERSERK", 9, CombatPosture.ATTACK));
        stored.put("COWARDLY", settings("COWARDLY", 1, CombatPosture.DEFEND));
        previous = settings("Mine", 4, CombatPosture.ATTACK);
        previous.setForcedWithdrawal(true);
        previous.setRetreatEdge(CardinalEdge.SOUTH);
        previous.addStrategicTarget(new Coords(2, 3).toString());
        previous.setPriorityUnit(7, 2);
        other = settings("Other", 6, CombatPosture.DEFEND);
        other.setForcedWithdrawal(false);
    }

    private static BehaviorSettings settings(String name, int bravery, CombatPosture posture)
          throws PrincessException {
        BehaviorSettings settings = new BehaviorSettings();
        settings.setDescription(name);
        settings.setBraveryIndex(bravery);
        settings.setCombatPosture(posture);
        return settings;
    }

    private BotSetupForm form(String fixedName, AIType locked, boolean caspar, Board board) {
        return new BotSetupForm(fixedName, "Princess", Set.of("Local", "Princess-Taken"), previous, locked, caspar,
              false, presets, Map.of("Other", other), board, new BotTargetPicker(true, List.of(), Map.of(), Map.of()));
    }

    private BotSetupForm newBot() {
        return form(null, null, false, null);
    }

    @Test
    void aNewBotStartsFromThePreviousConfiguration() {
        BotSetupForm form = newBot();

        assertTrue(form.newBot());
        assertEquals("Princess", form.name());
        assertEquals(4, form.value(Slider.BRAVERY));
        assertTrue(form.flag(Flag.FORCED_WITHDRAWAL));
        assertEquals(CardinalEdge.SOUTH, form.retreatEdge());
        assertEquals(List.of(new Coords(2, 3)), form.hexTargets().stream().map(BotSetupForm.HexTarget::coords)
              .toList());
        assertEquals(Map.of(7, 2), form.picker().picked());
        assertEquals("Mine", form.description());
    }

    @Test
    void theNameMustBeFreeAndNotBlank() {
        BotSetupForm form = newBot();
        assertTrue(form.valid());

        form.name("Local");
        assertEquals(Optional.of("ChatLounge.AlertExistsBot.message"), form.nameError());
        form.name("  ");
        assertEquals(Optional.of("Lobby.bot.nameBlank"), form.nameError());
        assertFalse(form.valid());
    }

    @Test
    void anExistingBotKeepsItsNameAndKind() {
        BotSetupForm form = form("Princess-Taken", AIType.PRINCESS, false, null);

        form.name("Else");
        form.type(AIType.CASPAR);

        assertFalse(form.newBot());
        assertEquals("Princess-Taken", form.name());
        assertTrue(form.valid());
        assertTrue(form.typeLocked());
        assertEquals(AIType.PRINCESS, form.type());
    }

    @Test
    void casparIsOfferedOnlyWhenEnabledOrLocked() {
        assertFalse(newBot().types().contains(AIType.CASPAR));
        assertTrue(form(null, null, true, null).types().contains(AIType.CASPAR));
        assertTrue(form("Princess-Taken", AIType.CASPAR, false, null).types().contains(AIType.CASPAR));

        BotSetupForm form = newBot();
        form.type(AIType.CASPAR);
        assertEquals(AIType.PRINCESS, form.type());
    }

    @Test
    void thePresetsListThePreviousTheStoredAndTheOtherBots() {
        assertEquals(List.of(new Preset("", PresetKind.PREVIOUS), new Preset("BERSERK", PresetKind.STORED),
              new Preset("COWARDLY", PresetKind.STORED), new Preset("Other", PresetKind.BOT)), newBot().presets());
    }

    @Test
    void shippedPresetsShowFriendlyNamesAndOwnNamesStay() {
        assertEquals("LRM Missile Boat", Preset.shownName("LRM Missile Boat (pPT)"));
        assertEquals("Balanced v2", Preset.shownName("DEFAULT v2 BRAVER"));
        assertEquals("Escape", new Preset("ESCAPE", PresetKind.STORED).label());
        assertEquals("My Lance", Preset.shownName("My Lance"));
    }

    @Test
    void aStoredPresetLoadsOnlyThePresetFields() {
        BotSetupForm form = newBot();

        form.select(new Preset("COWARDLY", PresetKind.STORED));

        assertEquals(1, form.value(Slider.BRAVERY));
        assertEquals(CombatPosture.DEFEND, form.posture());
        assertEquals("COWARDLY", form.description());
        assertTrue(form.flag(Flag.FORCED_WITHDRAWAL));
        assertEquals(CardinalEdge.SOUTH, form.retreatEdge());
        assertEquals(1, form.hexTargets().size());
        assertFalse(form.changedPreset());
    }

    @Test
    void anotherBotOrThePreviousLoadsEverything() {
        BotSetupForm form = newBot();

        form.select(new Preset("Other", PresetKind.BOT));
        assertEquals(6, form.value(Slider.BRAVERY));
        assertFalse(form.flag(Flag.FORCED_WITHDRAWAL));
        assertTrue(form.hexTargets().isEmpty());
        assertTrue(form.picker().picked().isEmpty());

        form.select(new Preset("", PresetKind.PREVIOUS));
        assertEquals(4, form.value(Slider.BRAVERY));
        assertTrue(form.flag(Flag.FORCED_WITHDRAWAL));
        assertEquals(Map.of(7, 2), form.picker().picked());
    }

    @Test
    void savingAChangedPresetWritesItAndSettles() {
        BotSetupForm form = newBot();
        form.select(new Preset("BERSERK", PresetKind.STORED));

        form.value(Slider.BRAVERY, 3);
        assertTrue(form.changedPreset());
        form.savePreset();

        assertEquals(3, stored.get("BERSERK").getBraveryIndex());
        assertFalse(form.changedPreset());
    }

    @Test
    void onlyAStoredPresetHasChanges() {
        BotSetupForm form = newBot();
        form.value(Slider.BRAVERY, 8);

        assertFalse(form.changedPreset());
        form.savePreset();
        assertEquals(9, stored.get("BERSERK").getBraveryIndex());
    }

    @Test
    void aNewPresetNeedsAFreeName() {
        BotSetupForm form = newBot();
        form.value(Slider.BRAVERY, 7);

        assertFalse(form.canSaveNew(" "));
        assertEquals(Optional.of("BotConfigDialog.saveNewTaken"), form.newPresetError(" BERSERK "));
        assertFalse(form.saveNewPreset("BERSERK"));

        assertTrue(form.saveNewPreset(" Brave "));
        assertEquals(7, stored.get("Brave").getBraveryIndex());
        assertEquals(new Preset("Brave", PresetKind.STORED), form.selected());
        assertTrue(form.presets().contains(new Preset("Brave", PresetKind.STORED)));
    }

    @Test
    void deletingTheChosenPresetUnchoosesIt() {
        BotSetupForm form = newBot();
        form.select(new Preset("BERSERK", PresetKind.STORED));

        form.deletePreset("BERSERK");

        assertNull(form.selected());
        assertFalse(form.changedPreset());
        assertFalse(stored.containsKey("BERSERK"));
        assertFalse(form.presets().contains(new Preset("BERSERK", PresetKind.STORED)));
    }

    @Test
    void slidersClampAndEdgesIgnoreNone() {
        BotSetupForm form = newBot();

        form.value(Slider.AGGRESSION, 99);
        form.retreatEdge(CardinalEdge.NONE);
        form.fleeEdge(CardinalEdge.EAST);

        assertEquals(Slider.AGGRESSION.max(), form.value(Slider.AGGRESSION));
        assertEquals(CardinalEdge.SOUTH, form.retreatEdge());
        assertEquals(CardinalEdge.EAST, form.fleeEdge());
        assertFalse(BotSetupForm.edges().contains(CardinalEdge.NONE));
    }

    @Test
    void onlyStoredPersonalitiesShow() {
        assertEquals(List.of(Personality.CAUTIOUS, Personality.BERSERKER), newBot().personalities());
        assertEquals(Personality.BERSERKER, Personality.of(" BERSERK "));
        assertNull(Personality.of("Mine"));
        assertNull(Personality.of(null));
    }

    @Test
    void choosingAPersonalityKeepsThePosture() {
        BotSetupForm form = newBot();
        form.posture(CombatPosture.DEFEND);

        form.choose(Personality.BERSERKER);

        assertEquals(9, form.value(Slider.BRAVERY));
        assertEquals(CombatPosture.DEFEND, form.posture());
        assertEquals(Optional.of(Personality.BERSERKER), form.personality());
        assertEquals(Personality.BERSERKER, form.basedOn());
        assertFalse(form.modified());
    }

    @Test
    void aChangedSliderIsModifiedUntilItMatchesAgain() {
        BotSetupForm form = newBot();
        assertNull(form.basedOn());
        assertFalse(form.modified());

        form.choose(Personality.CAUTIOUS);
        form.value(Slider.BRAVERY, 5);
        assertTrue(form.modified());
        assertEquals(Optional.empty(), form.personality());

        form.value(Slider.BRAVERY, 1);
        assertFalse(form.modified());
        form.posture(CombatPosture.ATTACK);
        assertEquals(Optional.of(Personality.CAUTIOUS), form.personality());
    }

    @Test
    void aBotNamedAfterAPersonalityStartsFromIt() throws PrincessException {
        previous = settings("BERSERK", 9, CombatPosture.ATTACK);

        assertEquals(Personality.BERSERKER, newBot().basedOn());
        previous = settings("DEFAULT", 5, CombatPosture.ATTACK);
        assertNull(newBot().basedOn(), "a personality without a stored preset has no card to start from");
    }

    @Test
    void theRetreatChoiceSetsBothSwitchesAndItsEdge() {
        BotSetupForm form = newBot();
        assertEquals(Retreat.WITHDRAW, form.retreat());
        assertEquals(CardinalEdge.SOUTH, form.retreatTo());

        form.retreat(Retreat.LEAVE);
        assertTrue(form.flag(Flag.AUTO_FLEE));
        assertTrue(form.flag(Flag.FORCED_WITHDRAWAL));
        assertEquals(CardinalEdge.SOUTH, form.retreatTo());
        form.retreatTo(CardinalEdge.WEST);
        assertEquals(CardinalEdge.WEST, form.fleeEdge());
        assertEquals(CardinalEdge.WEST, form.retreatEdge());

        form.retreat(Retreat.FIGHT);
        assertFalse(form.flag(Flag.AUTO_FLEE));
        assertFalse(form.flag(Flag.FORCED_WITHDRAWAL));
        assertEquals(Retreat.FIGHT, form.retreat());
    }

    @Test
    void experimentalFieldsHideUnlessEnabled() {
        BotSetupForm form = newBot();

        assertTrue(form.sliders().stream().noneMatch(Slider::experimental));
        assertFalse(form.presetFlags().contains(Flag.EXPERIMENTAL));
        assertTrue(form.presetFlags().contains(Flag.PIRATE));
    }

    @Test
    void hexesAreTypedCountingFromOne() {
        assertEquals(Optional.of(new Coords(4, 9)), BotSetupForm.parseHex(" 5 , 10 "));
        assertTrue(BotSetupForm.parseHex("5").isEmpty());
        assertTrue(BotSetupForm.parseHex("a, 3").isEmpty());
        assertTrue(BotSetupForm.parseHex(null).isEmpty());

        BotSetupForm form = newBot();
        assertTrue(form.addHex("5, 10"));
        assertTrue(form.addHex("5,10"));
        assertFalse(form.addHex("five"));
        assertEquals(2, form.hexTargets().size());
        form.removeHex(new Coords(2, 3));
        assertEquals(List.of(new Coords(4, 9)), form.hexTargets().stream().map(BotSetupForm.HexTarget::coords)
              .toList());
    }

    @Test
    void hexesWithoutABuildingAreNotValid() {
        Hex[] hexes = new Hex[4];
        Arrays.setAll(hexes, index -> new Hex());
        BotSetupForm form = form(null, null, false, new Board(2, 2, hexes));
        form.removeHex(new Coords(2, 3));

        form.addHex(new Coords(0, 0));
        form.addHex(new Coords(5, 5));

        assertTrue(form.hexTargets().stream().noneMatch(BotSetupForm.HexTarget::valid));
        assertTrue(form.buildingHexes().isEmpty());
        assertFalse(newBot().hexTargets().getFirst().valid());
    }

    @Test
    void theResultCarriesEveryField() {
        BotSetupForm form = newBot();
        form.select(new Preset("BERSERK", PresetKind.STORED));
        form.flag(Flag.PIRATE, true);
        form.flag(Flag.AUTO_FLEE, true);
        form.fleeEdge(CardinalEdge.WEST);
        form.addHex("1, 1");
        form.picker().tick("unit-9", true);

        BehaviorSettings result = form.behavior();

        assertEquals("BERSERK", result.getDescription());
        assertEquals(9, result.getBraveryIndex());
        assertTrue(result.iAmAPirate());
        assertTrue(result.shouldAutoFlee());
        assertTrue(result.isForcedWithdrawal());
        assertEquals(CardinalEdge.WEST, result.getDestinationEdge());
        assertEquals(CardinalEdge.SOUTH, result.getRetreatEdge());
        assertEquals(Set.of(new Coords(2, 3).toString(), new Coords(0, 0).toString()),
              result.getStrategicBuildingTargets());
        assertEquals(2, result.getTargetPriority(7));
        assertEquals(BehaviorSettings.DEFAULT_TARGET_PRIORITY, result.getTargetPriority(9));
    }

    @Test
    void aFreeNameAvoidsTakenOnes() {
        assertEquals("Princess", BotSetupForm.freeName(Set.of("Local")));
        String name = BotSetupForm.freeName(Set.of("Princess"));
        assertTrue(name.startsWith("Princess-"));
        assertFalse(name.contains(" "));
    }
}
