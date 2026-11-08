/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import megamek.client.Client;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The prompts the lobby's commands fall back on when no lobby shows to ask through: the lobby attaches its own asks,
 * camo picker and unit configurator while it shows, so these only log that nobody was there to ask, and decline.
 */
public final class DecliningLobbyPrompts implements LobbyPrompts {
    private static final MMLogger LOGGER = MMLogger.create(DecliningLobbyPrompts.class);

    private static void declined(String what) {
        LOGGER.warn("The lobby's commands asked for {} while no lobby showed; declined", what);
    }

    @Override
    public void error(LobbyError error, Object... args) {
        LOGGER.warn("The lobby refused a command while no lobby showed: {}", error.message(args));
    }

    @Override
    public boolean confirm(String title, String question) {
        declined("a confirmation");
        return false;
    }

    @Override
    public void readyBlocked(ReadyCheck.Blocker blocker) {
        LOGGER.warn("The lobby could not ready up while no lobby showed: {}", blocker.message());
    }

    @Override
    public Optional<String> chooseForceName(ForceNameRequest request) {
        declined("a force name");
        return Optional.empty();
    }

    @Override
    public Optional<String> chooseSquadronDesignation() {
        declined("a squadron designation");
        return Optional.empty();
    }

    @Override
    public Optional<Camouflage> chooseCamo(Camouflage initial, boolean hasIndividualCamo, List<Entity> units) {
        declined("a camo");
        return Optional.empty();
    }

    @Override
    public CustomizeResult customizeUnits(Client client, List<Entity> units, boolean editable) {
        declined("the units' configuration");
        return new CustomizeResult(false, null);
    }

    @Override
    public CustomizeResult customizeUnit(Client client, Entity unit, boolean editable) {
        declined("a unit's configuration");
        return new CustomizeResult(false, null);
    }

    @Override
    public boolean configureAsset(BattlefieldSupportAsset asset, boolean editable) {
        declined("an asset's configuration");
        return false;
    }

    @Override
    public void editDamage(Entity unit) {
        declined("a unit's damage");
    }

    @Override
    public boolean editAssetDamage(BattlefieldSupportAsset asset) {
        declined("an asset's damage");
        return false;
    }

    @Override
    public Optional<List<Entity>> orderTrain(Entity head, List<Entity> trailers) {
        declined("a train's order");
        return Optional.empty();
    }

    @Override
    public void showSbfStats(Collection<Force> forces, Game game) {
        declined("the SBF stats");
    }

    @Override
    public void showAlphaStrikeStats(Collection<Entity> units) {
        declined("the Alpha Strike stats");
    }
}
