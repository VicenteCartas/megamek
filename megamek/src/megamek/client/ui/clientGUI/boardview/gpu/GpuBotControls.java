/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTraitCard;

/**
 * The bot controls the lobby's bot setup and the battle's bot sheet share, so a bot reads the same in both: the
 * personality cards, the combat posture with its hint, a behaviour slider, and the hint text with its steady slot.
 * Each builder takes its values and reports a user's change; the caller decides what a change does.
 * <p>
 * GPU thread.
 */
final class GpuBotControls {
    /** The sliders the cards' meters show, in their order. */
    static final List<BotSetupForm.Slider> TRAITS = List.of(BotSetupForm.Slider.AGGRESSION,
          BotSetupForm.Slider.BRAVERY, BotSetupForm.Slider.SELF_PRESERVATION, BotSetupForm.Slider.MUTUAL_SUPPORT);
    private static final String[] TRAIT_KEYS = { "aggression", "bravery", "survival", "teamwork" };
    static final CombatPosture[] POSTURES = { CombatPosture.AUTO, CombatPosture.ATTACK, CombatPosture.DEFEND };

    private GpuBotControls() {
    }

    static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    static String colonless(String key) {
        String text = Messages.getString(key).strip();
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    static void tip(UiKit kit, Actor actor, String text) {
        kit.tip(actor, true).getActor().setText(GpuBoardWindow.plainText(text));
    }

    /** Small muted text that wraps. */
    static Label hint(UiKit kit, String text) {
        Label hint = kit.typeLabel(text, "small", "text.muted");
        hint.setWrap(true);
        hint.setAlignment(Align.topLeft);
        return hint;
    }

    /**
     * {@code shown} over hidden copies of each of {@code texts}, so the slot keeps the height of the longest and what
     * is below never moves as the text changes; {@code shown} may be one of several labels in the slot.
     */
    static Stack steady(UiKit kit, List<String> texts, Label... shown) {
        Stack slot = new Stack();
        for (String text : texts) {
            Label sizer = hint(kit, text);
            sizer.setVisible(false);
            slot.add(sizer);
        }
        for (Label each : shown) {
            slot.add(each);
        }
        return slot;
    }

    /**
     * One card per personality in a grid of {@code columns}, each with its preset's meters; a click on a card, or on
     * its reset, reports the personality.
     */
    static final class PersonalityCards {
        private final Table grid = new Table();
        private final Map<Personality, UiTraitCard> cards = new EnumMap<>(Personality.class);
        private final Map<Personality, int[]> own = new EnumMap<>(Personality.class);

        /**
         * @param presets each personality's stored preset, or null when it is gone
         * @param choose  takes the personality a user clicked
         */
        PersonalityCards(UiKit kit, List<Personality> shown, Function<Personality, BehaviorSettings> presets,
              int columns, Consumer<Personality> choose) {
            grid.top().left();
            float gap = space("md");
            for (int index = 0; index < shown.size(); index++) {
                Personality personality = shown.get(index);
                UiTraitCard card = kit.traitCard(personality.label(), personality.blurb());
                card.setName("bot-personality-" + personality.key());
                BehaviorSettings preset = presets.apply(personality);
                if (preset != null) {
                    int[] values = new int[TRAITS.size()];
                    for (int trait = 0; trait < TRAITS.size(); trait++) {
                        values[trait] = TRAITS.get(trait).of(preset);
                        card.trait(Messages.getString("Lobby.bot.trait." + TRAIT_KEYS[trait]), values[trait],
                              TRAITS.get(trait).max());
                    }
                    own.put(personality, values);
                }
                card.resetButton().setName("bot-reset-" + personality.key());
                tip(kit, card.resetButton(), Messages.getString("Lobby.bot.resetTip", personality.label()));
                // A click on the card, or on its reset, goes back to the personality's own settings
                UiKit.onChange(card, () -> choose.accept(personality));
                cards.put(personality, card);
                int column = index % columns;
                grid.add(card).growX().uniform().fill().minWidth(0)
                      .padLeft(column == 0 ? 0 : gap).padTop(index < columns ? 0 : gap);
                if (column == columns - 1) {
                    grid.row();
                }
            }
            // A short last row keeps its cards the width of the rest
            for (int index = shown.size(); index % columns != 0; index++) {
                grid.add().growX().uniform().padLeft(gap);
            }
        }

        Table grid() {
            return grid;
        }

        /**
         * Marks {@code shown}'s card chosen, Modified with its reset when {@code modified}, its meters then showing
         * {@code now}; the other cards show their own values.
         *
         * @param now           the current trait values, in {@link #TRAITS}' order
         * @param resetDisabled whether the reset buttons are disabled
         */
        void show(@Nullable Personality shown, boolean modified, int[] now, boolean resetDisabled) {
            cards.forEach((personality, card) -> {
                boolean chosen = personality == shown;
                boolean changed = chosen && modified;
                card.chosen(chosen);
                card.modified(changed ? Messages.getString("Lobby.bot.modifiedTag") : null);
                int[] values = own.get(personality);
                if (values != null) {
                    card.current(changed ? now : values);
                }
                card.resetButton().setDisabled(resetDisabled);
            });
        }

        @Nullable UiTraitCard card(Personality personality) {
            return cards.get(personality);
        }
    }

    /** The combat posture as Auto, Attack and Defend, with a hint for the selected one that never moves what is below. */
    static final class PostureControl {
        private final Table block = new Table();
        private final UiSegmented bar;
        private final Label hint;

        /** @param change takes the posture a user chose */
        PostureControl(UiKit kit, Consumer<CombatPosture> change) {
            block.top().left().defaults().left().growX();
            String[] labels = new String[POSTURES.length];
            for (int index = 0; index < POSTURES.length; index++) {
                labels[index] = POSTURES[index].toString();
            }
            bar = kit.tokenSegmented(true, labels);
            bar.setName("bot-posture");
            for (int index = 0; index < POSTURES.length; index++) {
                bar.option(index).setName("bot-posture-" + POSTURES[index].name().toLowerCase(Locale.ROOT));
            }
            hint = hint(kit, "");
            hint.setName("bot-posture-hint");
            UiKit.onChange(bar, () -> {
                showHint();
                change.accept(selected());
            });
            block.add(kit.formField(colonless("BotConfigDialog.postureLabel"), bar)).row();
            List<String> hints = new ArrayList<>();
            for (CombatPosture each : POSTURES) {
                hints.add(hintText(each));
            }
            block.add(steady(kit, hints, hint)).minWidth(0).padTop(space("xs")).row();
            showHint();
        }

        Table block() {
            return block;
        }

        UiSegmented bar() {
            return bar;
        }

        CombatPosture selected() {
            return POSTURES[Math.max(0, bar.getSelectedIndex())];
        }

        /** Selects {@code posture} without an event. */
        void select(CombatPosture posture) {
            bar.select(Math.max(0, List.of(POSTURES).indexOf(posture)));
            showHint();
        }

        void setDisabled(boolean disabled) {
            bar.setDisabled(disabled);
        }

        private void showHint() {
            hint.setText(hintText(selected()));
        }

        private static String hintText(CombatPosture posture) {
            return Messages.getString("Lobby.bot.posture." + posture.name().toLowerCase(Locale.ROOT) + ".hint");
        }
    }

    /** One behaviour slider: its title with the value, the track, and its ends' words below. */
    static final class SliderRow {
        private final Table row = new Table();
        private final BotSetupForm.Slider kind;
        private final Slider slider;
        private final Label title;

        /** @param change takes each value a user moves the slider to */
        SliderRow(UiKit kit, BotSetupForm.Slider kind, int value, IntConsumer change) {
            this.kind = kind;
            slider = new Slider(kind.min(), kind.max(), 1, false, kit.tokenSliderStyle());
            slider.setName("bot-slider-" + kind.name().toLowerCase(Locale.ROOT));
            slider.setProgrammaticChangeEvents(false);
            slider.setValue(value);
            slider.addListener(new UiSliderInput(slider));
            title = kit.typeLabel(kind.title(value), "small", "text.secondary");
            UiKit.onChange(slider, () -> {
                int now = Math.round(slider.getValue());
                title.setText(kind.title(now));
                change.accept(now);
            });
            tip(kit, slider, kind.tip());
            tip(kit, title, kind.tip());
            row.top().left();
            row.add(title).left().colspan(2).row();
            row.add(slider).growX().colspan(2).padTop(space("xs")).row();
            Label min = kit.typeLabel(kind.minText(), "small", "text.muted");
            Label max = kit.typeLabel(kind.maxText(), "small", "text.muted");
            min.setEllipsis(true);
            max.setEllipsis(true);
            max.setAlignment(Align.right);
            row.add(min).left().growX().uniformX().minWidth(0);
            row.add(max).right().growX().uniformX().minWidth(0).padLeft(space("sm"));
        }

        Table row() {
            return row;
        }

        Slider slider() {
            return slider;
        }

        /** Shows {@code value} without an event. */
        void value(int value) {
            slider.setValue(value);
            title.setText(kind.title(value));
        }

        int value() {
            return Math.round(slider.getValue());
        }
    }
}
