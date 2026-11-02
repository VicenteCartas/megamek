/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Vector;

import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.lobby.UnitAbilitiesForm.Slot;
import megamek.client.lobby.UnitEquipmentForm.Toggle;
import megamek.common.Player;
import megamek.common.enums.ProstheticEnhancementType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.game.Game;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnitAbilitiesFormTest {
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
        option(OptionsConstants.RPG_PILOT_ADVANTAGES, true);
        option(OptionsConstants.EDGE, true);
        game.getOptions().getOption(OptionsConstants.ADVANCED_NEURAL_INTERFACE_MODE)
              .setValue(OptionsConstants.NEURAL_INTERFACE_MODE_FULL_TRACKING);
    }

    private void option(String name, boolean on) {
        game.getOptions().getOption(name).setValue(on);
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

    private UnitAbilitiesForm abilities(Entity unit) {
        return UnitAbilitiesForm.of(unit, game.getOptions(), true).orElseThrow();
    }

    private static OptionEntry entry(UnitAbilitiesForm form, String name) {
        return form.entry(name).orElseThrow();
    }

    @Test
    void theGameOptionsChooseTheGroups() {
        Entity atlas = unit("Atlas AS7-D.mtf");
        assertEquals(4, abilities(atlas).groups().size(), "advantages, edge, and the two implant groups");
        option(OptionsConstants.EDGE, false);
        game.getOptions().getOption(OptionsConstants.ADVANCED_NEURAL_INTERFACE_MODE)
              .setValue(OptionsConstants.NEURAL_INTERFACE_MODE_OFF);
        UnitAbilitiesForm form = abilities(atlas);
        assertEquals(1, form.groups().size());
        assertTrue(form.entry(OptionsConstants.EDGE).isEmpty());
        assertTrue(form.entry(OptionsConstants.MD_VDNI).isEmpty());
        option(OptionsConstants.RPG_PILOT_ADVANTAGES, false);
        assertTrue(UnitAbilitiesForm.of(atlas, game.getOptions(), true).isEmpty());
    }

    @Test
    void abilitiesChoicesAndEdgeReachTheCrew() {
        Entity atlas = unit("Atlas AS7-D.mtf");
        UnitAbilitiesForm form = abilities(atlas);
        assertTrue(form.entry(OptionsConstants.MD_CYBER_IMP_AUDIO).isEmpty(), "sensory implants are infantry's");
        form.set(entry(form, OptionsConstants.PILOT_DODGE_MANEUVER), true);
        OptionEntry specialist = entry(form, OptionsConstants.GUNNERY_SPECIALIST);
        specialist.choice(specialist.choices().indexOf(Crew.SPECIAL_ENERGY));
        OptionEntry weapon = entry(form, OptionsConstants.GUNNERY_WEAPON_SPECIALIST);
        assertTrue(weapon.choices().size() > 1, "the Atlas's weapons");
        form.choice(weapon, 1);
        String chosen = weapon.choices().get(1);
        form.number(entry(form, OptionsConstants.EDGE), 3);

        form.apply(atlas);

        assertTrue(atlas.hasAbility(OptionsConstants.PILOT_DODGE_MANEUVER));
        assertEquals(Crew.SPECIAL_ENERGY, atlas.getCrew().getOptions().stringOption(OptionsConstants.GUNNERY_SPECIALIST));
        assertEquals(chosen, atlas.getCrew().getOptions().stringOption(OptionsConstants.GUNNERY_WEAPON_SPECIALIST));
        assertEquals(3, atlas.getCrew().getOptions().intOption(OptionsConstants.EDGE));

        UnitAbilitiesForm again = abilities(atlas);
        assertTrue(entry(again, OptionsConstants.PILOT_DODGE_MANEUVER).on());
        assertEquals(chosen, entry(again, OptionsConstants.GUNNERY_WEAPON_SPECIALIST).text());
        again.choice(entry(again, OptionsConstants.GUNNERY_WEAPON_SPECIALIST), 0);
        again.apply(atlas);
        assertEquals("None", atlas.getCrew().getOptions().stringOption(OptionsConstants.GUNNERY_WEAPON_SPECIALIST));
    }

    @Test
    void neuralInterfacesExcludeEachOtherAndSetTheCockpit() {
        Entity atlas = unit("Atlas AS7-D.mtf");
        UnitEquipmentForm equipment = UnitEquipmentForm.of(atlas, client, true).orElseThrow();
        UnitAbilitiesForm form = abilities(atlas);
        form.link(equipment);
        assertTrue(equipment.shows(Toggle.DNI));
        form.set(entry(form, OptionsConstants.MD_VDNI), true);
        assertTrue(equipment.value(Toggle.DNI), "the implant asks for the cockpit modification");
        form.set(entry(form, OptionsConstants.MD_BVDNI), true);
        assertFalse(entry(form, OptionsConstants.MD_VDNI).on(), "one neural interface at a time");
        assertTrue(entry(form, OptionsConstants.MD_BVDNI).on());
        form.set(entry(form, OptionsConstants.MD_BVDNI), false);
        assertFalse(equipment.value(Toggle.DNI));
        form.set(entry(form, OptionsConstants.MD_PROTO_DNI), true);
        assertTrue(entry(form, OptionsConstants.MD_PROTO_DNI).on(), "a BattleMek takes the prototype");
        assertTrue(form.notice().isEmpty());
    }

    @Test
    void infantryImplantsKeepTheirLimits() {
        Entity platoon = unit("Foot Platoon (AFFS) (Laser 3067+).blk");
        UnitAbilitiesForm form = abilities(platoon);
        form.set(entry(form, OptionsConstants.MD_CYBER_IMP_AUDIO), true);
        form.set(entry(form, OptionsConstants.MD_CYBER_IMP_VISUAL), true);
        form.set(entry(form, OptionsConstants.MD_CYBER_IMP_LASER), true);
        assertFalse(entry(form, OptionsConstants.MD_CYBER_IMP_LASER).on(), "at most two sensory implants");
        assertFalse(form.notice().isEmpty());
        form.set(entry(form, OptionsConstants.MD_GAS_EFFUSER_TOXIN), true);
        form.set(entry(form, OptionsConstants.MD_GAS_EFFUSER_PHEROMONE), true);
        assertFalse(entry(form, OptionsConstants.MD_GAS_EFFUSER_PHEROMONE).on(), "one gas effuser");
        assertTrue(form.entry(OptionsConstants.MD_VDNI).isEmpty(), "no vehicular interface for infantry");
        form.set(entry(form, OptionsConstants.MD_PROTO_DNI), true);
        assertFalse(entry(form, OptionsConstants.MD_PROTO_DNI).on(), "the prototype is for BattleMeks");
    }

    @Test
    void prostheticsAndLimbsFollowTheirImplants() {
        ConvInfantry platoon = (ConvInfantry) unit("Foot Platoon (AFFS) (Laser 3067+).blk");
        UnitAbilitiesForm form = abilities(platoon);
        OptionEntry enhanced = entry(form, OptionsConstants.MD_PL_ENHANCED);
        OptionEntry improved = entry(form, OptionsConstants.MD_PL_I_ENHANCED);
        assertEquals(List.of(Slot.STANDARD), form.slots(enhanced));
        form.set(enhanced, true);
        form.set(improved, true);
        assertFalse(enhanced.on(), "the enhanced prosthetics exclude each other");
        form.prosthetic(Slot.IMPROVED_1, 1);
        form.count(Slot.IMPROVED_1, 2);
        form.prosthetic(Slot.IMPROVED_2, 2);
        form.set(entry(form, OptionsConstants.MD_PL_EXTRA_LIMBS), true);
        form.prosthetic(Slot.PAIR_1, 3);
        form.prosthetic(Slot.PAIR_2, 4);
        form.set(entry(form, OptionsConstants.MD_PL_GLIDER), true);
        assertEquals(0, form.prosthetic(Slot.PAIR_2), "wings leave one pair of limbs");
        assertFalse(form.prostheticEnabled(Slot.PAIR_2));

        form.apply(platoon);
        form.applyProsthetics(platoon);

        List<String> choices = form.prostheticChoices();
        assertEquals(choices.get(1), label(platoon.getProstheticEnhancement1()));
        assertEquals(2, platoon.getProstheticEnhancement1Count());
        assertEquals(choices.get(2), label(platoon.getProstheticEnhancement2()));
        assertEquals(1, platoon.getProstheticEnhancement2Count());
        assertEquals(choices.get(3), label(platoon.getExtraneousPair1()));
        assertEquals(null, platoon.getExtraneousPair2());
    }

    private static String label(ProstheticEnhancementType type) {
        return type.getCategory().getDisplayName() + ": " + type.getDisplayName();
    }

    @Test
    void quirksAndWeaponQuirksReachTheUnit() {
        option(OptionsConstants.ADVANCED_STRATOPS_QUIRKS, true);
        Entity atlas = unit("Atlas AS7-D.mtf");
        UnitQuirksForm form = UnitQuirksForm.of(atlas, true).orElseThrow();
        OptionEntry positive = form.positive().stream()
              .filter(entry -> entry.name().equals(OptionsConstants.QUIRK_POS_COMMAND_MEK)).findFirst().orElseThrow();
        form.set(positive, true);
        UnitQuirksForm.Weapon weapon = form.weapons().get(0);
        OptionEntry weaponQuirk = weapon.entries().stream()
              .filter(entry -> entry.kind() == OptionEntry.Kind.BOOLEAN).findFirst().orElseThrow();
        form.set(weaponQuirk, true);

        form.apply(atlas);

        assertTrue(atlas.hasQuirk(OptionsConstants.QUIRK_POS_COMMAND_MEK));
        Mounted<?> mounted = atlas.getEquipment(weapon.number());
        assertTrue(mounted.getQuirks().booleanOption(weaponQuirk.name()));
    }

    @Test
    void theBayHoldsTheSingleUnitPartsWithTheirOptions() {
        Entity atlas = unit("Atlas AS7-D.mtf");
        unit("Archer ARC-2R.mtf");
        UnitConfigForm single = UnitConfigForm.of(client, client, List.of(atlas), true);
        assertTrue(single.abilities().isPresent());
        assertTrue(single.quirks().isEmpty(), "quirks only with their option");
        assertTrue(single.repairs().isEmpty(), "partial repairs only with their option");
        option(OptionsConstants.ADVANCED_STRATOPS_QUIRKS, true);
        option(OptionsConstants.ADVANCED_STRATOPS_PARTIAL_REPAIRS, true);
        single = UnitConfigForm.of(client, client, List.of(atlas), true);
        assertTrue(single.quirks().isPresent());
        PartialRepairsForm repairs = single.repairs().orElseThrow();
        OptionEntry repair = repairs.groups().get(0).entries().get(0);
        repairs.set(repair, true);
        single.abilities().orElseThrow().set(entry(single.abilities().orElseThrow(),
              OptionsConstants.PILOT_DODGE_MANEUVER), true);

        assertTrue(single.apply());

        assertTrue(atlas.getPartialRepairs().booleanOption(repair.name()));
        assertTrue(atlas.hasAbility(OptionsConstants.PILOT_DODGE_MANEUVER));
        assertTrue(entry(single.abilities().orElseThrow(), OptionsConstants.PILOT_DODGE_MANEUVER).on(),
              "read again after the apply");

        UnitConfigForm multi = UnitConfigForm.of(client, client, game.getEntitiesVector(), true);
        assertTrue(multi.abilities().isEmpty() && multi.quirks().isEmpty() && multi.repairs().isEmpty());
    }
}
