/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.lobby.RequisitionAlphaStrike;
import megamek.client.lobby.RequisitionCardImage;
import megamek.client.lobby.RequisitionCatalog;
import megamek.client.lobby.RequisitionCatalog.Category;
import megamek.client.lobby.RequisitionCatalog.Either;
import megamek.client.lobby.RequisitionCatalog.ExactType;
import megamek.client.lobby.RequisitionCatalog.Filters;
import megamek.client.lobby.RequisitionCatalog.TechBase;
import megamek.client.lobby.RequisitionCatalog.Weight;
import megamek.client.lobby.RequisitionDamage;
import megamek.client.lobby.RequisitionDossier;
import megamek.client.lobby.RequisitionFactions;
import megamek.client.lobby.RequisitionRecord;
import megamek.client.lobby.RequisitionRow;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.lobby.RequisitionVisit;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.gdx.UiArcDiagram;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiOptionBar;
import megamek.client.ui.gdx.UiReveal;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepChart;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiVirtualList;
import megamek.common.TechConstants;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitRole;
import megamek.logging.MMLogger;

/**
 * The lobby's Requisition view (gap analysis 7.1 to 7.5): the unit selector in the forces' place. Category tabs over
 * the search, the weight chips and "Filters", which opens a drawer in place with the rest of the filters, or shows
 * those at work as tags; the results, one slim row per unit, only those in view drawn; a dossier of the picked unit
 * beside them, sliding over them in a narrow window; and the adding strip: who the units are for, their skills and how
 * many, Add, the running Battle Value and tonnage of what this visit added, each with × to take it back, and the other
 * sources of units. Searches run away from the GPU thread ({@link RequisitionSearch}); every change to the game goes
 * to the window ({@link Actions}), which adds units as the unit selector dialog does.
 */
final class LobbyRequisitionPanel {
    private static final MMLogger LOGGER = MMLogger.create(LobbyRequisitionPanel.class);
    /** The pause after typing before the search runs. */
    static final float SEARCH_DELAY = .15f;
    /** How long Add reads "Added ✓". */
    private static final float ADDED_FOR = 1.2f;
    /** How long the Filters drawer takes to slide open or shut (motion's medium). */
    private static final float SLIDE_SECONDS = DesignTokens.shipped().duration("medium") / 1000f;

    /** What the Requisition view asks of the screen and window. */
    interface Actions {
        /** Runs {@code query} away from the GPU thread and hands its result back on it. */
        void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done);

        /** Adds {@code quantity} copies of {@code unit} for the player {@code ownerId} with {@code skills}. */
        void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills);

        /** Takes the unit {@code id} back out of the game. */
        void remove(int id);

        /** Draws the sprites of {@code units} in the background. */
        void sprites(List<MekSummary> units);

        /** Runs one of the forces' other sources of units: a unit list file or the random army. */
        void source(LobbyForcesPanel.Command command);

        /** Reads {@code unit}'s full record away from the GPU thread and hands it back on it; the newest wins. */
        default void load(MekSummary unit, Consumer<RequisitionRecord> done) { }

        /** Reads {@code unit}'s availability by faction and era away from the GPU thread and hands it back on it. */
        default void factions(MekSummary unit, Consumer<RequisitionFactions> done) { }

        /**
         * Draws {@code unit}'s Alpha Strike card {@code width} pixels wide away from the GPU thread and hands it back on
         * it, or null when it could not be drawn; the newest asked for wins.
         */
        default void asCardImage(MekSummary unit, int width, Consumer<BufferedImage> done) {
            done.accept(null);
        }

        /** Whether the dossier's Readout shows the TRO rather than the summary (a client preference). */
        default boolean readoutTro() {
            return false;
        }

        /** Keeps whether the dossier's Readout shows the TRO. */
        default void readoutTro(boolean tro) { }

        /** The kept category tab, weights, tech bases and rules levels ({@code Filters.kept(Category)}, a preference). */
        default String keptFilters() {
            return "";
        }

        /** Keeps the category tab, weights, tech bases and rules levels as {@code Filters.kept(Category)} wrote them. */
        default void keptFilters(String kept) { }

        /** Player {@code id}'s camo or colour, for the swatch beside the recipient; null when unknown. */
        default @Nullable Drawable swatch(int id) {
            return null;
        }
    }

    /** The pause on a unit before its full record is read. */
    static final float LOAD_DELAY = .13f;
    /** The most of a Factions row the ratings take, so the name keeps the rest. */
    private static final float RATINGS_SHARE = .45f;
    /** The Damage tab's curves: maximum, sustained over the turns, and expected. */
    private static final String MAXIMUM_ROLE = "text.muted";
    private static final String SUSTAINED_ROLE = "status.warning";
    private static final String EXPECTED_ROLE = "accent.primary";

    private final UiKit kit;
    private final Actions actions;
    private final Function<String, Drawable> sprites;
    private final boolean motion;
    private final Table body = new Table() {
        @Override
        public void act(float delta) {
            super.act(delta);
            tick(delta);
        }
    };
    private final UiTabs tabs;
    private final UiTextField search;
    private final List<UiFilterChip> weightChips = new ArrayList<>();
    private final UiButton filtersButton;
    private final Container<Actor> drawerHolder = new Container<>() {
        @Override
        public float getPrefHeight() {
            return slid(super.getPrefHeight());
        }

        @Override
        public float getMinHeight() {
            return slid(super.getMinHeight());
        }
    };
    private final Label count;
    private final UiSegmented valueToggle;
    private final UiVirtualList<RequisitionRow> list;
    /** The widths of the results' columns ({@link RequisitionRow.Column}) and of their value, the same in every row. */
    private final float[] columnWidths;
    private final float valueWidth;
    /** The columns' names over the results, laid out as their rows are. */
    private final ColumnsRow resultsHeader;
    private final ScrollPane results;
    private final Label resultsNote;
    private final Table dossier = new Table();
    private final Split split;
    /** Holds the results and the dossier, or the advanced search in their place. */
    private final Container<Actor> centre = new Container<>();
    private @Nullable LobbyAdvancedSearchView advanced;
    private boolean advancedOpen;
    private @Nullable UiButton advancedButton;
    private final Table ownerGroup = new Table();
    private UiSelect owner;
    private final Image ownerSwatch = new Image((Drawable) null, Scaling.fill);
    private final UiSegmented skillsMode;
    private final UiStepper gunnery;
    private final UiStepper piloting;
    /** The strip's owner, skills and, when set, the gunnery and piloting steppers, wrapping as one row. */
    private final HorizontalGroup stripControls = new HorizontalGroup();
    private final Table fixedSkills = new Table();
    private final UiStepper quantity;
    private final UiButton add;
    private final Label totals;
    private final HorizontalGroup addedChips = new HorizontalGroup();

    private LobbyRequisition requisition = LobbyRequisition.EMPTY;
    private LobbyForces forces = LobbyForces.EMPTY;
    private Category category = Category.ALL;
    private Filters filters = Filters.NONE;
    private RequisitionSkills skills = RequisitionSkills.initial(false);
    private boolean skillsChosen;
    private RequisitionSearch.Query asked;
    private RequisitionSearch.Result result;
    private List<String> sources = List.of();
    private boolean drawerOpen;
    /** The drawer's slide: the height it set out from, its seconds so far (negative: none) and whether it shuts. */
    private float slideFrom;
    private float slideAt = -1;
    private boolean slideCloses;
    private boolean dossierOpen;
    private @Nullable MekSummary picked;
    private boolean showPv;
    private RequisitionVisit visit;
    private List<LobbyUnit> added = List.of();
    private int ownerId = -1;
    private float searchDue = -1;
    private float addedShown = -1;
    private float clock;
    private long openedAt;
    private boolean pickFirst;
    private final Set<MekSummary> spriteWanted = new LinkedHashSet<>();
    private final Set<String> spriteAsked = new java.util.HashSet<>();
    private final Label dossierName;
    private final Label dossierDetails;
    private final Label dossierValueLabel;
    private final Label dossierValue;
    private final UiTabs dossierTabs;
    private final Container<Actor> tabBody = new Container<>();
    private final UiSegmented readoutSwitch;
    private final Map<RequisitionDossier.Tab, Built> built = new EnumMap<>(RequisitionDossier.Tab.class);
    /** The AS card tab's picture, while one is built. */
    private @Nullable AsCardView cardView;
    private RequisitionDossier.Tab tab = RequisitionDossier.Tab.OVERVIEW;
    private boolean readoutTro;
    private @Nullable RequisitionDossier shown;
    private @Nullable RequisitionRecord record;
    private @Nullable RequisitionFactions factions;
    private boolean factionsAsked;
    private TextTooltip nameTip;
    private float recordDue = -1;

    LobbyRequisitionPanel(UiKit kit, Actions actions, Function<String, Drawable> sprites, boolean motion) {
        this.kit = kit;
        this.actions = actions;
        this.sprites = sprites;
        this.motion = motion;
        body.top();
        body.setName("lobby-requisition");

        String[] categories = new String[Category.values().length];
        for (Category each : Category.values()) {
            categories[each.ordinal()] = Messages.getString(each.messageKey());
        }
        tabs = kit.tokenTabs(true, categories);
        UiKit.onChange(tabs, () -> category(Category.values()[tabs.getSelectedIndex()]));
        body.add(kit.scrollStrip(tabs)).growX().minWidth(0).pad(0, space("md"), space("sm"), space("md")).row();

        UiKit.TokenSearch searchBox = kit.tokenSearch(Messages.getString("Lobby.requisition.search"), true);
        search = searchBox.field;
        search.setFocusTraversal(false);
        UiKit.onChange(search, () -> searchDue = clock + SEARCH_DELAY);
        search.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.DOWN) {
                    enterResults();
                    return true;
                }
                return false;
            }
        });
        Table weights = new Table();
        for (Weight weight : Weight.values()) {
            UiFilterChip chip = kit.filterChip(Messages.getString(weight.messageKey()));
            UiKit.onChange(chip, this::weightsChanged);
            weightChips.add(chip);
            weights.add(chip).padLeft(space("xs"));
        }
        UiFilterChip.sameWidth(weightChips);
        filtersButton = kit.button("button-toggle" + UiTheme.COMPACT, "tune",
              Messages.getString("Lobby.requisition.filters"), null);
        filtersButton.setProgrammaticChangeEvents(false);
        kit.tip(filtersButton, true).getActor().setText(Messages.getString("Lobby.requisition.filtersTip"));
        // A UiButton never toggles itself (isChecked() is the shown state), so the click flips the drawer's own state
        UiKit.onChange(filtersButton, () -> drawer(!drawerOpen));
        Table toolbar = new Table();
        toolbar.add(searchBox).growX().minWidth(0);
        toolbar.add(weights).padLeft(space("sm"));
        toolbar.add(filtersButton).padLeft(space("sm"));
        body.add(toolbar).growX().minWidth(0).pad(0, space("md"), 0, space("md")).row();
        body.add(drawerHolder).growX().minWidth(0).pad(space("sm"), space("md"), 0, space("md")).row();
        // The drawer keeps its own height and the holder clips it, so it slides rather than squashes
        drawerHolder.fillX().top().minHeight(Value.prefHeight);
        drawerHolder.setClip(true);

        count = kit.typeLabel("", "small", "text.secondary");
        valueToggle = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.bv"),
              Messages.getString("Lobby.requisition.pv"));
        kit.tip(valueToggle, true).getActor().setText(Messages.getString("Lobby.requisition.valueTip"));
        UiKit.onChange(valueToggle, this::valueToggled);
        Table status = new Table();
        status.add(count).left().growX().minWidth(0);
        status.add(valueToggle).right();
        body.add(status).growX().pad(space("sm"), space("md"), space("xs"), space("md")).row();

        columnWidths = columnWidths();
        valueWidth = valueWidth();
        float rowHeight = size("control.lg");
        list = new UiVirtualList<>(kit, rowHeight, ResultRow::new, (row, item) -> ((ResultRow) row).bind(item));
        list.setName("lobby-requisition-results");
        list.onSelect((row, index) -> pick(row.unit(), true));
        list.onActivate((row, index) -> {
            pick(row.unit(), true);
            add();
        });
        list.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.RIGHT && picked != null) {
                    openDossier(true);
                    return true;
                }
                if (keycode == Input.Keys.LEFT && dossierOpen) {
                    openDossier(false);
                    return true;
                }
                return false;
            }
        });
        results = kit.scrollList(list, true, false);
        results.setFadeScrollBars(false);
        resultsNote = kit.typeLabel(Messages.getString("Lobby.requisition.loading"), "body", "text.muted");
        resultsNote.setAlignment(Align.center);
        resultsNote.setWrap(true);
        Stack resultsStack = new Stack();
        resultsStack.add(results);
        Container<Label> note = new Container<>(resultsNote);
        note.top().fillX().padTop(space("xl"));
        resultsStack.add(note);
        resultsHeader = new ColumnsRow(true);
        resultsHeader.pad(space("xs"), space("md"), space("xs"), space("md"));
        resultsHeader.setName("lobby-requisition-columns");
        Table resultsSide = new Table();
        resultsSide.add(resultsHeader).growX().minWidth(0).row();
        resultsSide.add(resultsStack).grow().minWidth(0).minHeight(0);
        dossier.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_PANEL));
        dossier.setName("lobby-requisition-dossier");
        dossier.setVisible(false);
        dossierName = kit.roleTypeLabel("title", "", "text.primary");
        dossierDetails = kit.typeLabel("", "small", "text.secondary");
        dossierValueLabel = kit.roleTypeLabel("overline", "", "text.muted");
        dossierValue = kit.typeLabel("", "title", "text.primary");
        String[] tabNames = new String[RequisitionDossier.Tab.values().length];
        for (RequisitionDossier.Tab each : RequisitionDossier.Tab.values()) {
            tabNames[each.ordinal()] = Messages.getString(each.messageKey());
        }
        dossierTabs = kit.tokenTabs(true, tabNames);
        readoutSwitch = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.readout.summary"),
              Messages.getString("Lobby.requisition.readout.tro"));
        readoutSwitch.setName("lobby-requisition-readout-switch");
        readoutTro = actions.readoutTro();
        buildDossier();
        split = new Split(resultsSide, dossier);
        centre.fill();
        centre.setActor(split);
        body.add(centre).grow().minHeight(0).pad(0, space("md"), 0, space("md")).row();

        skillsMode = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.skills.default"),
              Messages.getString("Lobby.requisition.skills.random"),
              Messages.getString("Lobby.requisition.skills.fixed"));
        kit.tip(skillsMode, true).getActor().setText(Messages.getString("Lobby.requisition.skillsTip"));
        UiKit.onChange(skillsMode, () -> {
            skillsChosen = true;
            skills(skills.withMode(RequisitionSkills.Mode.values()[skillsMode.getSelectedIndex()]));
        });
        gunnery = kit.stepper(Messages.getString("Lobby.requisition.gunnery"), RequisitionSkills.DEFAULT_GUNNERY,
              RequisitionSkills.MIN_SKILL, RequisitionSkills.MAX_SKILL);
        kit.tip(gunnery, true).getActor().setText(Messages.getString("Lobby.requisition.gunneryTip"));
        UiKit.onChange(gunnery, () -> skills(skills.withGunnery(gunnery.value())));
        piloting = kit.stepper(Messages.getString("Lobby.requisition.piloting"), RequisitionSkills.DEFAULT_PILOTING,
              RequisitionSkills.MIN_SKILL, RequisitionSkills.MAX_SKILL);
        kit.tip(piloting, true).getActor().setText(Messages.getString("Lobby.requisition.pilotingTip"));
        UiKit.onChange(piloting, () -> skills(skills.withPiloting(piloting.value())));
        quantity = kit.stepper(Messages.getString("Lobby.requisition.quantity"), 1, 1,
              LobbyRequisitionController.MAX_QUANTITY);
        kit.tip(quantity, true).getActor().setText(Messages.getString("Lobby.requisition.quantityTip"));
        UiKit.onChange(quantity, this::refreshAdd);
        add = kit.button("button-primary" + UiTheme.COMPACT, "add", Messages.getString("Lobby.requisition.add"),
              null);
        add.setName("lobby-requisition-add");
        UiKit.onChange(add, this::add);
        totals = kit.typeLabel("", "small", "text.secondary");
        addedChips.space(space("xs")).wrap(false);
        body.add(strip()).growX().pad(space("sm"), space("md"), space("md"), space("md")).row();
        refreshFilters();
        refreshAdd();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private Label caption(String key) {
        return kit.typeLabel(Messages.getString(key), "small", "text.secondary");
    }

    private void valueToggled() {
        showPv = valueToggle.getSelectedIndex() == 1;
        resultsHeader.value.setText(Messages.getString(showPv ? "Lobby.requisition.pv" : "Lobby.requisition.bv"));
        list.rebind();
        showDossier();
    }

    /** The view's body, which the centre shows under its header with Back. */
    Table body() {
        return body;
    }

    // ---- Opening and the lobby ----

    /** Opens the view afresh on the lobby's {@code current} forces: a new visit, the search field ready. */
    void opened(LobbyForces current) {
        openedAt = System.nanoTime();
        forces = current;
        visit = RequisitionVisit.of(current);
        added = List.of();
        filters = filters.withKept(actions.keptFilters(), requisition.rules().levels());
        category = Filters.keptCategory(actions.keptFilters());
        tabs.select(category.ordinal());
        if (filters.type() != null && filters.type().category() != category) {
            filters = filters.withType(null);
        }
        refreshFilters();
        refreshAdded();
        runSearch();
        Stage stage = body.getStage();
        if (stage != null) {
            stage.setKeyboardFocus(search);
        }
    }

    /** Shows the lobby's {@code next} rules and recipients, and the units this visit added among {@code current}. */
    void show(LobbyRequisition next, LobbyForces current) {
        boolean rulesChanged = !next.rules().equals(requisition.rules());
        boolean recipientsChanged = !next.recipients().equals(requisition.recipients());
        requisition = next;
        if (!skillsChosen) {
            skills = RequisitionSkills.initial(next.randomSkills()).withGunnery(skills.gunnery())
                  .withPiloting(skills.piloting());
            skillsMode.select(skills.mode().ordinal());
            showFixedSkills(skills.mode() == RequisitionSkills.Mode.FIXED);
        }
        if (recipientsChanged) {
            refreshOwners();
        } else {
            // A camo drawn since shows on the swatch
            refreshSwatch();
        }
        if (!current.equals(forces)) {
            forces = current;
            if (visit != null) {
                added = visit.added(current);
                refreshAdded();
            }
        }
        if (rulesChanged) {
            filters = filters.withLevels(Set.of());
            refreshFilters();
            if (visit != null) {
                runSearch();
            }
        }
    }

    /** For the developer switch: types {@code text} into the search. */
    void probeSearch(String text) {
        search.setText(text);
        searchDue = clock;
    }

    /** For the developer switch: opens the Filters drawer. */
    void probeFilters() {
        filtersButton.setChecked(true);
        drawer(true);
    }

    /** The Filters button, for the developer switch's clicks. */
    UiButton filtersToggle() {
        return filtersButton;
    }

    /** Whether the Filters drawer is open. */
    boolean drawerOpen() {
        return drawerOpen;
    }

    /** The results list, for the developer switch's pointer. */
    UiVirtualList<RequisitionRow> resultsList() {
        return list;
    }

    /** The results' scroll pane, for the developer switch's pointer. */
    ScrollPane resultsPane() {
        return results;
    }

    /** For the developer switch: logs, in stage units, where the rows and their values end against the scroll bar. */
    void probeMeasure() {
        float listRight = list.localToStageCoordinates(new Vector2(list.getWidth(), 0)).x;
        float paneRight = results.localToStageCoordinates(new Vector2(results.getWidth(), 0)).x;
        float valueRight = 0;
        for (Actor child : list.getChildren()) {
            if (child.isVisible() && child instanceof ResultRow row) {
                valueRight = Math.max(valueRight,
                      row.value.localToStageCoordinates(new Vector2(row.value.getPrefWidth(), 0)).x);
            }
        }
        float strip = results.getScrollBarWidth();
        LOGGER.info("Requisition probe: values end at {}, rows at {}; the bar's strip runs {} to {} and draws its bar"
              + " from {}", valueRight, listRight, paneRight - strip, paneRight,
              paneRight - (space("sm") + 2 * space("xxs")));
    }

    /** For the developer switch: picks the first result once there are results. */
    void probePick() {
        pickFirst = true;
        showResult();
    }

    /** One Escape step: shuts the drawer, then the advanced search, then the dossier; true when one was open. */
    boolean back() {
        if (drawerOpen) {
            filtersButton.setChecked(false);
            drawer(false);
            return true;
        }
        if (advancedOpen) {
            openAdvanced(false);
            return true;
        }
        if (dossierOpen) {
            openDossier(false);
            return true;
        }
        return false;
    }

    /** Draws the rows again, as after the sprite of {@code key} arrived. */
    void spriteArrived(String key) {
        list.rebind();
        if (dossierOpen && picked != null && spriteKey(picked).equals(key)) {
            showDossier();
        }
    }

    /** The sprite of {@code key} was let go, so it is asked for again when its row shows. */
    void spriteForgotten(String key) {
        spriteAsked.remove(key);
    }

    // ---- Searching ----

    private RequisitionSearch.Query query() {
        return new RequisitionSearch.Query(requisition.rules(), category, filters, search.getText());
    }

    private void runSearch() {
        searchDue = -1;
        RequisitionSearch.Query query = query();
        asked = query;
        actions.search(query, this::results);
    }

    /** GPU thread: shows a search's result, unless a newer search was asked for since. */
    private void results(RequisitionSearch.Result found) {
        if (!Objects.equals(found.query(), asked)) {
            return;
        }
        boolean first = result == null;
        result = found;
        if (first || found.loadMillis() > 0) {
            LOGGER.info("Requisition: the catalogue of {} units was read in {} ms; the view opened in {} ms",
                  found.total(), found.loadMillis(), (System.nanoTime() - openedAt) / 1_000_000);
        }
        LOGGER.info("Requisition: {} of {} units found in {} ms for \"{}\"", found.rows().size(), found.total(),
              found.millis(), found.query().search());
        if (!found.sources().equals(sources)) {
            sources = found.sources();
            if (drawerOpen) {
                fillDrawer();
            }
        }
        showResult();
    }

    private void showResult() {
        if (result == null) {
            return;
        }
        list.setItems(result.rows());
        results.setScrollY(0);
        results.layout();
        boolean none = result.rows().isEmpty();
        resultsNote.setText(Messages.getString(result.total() == 0 ? "Lobby.requisition.loading"
              : "Lobby.requisition.none"));
        resultsNote.setVisible(none);
        count.setText(Messages.getString("Lobby.requisition.count",
              NumberFormat.getIntegerInstance().format(result.rows().size())));
        if (advanced != null) {
            advanced.matches(result.rows().size());
        }
        if (picked != null) {
            int index = indexOf(picked);
            if (index >= 0) {
                list.select(index, false);
            }
        }
        if (pickFirst && !none) {
            pickFirst = false;
            list.select(0, true);
        }
    }

    private int indexOf(MekSummary unit) {
        List<RequisitionRow> rows = list.items();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).unit() == unit) {
                return i;
            }
        }
        return -1;
    }

    private void enterResults() {
        Stage stage = body.getStage();
        if (stage == null || list.items().isEmpty()) {
            return;
        }
        stage.setKeyboardFocus(list);
        if (list.selectedIndex() < 0) {
            list.select(0, true);
        }
    }

    private void tick(float delta) {
        clock += delta;
        slide(delta);
        if (searchDue >= 0 && clock >= searchDue) {
            runSearch();
        }
        if (addedShown >= 0 && clock >= addedShown) {
            addedShown = -1;
            refreshAdd();
        }
        if (recordDue >= 0 && clock >= recordDue) {
            loadRecord();
        }
        for (RequisitionRow row : list.inView()) {
            String key = spriteKey(row.unit());
            if (sprites.apply(key) == null && spriteAsked.add(key)) {
                spriteWanted.add(row.unit());
            }
        }
        if (!spriteWanted.isEmpty()) {
            actions.sprites(List.copyOf(spriteWanted));
            spriteWanted.clear();
        }
    }

    /** The key of a catalogue unit's sprite. */
    static String spriteKey(MekSummary unit) {
        return unit.getName();
    }

    // ---- Filters ----

    private void category(Category next) {
        category = next;
        if (filters.type() != null && filters.type().category() != next) {
            filters = filters.withType(null);
        }
        actions.keptFilters(filters.kept(next));
        refreshFilters();
        runSearch();
    }

    private void weightsChanged() {
        Set<Weight> lit = EnumSet.noneOf(Weight.class);
        for (int i = 0; i < weightChips.size(); i++) {
            if (weightChips.get(i).isChecked()) {
                lit.add(Weight.values()[i]);
            }
        }
        setFilters(filters.withWeights(lit));
    }

    private void setFilters(Filters next) {
        filters = next;
        actions.keptFilters(next.kept(category));
        refreshFilters();
        runSearch();
    }

    private void drawer(boolean open) {
        boolean changed = open != drawerOpen;
        float from = drawerHolder.getHeight();
        drawerOpen = open;
        if (changed && motion) {
            slideFrom = from;
            slideAt = 0;
            slideCloses = !open;
        }
        refreshFilters();
    }

    /**
     * The drawer's height {@code elapsed} seconds into a slide of {@code duration} from {@code from} to {@code to}:
     * quick at first, then easing in; {@code to} once the slide is over.
     */
    static float slideHeight(float from, float to, float elapsed, float duration) {
        if (duration <= 0 || elapsed >= duration) {
            return to;
        }
        return from + (to - from) * Interpolation.pow2Out.apply(Math.max(0, elapsed) / duration);
    }

    /** The holder's height for the drawer's own {@code natural} one, while it slides open or shut. */
    private float slid(float natural) {
        return slideAt < 0 ? natural : slideHeight(slideFrom, slideCloses ? 0 : natural, slideAt, SLIDE_SECONDS);
    }

    private void slide(float delta) {
        if (slideAt < 0) {
            return;
        }
        slideAt += delta;
        if (slideAt >= SLIDE_SECONDS) {
            slideAt = -1;
            if (slideCloses) {
                slideCloses = false;
                refreshFilters();
            }
        }
        drawerHolder.invalidateHierarchy();
    }

    /** Shows the filters: the weight chips, the button's count, and the drawer or the tags of those at work. */
    private void refreshFilters() {
        boolean weighs = Filters.weighs(category);
        for (int i = 0; i < weightChips.size(); i++) {
            UiFilterChip chip = weightChips.get(i);
            chip.setChecked(filters.weights().contains(Weight.values()[i]));
            chip.setDisabled(!weighs);
            chip.getColor().a = weighs ? 1 : .4f;
        }
        int at = filters.drawerCount();
        if (advancedOpen && advanced != null) {
            advanced.sync(filters.advanced());
        }
        String label = Messages.getString("Lobby.requisition.filters");
        filtersButton.setText(kit.cased("button", (at > 0) ? label + " · " + at : label));
        filtersButton.setChecked(drawerOpen);
        if (drawerOpen) {
            fillDrawer();
        } else if (slideCloses) {
            // The drawer stays in the holder while it slides shut; the slide's end shows the tags
            return;
        } else if (at > 0) {
            drawerHolder.setActor(tags());
        } else {
            drawerHolder.setActor(null);
        }
    }

    private void fillDrawer() {
        Table drawer = new Table();
        drawer.setName("lobby-requisition-drawer");
        drawer.top().left();
        drawer.defaults().left();
        List<UiFilterChip> chips = new ArrayList<>();

        List<ExactType> types = ExactType.of(category);
        if (types.size() > 1) {
            HorizontalGroup group = chipGroup();
            for (ExactType type : types) {
                UiFilterChip chip = chip(Messages.getString(type.messageKey()), filters.type() == type, chips);
                UiKit.onChange(chip, () -> setFilters(filters.withType(chip.isChecked() ? type : null)));
                group.addActor(chip);
            }
            drawerRow(drawer, "Lobby.requisition.drawer.type", group);
        }

        HorizontalGroup bases = chipGroup();
        for (TechBase base : TechBase.values()) {
            UiFilterChip chip = chip(Messages.getString(base.messageKey()), filters.bases().contains(base), chips);
            UiKit.onChange(chip, () -> setFilters(filters.withBases(toggled(filters.bases(), base,
                  chip.isChecked()))));
            bases.addActor(chip);
        }
        drawerRow(drawer, "Lobby.requisition.drawer.tech", bases);

        HorizontalGroup levels = chipGroup();
        for (int level : requisition.rules().levels()) {
            UiFilterChip chip = chip(TechConstants.getSimpleLevelName(level), filters.levels().contains(level), chips);
            UiKit.onChange(chip, () -> setFilters(filters.withLevels(toggled(filters.levels(), level,
                  chip.isChecked()))));
            levels.addActor(chip);
        }
        drawerRow(drawer, "Lobby.requisition.drawer.level", levels);

        if (!requisition.rules().canonOnly()) {
            drawerRow(drawer, "Lobby.requisition.drawer.canon", either(filters.canon(),
                  "Lobby.requisition.canon.yes", "Lobby.requisition.canon.no", chips,
                  next -> setFilters(filters.withCanon(next))));
        }
        drawerRow(drawer, "Lobby.requisition.drawer.recordSheet", either(filters.recordSheet(),
              "Lobby.requisition.recordSheet.yes", "Lobby.requisition.recordSheet.no", chips,
              next -> setFilters(filters.withRecordSheet(next))));

        List<UnitRole> roles = new ArrayList<>();
        for (UnitRole role : UnitRole.values()) {
            if (role.hasRole()) {
                roles.add(role);
            }
        }
        List<String> roleNames = new ArrayList<>();
        roleNames.add(Messages.getString("Lobby.requisition.any"));
        roles.forEach(role -> roleNames.add(role.toString()));
        UiSelect roleSelect = kit.tokenSelect(true, roleNames.toArray(String[]::new));
        UnitRole role = filters.roles().isEmpty() ? null : filters.roles().iterator().next();
        roleSelect.select(role == null ? 0 : roles.indexOf(role) + 1);
        UiKit.onChange(roleSelect, () -> {
            int index = roleSelect.getSelectedIndex();
            setFilters(filters.withRoles(index <= 0 ? Set.of() : Set.of(roles.get(index - 1))));
        });

        List<RequisitionCatalog.YearSpan> eras = requisition.eras();
        List<String> eraNames = new ArrayList<>();
        eraNames.add(Messages.getString("Lobby.requisition.any"));
        eras.forEach(era -> eraNames.add(era.name()));
        UiSelect eraSelect = kit.tokenSelect(true, eraNames.toArray(String[]::new));
        eraSelect.select(filters.era() == null ? 0 : Math.max(0, eras.indexOf(filters.era()) + 1));
        UiKit.onChange(eraSelect, () -> {
            int index = eraSelect.getSelectedIndex();
            setFilters(filters.withEra(index <= 0 ? null : eras.get(index - 1)));
        });

        List<String> sourceNames = new ArrayList<>();
        sourceNames.add(Messages.getString("Lobby.requisition.any"));
        sourceNames.addAll(sources);
        UiSelect sourceSelect = kit.tokenSelect(true, sourceNames.toArray(String[]::new));
        sourceSelect.select(filters.source() == null ? 0 : Math.max(0, sources.indexOf(filters.source()) + 1));
        UiKit.onChange(sourceSelect, () -> {
            int index = sourceSelect.getSelectedIndex();
            setFilters(filters.withSource(index <= 0 ? null : sources.get(index - 1)));
        });
        // Three equal shares of the drawer's width, so the selects' right edges line up with the rows above
        Table selects = new Table();
        selects.defaults().growX().uniformX().minWidth(0);
        selects.add(labelled("Lobby.requisition.drawer.role", roleSelect));
        selects.add(labelled("Lobby.requisition.drawer.era", eraSelect)).padLeft(space("lg"));
        selects.add(labelled("Lobby.requisition.drawer.source", sourceSelect)).padLeft(space("lg"));
        drawer.add(selects).growX().minWidth(0).colspan(2).padTop(space("sm")).row();

        UiButton reset = kit.button("button-quiet" + UiTheme.COMPACT, "undo",
              Messages.getString("Lobby.requisition.reset"), null);
        reset.setDisabled(filters.drawerCount() == 0);
        UiKit.onChange(reset, () -> setFilters(filters.withoutDrawer()));
        int criteria = filters.advanced().count();
        String open = Messages.getString("Lobby.requisition.advanced.open");
        advancedButton = kit.button("button-quiet" + UiTheme.COMPACT, "tune",
              criteria > 0 ? open + " \u00b7 " + criteria : open, null);
        advancedButton.setName("lobby-requisition-advanced-open");
        kit.tip(advancedButton, true).getActor().setText(Messages.getString("Lobby.requisition.advanced.openTip"));
        UiKit.onChange(advancedButton, () -> openAdvanced(true));
        Table buttons = new Table();
        buttons.add(advancedButton);
        buttons.add(reset).padLeft(space("sm"));
        drawer.add(buttons).colspan(2).left().padTop(space("sm"));
        UiFilterChip.sameWidth(chips);
        drawerHolder.setActor(drawer);
    }

    private Table labelled(String key, UiSelect select) {
        Table cell = new Table();
        cell.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).padRight(space("sm"));
        cell.add(select).growX().minWidth(0);
        return cell;
    }

    private HorizontalGroup chipGroup() {
        HorizontalGroup group = new HorizontalGroup();
        group.wrap(true).space(space("sm")).wrapSpace(space("xs")).rowLeft();
        return group;
    }

    private void drawerRow(Table drawer, String key, Actor content) {
        drawer.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).padRight(space("md"))
              .padTop(space("xs")).top().minWidth(size("control.lg") * 2);
        drawer.add(content).growX().minWidth(0).padTop(space("xs")).row();
    }

    private UiFilterChip chip(String text, boolean on, List<UiFilterChip> group) {
        UiFilterChip chip = kit.filterChip(text);
        chip.setChecked(on);
        group.add(chip);
        return chip;
    }

    private HorizontalGroup either(Either value, String yesKey, String noKey, List<UiFilterChip> group,
          Consumer<Either> changed) {
        HorizontalGroup row = chipGroup();
        UiFilterChip yes = chip(Messages.getString(yesKey), value == Either.YES, group);
        UiFilterChip no = chip(Messages.getString(noKey), value == Either.NO, group);
        UiKit.onChange(yes, () -> changed.accept(yes.isChecked() ? Either.YES : Either.ANY));
        UiKit.onChange(no, () -> changed.accept(no.isChecked() ? Either.NO : Either.ANY));
        row.addActor(yes);
        row.addActor(no);
        return row;
    }

    private static <T> Set<T> toggled(Set<T> set, T value, boolean on) {
        Set<T> next = new LinkedHashSet<>(set);
        if (on) {
            next.add(value);
        } else {
            next.remove(value);
        }
        return next;
    }

    /** The drawer's filters at work as tags, each removed by a click, and Clear filters. */
    private Actor tags() {
        HorizontalGroup row = chipGroup();
        row.setName("lobby-requisition-tags");
        if (filters.type() != null) {
            row.addActor(tag(Messages.getString(filters.type().messageKey()), filters.withType(null)));
        }
        for (TechBase base : filters.bases()) {
            row.addActor(tag(Messages.getString(base.messageKey()), filters.withBases(toggled(filters.bases(), base,
                  false))));
        }
        for (int level : filters.levels()) {
            row.addActor(tag(TechConstants.getSimpleLevelName(level), filters.withLevels(toggled(filters.levels(),
                  level, false))));
        }
        for (UnitRole role : filters.roles()) {
            row.addActor(tag(role.toString(), filters.withRoles(Set.of())));
        }
        if (filters.canon() != Either.ANY) {
            row.addActor(tag(Messages.getString(filters.canon() == Either.YES ? "Lobby.requisition.canon.yes"
                  : "Lobby.requisition.canon.no"), filters.withCanon(Either.ANY)));
        }
        if (filters.recordSheet() != Either.ANY) {
            row.addActor(tag(Messages.getString(filters.recordSheet() == Either.YES
                  ? "Lobby.requisition.recordSheet.yes" : "Lobby.requisition.recordSheet.no"),
                  filters.withRecordSheet(Either.ANY)));
        }
        if (filters.era() != null) {
            row.addActor(tag(filters.era().name(), filters.withEra(null)));
        }
        if (filters.source() != null) {
            row.addActor(tag(filters.source(), filters.withSource(null)));
        }
        RequisitionAdvancedSearch criteria = filters.advanced();
        for (RequisitionAdvancedSearch.Field field : criteria.values().keySet()) {
            row.addActor(tag(criteria.describe(field), filters.withAdvanced(criteria.without(field))));
        }
        UiButton clear = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.requisition.clear"), null);
        UiKit.onChange(clear, () -> setFilters(filters.withoutDrawer()));
        row.addActor(clear);
        return row;
    }

    /** Shows the advanced search in the results' place, or the results again; opening it shuts the drawer. */
    void openAdvanced(boolean open) {
        if (open == advancedOpen) {
            return;
        }
        advancedOpen = open;
        if (open) {
            if (advanced == null) {
                advanced = new LobbyAdvancedSearchView(kit, new LobbyAdvancedSearchView.Host() {
                    @Override
                    public RequisitionAdvancedSearch criteria() {
                        return filters.advanced();
                    }

                    @Override
                    public void criteria(RequisitionAdvancedSearch next) {
                        setFilters(filters.withAdvanced(next));
                    }

                    @Override
                    public void done() {
                        openAdvanced(false);
                    }
                });
            }
            advanced.sync(filters.advanced());
            if (result != null) {
                advanced.matches(result.rows().size());
            }
            centre.setActor(advanced.actor());
            if (drawerOpen) {
                drawer(false);
            }
        } else {
            centre.setActor(split);
            Stage stage = body.getStage();
            if (stage != null) {
                stage.setKeyboardFocus(search);
            }
        }
    }

    /** The advanced search, once it has been opened; a probe and a test reach its controls. */
    @Nullable LobbyAdvancedSearchView advancedView() {
        return advanced;
    }

    boolean advancedOpen() {
        return advancedOpen;
    }

    /** The drawer's Advanced search button, while the drawer is open. */
    @Nullable UiButton drawerAdvancedButton() {
        return drawerOpen ? advancedButton : null;
    }

    /** The advanced criteria at work, for the probe's log. */
    String probeCriteria() {
        return filters.advanced().values().keySet().stream().map(filters.advanced()::describe).toList().toString();
    }

    private Button tag(String text, Filters without) {
        Button tag = kit.actingChip(text, UiKit.Tone.NORMAL);
        float icon = size("icon.sm");
        tag.add(kit.icon("close", icon, UiTheme.tokenColor("text.secondary"))).size(icon).padLeft(space("xs"));
        kit.tip(tag, true).getActor().setText(Messages.getString("Lobby.requisition.removeFilter"));
        UiKit.onChange(tag, () -> setFilters(without));
        return tag;
    }

    // ---- The dossier ----

    private void pick(MekSummary unit, boolean open) {
        if (unit != picked) {
            picked = unit;
            record = null;
            factions = null;
            factionsAsked = false;
            forgetBuilt();
            recordDue = clock + LOAD_DELAY;
        }
        refreshAdd();
        if (open) {
            openDossier(true);
        } else {
            showDossier();
        }
    }

    private void openDossier(boolean open) {
        boolean opening = open && !dossierOpen;
        dossierOpen = open && picked != null;
        showDossier();
        split.opened(opening);
    }

    /** The dossier's fixed header and its tabs, built once; the unit's values and the tab's body come later. */
    private void buildDossier() {
        dossier.top().pad(space("md"));
        Table head = new Table();
        Table names = new Table();
        names.left().defaults().left();
        dossierName.setEllipsis(true);
        nameTip = kit.tip(dossierName, true);
        names.add(dossierName).growX().minWidth(0).row();
        dossierDetails.setEllipsis(true);
        names.add(dossierDetails).growX().minWidth(0).padTop(space("xxs"));
        head.add(names).growX().minWidth(0);
        Table value = new Table();
        value.right().defaults().right();
        value.add(dossierValueLabel).row();
        value.add(dossierValue);
        head.add(value).padLeft(space("md"));
        UiButton close = kit.button("button-icon" + UiTheme.COMPACT, "close", null, null);
        kit.tip(close, true).getActor().setText(Messages.getString("Lobby.requisition.closeDossier"));
        UiKit.onChange(close, () -> openDossier(false));
        head.add(close).top().padLeft(space("xs"));
        dossier.add(head).growX().minWidth(0).minHeight(Value.prefHeight).row();

        dossierTabs.setName("lobby-requisition-dossier-tabs");
        UiKit.onChange(dossierTabs, () -> showTab(RequisitionDossier.Tab.values()[dossierTabs.getSelectedIndex()]));
        dossier.add(kit.scrollStrip(dossierTabs)).growX().minWidth(0).minHeight(dossierTabs.getPrefHeight())
              .padTop(space("sm")).row();
        tabBody.fill().top();
        tabBody.setName("lobby-requisition-dossier-body");
        dossier.add(tabBody).grow().minHeight(0).padTop(space("md"));

        readoutSwitch.select(readoutTro ? 1 : 0);
        kit.tip(readoutSwitch, true).getActor().setText(Messages.getString("Lobby.requisition.readout.tip"));
        UiKit.onChange(readoutSwitch, () -> {
            readoutTro = readoutSwitch.getSelectedIndex() == 1;
            actions.readoutTro(readoutTro);
            showTab(tab);
        });
    }

    /** Shows the picked unit in the dossier's header and the picked tab's body, built only when it changed. */
    private void showDossier() {
        dossier.setVisible(dossierOpen && picked != null);
        if (!dossier.isVisible()) {
            return;
        }
        shown = RequisitionDossier.of(picked);
        dossierName.setText(shown.name());
        nameTip.getActor().setText(shown.name());
        dossierDetails.setText(shown.details());
        dossierValueLabel.setText(kit.cased("overline", Messages.getString(showPv ? "Lobby.requisition.pv"
              : "Lobby.requisition.bv")));
        dossierValue.setText(showPv ? String.valueOf(shown.pv())
              : NumberFormat.getIntegerInstance().format(skills.bv(shown.bv())));
        showTab(tab);
    }

    /** Shows {@code next}'s body, building it only when what it shows has changed since it was last built. */
    private void showTab(RequisitionDossier.Tab next) {
        tab = next;
        if (dossierTabs.getSelectedIndex() != next.ordinal()) {
            dossierTabs.select(next.ordinal());
        }
        if (!dossier.isVisible() || picked == null || shown == null) {
            return;
        }
        if (next == RequisitionDossier.Tab.FACTIONS) {
            askFactions();
        }
        // The AS card is drawn from the catalogue alone, so a record read later does not draw it again
        List<Object> key = Arrays.asList(picked, (next == RequisitionDossier.Tab.AS_CARD) ? null : record,
              switch (next) {
                  case OVERVIEW -> sprites.apply(spriteKey(picked)) != null;
                  case READOUT -> readoutTro;
                  case FACTIONS -> factions;
                  case DAMAGE -> List.of(gunnery(), sprites.apply(spriteKey(picked)) != null);
                  case AS_CARD -> null;
              });
        Built made = built.get(next);
        if (made == null || !made.key().equals(key)) {
            made = new Built(key, switch (next) {
                case OVERVIEW -> overview();
                case READOUT -> readout();
                case AS_CARD -> scrolled(asCardTab());
                case FACTIONS -> factionsTab();
                case DAMAGE -> damage();
            });
            built.put(next, made);
        }
        if (tabBody.getActor() != made.actor()) {
            tabBody.setActor(made.actor());
        }
    }

    /** Drops the built tab bodies, freeing the AS card's picture. */
    private void forgetBuilt() {
        built.clear();
        if (cardView != null) {
            cardView.dispose();
            cardView = null;
        }
    }

    /** Frees what the view holds on the GPU: the AS card's picture. */
    void dispose() {
        forgetBuilt();
    }

    /** One tab's body and what it was built from. */
    private record Built(List<Object> key, Actor actor) { }

    /** The gunnery of the Damage tab: the adding strip's when it sets skills, else 4. */
    private int gunnery() {
        return skills.mode() == RequisitionSkills.Mode.FIXED ? skills.gunnery() : RequisitionSkills.DEFAULT_GUNNERY;
    }

    /** GPU thread: once the Commander has stopped on a unit for a moment, loads its full record. */
    private void loadRecord() {
        recordDue = -1;
        MekSummary unit = picked;
        if (unit == null) {
            return;
        }
        actions.load(unit, loaded -> {
            if (loaded.unit() == picked) {
                record = loaded;
                showDossier();
            }
        });
        if (tab == RequisitionDossier.Tab.FACTIONS) {
            askFactions();
        }
    }

    /** Asks for the picked unit's availability once the Commander has stopped on it. */
    private void askFactions() {
        MekSummary unit = picked;
        if (factionsAsked || unit == null || recordDue >= 0) {
            return;
        }
        factionsAsked = true;
        actions.factions(unit, read -> {
            if (unit == picked) {
                factions = read;
                showTab(tab);
            }
        });
    }

    private ScrollPane scrolled(Actor content) {
        ScrollPane scroll = kit.scrollList(content, true, false);
        scroll.setFadeScrollBars(false);
        return scroll;
    }

    private Label note(String key) {
        Label note = kit.typeLabel(Messages.getString(key), "body", "text.muted");
        note.setWrap(true);
        return note;
    }

    private Actor noteTab(String key) {
        Table table = new Table();
        table.top().left();
        table.add(note(key)).growX().minWidth(0).left();
        return table;
    }

    /** Overview: the sprite beside the stat strip, then role, rules level, introduced, source and cost. */
    private Actor overview() {
        Table content = new Table();
        content.top().left();
        Table top = new Table();
        top.top().left();
        float spriteSize = size("control.lg") * 2;
        Drawable drawable = sprites.apply(spriteKey(picked));
        Actor sprite = (drawable == null) ? kit.icon("unit", size("icon.lg"), UiTheme.tokenColor("text.muted"))
              : new Image(drawable, Scaling.fit);
        top.add(sprite).size(spriteSize).top().padRight(space("md"));
        RequisitionDossier.Stats stats = shown.stats();
        if (record != null && record.entity() != null) {
            stats = stats.with(record.entity());
        }
        top.add(statStrip(stats)).growX().minWidth(0).top();
        content.add(top).growX().minWidth(0).row();

        Table facts = new Table();
        facts.left().defaults().left();
        for (RequisitionDossier.Fact fact : shown.facts()) {
            facts.add(kit.typeLabel(fact.label(), "body", "text.secondary")).padRight(space("md")).top()
                  .padTop(space("xxs"));
            Label factValue = kit.typeLabel(fact.value(), "body", "text.primary");
            factValue.setWrap(true);
            facts.add(factValue).growX().minWidth(0).padTop(space("xxs")).row();
        }
        content.add(facts).growX().minWidth(0).padTop(space("lg")).row();
        return scrolled(content);
    }

    /**
     * The stat strip, as the force cards' (gap analysis 7.3): move, heat, armor and structure as one bar with their
     * numbers, then the weapons. A Mek's heat shows a dash until its record has loaded, in the same room.
     */
    private Table statStrip(RequisitionDossier.Stats stats) {
        Table strip = new Table();
        strip.top().left();
        HorizontalGroup numbers = new HorizontalGroup();
        numbers.wrap(true).space(space("lg")).wrapSpace(space("sm")).rowLeft();
        numbers.addActor(stat("Lobby.requisition.stat.move", stats.move()));
        if (stats.heat() != RequisitionDossier.NO_HEAT) {
            numbers.addActor(stat("Lobby.requisition.stat.heat", stats.heat() == RequisitionDossier.HEAT_PENDING
                  ? "\u2013" : String.valueOf(stats.heat())));
        }
        Table armor = stat("Lobby.requisition.stat.armor", Messages.getString("Lobby.requisition.stat.armorValue",
              stats.armor(), stats.structure()));
        armor.row();
        armor.add(armorBar(stats)).width(size("control.lg") * 2).height(space("xs")).padTop(space("xxs"));
        kit.tip(armor, true).getActor().setText(Messages.getString("Lobby.requisition.stat.armorTip", stats.armor(),
              stats.structure()));
        numbers.addActor(armor);
        strip.add(numbers).growX().minWidth(0).left().row();
        Table weapons = new Table();
        weapons.left().defaults().left();
        weapons.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.stat.weapons"),
              "text.muted")).row();
        Label list = kit.typeLabel(stats.weapons().isEmpty() ? Messages.getString("Lobby.requisition.noWeapons")
              : stats.weapons(), "body", stats.weapons().isEmpty() ? "text.muted" : "text.primary");
        list.setWrap(true);
        weapons.add(list).growX().minWidth(0);
        strip.add(weapons).growX().minWidth(0).left().padTop(space("sm"));
        return strip;
    }

    /** Armor beside structure, each its share of the bar, filled as far as it is left. */
    private Actor armorBar(RequisitionDossier.Stats stats) {
        Table bar = new Table();
        int total = stats.armorMax() + stats.structureMax();
        if (total <= 0) {
            bar.add(kit.solid("border.subtle")).grow();
            return bar;
        }
        float armorShare = (float) stats.armorMax() / total;
        bar.add(kit.solid(stats.armor() < stats.armorMax() ? "status.warning" : "text.secondary")).growY()
              .width(Value.percentWidth(armorShare, bar));
        bar.add(kit.solid("text.muted")).grow().padLeft(space("xxs"));
        return bar;
    }

    private Table stat(String key, String value) {
        Table stat = new Table();
        stat.left().defaults().left();
        stat.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).row();
        stat.add(kit.typeLabel(value, "body.strong", "text.primary"));
        return stat;
    }

    /** Readout: the Summary or TRO switch, kept as a preference, over the readout's rows. */
    private Actor readout() {
        Table content = new Table();
        content.top().left();
        content.add(readoutSwitch).left().row();
        Actor body;
        if (record == null) {
            body = noteTab("Lobby.requisition.record.loading");
        } else if (record.entity() == null) {
            body = noteTab("Lobby.requisition.record.failed");
        } else {
            body = scrolled(readoutRows(readoutTro ? record.tro() : record.summary()));
        }
        content.add(body).grow().minHeight(0).padTop(space("sm"));
        return content;
    }

    /** The readout's rows, laid out by {@link LobbyReadoutRows}. */
    private Table readoutRows(List<EntityReadout.Row> rows) {
        return LobbyReadoutRows.of(kit, motion, rows);
    }

    private Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    /**
     * AS card: MegaMek's own Alpha Strike card, drawn away from the GPU thread at the pixels it takes; the kit's card
     * of its values shows until it is drawn and stays when it cannot be.
     */
    private Actor asCardTab() {
        if (cardView != null) {
            cardView.dispose();
            cardView = null;
        }
        if (RequisitionAlphaStrike.of(picked) == null) {
            return asCard();
        }
        cardView = new AsCardView(picked, asCard());
        return cardView;
    }

    /**
     * The AS card's picture, filling the tab's width at the card's aspect; it shows {@code fallback} until the
     * picture is drawn and asks for it again, at the new size, whenever its pixel width changes.
     */
    private final class AsCardView extends Container<Actor> {
        private final MekSummary unit;
        private final Actor fallback;
        private @Nullable Texture texture;
        private int drawn;
        private int asked;
        private boolean failed;
        private boolean disposed;

        AsCardView(MekSummary unit, Actor fallback) {
            super(fallback);
            this.unit = unit;
            this.fallback = fallback;
            top().left().fillX();
        }

        @Override
        public float getPrefHeight() {
            if (texture == null) {
                return super.getPrefHeight();
            }
            return getWidth() * texture.getHeight() / texture.getWidth();
        }

        @Override
        public float getPrefWidth() {
            return (texture == null) ? super.getPrefWidth() : 0;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            int wanted = RequisitionCardImage.pixels(getWidth(), UiTheme.pixelScale(batch));
            if (!failed && !disposed && RequisitionCardImage.redraw(drawn, asked, wanted)) {
                asked = wanted;
                actions.asCardImage(unit, wanted, image -> shown(image, wanted));
            }
            super.draw(batch, parentAlpha);
        }

        private void shown(@Nullable BufferedImage image, int width) {
            if (disposed || (asked != width)) {
                return;
            }
            asked = 0;
            if (image == null) {
                failed = true;
                return;
            }
            Texture next = GpuStartScreen.texture(image, false);
            if (texture != null) {
                texture.dispose();
            }
            texture = next;
            drawn = width;
            if (getActor() == fallback) {
                Image picture = new Image(next);
                picture.setScaling(Scaling.fit);
                picture.setAlign(Align.top);
                setActor(picture);
            } else if (getActor() instanceof Image picture) {
                picture.setDrawable(new TextureRegionDrawable(next));
            }
            fill();
            invalidateHierarchy();
        }

        void dispose() {
            disposed = true;
            if (texture != null) {
                texture.dispose();
                texture = null;
            }
        }
    }

    /** AS card: the Alpha Strike card's values on a card of the kit's, read from the catalogue at once. */
    private Actor asCard() {
        RequisitionAlphaStrike card = RequisitionAlphaStrike.of(picked);
        if (card == null) {
            return noteTab("Lobby.requisition.as.none");
        }
        Table face = kit.card();
        face.top().left();
        face.pad(space("md"));
        Table head = new Table();
        Label name = kit.roleTypeLabel("title", card.name(), "text.primary");
        name.setEllipsis(true);
        head.add(name).growX().minWidth(0).left();
        Table pv = new Table();
        pv.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.as.pv"), "text.muted")).right()
              .row();
        pv.add(kit.typeLabel(String.valueOf(card.pv()), "title", "accent.primary")).right();
        head.add(pv).right().padLeft(space("md"));
        face.add(head).growX().minWidth(0).row();
        face.add(kit.typeLabel(Messages.getString("Lobby.requisition.as.skill", card.skill()), "small",
              "text.secondary")).left().row();

        HorizontalGroup values = new HorizontalGroup();
        values.wrap(true).space(space("lg")).wrapSpace(space("sm")).rowLeft();
        values.addActor(stat("Lobby.requisition.as.type", card.type()));
        values.addActor(stat("Lobby.requisition.as.size", String.valueOf(card.size())));
        if (!card.tmm().isEmpty()) {
            values.addActor(stat("Lobby.requisition.as.tmm", card.tmm()));
        }
        values.addActor(stat("Lobby.requisition.as.move", card.move()));
        if (!card.role().isEmpty()) {
            values.addActor(stat("Lobby.requisition.as.role", card.role()));
        }
        face.add(values).growX().minWidth(0).left().padTop(space("md")).row();

        if (!card.damage().isEmpty()) {
            face.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.as.damage"), "text.muted"))
                  .left().padTop(space("md")).row();
            Table damage = new Table();
            damage.left();
            for (RequisitionAlphaStrike.Cell cell : card.damage()) {
                damage.add(asBox(cell.label(), cell.value())).padRight(space("sm"));
            }
            if (!card.overheat().isEmpty()) {
                damage.add(asBox(Messages.getString("Lobby.requisition.as.overheat"), card.overheat()))
                      .padLeft(space("md"));
            }
            face.add(damage).left().padTop(space("xs")).row();
        }

        Table body = new Table();
        body.left().defaults().left();
        body.add(stat("Lobby.requisition.as.armor", String.valueOf(card.armor()))).padRight(space("lg"));
        body.add(stat("Lobby.requisition.as.structure", String.valueOf(card.structure()))).padRight(space("lg"));
        if (!card.threshold().isEmpty()) {
            body.add(stat("Lobby.requisition.as.threshold", card.threshold()));
        }
        face.add(body).left().padTop(space("md")).row();

        if (!card.arcs().isEmpty()) {
            face.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.as.arcs"), "text.muted"))
                  .left().padTop(space("md")).row();
            Table arcs = new Table();
            arcs.left().defaults().left().top();
            for (RequisitionAlphaStrike.Cell arc : card.arcs()) {
                arcs.add(kit.typeLabel(arc.label(), "body", "text.secondary")).padRight(space("md"))
                      .padTop(space("xxs"));
                arcs.add(wrapped(arc.value(), "body", "text.primary")).growX().minWidth(0).padTop(space("xxs"))
                      .row();
            }
            face.add(arcs).growX().minWidth(0).padTop(space("xs")).row();
        }
        if (!card.specials().isEmpty()) {
            face.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.as.specials"),
                  "text.muted")).left().padTop(space("md")).row();
            face.add(wrapped(card.specials(), "body", "text.primary")).growX().minWidth(0).padTop(space("xxs"))
                  .row();
        }
        Table content = new Table();
        content.top().left();
        content.add(face).growX().minWidth(0).maxWidth(size("dialog.sm"));
        content.add().growX();
        return content;
    }

    /** One of the AS card's damage boxes: the range over its value. */
    private Table asBox(String label, String value) {
        Table box = new Table();
        box.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_FRAME));
        box.pad(space("xs"), space("md"), space("xs"), space("md"));
        box.add(kit.roleTypeLabel("overline", label, "text.muted")).row();
        box.add(kit.typeLabel(value, "body.strong", "text.primary"));
        return box;
    }

    /** Factions: the unit's availability by era, each era's factions under it, read away from the GPU thread. */
    private Actor factionsTab() {
        if (factions == null) {
            return noteTab("Lobby.requisition.factions.loading");
        }
        if (factions.factions().isEmpty()) {
            return noteTab("Lobby.requisition.factions.none");
        }
        // The code is a column of its own, as wide as the widest code, at the same x in every era
        float codeWidth = 0;
        for (RequisitionFactions.Faction faction : factions.factions()) {
            codeWidth = Math.max(codeWidth, kit.typeLabel(faction.code(), "body.strong", "text.secondary")
                  .getPrefWidth());
        }
        UiReveal list = new UiReveal(motion);
        for (int i = 0; i < factions.eras().size(); i++) {
            RequisitionFactions.EraColumn era = factions.eras().get(i);
            Table band = new Table();
            band.top().left();
            Table head = new Table();
            head.left();
            head.add(kit.roleTypeLabel("overline", era.name(), "text.secondary")).left().minWidth(0);
            head.add(kit.typeLabel(era.years(), "caption", "text.muted")).left().padLeft(space("sm"));
            head.add().growX();
            band.add(head).growX().minWidth(0).colspan(3).row();
            band.add(kit.solid("border.subtle")).growX().height(size("border")).colspan(3).padTop(space("xxs"))
                  .row();
            for (RequisitionFactions.Faction faction : factions.factions()) {
                List<String> ratings = RequisitionFactions.ratings(faction.cells().get(i));
                if (ratings.isEmpty()) {
                    continue;
                }
                band.add(kit.typeLabel(faction.code(), "body.strong", "text.secondary")).width(codeWidth).left()
                      .top().padRight(space("lg")).padTop(space("xs"));
                Label name = kit.typeLabel(faction.name(), "body", "text.primary");
                name.setEllipsis(true);
                band.add(name).growX().minWidth(0).left().top().padTop(space("xs"));
                ratingChips(band, ratings);
            }
            list.band(band).padTop(i == 0 ? 0 : space("lg"));
        }
        list.setName("lobby-requisition-factions");
        return scrolled(list);
    }

    /**
     * A faction's ratings in one era, as token chips that wrap, right-aligned, within at most {@link #RATINGS_SHARE}
     * of {@code band}'s width, so they never run into the name.
     */
    private void ratingChips(Table band, List<String> ratings) {
        HorizontalGroup chips = new HorizontalGroup();
        chips.wrap().rowRight().right().space(space("xxs")).wrapSpace(space("xxs"));
        float natural = 0;
        for (String rating : ratings) {
            Actor chip = kit.chip(rating, UiKit.Tone.NORMAL, true);
            chips.addActor(chip);
            natural += (chip instanceof Layout layout) ? layout.getPrefWidth() : chip.getWidth();
        }
        float wanted = natural + space("xxs") * (ratings.size() - 1);
        band.add(chips).width(new Value() {
            @Override
            public float get(@Nullable Actor context) {
                return Math.min(wanted, band.getWidth() * RATINGS_SHARE);
            }
        }).right().top().padLeft(space("md")).padTop(space("xs")).row();
    }

    /**
     * Damage, as a targeting display: the curves by range at the strip's gunnery over the short, medium and long
     * bands, a tile per band and the expected damage by firing arc around the unit, shown one after another.
     */
    private Actor damage() {
        if (record == null) {
            return noteTab("Lobby.requisition.record.loading");
        }
        if (record.entity() == null) {
            return noteTab("Lobby.requisition.record.failed");
        }
        RequisitionDamage damage = RequisitionDamage.of(record.entity(), gunnery());
        if (!damage.hasWeapons()) {
            return noteTab("Lobby.requisition.damage.none");
        }
        UiReveal content = new UiReveal(motion);
        Table head = new Table();
        head.left();
        head.add(kit.roleTypeLabel("overline", Messages.getString(damage.capital()
              ? "Lobby.requisition.damage.titleCapital" : "Lobby.requisition.damage.title", damage.gunnery()),
              "text.muted")).left().row();
        Table legend = new Table();
        legend.left();
        legend.add(swatch(EXPECTED_ROLE, "Lobby.requisition.damage.expected")).padRight(space("lg"));
        legend.add(swatch(SUSTAINED_ROLE, "Lobby.requisition.damage.sustained")).padRight(space("lg"));
        legend.add(swatch(MAXIMUM_ROLE, "Lobby.requisition.damage.maximum"));
        kit.tip(legend, true).getActor().setText(Messages.getString("Lobby.requisition.damage.legendTip"));
        head.add(legend).left().padTop(space("xs"));
        content.band(head).left();

        UiStepChart chart = kit.stepChart();
        chart.setName("lobby-requisition-damage-chart");
        float line = size("selection.width");
        chart.show(List.of(
              new UiStepChart.Series(floats(damage.maximum()), UiTheme.tokenColor(MAXIMUM_ROLE), line / 2,
                    UiStepChart.Style.CEILING),
              new UiStepChart.Series(floats(damage.expected()), UiTheme.tokenColor(EXPECTED_ROLE), line,
                    UiStepChart.Style.AREA),
              new UiStepChart.Series(floats(damage.sustained()), UiTheme.tokenColor(SUSTAINED_ROLE), line)),
              RequisitionDamage::format);
        RequisitionDamage.Band[] bands = RequisitionDamage.Band.values();
        int[] starts = new int[bands.length];
        String[] ticks = new String[bands.length];
        for (int i = 0; i < bands.length; i++) {
            starts[i] = bands[i].from();
            ticks[i] = Messages.getString("Lobby.requisition.damage." + bands[i].name().toLowerCase(Locale.ROOT)
                  + "Tick");
        }
        chart.bands(starts, ticks);
        chart.sweep(motion, content.delay(1));
        content.band(chart).height(space("xxxl") * 5).padTop(space("sm"));

        Table tiles = new Table();
        tiles.setName("lobby-requisition-damage-tiles");
        for (RequisitionDamage.RangeBand band : damage.ranges()) {
            float gap = tiles.getCells().isEmpty() ? 0 : space("sm");
            tiles.add(rangeTile(band)).growX().uniformX().minWidth(0).padLeft(gap);
        }
        content.band(tiles).padTop(space("lg"));

        List<RequisitionDamage.RangeBand> ranges = damage.ranges();
        Table arcs = new Table();
        arcs.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.damage.arcs"), "text.muted"))
              .left().growX().minWidth(0);
        String[] bandNames = new String[ranges.size()];
        for (int i = 0; i < bandNames.length; i++) {
            bandNames[i] = ranges.get(i).band().label();
        }
        UiOptionBar bandSwitch = kit.tokenSegmented(true, bandNames);
        bandSwitch.setName("lobby-requisition-damage-arc-band");
        arcs.add(bandSwitch).right().row();
        String[] names = damage.directions().stream().map(RequisitionDamage.Direction::name).toArray(String[]::new);
        float peak = 0;
        for (RequisitionDamage.RangeBand band : ranges) {
            for (double value : damage.arcs(band.band())) {
                peak = Math.max(peak, (float) value);
            }
        }
        Drawable sprite = sprites.apply(spriteKey(picked));
        UiArcDiagram diagram = kit.arcDiagram().centre((sprite != null) ? new Image(sprite, Scaling.fit)
              : new Container<>(kit.icon("unit", size("icon.lg"), UiTheme.tokenColor("text.muted"))));
        diagram.setName("lobby-requisition-damage-arcs");
        float arcPeak = peak;
        Runnable showArcs = () -> diagram.show(names,
              floats(damage.arcs(ranges.get(bandSwitch.getSelectedIndex()).band())), arcPeak,
              RequisitionDamage::format);
        showArcs.run();
        diagram.reveal(motion, content.delay(3));
        UiKit.onChange(bandSwitch, showArcs);
        arcs.add(diagram).colspan(2).growX().minWidth(0).height(space("xxxl") * 7).padTop(space("sm"));
        content.band(arcs).padTop(space("lg"));
        return scrolled(content);
    }

    /** One range band's tile: its name and hexes, the expected damage large, the maximum and heat-sustained small. */
    private Table rangeTile(RequisitionDamage.RangeBand band) {
        Table tile = new Table();
        tile.setBackground(kit.skin.getDrawable("token-card"));
        tile.pad(space("sm"), space("md"), space("sm"), space("md"));
        tile.left();
        Table top = new Table();
        top.add(kit.roleTypeLabel("overline", band.band().label(), "text.secondary")).left().growX().minWidth(0);
        top.add(kit.typeLabel(Messages.getString("Lobby.requisition.damage.hexes", band.ranges()), "caption",
              "text.muted")).right();
        tile.add(top).growX().minWidth(0).row();
        tile.add(kit.typeLabel(RequisitionDamage.format(band.expected()), "display", EXPECTED_ROLE)).left()
              .padTop(space("xxs")).row();
        Label detail = kit.typeLabel(Messages.getString("Lobby.requisition.damage.tileDetail",
              RequisitionDamage.format(band.maximum()), RequisitionDamage.format(band.sustained())), "small",
              "text.secondary");
        detail.setEllipsis(true);
        tile.add(detail).left().growX().minWidth(0);
        return tile;
    }

    private Table swatch(String role, String key) {
        Table swatch = new Table();
        swatch.add(kit.solid(role)).size(space("md"), space("xxs") * 2).padRight(space("xs"));
        swatch.add(kit.typeLabel(Messages.getString(key), "small", "text.secondary"));
        return swatch;
    }
    private static float[] floats(double[] values) {
        float[] floats = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            floats[i] = (float) values[i];
        }
        return floats;
    }

    /** For the developer switch: the dossier tab named {@code key}'s button, to click through the stage. */
    @Nullable Actor dossierTab(String key) {
        RequisitionDossier.Tab named = RequisitionDossier.Tab.of(key);
        return (named == null) ? null : dossierTabs.option(named.ordinal());
    }

    /** The adding strip's skills option {@code index} (Default, Random, Set), to click through the stage. */
    Actor skillsOption(int index) {
        return skillsMode.option(index);
    }

    /** The adding strip's gunnery stepper, to click through the stage. */
    UiStepper gunneryStepper() {
        return gunnery;
    }

    /** The Readout tab's Summary or TRO switch's option, to click through the stage. */
    Actor readoutOption(boolean tro) {
        return readoutSwitch.option(tro ? 1 : 0);
    }

    /** The scroll pane of the dossier tab in view, the innermost first, or null. */
    @Nullable
    ScrollPane dossierScroll() {
        return innermostScroll(tabBody.getActor());
    }

    @Nullable
    private static ScrollPane innermostScroll(@Nullable Actor actor) {
        if (actor instanceof WidgetGroup group) {
            for (Actor child : group.getChildren()) {
                ScrollPane inner = innermostScroll(child);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return (actor instanceof ScrollPane scroll) ? scroll : null;
    }

    /** The dossier tab in view. */
    RequisitionDossier.Tab dossierTab() {
        return tab;
    }

    /** Whether the Readout tab shows the TRO. */
    boolean readoutTro() {
        return readoutTro;
    }

    /** The dossier's tab body now shown, for tests. */
    @Nullable Actor dossierBody() {
        return tabBody.getActor();
    }

    /** Whether the picked unit's full record has loaded. */
    boolean recordLoaded() {
        return record != null;
    }

    /** Whether the dossier shows. */
    boolean dossierOpen() {
        return dossierOpen;
    }

    /** Whether the dossier shows and has finished sliding in. */
    boolean dossierSettled() {
        return dossierOpen && !dossier.hasActions();
    }

    // ---- The adding strip ----

    /** The space between the strip's groups, twice that inside a group ({@link UiStepper#gap()}). */
    private static final float STRIP_GROUP_GAP = space("md");

    private Table strip() {
        Table strip = new Table();
        strip.setName("lobby-requisition-strip");
        strip.add(kit.solid("border.subtle")).growX().height(size("border")).colspan(2).padBottom(space("sm")).row();
        HorizontalGroup controls = stripControls;
        // Each group (skills, the steppers) stands the group space from the next, wrapping whole when narrow
        controls.wrap(true).space(STRIP_GROUP_GAP).wrapSpace(space("xs")).rowLeft();
        Table skillsGroup = new Table();
        skillsGroup.add(caption("Lobby.requisition.skills")).padRight(UiStepper.gap());
        skillsGroup.add(skillsMode);
        controls.addActor(skillsGroup);
        strip.add(controls).growX().minWidth(0).left();
        // Who the units go to stands with the quantity and Add at the row's end, so the recipient is read at the
        // moment of adding; a narrow strip wraps the skills before it
        Table adding = new Table();
        adding.add(ownerGroup);
        adding.add(quantity);
        adding.add(add).width(kit.faceWidth(add, Messages.getString("Lobby.requisition.add"),
              Messages.getString("Lobby.requisition.addMany", LobbyRequisitionController.MAX_QUANTITY),
              Messages.getString("Lobby.requisition.added"))).padLeft(space("md"));
        strip.add(adding).right().bottom().padLeft(STRIP_GROUP_GAP).row();

        Table addedRow = new Table();
        addedRow.add(totals).left().padRight(space("sm"));
        ScrollPane chips = kit.scrollStrip(addedChips);
        addedRow.add(chips).growX().minWidth(0);
        UiButton loadList = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.requisition.source.list"), null);
        kit.tip(loadList, true).getActor().setText(Messages.getString("Lobby.requisition.source.listTip"));
        UiKit.onChange(loadList, () -> actions.source(LobbyForcesPanel.Command.LOAD_LIST));
        UiButton randomArmy = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.requisition.source.random"), null);
        kit.tip(randomArmy, true).getActor().setText(Messages.getString("Lobby.forces.randomArmyTip"));
        UiKit.onChange(randomArmy, () -> actions.source(LobbyForcesPanel.Command.RANDOM_ARMY));
        addedRow.add(caption("Lobby.requisition.sources")).padLeft(space("md"));
        addedRow.add(loadList).padLeft(space("xs"));
        addedRow.add(randomArmy).padLeft(space("xs"));
        strip.add(addedRow).growX().minWidth(0).colspan(2).padTop(space("sm"));
        return strip;
    }

    /** Shows the gunnery and piloting steppers after the skills while they are set, else takes them out of the strip. */
    private void showFixedSkills(boolean show) {
        if (show == (fixedSkills.getParent() == stripControls)) {
            return;
        }
        if (fixedSkills.getChildren().isEmpty()) {
            // Gunnery and piloting fold together, a group's space apart
            fixedSkills.add(gunnery);
            fixedSkills.add(piloting).padLeft(STRIP_GROUP_GAP);
        }
        if (show) {
            stripControls.addActor(fixedSkills);
        } else {
            fixedSkills.remove();
        }
    }

    private void refreshOwners() {
        ownerGroup.clearChildren();
        owner = null;
        List<LobbyRequisition.Recipient> recipients = requisition.recipients();
        if (recipients.stream().noneMatch(recipient -> recipient.id() == ownerId)) {
            ownerId = recipients.isEmpty() ? -1 : recipients.get(0).id();
        }
        if (recipients.size() < 2) {
            return;
        }
        String[] names = recipients.stream().map(recipient -> recipient.self()
              ? Messages.getString("Lobby.requisition.you", recipient.name()) : recipient.name())
              .toArray(String[]::new);
        UiSelect select = kit.tokenSelect(true, names);
        int index = 0;
        for (int i = 0; i < recipients.size(); i++) {
            if (recipients.get(i).id() == ownerId) {
                index = i;
            }
        }
        select.select(index);
        UiKit.onChange(select, () -> {
            ownerId = recipients.get(Math.max(0, select.getSelectedIndex())).id();
            refreshSwatch();
        });
        select.setName("lobby-requisition-owner");
        kit.tip(select, true).getActor().setText(Messages.getString("Lobby.requisition.ownerTip"));
        ownerGroup.add(caption("Lobby.requisition.owner")).padRight(UiStepper.gap());
        float swatchSize = size("icon.md");
        ownerGroup.add(ownerSwatch).size(swatchSize).padRight(space("xs"));
        ownerGroup.add(select).minWidth(size("dialog.sm") / 4).padRight(STRIP_GROUP_GAP);
        owner = select;
        refreshSwatch();
    }

    /** Shows the recipient's camo or colour beside the picker. */
    private void refreshSwatch() {
        ownerSwatch.setDrawable(ownerId < 0 ? null : actions.swatch(ownerId));
    }

    /** The player the units go to; -1 when there is no one to add for. */
    int recipient() {
        return ownerId;
    }

    /** Sends the units to player {@code id} when they may receive them; whether they now do. */
    boolean recipient(int id) {
        List<LobbyRequisition.Recipient> recipients = requisition.recipients();
        for (int i = 0; i < recipients.size(); i++) {
            if (recipients.get(i).id() == id) {
                ownerId = id;
                if (owner != null) {
                    owner.select(i);
                }
                refreshSwatch();
                return true;
            }
        }
        return false;
    }

    private void skills(RequisitionSkills next) {
        skills = next;
        showFixedSkills(skills.mode() == RequisitionSkills.Mode.FIXED);
        list.rebind();
        showDossier();
    }

    private void refreshAdd() {
        int copies = quantity.value();
        if (addedShown < 0) {
            add.setText(kit.cased("button", copies > 1
                  ? Messages.getString("Lobby.requisition.addMany", copies)
                  : Messages.getString("Lobby.requisition.add")));
        }
        add.setDisabled(picked == null || requisition.recipients().isEmpty());
    }

    private void add() {
        if (picked == null || requisition.recipients().isEmpty() || visit == null) {
            return;
        }
        int to = (ownerId < 0) ? requisition.recipients().get(0).id() : ownerId;
        visit.sentFor(to);
        requisition.recipients().stream().filter(LobbyRequisition.Recipient::self)
              .forEach(self -> visit.sentFor(self.id()));
        actions.add(picked, quantity.value(), to, skills);
        add.setText(kit.cased("button", Messages.getString("Lobby.requisition.added")));
        addedShown = clock + ADDED_FOR;
    }

    private void refreshAdded() {
        RequisitionVisit.Totals sum = RequisitionVisit.totals(added);
        totals.setText(sum.units() == 0 ? Messages.getString("Lobby.requisition.nothingAdded")
              : Messages.getString("Lobby.requisition.totals", sum.units(),
              NumberFormat.getIntegerInstance().format(sum.bv()), RequisitionRow.tons(sum.tons())));
        addedChips.clearChildren();
        for (LobbyUnit unit : added) {
            Button chip = kit.actingChip(unit.name(), UiKit.Tone.NORMAL);
            float icon = size("icon.sm");
            chip.add(kit.icon("close", icon, UiTheme.tokenColor("text.secondary"))).size(icon).padLeft(space("xs"));
            kit.tip(chip, true).getActor().setText(Messages.getString("Lobby.requisition.undo", unit.name()));
            UiKit.onChange(chip, () -> {
                visit.forget(unit.id());
                actions.remove(unit.id());
                added = visit.added(forces);
                refreshAdded();
            });
            addedChips.addActor(chip);
        }
    }

    // ---- Rows ----

    /** The widths of the results' columns: the widest text each can hold, or its name over it. */
    private float[] columnWidths() {
        RequisitionRow.Column[] columns = RequisitionRow.Column.values();
        float[] widths = new float[columns.length];
        for (RequisitionRow.Column column : columns) {
            List<String> samples = new ArrayList<>();
            switch (column) {
                case MOVE -> samples.add(RequisitionRow.move(88, 88, 88));
                case ARMOR -> samples.add(NumberFormat.getIntegerInstance().format(8888));
                case ROLE -> {
                    for (UnitRole role : UnitRole.values()) {
                        if (role.hasRole()) {
                            samples.add(role.toString());
                        }
                    }
                }
                case LEVEL -> samples.addAll(Arrays.asList(TechConstants.T_SIMPLE_NAMES));
            }
            float widest = kit.roleTypeLabel("overline", Messages.getString(column.messageKey()), "text.muted")
                  .getPrefWidth();
            Label measure = kit.typeLabel("", COLUMN_TYPE, "text.secondary");
            for (String sample : samples) {
                measure.setText(sample);
                widest = Math.max(widest, measure.getPrefWidth());
            }
            widths[column.ordinal()] = (float) Math.ceil(widest);
        }
        return widths;
    }

    /** The value's width: a five-figure Battle Value, so the columns before it stand still from row to row. */
    private float valueWidth() {
        Label measure = kit.typeLabel(NumberFormat.getIntegerInstance().format(88888), "body.strong",
              "text.primary");
        return (float) Math.ceil(measure.getPrefWidth());
    }

    /** The type of the results' columns. */
    private static final String COLUMN_TYPE = "small";

    /**
     * A row of the results' layout: the sprite, the name over its details, the columns and the value, each column the
     * same width in every row, so they line up; as the row narrows the last columns go first
     * ({@link RequisitionLayout#columns}), the name keeping {@link RequisitionLayout#NAME_MIN}. The header over the
     * results is one, with the columns' names.
     */
    private class ColumnsRow extends Table {
        final Label[] columns = new Label[RequisitionRow.Column.values().length];
        private final List<Cell<Label>> columnCells = new ArrayList<>();
        final Label value;
        private final boolean header;
        private int shownColumns = -1;
        private float scrollStrip = -1;

        ColumnsRow(boolean header) {
            this.header = header;
            float spriteSize = size("control.md");
            pad(0, space("md"), 0, space("md"));
            if (header) {
                add().width(spriteSize).padRight(space("sm"));
                add().growX().minWidth(0);
            } else {
                add(sprite()).size(spriteSize).padRight(space("sm"));
                add(lines()).growX().minWidth(0);
            }
            for (RequisitionRow.Column column : RequisitionRow.Column.values()) {
                Label label = header ? kit.roleTypeLabel("overline", Messages.getString(column.messageKey()),
                      "text.muted") : kit.typeLabel("", COLUMN_TYPE, "text.secondary");
                label.setEllipsis(true);
                columns[column.ordinal()] = label;
                columnCells.add(add(label).left().minWidth(0));
            }
            value = header ? kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.bv"), "text.muted")
                  : kit.typeLabel("", "body.strong", "text.primary");
            value.setAlignment(Align.right);
            add(value).width(valueWidth).right().padLeft(COLUMN_GAP);
            showColumns(0);
        }

        Actor sprite() {
            return new Container<>();
        }

        Actor lines() {
            return new Table();
        }

        /** Shows the first {@code count} columns and takes the rest out of the row. */
        private void showColumns(int count) {
            if (count == shownColumns) {
                return;
            }
            shownColumns = count;
            for (int i = 0; i < columnCells.size(); i++) {
                boolean shown = i < count;
                columns[i].setVisible(shown);
                columnCells.get(i).width(shown ? columnWidths[i] : 0).padLeft(shown ? COLUMN_GAP : 0);
            }
            invalidate();
        }

        @Override
        protected void sizeChanged() {
            if (!header) {
                showColumns(columnsFor(getWidth()));
            }
            super.sizeChanged();
        }

        /** How many columns a row {@code rowWidth} wide shows. */
        private int columnsFor(float rowWidth) {
            float fixed = 2 * space("md") + size("control.md") + space("sm") + valueWidth + COLUMN_GAP;
            return RequisitionLayout.columns(rowWidth - fixed, columnWidths, COLUMN_GAP);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (header) {
                // The header follows the rows: their width, and the scroll bar's strip beside them
                float strip = Math.max(0, results.getWidth() - list.getWidth());
                if (strip != scrollStrip) {
                    scrollStrip = strip;
                    padRight(space("md") + strip);
                    invalidate();
                }
                showColumns(columnsFor(list.getWidth()));
            }
            super.draw(batch, parentAlpha);
        }

        /** How many columns show, for tests. */
        int shownColumns() {
            return shownColumns;
        }
    }

    /** The space between the results' columns. */
    private static final float COLUMN_GAP = space("lg");

    /** One result: the sprite, the name over tonnage · type · tech · year, the columns and the Battle or Point Value. */
    private final class ResultRow extends ColumnsRow {
        private Container<Actor> sprite;
        private Label name;
        private Label details;
        private final Actor placeholder = kit.icon("unit", size("icon.md"), UiTheme.tokenColor("text.muted"));
        private final Image image = new Image((Drawable) null, Scaling.fit);

        ResultRow() {
            super(false);
        }

        @Override
        Actor sprite() {
            sprite = new Container<>();
            return sprite;
        }

        @Override
        Actor lines() {
            name = kit.roleTypeLabel("name", "", "text.primary");
            details = kit.typeLabel("", "small", "text.secondary");
            Table lines = new Table();
            lines.left().defaults().left();
            name.setEllipsis(true);
            details.setEllipsis(true);
            lines.add(name).growX().minWidth(0).row();
            lines.add(details).growX().minWidth(0);
            return lines;
        }

        void bind(RequisitionRow row) {
            name.setText(row.name());
            details.setText(row.details());
            for (RequisitionRow.Column column : RequisitionRow.Column.values()) {
                columns[column.ordinal()].setText(column.of(row));
            }
            value.setText(showPv ? String.valueOf(row.pv())
                  : NumberFormat.getIntegerInstance().format(skills.bv(row.bv())));
            Drawable drawable = sprites.apply(spriteKey(row.unit()));
            if (drawable == null) {
                sprite.setActor(placeholder);
                sprite.size(size("icon.md"));
            } else {
                image.setDrawable(drawable);
                sprite.setActor(image);
                sprite.fill();
                sprite.size(size("control.md"));
            }
        }
    }

    // ---- The results and the dossier ----

    /**
     * The results with the dossier beside them, or over all but their leading edge when the two do not fit
     * ({@link RequisitionLayout}); the dossier slides in over them.
     */
    private final class Split extends WidgetGroup {
        private final Actor resultsSide;
        private final Table dossierSide;
        private final Image shadow;
        private boolean slideIn;

        Split(Actor resultsSide, Table dossierSide) {
            this.resultsSide = resultsSide;
            this.dossierSide = dossierSide;
            shadow = new Image(kit.skin.getDrawable("shadow"));
            shadow.setVisible(false);
            addActor(resultsSide);
            addActor(shadow);
            addActor(dossierSide);
        }

        void opened(boolean opening) {
            slideIn = opening;
            invalidate();
        }

        @Override
        public float getPrefWidth() {
            return 0;
        }

        @Override
        public float getPrefHeight() {
            return 0;
        }

        @Override
        public void layout() {
            float gap = space("md");
            RequisitionLayout.Dock dock = RequisitionLayout.dock(getWidth(), dossierSide.isVisible(), gap);
            resultsSide.setBounds(0, 0, dock.resultsWidth(), getHeight());
            if (!dossierSide.isVisible()) {
                shadow.setVisible(false);
                return;
            }
            float target = dock.dossierX();
            boolean sliding = dossierSide.hasActions();
            dossierSide.setSize(dock.dossierWidth(), getHeight());
            if (slideIn && dock.overlay() && motion) {
                dossierSide.setPosition(getWidth(), 0);
                dossierSide.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.moveTo(target, 0,
                      DesignTokens.shipped().duration("medium") / 1000f, Interpolation.pow2Out));
            } else if (!sliding) {
                dossierSide.setPosition(target, 0);
            }
            slideIn = false;
            dossierSide.validate();
            shadow.setVisible(dock.overlay());
            float blur = space("md");
            shadow.setBounds(target - blur, -blur, dock.dossierWidth() + 2 * blur, getHeight() + 2 * blur);
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            if (shadow.isVisible()) {
                float blur = space("md");
                shadow.setX(dossierSide.getX() - blur);
            }
        }
    }
}
