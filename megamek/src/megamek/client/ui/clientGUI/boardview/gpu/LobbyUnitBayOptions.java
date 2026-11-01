/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.OptionEntry;
import megamek.client.lobby.PartialRepairsForm;
import megamek.client.lobby.UnitAbilitiesForm;
import megamek.client.lobby.UnitAbilitiesForm.Slot;
import megamek.client.lobby.UnitQuirksForm;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;

/**
 * The unit bay's option pages, the native take on the Swing pilot options, quirks and partial repairs panels: each
 * option a chip that is on or off, or a small card with its choice, number or torso facings, laid out in a grid under
 * its group's title. The abilities page searches the abilities by name and rules text and shows a conventional
 * infantry trooper's prosthetic choices beside the implant that carries them; the quirks page sets the chassis quirks,
 * positive and negative, and each weapon's. The values are held by the {@link UnitAbilitiesForm},
 * {@link UnitQuirksForm} or {@link PartialRepairsForm}.
 * <p>
 * GPU thread.
 */
final class LobbyUnitBayOptions {
    /** The options in a row of a group. */
    private static final int PER_ROW = 3;

    /** One group's grid, laid out again with the options the search matches. */
    private record Section(Table card, Table grid, List<OptionEntry> entries, List<Actor> cells) {}

    private final UiKit kit;
    private final LobbyUnitBayView bay;
    private final BooleanSupplier editable;
    private final List<Runnable> refreshers = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final Table page = new Table();
    private final Table header = new Table();
    private final Table body = new Table();

    private LobbyUnitBayOptions(UiKit kit, LobbyUnitBayView bay, BooleanSupplier editable) {
        this.kit = kit;
        this.bay = bay;
        this.editable = editable;
        page.top().left().defaults().left().growX();
        header.top().left().defaults().left().growX();
        body.top().left().defaults().left().growX();
        page.add(header).row();
        page.add(body).row();
    }

    /** The abilities page: a search over the ability groups, each a grid of ability chips and cards. */
    static LobbyUnitBayOptions abilities(UiKit kit, UnitAbilitiesForm form, LobbyUnitBayView bay) {
        LobbyUnitBayOptions options = new LobbyUnitBayOptions(kit, bay, form::editable);
        UiKit.TokenSearch search = kit.tokenSearch(Messages.getString("CustomMekDialog.spaFilterPlaceholder"), true);
        options.name(search.field, "bay-ability-search");
        kit.tip(search.field, true).getActor().setText(Messages.getString("CustomMekDialog.spaFilterTooltip"));
        Label count = kit.typeLabel("", "small", "text.secondary");
        Table top = new Table();
        top.left();
        top.add(search).width(LobbyUnitBayView.size("dialog.sm") * 0.6f).padRight(space("md"));
        top.add(count).left();
        options.header.add(top).padBottom(space("md")).row();
        Label notice = options.name(kit.typeLabel("", "small", "status.warning"), "bay-ability-notice");
        notice.setWrap(true);
        options.refreshers.add(() -> notice.setText(form.notice()));
        options.header.add(notice).padBottom(space("sm")).row();
        for (UnitAbilitiesForm.Group group : form.groups()) {
            List<Actor> cells = new ArrayList<>();
            for (OptionEntry entry : group.entries()) {
                cells.add(options.ability(form, entry));
            }
            options.section(group.title(), group.entries(), cells);
        }
        int total = form.entries().size();
        Runnable filter = () -> {
            int shown = options.filter(search.field.getText());
            count.setText(Messages.getString("CustomMekDialog.spaMatchCount", shown, total));
        };
        UiKit.onChange(search.field, filter);
        filter.run();
        return options;
    }

    /** The quirks page: the positive and negative chassis quirks, then each weapon's. */
    static LobbyUnitBayOptions quirks(UiKit kit, UnitQuirksForm form, LobbyUnitBayView bay) {
        LobbyUnitBayOptions options = new LobbyUnitBayOptions(kit, bay, form::editable);
        options.quirkSection("CustomMekDialog.quirksPositiveTitle", form, form.positive(), "bay-quirk-");
        options.quirkSection("CustomMekDialog.quirksNegativeTitle", form, form.negative(), "bay-quirk-");
        if (!form.weapons().isEmpty()) {
            Table weapons = new Table();
            weapons.top().left().defaults().left().growX();
            for (UnitQuirksForm.Weapon weapon : form.weapons()) {
                Table grid = new Table();
                grid.top().left();
                List<Actor> cells = new ArrayList<>();
                for (OptionEntry entry : weapon.entries()) {
                    cells.add(options.quirk(form, entry, "bay-wquirk-" + weapon.number() + "-"));
                }
                LobbyUnitBayOptions.grid(grid, cells, PER_ROW);
                weapons.add(kit.typeLabel(weapon.title(), "small", "text.secondary")).padTop(space("sm")).row();
                weapons.add(grid).padTop(space("xs")).row();
            }
            options.body.add(options.bay.titledCard(LobbyUnitBayView.colonless("CustomMekDialog.quirksWeaponTitle"),
                  weapons)).row();
        }
        return options;
    }

    /** The partial repairs page: a grid of repair chips per group. */
    static LobbyUnitBayOptions repairs(UiKit kit, PartialRepairsForm form, LobbyUnitBayView bay) {
        LobbyUnitBayOptions options = new LobbyUnitBayOptions(kit, bay, form::editable);
        for (PartialRepairsForm.Group group : form.groups()) {
            List<Actor> cells = new ArrayList<>();
            for (OptionEntry entry : group.entries()) {
                cells.add(options.chip("bay-repair-" + key(entry), entry, value -> form.set(entry, value)));
            }
            options.section(group.title(), group.entries(), cells);
        }
        return options;
    }

    private static float space(String name) {
        return LobbyUnitBayView.space(name);
    }

    private static String key(OptionEntry entry) {
        return entry.name().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private <T extends Actor> T name(T actor, String name) {
        return bay.name(actor, name);
    }

    private void changed() {
        bay.refresh();
    }

    private void quirkSection(String titleKey, UnitQuirksForm form, List<OptionEntry> entries, String prefix) {
        if (entries.isEmpty()) {
            return;
        }
        List<Actor> cells = new ArrayList<>();
        for (OptionEntry entry : entries) {
            cells.add(quirk(form, entry, prefix));
        }
        section(LobbyUnitBayView.colonless(titleKey), entries, cells);
    }

    private void section(String title, List<OptionEntry> entries, List<Actor> cells) {
        Table grid = new Table();
        grid.top().left();
        Table card = bay.titledCard(title, grid);
        sections.add(new Section(card, grid, entries, cells));
        grid(grid, cells, PER_ROW);
        body.add(card).row();
    }

    /** Lays each section out with the options matching {@code filter} and leaves out those left empty. */
    private int filter(String filter) {
        int shown = 0;
        body.clearChildren();
        for (Section section : sections) {
            List<Actor> matching = new ArrayList<>();
            for (int i = 0; i < section.entries().size(); i++) {
                if (section.entries().get(i).matches(filter)) {
                    matching.add(section.cells().get(i));
                }
            }
            section.grid().clearChildren();
            grid(section.grid(), matching, PER_ROW);
            if (!matching.isEmpty()) {
                body.add(section.card()).row();
            }
            shown += matching.size();
        }
        page.invalidateHierarchy();
        return shown;
    }

    private Actor ability(UnitAbilitiesForm form, OptionEntry entry) {
        String name = "bay-ability-" + key(entry);
        Actor control = switch (entry.kind()) {
            case BOOLEAN -> chip(name, entry, value -> form.set(entry, value));
            case INTEGER -> titled(entry, stepper(name + "-number", entry::number, 0, OptionEntry.MAX_NUMBER,
                  value -> form.number(entry, value), form::editable));
            case CHOICE -> titled(entry, select(name + "-choice", entry.choices(), entry::choice,
                  index -> form.choice(entry, index), form::editable));
            default -> titled(entry, text(name + "-text", entry, entry::text, form::editable));
        };
        List<Slot> slots = form.slots(entry);
        if (slots.isEmpty()) {
            return control;
        }
        Table cell = new Table();
        cell.top().left().defaults().left().growX();
        cell.add(control).row();
        Table extras = new Table();
        extras.top().left().defaults().left().growX();
        List<String> choices = form.prostheticChoices();
        for (Slot slot : slots) {
            String slotName = "bay-slot-" + slot.name().toLowerCase(Locale.ROOT).replace('_', '-');
            Table line = new Table();
            line.left();
            line.add(select(slotName, choices, () -> form.prosthetic(slot), index -> form.prosthetic(slot, index),
                  () -> form.prostheticEnabled(slot))).growX().padRight(space("xs"));
            if (slot.counted()) {
                line.add(stepper(slotName + "-count", () -> form.count(slot), 1, 2, value -> form.count(slot, value),
                      () -> form.countEnabled(slot)));
            }
            extras.add(line).padTop(space("xs")).row();
        }
        kit.tip(extras, true).getActor().setText(Messages.getString("CustomMekDialog.ProstheticTypeTooltip"));
        refreshers.add(() -> extras.setVisible(entry.on()));
        cell.add(extras).row();
        return cell;
    }

    private Actor quirk(UnitQuirksForm form, OptionEntry entry, String prefix) {
        String name = prefix + key(entry);
        return switch (entry.kind()) {
            case BOOLEAN -> chip(name, entry, value -> form.set(entry, value));
            case INTEGER -> titled(entry, stepper(name + "-number", entry::number, 0, OptionEntry.MAX_NUMBER,
                  value -> form.number(entry, value), form::editable));
            case LOCATIONS -> {
                Table chips = new Table();
                chips.left();
                for (String location : OptionEntry.LOCATIONS) {
                    UiButton chip = toggle(name + "-" + location.toLowerCase(Locale.ROOT), location,
                          () -> entry.location(location), value -> form.location(entry, location, value));
                    kit.tip(chip, true).getActor()
                          .setText(Messages.getString("DialogOptionComponentYPanel.torsoMount." + location));
                    chips.add(chip).padRight(space("xs"));
                }
                yield titled(entry, chips);
            }
            default -> titled(entry, text(name + "-text", entry, value -> form.text(entry, value), form::editable));
        };
    }

    // ---- Controls ----

    /** Lays {@code cells} out {@code columns} to a row, of even width. */
    private static void grid(Table content, List<Actor> cells, int columns) {
        for (int i = 0; i < cells.size(); i++) {
            content.add(cells.get(i)).prefWidth(0).minWidth(0).growX().uniformX().top().left()
                  .padRight(space("sm")).padBottom(space("xs"));
            if (i % columns == columns - 1) {
                content.row();
            }
        }
        for (int i = cells.size() % columns; i > 0 && i < columns; i++) {
            content.add().prefWidth(0).minWidth(0).growX().uniformX();
        }
    }

    /** An option with a value beside its name, as a small card: the name over the control. */
    private Table titled(OptionEntry entry, Actor control) {
        Table card = kit.panel(true);
        card.top().left().defaults().left();
        card.pad(space("sm"));
        Label label = kit.typeLabel(entry.label(), "small", entry.dimmed() ? "text.muted" : "text.secondary");
        label.setEllipsis(true);
        card.add(label).growX().minWidth(0).row();
        card.add(control).growX().padTop(space("xs")).row();
        if (!entry.description().isBlank()) {
            kit.tip(card, true).getActor().setText(entry.description());
        }
        return card;
    }

    /** A yes-or-no option as a chip that is on or off, its rules text as its tooltip. */
    private UiButton chip(String name, OptionEntry entry, Consumer<Boolean> set) {
        UiButton chip = toggle(name, entry.label(), entry::on, set);
        if (!entry.description().isBlank()) {
            kit.tip(chip, true).getActor().setText(entry.description());
        }
        if (entry.dimmed()) {
            chip.getColor().a = 0.7f;
        }
        return chip;
    }

    private UiButton toggle(String name, String text, BooleanSupplier value, Consumer<Boolean> set) {
        UiButton chip = name(kit.button("button-toggle-compact", null, text, null), name);
        chip.getLabel().setEllipsis(true);
        chip.getLabelCell().minWidth(0);
        chip.setProgrammaticChangeEvents(false);
        UiKit.onChange(chip, () -> {
            set.accept(!value.getAsBoolean());
            changed();
        });
        refreshers.add(() -> {
            chip.setChecked(value.getAsBoolean());
            chip.setDisabled(!editable.getAsBoolean());
        });
        return chip;
    }

    private UiSelect select(String name, List<String> items, IntSupplier index, IntConsumer set,
          BooleanSupplier enabled) {
        UiSelect select = name(kit.tokenSelect(true, items.toArray(String[]::new)), name);
        UiKit.onChange(select, () -> {
            set.accept(select.getSelectedIndex());
            changed();
        });
        refreshers.add(() -> {
            int current = index.getAsInt();
            if (current >= 0 && current < select.count() && current != select.getSelectedIndex()) {
                select.select(current);
            }
            select.setDisabled(!enabled.getAsBoolean());
        });
        return select;
    }

    private UiStepper stepper(String name, IntSupplier value, int min, int max, IntConsumer set,
          BooleanSupplier enabled) {
        UiStepper stepper = name(kit.stepper(null, value.getAsInt(), min, max), name);
        name(stepper.down(), name + "-down");
        name(stepper.up(), name + "-up");
        UiKit.onChange(stepper, () -> {
            set.accept(stepper.value());
            changed();
        });
        refreshers.add(() -> {
            stepper.value(value.getAsInt());
            boolean on = enabled.getAsBoolean();
            stepper.down().setDisabled(!on || stepper.value() <= min);
            stepper.up().setDisabled(!on || stepper.value() >= max);
        });
        return stepper;
    }

    private UiTextField text(String name, OptionEntry entry, Consumer<String> set, BooleanSupplier enabled) {
        UiTextField field = name(kit.input(entry.text(), true), name);
        UiKit.onChange(field, () -> {
            set.accept(field.getText());
            changed();
        });
        refreshers.add(() -> field.setDisabled(!enabled.getAsBoolean()));
        return field;
    }

    /** Shows the form's state. */
    void refresh() {
        refreshers.forEach(Runnable::run);
    }

    /** The page's content. */
    Actor page() {
        refresh();
        return page;
    }
}
