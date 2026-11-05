/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.units.Entity;

/**
 * The questions of {@link LobbyPrompts} for a view that answers later, such as one drawn in the lobby's own window
 * rather than in modal Swing dialogs. Every method returns at once; an answer runs its callback on the thread the
 * view calls back on (the lobby's controller thread), and a cancelled question never runs it.
 * {@link #of(LobbyPrompts)} adapts blocking prompts, whose callbacks run before the method returns.
 */
public interface LobbyAsks {

    /** Tells the player why a command was refused. */
    void error(LobbyError error, Object... args);

    /** Tells the player why they cannot be ready yet. */
    void readyBlocked(ReadyCheck.Blocker blocker);

    /** Runs {@code yes} when the player confirms. */
    void confirm(String title, String question, Runnable yes);

    /** Hands the chosen, trimmed force name to {@code chosen}; never with a blank one. */
    void chooseForceName(ForceNameRequest request, Consumer<String> chosen);

    /** Hands the designation for a new fighter squadron to {@code chosen}; never a blank one. */
    void chooseSquadronDesignation(Consumer<String> chosen);

    /** Shows an asset's crew editor; {@code applied} runs once the choices are applied to the asset. */
    void configureAsset(BattlefieldSupportAsset asset, boolean editable, Runnable applied);

    /** Shows the damage editor of a unit, not an asset; {@code done} runs when it closes. */
    void editDamage(Entity unit, UnitDamageForm.Options options, Runnable done);

    /** Shows an asset's damage editor; {@code applied} runs once the choice is applied to the asset. */
    void editAssetDamage(BattlefieldSupportAsset asset, Runnable applied);

    /** Hands the trailers in the chosen order to {@code ordered}. */
    void orderTrain(Entity head, List<Entity> trailers, Consumer<List<Entity>> ordered);

    /** Shows the Strategic BattleForce stats of forces. */
    void showSbfStats(Collection<Force> forces, Game game);

    /** Shows the Alpha Strike stats of units. */
    void showAlphaStrikeStats(Collection<Entity> units);

    /** @return the questions of blocking prompts, answered before each method returns */
    static LobbyAsks of(LobbyPrompts prompts) {
        Objects.requireNonNull(prompts);
        return new LobbyAsks() {
            @Override
            public void error(LobbyError error, Object... args) {
                prompts.error(error, args);
            }

            @Override
            public void readyBlocked(ReadyCheck.Blocker blocker) {
                prompts.readyBlocked(blocker);
            }

            @Override
            public void confirm(String title, String question, Runnable yes) {
                if (prompts.confirm(title, question)) {
                    yes.run();
                }
            }

            @Override
            public void chooseForceName(ForceNameRequest request, Consumer<String> chosen) {
                prompts.chooseForceName(request).filter(name -> !name.isBlank()).ifPresent(chosen);
            }

            @Override
            public void chooseSquadronDesignation(Consumer<String> chosen) {
                prompts.chooseSquadronDesignation().filter(name -> !name.isBlank()).ifPresent(chosen);
            }

            @Override
            public void configureAsset(BattlefieldSupportAsset asset, boolean editable, Runnable applied) {
                if (prompts.configureAsset(asset, editable)) {
                    applied.run();
                }
            }

            @Override
            public void editDamage(Entity unit, UnitDamageForm.Options options, Runnable done) {
                // the Swing editor edits only damage, so a player who may not edit it has nothing to open there
                if (!options.damage()) {
                    prompts.error(LobbyError.CANNOT_EDIT_DAMAGE);
                    return;
                }
                prompts.editDamage(unit);
                done.run();
            }

            @Override
            public void editAssetDamage(BattlefieldSupportAsset asset, Runnable applied) {
                if (prompts.editAssetDamage(asset)) {
                    applied.run();
                }
            }

            @Override
            public void orderTrain(Entity head, List<Entity> trailers, Consumer<List<Entity>> ordered) {
                prompts.orderTrain(head, trailers).ifPresent(ordered);
            }

            @Override
            public void showSbfStats(Collection<Force> forces, Game game) {
                prompts.showSbfStats(forces, game);
            }

            @Override
            public void showAlphaStrikeStats(Collection<Entity> units) {
                prompts.showAlphaStrikeStats(units);
            }
        };
    }
}
