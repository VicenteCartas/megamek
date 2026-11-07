/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.lobby.BotFacingForm;
import megamek.client.lobby.LanceRoleForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTabs;
import megamek.common.orders.LanceRole;
import megamek.common.orders.UnitOrders;

/**
 * The bot orders the lobby's unit menu sets, as {@link UiDialog} cards: the facings a bot unit keeps, on two hex
 * compasses, and a bot lance's role, as a convoy or an escort round another lance. Each fills a dialog
 * {@link LobbyNativeDialogs} opened; OK hands the form to the Swing thread through an
 * {@link LobbyPromptViews.Edt}, after which the dialog closes.
 * <p>
 * GPU thread.
 */
final class LobbyBotOrderViews {
    /** The hex sides as the compass lays them out, row by row: the top three, Auto alone, the bottom three. */
    private static final int[][] COMPASS = { { 5, 0, 1 }, { -2, UnitOrders.FACING_AUTO, -2 }, { 4, 3, 2 } };
    /** The short names on the compass, by hex side. */
    private static final String[] SIDES = { "N", "NE", "SE", "S", "SW", "NW" };

    private LobbyBotOrderViews() {
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static String strip(String text) {
        String trimmed = text.replaceAll("<[^>]*>", "").trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Label wrapped(UiKit kit, String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    // ---- Facing

    /** The unit's facings on two compasses, while moving and when stopped, each with Auto at its middle. */
    static void facing(UiKit kit, UiDialog dialog, BotFacingForm form, LobbyPromptViews.Edt edt, Runnable confirmed,
          Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left();
        body.add(kit.typeLabel(form.unitName(), "title", "text.primary")).growX().row();
        Table compasses = new Table();
        compasses.defaults().top().expandX();
        compasses.add(compass(kit, "moving", form::whileMoving, form::whileMoving));
        compasses.add(compass(kit, "stopped", form::whenStopped, form::whenStopped)).padLeft(space("xl"));
        body.add(compasses).growX().padTop(space("lg")).row();
        dialog.primary(Messages.getString("Ok.text"), () -> edt.edt(confirmed, close)).setName("facing-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("facing-cancel");
    }

    private static Table compass(UiKit kit, String which, IntSupplier facing, IntConsumer choose) {
        Table column = new Table();
        column.add(kit.caption(Messages.getString("BotCommandPanel.Orders.facing." + which), true)).row();
        Label chosen = kit.typeLabel(BotFacingForm.facingName(facing.getAsInt()), "body.strong", "text.primary");
        chosen.setName("facing-" + which + "-chosen");
        Table rose = new Table();
        List<UiButton> sides = new ArrayList<>();
        float gap = space("xs");
        for (int[] row : COMPASS) {
            for (int side : row) {
                if (side < UnitOrders.FACING_AUTO) {
                    rose.add().size(space("xxxl"));
                    continue;
                }
                boolean auto = side == UnitOrders.FACING_AUTO;
                UiButton button = kit.button("button-toggle-compact", null,
                      auto ? Messages.getString("BotCommandPanel.Orders.facing.autoShort") : SIDES[side], null);
                button.setName("facing-" + which + '-' + (auto ? "auto" : String.valueOf(side)));
                button.setUserObject(side);
                kit.tip(button, true).getActor().setText(BotFacingForm.facingName(side));
                button.setChecked(facing.getAsInt() == side);
                sides.add(button);
                UiKit.onChange(button, () -> {
                    choose.accept(side);
                    for (UiButton each : sides) {
                        each.setChecked(Integer.valueOf(facing.getAsInt()).equals(each.getUserObject()));
                    }
                    chosen.setText(BotFacingForm.facingName(facing.getAsInt()));
                });
                rose.add(button).minSize(space("xxxl")).pad(gap / 2);
            }
            rose.row();
        }
        column.add(rose).padTop(space("sm")).row();
        column.add(chosen).padTop(space("sm"));
        return column;
    }

    // ---- Lance role

    /**
     * The lance's role: tabs for None, Convoy and Escort, each with its settings and what the role does; OK, which
     * waits until an escort has a lance to guard and a place round it, sends it.
     */
    static void lanceRole(UiKit kit, UiDialog dialog, LanceRoleForm form, LobbyPromptViews.Edt edt,
          Runnable confirmed, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        boolean escortable = !form.convoys().isEmpty();
        List<LanceRoleForm.Choice> choices = Arrays.stream(LanceRoleForm.Choice.values())
              .filter(choice -> escortable || choice != LanceRoleForm.Choice.ESCORT).toList();
        UiTabs tabs = kit.tokenTabs(true, choices.stream().map(LanceRoleForm::choiceName).toArray(String[]::new));
        tabs.setName("role-tabs");
        for (int index = 0; index < choices.size(); index++) {
            tabs.option(index).setName("role-tab-" + choices.get(index).name().toLowerCase());
        }
        tabs.select(Math.max(0, choices.indexOf(form.choice())));
        body.add(tabs).row();
        Table page = new Table();
        page.top().left().defaults().left().growX();
        body.add(page).padTop(space("lg")).row();
        UiButton ok = dialog.primary(Messages.getString("Ok.text"), () -> {
            if (form.complete()) {
                edt.edt(confirmed, close);
            }
        });
        ok.setName("role-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("role-cancel");
        Runnable[] show = new Runnable[1];
        show[0] = () -> {
            page.clearChildren();
            switch (form.choice()) {
                case NONE -> page.add(wrapped(kit, strip(Messages.getString("BotCommandPanel.Role.none.help")),
                      "body", "text.secondary")).row();
                case CONVOY -> convoyPage(kit, page, form, show[0]);
                case ESCORT -> escortPage(kit, page, form, show[0]);
            }
            ok.setDisabled(!form.complete());
        };
        UiKit.onChange(tabs, () -> {
            form.choice(choices.get(tabs.getSelectedIndex()));
            show[0].run();
        });
        show[0].run();
    }

    private static void convoyPage(UiKit kit, Table page, LanceRoleForm form, Runnable refresh) {
        UiSegmented then = kit.tokenSegmented(true, Messages.getString("BotCommandPanel.Role.convoy.then.LEAVE"),
              Messages.getString("BotCommandPanel.Role.convoy.then.WAIT"));
        then.setName("role-convoy-then");
        then.select(form.waits() ? 1 : 0);
        UiKit.onChange(then, () -> {
            form.waits(then.getSelectedIndex() == 1);
            refresh.run();
        });
        page.add(kit.formField(strip(Messages.getString("BotCommandPanel.Role.convoy.then")), then)).row();
        UiSegmented edge = kit.tokenSegmented(true, LanceRoleForm.EDGES.stream().map(LanceRoleForm::edgeName)
              .toArray(String[]::new));
        edge.setName("role-convoy-edge");
        edge.select(LanceRoleForm.EDGES.indexOf(form.exitEdge()));
        edge.setDisabled(!form.edgeApplies());
        UiKit.onChange(edge, () -> {
            form.exitEdge(LanceRoleForm.EDGES.get(edge.getSelectedIndex()));
            refresh.run();
        });
        page.add(kit.formField(strip(Messages.getString("BotCommandPanel.Role.convoy.exitEdge")), edge))
              .padTop(space("md")).row();
        page.add(summary(kit, form.convoyHelp(), "role-convoy-help")).padTop(space("lg")).row();
    }

    private static void escortPage(UiKit kit, Table page, LanceRoleForm form, Runnable refresh) {
        UiSelect convoy = kit.tokenSelect(true, form.convoys().stream().map(LanceRoleForm::convoyLabel)
              .toArray(String[]::new));
        convoy.setName("role-escort-convoy");
        convoy.select(form.convoy());
        UiKit.onChange(convoy, () -> {
            form.convoy(convoy.getSelectedIndex());
            refresh.run();
        });
        page.add(kit.formField(strip(Messages.getString("BotCommandPanel.Role.escort.escorting")), convoy)).row();

        page.add(kit.caption(strip(Messages.getString("BotCommandPanel.Role.escort.position")), true))
              .padTop(space("md")).row();
        page.add(formation(kit, form, refresh)).left().expand(false, false).fill(false, false).padTop(space("xs")).row();

        page.add(choice(kit, "distance", LanceRole.Distance.values(), LanceRoleForm::distanceName,
              form.distance(), form::distance, form.distanceHint(), true, refresh))
              .padTop(space("md")).row();
        page.add(choice(kit, "contact", LanceRole.Contact.values(), LanceRoleForm::contactName,
              form.contact(), form::contact, form.contactHint(), true, refresh))
              .padTop(space("md")).row();
        page.add(choice(kit, "leave", LanceRole.LeaveToFight.values(), LanceRoleForm::leaveName,
              form.leave(), form::leave, form.leaveHint(), form.leaveApplies(),
              refresh)).padTop(space("md")).row();
        page.add(choice(kit, "gone", LanceRole.WhenConvoyGone.values(), LanceRoleForm::goneName,
              form.gone(), form::gone, form.goneHint(), true, refresh))
              .padTop(space("md")).row();
        page.add(summary(kit, form.escortSummary(), "role-escort-summary")).padTop(space("lg")).row();
    }

    /** The places round the convoy as they stand on the field: the lead ahead, the flanks beside, the rear behind. */
    private static Table formation(UiKit kit, LanceRoleForm form, Runnable refresh) {
        Table grid = new Table();
        float cell = space("xxxl") * 2;
        grid.add();
        grid.add(place(kit, form, LanceRole.Position.LEAD, refresh)).minWidth(cell).pad(space("xxs"));
        grid.add().row();
        grid.add(place(kit, form, LanceRole.Position.LEFT, refresh)).minWidth(cell).pad(space("xxs"));
        Label convoy = kit.typeLabel(strip(Messages.getString("BotCommandPanel.Role.escort.convoyIcon")),
              "body.strong", "text.muted");
        grid.add(convoy).minWidth(cell).pad(space("xxs"));
        convoy.setAlignment(Align.center);
        grid.add(place(kit, form, LanceRole.Position.RIGHT, refresh)).minWidth(cell).pad(space("xxs")).row();
        UiButton surround = kit.button("button-quiet-compact", null,
              strip(Messages.getString("BotCommandPanel.Role.escort.surround")), null);
        surround.setName("role-escort-surround");
        UiKit.onChange(surround, () -> {
            form.surround();
            refresh.run();
        });
        grid.add(surround).pad(space("xxs"));
        grid.add(place(kit, form, LanceRole.Position.REAR, refresh)).minWidth(cell).pad(space("xxs"));
        grid.add();
        return grid;
    }

    private static UiButton place(UiKit kit, LanceRoleForm form, LanceRole.Position position, Runnable refresh) {
        UiButton button = kit.button("button-toggle-compact", null, LanceRoleForm.positionName(position), null);
        button.setName("role-escort-" + position.name().toLowerCase());
        button.setProgrammaticChangeEvents(false);
        button.setChecked(form.holds(position));
        UiKit.onChange(button, () -> {
            form.toggle(position);
            refresh.run();
        });
        return button;
    }

    /** A labelled choice of one of {@code values}, with the hint on what the chosen one does under it. */
    private static <T> Table choice(UiKit kit, String which, T[] values, Function<T, String> namer, T current,
          Consumer<T> chooser, String hint, boolean applies, Runnable refresh) {
        UiSelect select = kit.tokenSelect(true, Arrays.stream(values).map(namer).toArray(String[]::new));
        select.setName("role-escort-" + which);
        select.select(Math.max(0, Arrays.asList(values).indexOf(current)));
        select.setDisabled(!applies);
        UiKit.onChange(select, () -> {
            chooser.accept(values[select.getSelectedIndex()]);
            refresh.run();
        });
        Table field = new Table();
        field.top().left().defaults().left().growX();
        field.add(kit.formField(strip(Messages.getString("BotCommandPanel.Role.escort." + which)), select)).row();
        Label note = wrapped(kit, hint, "small", "text.muted");
        note.setName("role-escort-" + which + "-hint");
        field.add(note).padTop(space("xxs")).row();
        return field;
    }

    private static Table summary(UiKit kit, String text, String name) {
        Table card = kit.card();
        card.top().left();
        Label label = wrapped(kit, text, "body", "text.primary");
        label.setName(name);
        card.add(label).growX().left();
        return card;
    }
}
