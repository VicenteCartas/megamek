/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.generator.enums.SkillGeneratorType;
import megamek.client.generator.skillGenerators.AbstractSkillGenerator;
import megamek.common.enums.SkillLevel;
import megamek.common.units.Entity;

/**
 * The random skills without a toolkit, as the Swing {@code SkillGenerationDialog} sets them: the skill generator of
 * the local player or one of their bots (method, type, level and whether the skills stay close to the level), saved
 * back to that client's generator, and a randomize that rolls new skills for that client's units and sends them.
 */
public final class RandomSkillsForm {

    private final List<Client> clients;
    private int selected;
    private SkillGeneratorMethod method;
    private SkillGeneratorType type;
    private SkillLevel level;
    private boolean forceClose;

    /** @param clients the local player's client first, then the local bots' */
    public RandomSkillsForm(List<Client> clients) {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No client to generate skills for");
        }
        this.clients = List.copyOf(clients);
        read();
    }

    private Client client() {
        return clients.get(selected);
    }

    private void read() {
        AbstractSkillGenerator generator = client().getSkillGenerator();
        method = generator.getMethod();
        type = generator.getType();
        level = generator.getLevel();
        forceClose = generator.isForceClose();
    }

    /** @return the names of the clients, the local player's first */
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

    /** Switches to another client, saving the settings of the one shown first, as the Swing dialog does. */
    public void select(int index) {
        if (index < 0 || index >= clients.size() || index == selected) {
            return;
        }
        save();
        selected = index;
        read();
    }

    public List<SkillGeneratorMethod> methods() {
        return List.of(SkillGeneratorMethod.values());
    }

    public List<SkillGeneratorType> types() {
        return List.of(SkillGeneratorType.values());
    }

    public List<SkillLevel> levels() {
        return SkillLevel.getGeneratableValues();
    }

    public SkillGeneratorMethod method() {
        return method;
    }

    public void method(SkillGeneratorMethod method) {
        this.method = Objects.requireNonNull(method);
    }

    public SkillGeneratorType type() {
        return type;
    }

    public void type(SkillGeneratorType type) {
        this.type = Objects.requireNonNull(type);
    }

    public SkillLevel level() {
        return level;
    }

    public void level(SkillLevel level) {
        this.level = Objects.requireNonNull(level);
    }

    public boolean forceClose() {
        return forceClose;
    }

    public void forceClose(boolean forceClose) {
        this.forceClose = forceClose;
    }

    /** Gives the shown client's generator these settings, a new generator when the method changed. */
    public void save() {
        Client client = client();
        if (method != client.getSkillGenerator().getMethod()) {
            client.setSkillGenerator(method.getGenerator());
        }
        AbstractSkillGenerator generator = client.getSkillGenerator();
        generator.setType(type);
        generator.setLevel(level);
        generator.setForceClose(forceClose);
    }

    /**
     * Saves the settings, then rolls new skills for those of {@code units} that the shown client's player owns and
     * sends them through that client.
     *
     * @return how many units got new skills
     */
    public int randomize(Collection<Entity> units) {
        save();
        Client client = client();
        int count = 0;
        for (Entity unit : units) {
            if (unit.getOwnerId() != client.getLocalPlayer().getId()) {
                continue;
            }
            client.getSkillGenerator().setRandomSkills(unit);
            client.sendUpdateEntity(unit);
            count++;
        }
        return count;
    }
}
