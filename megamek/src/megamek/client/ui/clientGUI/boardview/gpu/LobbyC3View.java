/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.C3NetworkBoard;
import megamek.client.lobby.C3NetworkBoard.Network;
import megamek.client.lobby.C3NetworkBoard.Node;
import megamek.client.lobby.C3NetworkBoard.NodeKind;
import megamek.client.lobby.C3NetworkBoard.PeerSeed;
import megamek.client.lobby.C3NetworkBoard.Picture;
import megamek.client.lobby.C3NetworkBoard.PlanCard;
import megamek.client.lobby.C3NetworkBoard.RosterGroup;
import megamek.client.lobby.C3NetworkBoard.RosterUnit;
import megamek.client.lobby.C3NetworkBoard.Target;
import megamek.client.lobby.C3NetworkBoard.Tint;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;

/**
 * The lobby's C3 network manager, in the forces' place: the configuration templates and Plan Force over the network
 * diagrams, drawn as the rulebook's C3 Configuration Diagram draws them (the company master on top, its lance masters
 * and direct slaves under it, each lance's slaves under its master; a peer network as a chain), the staged plan cards
 * and the new-network targets, and a tray of the unassigned units below with the actions.
 *
 * <p>Units move by drag and drop, as in the Swing dialog, or by clicks: a click picks a unit, and a click on an open
 * slot, a free link, a new-network target or a plan card's header places the picked units; a click on the tray sends
 * them back. Every action runs on the Swing thread through the {@link Host}, and the window sends the board anew.</p>
 */
final class LobbyC3View {
    /** The manager's centre view key. */
    static final String KEY = "c3";

    /** The window's side of the view. */
    interface Host {
        /** Runs {@code action} on the board on the Swing thread; a fresh picture follows. */
        void act(Consumer<C3NetworkBoard> action);

        /** Asks before {@code yes} runs. */
        void confirm(String title, String text, Runnable yes);
    }

    private enum Link { BUS, LINK }

    private record Edge(Actor from, Actor to, Link link, Color colour) {}

    private static final int PEERS_PER_ROW = 6;

    private final UiKit kit;
    private final Host host;
    private final IntFunction<Drawable> sprites;
    private final Table body = new Table();
    private final DragAndDrop dragAndDrop = new DragAndDrop();
    private final Set<Integer> selection = new LinkedHashSet<>();
    private final Map<Integer, List<Table>> chips = new HashMap<>();
    private final Map<Table, String> chipFaces = new HashMap<>();
    private final UiButton[] templates = new UiButton[4];
    private final UiButton planForce;
    private final UiChoice autoAssign;
    private final UiButton reset;
    private final UiButton build;
    private final UiButton discard;
    private final UiButton autoAssignPlan;
    private final UiButton peer;
    private final UiButton disconnect;
    private final Label forcePlan;
    private final Label status;
    private final Table diagrams = new Table();
    private final ScrollPane diagramScroll;
    private final Table tray = new Table();
    private final Table actions = new Table();
    private final Table trayUnits = new Table();
    private final Label trayCount;
    private @Nullable Picture shown;

    LobbyC3View(UiKit kit, Host host, IntFunction<Drawable> sprites) {
        this.kit = kit;
        this.host = host;
        this.sprites = sprites;
        body.top().pad(space("md"), space("lg"), space("md"), space("lg"));

        Table toolbar = new Table();
        toolbar.left();
        toolbar.add(kit.roleTypeLabel("overline", Messages.getString("C3NetworkManagerDialog.templates"),
              "text.secondary")).padRight(space("sm"));
        for (int index = 0; index < templates.length; index++) {
            int number = index + 1;
            UiButton template = kit.button("button-toggle" + UiTheme.COMPACT, null, String.valueOf(number), null);
            template.setName("c3-config-" + number);
            UiKit.onChange(template, () -> startTemplate(number));
            templates[index] = template;
            toolbar.add(template).padRight(space("xs"));
        }
        planForce = kit.button("button-secondary" + UiTheme.COMPACT, "layers",
              Messages.getString("C3NetworkManagerDialog.btnAutoForm"), null);
        planForce.setName("c3-plan-force");
        tip(planForce, "C3NetworkManagerDialog.btnAutoForm.tooltip");
        UiKit.onChange(planForce, this::planForce);
        toolbar.add(planForce).padLeft(space("md"));
        toolbar.add().growX();
        autoAssign = kit.check(Messages.getString("C3NetworkManagerDialog.chkAutoAssign"), true);
        autoAssign.setName("c3-auto-check");
        tip(autoAssign, "C3NetworkManagerDialog.chkAutoAssign.tooltip");
        UiKit.onChange(autoAssign, () -> {
            boolean on = autoAssign.isChecked();
            host.act(board -> board.autoAssign(on));
        });
        toolbar.add(autoAssign).padRight(space("md"));
        reset = kit.button("button-quiet" + UiTheme.COMPACT, "undo",
              Messages.getString("C3NetworkManagerDialog.btnReset"), null);
        reset.setName("c3-reset");
        tip(reset, "C3NetworkManagerDialog.btnReset.tooltip");
        UiKit.onChange(reset, this::reset);
        toolbar.add(reset);
        body.add(toolbar).growX().row();

        Table guide = new Table();
        forcePlan = kit.typeLabel("", "small", "text.secondary");
        forcePlan.setEllipsis(true);
        guide.add(forcePlan).growX().minWidth(0).left();
        Actor info = kit.icon("info", size("icon.md"), UiTheme.tokenColor("text.muted"));
        info.setName("c3-hint");
        tip(info, "C3NetworkManagerDialog.clickHint");
        guide.add(info).size(size("icon.md")).padLeft(space("sm"));
        body.add(guide).growX().padTop(space("sm")).row();

        diagrams.top();
        diagramScroll = kit.scrollList(diagrams, true, false);
        diagramScroll.setScrollingDisabled(true, false);
        diagramScroll.setName("c3-diagrams");
        body.add(diagramScroll).grow().minHeight(0).padTop(space("md")).row();

        tray.setName("c3-tray");
        tray.setBackground(kit.skin.getDrawable("token-panel-plain"));
        tray.pad(space("sm"), space("md"), space("sm"), space("md"));
        Table trayHeader = new Table();
        trayHeader.add(kit.roleTypeLabel("overline", Messages.getString("C3NetworkManagerDialog.unassignedUnits"),
              "text.secondary")).left();
        trayCount = kit.typeLabel("", "small", "text.muted");
        trayHeader.add(trayCount).left().padLeft(space("sm")).growX();
        tray.add(trayHeader).growX().row();
        trayUnits.top().left();
        ScrollPane trayScroll = kit.scrollList(trayUnits, true, false);
        trayScroll.setScrollingDisabled(false, true);
        trayScroll.setName("c3-tray-scroll");
        tray.add(trayScroll).growX().minHeight(Value.prefHeight).padTop(space("xs"));
        tray.setTouchable(Touchable.enabled);
        tray.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!inChip(event.getTarget())) {
                    place(new Target.Roster());
                }
            }
        });
        body.add(tray).growX().padTop(space("md")).row();

        status = kit.typeLabel("", "small", "text.secondary");
        status.setWrap(true);
        status.setName("c3-status");
        actions.add(status).growX().left().padRight(space("md"));
        autoAssignPlan = action("button-secondary", "tune", "btnAutoAssignPlan", "c3-autoassign",
              () -> host.act(C3NetworkBoard::autoAssignPlans));
        discard = action("button-quiet", "close", "btnDiscardPlan", "c3-discard",
              () -> host.act(C3NetworkBoard::discardPlan));
        build = action("button-primary", "check", "btnBuild", "c3-build", () -> host.act(C3NetworkBoard::build));
        peer = action("button-secondary", "group", "btnFormPeerNetwork", "c3-peer", () -> {
            List<Integer> ids = List.copyOf(selection);
            selection.clear();
            host.act(board -> board.formPeerNetwork(ids));
        });
        disconnect = action("button-secondary", "unlock", "btnDisconnect", "c3-disconnect", () -> {
            List<Integer> ids = List.copyOf(selection);
            selection.clear();
            host.act(board -> board.disconnect(ids));
        });
        body.add(actions).growX().padTop(space("md"));
    }

    /** The view, to show in the centre. */
    Table body() {
        return body;
    }

    /** The picture last shown; null before the first. */
    @Nullable Picture shown() {
        return shown;
    }

    /** Shows the board anew, unless it is as shown; the units it opened on are picked. */
    void show(Picture picture) {
        if (picture.equals(shown)) {
            return;
        }
        if (!picture.selection().isEmpty()) {
            selection.clear();
            selection.addAll(picture.selection());
        }
        shown = picture;
        rebuild();
    }

    /** Draws the board again, as after the units' sprites arrive. */
    void redraw() {
        if (shown != null) {
            rebuild();
        }
    }

    /** The units picked, in the order picked. */
    List<Integer> selected() {
        return List.copyOf(selection);
    }

    /** Forgets the board and the picked units, as the manager leaves the centre or opens afresh. */
    void closed() {
        shown = null;
        selection.clear();
    }

    private void rebuild() {
        Picture picture = shown;
        float scrollX = diagramScroll.getScrollX();
        float scrollY = diagramScroll.getScrollY();
        dragAndDrop.clear();
        dragAndDrop.addTarget(dropTarget(tray, new Target.Roster(), "token-panel-plain"));
        chips.clear();
        chipFaces.clear();
        Set<Integer> present = new LinkedHashSet<>();
        picture.rosterUnits().forEach(unit -> present.add(unit.id()));
        picture.networks().forEach(network -> present.addAll(network.members()));
        picture.plans().forEach(card -> present.addAll(card.root().unitIds()));
        selection.retainAll(present);

        for (int index = 0; index < templates.length; index++) {
            templates[index].setChecked(picture.templateLit() == index + 1);
            kit.tip(templates[index], true).getActor().setText(picture.templateTips().get(index));
        }
        planForce.setChecked(picture.planForceLit());
        planForce.setDisabled(!picture.planForceEnabled());
        autoAssign.setChecked(picture.autoAssign());
        forcePlan.setText(picture.forcePlan());
        status.setText(picture.status());
        status.setColor(UiTheme.tokenColor(picture.notice() ? "status.warning" : "text.secondary"));
        build.setVisible(picture.buildVisible());
        discard.setVisible(picture.discardVisible());
        autoAssignPlan.setVisible(picture.autoAssignVisible());
        peer.setVisible(picture.peerVisible());
        disconnect.setVisible(picture.disconnectVisible());
        actions.clearChildren();
        actions.add(status).growX().left().padRight(space("md"));
        for (UiButton button : List.of(autoAssignPlan, discard, build, peer, disconnect)) {
            if (button.isVisible()) {
                actions.add(button).padLeft(space("sm"));
            }
        }

        diagrams.clearChildren();
        for (PlanCard card : picture.plans()) {
            diagrams.add(planCard(card)).growX().padBottom(space("lg")).row();
        }
        for (Network network : picture.networks()) {
            diagrams.add(networkCard(network)).growX().padBottom(space("lg")).row();
        }
        diagrams.add(targets(picture)).padBottom(space("md")).row();

        trayUnits.clearChildren();
        trayCount.setText(String.valueOf(picture.rosterUnits().size()));
        if (picture.roster().isEmpty()) {
            trayUnits.add(kit.typeLabel(Messages.getString("C3NetworkManagerDialog.rosterEmpty"), "small",
                  "text.muted")).left().pad(space("sm"));
        }
        boolean first = true;
        for (RosterGroup group : picture.roster()) {
            if (!first) {
                trayUnits.add(kit.solid("border.subtle")).width(size("selection.width") / 2).growY()
                      .padLeft(space("lg")).padRight(space("lg"));
            }
            first = false;
            Table cluster = new Table();
            cluster.top().left();
            cluster.add(kit.roleTypeLabel("overline", group.title() + "  " + group.units().size(),
                  rosterTint(group))).left().padBottom(space("xs")).row();
            Table row = new Table();
            for (RosterUnit unit : group.units()) {
                row.add(rosterChip(unit, group)).width(chipWidth()).padRight(space("sm"));
            }
            cluster.add(row).left();
            trayUnits.add(cluster).top().left();
        }
        paintSelection();
        diagramScroll.layout();
        diagramScroll.setScrollX(scrollX);
        diagramScroll.setScrollY(scrollY);
        diagramScroll.updateVisualScroll();
    }

    // ---- Diagrams ----

    private Table planCard(PlanCard card) {
        Table panel = kit.card();
        panel.setName("c3-plan-" + card.index());
        Table header = new Table();
        header.add(kit.icon("layers", size("icon.md"), UiTheme.tokenColor("accent.primary")))
              .size(size("icon.md")).padRight(space("sm"));
        Label title = kit.typeLabel(card.header(), "body.strong", "text.primary");
        title.setWrap(true);
        header.add(title).growX().left();
        header.add(kit.chip(card.filled() + " / " + card.total(), card.filled() == card.total() ? Tone.OK
              : Tone.NORMAL, true)).padLeft(space("sm"));
        panel.add(header).growX().row();
        Target target = new Target.Plan(card.index());
        header.setName("c3-plan-head-" + card.index());
        header.setTouchable(Touchable.enabled);
        header.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                place(target);
            }
        });
        dragAndDrop.addTarget(dropTarget(header, target, null));
        Graph graph = new Graph();
        graph.add(tree(graph, card.root(), new Actor[1])).top();
        panel.add(new Fit(graph)).growX().padTop(space("lg")).padBottom(space("sm"));
        return panel;
    }

    private Table networkCard(Network network) {
        Table panel = kit.card();
        panel.setName("c3-network-" + network.topId());
        Table header = new Table();
        header.add(kit.icon(network.peer() ? "group" : "hex", size("icon.md"),
              UiTheme.tokenColor(network.peer() ? "accent.primary" : "selected.primary")))
              .size(size("icon.md")).padRight(space("sm"));
        Label title = kit.typeLabel(network.title(), "body.strong", "text.primary");
        title.setWrap(true);
        header.add(title).growX().left();
        if (!network.config().isEmpty()) {
            header.add(kit.chip(network.config(), network.configComplete() ? Tone.OK : Tone.WARN, true))
                  .padLeft(space("sm"));
        }
        panel.add(header).growX().row();
        Graph graph = new Graph();
        if (network.peer()) {
            graph.add(chain(graph, network.roots())).top();
        } else {
            Table roots = new Table();
            roots.top();
            for (Node root : network.roots()) {
                roots.add(tree(graph, root, new Actor[1])).top().pad(0, space("lg"), 0, space("lg"));
            }
            graph.add(roots).top();
        }
        panel.add(new Fit(graph)).growX().padTop(space("lg")).padBottom(space("sm"));
        return panel;
    }

    /**
     * A node and everything under it as an org chart: its chip centred on top, its children side by side in a row
     * beneath, hung from a bus.
     *
     * @param head receives the node's own chip, for the parent's line
     */
    private Actor tree(Graph graph, Node node, Actor[] head) {
        Table chip = chip(node);
        head[0] = chip;
        Table box = new Table();
        box.top();
        box.add(chip).width(nodeWidth()).row();
        if (node.children().isEmpty()) {
            return box;
        }
        Table row = new Table();
        row.top();
        for (Node child : node.children()) {
            Actor[] childHead = new Actor[1];
            row.add(tree(graph, child, childHead)).top().pad(0, space("xs"), 0, space("xs"));
            graph.edges.add(new Edge(chip, childHead[0], Link.BUS, UiTheme.tokenColor(role(child.tint()))));
        }
        box.add(row).padTop(space("xxl"));
        return box;
    }

    /** A masterless network's members side by side, {@value #PEERS_PER_ROW} to a row, each linked to the next. */
    private Table chain(Graph graph, List<Node> members) {
        Table rows = new Table();
        rows.top();
        Actor previous = null;
        int inRow = 0;
        for (Node member : members) {
            if (inRow == PEERS_PER_ROW) {
                rows.row();
                inRow = 0;
            }
            Table chip = chip(member);
            rows.add(chip).width(nodeWidth()).pad(space("sm"), space("lg"), space("sm"), space("lg"));
            if (previous != null) {
                graph.edges.add(new Edge(previous, chip, inRow == 0 ? Link.BUS : Link.LINK,
                      UiTheme.tokenColor(role(member.tint()))));
            }
            previous = chip;
            inRow++;
        }
        return rows;
    }
    /** The peer network starts, the new-network target and the start hint, as dashed tiles. */
    private Table targets(Picture picture) {
        Table row = new Table();
        row.left();
        for (PeerSeed seed : picture.peerSeeds()) {
            row.add(tile("c3-seed-" + seed.system().name().toLowerCase(), "group", seed.system().displayName(),
                  seed.label(), new Target.Peer(seed.system()))).padRight(space("md"));
        }
        String text = picture.startHint() != null ? picture.startHint()
              : Messages.getString("C3NetworkManagerDialog.dropMasterToStart");
        Table start = tile("c3-new-network", "add", Messages.getString("C3NetworkManagerDialog.newNetwork"), text,
              new Target.NewNetwork());
        row.add(start);
        return row;
    }

    private Table tile(String name, String icon, String title, String text, Target target) {
        Table tile = new Table();
        tile.setName(name);
        tile.setTouchable(Touchable.enabled);
        tile.setBackground(kit.skin.getDrawable("token-row-slot"));
        tile.pad(space("md"), space("lg"), space("md"), space("lg"));
        tile.add(kit.icon(icon, size("icon.md"), UiTheme.tokenColor("accent.primary"))).size(size("icon.md"))
              .padRight(space("sm"));
        Table words = new Table();
        words.add(kit.roleTypeLabel("overline", title, "accent.primary")).left().row();
        Label line = kit.typeLabel(text, "small", "text.secondary");
        line.setWrap(true);
        words.add(line).width(chipWidth()).left();
        tile.add(words);
        tile.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                place(target);
            }
        });
        dragAndDrop.addTarget(dropTarget(tile, target, "token-row-slot"));
        return tile;
    }

    // ---- Chips ----

    /**
     * A diagram node's chip, a small upright card: its tint along the top, sprite, role, name and detail; an open slot
     * or free link is a ghost of one.
     */
    private Table chip(Node node) {
        boolean open = node.unitId() < 0;
        String face = open ? "token-row-slot" : "token-card";
        String tint = role(node.tint());
        Table chip = new Table();
        chip.setBackground(kit.skin.getDrawable(face));
        chip.setTouchable(Touchable.enabled);
        chip.top().pad(0, space("sm"), space("sm"), space("sm"));
        chip.add(kit.solid(tint)).height(size("selection.width") * 2).growX().padBottom(space("sm")).row();
        Actor picture = open ? kit.icon("add", size("icon.md"), UiTheme.tokenColor(tint)) : sprite(node.unitId());
        chip.add(picture).size(spriteSize()).row();
        chip.add(kit.roleTypeLabel("overline", node.role(), tint)).padTop(space("xs")).row();
        Label name = kit.typeLabel(node.name(), "body.strong", open ? "text.secondary" : "text.primary");
        name.setWrap(true);
        name.setAlignment(Align.center);
        chip.add(name).growX().minWidth(0).row();
        if (!node.detail().isEmpty()) {
            Label detail = kit.typeLabel(node.detail(), "small", "text.muted");
            detail.setEllipsis(true);
            detail.setAlignment(Align.center);
            chip.add(detail).growX().minWidth(0);
        }
        if (open) {
            chip.getColor().a = .8f;
        }
        if (!node.tip().isEmpty() && open) {
            kit.tip(chip, true).getActor().setText(node.tip());
        }
        if (open) {
            chip.setName(node.kind() == NodeKind.FREE ? "c3-free-" + ((Target.Unit) node.target()).id()
                  : slotName(node.target()));
        } else {
            chip.setName((node.kind() == NodeKind.SLOT ? slotName(node.target()) + "-" : "c3-unit-") + node.unitId());
            register(node.unitId(), chip, face);
            dragAndDrop.addSource(dragSource(chip, node.unitId(), node.name()));
        }
        Target target = node.target();
        dragAndDrop.addTarget(dropTarget(chip, target, face));
        chip.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                event.stop();
                // A unit picks; a slot, free link or a filled slot with units picked takes them
                if (node.kind() == NodeKind.UNIT || (!open && selection.isEmpty())
                      || (!open && selection.contains(node.unitId()))) {
                    toggle(node.unitId());
                } else {
                    place(target);
                }
            }
        });
        return chip;
    }

    private Table rosterChip(RosterUnit unit, RosterGroup group) {
        String tint = rosterTint(group);
        Table chip = new Table();
        chip.setName("c3-unit-" + unit.id());
        chip.setTouchable(Touchable.enabled);
        chip.setBackground(kit.skin.getDrawable("token-card"));
        chip.pad(space("sm"), space("sm"), space("sm"), space("md"));
        chip.add(kit.solid(tint)).width(size("selection.width") * 2).growY().padRight(space("sm"));
        chip.add(sprite(unit.id())).size(spriteSize()).padRight(space("sm"));
        String detail = "#" + unit.id() + (unit.planned()
              ? " " + Messages.getString("C3NetworkManagerDialog.plannedSuffix") : "");
        chip.add(words(unit.equipment(), tint, unit.name(), "text.primary", detail)).width(chipWidth()
              - spriteSize() - space("xl") * 2).minWidth(0);
        if (unit.planned()) {
            chip.getColor().a = .55f;
        }
        kit.tip(chip, true).getActor().setText(unit.label());
        register(unit.id(), chip, "token-card");
        dragAndDrop.addSource(dragSource(chip, unit.id(), unit.name()));
        dragAndDrop.addTarget(dropTarget(chip, new Target.RosterUnit(unit.id()), "token-card"));
        chip.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                event.stop();
                toggle(unit.id());
            }
        });
        return chip;
    }

    private Table words(String role, String tint, String name, String nameRole, String detail) {
        Table words = new Table();
        words.left();
        words.add(kit.roleTypeLabel("overline", role, tint)).left().row();
        Label nameLabel = kit.typeLabel(name, "body.strong", nameRole);
        nameLabel.setEllipsis(true);
        words.add(nameLabel).growX().minWidth(0).left().row();
        if (!detail.isEmpty()) {
            Label detailLabel = kit.typeLabel(detail, "small", "text.muted");
            detailLabel.setEllipsis(true);
            words.add(detailLabel).growX().minWidth(0).left();
        }
        return words;
    }

    private Actor sprite(int id) {
        Drawable drawable = sprites.apply(id);
        if (drawable == null) {
            return kit.icon("unit", size("icon.md"), UiTheme.tokenColor("text.muted"));
        }
        return new Image(drawable, Scaling.fit);
    }

    private void register(int id, Table chip, String face) {
        chips.computeIfAbsent(id, unused -> new ArrayList<>()).add(chip);
        chipFaces.put(chip, face);
    }

    private static String slotName(Target target) {
        Target.Slot slot = (Target.Slot) target;
        return "c3-slot-" + slot.plan() + "-" + slot.kind().name().toLowerCase() + "-" + slot.lance() + "-"
              + slot.slot();
    }

    /** The token colour role of a tint, as the book's diagram colours the company and each lance. */
    static String role(Tint tint) {
        return switch (tint) {
            case COMPANY -> "selected.primary";
            case COMPANY_LANCE -> "status.warning";
            case LANCE_1 -> "status.danger";
            case LANCE_2 -> "status.info";
            case LANCE_3 -> "status.success";
            case LANCE_4 -> "status.gamemaster";
            case PEER -> "accent.primary";
            case MUTED -> "text.muted";
        };
    }

    private static String rosterTint(RosterGroup group) {
        if (group.title().equals(Messages.getString("C3NetworkManagerDialog.groupMasters"))) {
            return "selected.primary";
        }
        if (group.title().equals(Messages.getString("C3NetworkManagerDialog.groupSlaves"))) {
            return "status.info";
        }
        return "accent.primary";
    }

    // ---- Picking and placing ----

    private void toggle(int id) {
        if (!selection.remove(id)) {
            selection.add(id);
        }
        paintSelection();
    }

    /** Places the picked units on {@code target}, if any are picked. */
    private void place(Target target) {
        if (selection.isEmpty()) {
            return;
        }
        List<Integer> ids = List.copyOf(selection);
        selection.clear();
        paintSelection();
        host.act(board -> board.drop(ids, target));
    }

    private void paintSelection() {
        chips.forEach((id, list) -> list.forEach(chip -> chip.setBackground(kit.skin.getDrawable(
              selection.contains(id) ? "token-card-selected" : chipFaces.get(chip)))));
    }

    private boolean inChip(Actor actor) {
        for (Actor at = actor; at != null && at != tray; at = at.getParent()) {
            if (at instanceof Table table && chipFaces.containsKey(table)) {
                return true;
            }
        }
        return false;
    }

    private DragAndDrop.Source dragSource(Table chip, int id, String name) {
        return new DragAndDrop.Source(chip) {
            @Override
            public DragAndDrop.Payload dragStart(InputEvent event, float x, float y, int pointer) {
                List<Integer> ids = selection.contains(id) ? List.copyOf(selection) : List.of(id);
                DragAndDrop.Payload payload = new DragAndDrop.Payload();
                payload.setObject(ids);
                Table flying = new Table();
                flying.setBackground(kit.skin.getDrawable("token-row-lifted"));
                flying.pad(space("xs"), space("sm"), space("xs"), space("sm"));
                flying.add(kit.icon("unit", size("icon.sm"), UiTheme.tokenColor("text.secondary")))
                      .size(size("icon.sm")).padRight(space("xs"));
                flying.add(kit.typeLabel(ids.size() == 1 ? name : String.valueOf(ids.size()), "body.strong",
                      "text.primary"));
                flying.pack();
                payload.setDragActor(flying);
                dragAndDrop.setDragActorPosition(-space("md"), flying.getHeight() / 2);
                return payload;
            }
        };
    }

    /** A target for dragged units; {@code face} is its background while nothing is dragged over it. */
    private DragAndDrop.Target dropTarget(Table actor, Target target, @Nullable String face) {
        return new DragAndDrop.Target(actor) {
            @Override
            public boolean drag(DragAndDrop.Source source, DragAndDrop.Payload payload, float x, float y,
                  int pointer) {
                if (!(payload.getObject() instanceof List<?> ids) || source.getActor() == actor) {
                    return false;
                }
                if (target instanceof Target.RosterUnit unit && ids.contains(unit.id())) {
                    return false;
                }
                actor.setBackground(kit.skin.getDrawable("token-card-drop"));
                return true;
            }

            @Override
            public void reset(DragAndDrop.Source source, DragAndDrop.Payload payload) {
                if (chipFaces.containsKey(actor)) {
                    paintSelection();
                } else {
                    actor.setBackground(face == null ? null : kit.skin.getDrawable(face));
                }
            }

            @Override
            public void drop(DragAndDrop.Source source, DragAndDrop.Payload payload, float x, float y, int pointer) {
                List<Integer> ids = new ArrayList<>();
                for (Object id : (List<?>) payload.getObject()) {
                    ids.add((Integer) id);
                }
                selection.clear();
                host.act(board -> board.drop(ids, target));
            }
        };
    }

    // ---- Toolbar ----

    private void startTemplate(int number) {
        Picture picture = shown;
        if (picture != null && picture.planFilled() > 0) {
            host.confirm(Messages.getString("C3NetworkManagerDialog.title"),
                  UiKit.text("C3NetworkManagerDialog.replacePlan", number),
                  () -> host.act(board -> board.startTemplate(number)));
        } else {
            host.act(board -> board.startTemplate(number));
        }
    }

    private void planForce() {
        Picture picture = shown;
        if (picture != null && picture.planFilled() > 0) {
            host.confirm(Messages.getString("C3NetworkManagerDialog.title"),
                  Messages.getString("C3NetworkManagerDialog.replaceForcePlan"),
                  () -> host.act(C3NetworkBoard::planForce));
        } else {
            host.act(C3NetworkBoard::planForce);
        }
    }

    private void reset() {
        Picture picture = shown;
        if (picture != null && picture.resettable()) {
            host.confirm(Messages.getString("C3NetworkManagerDialog.title"),
                  Messages.getString("C3NetworkManagerDialog.confirmReset"), () -> host.act(C3NetworkBoard::reset));
        } else {
            host.act(C3NetworkBoard::reset);
        }
    }

    private UiButton action(String style, String icon, String key, String name, Runnable run) {
        UiButton button = kit.button(style + UiTheme.COMPACT, icon,
              Messages.getString("C3NetworkManagerDialog." + key), null);
        button.setName(name);
        tip(button, "C3NetworkManagerDialog." + key + ".tooltip");
        UiKit.onChange(button, run);
        return button;
    }

    private void tip(Actor actor, String key) {
        kit.tip(actor, true).getActor().setText(Messages.getString(key));
    }

    // ---- Sizes ----

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private static float chipWidth() {
        return size("dialog.sm") / 2;
    }

    private static float spriteSize() {
        return size("control.lg");
    }

    /** A diagram node's width: three to a control's width, so a quad master's company fits across the centre. */
    private static float nodeWidth() {
        return size("control.lg") * 3;
    }

    /** A diagram drawn at its own size, scaled down to fit the card's width when wider, and centred. */
    private static final class Fit extends WidgetGroup {
        private final Graph graph;
        private float scale = 1;

        Fit(Graph graph) {
            this.graph = graph;
            graph.setTransform(true);
            addActor(graph);
        }

        @Override
        public float getPrefWidth() {
            return 0;
        }

        @Override
        public float getPrefHeight() {
            return graph.getPrefHeight() * scale;
        }

        @Override
        public void layout() {
            float width = graph.getPrefWidth();
            float height = graph.getPrefHeight();
            float fit = (getWidth() > 0 && width > getWidth()) ? getWidth() / width : 1;
            graph.setBounds((getWidth() - width * fit) / 2, getHeight() - height * fit, width, height);
            graph.setScale(fit);
            graph.validate();
            if (fit != scale) {
                scale = fit;
                invalidateHierarchy();
            }
        }
    }

    /** A diagram: its chips laid out as tables, with the network's links drawn under them. */
    private final class Graph extends Table {
        private final List<Edge> edges = new ArrayList<>();
        private final Vector2 point = new Vector2();

        @Override
        protected void drawChildren(Batch batch, float parentAlpha) {
            float alpha = parentAlpha * getColor().a;
            float originX = isTransform() ? 0 : getX();
            float originY = isTransform() ? 0 : getY();
            float width = size("selection.width");
            for (Edge edge : edges) {
                Vector2 from = at(edge.from()).add(originX, originY);
                Vector2 to = at(edge.to()).add(originX, originY);
                Color colour = edge.colour();
                if (edge.link() == Link.LINK) {
                    float y = from.y + edge.from().getHeight() / 2;
                    float x = from.x + edge.from().getWidth();
                    kit.fill(batch, colour, alpha, x, y - width / 2, to.x - x, width);
                    continue;
                }
                // Down from the parent's foot to the bus, along it, and down to the child's head
                float x = from.x + edge.from().getWidth() / 2;
                float toX = to.x + edge.to().getWidth() / 2;
                float toTop = to.y + edge.to().getHeight();
                float bus = toTop + space("xxl") / 2;
                kit.fill(batch, colour, alpha, x - width / 2, bus, width, from.y - bus);
                kit.fill(batch, colour, alpha, Math.min(x, toX) - width / 2, bus - width / 2,
                      Math.abs(toX - x) + width, width);
                kit.fill(batch, colour, alpha, toX - width / 2, toTop, width, bus - toTop);
            }
            batch.setColor(Color.WHITE);
            super.drawChildren(batch, parentAlpha);
        }
        /** The actor's corner in this diagram. */
        private Vector2 at(Actor actor) {
            float x = 0;
            float y = 0;
            for (Actor at = actor; at != null && at != this; at = at.getParent()) {
                x += at.getX();
                y += at.getY();
            }
            return point.set(x, y).cpy();
        }
    }
}
