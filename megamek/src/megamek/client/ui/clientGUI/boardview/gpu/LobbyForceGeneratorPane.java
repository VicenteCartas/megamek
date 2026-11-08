/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.lobby.ForceGeneratorForm;
import megamek.client.ratgenerator.ForceComposition;
import megamek.client.ratgenerator.ForceDescriptor;
import megamek.client.ratgenerator.ForceEdits;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.common.units.Entity;

/**
 * The Force Generator's command, beside its choices in the random army: what the rolls built so far, with Generate
 * to roll another force into it and Export to write it to a unit list; the command's organisation as a tree, found by
 * words; the chosen node's orders - its readout, a ship swapped for another design, the formation re-rolled as or a
 * formation added of the one picked in the formation mix below; each branch left out or brought back by its row's eye;
 * and what the command is made of, by unit type and weight class.
 * <p>
 * GPU thread. The rolls and the edits run on the Swing thread through {@link Owner#edt}.
 */
final class LobbyForceGeneratorPane {
    private static final String INCLUDE = "include";

    /** What the pane asks of the random army. */
    interface Owner {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Rolls a force into {@code form}'s command; {@code done} hears on the GPU thread whether it found units. */
        void generate(ForceGeneratorForm form, Consumer<Boolean> done);

        /** Asks {@code text}; {@code yes} runs on the GPU thread once the player agreed. */
        void confirm(String title, String text, Runnable yes);

        /** Tells the player {@code text}. */
        void message(String title, String text);

        /** Opens {@code unit}'s readout over the random army. */
        void readout(Entity unit);

        /** Writes the command to a unit list the player chooses. */
        void export(ForceGeneratorForm form);

        /** The pane is busy or done: the random army's other controls follow. */
        void busy(boolean busy);
    }

    private final UiKit kit;
    private final ForceGeneratorForm form;
    private final Owner owner;
    private final Table root = new Table();
    private final Label headline;
    private final Label detail;
    private final UiButton generate;
    private final UiButton export;
    private final UiTree tree;
    private final Table orders = new Table();
    private final Table palette = new Table();
    private final Table composition = new Table();
    private final List<UiButton> controls = new ArrayList<>();
    private ForceGeneratorForm.Stats stats = ForceGeneratorForm.Stats.NONE;
    private String selected = "";
    private boolean busy;

    LobbyForceGeneratorPane(UiKit kit, ForceGeneratorForm form, float width, Actor player, Owner owner) {
        this.kit = kit;
        this.form = form;
        this.owner = owner;
        root.setName("army-force");
        root.top().left().defaults().left().growX();
        root.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.randomArmy.force.command"),
              "text.secondary")).row();
        headline = kit.typeLabel("", "title", "text.primary");
        headline.setName("army-force-headline");
        headline.setEllipsis(true);
        root.add(headline).minWidth(0).padTop(space("xs")).row();
        detail = kit.typeLabel("", "small", "accent.primary");
        detail.setName("army-force-totals");
        detail.setEllipsis(true);
        root.add(detail).minWidth(0).row();

        Table bar = new Table();
        bar.bottom().left();
        if (player != null) {
            bar.add(player).padRight(space("md")).width(size("control.lg") * 5);
        }
        generate = kit.button("button-secondary", "layers", Messages.getString("ForceGeneratorDialog.generate"),
              null);
        generate.setName("army-force-generate");
        kit.tip(generate, true).getActor().setText(Messages.getString("Lobby.randomArmy.force.generateTip"));
        UiKit.onChange(generate, this::generate);
        bar.add(generate).bottom();
        export = kit.button("button-quiet", "folder", Messages.getString("Lobby.randomArmy.force.export"), null);
        export.setName("army-force-export");
        kit.tip(export, true).getActor().setText(Messages.getString("Lobby.randomArmy.force.exportTip"));
        UiKit.onChange(export, () -> {
            if (!busy && !form.empty()) {
                owner.export(form);
            }
        });
        bar.add(export).bottom().padLeft(space("sm"));
        bar.add().growX();
        root.add(bar).padTop(space("md")).row();

        tree = new UiTree(kit, UiTheme.TOKEN);
        tree.setName("army-force-tree");
        tree.empty(Messages.getString("Lobby.randomArmy.force.empty"));
        tree.onSelect(id -> {
            if (!busy) {
                selected = id;
                showOrders();
                sampleMix();
            }
        });
        tree.onAction((id, action) -> {
            ForceDescriptor node = form.node(id);
            if (!busy && node != null && action.endsWith(INCLUDE)) {
                run(() -> form.toggleIncluded(node));
            }
        });
        Table treeControls = tree.controls(Messages.getString("ForceGeneratorDialog.search.placeholder"));
        treeControls.setName("army-force-search");
        root.add(treeControls).padTop(space("lg")).row();
        ScrollPane treeScroll = kit.scrollList(tree, true);
        treeScroll.setScrollingDisabled(true, false);
        treeScroll.setName("army-force-tree-scroll");
        root.add(treeScroll).width(width).height(size("dialog.sm") * 0.6f).padTop(space("xs")).row();

        orders.setName("army-force-orders");
        orders.left();
        root.add(orders).padTop(space("sm")).row();

        palette.setName("army-force-mix");
        palette.top().left().defaults().left().growX();
        root.add(palette).padTop(space("lg")).row();

        composition.setName("army-force-composition");
        composition.top().left();
        root.add(composition).padTop(space("lg")).row();
        rebuild();
    }

    Actor actor() {
        return root;
    }

    boolean busy() {
        return busy;
    }

    /** Whether the command has units for Add to game. */
    boolean canAdd() {
        return !busy && stats.units() > 0;
    }

    /** The command was cleared: the pane shows it empty. */
    void cleared() {
        stats = ForceGeneratorForm.Stats.NONE;
        selected = "";
        rebuild();
    }

    // ---- Work

    private void generate() {
        if (busy) {
            return;
        }
        busy(true);
        owner.generate(form, found -> {
            if (!found) {
                List<String> notice = ForceGeneratorForm.insufficientData();
                owner.message(notice.get(0), notice.get(1));
            }
            recount();
        });
    }

    /** Runs an edit on the Swing thread, then counts the command and shows it anew. */
    private void run(Runnable work) {
        if (busy) {
            return;
        }
        busy(true);
        owner.edt(() -> {
            work.run();
            stats = form.stats();
        }, () -> {
            busy(false);
            rebuild();
        });
    }

    /** Counts the command on the Swing thread, the Battle Value being calculated there, and shows it anew. */
    private void recount() {
        owner.edt(() -> {
            stats = form.stats();
            form.sampleMix(form.node(selected) == null ? form.top() : form.node(selected));
        }, () -> {
            busy(false);
            rebuild();
        });
    }

    private void busy(boolean next) {
        busy = next;
        tree.setTouchable(next ? Touchable.disabled : Touchable.enabled);
        refresh();
        owner.busy(next);
    }

    /** Samples the formation mix for the chosen node's shape, or the command's, then shows the palette. */
    private void sampleMix() {
        if (form.empty()) {
            return;
        }
        ForceDescriptor context = form.node(selected) == null ? form.top() : form.node(selected);
        busy(true);
        owner.edt(() -> form.sampleMix(context), () -> {
            busy(false);
            showPalette();
            showOrders();
        });
    }

    // ---- Showing the command

    private void rebuild() {
        if (form.node(selected) == null) {
            selected = "";
        }
        ForceDescriptor top = form.top();
        tree.nodes(top == null ? List.of() : List.of(node(top)));
        if (!selected.isEmpty()) {
            tree.select(selected);
        }
        String organization = form.organization();
        String faction = form.rolledFaction();
        headline.setText(top == null ? Messages.getString("Lobby.randomArmy.force.noCommand")
              : join(organization, faction));
        detail.setText(top == null ? "" : join(form.rolledRating(), Messages.getString("Lobby.randomArmy.totals",
              stats.units(), stats.battleValue(), format(stats.tons()))));
        showOrders();
        showPalette();
        showComposition();
        refresh();
    }

    private UiTree.Node node(ForceDescriptor descriptor) {
        String id = form.id(descriptor);
        boolean included = descriptor.isIncluded();
        String line = ForceGeneratorForm.detail(descriptor);
        if (!included) {
            line = join(Messages.getString("Lobby.randomArmy.force.left"), line);
        }
        UiTree.Node node = new UiTree.Node(id, ForceGeneratorForm.label(descriptor)).detail(line)
              .icon(!included ? "hide" : descriptor.isElement() ? "unit" : "account-tree")
              .keywords(ForceGeneratorForm.keywords(descriptor))
              .action(new UiTree.Action(id + "-" + INCLUDE, included ? "show" : "hide",
                    Messages.getString(included ? "Lobby.randomArmy.force.leaveOut"
                          : "Lobby.randomArmy.force.bringBack"), false));
        List<UiTree.Node> children = new ArrayList<>();
        for (ForceDescriptor child : ForceGeneratorForm.children(descriptor)) {
            children.add(node(child));
        }
        return node.children(children);
    }

    /** The chosen node's orders: its readout, a ship swapped, its formation re-rolled or one added under it. */
    private void showOrders() {
        orders.clearChildren();
        controls.clear();
        ForceDescriptor node = form.node(selected);
        if (node == null) {
            if (!form.empty()) {
                orders.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.force.pick"), "small", "text.muted"))
                      .left();
            }
            return;
        }
        Label name = kit.typeLabel(ForceGeneratorForm.label(node), "body.strong", "text.primary");
        name.setEllipsis(true);
        orders.add(name).left().minWidth(0).growX().padRight(space("sm"));
        Entity unit = node.getEntity();
        if (node.isElement() && unit != null) {
            UiButton readout = order("army-force-readout", "info", "Lobby.randomArmy.readout");
            UiKit.onChange(readout, () -> owner.readout(unit));
        }
        if (ForceGeneratorForm.ship(node)) {
            UiButton reroll = order("army-force-reroll-ship", "replay", "ForceGeneratorDialog.rerollShip");
            kit.tip(reroll, true).getActor().setText(ForceGeneratorForm.plainText(
                  Messages.getString("ForceGeneratorDialog.rerollShip.tooltip")));
            UiKit.onChange(reroll, () -> run(() -> form.rerollShip(node)));
        }
        if (ForceGeneratorForm.formationNode(node)) {
            UiButton change = edit("army-force-change", "tune", "ForceGeneratorDialog.changeFormation.to",
                  form.changeBlocker(node));
            if (form.brush() != null && form.changeBlocker(node) == null && !form.offers(node)) {
                kit.tip(change, true).getActor().setText(Messages.getString(
                      "ForceGeneratorDialog.changeFormation.notOffered", form.brushName()));
            }
            UiKit.onChange(change, () -> {
                if (form.changeBlocker(node) == null) {
                    owner.confirm(Messages.getString("ForceGeneratorDialog.changeFormation"),
                          form.changeQuestion(node), () -> edit(node, () -> form.changeFormation(node)));
                }
            });
        }
        if (ForceGeneratorForm.holdsFormations(node)) {
            UiButton add = edit("army-force-add", "add", "ForceGeneratorDialog.addFormation.of",
                  form.addBlocker(node));
            UiKit.onChange(add, () -> {
                if (form.addBlocker(node) == null) {
                    owner.confirm(Messages.getString("ForceGeneratorDialog.addFormation"), form.addQuestion(node),
                          () -> edit(node, () -> form.addFormation(node)));
                }
            });
        }
    }

    private UiButton order(String name, String icon, String textKey) {
        UiButton button = kit.button("button-secondary-compact", icon, Messages.getString(textKey), null);
        button.setName(name);
        controls.add(button);
        orders.add(button).padLeft(space("xs"));
        return button;
    }

    /** A formation order: named for the picked formation, or disabled with why. */
    private UiButton edit(String name, String icon, String textKey, @Nullable ForceGeneratorForm.Blocker blocker) {
        String text = form.brush() == null ? Messages.getString(
              textKey.endsWith(".to") ? "ForceGeneratorDialog.changeFormation" : "ForceGeneratorDialog.addFormation")
              : Messages.getString(textKey, form.brushName());
        UiButton button = kit.button("button-secondary-compact", icon, text, null);
        button.setName(name);
        controls.add(button);
        if (blocker != null) {
            button.setDisabled(true);
            kit.tip(button, true).getActor().setText(blocker.message());
        }
        orders.add(button).padLeft(space("xs"));
        return button;
    }

    /** Runs a formation edit, then tells the player when it did not go as asked. */
    private void edit(ForceDescriptor node, java.util.function.Supplier<ForceEdits.Result> work) {
        ForceEdits.Result[] result = new ForceEdits.Result[1];
        if (busy) {
            return;
        }
        busy(true);
        owner.edt(() -> {
            result[0] = work.get();
            stats = form.stats();
        }, () -> {
            busy(false);
            rebuild();
            List<String> notice = result[0] == null ? null : form.outcome(result[0], node);
            if (notice != null) {
                owner.message(notice.get(0), notice.get(1));
            }
        });
    }

    /** The formations this command could be made of, by family, the one picked lit as the brush. */
    private void showPalette() {
        palette.clearChildren();
        if (form.empty()) {
            return;
        }
        palette.add(kit.roleTypeLabel("overline", Messages.getString("ForceGeneratorDialog.formationMix.title"),
              "text.secondary")).row();
        Label explain = kit.typeLabel(Messages.getString("Lobby.randomArmy.force.mixExplain"), "small",
              "text.muted");
        explain.setWrap(true);
        palette.add(explain).padTop(space("xs")).row();
        ForceGeneratorForm.Palette families = form.palette();
        if (families.empty()) {
            palette.add(kit.typeLabel(Messages.getString("ForceGeneratorDialog.formationMix.nothingToTweak"),
                  "small", "text.muted")).padTop(space("sm")).row();
            return;
        }
        List<UiFilterChip> chips = new ArrayList<>();
        families(families.ground(), chips);
        if (!families.aerospace().isEmpty()) {
            palette.add(kit.roleTypeLabel("overline",
                  Messages.getString("ForceGeneratorDialog.formationMix.domain.aerospace"), "text.muted"))
                  .padTop(space("md")).row();
            families(families.aerospace(), chips);
        }
    }

    private void families(List<ForceGeneratorForm.Family> families, List<UiFilterChip> chips) {
        for (ForceGeneratorForm.Family family : families) {
            if (family.heading() != null) {
                palette.add(kit.typeLabel(ForceGeneratorForm.formationTypeName(family.heading()), "small",
                      "text.secondary")).padTop(space("sm")).row();
            }
            HorizontalGroup group = new HorizontalGroup();
            group.wrap(true).space(space("sm")).wrapSpace(space("xs")).rowLeft();
            for (String formation : family.formations()) {
                UiFilterChip chip = kit.filterChip(ForceGeneratorForm.formationTypeName(formation));
                chip.setName("army-force-mix-" + formation.replace(' ', '-').toLowerCase());
                chip.setProgrammaticChangeEvents(false);
                chip.setChecked(formation.equals(form.brush()));
                kit.tip(chip, true).getActor().setText(form.describe(formation));
                UiKit.onChange(chip, () -> {
                    form.brush(chip.isChecked() ? formation : null);
                    for (UiFilterChip other : chips) {
                        if (other != chip) {
                            other.setChecked(false);
                        }
                    }
                    showOrders();
                    refresh();
                });
                chips.add(chip);
                group.addActor(chip);
            }
            palette.add(group).padTop(space("xs")).row();
        }
    }

    /** What the command is made of: a row per unit type, a column per weight class, and the totals. */
    private void showComposition() {
        composition.clearChildren();
        List<ForceComposition.Row> rows = form.composition();
        if (rows.isEmpty()) {
            return;
        }
        composition.add(kit.roleTypeLabel("overline", Messages.getString("ForceGeneratorDialog.summary.title"),
              "text.secondary")).colspan(6).left().row();
        String[] heads = { "unitType", "light", "medium", "heavy", "assault", "total" };
        for (int column = 0; column < heads.length; column++) {
            Label head = kit.typeLabel(Messages.getString("ForceGeneratorDialog.summary." + heads[column]), "small",
                  "text.muted");
            head.setAlignment(column == 0 ? Align.left : Align.right);
            composition.add(head).growX().padTop(space("xs")).padLeft(column == 0 ? 0 : space("md"))
                  .left();
        }
        composition.row();
        for (int index = 0; index < rows.size(); index++) {
            ForceComposition.Row row = rows.get(index);
            boolean total = index == rows.size() - 1;
            String type = total ? "body.strong" : "body";
            composition.add(kit.typeLabel(row.label(), type, "text.primary")).left().padTop(space("xs"));
            for (String cell : row.weight()) {
                Label value = kit.typeLabel(cell, type, total ? "text.primary" : "text.secondary");
                value.setAlignment(Align.right);
                composition.add(value).growX().padLeft(space("md")).padTop(space("xs"));
            }
            Label sum = kit.typeLabel(row.total(), type, "accent.primary");
            sum.setAlignment(Align.right);
            composition.add(sum).growX().padLeft(space("md")).padTop(space("xs")).row();
        }
    }

    private void refresh() {
        generate.setDisabled(busy || form.factions().isEmpty());
        export.setDisabled(busy || form.empty());
        for (UiButton control : controls) {
            if (busy) {
                control.setDisabled(true);
            }
        }
        if (!busy) {
            showOrdersEnabled();
        }
    }

    /** Re-enables the orders after a busy spell, each as its node allows. */
    private void showOrdersEnabled() {
        ForceDescriptor node = form.node(selected);
        for (UiButton control : controls) {
            String name = control.getName();
            if (node == null) {
                continue;
            }
            if ("army-force-change".equals(name)) {
                control.setDisabled(form.changeBlocker(node) != null);
            } else if ("army-force-add".equals(name)) {
                control.setDisabled(form.addBlocker(node) != null);
            } else {
                control.setDisabled(false);
            }
        }
    }

    /** The parts that are there, between middle dots. */
    private static String join(@Nullable String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!joined.isEmpty()) {
                    joined.append(" \u00B7 ");
                }
                joined.append(part);
            }
        }
        return joined.toString();
    }

    private static String format(double tons) {
        return (tons == Math.rint(tons)) ? String.valueOf((long) tons) : String.valueOf(tons);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    // ---- For the probe and the tests

    ForceGeneratorForm form() {
        return form;
    }

    UiTree tree() {
        return tree;
    }

    void select(String id) {
        tree.select(id);
        selected = id;
        showOrders();
        refresh();
    }
}
