/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.lobby.AutoResolveRun;
import megamek.client.lobby.AutoResolveRun.Outcome;
import megamek.client.lobby.AutoResolveRun.Score;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;

/**
 * The lobby's auto-resolve as one briefing that moves on as the battle does: the war game that works out the odds,
 * a progress whose caption cycles through the staff's chatter; the chance of victory, large, over the victories,
 * defeats and draws it came from, with Resolve and Cancel; the battle being resolved; and the outcome as a headline,
 * Victory!, Defeat! or Draw, with who won and the battle report.
 * <p>
 * GPU thread. The window runs the simulations and tells the view how they go.
 */
final class LobbyAutoResolveView {
    /** What the view asks of the window. */
    interface Host {
        /** Resolves the battle once, after the odds. */
        void proceed();

        /** Shows the battle report over the view. */
        void report();
    }

    /** Where the view is. */
    enum Phase {
        ODDS, CHOICE, RESOLVING, RESULT
    }

    /** How long each line of chatter shows, in seconds. */
    static final float LINE_SECONDS = 1.8f;
    /** How long the resolving progress takes to fill most of its bar, in seconds, as no real share is known. */
    private static final float RESOLVE_SECONDS = 12;
    /** The share the resolving progress never passes before the battle ends. */
    private static final float RESOLVE_MOST = .95f;
    /** A chance of victory from this percentage up shows as good. */
    static final int GOOD_ODDS = 60;
    /** A chance of victory below this percentage shows as bad. */
    static final int BAD_ODDS = 40;

    private final UiKit kit;
    private final UiDialog dialog;
    private final Host host;
    private final Runnable close;
    private final List<String> chatter;
    private Phase phase;
    private @Nullable UiKit.Progress progress;
    private @Nullable Label count;
    private int line;
    private float elapsed;
    private float lineElapsed;

    /** Opens on the odds when {@code odds} says so, else on the battle being resolved. */
    LobbyAutoResolveView(UiKit kit, UiDialog dialog, boolean odds, List<String> chatter, Host host, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.host = host;
        this.close = close;
        this.chatter = chatter;
        dialog.body().top().left().defaults().left().growX();
        dialog.card().addAction(new Action() {
            @Override
            public boolean act(float delta) {
                tick(delta);
                return false;
            }
        });
        if (odds) {
            showOdds();
        } else {
            resolving();
        }
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** The phase that shows. */
    Phase phase() {
        return phase;
    }

    /** Whether the dialog still shows; it may have been closed while the simulations ran. */
    boolean isOpen() {
        return dialog.isOpen();
    }

    private Table begin(Phase next, String overline) {
        phase = next;
        progress = null;
        count = null;
        elapsed = 0;
        lineElapsed = 0;
        dialog.clearFooter();
        Table body = dialog.body();
        body.clearChildren();
        Label head = kit.typeLabel(overline, "overline", "text.secondary");
        head.setName("autoresolve-overline");
        body.add(head).row();
        return body;
    }

    private void showOdds() {
        Table body = begin(Phase.ODDS, Messages.getString("Lobby.autoResolve.simulating"));
        progress = kit.progress(AutoResolveRun.loading());
        progress.setName("autoresolve-progress");
        body.add(progress).padTop(space("lg")).row();
        count = kit.typeLabel("", "small", "text.muted");
        count.setName("autoresolve-count");
        body.add(count).padTop(space("sm")).row();
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("autoresolve-cancel");
    }

    /** {@code ended} of {@code runs} simulated battles have ended. */
    void odds(int ended, int runs) {
        if (phase == Phase.ODDS && progress != null && count != null) {
            progress.set(runs == 0 ? 1 : (float) ended / runs);
            count.setText(Messages.getString("Lobby.autoResolve.simulated", ended, runs));
        }
    }

    /** The odds are in: the chance of victory, and Resolve or Cancel. */
    void choice(Score score) {
        Table body = begin(Phase.CHOICE, Messages.getString("Lobby.autoResolve.odds"));
        if (score.runs() == 0) {
            Label none = kit.typeLabel(Messages.getString("Lobby.autoResolve.noOdds"), "body", "text.primary");
            none.setWrap(true);
            none.setName("autoresolve-chance");
            body.add(none).padTop(space("lg")).row();
        } else {
            Label chance = kit.typeLabel(score.percent() + "%", "display", oddsRole(score.percent()));
            chance.setName("autoresolve-chance");
            chance.setAlignment(Align.center);
            body.add(chance).padTop(space("lg")).row();
            Label caption = kit.typeLabel(Messages.getString("Lobby.autoResolve.chance"), "label", "text.secondary");
            caption.setAlignment(Align.center);
            body.add(caption).row();
            Table tally = new Table();
            float gap = space("md");
            tally.add(stat("victories", score.victories(), "status.success")).growX().uniformX().padRight(gap / 2);
            tally.add(stat("defeats", score.losses(), "status.danger")).growX().uniformX().padLeft(gap / 2)
                  .padRight(gap / 2);
            tally.add(stat("draws", score.draws(), "text.primary")).growX().uniformX().padLeft(gap / 2);
            body.add(tally).padTop(space("lg")).row();
            String basis = Messages.getString("Lobby.autoResolve.basis", score.runs());
            if (score.failed() > 0) {
                basis += " " + Messages.getString("Lobby.autoResolve.failed", score.failed());
            }
            Label from = kit.typeLabel(basis, "small", "text.muted");
            from.setWrap(true);
            from.setAlignment(Align.center);
            body.add(from).padTop(space("md")).row();
        }
        UiButton proceed = dialog.primary(Messages.getString("Lobby.autoResolve.proceed"), host::proceed);
        proceed.setName("autoresolve-proceed");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("autoresolve-cancel");
        focus(proceed);
    }

    /** The token color a chance of victory shows in: good, even or bad. */
    static String oddsRole(int percent) {
        if (percent >= GOOD_ODDS) {
            return "status.success";
        }
        return percent < BAD_ODDS ? "status.danger" : "status.warning";
    }

    private Table stat(String name, int value, String role) {
        Table card = kit.card();
        card.setName("autoresolve-" + name);
        Label number = kit.typeLabel(String.valueOf(value), "headline", role);
        number.setAlignment(Align.center);
        card.add(number).growX().row();
        Label label = kit.typeLabel(Messages.getString("Lobby.autoResolve." + name), "small", "text.secondary");
        label.setAlignment(Align.center);
        card.add(label).growX();
        return card;
    }

    /** The battle is being resolved. */
    void resolving() {
        Table body = begin(Phase.RESOLVING, Messages.getString("Lobby.autoResolve.resolving"));
        progress = kit.progress(AutoResolveRun.loading());
        progress.setName("autoresolve-progress");
        body.add(progress).padTop(space("lg")).row();
    }

    /** The battle is over: how it went for the local side, who won, and its report when there is one. */
    void result(Outcome outcome, String winner, boolean report) {
        Table body = begin(Phase.RESULT, Messages.getString("Lobby.autoResolve.over"));
        Label headline = kit.typeLabel(AutoResolveRun.headline(outcome), "display", outcomeRole(outcome));
        headline.setName("autoresolve-headline");
        headline.setAlignment(Align.center);
        body.add(headline).padTop(space("lg")).row();
        Label who = kit.typeLabel(winner, "body", "text.primary");
        who.setName("autoresolve-winner");
        who.setWrap(true);
        who.setAlignment(Align.center);
        body.add(who).padTop(space("sm")).row();
        UiButton main;
        if (report) {
            main = dialog.primary(Messages.getString("Lobby.autoResolve.report"), host::report);
            main.setName("autoresolve-report");
            dialog.secondary(Messages.getString("Close"), close).setName("autoresolve-close");
        } else {
            main = dialog.primary(Messages.getString("Close"), close);
            main.setName("autoresolve-close");
        }
        focus(main);
    }

    /** The token color an outcome's headline shows in. */
    static String outcomeRole(Outcome outcome) {
        return switch (outcome) {
            case VICTORY -> "status.success";
            case DEFEAT -> "status.danger";
            case DRAW -> "status.warning";
        };
    }

    /** The battle could not be resolved: {@code text} says so. */
    void failed(String title, String text) {
        Table body = begin(Phase.RESULT, title);
        Label why = kit.typeLabel(text, "body", "text.primary");
        why.setName("autoresolve-winner");
        why.setWrap(true);
        body.add(why).padTop(space("md")).row();
        UiButton main = dialog.primary(Messages.getString("Close"), close);
        main.setName("autoresolve-close");
        focus(main);
    }

    /** Gives the focus to the footer's new main button, as a dialog does when it opens. */
    private static void focus(UiButton main) {
        if (main.getStage() != null) {
            main.getStage().setKeyboardFocus(main);
        }
    }

    /** Cycles the chatter while the simulations run, and fills the resolving bar towards the end it cannot know. */
    private void tick(float delta) {
        if (progress == null || (phase != Phase.ODDS && phase != Phase.RESOLVING)) {
            return;
        }
        elapsed += delta;
        lineElapsed += delta;
        if (lineElapsed >= LINE_SECONDS && !chatter.isEmpty()) {
            lineElapsed = 0;
            progress.caption(chatter.get(line++ % chatter.size()));
        }
        if (phase == Phase.RESOLVING) {
            progress.set(RESOLVE_MOST * (1 - (float) Math.exp(-elapsed / (RESOLVE_SECONDS / 3))));
        }
        if (Gdx.graphics != null) {
            Gdx.graphics.requestRendering();
        }
    }
}
