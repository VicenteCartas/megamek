/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.UnitConfigForm;
import megamek.client.lobby.UnitConfigForm.Field;
import megamek.client.lobby.UnitConfigForm.Member;
import megamek.client.lobby.UnitConfigForm.Problem;
import megamek.client.lobby.UnitConfigForm.Skill;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.board.Board;

/**
 * The lobby's unit bay, in the forces' place: the native take on the Swing {@code CustomMekDialog}'s crew and
 * deployment tabs for one unit or several. A hero header shows the unit's sprite, name, model, BV and tonnage between
 * the Previous and Next arrows (for several units their sprites stacked and their count); tabs below hold each crew
 * member's dossier (portrait, name and callsign, the skills as large dials, aptitudes, personal equipment and crew
 * role), the command controls, and the deployment (the zone as a compass grid, the round, the starting state as
 * chips, flight and off board). The footer holds Cancel, Apply and OK, which stay disabled while a number is wrong.
 * The values are held by a {@link UnitConfigForm}.
 * <p>
 * GPU thread. Whatever reads or changes the game runs on the Swing thread through {@link Host#edt}.
 */
final class LobbyUnitBayView {
    /** What the bay asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: writes the form to the units and sends them when they may be changed; false when refused. */
        boolean apply(UnitConfigForm form);

        /** Swing thread: opens the unit after the form's, or the one before, in the bay. */
        void next(UnitConfigForm form, boolean forward);

        /** Swing thread: asks for the crew member's portrait; its picture once chosen, null when none was. */
        @Nullable BufferedImage portrait(UnitConfigForm form, int slot);

        /** GPU thread: closes the bay, showing the forces again. */
        void close();

        /** The board's terrain for the zone picture, as {@link LobbyMapThumbnail#bake} draws it; null for none. */
        default @Nullable BufferedImage boardPicture() {
            return null;
        }
    }

    /** The zone grid: the compass, then Any and Edge, as {@link Board}'s start positions; the owner's stands above. */
    static final int[][] GRID = { { 1, 2, 3 }, { 8, 10, 4 }, { 7, 6, 5 }, { 0, 9 } };
    private static final String[] ZONE_KEYS = { "deployAny", "deployNW", "deployN", "deployNE", "deployE",
                                                "deploySE", "deployS", "deploySW", "deployW", "deployEdge",
                                                "deployCenter" };
    /** The zone picture's longer side, in large controls. */
    private static final float ZONE_MAP_SIDE = 5;
    /** The most sprites stacked in the header for several units. */
    private static final int STACKED = 5;

    private final UiKit kit;
    private final UnitConfigForm form;
    private final Host host;
    private final Table body = new Table();
    private final Container<Actor> page = new Container<>();
    private final List<Actor> pages = new ArrayList<>();
    private final List<Runnable> refreshers = new ArrayList<>();
    private final List<Texture> textures = new ArrayList<>();
    private @Nullable LobbyZoneMap zoneMap;
    private final Map<Integer, Image> portraits = new HashMap<>();
    private final Map<String, Actor> named = new HashMap<>();
    private UiTabs tabs;
    private UiButton previous;
    private UiButton next;
    private UiButton ok;
    private UiButton apply;
    private Label status;
    private boolean busy;
    private @Nullable LobbyUnitBayEquipment equipment;
    private final List<LobbyUnitBayOptions> optionPages = new ArrayList<>();
    private final Map<Integer, Supplier<Actor>> renewables = new LinkedHashMap<>();

    /**
     * @param sprites   the units' sprites, at most {@value #STACKED}, as the window drew them on the Swing thread
     * @param portraits each crew member's portrait picture by slot, null where there is none
     */
    LobbyUnitBayView(UiKit kit, UnitConfigForm form, List<BufferedImage> sprites,
          List<BufferedImage> portraits, Host host) {
        this.kit = kit;
        this.form = form;
        this.host = host;
        body.top().left().defaults().left().growX();
        body.pad(space("lg"));
        body.add(hero(sprites)).row();

        List<String> titles = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        if (form.showsCrew()) {
            for (Member member : form.members()) {
                titles.add(form.commandTab() ? member.role() : Messages.getString("CustomMekDialog.tabPilot"));
                keys.add(form.commandTab() ? "member-" + member.slot() : "pilot");
                pages.add(memberPage(member, slot(portraits, member.slot())));
            }
            if (form.commandTab()) {
                titles.add(Messages.getString("Lobby.bay.crew"));
                keys.add("crew");
                Table crew = new Table();
                crew.top().left().defaults().left().growX();
                crew.add(card("CustomMekDialog.sectionCommand", command())).row();
                pages.add(crew);
            }
        }
        if (form.abilities().isPresent()) {
            titles.add(Messages.getString("Lobby.bay.abilities"));
            keys.add("abilities");
            renewable(() -> options(LobbyUnitBayOptions.abilities(kit, form.abilities().orElseThrow(), this)));
        }
        if (form.equipment().filter(equipment -> !equipment.isEmpty()).isPresent()) {
            titles.add(Messages.getString("CustomMekDialog.tabEquipment"));
            keys.add("equipment");
            renewable(this::equipmentPage);
        }
        titles.add(Messages.getString("CustomMekDialog.tabDeployment"));
        keys.add("deployment");
        pages.add(deploymentPage());
        if (form.quirks().isPresent()) {
            titles.add(Messages.getString("Lobby.bay.quirks"));
            keys.add("quirks");
            renewable(() -> options(LobbyUnitBayOptions.quirks(kit, form.quirks().orElseThrow(), this)));
        }
        if (form.repairs().isPresent()) {
            titles.add(Messages.getString("CustomMekDialog.tabPartialRepairs"));
            keys.add("repairs");
            renewable(() -> options(LobbyUnitBayOptions.repairs(kit, form.repairs().orElseThrow(), this)));
        }
        tabs = kit.tokenTabs(false, titles.toArray(String[]::new));
        tabs.setName("bay-tabs");
        for (int i = 0; i < keys.size(); i++) {
            tabs.option(i).setName("bay-tab-" + keys.get(i));
        }
        UiKit.onChange(tabs, this::showPage);
        body.add(tabs).fill(false, false).padTop(space("xl")).row();
        page.fill().align(Align.topLeft);
        ScrollPane scroll = kit.scrollList(page, true, false);
        scroll.setName("bay-scroll");
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        body.add(scroll).grow().padTop(space("md")).row();
        body.add(footer()).padTop(space("lg")).row();
        showPage();
        refresh();
    }

    private static @Nullable BufferedImage slot(List<BufferedImage> pictures, int slot) {
        return slot < pictures.size() ? pictures.get(slot) : null;
    }

    static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    static String colonless(String key) {
        String text = Messages.getString(key).trim();
        return text.endsWith(":") || text.endsWith("?") ? text.substring(0, text.length() - 1) : text;
    }

    <T extends Actor> T name(T actor, String name) {
        actor.setName(name);
        named.put(name, actor);
        return actor;
    }

    // ---- The header ----

    private Table hero(List<BufferedImage> sprites) {
        Table hero = new Table();
        hero.left();
        previous = name(kit.button("button-icon", "chevron-left", null, null), "bay-prev");
        kit.tip(previous, true).getActor().setText(Messages.getString("Previous"));
        UiKit.onChange(previous, () -> step(false));
        next = name(kit.button("button-icon", "chevron-right", null, null), "bay-next");
        kit.tip(next, true).getActor().setText(Messages.getString("Next"));
        UiKit.onChange(next, () -> step(true));

        float spriteSize = size("control.lg") * 2;
        Table stack = new Table();
        for (int i = 0; i < Math.min(STACKED, sprites.size()); i++) {
            Texture texture = GpuStartScreen.texture(sprites.get(i), true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            textures.add(texture);
            Image image = new Image(new TextureRegionDrawable(texture), Scaling.fit);
            float shown = (i == 0) ? spriteSize : spriteSize * .75f;
            // Several units overlap like a hand of cards
            stack.add(image).size(shown).padLeft(i == 0 ? 0 : -shown / 2).bottom();
        }
        if (!form.multiple()) {
            hero.add(previous).padRight(space("md"));
        }
        hero.add(stack).padRight(space("lg"));

        Table text = new Table();
        text.left().defaults().left();
        List<UnitConfigForm.Summary> units = form.summaries();
        if (units.size() == 1) {
            UnitConfigForm.Summary unit = units.get(0);
            text.add(name(kit.typeLabel(unit.chassis(), "headline", "text.primary"), "bay-name")).row();
            text.add(kit.typeLabel(unit.model(), "title", "text.secondary")).row();
            Table chips = new Table();
            chips.left();
            chips.add(kit.chip(Messages.getString("Lobby.bay.bv", unit.battleValue()), UiKit.Tone.NORMAL, true))
                  .padRight(space("xs"));
            chips.add(kit.chip(Messages.getString("Lobby.bay.tons", formatTons(unit.tons())), UiKit.Tone.NORMAL,
                  true));
            text.add(chips).padTop(space("sm")).row();
        } else {
            int battleValue = 0;
            double tons = 0;
            StringBuilder names = new StringBuilder();
            for (UnitConfigForm.Summary unit : units) {
                battleValue += unit.battleValue();
                tons += unit.tons();
                names.append(names.isEmpty() ? "" : ", ").append(unit.chassis()).append(' ').append(unit.model());
            }
            text.add(name(kit.typeLabel(Messages.getString("Lobby.bay.units", units.size()), "headline",
                  "text.primary"), "bay-name")).row();
            Label list = kit.typeLabel(names.toString(), "body", "text.secondary");
            list.setEllipsis(true);
            text.add(list).width(size("dialog.sm")).row();
            Table chips = new Table();
            chips.left();
            chips.add(kit.chip(Messages.getString("Lobby.bay.bv", battleValue), UiKit.Tone.NORMAL, true))
                  .padRight(space("xs"));
            chips.add(kit.chip(Messages.getString("Lobby.bay.tons", formatTons(tons)), UiKit.Tone.NORMAL, true));
            text.add(chips).padTop(space("sm")).row();
        }
        if (!form.editable()) {
            text.add(kit.chip(Messages.getString("Lobby.bay.readOnly"), UiKit.Tone.WARN, true)).padTop(space("xs"))
                  .row();
        }
        hero.add(text).growX().minWidth(0);
        if (!form.multiple()) {
            hero.add(next).padLeft(space("md"));
        }
        return hero;
    }

    private static String formatTons(double tons) {
        return (tons == Math.rint(tons)) ? Long.toString((long) tons) : String.format(Locale.ROOT, "%.1f", tons);
    }

    // ---- The pages ----

    private void showPage() {
        page.setActor(pages.get(tabs.getSelectedIndex()));
    }

    /** The equipment page, built afresh from the form's equipment, which an apply renews. */
    private Actor equipmentPage() {
        equipment = new LobbyUnitBayEquipment(kit, form.equipment().orElseThrow(), this);
        return equipment.page();
    }

    /** Adds a page built from a part of the form that an apply renews, to be built again after one. */
    private void renewable(Supplier<Actor> build) {
        renewables.put(pages.size(), build);
        pages.add(build.get());
    }

    /** An option page, refreshed with the bay until an apply replaces it. */
    private Actor options(LobbyUnitBayOptions options) {
        optionPages.add(options);
        return options.page();
    }

    /** Builds the renewable pages again from the form an apply renewed. */
    private void renew() {
        equipment = null;
        optionPages.clear();
        renewables.forEach((index, build) -> pages.set(index, build.get()));
        showPage();
    }

    /** A titled section of a page: a token panel with a caption over its content. */
    private Table card(String key, Actor content) {
        return titledCard(colonless(key), content);
    }

    /** A section of a page under {@code title}, already in words. */
    Table titledCard(String title, Actor content) {
        Table card = kit.plainPanel();
        card.top().left().defaults().left();
        card.pad(space("lg"));
        card.add(kit.caption(title, true)).row();
        card.add(content).growX().padTop(space("sm")).row();
        Table wrap = new Table();
        wrap.add(card).growX().padBottom(space("md"));
        return wrap;
    }

    private Table memberPage(Member member, @Nullable BufferedImage portrait) {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(card("CustomMekDialog.sectionIdentity", identity(member, portrait))).row();
        content.add(card("CustomMekDialog.sectionSkills", skills(member))).row();
        if (member.showsPersonalEquipment() || member.shows(Skill.TOUGHNESS) || member.shows(Skill.FATIGUE)) {
            content.add(card("CustomMekDialog.sectionRpgExtras", extras(member))).row();
        }
        if (!member.backups().isEmpty() || !member.swaps().isEmpty()) {
            content.add(card("CustomMekDialog.sectionCrewRole", role(member))).row();
        }
        if (!form.commandTab()) {
            content.add(card("CustomMekDialog.sectionCommand", command())).row();
        }
        return content;
    }

    private Table identity(Member member, @Nullable BufferedImage portrait) {
        int slot = member.slot();
        Table row = new Table();
        row.top().left();
        Table picture = new Table();
        float portraitSize = size("control.lg") * 2.5f;
        Image image = new Image();
        image.setScaling(Scaling.fit);
        portraits.put(slot, image);
        showPortrait(slot, portrait);
        picture.add(image).size(portraitSize).row();
        UiButton change = name(kit.button("button-secondary-compact", "palette",
              Messages.getString("CustomMekDialog.labPortrait"), null), "bay-portrait-" + slot);
        UiKit.onChange(change, () -> choosePortrait(slot));
        refreshers.add(() -> change.setDisabled(busy || !member.enabled()));
        picture.add(change).padTop(space("sm"));
        row.add(picture).top().padRight(space("xl"));

        Table fields = new Table();
        fields.top().left().defaults().left().growX();
        if (form.commandTab()) {
            fields.add(kit.typeLabel(member.role(), "overline", "text.secondary")).row();
        }
        UiTextField name = name(kit.input(member.name(), false), "bay-name-" + slot);
        UiKit.onChange(name, () -> member.name(name.getText()));
        UiButton randomName = randomButton("bay-random-name-" + slot, "CustomMekDialog.RandomName", member,
              () -> form.randomName(slot), () -> name.setText(member.name()));
        UiTextField nickname = name(kit.input(member.nickname(), false), "bay-nickname-" + slot);
        UiKit.onChange(nickname, () -> member.nickname(nickname.getText()));
        UiButton randomCallsign = randomButton("bay-random-callsign-" + slot, "CustomMekDialog.RandomCallsign",
              member, () -> form.randomCallsign(slot), () -> nickname.setText(member.nickname()));
        // Both buttons take the wider one's width, so the name and callsign fields end at one edge
        float buttonWidth = Math.max(randomName.getPrefWidth(), randomCallsign.getPrefWidth());
        fields.add(withButton(kit.formField(colonless("CustomMekDialog.labName"), name), randomName, buttonWidth))
              .row();
        fields.add(withButton(kit.formField(colonless("CustomMekDialog.labNick"), nickname), randomCallsign,
              buttonWidth)).padTop(space("sm")).row();
        refreshers.add(() -> {
            name.setDisabled(!member.enabled());
            nickname.setDisabled(!member.enabled());
        });

        Table checks = new Table();
        checks.left();
        if (!member.callsign().isEmpty()) {
            checks.add(kit.chip(Messages.getString("CustomMekDialog.Callsign") + " " + member.callsign(),
                  UiKit.Tone.NORMAL, true)).padRight(space("lg"));
        }
        UiChoice clan = name(kit.check(Messages.getString("CustomMekDialog.chkClanPilot"), member.clanPilot()),
              "bay-clan-" + slot);
        UiKit.onChange(clan, () -> member.clanPilot(clan.isChecked()));
        refreshers.add(() -> clan.setDisabled(!member.enabled()));
        checks.add(clan);
        if (member.showsMissing()) {
            UiChoice missing = name(kit.check(Messages.getString("CustomMekDialog.chkMissing"), member.missing()),
                  "bay-missing-" + slot);
            UiKit.onChange(missing, () -> {
                member.missing(missing.isChecked());
                refresh();
            });
            refreshers.add(() -> {
                missing.setChecked(member.missing());
                missing.setDisabled(!form.editable() || !member.missingEnabled());
            });
            checks.add(missing).padLeft(space("lg"));
        }
        fields.add(checks).padTop(space("md")).row();
        row.add(fields).growX().top();
        return row;
    }

    private static Table withButton(Actor field, Actor button, float buttonWidth) {
        Table row = new Table();
        row.bottom().left();
        row.add(field).growX().bottom();
        row.add(button).width(buttonWidth).bottom().padLeft(space("sm"));
        return row;
    }

    private UiButton randomButton(String name, String key, Member member, Runnable roll, Runnable shown) {
        UiButton button = name(kit.button("button-secondary", "replay", Messages.getString(key), null), name);
        UiKit.onChange(button, () -> {
            if (busy) {
                return;
            }
            busy = true;
            refresh();
            host.edt(roll, () -> {
                busy = false;
                shown.run();
                refresh();
            });
        });
        refreshers.add(() -> button.setDisabled(busy || !member.enabled()));
        return button;
    }

    private Table skills(Member member) {
        int slot = member.slot();
        Table content = new Table();
        content.top().left().defaults().left();
        Table dials = new Table();
        dials.left().defaults().top().padRight(space("xxl"));
        if (member.shows(Skill.GUNNERY)) {
            dials.add(dial(member, Skill.GUNNERY, "CustomMekDialog.labGunnery"));
        } else {
            dials.add(dial(member, Skill.GUNNERY_L, "CustomMekDialog.labGunneryL"));
            dials.add(dial(member, Skill.GUNNERY_M, "CustomMekDialog.labGunneryM"));
            dials.add(dial(member, Skill.GUNNERY_B, "CustomMekDialog.labGunneryB"));
        }
        dials.add(dial(member, Skill.PILOTING, member.pilotingKey()));
        if (member.shows(Skill.ARTILLERY)) {
            dials.add(dial(member, Skill.ARTILLERY, "CustomMekDialog.labArtillery"));
        }
        UiButton random = randomButton("bay-random-skills-" + slot, "CustomMekDialog.RandomSkill", member,
              () -> form.randomSkills(slot), () -> { });
        dials.add(random).bottom().padRight(0);
        content.add(dials).row();
        if (member.lamPilot()) {
            Table aero = new Table();
            aero.left().defaults().top().padRight(space("xxl"));
            if (member.shows(Skill.GUNNERY_AERO)) {
                aero.add(dial(member, Skill.GUNNERY_AERO, "CustomMekDialog.labGunneryAero"));
            } else {
                aero.add(dial(member, Skill.GUNNERY_AERO_L, "CustomMekDialog.labGunneryAeroL"));
                aero.add(dial(member, Skill.GUNNERY_AERO_M, "CustomMekDialog.labGunneryAeroM"));
                aero.add(dial(member, Skill.GUNNERY_AERO_B, "CustomMekDialog.labGunneryAeroB"));
            }
            aero.add(dial(member, Skill.PILOTING_AERO, "CustomMekDialog.labPilotingAero"));
            content.add(aero).padTop(space("lg")).row();
        }


        Table aptitudes = new Table();
        aptitudes.left();
        boolean any = false;
        for (Skill skill : List.of(Skill.GUNNERY, Skill.PILOTING, Skill.GUNNERY_AERO, Skill.PILOTING_AERO,
              Skill.ARTILLERY, Skill.SMALL_ARMS)) {
            if (!member.showsAptitude(skill)) {
                continue;
            }
            UiButton chip = name(kit.button("button-toggle-compact", "star", aptitudeName(member, skill), null),
                  "bay-aptitude-" + key(skill) + "-" + slot);
            chip.setProgrammaticChangeEvents(false);
            kit.tip(chip, true).getActor().setText(Messages.getString("CustomMekDialog.naturalAptitude.tooltip"));
            UiKit.onChange(chip, () -> {
                member.aptitude(skill, !member.aptitude(skill));
                refresh();
            });
            refreshers.add(() -> {
                chip.setChecked(member.aptitude(skill));
                chip.setDisabled(!member.enabled());
            });
            aptitudes.add(chip).padRight(space("xs"));
            any = true;
        }
        if (any) {
            content.add(kit.typeLabel(Messages.getString("Lobby.bay.aptitudes"), "small", "text.secondary"))
                  .padTop(space("lg")).row();
            content.add(aptitudes).padTop(space("xs")).row();
        }
        return content;
    }

    private static String aptitudeName(Member member, Skill skill) {
        String key = switch (skill) {
            case PILOTING -> switch (member.pilotingKey()) {
                case "CustomMekDialog.labDriving" -> "Driving";
                case "CustomMekDialog.labAntiMek" -> "AntiMek";
                default -> "Piloting";
            };
            case PILOTING_AERO -> "PilotingAero";
            case GUNNERY_AERO -> "GunneryAero";
            case ARTILLERY -> "Artillery";
            case SMALL_ARMS -> "SmallArms";
            default -> "Gunnery";
        };
        return Messages.getString("BoardView1.Tooltip.NaturalAptitude." + key);
    }

    private static String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A skill as a large number between − and +, its caption above and its error below. */
    private Table dial(Member member, Skill skill, String labelKey) {
        String prefix = "bay-" + key(skill) + "-" + member.slot();
        Table dial = name(new Table(), prefix);
        Label value = name(kit.typeLabel(member.text(skill), "display", "text.primary"), prefix + "-value");
        value.setAlignment(Align.center);
        UiButton down = name(kit.button("button-icon-compact", "remove", null, null), prefix + "-down");
        UiButton up = name(kit.button("button-icon-compact", "add", null, null), prefix + "-up");
        Label error = kit.typeLabel("", "small", "status.danger");
        UiKit.onChange(down, () -> stepSkill(member, skill, -1));
        UiKit.onChange(up, () -> stepSkill(member, skill, 1));
        dial.add(kit.typeLabel(colonless(labelKey), "overline", "text.secondary")).colspan(3).center().row();
        dial.add(down).size(size("control.sm"));
        dial.add(value).minWidth(size("control.lg")).center().padLeft(space("sm")).padRight(space("sm"));
        dial.add(up).size(size("control.sm")).row();
        dial.add(error).colspan(3).center().row();
        refreshers.add(() -> {
            value.setText(member.text(skill));
            Integer number = parse(member.text(skill));
            int shown = (number == null) ? -1 : number;
            down.setDisabled(!member.enabled() || shown <= 0);
            up.setDisabled(!member.enabled() || shown >= UnitConfigForm.MAX_SKILL);
            error.setText(member.error(skill).map(LobbyUnitBayView::problem).orElse(""));
        });
        return dial;
    }

    private void stepSkill(Member member, Skill skill, int delta) {
        Integer number = parse(member.text(skill));
        int stepped = UiStepper.clamp((number == null ? 0 : number) + delta, 0, UnitConfigForm.MAX_SKILL);
        member.text(skill, Integer.toString(stepped));
        refresh();
    }

    private static @Nullable Integer parse(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String problem(Problem problem) {
        return Messages.getString(problem.key(), problem.values().toArray());
    }

    private Table extras(Member member) {
        int slot = member.slot();
        Table content = new Table();
        content.top().left().defaults().left().top();
        List<Actor> cells = new ArrayList<>();
        if (member.shows(Skill.TOUGHNESS)) {
            cells.add(memberField(member, Skill.TOUGHNESS, "CustomMekDialog.labTough", true));
        }
        if (member.shows(Skill.FATIGUE)) {
            cells.add(memberField(member, Skill.FATIGUE, "CustomMekDialog.labFatigue", false));
        }
        if (member.showsPersonalEquipment()) {
            cells.add(memberField(member, Skill.SMALL_ARMS, "CustomMekDialog.labSmallArms", false));
            List<UnitConfigForm.Choice> kits = member.armorKits();
            UiSelect armorKit = name(choices(kits, "CustomMekDialog.choArmorKit.none", member.armorKit()),
                  "bay-armor-kit-" + slot);
            UiKit.onChange(armorKit, () -> member.armorKit(kits.get(armorKit.getSelectedIndex()).internalName()));
            List<UnitConfigForm.Choice> arms = member.sidearms();
            UiSelect sidearm = name(choices(arms, "CustomMekDialog.choSidearm.none", member.sidearm()),
                  "bay-sidearm-" + slot);
            UiKit.onChange(sidearm, () -> member.sidearm(arms.get(sidearm.getSelectedIndex()).internalName()));
            refreshers.add(() -> {
                armorKit.setDisabled(!member.enabled());
                sidearm.setDisabled(!member.enabled());
            });
            cells.add(kit.formField(Messages.getString("CustomMekDialog.choArmorKit"), armorKit));
            cells.add(kit.formField(Messages.getString("CustomMekDialog.choSidearm"), sidearm));
        }
        grid(content, cells, 3);
        return content;
    }

    /** Lays {@code cells} out {@code columns} to a row, of even width. */
    private static void grid(Table content, List<Actor> cells, int columns) {
        for (int i = 0; i < cells.size(); i++) {
            content.add(cells.get(i)).growX().uniformX().padRight(space("lg")).padBottom(space("sm"));
            if (i % columns == columns - 1) {
                content.row();
            }
        }
        for (int i = cells.size() % columns; i > 0 && i < columns; i++) {
            content.add().growX().uniformX();
        }
    }

    private UiSelect choices(List<UnitConfigForm.Choice> choices, String noneKey, @Nullable String selected) {
        String[] names = new String[choices.size()];
        int index = 0;
        for (int i = 0; i < choices.size(); i++) {
            UnitConfigForm.Choice choice = choices.get(i);
            names[i] = choice.internalName() == null ? Messages.getString(noneKey) : choice.name();
            if (choice.internalName() == null ? selected == null : choice.internalName().equals(selected)) {
                index = i;
            }
        }
        UiSelect select = kit.tokenSelect(true, names);
        select.select(index);
        return select;
    }

    private UiFormField memberField(Member member, Skill skill, String label, boolean signed) {
        UiTextField input = name(kit.input(member.text(skill), true), "bay-" + key(skill) + "-" + member.slot());
        input.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || (typed == '-' && signed));
        UiFormField field = kit.formField(colonless(label), input);
        UiKit.onChange(input, () -> {
            member.text(skill, input.getText());
            refresh();
        });
        refreshers.add(() -> {
            input.setDisabled(!member.enabled());
            field.error(member.error(skill).map(LobbyUnitBayView::problem).orElse(null));
        });
        return field;
    }

    private Table role(Member member) {
        int slot = member.slot();
        Table content = new Table();
        content.top().left().defaults().left().top();
        List<Actor> cells = new ArrayList<>();
        List<Integer> backups = member.backups();
        if (!backups.isEmpty()) {
            String[] names = new String[backups.size()];
            int index = 0;
            for (int i = 0; i < backups.size(); i++) {
                names[i] = member.roleOf(backups.get(i));
                if (backups.get(i) == member.backup()) {
                    index = i;
                }
            }
            UiSelect backup = name(kit.tokenSelect(true, names), "bay-backup-" + slot);
            backup.select(index);
            UiKit.onChange(backup, () -> member.backup(backups.get(backup.getSelectedIndex())));
            refreshers.add(() -> backup.setDisabled(!member.enabled()));
            cells.add(kit.formField(Messages.getString(member.pilot() ? "CustomMekDialog.labBackupPilot"
                  : "CustomMekDialog.labBackupGunner"), backup));
        }
        List<UnitConfigForm.Swap> swaps = member.swaps();
        if (!swaps.isEmpty()) {
            String[] names = new String[swaps.size() + 1];
            names[0] = Messages.getString("CustomMekDialog.doNotSwapUnits");
            for (int i = 0; i < swaps.size(); i++) {
                names[i + 1] = swaps.get(i).label();
            }
            UiSelect swap = name(kit.tokenSelect(true, names), "bay-swap-" + slot);
            UiKit.onChange(swap, () -> member.swap(swap.getSelectedIndex()));
            refreshers.add(() -> {
                if (swap.getSelectedIndex() != member.swap()) {
                    swap.select(member.swap());
                }
                swap.setDisabled(!member.enabled());
            });
            cells.add(kit.formField(colonless("CustomMekDialog.labUnitNum"), swap));
        }
        grid(content, cells, 2);
        return content;
    }

    private Table command() {
        Table content = new Table();
        content.top().left().defaults().left().top();
        List<Actor> cells = new ArrayList<>();
        if (form.shows(Field.INIT)) {
            cells.add(field(Field.INIT, "CustomMekDialog.labInit", null, true));
        }
        if (form.shows(Field.COMMAND)) {
            cells.add(field(Field.COMMAND, "CustomMekDialog.labCommandInit", null, true));
        }
        UiChoice commander = name(kit.check(Messages.getString("CustomMekDialog.labCommander"), form.commander()),
              "bay-commander");
        UiKit.onChange(commander, () -> form.commander(commander.isChecked()));
        refreshers.add(() -> commander.setDisabled(!form.editable()));
        Table check = new Table();
        check.bottom().left();
        check.add(commander).padBottom(space("sm"));
        cells.add(check);
        grid(content, cells, 3);
        return content;
    }

    /** A whole-number field of the form, its error under it. */
    private UiFormField field(Field field, String label, @Nullable String tip, boolean signed) {
        UiTextField input = name(kit.input(form.text(field), true), "bay-" + key(field));
        input.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || (typed == '-' && signed));
        UiFormField formField = kit.formField(colonless(label), input);
        if (tip != null) {
            kit.tip(input, true).getActor().setText(Messages.getString(tip));
        }
        UiKit.onChange(input, () -> {
            form.text(field, input.getText());
            refresh();
        });
        refreshers.add(() -> {
            input.setDisabled(!form.enabled(field));
            formField.error(form.error(field).map(LobbyUnitBayView::problem).orElse(null));
        });
        return formField;
    }

    /** A whole-number field of the form that cannot go below zero, as a stepper under its caption. */
    private Table fieldStepper(Field field, String label, String tip) {
        Integer current = parse(form.text(field));
        int most = Math.max(Math.max(form.boardWidth(), form.boardHeight()), current == null ? 0 : current);
        UiStepper stepper = name(kit.stepper(null, current == null ? 0 : current, 0, most), "bay-" + key(field));
        name(stepper.down(), "bay-" + key(field) + "-down");
        name(stepper.up(), "bay-" + key(field) + "-up");
        kit.tip(stepper, true).getActor().setText(Messages.getString(tip));
        UiKit.onChange(stepper, () -> {
            form.text(field, Integer.toString(stepper.value()));
            refresh();
        });
        refreshers.add(() -> {
            boolean enabled = form.enabled(field);
            stepper.down().setDisabled(!enabled || stepper.value() <= 0);
            stepper.up().setDisabled(!enabled || stepper.value() >= most);
        });
        Table content = new Table();
        content.top().left().defaults().left();
        content.add(kit.typeLabel(colonless(label), "small", "text.secondary")).row();
        content.add(stepper).padTop(space("xs")).row();
        return content;
    }

    // ---- Deployment ----

    private Table deploymentPage() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(card("CustomMekDialog.labDeploymentZone", zone())).row();
        content.add(card("Lobby.bay.round", round())).row();
        Optional<Table> state = state();
        state.ifPresent(table -> content.add(card("Lobby.bay.state", table)).row());
        if (form.shows(Field.VELOCITY) || form.shows(Field.HEIGHT) || form.showsAirborne()) {
            content.add(card("Lobby.bay.flight", flight())).row();
        }
        if (form.showsOffBoard()) {
            content.add(card("CustomMekDialog.labOffBoard", offBoard())).row();
        }
        return content;
    }

    private Table zone() {
        Table content = new Table();
        content.top().left();
        UiButton owners = name(kit.button("button-toggle", "locate", Messages.getString("Lobby.bay.ownersZone"), null),
              "bay-zone-owner");
        kit.tip(owners, true).getActor().setText(Messages.getString("Lobby.bay.ownersZone.tip"));
        zoneToggle(owners, Board.START_NONE);
        Table side = new Table();
        side.top().left().defaults().left();
        Board board = form.board();
        if (board != null) {
            // The board to click on the left, everything else beside it
            Table picture = new Table();
            picture.top().left();
            picture.add(kit.typeLabel(Messages.getString("Lobby.bay.mapZone"), "small", "text.secondary")).left()
                  .row();
            zoneMap = new LobbyZoneMap(kit, host.boardPicture(), board.getWidth(), board.getHeight(),
                  size("control.lg") * ZONE_MAP_SIDE, form::zone, form::zoneHexes, id -> {
                      form.zone(id);
                      refresh();
                  }, zoneNames());
            kit.tip(zoneMap, true).getActor().setText(Messages.getString("Lobby.bay.mapZone.tip"));
            refreshers.add(zoneMap::refresh);
            picture.add(zoneMap).left().padTop(space("xs")).row();
            content.add(picture).top().padRight(space("xl"));
            side.add(owners).height(size("control.lg")).row();
            side.add(kit.typeLabel(Messages.getString("Lobby.bay.orZone"), "small", "text.secondary"))
                  .padTop(space("sm")).row();
            Table chips = new Table();
            chips.left().defaults().height(size("control.sm")).padRight(space("xs")).padTop(space("xxs"));
            chips.add(zoneButton(Board.START_ANY, zoneName(Board.START_ANY)));
            chips.add(zoneButton(Board.START_EDGE, zoneName(Board.START_EDGE)));
            for (int number : form.customZones()) {
                chips.add(zoneButton(Board.encodeCustomDeploymentZoneID(number),
                      Messages.getString("Lobby.bay.customZone", number)));
            }
            side.add(chips).padTop(space("xxs")).row();
        } else {
            float cell = size("control.lg") * 2.4f;
            Table grid = new Table();
            grid.defaults().width(cell).height(size("control.lg")).pad(space("xxs"));
            for (int[] row : GRID) {
                for (int id : row) {
                    grid.add(zoneButton(id, zoneName(id)));
                }
                grid.row();
            }
            Table column = new Table();
            column.top().left();
            column.add(owners).width(cell * 3 + space("xxs") * 4).height(size("control.lg")).left().row();
            column.add(kit.typeLabel(Messages.getString("Lobby.bay.orZone"), "small", "text.secondary"))
                  .left().padTop(space("sm")).row();
            column.add(grid).left().padTop(space("xxs")).row();
            List<Integer> custom = form.customZones();
            if (!custom.isEmpty()) {
                Table zones = new Table();
                zones.left().defaults().height(size("control.sm")).pad(space("xxs"));
                for (int number : custom) {
                    zones.add(zoneButton(Board.encodeCustomDeploymentZoneID(number),
                          Messages.getString("Lobby.bay.customZone", number)));
                }
                column.add(zones).left().padTop(space("xs")).row();
            }
            content.add(column).top().padRight(space("xxl"));
        }
        Table steppers = new Table();
        steppers.top().left().defaults().left().top();
        steppers.add(fieldStepper(Field.OFFSET, "CustomMekDialog.labDeploymentOffset",
              "CustomMekDialog.labDeploymentOffsetTip")).padRight(space("xl"));
        steppers.add(fieldStepper(Field.WIDTH, "CustomMekDialog.labDeploymentWidth",
              "CustomMekDialog.labDeploymentWidthTip"));
        side.add(steppers).padTop(board != null ? space("lg") : 0).row();
        Label edgeOnly = name(kit.typeLabel(Messages.getString("Lobby.bay.edgeOnly"), "small", "text.secondary"),
              "bay-edge-only");
        edgeOnly.setWrap(true);
        side.add(edgeOnly).width(size("control.lg") * 6).padTop(space("xs")).row();
        refreshers.add(() -> {
            boolean offset = form.enabled(Field.OFFSET);
            boolean width = form.enabled(Field.WIDTH);
            edgeOnly.setVisible(!offset || !width);
            edgeOnly.setText(Messages.getString(offset ? "Lobby.bay.widthFixed" : "Lobby.bay.edgeOnly"));
        });
        Table box = anyBox();
        side.add(box).padTop(space("md")).row();
        refreshers.add(() -> box.setVisible(form.zone() == Board.START_ANY));
        content.add(side).growX().top();
        return content;
    }
    private static String[] zoneNames() {
        String[] names = new String[ZONE_KEYS.length];
        for (int id = 0; id < names.length; id++) {
            names[id] = zoneName(id);
        }
        return names;
    }

    private static String zoneName(int id) {
        return Messages.getString("CustomMekDialog." + ZONE_KEYS[id]);
    }

    private UiButton zoneButton(int id, String text) {
        UiButton button = name(kit.button("button-toggle-compact", null, text, null), "bay-zone-" + id);
        return zoneToggle(button, id);
    }

    private UiButton zoneToggle(UiButton button, int id) {
        button.setProgrammaticChangeEvents(false);
        button.getLabel().setEllipsis(true);
        button.getLabelCell().minWidth(0);
        UiKit.onChange(button, () -> {
            form.zone(id);
            refresh();
        });
        refreshers.add(() -> button.setChecked(form.zone() == id));
        return button;
    }

    private Table anyBox() {
        Table box = new Table();
        box.top().left().defaults().left();
        box.add(kit.typeLabel(Messages.getString("Lobby.bay.anyBox"), "small", "text.secondary")).colspan(2).row();
        String[] corners = { "CustomMekDialog.labDeploymentAnyNW", "CustomMekDialog.labDeploymentAnySE" };
        String[] names = { "nw", "se" };
        for (int corner = 0; corner < 2; corner++) {
            box.add(kit.typeLabel(Messages.getString(corners[corner]).replace(":", ""), "body", "text.primary"))
                  .padTop(space("xs")).padRight(space("md"));
            Table steppers = new Table();
            for (int axis = 0; axis < 2; axis++) {
                int index = corner * 2 + axis;
                int most = axis == 0 ? form.boardWidth() : form.boardHeight();
                UiStepper stepper = name(kit.stepper(axis == 0 ? "x" : "y", form.anyBox()[index], 0, most),
                      "bay-any-" + names[corner] + (axis == 0 ? "-x" : "-y"));
                UiKit.onChange(stepper, () -> form.anyBox(index, stepper.value()));
                steppers.add(stepper).padRight(space("md"));
            }
            box.add(steppers).padTop(space("xs")).row();
        }
        return box;
    }

    private Table round() {
        Table content = new Table();
        content.left();
        UiStepper stepper = name(kit.stepper(null, form.round(), form.firstRound(), UnitConfigForm.LAST_ROUND),
              "bay-round");
        name(stepper.down(), "bay-round-down");
        name(stepper.up(), "bay-round-up");
        Label shown = name(kit.typeLabel("", "body.strong", "text.primary"), "bay-round-text");
        UiKit.onChange(stepper, () -> {
            form.round(stepper.value());
            refresh();
        });
        refreshers.add(() -> {
            shown.setText(roundName(form.round()));
            boolean enabled = form.roundEnabled();
            stepper.down().setDisabled(!enabled || form.round() <= form.firstRound());
            stepper.up().setDisabled(!enabled || form.round() >= UnitConfigForm.LAST_ROUND);
        });
        content.add(stepper).padRight(space("lg"));
        content.add(shown);
        return content;
    }

    private static String roundName(int round) {
        if (round < 0) {
            return Messages.getString("CustomMekDialog.PreGame");
        }
        return round == 0 ? Messages.getString("CustomMekDialog.StartOfGame")
              : Messages.getString("CustomMekDialog.AfterRound") + " " + round;
    }

    private Optional<Table> state() {
        Table content = new Table();
        content.top().left().defaults().left();
        boolean any = false;
        List<String> modes = form.modes();
        if (!modes.isEmpty()) {
            String[] names = new String[modes.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = Messages.getString(modes.get(i));
            }
            UiSegmented mode = name(kit.tokenSegmented(true, names), "bay-mode");
            for (int i = 0; i < names.length; i++) {
                mode.option(i).setName("bay-mode-" + i);
            }
            mode.select(form.mode());
            UiKit.onChange(mode, () -> {
                form.mode(mode.getSelectedIndex());
                refresh();
            });
            refreshers.add(() -> mode.setDisabled(!form.modeEnabled()));
            content.add(kit.typeLabel(colonless("CustomMekDialog.labStartingMode"), "small", "text.secondary"))
                  .row();
            content.add(mode).padTop(space("xs")).padBottom(space("md")).row();
            any = true;
        }
        Table chips = new Table();
        chips.left();
        if (form.showsProne()) {
            chips.add(stance("bay-prone", "prone", "CustomMekDialog.labDeployProne", form::prone, form::prone,
                  form::proneEnabled)).padRight(space("xs"));
        }
        if (form.showsHullDown()) {
            chips.add(stance("bay-hull-down", "hull-down", "CustomMekDialog.labDeployHullDown", form::hullDown,
                  form::hullDown, form::hullDownEnabled)).padRight(space("xs"));
        }
        if (form.showsShutdown()) {
            chips.add(stance("bay-shutdown", "power", "CustomMekDialog.labDeployShutdown", form::shutdown,
                  form::shutdown, form::editable)).padRight(space("xs"));
        }
        if (form.showsDugIn()) {
            chips.add(stance("bay-dug-in", "shield-person", "CustomMekDialog.labDeployDugIn", form::dugIn,
                  form::dugIn, form::editable)).padRight(space("xs"));
        }
        if (form.showsHidden()) {
            chips.add(stance("bay-hidden", "hidden", "CustomMekDialog.labHidden", form::hidden, form::hidden,
                  form::hiddenEnabled)).padRight(space("xs"));
        }
        if (form.showsStealth()) {
            chips.add(stance("bay-stealth", "jammed", "CustomMekDialog.labDeployStealth", form::stealth,
                  form::stealth, form::editable)).padRight(space("xs"));
        }
        if (form.showsScanTarget()) {
            chips.add(stance("bay-scan", "target", "CustomMekDialog.labScanTarget", form::scanTarget,
                  form::scanTarget, form::editable)).padRight(space("xs"));
        }
        if (chips.getChildren().size > 0) {
            content.add(chips).row();
            any = true;
        }
        return any ? Optional.of(content) : Optional.empty();
    }

    /** A starting state as a chip that is on or off. */
    private UiButton stance(String name, String icon, String key, java.util.function.BooleanSupplier value,
          java.util.function.Consumer<Boolean> set, java.util.function.BooleanSupplier enabled) {
        UiButton chip = name(kit.button("button-toggle-compact", icon, colonless(key), null), name);
        chip.setProgrammaticChangeEvents(false);
        UiKit.onChange(chip, () -> {
            set.accept(!value.getAsBoolean());
            refresh();
        });
        refreshers.add(() -> {
            chip.setChecked(value.getAsBoolean());
            chip.setDisabled(!enabled.getAsBoolean());
        });
        return chip;
    }

    private Table flight() {
        Table content = new Table();
        content.top().left().defaults().left().top();
        List<Actor> cells = new ArrayList<>();
        if (form.shows(Field.VELOCITY)) {
            cells.add(field(Field.VELOCITY, "CustomMekDialog.labStartVelocity", null, false));
        }
        if (form.shows(Field.ALTITUDE)) {
            cells.add(field(Field.ALTITUDE, "CustomMekDialog.labStartAltitude", null, false));
        }
        if (form.shows(Field.FUEL)) {
            cells.add(field(Field.FUEL, "CustomMekDialog.labCurrentFuel", null, false));
        }
        if (form.shows(Field.HEIGHT)) {
            cells.add(field(Field.HEIGHT, "CustomMekDialog.labStartHeight", null, false));
        }
        if (form.showsAirborne()) {
            Table chip = new Table();
            chip.bottom().left();
            chip.add(stance("bay-airborne", "arrow-up", "CustomMekDialog.labDeployAirborne", form::airborne,
                  form::airborne, form::editable)).padBottom(space("sm"));
            cells.add(chip);
        }
        grid(content, cells, 3);
        return content;
    }

    private Table offBoard() {
        Table content = new Table();
        content.top().left().defaults().left();
        UiButton toggle = stance("bay-offboard", "arrow-right", "CustomMekDialog.labOffBoard", form::offBoard,
              form::offBoard, form::editable);
        content.add(toggle).row();
        String[] directions = { Messages.getString("CustomMekDialog.North"), Messages.getString("CustomMekDialog.South"),
                                Messages.getString("CustomMekDialog.East"), Messages.getString("CustomMekDialog.West") };
        UiSegmented direction = name(kit.tokenSegmented(true, directions), "bay-direction");
        direction.select(form.direction());
        UiKit.onChange(direction, () -> form.direction(direction.getSelectedIndex()));
        UiStepper distance = name(kit.stepper(colonless("CustomMekDialog.labOffBoardDistance"), form.distance(),
              UnitConfigForm.MIN_OFF_BOARD, form.maxDistance()), "bay-distance");
        UiKit.onChange(distance, () -> form.distance(distance.value()));
        refreshers.add(() -> {
            boolean enabled = form.editable() && form.offBoard();
            direction.setDisabled(!enabled);
            distance.down().setDisabled(!enabled || form.distance() <= UnitConfigForm.MIN_OFF_BOARD);
            distance.up().setDisabled(!enabled || form.distance() >= form.maxDistance());
        });
        Table row = new Table();
        row.left();
        row.add(kit.typeLabel(colonless("CustomMekDialog.labOffBoardDirection"), "small", "text.secondary"))
              .padRight(space("sm"));
        row.add(direction).padRight(space("xl"));
        row.add(distance);
        content.add(row).padTop(space("md")).row();
        return content;
    }

    // ---- The footer ----

    /** The status line over the buttons, which are centred with the main one first, as every dialog's footer. */
    private Table footer() {
        Table footer = new Table();
        status = name(kit.typeLabel("", "small", "status.danger"), "bay-status");
        status.setAlignment(Align.center);
        status.setWrap(true);
        footer.add(status).growX().minWidth(0).row();
        UiButton cancel = name(kit.button("button-secondary", null, Messages.getString("Cancel.text"), null),
              "bay-cancel");
        UiKit.onChange(cancel, () -> {
            if (!busy) {
                host.close();
            }
        });
        apply = name(kit.button("button-secondary", null, Messages.getString("Lobby.bay.apply"), null), "bay-apply");
        UiKit.onChange(apply, () -> confirm(false));
        ok = name(kit.button("button-primary", "check", Messages.getString("Ok.text"), null), "bay-ok");
        UiKit.onChange(ok, () -> confirm(true));
        Table buttons = new Table();
        float width = Math.max(UiDialog.MIN_BUTTON_WIDTH, Math.max(ok.getPrefWidth(),
              Math.max(apply.getPrefWidth(), cancel.getPrefWidth())));
        buttons.add(ok).width(width);
        buttons.add(apply).width(width).padLeft(space("md"));
        buttons.add(cancel).width(width).padLeft(space("md"));
        footer.add(buttons).padTop(space("sm"));
        return footer;
    }

    /** Shows the form's state: the values, which controls may be used, the errors, and what may be pressed. */
    void refresh() {
        refreshers.forEach(Runnable::run);
        if (equipment != null) {
            equipment.refresh();
        }
        optionPages.forEach(LobbyUnitBayOptions::refresh);
        boolean valid = form.valid();
        status.setText(valid ? String.join(" ", form.warnings()) : Messages.getString("Lobby.bay.invalid"));
        ok.setDisabled(!valid || busy);
        apply.setDisabled(!valid || busy);
        previous.setDisabled(!valid || busy || !form.hasPrevious());
        next.setDisabled(!valid || busy || !form.hasNext());
    }

    private void confirm(boolean close) {
        if (!form.valid() || busy) {
            return;
        }
        busy = true;
        refresh();
        boolean[] applied = new boolean[1];
        host.edt(() -> applied[0] = host.apply(form), () -> {
            busy = false;
            if (applied[0] && close) {
                host.close();
            } else {
                if (applied[0]) {
                    renew();
                }
                refresh();
            }
        });
    }

    /** Applies, as OK, then opens the next unit, or the one before, in the bay. */
    private void step(boolean forward) {
        if (!form.valid() || busy) {
            return;
        }
        busy = true;
        refresh();
        host.edt(() -> {
            if (host.apply(form)) {
                host.next(form, forward);
            }
        }, () -> {
            busy = false;
            refresh();
        });
    }

    private void choosePortrait(int slot) {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        BufferedImage[] chosen = new BufferedImage[1];
        host.edt(() -> chosen[0] = host.portrait(form, slot), () -> {
            busy = false;
            if (chosen[0] != null) {
                showPortrait(slot, chosen[0]);
            }
            refresh();
        });
    }

    private void showPortrait(int slot, @Nullable BufferedImage picture) {
        Image image = portraits.get(slot);
        if (image == null || picture == null) {
            return;
        }
        Texture texture = GpuStartScreen.texture(picture, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        textures.add(texture);
        image.setDrawable(new TextureRegionDrawable(texture));
    }

    /** The bay's content, for the centre column. */
    Table body() {
        return body;
    }

    /** GPU thread, once the bay has left the centre: gives the pictures back. */
    void dispose() {
        textures.forEach(Texture::dispose);
        textures.clear();
        if (zoneMap != null) {
            zoneMap.dispose();
        }
    }

    // ---- For the probe and the tests ----

    UnitConfigForm form() {
        return form;
    }

    /** The bay's control named {@code name}, such as {@code bay-ok} or {@code bay-gunnery-0-up}, or null. */
    @Nullable Actor control(String name) {
        Actor actor = named.get(name);
        return actor != null ? actor : body.findActor(name);
    }
}
