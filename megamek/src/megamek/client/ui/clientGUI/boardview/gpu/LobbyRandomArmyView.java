/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.common.loaders.MekSummary;

/**
 * The lobby's random army, dealt as unit cards: the table, the unit count and the player above; Roll deals the hand,
 * rerolling every card not kept; each card shows its unit's sprite, Battle Value and tonnage and can be kept, rerolled
 * or discarded; the army's totals over the hand. Add to game sends the hand. Advanced opens the Swing generator with its
 * Battle Value, RAT generator and force generator tabs. The rolls are a {@link RandomArmyForm}'s.
 * <p>
 * GPU thread. The rolls and the adding run on the Swing thread through {@link Host#edt}.
 */
final class LobbyRandomArmyView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: adds the hand to the game for the chosen player. */
        void add(RandomArmyForm form);

        /** Opens the Swing random army generator. */
        void advanced();

        /** Asks for the sprites of {@code units}, which arrive through {@link #spriteArrived}. */
        void sprites(List<MekSummary> units);
    }

    private final UiKit kit;
    private final RandomArmyForm form;
    private final Host host;
    private final Runnable close;
    private final Function<String, Drawable> sprites;
    private final float cardWidth;
    private final int columns;
    private final Table hand = new Table();
    private final Label totals;
    private final UiButton add;
    private final UiButton roll;
    private final List<Image> images = new ArrayList<>();
    private boolean busy;

    LobbyRandomArmyView(UiKit kit, UiDialog dialog, RandomArmyForm form, float bodyWidth,
          Function<String, Drawable> sprites, Host host, Runnable close) {
        this.kit = kit;
        this.form = form;
        this.host = host;
        this.close = close;
        this.sprites = sprites;
        float gap = space("md");
        columns = Math.max(1, (int) ((bodyWidth + gap) / (size("control.lg") * 5 + gap)));
        cardWidth = (bodyWidth - gap * (columns - 1)) / columns;
        Table body = dialog.body();
        body.top().left().defaults().left().growX();

        Table controls = new Table();
        controls.bottom().left();
        UiSelect table = kit.tokenSelect(true, form.tables().toArray(String[]::new));
        table.setName("army-table");
        int at = form.tables().indexOf(form.table());
        if (at >= 0) {
            table.select(at);
        }
        UiKit.onChange(table, () -> form.table(form.tables().get(table.getSelectedIndex())));
        controls.add(kit.formField(strip("RandomArmyDialog.RAT"), table)).growX().minWidth(0);
        UiStepper count = kit.stepper(form.count(), 1, RandomArmyForm.MAX_UNITS);
        count.setName("army-count");
        count.up().setName("army-count-up");
        count.down().setName("army-count-down");
        UiKit.onChange(count, () -> form.count(count.value()));
        controls.add(kit.formField(strip("RandomArmyDialog.Unit"), count)).padLeft(gap);
        if (form.players().size() > 1) {
            UiSelect player = kit.tokenSelect(true, form.players().toArray(String[]::new));
            player.setName("army-player");
            player.select(form.player());
            UiKit.onChange(player, () -> form.player(player.getSelectedIndex()));
            controls.add(kit.formField(strip("RandomArmyDialog.Player"), player)).padLeft(gap)
                  .width(size("control.lg") * 5);
        }
        roll = kit.button("button-secondary", "replay", Messages.getString("RandomArmyDialog.Roll"), null);
        roll.setName("army-roll");
        UiKit.onChange(roll, () -> run(form::roll));
        controls.add(roll).padLeft(gap).bottom();
        body.add(controls).row();

        totals = kit.typeLabel("", "title", "accent.primary");
        totals.setName("army-totals");
        body.add(totals).padTop(space("lg")).row();
        hand.top().left();
        body.add(hand).padTop(space("sm")).row();

        dialog.tool(Messages.getString("RandomArmyDialog.AdvancedSearch").replace("...", "\u2026"), () -> {
            close.run();
            host.advanced();
        }).setName("army-advanced");
        dialog.secondary(Messages.getString("RandomArmyDialog.Clear"), () -> {
            form.clear();
            rebuild();
        }).setName("army-clear");
        add = dialog.primary(Messages.getString("Lobby.randomArmy.add"), this::confirm);
        add.setName("army-add");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("army-cancel");
        rebuild();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private static String strip(String key) {
        String text = Messages.getString(key);
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    /** Runs a roll on the Swing thread, then deals the hand again. */
    private void run(Runnable work) {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        host.edt(work, () -> {
            busy = false;
            rebuild();
        });
    }

    private void rebuild() {
        hand.clearChildren();
        images.clear();
        List<RandomArmyForm.Card> cards = form.cards();
        if (cards.isEmpty()) {
            Label empty = kit.typeLabel(Messages.getString(form.canRoll() ? "Lobby.randomArmy.empty"
                  : "RandomArmyDialog.ratStatusLoading"), "body", "text.muted");
            empty.setWrap(true);
            empty.setAlignment(Align.center);
            hand.add(empty).width(cardWidth * columns).pad(space("xl"));
        }
        float gap = space("md");
        for (int index = 0; index < cards.size(); index++) {
            hand.add(card(index, cards.get(index))).width(cardWidth).top().fillY()
                  .padLeft(index % columns == 0 ? 0 : gap).padTop(index < columns ? 0 : gap);
            if ((index + 1) % columns == 0) {
                hand.row();
            }
        }
        host.sprites(cards.stream().map(RandomArmyForm.Card::unit).toList());
        refresh();
    }

    private Table card(int index, RandomArmyForm.Card card) {
        MekSummary unit = card.unit();
        Table frame = kit.card();
        frame.setName("army-card-" + index);
        frame.top().left().defaults().left();
        Image sprite = new Image((Drawable) null, Scaling.fit);
        sprite.setName(LobbyRequisitionPanel.spriteKey(unit));
        sprite.setDrawable(sprites.apply(LobbyRequisitionPanel.spriteKey(unit)));
        images.add(sprite);
        frame.add(sprite).size(size("control.lg") * 2).center().row();
        Label name = kit.typeLabel(unit.getName(), "body.strong", "text.primary");
        name.setWrap(true);
        frame.add(name).growX().padTop(space("xs")).row();
        frame.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.stats", unit.getBV(), format(unit.getTons()),
              unit.getUnitType()), "small", "text.secondary")).growX().row();
        Table actions = new Table();
        UiButton keep = kit.button("button-toggle-compact", "star", Messages.getString("Lobby.randomArmy.keep"), null);
        keep.setName("army-keep-" + index);
        keep.setProgrammaticChangeEvents(false);
        keep.setChecked(card.kept());
        UiKit.onChange(keep, () -> {
            form.keep(index, !form.cards().get(index).kept());
            keep.setChecked(form.cards().get(index).kept());
        });
        UiButton reroll = kit.button("button-quiet-compact", "replay", null, null);
        reroll.setName("army-reroll-" + index);
        kit.tip(reroll, true).getActor().setText(Messages.getString("Lobby.randomArmy.reroll"));
        UiKit.onChange(reroll, () -> run(() -> form.reroll(index)));
        UiButton discard = kit.button("button-quiet-compact", "close", null, null);
        discard.setName("army-discard-" + index);
        kit.tip(discard, true).getActor().setText(Messages.getString("Lobby.randomArmy.discard"));
        UiKit.onChange(discard, () -> {
            form.discard(index);
            rebuild();
        });
        actions.add(keep).left().expandX();
        actions.add(reroll).padLeft(space("xs"));
        actions.add(discard).padLeft(space("xs"));
        frame.add(actions).growX().padTop(space("sm")).expandY().bottom();
        return frame;
    }

    private static String format(double tons) {
        return (tons == Math.rint(tons)) ? String.valueOf((long) tons) : String.valueOf(tons);
    }

    private void refresh() {
        totals.setText(Messages.getString("Lobby.randomArmy.totals", form.cards().size(), form.battleValue(),
              format(form.tonnage())));
        add.setDisabled(busy || form.cards().isEmpty() || form.playerName() == null);
        roll.setDisabled(busy || !form.canRoll());
    }

    /** A unit's sprite has been drawn: shows it on its cards. */
    void spriteArrived(String key) {
        for (Image image : images) {
            if (key.equals(image.getName())) {
                image.setDrawable(sprites.apply(key));
            }
        }
    }

    private void confirm() {
        if (busy || form.cards().isEmpty()) {
            return;
        }
        busy = true;
        refresh();
        host.edt(() -> host.add(form), close);
    }

    // ---- For the probe and the tests ----

    RandomArmyForm form() {
        return form;
    }

    @Nullable Actor control(String name) {
        return hand.getStage() == null ? null : hand.getStage().getRoot().findActor(name);
    }
}
