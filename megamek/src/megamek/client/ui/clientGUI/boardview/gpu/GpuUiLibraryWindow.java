/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.MegaMek;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.ColorToken;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Palette;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiAngleDial;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCardGrid;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiChoiceGrid;
import megamek.client.ui.gdx.UiConfirmation;
import megamek.client.ui.gdx.UiFlow;
import megamek.client.ui.gdx.UiHexSides;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiList;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/**
 * The developer gallery of the native (libGDX) side of the design system, opened from Help in the lobby. It shows
 * every hud-v3 toolkit element as the game draws it today, one tab per group, each beside the column where its
 * migrated (Token v0) look will go, so the team can discuss the migration element by element. It is a scene of the one
 * native window ({@link GpuHost}): it replaces a map preview, refuses beside a board or editor, and ends when a board
 * opens.
 */
public final class GpuUiLibraryWindow extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuUiLibraryWindow.class);
    private static final String[] TABS = { "colors", "buttons", "selection", "inputs", "lists", "containers",
                                           "status", "hud" };
    /** Button styles whose own tab shows them in their control; the Buttons tab names them only. */
    private static final Set<String> SHOWN_ELSEWHERE = Set.of("hud-seg", "hud-pill", "hud-tab", "hud-tab-caps",
          "hud-select", "hud-select-inline", "hud-menu-row", "hud-row");
    private static final float NAME_WIDTH = 140;
    private static final float TODAY_WIDTH = 420;
    private static final float MIGRATED_WIDTH = 400;
    private static final float COLUMN_GAP = 14;
    /** A dialog footer button's least width (docs/design-system/tokens.md, Dialogs). */
    private static final float BUTTON_MIN_WIDTH = 88;
    private static final String SAMPLE_SPRITE = "defaults/default_heavy.png";
    /** The token button style each hud-v3 button style migrates to (tokens.md, "Migrating hud-v3 styles"). */
    private static final Map<String, String> MIGRATED_BUTTONS = Map.ofEntries(
          Map.entry("hud", "button-secondary"), Map.entry("hud-plain", "button-secondary"),
          Map.entry("hud-main", "button-primary"), Map.entry("hud-danger", "button-danger"),
          Map.entry("hud-icon", "button-icon"), Map.entry("hud-delete-icon", "button-icon"),
          Map.entry("hud-mini", "button-secondary" + UiTheme.COMPACT),
          Map.entry("hud-utility-small", "button-secondary" + UiTheme.COMPACT),
          Map.entry("hud-utility", "button-special"), Map.entry("hud-utility-narrow", "button-special"),
          Map.entry("hud-utility-row", "button-special"));

    /** A hud-v3 color and the token that replaces it, or null when it stays a HUD game color. */
    private record ColorMove(String name, Color today, String token) { }

    /** Each hud-v3 color in UiTheme and its token (tokens.md, "Migrating hud-v3 colors"). */
    private static final List<ColorMove> COLOR_MOVES = List.of(
          new ColorMove("TEXT", UiTheme.TEXT, "text.primary"),
          new ColorMove("ACCENT", UiTheme.ACCENT, "text.secondary"),
          new ColorMove("MUTED", UiTheme.MUTED, "text.muted"),
          new ColorMove("DISABLED", UiTheme.DISABLED, "text.disabled"),
          new ColorMove("PANEL", UiTheme.PANEL, "surface.panel"),
          new ColorMove("PANEL2", UiTheme.PANEL2, "surface.raised"),
          new ColorMove("POP", UiTheme.POP, "surface.popover"),
          new ColorMove("FILL", UiTheme.FILL, "surface.inverse"),
          new ColorMove("MAIN", UiTheme.MAIN, "surface.inverse"),
          new ColorMove("FILL_INK", UiTheme.FILL_INK, "text.onInverse"),
          new ColorMove("FILL_MUTED", UiTheme.FILL_MUTED, "surface.raised"),
          new ColorMove("LINE", UiTheme.LINE, "border.subtle"),
          new ColorMove("TRACK", UiTheme.TRACK, "border.subtle"),
          new ColorMove("QUIET", UiTheme.QUIET, "border.subtle"),
          new ColorMove("TICK", UiTheme.TICK, "border.control"),
          new ColorMove("RAIL", UiTheme.RAIL, "border.control"),
          new ColorMove("CORNER", UiTheme.CORNER, "border.strong"),
          new ColorMove("HOVER", UiTheme.HOVER, "border.strong"),
          new ColorMove("MINT", UiTheme.MINT, "accent.primary"),
          new ColorMove("CORAL", UiTheme.CORAL, "status.danger"),
          new ColorMove("AMBER", UiTheme.AMBER, "status.warning"),
          new ColorMove("VIOLET", UiTheme.VIOLET, "status.gamemaster"),
          new ColorMove("BLIP", UiTheme.BLIP, null));
    /** Mint meant both active and success; its success uses move to their own token. */
    private static final String MINT_SUCCESS = "status.success";

    /** The Token v0 type style each hud-v3 font becomes (tokens.md, "Migrating hud-v3 fonts"). */
    private static final Map<String, String> FONT_MOVES = Map.ofEntries(
          Map.entry("hud-body", "body"), Map.entry("hud-small", "small"), Map.entry("hud-medium", "label"),
          Map.entry("hud-utility", "label"), Map.entry("hud-sub", "caption"), Map.entry("hud-caption", "overline"),
          Map.entry("hud-mini", "label"), Map.entry("hud-button", "label"), Map.entry("hud-name", "body.strong"),
          Map.entry("hud-title", "title"), Map.entry("hud-heading", "headline"), Map.entry("hud-phase", "display"),
          Map.entry("hud-main", "label"));
    /**
     * The text role whose token type replaces a hud-v3 font, where one does; the gallery shows the role in its
     * presentation mode, condensed and upper case as the role is.
     */
    private static final Map<String, String> FONT_ROLES = Map.ofEntries(
          Map.entry("hud-utility", "button"), Map.entry("hud-caption", "overline"), Map.entry("hud-mini", "button"),
          Map.entry("hud-button", "button"), Map.entry("hud-name", "name"), Map.entry("hud-title", "title"),
          Map.entry("hud-heading", "headline"), Map.entry("hud-phase", "display"), Map.entry("hud-main", "button"));
    /** The hud-v3 fonts whose text hud-v3 upper-cases where it draws them (labels, buttons, titles, the phase). */
    private static final Set<String> HUD_UPPER = Set.of("hud-caption", "hud-mini", "hud-button", "hud-title",
          "hud-heading", "hud-phase", "hud-main");

    /** The open gallery; read and written on the EDT. */
    private static GpuUiLibraryWindow open;

    private final float preference;
    private DesignTokens tokens = DesignTokens.shipped();
    /** The palette when the gallery opened, put back when it closes. */
    private final Palette previousPalette = DesignTokens.palette();
    private final GpuDisplayScale displayScale = new GpuDisplayScale();
    private final Table[] pages = new Table[TABS.length];
    private volatile boolean closeRequested;
    /** What runs on the EDT once the gallery has ended. */
    private Runnable afterClose;
    private final GpuHost.Scene scene = new GpuHost.Scene() {
        @Override
        public ApplicationListener listener() {
            return GpuUiLibraryWindow.this;
        }

        @Override
        public String title() {
            return Messages.getString("UiLibrary.gdx.title");
        }

        @Override
        public void closeRequested() {
            GpuHost.end(this);
        }

        @Override
        public void ended(Throwable failure) {
            if (failure != null) {
                LOGGER.error(failure, "The native UI library failed");
            }
            SwingUtilities.invokeLater(GpuUiLibraryWindow.this::finished);
        }
    };
    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private UiKit.Segmented tabs;
    /** The tab shown. */
    private int tab;
    private ScrollPane scroll;
    private UiModal modal;
    private UiModal tokenModal;
    private UiConfirmation confirmation;
    private UiConfirmation tokenConfirmation;
    private BoardScene.Pixels sprite;

    private GpuUiLibraryWindow(float preference) {
        this.preference = preference;
    }

    /** EDT: opens the gallery, or brings the open one to the front. */
    public static void showLibrary(Window owner) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showLibrary(owner));
            return;
        }
        if (open != null && !open.closeRequested) {
            GpuHost.focus();
            return;
        }
        if (GpuBoardWindow.isRunning()) {
            GpuBoardWindow.alreadyOpen(owner);
            return;
        }
        GpuUiLibraryWindow window = new GpuUiLibraryWindow(GUIPreferences.getInstance().getGUIScale());
        open = window;
        GpuHost.show(window.scene);
    }

    /**
     * EDT: closes the open gallery and runs {@code then} on the EDT once it has ended. Returns false, and runs
     * nothing, when no gallery is open.
     */
    static boolean closeThen(Runnable then) {
        if (open == null) {
            return false;
        }
        open.afterClose = then;
        open.requestClose();
        return true;
    }

    /** EDT: whether the gallery holds the window and is not closing. */
    static boolean isShowing() {
        return open != null && !open.closeRequested;
    }

    private void finished() {
        if (open == this) {
            open = null;
        }
        Runnable then = afterClose;
        afterClose = null;
        if (then != null) {
            then.run();
        }
    }

    private void requestClose() {
        closeRequested = true;
        GpuHost.end(scene);
    }

    @Override
    public void create() {
        boardSkin = GpuBoardSkin.forScene();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);
        sprite = loadSprite();

        Table root = new Table();
        root.setFillParent(true);
        root.top().left();
        Table head = new Table();
        head.left().pad(20, 24, 10, 24);
        head.defaults().left();
        head.add(kit.label(text("UiLibrary.gdx.title"), "hud-heading", 20, UiTheme.TEXT)).row();
        Label note = kit.label(text("UiLibrary.gdx.note"), "hud-small", 11.5f, UiTheme.MUTED);
        note.setWrap(true);
        head.add(note).width(NAME_WIDTH + TODAY_WIDTH + MIGRATED_WIDTH + 2 * COLUMN_GAP).padTop(4).row();
        head.add(presentationSwitch()).padTop(8);
        root.add(head).growX().row();
        String[] labels = new String[TABS.length];
        for (int index = 0; index < TABS.length; index++) {
            labels[index] = text("UiLibrary.gdx.tab." + TABS[index]);
        }
        tabs = kit.segmented("hud-tab", false, labels);
        tabs.left().pad(2, 24, 0, 24);
        for (int index = 0; index < TABS.length; index++) {
            int tab = index;
            UiKit.onChange(tabs.buttons.get(index), () -> selectTab(tab));
        }
        root.add(tabs).growX().row();
        scroll = kit.scrollList(null);
        root.add(scroll).grow();
        stage.addActor(root);

        modal = modal(false);
        stage.addActor(modal);
        confirmation = new UiConfirmation(kit, "library-confirm", () -> { });
        confirmation.text(text("UiLibrary.gdx.confirmHeading"), text("UiLibrary.gdx.confirmDetail"),
              text("UiLibrary.gdx.confirmYes"), text("UiLibrary.gdx.confirmNo"));
        stage.addActor(confirmation.actor());
        tokenOverlays();
        selectTab(0);
        if (closeRequested) {
            GpuHost.end(scene);
        }
    }

    /** Builds the token modal and confirmation, whose buttons follow the kit's text mode, in place of earlier ones. */
    private void tokenOverlays() {
        if (tokenModal != null) {
            tokenModal.remove();
            tokenConfirmation.actor().remove();
        }
        tokenModal = modal(true);
        stage.addActor(tokenModal);
        tokenConfirmation = new UiConfirmation(kit, "library-token-confirm", () -> { }, UiTheme.TOKEN);
        tokenConfirmation.text(text("UiLibrary.gdx.confirmHeading"), text("UiLibrary.gdx.confirmDetail"),
              text("UiLibrary.gdx.confirmYes"), text("UiLibrary.gdx.confirmNo"));
        stage.addActor(tokenConfirmation.actor());
        tokenConfirmation.resize(stage.getWidth(), stage.getHeight());
    }

    /**
     * The text case mode the token controls are built in: game, as in-game views are, or app. Switching rebuilds the
     * pages and the token overlays, and shows the current tab where it was scrolled to.
     */
    private Actor presentationSwitch() {
        UiButton toggle = kit.button("button-toggle" + UiTheme.COMPACT, null, text("UiLibrary.presentation.game"), null);
        toggle.setChecked(kit.presentation() == Presentation.GAME);
        UiKit.onChange(toggle, () -> {
            Presentation mode = toggle.isChecked() ? Presentation.GAME : Presentation.APP;
            rebuildPages(() -> kit.presentation(mode));
        });
        UiButton colorBlind = kit.button("button-toggle" + UiTheme.COMPACT, null, text("UiLibrary.palette.colorBlind"),
              null);
        colorBlind.setChecked(DesignTokens.palette() == Palette.COLORBLIND);
        UiKit.onChange(colorBlind, () -> {
            Palette palette = colorBlind.isChecked() ? Palette.COLORBLIND : Palette.STANDARD;
            rebuildPages(() -> {
                DesignTokens.setPalette(palette);
                tokens = DesignTokens.shipped();
                boardSkin.refreshTokens();
            });
        });
        Table row = new Table();
        row.defaults().padRight(8);
        row.add(toggle);
        row.add(colorBlind);
        return row;
    }

    /** Applies a change after this event, as the rebuilt pages replace the actors under the pointer. */
    private void rebuildPages(Runnable change) {
        Gdx.app.postRunnable(() -> {
            change.run();
            float scrollY = scroll.getScrollY();
            Arrays.fill(pages, null);
            tokenOverlays();
            selectTab(tab);
            scroll.layout();
            scroll.setScrollY(scrollY);
            scroll.updateVisualScroll();
        });
    }

    @Override
    public void resize(int width, int height) {
        float scale = DisplayScale.read(preference, displayScale.contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        confirmation.resize(stage.getWidth(), stage.getHeight());
        tokenConfirmation.resize(stage.getWidth(), stage.getHeight());
    }

    @Override
    public void render() {
        ScreenUtils.clear(color(tokens.color("surface.base", Mode.DARK)));
        stage.act(Gdx.graphics.getDeltaTime());
        hud.update(sprite == null ? Set.of() : Set.of(sprite));
        stage.draw();
    }

    @Override
    public void dispose() {
        DesignTokens.setPalette(previousPalette);
        if (stage != null) {
            stage.dispose();
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.release();
        }
    }

    /** Shows tab {@code index}; each page is built when first shown and keeps its state afterwards. */
    private void selectTab(int index) {
        tab = index;
        tabs.select(index);
        if (pages[index] == null) {
            pages[index] = page(index);
        }
        scroll.setActor(pages[index]);
        scroll.setScrollY(0);
        stage.setScrollFocus(scroll);
    }

    /** A unit sprite for the unit rows and toasts; without mm-data's image they show a sensor contact's "?". */
    private static BoardScene.Pixels loadSprite() {
        try {
            var image = ImageIO.read(new File(Configuration.unitImagesDir(), SAMPLE_SPRITE));
            return image == null ? null : new BoardScene.Pixels(image);
        } catch (IOException failure) {
            LOGGER.warn(failure, "The UI library's sample sprite failed to load");
            return null;
        }
    }

    // ---- Pages

    private Table page(int index) {
        Table page = new Table();
        page.top().left().pad(16, 24, 40, 24);
        page.defaults().left().top();
        page.add(columnTitle("UiLibrary.gdx.element")).width(NAME_WIDTH).padRight(COLUMN_GAP);
        page.add(columnTitle("UiLibrary.gdx.today")).width(TODAY_WIDTH).padRight(COLUMN_GAP);
        page.add(columnTitle("UiLibrary.gdx.migrated")).width(MIGRATED_WIDTH).row();
        switch (TABS[index]) {
            case "colors" -> colors(page);
            case "buttons" -> buttons(page);
            case "selection" -> selection(page);
            case "inputs" -> inputs(page);
            case "lists" -> lists(page);
            case "containers" -> containers(page);
            case "status" -> status(page);
            default -> hudParts(page);
        }
        return page;
    }

    private void colors(Table page) {
        heading(page, "UiLibrary.gdx.colors");
        Set<String> used = new HashSet<>();
        used.add(MINT_SUCCESS);
        for (ColorMove move : COLOR_MOVES) {
            Actor migrated;
            if (move.token() == null) {
                migrated = kit.label(text("UiLibrary.gdx.gameColor"), "hud-small", 11.5f, UiTheme.MUTED);
            } else {
                used.add(move.token());
                Table token = new Table();
                token.left().top();
                token.add(tokenSwatch(move.token())).left().row();
                if ("MINT".equals(move.name())) {
                    token.add(kit.label(text("UiLibrary.gdx.successUses", MINT_SUCCESS), "hud-small", 11,
                          UiTheme.MUTED)).left().padTop(6).row();
                    token.add(tokenSwatch(MINT_SUCCESS)).left().padTop(4);
                }
                migrated = token;
            }
            compare(page, move.name(), swatch(null, "#" + move.today().toString().toUpperCase(Locale.ROOT),
                  move.today()), migrated);
        }
        Table added = new Table();
        added.defaults().left().padBottom(8);
        for (ColorToken token : tokens.colors().values()) {
            if (!used.contains(token.name())) {
                added.add(tokenSwatch(token.name())).row();
            }
        }
        compare(page, text("UiLibrary.gdx.newTokens"),
              kit.label(text("UiLibrary.gdx.noHudColor"), "hud-small", 11.5f, UiTheme.MUTED), added);
        heading(page, "UiLibrary.gdx.fonts");
        for (UiTheme.HudFont font : UiTheme.HUD_FONTS) {
            if (font.name().equals(UiTheme.CAPS_FONT)) {
                continue;
            }
            UiTheme.HudFont type = typeStyle(FONT_MOVES.get(font.name()));
            String role = FONT_ROLES.get(font.name());
            // Each row shows the kind of text the font carries: short framing labels, or a sentence for body text
            String line = text("UiLibrary.gdx.fontSample." + font.name());
            String today = HUD_UPPER.contains(font.name()) ? UiTheme.upper(line) : line;
            compare(page, font.name(), fontSample(font.name(), font, font.name(), today, TODAY_WIDTH),
                  role != null ? roleSample(role, line, MIGRATED_WIDTH)
                        : fontSample(type.name(), type, UiTheme.TYPE + type.name(), line, MIGRATED_WIDTH));
        }
        // Game mode's caps font is new: hud-v3 upper-cases its labels in its condensed fonts instead
        UiTheme.HudFont caps = UiTheme.HUD_FONTS.stream().filter(font -> font.name().equals(UiTheme.CAPS_FONT))
              .findFirst().orElseThrow();
        compare(page, UiTheme.CAPS_FONT, kit.label(text("UiLibrary.gdx.capsToday"), "hud-small", 11.5f,
              UiTheme.MUTED), fontSample(UiTheme.CAPS_FONT, caps, UiTheme.CAPS_FONT,
              tokens.cased("button", Presentation.GAME, text("UiLibrary.gdx.fontSample.hud-button"),
                    MegaMek.getMMOptions().getLocale()), MIGRATED_WIDTH));
    }

    private void buttons(Table page) {
        heading(page, "UiLibrary.gdx.buttonStyles");
        page.add();
        String[] states = {text("UiLibrary.gdx.rest"), text("UiLibrary.gdx.hover"), text("UiLibrary.gdx.held"),
              text("UiLibrary.gdx.pressed"), text("UiLibrary.gdx.disabled")};
        page.add(stateRow(76, 6, states)).left().padRight(COLUMN_GAP);
        page.add(stateRow(70, 6, states)).left().row();
        List<String> styles = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        kit.skin.getAll(TextButton.TextButtonStyle.class).keys().forEach(style -> {
            if (style.startsWith("hud")) {
                (SHOWN_ELSEWHERE.contains(style) ? elsewhere : styles).add(style);
            }
        });
        styles.sort(String::compareTo);
        elsewhere.sort(String::compareTo);
        for (String style : styles) {
            compare(page, style, buttonStates(() -> sampleButton(style), 76, 6), migratedButton(style));
        }
        Label note = kit.label(text("UiLibrary.gdx.shownElsewhere", String.join(", ", elsewhere)), "hud-small",
              11.5f, UiTheme.MUTED);
        note.setWrap(true);
        page.add(note).colspan(3).width(NAME_WIDTH + TODAY_WIDTH + MIGRATED_WIDTH).padTop(4).row();

        heading(page, "UiLibrary.gdx.buttonParts");
        Table badges = new Table();
        badges.defaults().padRight(12);
        badges.add(kit.button("hud-utility", "report", text("UiLibrary.gdx.sampleLog"), null).badge("3"));
        badges.add(kit.button("hud-icon", "chat", null, null).badge("12"));
        Table tokenBadges = new Table();
        tokenBadges.defaults().padRight(20);
        tokenBadges.add(kit.button("button-secondary", "report", text("UiLibrary.gdx.sampleLog"), null).badge("3"));
        tokenBadges.add(kit.button("button-icon", "chat", null, null).badge("12"));
        compare(page, "UiButton.badge", badges, tokenBadges);
        Table dots = new Table();
        dots.defaults().padRight(12);
        dots.add(kit.button("hud-utility", "settings", text("UiLibrary.gdx.sampleSettings"), null).dot(true));
        dots.add(kit.button("hud-icon", "chat", null, null).dot(true));
        Table tokenDots = new Table();
        tokenDots.defaults().padRight(20);
        tokenDots.add(kit.button("button-secondary", "settings", text("UiLibrary.gdx.sampleSettings"), null).dot(true));
        tokenDots.add(kit.button("button-icon", "chat", null, null).dot(true));
        compare(page, "UiButton.dot", dots, tokenDots);
        compare(page, "UiKit.closeButton", kit.closeButton(() -> { }), kit.closeButton(() -> { }, true));
    }

    private void selection(Table page) {
        String all = text("UiLibrary.gdx.segAll");
        String mine = text("UiLibrary.gdx.segMine");
        String critical = text("UiLibrary.gdx.segCritical");
        String friendly = text("UiLibrary.gdx.tabFriendly");
        String contacts = text("UiLibrary.gdx.tabContacts");
        String combat = text("UiLibrary.gdx.tabCombat");
        String chat = text("UiLibrary.gdx.tabChat");
        String compact = UiTheme.COMPACT;
        heading(page, "UiLibrary.gdx.segmented");
        compare(page, "hud-seg", kit.segmented("hud-seg", false, all, mine, critical).select(0),
              kit.segmented(UiTheme.TOKEN_SEGMENT, false, all, mine, critical).select(0));
        compare(page, "hud-pill", kit.segmented("hud-pill", false, all, mine, critical).select(1),
              kit.segmented(UiTheme.TOKEN_SEGMENT + compact, false, all, mine, critical).select(1));
        compare(page, "hud-tab", kit.segmented("hud-tab", false, friendly, contacts).select(0),
              kit.segmented(UiTheme.TOKEN_TAB + compact, false, friendly, contacts).select(0));
        compare(page, "hud-tab-caps", kit.segmented("hud-tab-caps", false, all, combat, chat).select(0),
              kit.segmented(UiTheme.TOKEN_TAB + compact, false, all, combat, chat).select(0));
        heading(page, "UiLibrary.gdx.selects");
        String value = text("UiLibrary.gdx.selectValue");
        compare(page, "hud-select", new Container<>(kit.select(value, false)).width(220),
              new Container<>(kit.select(value, false, true)).width(220));
        compare(page, "hud-select-inline", kit.select(value, true), kit.select(value, true, true));
        compare(page, "UiKit.checkbox", checkboxes(false), checkboxes(true));
        compare(page, "UiChoice", null, choices());
        compare(page, text("UiLibrary.gdx.onDisabled"), null, disabledOn(all, mine, critical));
        heading(page, "UiLibrary.gdx.menus");
        compare(page, "hud-menu-row", menuRows("hud-menu-row"), menuRows(UiTheme.TOKEN_MENU_ROW));
        compare(page, "UiMenuList", new Container<>(menuPanel()).width(280),
              new Container<>(tokenMenuPanel()).width(280));
    }

    private Table checkboxes(boolean token) {
        Table checks = new Table();
        checks.defaults().padRight(20);
        checks.add(kit.checkbox(text("UiLibrary.gdx.ticked"), true, token));
        checks.add(kit.checkbox(text("UiLibrary.gdx.clear"), false, token));
        return checks;
    }

    /** The token check box, radio button and switch, on: enabled above, disabled below. */
    private Table choices() {
        Table choices = new Table();
        choices.defaults().left().padRight(20).padBottom(8);
        for (boolean disabled : new boolean[] { false, true }) {
            UiChoice[] row = { kit.check(text("UiLibrary.gdx.ticked"), true),
                  kit.radio(text("UiLibrary.gdx.radioOn"), true),
                  kit.toggleSwitch(text("UiLibrary.gdx.switchOn"), true) };
            for (UiChoice choice : row) {
                choice.setDisabled(disabled);
                choices.add(choice);
            }
            choices.row();
        }
        return choices;
    }

    /** A checked toggle, a checked icon toggle and the segmented controls, each disabled while on. */
    private Table disabledOn(String... labels) {
        Table samples = new Table();
        samples.defaults().left().padBottom(8);
        Table buttons = new Table();
        buttons.defaults().padRight(12);
        UiButton toggle = kit.button("button-toggle", null, text("UiLibrary.gdx.sampleAction"), null).pressed(true);
        toggle.setDisabled(true);
        UiButton icon = kit.button("button-icon", "search", null, null).pressed(true);
        icon.setDisabled(true);
        buttons.add(toggle);
        buttons.add(icon);
        samples.add(buttons).row();
        UiKit.Segmented segments = kit.segmented(UiTheme.TOKEN_SEGMENT + UiTheme.COMPACT, false, labels).select(1);
        segments.buttons.forEach(button -> button.setDisabled(true));
        samples.add(segments).row();
        var segmented = kit.tokenSegmented(true, labels);
        segmented.select(1);
        segmented.setDisabled(true);
        samples.add(segmented);
        return samples;
    }

    private Table menuRows(String style) {
        Table rows = new Table();
        rows.defaults().growX().width(280);
        rows.add(kit.menuRow(style, text("UiLibrary.gdx.menuShortcut"), "Ctrl+K", null, false)).row();
        rows.add(kit.menuRow(style, text("UiLibrary.gdx.menuChecked"), null, true, false)).row();
        rows.add(kit.menuRow(style, text("UiLibrary.gdx.menuGroup"), null, null, true)).row();
        rows.add(kit.menuRow(style, text("UiLibrary.gdx.menuHighlighted"), null, false, false).pressed(true));
        return rows;
    }

    private void inputs(Table page) {
        heading(page, "UiLibrary.gdx.fields");
        compare(page, "hud-search", new Container<>(kit.search(text("UiLibrary.gdx.search"))).width(320),
              new Container<>(kit.search(text("UiLibrary.gdx.search"), true)).width(320));
        TextField tokenField = new TextField(text("UiLibrary.gdx.fieldValue"), kit.skin, "input");
        compare(page, "hud (TextField)",
              new Container<>(new TextField(text("UiLibrary.gdx.fieldValue"), kit.skin, "hud")).width(220).height(29),
              new Container<>(UiKit.hoverEdge(tokenField, tokenField)).width(220));
        compare(page, "UiNumber", new Container<>(number()).width(340),
              new Container<>(number().tokenLook()).width(340));
        compare(page, "UiKit.sliderStyle", new Container<>(slider(kit.sliderStyle())).width(300),
              new Container<>(slider(kit.tokenSliderStyle())).width(300));
        heading(page, "UiLibrary.gdx.pickers");
        compare(page, "UiAngleDial", new Container<>(dial("default")).size(132),
              new Container<>(dial(UiTheme.TOKEN)).size(132));
        compare(page, "UiHexSides", new Container<>(hexSides("default")).size(150),
              new Container<>(hexSides(UiTheme.TOKEN)).size(150));
    }

    private UiNumber number() {
        return new UiNumber(kit, stage, text("UiLibrary.gdx.elevation"), 2, 0, 10, 1, (value, done) -> { });
    }

    private Slider slider(Slider.SliderStyle style) {
        Slider slider = new Slider(0, 100, 1, false, style);
        slider.setValue(40);
        slider.addListener(new UiSliderInput(slider));
        return slider;
    }

    private UiAngleDial dial(String style) {
        UiAngleDial[] dial = new UiAngleDial[1];
        dial[0] = new UiAngleDial(kit, value -> dial[0].value(value), () -> { }).style(style);
        dial[0].value(60);
        return dial[0];
    }

    private UiHexSides hexSides(String style) {
        UiHexSides[] sides = new UiHexSides[1];
        sides[0] = new UiHexSides(kit, 0b000011, side -> sides[0].selected(sides[0].selected() ^ (1 << side)))
              .style(style);
        return sides[0];
    }

    private void lists(Table page) {
        heading(page, "UiLibrary.gdx.lists");
        compare(page, "UiList + hud-row", new Container<>(list("hud-row", null)).width(320),
              new Container<>(kit.scrollList(list(UiTheme.TOKEN_ROW, UiTheme.TOKEN), true)).width(320));
        compare(page, "UiTree", new Container<>(tree(null)).width(320),
              new Container<>(kit.scrollList(tree(UiTheme.TOKEN), true)).width(320));
        compare(page, "hud-list (ScrollPane)", new Container<>(kit.scrollList(listItems(false))).size(300, 110),
              new Container<>(kit.scrollList(listItems(true), true)).size(300, 110));
        Table empty = kit.panel();
        kit.empty(empty, text("UiLibrary.gdx.empty"));
        Table tokenEmpty = new Table();
        tokenEmpty.setBackground(kit.skin.getDrawable("list-frame"));
        kit.empty(tokenEmpty, text("UiLibrary.gdx.empty"), true);
        compare(page, "UiKit.empty", new Container<>(empty).width(320), new Container<>(tokenEmpty).width(320));
        heading(page, "UiLibrary.gdx.grids");
        compare(page, "UiCardGrid", new Container<>(cardGrid(false)).size(420, 330),
              new Container<>(cardGrid(true)).size(400, 330));
        compare(page, "UiChoiceGrid", new Container<>(choiceGrid("hud", 420)).width(420),
              new Container<>(choiceGrid("button-secondary", 400)).width(400));
        compare(page, "hud-split", new Container<>(split(false)).size(320, 120),
              new Container<>(kit.tokenSplit(split(true))).size(320, 120));
    }

    /** Three list rows in {@code rowStyle}, the second selected, reorderable in a {@link UiList} of {@code style}. */
    private UiList list(String rowStyle, String style) {
        UiList list = new UiList(kit, style);
        for (int index = 0; index < 3; index++) {
            UiButton row = kit.button(rowStyle, null,
                  text(index == 1 ? "UiLibrary.sample.selectedRow" : "UiLibrary.sample.row"), null);
            row.getLabel().setAlignment(Align.left);
            row.getCell(row.getLabel()).growX();
            row.pressed(index == 1);
            list.add(row, row);
        }
        list.reorderable((from, to) -> { });
        return list;
    }

    /** Twelve plain list items; in the token look each takes a row's height and text inset. */
    private Table listItems(boolean token) {
        Table items = new Table();
        items.top().left();
        for (int index = 1; index <= 12; index++) {
            if (token) {
                items.add(kit.label(text("UiLibrary.gdx.listItem", index), "hud-body", 13,
                      UiTheme.tokenColor("text.primary"))).left().growX().height(28).padLeft(8).row();
            } else {
                items.add(kit.label(text("UiLibrary.gdx.listItem", index), "hud-body", 12.5f, UiTheme.TEXT)).left()
                      .pad(3, 10, 3, 10).row();
            }
        }
        return items;
    }

    private UiChoiceGrid choiceGrid(String style, float width) {
        UiChoiceGrid choices = kit.choiceGrid(120, 8);
        for (int index = 1; index <= 5; index++) {
            choices.addActor(kit.button(style, null, text("UiLibrary.gdx.choice", index), null));
        }
        choices.fit(width, 200);
        return choices;
    }

    private SplitPane split(boolean token) {
        Color ink = token ? UiTheme.tokenColor("text.primary") : UiTheme.TEXT;
        Table top = new Table();
        top.add(kit.label(text("UiLibrary.gdx.splitTop"), "hud-body", token ? 13 : 12.5f, ink));
        Table bottom = new Table();
        bottom.add(kit.label(text("UiLibrary.gdx.splitBottom"), "hud-body", token ? 13 : 12.5f, ink));
        return new SplitPane(top, bottom, true, kit.skin, "hud-split");
    }

    private void containers(Table page) {
        heading(page, "UiLibrary.gdx.panels");
        compare(page, "panel + header + footer", new Container<>(listPanel(false)).width(320),
              new Container<>(listPanel(true)).width(320));
        Table foe = kit.panel();
        foe.setBackground(kit.skin.getDrawable("panel-foe"));
        foe.add(kit.header(text("UiLibrary.gdx.foePanel"), null)).growX().row();
        foe.add(kit.label(text("UiLibrary.sample.row"), "hud-body", 12.5f, UiTheme.TEXT)).left().pad(0, 14, 12, 14);
        Table tokenFoe = kit.panel(true);
        tokenFoe.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_PANEL_DANGER));
        Table foeHeader = kit.header(text("UiLibrary.gdx.foePanel"), null, true);
        foeHeader.padBottom(0);
        Table foeTitle = new Table();
        foeTitle.add(kit.icon("error", tokens.size().get("icon.md"), UiTheme.tokenColor("status.danger")))
              .padRight(tokens.space().get("sm"));
        foeTitle.add(foeHeader).growX();
        tokenFoe.add(foeTitle).growX().padBottom(tokens.space().get("md")).row();
        tokenFoe.add(kit.typeLabel(text("UiLibrary.sample.row"), "body", "text.primary")).left();
        compare(page, "panel-foe", new Container<>(foe).width(320), new Container<>(tokenFoe).width(320));
        Table rails = new Table();
        rails.setBackground(kit.skin.getDrawable("panel-rails"));
        rails.add(kit.label(text("UiLibrary.gdx.tacticalView"), "hud-medium", 12, UiTheme.TEXT));
        Table frame = new Table();
        frame.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_RAILS));
        frame.add(kit.typeLabel(text("UiLibrary.gdx.tacticalView"), "body", "text.primary"));
        compare(page, "panel-rails", rails, topLeft(frame));
        compare(page, "rule", new Container<>(new Image(kit.skin.getDrawable("rule"))).size(320, 1),
              new Container<>(new Image(kit.skin.getDrawable("token-divider"))).size(320, 1));
        heading(page, "UiLibrary.gdx.overlays");
        compare(page, "UiPopover (panel-pop)", popover(false), popover(true));
        compare(page, "UiKit.dialog", new Container<>(dialog(false, () -> { })).width(400),
              new Container<>(dialog(true, () -> { })).width(MIGRATED_WIDTH));
        UiButton openModal = kit.button("hud", null, text("UiLibrary.gdx.openModal"), null);
        UiKit.onChange(openModal, () -> {
            modal.open(true);
            modal.focus();
        });
        UiButton openTokenModal = kit.button("button-secondary", null, text("UiLibrary.gdx.openModal"), null);
        UiKit.onChange(openTokenModal, () -> {
            tokenModal.open(true);
            tokenModal.focus();
        });
        compare(page, "UiModal", openModal, topLeft(openTokenModal));
        UiButton ask = kit.button("hud", null, text("UiLibrary.gdx.askConfirm"), null);
        UiKit.onChange(ask, () -> confirmation.toggle(ask, () -> { }));
        UiButton tokenAsk = kit.button("button-secondary", null, text("UiLibrary.gdx.askConfirm"), null);
        UiKit.onChange(tokenAsk, () -> tokenConfirmation.toggle(tokenAsk, () -> { }));
        compare(page, "UiConfirmation", ask, topLeft(tokenAsk));
        UiButton tipped = kit.button("hud", "info", text("UiLibrary.gdx.hoverTip"), null);
        kit.tip(tipped).getActor().setText(text("UiLibrary.gdx.tipText"));
        UiButton tokenTipped = kit.button("button-secondary", "info", text("UiLibrary.gdx.hoverTip"), null);
        kit.tip(tokenTipped, true).getActor().setText(text("UiLibrary.gdx.tokenTipText"));
        compare(page, "UiKit.tip", tipped, topLeft(tokenTipped));
        heading(page, "UiLibrary.gdx.text");
        UiFlow flow = new UiFlow(kit, 12.5f, 17);
        flow.run(text("UiLibrary.gdx.flowLead"), "hud-body", UiTheme.MUTED)
              .run(text("UiLibrary.gdx.flowValue"), "hud-button", UiTheme.TEXT)
              .run(text("UiLibrary.gdx.flowRest"), "hud-body", UiTheme.MUTED);
        UiFlow tokenFlow = new UiFlow(kit, 13, 17.5f);
        tokenFlow.run(text("UiLibrary.gdx.flowLead"), "hud-body", UiTheme.tokenColor("text.secondary"))
              .run(text("UiLibrary.gdx.flowValue"), "hud-medium", UiTheme.tokenColor("text.primary"))
              .run(text("UiLibrary.gdx.flowRest"), "hud-body", UiTheme.tokenColor("text.secondary"));
        compare(page, "UiFlow", new Container<>(flow).width(360), new Container<>(tokenFlow).width(360));
    }

    private void status(Table page) {
        heading(page, "UiLibrary.gdx.chipsBadges");
        compare(page, "UiKit.chip", chips(false), chips(true));
        Table marks = new Table();
        marks.defaults().padRight(12);
        marks.add(new Container<>(kit.label("3", "hud-caption", 10, UiTheme.FILL_INK))
              .background(kit.skin.getDrawable("badge")));
        marks.add(new Image(kit.skin.getDrawable("dot")));
        Table tokenMarks = new Table();
        tokenMarks.defaults().padRight(20);
        tokenMarks.add(kit.button("button-secondary", "report", text("UiLibrary.gdx.sampleLog"), null).badge("3"));
        tokenMarks.add(kit.button("button-icon", "chat", null, null).badge("128"));
        tokenMarks.add(kit.button("button-icon", "report", null, null).dot(true));
        compare(page, "badge, dot", marks, tokenMarks);
        heading(page, "UiLibrary.gdx.meters");
        compare(page, "UiKit.meter", meters(false, false), meters(false, true));
        compare(page, "UiKit.meter (compact)", meters(true, false), meters(true, true));
        compare(page, "UiKit.progress", new Container<>(kit.progress(text("UiLibrary.gdx.progress")).set(.62f))
              .width(320), null);
        compare(page, "GpuHudKit.heatBar", heatBars(false), heatBars(true));
        heading(page, "UiLibrary.gdx.toasts");
        compare(page, "GpuToastStack", toasts(false), toasts(true));
    }

    /** The four chip tones, in hud-v3's look or Token v0's. */
    private Table chips(boolean token) {
        Table chips = new Table();
        chips.defaults().padRight(8);
        chips.add(kit.chip(text("UiLibrary.status.info"), Tone.NORMAL, token));
        chips.add(kit.chip(text("UiLibrary.status.success"), Tone.OK, token));
        chips.add(kit.chip(text("UiLibrary.status.warning"), Tone.WARN, token));
        chips.add(kit.chip(text("UiLibrary.status.danger"), Tone.BAD, token));
        return chips;
    }

    /** Armor, structure and (unless compact) heat meters in a panel, in hud-v3's look or Token v0's. */
    private Container<Table> meters(boolean compact, boolean token) {
        Table meters = kit.panel(token);
        if (!token) {
            meters.pad(compact ? 10 : 14, compact ? 14 : 16, compact ? 10 : 14, compact ? 14 : 16);
        }
        meters.defaults().growX().uniformX().padRight(12);
        Color armor = token ? UiTheme.tokenColor("status.success") : UiTheme.MINT;
        Color structure = token ? UiTheme.tokenColor("status.warning") : UiTheme.AMBER;
        meters.add(kit.meter(text("UiLibrary.gdx.armor"), compact, token).set("91%", .91f, armor));
        meters.add(kit.meter(text("UiLibrary.gdx.structure"), compact, token).set("40%", .4f, structure));
        if (!compact) {
            Color heat = token ? UiTheme.tokenColor("status.danger") : UiTheme.CORAL;
            meters.add(kit.meter(text("UiLibrary.gdx.heat"), false, token).set("12", .4f, heat));
        }
        return new Container<>(meters).width(compact ? 320 : token ? MIGRATED_WIDTH : 420);
    }

    /** The continuous and the segmented heat bar, in hud-v3's colors or Token v0's. */
    private Table heatBars(boolean token) {
        Table heat = new Table();
        heat.defaults().left().width(300).height(8).padBottom(12);
        heat.add(hud.heatBar(false, token).set(12, 18, 30, List.of(8, 13, 17, 24))).row();
        heat.add(hud.heatBar(true, token).set(12, 18, 30, List.of(8, 13, 17, 24)));
        return heat;
    }

    /** A toast of each level, settled, in hud-v3's look or Token v0's card. */
    private Table toasts(boolean token) {
        GpuToastStack stack = new GpuToastStack(hud, null, null, token);
        Table toasts = new Table();
        toasts.defaults().left().padBottom(token ? 8 : 6);
        long id = 0;
        for (ToastLevel level : ToastLevel.values()) {
            String key = "UiLibrary.gdx.toast." + level.name().toLowerCase(Locale.ROOT);
            Actor toast = stack.toast(new GpuToasts.Toast(id++, level, text(key),
                  level == ToastLevel.SUCCESS ? sprite : null, 1000));
            toast.clearActions();
            toast.getColor().a = 1;
            toasts.add(toast).row();
        }
        return toasts;
    }

    private void hudParts(Table page) {
        Label note = kit.label(text("UiLibrary.gdx.hudNote"), "hud-small", 11.5f, UiTheme.MUTED);
        note.setWrap(true);
        page.add(note).colspan(3).width(NAME_WIDTH + TODAY_WIDTH + MIGRATED_WIDTH).padTop(10).row();
        heading(page, "UiLibrary.gdx.targets");
        compare(page, "GpuHudKit.letter", letters(false), letters(true));
        Table pills = new Table();
        pills.defaults().padRight(8);
        pills.add(pill('A', "pill", UiTheme.TEXT));
        pills.add(pill('B', "pill-on", UiTheme.FILL_INK));
        Table tokenPills = new Table();
        tokenPills.defaults().padRight(8);
        tokenPills.add(tokenPill('A', ""));
        tokenPills.add(tokenPill('B', "-over"));
        tokenPills.add(tokenPill('C', "-on"));
        compare(page, "pill, pill-on", pills, tokenPills);
        heading(page, "UiLibrary.gdx.units");
        compare(page, "GpuHudKit.unitRow", unitRows(false), unitRows(true));
        compare(page, "GpuHudKit.damageTile", damageTiles(false), damageTiles(true));
        heading(page, "UiLibrary.gdx.hudBoxes");
        Table discs = new Table();
        discs.defaults().padRight(8);
        discs.add(disc("disc", "3", UiTheme.MINT));
        discs.add(disc("disc-foe", "4", UiTheme.CORAL));
        Table tokenDiscs = new Table();
        tokenDiscs.defaults().padRight(8);
        tokenDiscs.add(tokenDisc(GpuBoardSkin.TOKEN_DISC, "3"));
        tokenDiscs.add(tokenDisc(GpuBoardSkin.TOKEN_DISC + "-foe", "4"));
        compare(page, "disc, disc-foe", discs, tokenDiscs);
        Container<Label> waiting = new Container<>(kit.label(text("UiLibrary.gdx.waiting"), "hud-small", 11.5f,
              UiTheme.MUTED));
        waiting.setBackground(kit.skin.getDrawable("waiting"));
        waiting.pad(0, 14, 0, 14);
        Container<Label> tokenWaiting = new Container<>(kit.typeLabel(text("UiLibrary.gdx.waiting"), "body",
              "text.secondary"));
        tokenWaiting.setBackground(kit.skin.getDrawable("token-waiting"));
        compare(page, "waiting", waiting, tokenWaiting);
    }

    /** Six target letters in pill size and three in card size. */
    private Table letters(boolean token) {
        Table letters = new Table();
        letters.defaults().padRight(8);
        for (char letter = 'A'; letter <= 'F'; letter++) {
            letters.add(hud.letter(letter, false, token));
        }
        for (char letter = 'A'; letter <= 'C'; letter++) {
            letters.add(hud.letter(letter, true, token));
        }
        return letters;
    }

    /** A friend, the same friend selected, an inspected foe and a sensor contact. */
    private Table unitRows(boolean token) {
        Table rows = new Table();
        rows.defaults().width(340).padBottom(6);
        rows.add(hud.unitRow(40, 34, token).set(sprite, GpuHudKit.FRIEND_SPRITE, text("UiLibrary.gdx.unitModel"),
              text("UiLibrary.gdx.unitName"), text("UiLibrary.gdx.unitStatus"), Tone.NORMAL)).row();
        rows.add(hud.unitRow(40, 34, token).set(sprite, GpuHudKit.FRIEND_SPRITE, text("UiLibrary.gdx.unitModel"),
              text("UiLibrary.gdx.unitName"), text("UiLibrary.gdx.unitStatus"), Tone.NORMAL).pressed(true)).row();
        rows.add(hud.unitRow(40, 34, token).set(sprite, UiTheme.CORAL, text("UiLibrary.gdx.foeModel"),
              text("UiLibrary.gdx.foeName"), text("UiLibrary.gdx.foeStatus"), Tone.BAD).inspected(true)).row();
        rows.add(hud.unitRow(40, 34, token).set(null, UiTheme.BLIP, text("GpuBoard.hud.common.unidentified"),
              text("GpuBoard.hud.common.sensorContact"), text("UiLibrary.gdx.contactStatus"), Tone.WARN));
        return rows;
    }

    /** The light, heavy and crippled damage tiles; their colours are game data in both looks. */
    private Table damageTiles(boolean token) {
        Table damage = new Table();
        damage.defaults().left().padBottom(4);
        damage.add(damageLabel(UiTheme.MINT, "UiLibrary.gdx.damageLight", token)).row();
        damage.add(damageLabel(UiTheme.AMBER, "UiLibrary.gdx.damageHeavy", token)).row();
        damage.add(damageLabel(Color.BLACK, "UiLibrary.gdx.damageCrippled", token));
        return damage;
    }

    // ---- Samples

    private UiButton sampleButton(String style) {
        return switch (style) {
            case "hud-icon" -> kit.button(style, "search", null, null);
            case "hud-delete-icon" -> kit.button(style, "close", null, null);
            case "hud-utility", "hud-utility-row" -> kit.button(style, "map", text("UiLibrary.gdx.sampleMap"), null);
            default -> kit.button(style, null, text("UiLibrary.gdx.sampleAction"), null);
        };
    }

    /** A button's five states side by side: at rest, hovered, held, pressed and disabled. */
    private static Table buttonStates(Supplier<UiButton> sample, float width, float gap) {
        return buttonStates(sample, sample, width, gap);
    }

    /** As {@link #buttonStates(Supplier, float, float)}, with the pressed state drawn by its own sample. */
    private static Table buttonStates(Supplier<UiButton> sample, Supplier<UiButton> pressedSample, float width,
          float gap) {
        Table states = new Table();
        states.defaults().left().minWidth(width).padRight(gap);
        for (int state = 0; state < 5; state++) {
            UiButton button = state == 3 ? pressedSample.get() : sample.get();
            switch (state) {
                case 1 -> button.hovered(true);
                case 2 -> button.held(true);
                case 3 -> button.pressed(true);
                case 4 -> button.setDisabled(true);
                default -> { }
            }
            states.add(new Container<>(button).left());
        }
        return states;
    }

    /** The token button a hud-v3 style migrates to, in its five states, over the token style's name. */
    private Table migratedButton(String style) {
        String token = MIGRATED_BUTTONS.get(style);
        if (token == null) {
            return null;
        }
        Table migrated = new Table();
        migrated.left().top();
        // Secondary has no selected look: a button the game holds pressed becomes a toggle.
        String pressed = token.replace("button-secondary", "button-toggle");
        migrated.add(buttonStates(() -> tokenButton(style, token), () -> tokenButton(style, pressed), 70, 6))
              .left().row();
        String caption = pressed.equals(token) ? token : text("UiLibrary.gdx.heldPressed", token, pressed);
        migrated.add(kit.label(caption, "hud-small", 11, UiTheme.MUTED)).left().padTop(6);
        return migrated;
    }

    private UiButton tokenButton(String style, String token) {
        return switch (style) {
            case "hud-icon" -> kit.button(token, "search", null, null);
            case "hud-delete-icon" -> kit.button(token, "close", null, null);
            case "hud-utility-row" -> kit.button(token, "map", text("UiLibrary.gdx.sampleMap"), null);
            case "hud-utility" -> {
                // The icon-above-label layout stays a HUD composition on top of the special kind.
                UiButton button = kit.button(token, "map", null, null);
                button.row();
                button.setText(text("UiLibrary.gdx.sampleMap"));
                button.add(button.getLabel()).padTop(4);
                yield button;
            }
            default -> kit.button(token, null, text("UiLibrary.gdx.sampleAction"), null);
        };
    }

    private Table menuPanel() {
        Table popover = new Table();
        popover.setBackground(kit.skin.getDrawable("panel-pop"));
        popover.pad(8, 2, 8, 2);
        popover.add(menuList("hud-menu-row")).growX();
        return popover;
    }

    /** The menu list in the Token v0 look: the select's popup frame, whose padding insets the rows' wash. */
    private Table tokenMenuPanel() {
        Table popover = new Table();
        popover.setBackground(kit.skin.getDrawable("token-popup"));
        popover.add(menuList(UiTheme.TOKEN_MENU_ROW)).growX();
        return popover;
    }

    private UiMenuList menuList() {
        return menuList("hud-menu-row");
    }

    private UiMenuList menuList(String style) {
        UiMenuList list = new UiMenuList(kit, style);
        list.item(text("UiLibrary.gdx.menuShortcut"), "Ctrl+K", false, false, true, () -> { });
        list.item(text("UiLibrary.gdx.menuChecked"), null, true, false, true, () -> { });
        list.separator();
        list.item(text("UiLibrary.gdx.menuGroup"), null, false, true, true, () -> { });
        list.item(text("UiLibrary.gdx.menuDisabled"), null, false, false, false, () -> { });
        return list;
    }

    private UiPopover popover(boolean token) {
        UiPopover popover = new UiPopover(kit, () -> { }, token ? UiTheme.TOKEN : null);
        popover.header(text("UiLibrary.gdx.popoverTitle"), text("UiLibrary.gdx.popoverDetail"));
        popover.content(token ? menuList(UiTheme.TOKEN_MENU_ROW) : menuList());
        popover.setVisible(true);
        return popover;
    }

    private UiTree tree(String style) {
        UiTree tree = new UiTree(kit, style);
        tree.nodes(List.of(
              new UiTree.Node("display", text("UiLibrary.gdx.treeDisplay")).icon("tune").children(List.of(
                    new UiTree.Node("display.labels", text("UiLibrary.gdx.treeLabels")),
                    new UiTree.Node("display.shadows", text("UiLibrary.gdx.treeShadows"))
                          .detail(text("UiLibrary.gdx.treeShadowsDetail")))),
              new UiTree.Node("board", text("UiLibrary.gdx.treeBoard")).icon("map").children(List.of(
                    new UiTree.Node("board.terrain", text("UiLibrary.gdx.treeTerrain")))),
              new UiTree.Node("units", text("UiLibrary.gdx.treeUnits")).icon("unit")));
        tree.expand("display", true);
        tree.select("display.labels");
        return tree;
    }

    /** The card grid; in the token look its captions are overlines and its cards plain token panels. */
    private ScrollPane cardGrid(boolean token) {
        UiCardGrid grid = new UiCardGrid(3, 56, 24, 8);
        grid.items(List.of(new UiCardGrid.Section(kit.caption(text("UiLibrary.gdx.cardsLight"), token), 4),
              new UiCardGrid.Section(kit.caption(text("UiLibrary.gdx.cardsHeavy"), token), 5)), index -> {
                  Table card;
                  if (token) {
                      card = new Table();
                      card.setBackground(kit.skin.getDrawable("token-card"));
                      card.add(kit.label(text("UiLibrary.gdx.card", index + 1), "hud-medium", 12.5f,
                            UiTheme.tokenColor("text.primary"))).expand();
                  } else {
                      card = kit.panel();
                      card.add(kit.label(text("UiLibrary.gdx.card", index + 1), "hud-medium", 12, UiTheme.TEXT))
                            .expand();
                  }
                  return card;
              });
        return kit.scrollList(grid);
    }

    /** A panel with a header, three rows and a footer; in the token look, token rows in the token panel. */
    private Table listPanel(boolean token) {
        Table panel = kit.panel(token);
        panel.add(kit.header(text("UiLibrary.gdx.panel"), "3", token, kit.closeButton(() -> { }, token))).growX()
              .row();
        Table list = new Table();
        list.top();
        for (int index = 0; index < 3; index++) {
            UiButton row = kit.button(token ? UiTheme.TOKEN_ROW : "hud-row", null,
                  text(index == 1 ? "UiLibrary.sample.selectedRow" : "UiLibrary.sample.row"), null);
            row.getLabel().setAlignment(Align.left);
            row.getCell(row.getLabel()).growX();
            row.pressed(index == 1);
            if (token) {
                list.add(row).growX().row();
            } else {
                list.add(row).growX().pad(0, 10, 6, 10).row();
            }
        }
        panel.add(list).growX().row();
        Table foot = kit.footer(panel, token);
        if (token) {
            foot.add(kit.typeLabel(text("UiLibrary.sample.detail"), "small", "text.secondary")).growX().left();
            foot.add(kit.button("button-secondary" + UiTheme.COMPACT, null, text("UiLibrary.gdx.sampleAction"),
                  null));
        } else {
            foot.add(kit.label(text("UiLibrary.sample.detail"), "hud-small", 11.5f, UiTheme.MUTED)).growX().left();
            foot.add(kit.button("hud-mini", null, text("UiLibrary.gdx.sampleAction"), null));
        }
        return panel;
    }

    /** The dialog sample; in the token look its footer centres equal buttons, the main (danger) one first. */
    private Table dialog(boolean token, Runnable close) {
        Table dialog = kit.dialog(text("UiLibrary.gdx.dialogTitle"), close, token);
        if (!token) {
            dialog.add(dialogBody()).growX();
            return dialog;
        }
        Label message = kit.typeLabel(text("UiLibrary.gdx.dialogBody"), "body", "text.primary");
        message.setWrap(true);
        dialog.add(message).growX().row();
        UiButton remove = kit.button("button-danger", null, text("UiLibrary.gdx.confirmYes"), null);
        UiButton cancel = kit.button("button-secondary", null, text("UiLibrary.gdx.confirmNo"), null);
        float width = Math.max(BUTTON_MIN_WIDTH, Math.max(remove.getPrefWidth(), cancel.getPrefWidth()));
        Table footer = new Table();
        footer.add(remove).width(width);
        footer.add(cancel).width(width).padLeft(tokens.space().get("md"));
        dialog.add(footer).padTop(tokens.space().get("xxl"));
        return dialog;
    }

    private Table dialogBody() {
        Table body = new Table();
        Label message = kit.label(text("UiLibrary.gdx.dialogBody"), "hud-body", 12.5f, UiTheme.TEXT);
        message.setWrap(true);
        body.add(message).growX().colspan(3).padBottom(14).row();
        body.add().expandX();
        body.add(kit.button("hud-mini", null, text("UiLibrary.gdx.confirmNo"), null)).padRight(8);
        body.add(kit.button("hud-main", null, text("UiLibrary.gdx.confirmYes"), null));
        return body;
    }

    private UiModal modal(boolean token) {
        Runnable close = () -> (token ? tokenModal : modal).open(false);
        UiModal layer = new UiModal(kit, close);
        layer.setFillParent(true);
        if (token) {
            layer.fadeIn(tokens.duration("fast") / 1000f);
            layer.add(dialog(true, close)).width(tokens.size().get("dialog.sm"));
        } else {
            layer.add(dialog(false, close)).width(400);
        }
        return layer;
    }

    private Table pill(char letter, String background, Color ink) {
        Table pill = new Table();
        pill.setBackground(kit.skin.getDrawable(background));
        pill.add(hud.letter(letter, false)).padRight(6);
        pill.add(kit.label(text("UiLibrary.gdx.foeName"), "hud-medium", 12, ink));
        return pill;
    }

    /** A token target pill: the filter chip at rest, hovered ("-over") or on ("-on", with its check). */
    private Table tokenPill(char letter, String state) {
        boolean on = "-on".equals(state);
        Table pill = new Table();
        pill.setBackground(kit.skin.getDrawable(GpuBoardSkin.TOKEN_PILL + state));
        if (on) {
            pill.add(kit.icon("check", DesignTokens.shipped().size().get("icon.sm"), UiTheme.tokenColor(
                  "text.onInverse"))).padRight(DesignTokens.shipped().space().get("xs"));
        }
        pill.add(hud.letter(letter, false, true)).padRight(DesignTokens.shipped().space().get("sm"));
        pill.add(kit.typeLabel(text("UiLibrary.gdx.foeName"), "label", state.isEmpty() ? "text.secondary"
              : on ? "text.onInverse" : "text.primary"));
        return pill;
    }

    private Label damageLabel(Color tile, String key, boolean token) {
        Label label = token ? kit.typeLabel(text(key), "small", "text.primary")
              : kit.label(text(key), "hud-small", 11.5f, UiTheme.TEXT);
        Label.LabelStyle style = new Label.LabelStyle(label.getStyle());
        style.background = hud.damageTile(tile, token);
        label.setStyle(style);
        return label;
    }

    private Container<Label> disc(String background, String number, Color ink) {
        Label label = kit.label(number, "hud-medium", 12, ink);
        label.setAlignment(Align.center);
        Container<Label> disc = new Container<>(label).size(28);
        disc.setBackground(kit.skin.getDrawable(background));
        return disc;
    }

    /** A token log disc: control.sm across, the number in label/text.primary. */
    private Container<Label> tokenDisc(String background, String number) {
        Label label = kit.typeLabel(number, "label", "text.primary");
        label.setAlignment(Align.center);
        Container<Label> disc = new Container<>(label).size(DesignTokens.shipped().size().get("control.sm"));
        disc.setBackground(kit.skin.getDrawable(background));
        return disc;
    }

    // ---- Layout

    private Label columnTitle(String key) {
        return kit.label(UiTheme.upper(text(key)), "hud-caption", 10.5f, UiTheme.MUTED);
    }

    private void heading(Table page, String key) {
        page.add(kit.label(text(key), "hud-title", 15, UiTheme.TEXT)).colspan(3).padTop(22).padBottom(8).row();
    }

    /** One element: its code name, today's look and its migrated look, or a placeholder while it has none. */
    private void compare(Table page, String name, Actor today, Actor migrated) {
        page.add(new Image(kit.skin.getDrawable("rule"))).colspan(3).growX().height(1).padBottom(10).row();
        Label label = kit.label(name, "hud-medium", 12, UiTheme.ACCENT);
        label.setWrap(true);
        page.add(label).width(NAME_WIDTH).padRight(COLUMN_GAP).padBottom(12);
        page.add(topLeft(today)).width(TODAY_WIDTH).padRight(COLUMN_GAP).padBottom(12);
        page.add(topLeft(migrated != null ? migrated : kit.label(text("UiLibrary.gdx.notMigrated"), "hud-small", 11.5f,
              UiTheme.DISABLED))).width(MIGRATED_WIDTH).padBottom(12).row();
    }

    /** Holds a sample at the top left of its column; a filled cell would center the sample's own content. */
    private static Table topLeft(Actor sample) {
        Table holder = new Table();
        holder.left().top();
        holder.add(sample);
        return holder;
    }

    private Table stateRow(float width, float gap, String... states) {
        Table row = new Table();
        row.defaults().left().width(width).padRight(gap);
        for (String state : states) {
            row.add(kit.label(state, "hud-small", 11, UiTheme.MUTED));
        }
        return row;
    }

    /** A token's swatch as the gallery draws it on a panel, with its name and dark value. */
    private Table tokenSwatch(String name) {
        ColorToken token = tokens.colors().get(name);
        int panel = tokens.color("surface.panel", Mode.DARK);
        int value = token.overlay() != null ? token.overlay() : token.dark();
        // Translucent roles show as they sit on a panel.
        int shown = ColorMath.alpha(value) == 0xFF ? value : ColorMath.over(value, panel);
        String hex = ColorMath.hex(value) + (token.overlay() != null ? "  " + text("UiLibrary.gdx.overlay") : "");
        return swatch(token.name(), hex, color(shown));
    }

    /** A color chip with its name, when it has one, and its value beside it. */
    private Table swatch(String name, String detail, Color fill) {
        Table cell = new Table();
        cell.left().top();
        Image chip = new Image(kit.skin.newDrawable("white", fill));
        // A subtle outline keeps colors that sit close to the background visible.
        Table outline = new Table();
        outline.setBackground(kit.skin.newDrawable("white", color(tokens.color("border.subtle", Mode.DARK))));
        outline.add(chip).size(110, 26);
        outline.pad(1);
        cell.add(outline).padRight(12);
        Table labels = new Table();
        labels.defaults().left();
        if (name != null) {
            labels.add(kit.label(name, "hud-medium", 12, UiTheme.TEXT)).row();
        }
        labels.add(kit.label(detail, "hud-small", 10.5f, UiTheme.MUTED));
        cell.add(labels).left();
        return cell;
    }

    private static UiTheme.HudFont typeStyle(String name) {
        for (UiTheme.HudFont type : UiTheme.TYPE_STYLES) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException(name);
    }

    /**
     * A text role's spec over a sample line as the kit draws the role in the gallery's presentation mode: upper case and
     * condensed where the role is.
     */
    private Table roleSample(String role, String line, float width) {
        DesignTokens.TypeStyle style = tokens.roleStyle(role);
        boolean condensed = kit.condensed(role);
        int instance = condensed ? UiTheme.CONDENSED_MEDIUM : typeStyle(style.name()).instance();
        Table sample = new Table();
        sample.left().top();
        sample.add(kit.label(String.format(Locale.ROOT, "%s (%s)  %s / %s", role, style.name(), trim(style.size()),
              weight(instance)), "hud-small", 11, UiTheme.MUTED)).left().row();
        Label label = kit.roleTypeLabel(role, line, "text.primary");
        label.setEllipsis(true);
        sample.add(label).width(width).left().padTop(2);
        return sample;
    }

    /** A font's spec over a sample line drawn in its Label style, the line cased as the caller shows it. */
    private Table fontSample(String name, UiTheme.HudFont font, String style, String line, float width) {
        Table sample = new Table();
        sample.left().top();
        sample.add(kit.label(String.format(Locale.ROOT, "%s  %s / %s", name, trim(font.size()),
              weight(font.instance())), "hud-small", 11, UiTheme.MUTED)).left().row();
        Label label = new Label(line, kit.skin, style);
        label.setEllipsis(true);
        sample.add(label).width(width).left().padTop(2);
        return sample;
    }
    // ---- Helpers

    private static String text(String key, Object... arguments) {
        return arguments.length == 0 ? Messages.getString(key) : Messages.getString(key, arguments);
    }

    /** A libGDX color from a design token's ARGB value. */
    private static Color color(int argb) {
        return new Color((argb << 8) | (argb >>> 24));
    }

    private static String weight(int instance) {
        return switch (instance) {
            case UiTheme.REGULAR -> "400";
            case UiTheme.MEDIUM -> "500";
            case UiTheme.CONDENSED_MEDIUM -> "condensed 500";
            case UiTheme.CONDENSED_SEMIBOLD -> "condensed 600";
            case UiTheme.CONDENSED_BOLD -> "condensed 700";
            default -> "instance " + instance;
        };
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }
}
