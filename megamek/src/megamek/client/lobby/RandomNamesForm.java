/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import megamek.client.Client;
import megamek.client.generator.RandomGenderGenerator;
import megamek.client.generator.RandomNameGenerator;
import megamek.client.ui.Messages;
import megamek.common.enums.Gender;
import megamek.common.units.Entity;

/**
 * The random crew names without a toolkit, as the Swing {@code RandomNameDialog} gives them: a faction, the share of
 * female crew and a historical ethnicity (or the faction's own mix), saved as the generators' settings, and a randomize
 * that names every crew member of the chosen client's units and sends them through that client.
 */
public final class RandomNamesForm {

    /** Names a crew member; the ethnicity is 0 for the faction's own mix. */
    @FunctionalInterface
    public interface Namer {
        String name(Gender gender, boolean clan, int ethnicity);
    }

    /** What the form reads and saves: the name generator's factions and ethnicities and the gender share. */
    public interface Settings {
        List<String> factions();

        String faction();

        void faction(String faction);

        /** The historical ethnicities by their code, from 1. */
        Map<Integer, String> ethnicities();

        int percentFemale();

        void percentFemale(int percent);

        Gender gender();

        Namer namer();
    }

    private final List<Client> clients;
    private final Settings settings;
    private final List<String> factions;
    private final List<String> ethnicities = new ArrayList<>();
    private int selected;
    private String faction;
    private int percentFemale;
    private int ethnicity;

    /** @param clients the local player's client first, then the local bots' */
    public RandomNamesForm(List<Client> clients, Settings settings) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No client to name crews for");
        }
        this.clients = List.copyOf(clients);
        this.settings = Objects.requireNonNull(settings);
        factions = List.copyOf(settings.factions());
        ethnicities.add(Messages.getString("Lobby.names.factionWeighted"));
        ethnicities.addAll(settings.ethnicities().values());
        read();
    }

    /** A form on the shared name and gender generators. */
    public static RandomNamesForm of(List<Client> clients) {
        return new RandomNamesForm(clients, GENERATORS);
    }

    private void read() {
        faction = settings.faction();
        percentFemale = settings.percentFemale();
        ethnicity = 0;
    }

    public List<String> clientNames() {
        List<String> names = new ArrayList<>();
        for (Client client : clients) {
            names.add(client.getName());
        }
        return names;
    }

    public int selected() {
        return selected;
    }

    /** Switches to another client; the settings are read again, as the Swing dialog does. */
    public void select(int index) {
        if (index >= 0 && index < clients.size() && index != selected) {
            selected = index;
            read();
        }
    }

    public List<String> factions() {
        return factions;
    }

    public String faction() {
        return faction;
    }

    public void faction(String faction) {
        if (factions.contains(faction)) {
            this.faction = faction;
        }
    }

    /** @return the share of female crew, 0 to 100 */
    public int percentFemale() {
        return percentFemale;
    }

    public void percentFemale(int percent) {
        percentFemale = Math.clamp(percent, 0, 100);
    }

    /** @return the ethnicities offered, the faction's own mix first */
    public List<String> ethnicities() {
        return List.copyOf(ethnicities);
    }

    public int ethnicity() {
        return ethnicity;
    }

    public void ethnicity(int index) {
        if (index >= 0 && index < ethnicities.size()) {
            ethnicity = index;
        }
    }

    /** Keeps the faction and the gender share as the generators' settings. */
    public void save() {
        if (faction != null) {
            settings.faction(faction);
        }
        settings.percentFemale(percentFemale);
    }

    /**
     * Saves the settings, then names every crew member of those of {@code units} that the chosen client's player owns,
     * and sends them through that client.
     *
     * @return how many units were named
     */
    public int randomize(Collection<Entity> units) {
        save();
        Client client = clients.get(selected);
        Namer namer = settings.namer();
        int count = 0;
        for (Entity unit : units) {
            if (unit.getOwnerId() != client.getLocalPlayer().getId()) {
                continue;
            }
            for (int slot = 0; slot < unit.getCrew().getSlotCount(); slot++) {
                Gender gender = settings.gender();
                unit.getCrew().setGender(gender, slot);
                unit.getCrew().setName(namer.name(gender, unit.getCrew().isClanPilot(slot), ethnicity), slot);
            }
            client.sendUpdateEntity(unit);
            count++;
        }
        return count;
    }

    private static final Settings GENERATORS = new Settings() {
        @Override
        public List<String> factions() {
            var factions = RandomNameGenerator.getInstance().getFactions();
            return (factions == null) ? List.of() : new ArrayList<>(factions);
        }

        @Override
        public String faction() {
            return RandomNameGenerator.getInstance().getChosenFaction();
        }

        @Override
        public void faction(String faction) {
            RandomNameGenerator.getInstance().setChosenFaction(faction);
        }

        @Override
        public Map<Integer, String> ethnicities() {
            return RandomNameGenerator.getInstance().getHistoricalEthnicity();
        }

        @Override
        public int percentFemale() {
            return RandomGenderGenerator.getPercentFemale();
        }

        @Override
        public void percentFemale(int percent) {
            RandomGenderGenerator.setPercentFemale(percent);
        }

        @Override
        public Gender gender() {
            return RandomGenderGenerator.generate();
        }

        @Override
        public Namer namer() {
            RandomNameGenerator generator = RandomNameGenerator.getInstance();
            return (gender, clan, ethnicity) -> (ethnicity == 0)
                  ? generator.generate(gender, clan)
                  : generator.generateWithEthnicCode(gender, clan, ethnicity);
        }
    };
}
