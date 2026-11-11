/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.MMConstants;
import megamek.Version;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.SplashArt;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Action;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.design.swing.TokenFonts;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiListPopup;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiOptionBar;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.settings.ClientSettingsForm;
import megamek.client.ui.util.UIUtil;
import megamek.common.preference.PreferenceManager;
import megamek.logging.MMLogger;

/**
 * The start screen: a greeting typed over a dark field, the art revealed over it tile by tile, then the main menu
 * sliding in at the right with the version at the top left and a tip at the bottom left. Pages such as Host and
 * Connect replace the menu in the same column; Esc goes back. The art and its overlays are drawn once with Java2D and
 * uploaded; the GPU only animates on top of them. The content's {@link StartScreenContent.Ambience} picks the reveal,
 * the grid and the ambience: hexes and rising embers for a battle; for a command post, a star map the view dives
 * through before the art boots up as a hologram, then light shafts, live screens and drifting dust
 * ({@link StartCommandEffects}). Once
 * the intro has played, only the slow ambience moves, at 30 frames a second.
 */
final class GpuStartScreen extends ApplicationAdapter {
    static final MMLogger LOGGER = MMLogger.create(GpuStartScreen.class);
    private static final double TIP_SECONDS = 10;
    private static final int EMBERS = 140;
    private static final int DUST = 110;
    /** A command post's light and screens come up over this many seconds once the art is in. */
    private static final double AMBIENCE_RAMP = 1.5;
    private static final float TIP_WIDTH = 360;
    private static final float NOTES_WIDTH = 320;
    /** The narrowest the column gets on the pages that list or describe something. */
    private static final float WIDE_COLUMN = 340;
    /** The least height a list or description keeps when the window is short. */
    private static final float MIN_FILLER = 96;
    static final float ROLE_WIDTH = 130;
    private static boolean probed;

    /** A tile of the reveal: its centre and when, as a fraction of the reveal, it opens. */
    record Tile(double x, double y, double at) { }

    /** A rendered line of greeting text, the pixel width of each prefix, its ascent and an M's width. */
    private record TextLine(Texture texture, int[] widths, int ascent, int charWidth) {
        int width() {
            return widths[widths.length - 1];
        }

        void dispose() {
            texture.dispose();
        }
    }

    enum Page { MAIN, HELP, HOST, CONNECT, LOAD, RESUME, SCENARIOS, BRIEFING, UNIT_CACHE }

    final GpuStartWindow window;
    private final StartScreenContent content;
    /** Whether the ambience is a command post's rather than a battle's. */
    private final boolean command;
    /** A command post's flipboard, light and screens; null for a battle or when its shaders failed. */
    private StartCommandEffects effects;
    /** The content's star chart for the star map intro, once it has come. */
    private volatile StartScreenContent.StarChart starChart;
    private final GUIPreferences preferences;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final java.awt.Color base = awtColor("surface.base");
    private final java.awt.Color mint = awtColor("accent.primary");
    private final java.awt.Color text = awtColor("text.primary");
    private final java.awt.Color muted = awtColor("text.muted");
    private final java.awt.Color strong = awtColor("border.strong");
    private final Random random = new Random(7);
    private final List<float[]> particles = new ArrayList<>();
    private final Matrix4 pixels = new Matrix4();
    final List<Actor> column = new ArrayList<>();
    final List<Actor> focusable = new ArrayList<>();
    final StartServerInputs inputs = new StartServerInputs(this);
    final StartHostPage hostPage = new StartHostPage(this);
    final StartConnectPage connectPage = new StartConnectPage(this);
    final StartLoadPage loadPage = new StartLoadPage(this);
    final StartScenarioPage scenarioPage = new StartScenarioPage(this);
    final StartBriefingPage briefingPage = new StartBriefingPage(this);
    final StartUnitCachePage unitCachePage = new StartUnitCachePage(this);
    /** The main menu's Unit cache button, whose detail follows the cache; null on other pages. */
    private UiButton unitCacheItem;
    private final List<String> tips;
    final boolean motion;
    /** The developer switch's page and shot, or null; only the first start window of a run reads it. */
    private final StartScreenProbe probe;
    private boolean shotSaved;
    /** Whether the developer switch's {@code briefing+play} has pressed the briefing's Start. */
    private boolean playPressed;
    /** Whether the developer switch has opened the briefing's dialog it asks for. */
    private boolean probeDialogOpened;
    /** The welcome while it shows, or null. */
    private StartWelcome welcome;
    /** The screen's libGDX dialogs, such as the briefing's camo, rules and conditions; null before it is made. */
    LobbyNativeDialogs dialogs;

    private BufferedImage art;
    GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    UiKit kit;
    Stage stage;
    private boolean inputBlocked;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private float scale = 1;
    private int bw;
    private int bh;
    private float w;
    private float h;
    private Texture backdrop;
    private TextLine line1;
    private TextLine line2;
    private List<Tile> tiles = new ArrayList<>();
    /** A hex's radius, or half a square tile's side. */
    private double tileR;
    private Image logo;
    private Texture logoTexture;
    private Table bottomRow;
    private Table corner;
    private Table tipCard;
    private Label tipText;
    private Label tipAuthor;
    private int tipIndex;
    private double tipShownAt;
    Page page = Page.MAIN;
    /** When the page last changed, in seconds on the clock; the first page slides in with the intro. */
    private double pageShownAt = -1;
    private long start;
    private boolean skipped;
    private boolean settledRate;
    /** Counts pages shown and reads asked for; a background read whose count is no longer current is dropped. */
    int generation;
    /** Set once the screen is disposed, so results that arrive after it are dropped. GPU thread only. */
    boolean disposed;
    private UiButton pageBackButton;
    /** Restores the start controls when starting ends; null while no game is starting. */
    private Runnable restoreStart;

    GpuStartScreen(GpuStartWindow window, boolean greet, GUIPreferences preferences) {
        this.window = window;
        this.preferences = preferences;
        content = window.content();
        command = content.ambience() == StartScreenContent.Ambience.COMMAND;
        motion = !preferences.getReduceMotion();
        skipped = !greet || !motion;
        tips = content.tips();
        // The developer switch opens MegaMek's own pages
        probe = content.gamePages() ? takeProbe() : null;
        if (probe != null && probe.page() != null) {
            skipped = true;
        }
    }

    private static synchronized StartScreenProbe takeProbe() {
        if (probed) {
            return null;
        }
        probed = true;
        return StartScreenProbe.fromEnvironment();
    }

    private java.awt.Color awtColor(String name) {
        return new java.awt.Color(tokens.color(name, Mode.DARK), true);
    }

    /** Seconds since the first frame. */
    private double clock() {
        return (System.nanoTime() - start) / 1e9;
    }

    /** The intro's time: past its end once skipped. */
    private double introTime() {
        return skipped ? StartTimeline.INTRO_END + 1 : clock();
    }

    /** GPU thread: ignores the mouse and keyboard while a modal Swing form is open, as a disabled window would. */
    void setInputBlocked(boolean blocked) {
        inputBlocked = blocked;
        if (stage == null) {
            return;
        }
        if (blocked) {
            stage.cancelTouchFocus();
        }
        Gdx.input.setInputProcessor(blocked ? null : stage);
    }

    @Override
    public void create() {
        window.started();
        boardSkin = GpuBoardSkin.forScene();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(inputBlocked ? null : stage);
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        File artFile = content.art();
        art = artFile == null ? null : readImage(artFile);
        BufferedImage logoImage = content.logo();
        if (logoImage != null) {
            logoTexture = texture(logoImage, true);
            logoTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            logo = new Image(logoTexture);
            stage.addActor(logo);
        }
        for (int i = 0; i < (command ? DUST : EMBERS); i++) {
            particles.add(newParticle(true));
        }
        if (command) {
            effects = new StartCommandEffects(mint);
            if (!effects.ready()) {
                effects.dispose();
                effects = null;
            } else {
                chartStars();
            }
        }
        bottomRow = bottomRow();
        stage.addActor(bottomRow);
        corner = corner();
        stage.addActor(corner);
        tipCard = tipCard();
        stage.addActor(tipCard);
        showPage(Page.MAIN);
        keys();
        dialogs = new LobbyNativeDialogs(kit, stage, motion);
        start = System.nanoTime();
        if (skipped && motion) {
            // Reopened after a game: no greeting, but the menu still slides in
            pageShownAt = 0;
        }
        applyProbe();
        showNotice();
    }

    /** GPU thread: shows the alert waiting for the screen, if any, over whatever shows. */
    void showNotice() {
        if (stage == null || disposed) {
            return;
        }
        String[] notice = GpuStartWindow.takeNotice();
        if (notice != null) {
            dialogs.message(notice[0], notice[1]);
        }
    }

    /** GPU thread: the welcome MegaMek shows at start; a developer switch's run shows it only when it asks to. */
    void welcomeAtStart() {
        if (probe == null || probe.page() == null) {
            showWelcome();
        }
    }

    /**
     * GPU thread: shows the welcome over the screen, unless it already shows. Acknowledge with the box ticked stops it
     * showing at later starts; the developer switch's welcome keeps the preference as it is.
     */
    void showWelcome() {
        if (stage == null || disposed || (welcome != null && welcome.isOpen())) {
            return;
        }
        welcome = new StartWelcome(kit, motion, content.welcomeTitle(), content.title(), new StartWelcome.Answer() {
            @Override
            public void acknowledged(boolean dontShowAgain) {
                welcome = null;
                if (dontShowAgain && probe == null) {
                    SwingUtilities.invokeLater(() -> {
                        preferences.setNagForReadme(false);
                        PreferenceManager.getInstance().save();
                    });
                }
            }

            @Override
            public void quit() {
                window.quit();
            }

            @Override
            public void browse(String url) {
                SwingUtilities.invokeLater(() -> UIUtil.browse(url));
            }
        });
        welcome.show(stage);
    }

    /** Opens the page the developer switch asks for, in the state its options ask for. */
    private void applyProbe() {
        if (probe == null || probe.page() == null) {
            return;
        }
        if (probe.has("welcome")) {
            showWelcome();
        }
        if (probe.has("notice")) {
            // As a failed lobby's client leaves its game: the alert waits for the start screen
            SwingUtilities.invokeLater(() -> GpuStartWindow.notice(Messages.getString("Lobby.windowTitle"),
                  Messages.getString("Lobby.failure", "java.lang.IllegalStateException: probe")));
        }
        if (probe.lobby()) {
            showPage(Page.HOST);
            if (inputs.nameInput.getText().isBlank()) {
                inputs.nameInput.setText(Messages.getString("Lobby.probePlayer"));
            }
            int port = StartHostForm.port(inputs.portInput.getText());
            if (port >= 0) {
                // Another MegaMek may already host on the saved port; the probe's game takes the next free one
                inputs.portInput.setText(String.valueOf(StartScreenProbe.freePort(port)));
            }
            hostPage.submitHost();
            return;
        }
        Page asked;
        try {
            asked = Page.valueOf(probe.page());
        } catch (IllegalArgumentException unknown) {
            LOGGER.warn("MM_START_PAGE names no start page: {}", probe.page());
            return;
        }
        if (asked == Page.BRIEFING) {
            // The scenario MM_START_SCENARIO names, or the last one played
            String file = System.getenv("MM_START_SCENARIO");
            briefingPage.openBriefing(new File(file == null || file.isBlank()
                  ? PreferenceManager.getClientPreferences().getLastScenario() : file), Page.SCENARIOS);
        } else if (asked == Page.RESUME) {
            List<File> saves = StartSaves.list(new File(MMConstants.SAVEGAME_DIR));
            showPage(Page.LOAD);
            if (!saves.isEmpty()) {
                loadPage.readSave(saves.get(0), null);
            }
        } else if (asked != Page.MAIN) {
            showPage(asked);
        }
        if (asked != Page.MAIN) {
            pageShownAt = 0;
        }
        if (asked == Page.HOST) {
            if (probe.has("online") && !hostPage.onlineOpen) {
                hostPage.onlineOpen = true;
                hostPage.chevron(hostPage.onlineToggle, true);
                hostPage.fillHostColumn();
            }
            if (probe.has("reveal")) {
                inputs.passwordBox.shown(true);
            }
        }
        if (asked == Page.CONNECT) {
            if (probe.has("bot")) {
                connectPage.roleBar.select(StartConnectPage.AS_BOT);
                connectPage.fillConnectColumn();
            }
            if (probe.has("sbf")) {
                connectPage.sbfBox.setChecked(true);
            }
        }
    }

    /** Saves the frame just drawn as the developer switch's PNG, then quits when it asks to. */
    private void saveShot() {
        shotSaved = true;
        if (saveFrame(probe.shot(), bw, bh)) {
            LOGGER.info("Start screen shot saved to {}", probe.shot().getAbsoluteFile());
        }
        if (probe.quit()) {
            window.quit();
        }
    }

    /** GPU thread: saves the frame just drawn, {@code width} by {@code height} pixels, as a PNG; false when it can't. */
    static boolean saveFrame(File shot, int width, int height) {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteBuffer rgba = pixels.getPixels();
            for (int y = 0; y < height; y++) {
                // The frame buffer's rows run upward
                int from = (height - y - 1) * width * 4;
                for (int x = 0; x < width; x++, from += 4) {
                    image.setRGB(x, y, (rgba.get(from) & 0xFF) << 16 | (rgba.get(from + 1) & 0xFF) << 8
                          | rgba.get(from + 2) & 0xFF);
                }
            }
            File file = shot.getAbsoluteFile();
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            ImageIO.write(image, "png", file);
            return true;
        } catch (IOException failure) {
            LOGGER.error(failure, "Could not save the shot to {}", shot);
            return false;
        } finally {
            pixels.dispose();
        }
    }

    private static BufferedImage readImage(File file) {
        try {
            return ImageIO.read(file);
        } catch (IOException failure) {
            LOGGER.error(failure, "The start screen could not read {}", file);
            return null;
        }
    }

    // ---- The menu ----

    void showPage(Page shown) {
        page = shown;
        pageBackButton = null;
        unitCacheItem = null;
        generation++;
        column.forEach(Actor::remove);
        column.clear();
        if (shown == Page.HOST) {
            hostPage.buildHostPage(hostPage::submitHost, Page.MAIN);
            hostPage.fillHostColumn();
        } else if (shown == Page.RESUME) {
            hostPage.buildHostPage(hostPage::submitResume, Page.LOAD);
            hostPage.buildResume();
            hostPage.fillHostColumn();
        } else if (shown == Page.CONNECT) {
            connectPage.buildConnectPage();
            connectPage.fillConnectColumn();
        } else if (shown == Page.LOAD) {
            loadPage.buildLoadPage();
            loadPage.fillLoadColumn();
        } else if (shown == Page.SCENARIOS) {
            scenarioPage.showScenarios();
        } else if (shown == Page.BRIEFING) {
            briefingPage.showBriefing();
        } else if (shown == Page.UNIT_CACHE) {
            unitCachePage.showUnitCache();
        } else {
            List<Actor> items = new ArrayList<>();
            if (shown == Page.MAIN && content.gamePages()) {
                // The latest save, when there is one, is the way back in and takes the accent from New game
                List<File> saves = StartSaves.list(new File(MMConstants.SAVEGAME_DIR));
                if (!saves.isEmpty()) {
                    items.add(continueItem(saves.get(0)));
                }
                items.add(pageButton(saves.isEmpty() ? "button-primary" : "button-secondary", "StartScreen.newGame",
                      Page.HOST));
                items.add(pageItem("button-secondary", "StartScreen.loadGame", () -> showPage(Page.LOAD),
                      () -> window.dispatch(Action.QUICK_LOAD)));
                // As a dev convenience, Ctrl opens the last scenario's briefing
                items.add(pageItem("button-secondary", "StartScreen.scenario", () -> showPage(Page.SCENARIOS),
                      scenarioPage::lastScenario));
                items.add(pageButton("button-secondary", "StartScreen.connect", Page.CONNECT));
                items.add(overline("StartScreen.tools"));
                items.add(item("button-quiet", "StartScreen.mapEditor", Action.MAP_EDITOR, null));
                items.add(item("button-quiet", "StartScreen.openMap", Action.OPEN_MAP, null));
                items.add(item("button-quiet", "StartScreen.unitBrowser", Action.UNIT_BROWSER, null));
                unitCacheItem = kit.button("button-quiet", null, Messages.getString("StartScreen.unitCache"),
                      StartUnitCachePage.detail());
                leftAligned(unitCacheItem);
                UiKit.onChange(unitCacheItem, () -> showPage(Page.UNIT_CACHE));
                items.add(unitCacheItem);
                items.add(item("button-quiet", "StartScreen.randomArmy", Action.RANDOM_ARMY, null));
                items.add(item("button-quiet", "StartScreen.skinEditor", Action.SKIN_EDITOR, null));
            } else if (shown == Page.MAIN) {
                content.menu().forEach(entry -> items.add(menuItem(entry)));
            } else {
                items.add(overline("StartScreen.help"));
                content.help().forEach(entry -> items.add(menuItem(entry)));
                items.add(backButton());
            }
            setColumn(items);
        }
        if (start != 0) {
            pageShownAt = clock();
        }
        Actor first = switch (shown) {
            case HOST, CONNECT -> inputs.nameInput;
            case RESUME -> hostPage.playAs != null ? hostPage.playAs : inputs.nameInput;
            default -> null;
        };
        stage.setKeyboardFocus(first);
        if (first instanceof TextField field) {
            field.selectAll();
        }
    }

    /**
     * Shows {@code items} in the column, in order, and makes them, then the bottom row, the keyboard's stops. Items
     * already showing stay on the stage, so they keep their keyboard focus and text.
     */
    void setColumn(List<Actor> items) {
        for (Actor actor : column) {
            if (!items.contains(actor)) {
                actor.remove();
            }
        }
        column.clear();
        column.addAll(items);
        focusable.clear();
        for (Actor item : items) {
            if (item.getStage() == null) {
                stage.addActor(item);
            }
            stops(item);
        }
        for (Actor actor : bottomRow.getChildren()) {
            stops(actor);
        }
    }

    /** Adds the keyboard stops in {@code actor}: itself when it is a control, or the controls inside it. */
    private void stops(Actor actor) {
        if (actor instanceof UiButton || actor instanceof UiChoice || actor instanceof TextField
              || actor instanceof UiOptionBar || actor instanceof UiSelect) {
            focusable.add(actor);
        } else if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                stops(child);
            }
        }
    }

    /** A menu button; with Ctrl held, it asks for {@code withControl} instead, where there is one. */
    private UiButton item(String style, String key, Action action, Action withControl) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> window.dispatch(withControl != null && controlHeld() ? withControl : action));
        return button;
    }

    /** A menu button that opens a page; with Ctrl held, it runs {@code withControl} instead. */
    private UiButton pageItem(String style, String key, Runnable action, Runnable withControl) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> (controlHeld() ? withControl : action).run());
        return button;
    }

    /** One of the content's entries: a heading, or a button whose action runs on the Swing thread. */
    private Actor menuItem(StartMenuItem entry) {
        return switch (entry) {
            case StartMenuItem.Heading heading -> kit.roleTypeLabel("overline", heading.label(), "text.muted");
            case StartMenuItem.Button item -> {
                String style = switch (item.emphasis()) {
                    case PRIMARY -> "button-primary";
                    case SECONDARY -> "button-secondary";
                    case QUIET -> "button-quiet";
                };
                UiButton button = kit.button(style, null, item.label(), item.detail());
                leftAligned(button);
                for (Label detail : button.details) {
                    // A long detail, such as a save's name, shortens rather than widen the column
                    detail.setEllipsis(true);
                    button.getCell(detail).minWidth(0).growX();
                }
                UiKit.onChange(button, () -> window.run(item.action()));
                yield button;
            }
        };
    }

    /** GPU thread: shows the main menu again, where it shows, without sliding it in again. */
    void menuChanged() {
        if (stage == null || disposed || page != Page.MAIN) {
            return;
        }
        double shownAt = pageShownAt;
        showPage(Page.MAIN);
        pageShownAt = shownAt;
    }

    /** Continue: the latest save, named with how long ago it was made, loaded as a click on it in the list loads it. */
    private UiButton continueItem(File save) {
        UiButton button = kit.button("button-primary", null, Messages.getString("StartScreen.continue"),
              StartSaves.title(save) + "  \u00b7  " + StartSaves.ago(save.lastModified(), System.currentTimeMillis()));
        leftAligned(button);
        UiKit.onChange(button, () -> loadPage.readSave(save, button));
        return button;
    }

    /** A menu button that opens {@code shown} in the column. */
    private UiButton pageButton(String style, String key, Page shown) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        leftAligned(button);
        UiKit.onChange(button, () -> showPage(shown));
        return button;
    }

    UiButton backButton() {
        return backButton(Page.MAIN);
    }

    /** Back, to {@code target}. */
    UiButton backButton(Page target) {
        UiButton back = kit.button("button-secondary", "chevron-left", Messages.getString("StartScreen.back"), null);
        leftAligned(back);
        back.setDisabled(restoreStart != null);
        UiKit.onChange(back, () -> showPage(target));
        pageBackButton = back;
        return back;
    }

    /** The page Back and Esc return to from {@code from}. */
    private Page parent(Page from) {
        return switch (from) {
            case RESUME -> Page.LOAD;
            case BRIEFING -> briefingPage.briefingBack;
            default -> Page.MAIN;
        };
    }

    /** Whether {@code shown} lists or describes something and so takes a wider column. */
    private static boolean wide(Page shown) {
        return shown == Page.LOAD || shown == Page.RESUME || shown == Page.SCENARIOS || shown == Page.BRIEFING;
    }

    /** A line of wrapped text in the column, such as a page's status or an error. */
    Label note(String text, String role) {
        Label note = kit.typeLabel(text, "body", role);
        note.setWrap(true);
        note.setAlignment(Align.left);
        return note;
    }

    /** One line of text that shortens with an ellipsis rather than wrap. */
    Label line(String text, String type, String role) {
        Label line = kit.typeLabel(text, type, role);
        line.setEllipsis(true);
        return line;
    }

    /**
     * Runs {@code work} off the GPU thread, then hands its result to {@code done} on it, unless another page or read
     * has been asked for since.
     */
    <T> void background(String name, Supplier<T> work, Consumer<T> done) {
        int asked = generation;
        // Gdx.app moves to the next native window once this one closes; results stay with this one
        Application app = Gdx.app;
        Thread thread = new Thread(() -> {
            T result = work.get();
            app.postRunnable(() -> {
                if (!disposed && asked == generation) {
                    done.accept(result);
                }
            });
        }, "MegaMek-start-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean controlHeld() {
        return Gdx.input.isKeyPressed(Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Keys.CONTROL_RIGHT);
    }

    static void leftAligned(UiButton button) {
        button.left();
        button.getCells().forEach(cell -> cell.left());
        button.getLabelCell().expandX();
    }

    Label overline(String key) {
        return kit.roleTypeLabel("overline", Messages.getString(key), "text.muted");
    }

    /**
     * Disables {@code button}, which starts a game, and Back, and has {@code button} say {@code key}'s text until
     * {@link #startEnded}, which runs {@code ended}.
     */
    void starting(UiButton button, String key, Runnable ended) {
        // The button of an earlier start, such as a save whose read another click dropped, works again
        startEnded();
        restoreStart = ended;
        button.setDisabled(true);
        if (pageBackButton != null) {
            pageBackButton.setDisabled(true);
        }
        button.setText(kit.cased("button", Messages.getString(key)));
    }

    /** As {@link #starting(UiButton, String, Runnable)}; {@code button} gets its own text back when the start ends. */
    void starting(UiButton button, String key) {
        String text = button.getText().toString();
        starting(button, key, () -> {
            button.setDisabled(false);
            button.setText(text);
        });
    }

    /** No game started, or the one that did has closed: the start controls work again. */
    void startEnded() {
        Runnable ended = restoreStart;
        restoreStart = null;
        if (pageBackButton != null) {
            pageBackButton.setDisabled(false);
        }
        if (ended != null) {
            ended.run();
        }
    }

    /**
     * Settings: MegaMek's start screen opens the native Client Settings over itself, read on the Swing thread; another
     * program's start screen, such as MekHQ's, opens its own.
     */
    private void settings() {
        if (!content.gamePages()) {
            window.settings();
            return;
        }
        window.run(() -> {
            ClientSettingsForm form = ClientSettingsForm.current();
            GpuHost.post(() -> {
                if (dialogs != null && !disposed) {
                    dialogs.clientSettings(form, null, ClientSettingsView.Host.swing(GpuHost::post));
                }
            });
        });
    }

    private Table bottomRow() {
        Table row = new Table();
        row.left();
        row.defaults().padRight(6);
        UiButton settings = kit.button("button-quiet-compact", "settings",
              Messages.getString("StartScreen.settings"), null);
        UiKit.onChange(settings, this::settings);
        UiButton help = kit.button("button-quiet-compact", "help", Messages.getString("StartScreen.help"), null);
        UiKit.onChange(help, () -> showPage(page == Page.HELP ? Page.MAIN : Page.HELP));
        UiButton quit = kit.button("button-quiet-compact", "close", Messages.getString("StartScreen.quit"), null);
        UiKit.onChange(quit, window::quit);
        row.add(settings);
        if (!content.help().isEmpty()) {
            row.add(help);
        }
        row.add(quit);
        row.pack();
        return row;
    }

    /** The version, and this build's note where it has one. */
    private Table corner() {
        Table table = new Table();
        table.left().top();
        table.defaults().left();
        table.add(kit.typeLabel(content.version(), "small", "text.muted")).row();
        if (Version.hasBuildNotes()) {
            Label notes = kit.typeLabel(Version.getBuildNotes(), "small", "text.primary");
            Label.LabelStyle style = new Label.LabelStyle(notes.getStyle());
            java.awt.Color color = SplashArt.buildNotesColor();
            style.fontColor = new com.badlogic.gdx.graphics.Color(color.getRed() / 255f, color.getGreen() / 255f,
                  color.getBlue() / 255f, 1);
            notes.setStyle(style);
            notes.setWrap(true);
            notes.setAlignment(Align.left);
            table.add(notes).width(NOTES_WIDTH).padTop(4).row();
        }
        table.pack();
        return table;
    }

    private Table tipCard() {
        // The screen's corners frame it already, and only the outermost frame has ticks
        Table card = kit.panel(true);
        card.setBackground(boardSkin.skin.getDrawable(UiTheme.TOKEN_PANEL_PLAIN));
        card.defaults().left();
        card.add(kit.roleTypeLabel("overline", Messages.getString("StartScreen.tip"), "text.muted"))
              .growX().row();
        tipText = kit.typeLabel("", "body", "text.primary");
        tipText.setWrap(true);
        card.add(tipText).width(TIP_WIDTH).padTop(6).row();
        tipAuthor = kit.typeLabel("", "small", "text.muted");
        card.add(tipAuthor).right().padTop(4).row();
        card.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                showTip(tipIndex + 1);
            }
        });
        tipIndex = tips.isEmpty() ? 0 : random.nextInt(tips.size());
        showTip(tipIndex);
        card.setVisible(!tips.isEmpty());
        card.pack();
        return card;
    }

    private void showTip(int index) {
        if (tips.isEmpty()) {
            return;
        }
        tipIndex = Math.floorMod(index, tips.size());
        tipShownAt = start == 0 ? 0 : clock();
        String[] lines = tips.get(tipIndex).split("\n");
        int last = lines.length - 1;
        boolean attributed = last > 0 && lines[last].startsWith("-");
        tipText.setText(String.join("\n", List.of(lines).subList(0, attributed ? last : lines.length)));
        tipAuthor.setText(attributed ? lines[last] : "");
        if (tipCard != null) {
            tipCard.pack();
        }
    }

    private void keys() {
        stage.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (introTime() < StartTimeline.INTRO_END) {
                    skip();
                    event.stop();
                    return true;
                }
                return false;
            }

            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (introTime() < StartTimeline.INTRO_END) {
                    skip();
                    return true;
                }
                if (UiModal.active(stage) != null) {
                    // A dialog over the screen answers its own keys
                    return false;
                }
                // An open list, and a select's own opening keys, are the select's; the event reaches here after
                Actor target = event.getTarget();
                if (target instanceof UiListPopup || target instanceof UiSelect && (keycode == Keys.DOWN
                      || keycode == Keys.ENTER || keycode == Keys.NUMPAD_ENTER || keycode == Keys.SPACE)) {
                    return false;
                }
                boolean shift = Gdx.input.isKeyPressed(Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Keys.SHIFT_RIGHT);
                switch (keycode) {
                    case Keys.DOWN -> move(1);
                    case Keys.UP -> move(-1);
                    case Keys.TAB -> move(shift ? -1 : 1);
                    case Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE -> {
                        Actor focus = stage.getKeyboardFocus();
                        if (focus instanceof UiButton button) {
                            if (!button.isDisabled()) {
                                button.fire(new ChangeListener.ChangeEvent());
                            }
                        } else if (focus instanceof TextField && keycode != Keys.SPACE && submitsOnEnter()) {
                            submit();
                        } else {
                            return false;
                        }
                    }
                    case Keys.ESCAPE -> {
                        if (page != Page.MAIN && restoreStart == null) {
                            showPage(parent(page));
                        }
                    }
                    case Keys.NUM_1, Keys.NUMPAD_1 -> {
                        if (!preferences.getBoolean(GUIPreferences.START_SCREEN_REPLAY_KEY) || page != Page.MAIN
                              || !motion || restoreStart != null) {
                            return false;
                        }
                        replay();
                    }
                    default -> {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    /** Whether Enter in a text field sends the page's form. */
    private boolean submitsOnEnter() {
        return page == Page.HOST || page == Page.RESUME || page == Page.CONNECT
              || page == Page.BRIEFING && briefingPage.briefing != null && briefingPage.briefing.error() == null;
    }

    private void submit() {
        switch (page) {
            case HOST -> hostPage.submitHost();
            case RESUME -> hostPage.submitResume();
            case CONNECT -> connectPage.submitConnect();
            case BRIEFING -> briefingPage.submitBriefing();
            default -> {
            }
        }
    }

    private void skip() {
        skipped = true;
        pageShownAt = -1;
    }

    /** Plays the intro again from the greeting, to watch it while tuning it. */
    private void replay() {
        skipped = false;
        pageShownAt = -1;
        start = System.nanoTime();
        tipShownAt = 0;
        settledRate = false;
        Gdx.graphics.setForegroundFPS(GpuBoardWindow.DEFAULT_VSYNC ? 0 : 60);
    }

    private void move(int step) {
        int index = focusable.indexOf(stage.getKeyboardFocus());
        int next = index < 0 ? (step > 0 ? 0 : focusable.size() - 1) : Math.floorMod(index + step, focusable.size());
        Actor focus = focusable.get(next);
        stage.setKeyboardFocus(focus);
        // A list's row the keyboard reaches scrolls into view
        for (Group parent = focus.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getActor() != null) {
                Vector2 at = focus.localToAscendantCoordinates(scroll.getActor(), new Vector2());
                scroll.scrollTo(at.x, at.y, focus.getWidth(), focus.getHeight());
                break;
            }
        }
    }

    /** How far column item {@code index} has slid in, from 0 to 1. */
    private double slide(int index) {
        if (!motion) {
            return 1;
        }
        if (pageShownAt < 0) {
            return StartTimeline.itemProgress(introTime(), index);
        }
        return StartTimeline.ease((clock() - pageShownAt - StartTimeline.ITEM_STAGGER * index)
              / StartTimeline.ITEM_SLIDE);
    }

    /**
     * The menu sits in a column at the right, clear of the art's main subject on the left, and slides in from the edge.
     * A page's list or description takes the height the other items leave it above the bottom row, and scrolls.
     */
    private void layoutMenu() {
        float margin = Math.max(40, w * 0.05f);
        float menuWidth = Math.max(260, w * 0.2f);
        float columnWidth = wide(page) ? Math.max(WIDE_COLUMN, w * 0.28f) : menuWidth;
        float left = w - margin - columnWidth;
        float top = Math.max(32, h * 0.07f);
        double t = introTime();
        double logoIn = motion ? StartTimeline.ease((t - StartTimeline.MENU_START) / 0.5) : 1;
        float y = top;
        if (logo != null) {
            float logoWidth = menuWidth * 0.8f;
            float logoHeight = logoWidth * logoTexture.getHeight() / logoTexture.getWidth();
            logo.setBounds(w - margin - logoWidth + (float) ((1 - logoIn) * 60), h - top - logoHeight, logoWidth,
                  logoHeight);
            logo.setColor(1, 1, 1, (float) logoIn);
            logo.setVisible(logoIn > 0);
            y += logoHeight + 28;
        }
        float[] heights = new float[column.size()];
        float taken = 0;
        int filler = -1;
        for (int i = 0; i < column.size(); i++) {
            Actor item = column.get(i);
            // A wrapped label's height follows its width
            item.setWidth(columnWidth);
            if (item instanceof ScrollPane scroll && filler < 0) {
                filler = i;
                fitScrolled(scroll, columnWidth);
            }
            heights[i] = item instanceof Layout layout ? layout.getPrefHeight() : item.getHeight();
            taken += gapAbove(item) + gapBelow(item, i) + (i == filler ? 0 : heights[i]);
        }
        float reserved = 32 + bottomRow.getHeight() + 16;
        float fillerWanted = filler >= 0 ? heights[filler] : 0;
        if (filler >= 0) {
            float room = h - y - taken - reserved;
            heights[filler] = Math.min(heights[filler], Math.max(room, MIN_FILLER));
            taken += heights[filler];
        }
        // The briefing's map gives way before the column runs into the bottom row
        for (int i = 0; i < column.size(); i++) {
            if (column.get(i) instanceof StartMapFrame map) {
                float fitted = StartMapFrame.fitted(heights[i], map.getMinHeight(), y + taken - (h - reserved));
                taken -= heights[i] - fitted;
                heights[i] = fitted;
                if (filler >= 0) {
                    // The room the map left goes back to the list or description, up to all it would show
                    float slack = h - reserved - y - taken;
                    float grown = Math.min(fillerWanted, heights[filler] + Math.max(0, slack));
                    taken += grown - heights[filler];
                    heights[filler] = grown;
                }
            }
        }
        float offscreen = columnWidth + margin + 40;
        for (int i = 0; i < column.size(); i++) {
            Actor item = column.get(i);
            float itemHeight = heights[i];
            double p = slide(i);
            float dx = (float) ((1 - p) * offscreen);
            y += gapAbove(item);
            item.setBounds(left + dx, h - y - itemHeight, columnWidth, itemHeight);
            y += itemHeight + gapBelow(item, i);
            item.setVisible(p > 0 && itemHeight > 0);
        }
        // Like the logo, the bottom row only slides in with the greeting and stays put between pages
        double rowIn = pageShownAt < 0 ? slide(column.size()) : 1;
        bottomRow.setPosition(w - margin - bottomRow.getWidth() + (float) ((1 - rowIn) * offscreen), 32);
        bottomRow.setVisible(rowIn > 0);
        boolean settled = t >= StartTimeline.MENU_START;
        corner.setPosition(32, h - corner.getHeight() - 28);
        corner.setVisible(settled);
        tipCard.setPosition(32, content.tipAtTop() ? corner.getY() - 20 - tipCard.getHeight() : 32);
        tipCard.setVisible(settled && !tips.isEmpty());
        if (settled && clock() - tipShownAt > TIP_SECONDS) {
            showTip(tipIndex + 1);
        }
    }

    /** Labels in the column are its section headings and notes, with room above them. */
    private static float gapAbove(Actor item) {
        return item instanceof Label ? 14 : 0;
    }

    private float gapBelow(Actor item, int index) {
        if (item instanceof Label) {
            return 6;
        }
        return index == 0 && page == Page.MAIN ? 14 : 8;
    }

    /** Gives a scrolled wrapped text its width, so the pane's preferred height is the text's at that width. */
    private static void fitScrolled(ScrollPane scroll, float width) {
        Actor inner = scroll.getActor();
        if (!(inner instanceof Label label) || !label.getWrap()) {
            return;
        }
        Drawable frame = scroll.getStyle().background;
        float innerWidth = width - (frame == null ? 0 : frame.getLeftWidth() + frame.getRightWidth());
        if (label.getWidth() != innerWidth) {
            label.setWidth(innerWidth);
            scroll.invalidate();
        }
    }

    // ---- Size and scale ----

    @Override
    public void resize(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        scale = DisplayScale.read(preferences.getGUIScale(), new GpuDisplayScale().contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        bw = Gdx.graphics.getBackBufferWidth();
        bh = Gdx.graphics.getBackBufferHeight();
        w = bw / scale;
        h = bh / scale;
        pixels.setToOrtho2D(0, 0, bw, bh);
        if (effects != null) {
            effects.resize(bw, bh);
        }
        layoutTiles();
        rebuildBackdrop();
        rebuildGreeting();
    }

    /** GPU thread: after the GUI scale changes. */
    void rescale() {
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    private void layoutTiles() {
        tiles = new ArrayList<>();
        if (command) {
            // The plotting grid's squares
            tileR = Math.max(40, h / 12.0) / 2;
            return;
        }
        Random scatter = new Random(11);
        tileR = Math.max(28, h / 16.0);
        double rowHeight = Math.sqrt(3) * tileR;
        int col = 0;
        for (double cx = 0; cx < w + tileR * 2; cx += tileR * 1.5, col++) {
            double offset = (col % 2) * rowHeight / 2;
            for (double cy = -rowHeight / 2 + offset; cy < h + rowHeight; cy += rowHeight) {
                double at = (cx / (w + tileR)) * 0.78 + scatter.nextDouble() * 0.22;
                tiles.add(new Tile(cx, cy, at));
            }
        }
    }

    /** The art, its shading, the faint grid, scanlines and corner ticks, drawn once at the window's size. */
    private void rebuildBackdrop() {
        BufferedImage image = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.scale(scale, scale);
        drawCoverArt(g);
        drawShading(g);
        g.setStroke(new BasicStroke(1f));
        if (command) {
            drawPlottingGrid(g);
        } else {
            g.setColor(alpha(text, 0.045f));
            for (Tile tile : tiles) {
                g.draw(hexPath(tile.x, tile.y, tileR));
            }
        }
        g.setColor(alpha(java.awt.Color.BLACK, 0.10f));
        for (int y = 0; y < h; y += 3) {
            g.fillRect(0, y, (int) w + 1, 1);
        }
        drawCornerTicks(g);
        g.dispose();
        if (backdrop != null) {
            backdrop.dispose();
        }
        backdrop = texture(image, false);
        if (effects != null) {
            findLights();
        }
    }

    /** The art over the dark field, covering the window, in logical units. */
    private void drawCoverArt(Graphics2D g) {
        g.setColor(base);
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
        if (art != null) {
            double cover = Math.max(w / art.getWidth(), h / art.getHeight()) * 1.04;
            double drawnWidth = art.getWidth() * cover;
            double drawnHeight = art.getHeight() * cover;
            g.drawImage(art, (int) ((w - drawnWidth) / 2), (int) ((h - drawnHeight) / 2), (int) drawnWidth,
                  (int) drawnHeight, null);
        }
    }

    /** Darkens the art towards the menu at the right and around the edges, in logical units. */
    private void drawShading(Graphics2D g) {
        g.setPaint(new LinearGradientPaint(w, 0, w * 0.45f, 0, new float[] { 0f, 0.45f, 1f },
              new java.awt.Color[] { alpha(base, 0.94f), alpha(base, 0.72f), alpha(base, 0f) }));
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(w * 0.4, h * 0.45), Math.max(w, h) * 0.75f,
              new float[] { 0.55f, 1f }, new java.awt.Color[] { alpha(base, 0f), alpha(base, 0.75f) }));
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
    }

    /** A command post's light and screens, found in the art as it shows, at a smaller size. */
    private void findLights() {
        int maskWidth = StartCommandEffects.maskSize(bw);
        int maskHeight = StartCommandEffects.maskSize(bh);
        BufferedImage shownArt = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = shownArt.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.scale(maskWidth / w, maskHeight / h);
        drawCoverArt(g);
        g.dispose();
        BufferedImage shading = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
        g = shading.createGraphics();
        g.scale(maskWidth / w, maskHeight / h);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, (int) w + 1, (int) h + 1);
        drawShading(g);
        g.dispose();
        effects.findLights(shownArt, shading);
    }

    /** A command post's square plotting grid: faint lines on the tiles' edges, stronger ones and marks every fourth. */
    private void drawPlottingGrid(Graphics2D g) {
        double side = tileR * 2;
        int index = 0;
        for (double x = 0; x < w + 1; x += side, index++) {
            g.setColor(alpha(text, index % 4 == 0 ? 0.06f : 0.025f));
            g.draw(new Line2D.Double(x, 0, x, h));
        }
        index = 0;
        for (double y = 0; y < h + 1; y += side, index++) {
            g.setColor(alpha(text, index % 4 == 0 ? 0.06f : 0.025f));
            g.draw(new Line2D.Double(0, y, w, y));
        }
        double mark = 4;
        g.setColor(alpha(mint, 0.16f));
        for (double x = 0; x < w + 1; x += side * 4) {
            for (double y = 0; y < h + 1; y += side * 4) {
                g.draw(new Line2D.Double(x - mark, y, x + mark, y));
                g.draw(new Line2D.Double(x, y - mark, x, y + mark));
            }
        }
    }

    private void drawCornerTicks(Graphics2D g) {
        int inset = 16;
        int length = 26;
        g.setColor(alpha(strong, 0.7f));
        g.setStroke(new BasicStroke(2));
        int x0 = inset;
        int y0 = inset;
        int x1 = (int) w - inset;
        int y1 = (int) h - inset;
        g.drawLine(x0, y0, x0 + length, y0);
        g.drawLine(x0, y0, x0, y0 + length);
        g.drawLine(x1, y0, x1 - length, y0);
        g.drawLine(x1, y0, x1, y0 + length);
        g.drawLine(x0, y1, x0 + length, y1);
        g.drawLine(x0, y1, x0, y1 - length);
        g.drawLine(x1, y1, x1 - length, y1);
        g.drawLine(x1, y1, x1, y1 - length);
    }

    private void rebuildGreeting() {
        Font overline = TokenFonts.font("overline", true, true)
              .deriveFont(TokenFonts.font("overline").getSize2D() * 1.4f * scale);
        Font display = TokenFonts.font("display", true, true).deriveFont((float) Math.max(34, h * 0.055) * scale);
        String first = content.greetingOverline();
        String second = content.greeting();
        if (line1 != null) {
            line1.dispose();
            line2.dispose();
        }
        line1 = textLine(first.toUpperCase(Locale.ROOT), overline, mint);
        line2 = textLine(second.toUpperCase(Locale.ROOT), display, text);
    }

    private static TextLine textLine(String line, Font font, java.awt.Color color) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = probe.createGraphics();
        textHints(probeGraphics);
        FontMetrics metrics = probeGraphics.getFontMetrics(font);
        int[] widths = new int[line.length() + 1];
        for (int i = 0; i <= line.length(); i++) {
            widths[i] = (int) Math.ceil(metrics.getStringBounds(line.substring(0, i), probeGraphics).getWidth());
        }
        int ascent = metrics.getAscent();
        int height = metrics.getAscent() + metrics.getDescent();
        int charWidth = metrics.charWidth('M');
        probeGraphics.dispose();
        BufferedImage image = new BufferedImage(widths[line.length()] + 4, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        textHints(g);
        g.setFont(font);
        g.setColor(color);
        g.drawString(line, 0, ascent);
        g.dispose();
        return new TextLine(texture(image, false), widths, ascent, charWidth);
    }

    private static void textHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    static Texture texture(BufferedImage image, boolean mipMaps) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        ByteBuffer buffer = pixmap.getPixels();
        for (int value : argb) {
            buffer.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) (value >>> 24));
        }
        buffer.flip();
        Texture texture = new Texture(pixmap, mipMaps);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    private static Path2D hexPath(double cx, double cy, double r) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 3 * i;
            if (i == 0) {
                path.moveTo(cx + r * Math.cos(angle), cy + r * Math.sin(angle));
            } else {
                path.lineTo(cx + r * Math.cos(angle), cy + r * Math.sin(angle));
            }
        }
        path.closePath();
        return path;
    }

    private static java.awt.Color alpha(java.awt.Color color, float alpha) {
        return new java.awt.Color(color.getRed(), color.getGreen(), color.getBlue(),
              Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    // ---- Each frame ----

    @Override
    public void render() {
        double t = introTime();
        float dt = Math.min(0.1f, Gdx.graphics.getDeltaTime());
        Gdx.gl.glClearColor(base.getRed() / 255f, base.getGreen() / 255f, base.getBlue() / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_STENCIL_BUFFER_BIT);
        if (motion) {
            stepParticles(dt);
        }
        drawScene(t);
        if (page == Page.UNIT_CACHE) {
            unitCachePage.update();
        } else if (unitCacheItem != null && !unitCacheItem.details.isEmpty()) {
            unitCacheItem.details.get(0).setText(StartUnitCachePage.detail());
        }
        layoutMenu();
        stage.act(dt);
        hud.update(Set.of());
        stage.draw();
        if (probe != null && probe.shot() != null && !probe.lobby() && !shotSaved && clock() >= probe.shotAt()) {
            saveShot();
        }
        if (probe != null && probe.has("play") && page == Page.BRIEFING && !playPressed && clock() >= probe.shotAt()
              && briefingPage.canStart()) {
            playPressed = true;
            briefingPage.submitBriefing();
        }
        if (probe != null && page == Page.BRIEFING && !probeDialogOpened && briefingPage.canStart()) {
            probeDialogOpened = true;
            briefingPage.probeDialog(probe);
        }
        if (!settledRate && t >= StartTimeline.INTRO_END) {
            // The settled screen has only slow motes: 30 frames a second is plenty
            settledRate = true;
            Gdx.graphics.setForegroundFPS(30);
            window.settled();
        }
    }

    /** Asks the content for its star chart on a thread of its own, as reading it may take a moment. */
    private void chartStars() {
        Thread reader = new Thread(() -> {
            try {
                starChart = content.starChart();
            } catch (RuntimeException e) {
                LOGGER.warn(e, "The start screen could not chart the stars");
            }
        }, "Start screen star chart");
        reader.setDaemon(true);
        reader.start();
    }

    private void drawScene(double t) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setProjectionMatrix(pixels);
        shapes.setProjectionMatrix(pixels);
        if (effects != null && t < StartTimeline.REVEAL_END) {
            effects.drawStarMap(t, starChart);
        }
        drawGreeting(t);
        drawReveal(t);
    }

    private void drawArt() {
        batch.begin();
        batch.setColor(1, 1, 1, 1);
        batch.draw(backdrop, 0, 0, bw, bh);
        batch.end();
        if (effects != null) {
            double t = introTime();
            float strength = (float) StartTimeline.ease((t - StartTimeline.REVEAL_END) / AMBIENCE_RAMP);
            effects.drawAmbience(batch, motion ? (float) clock() : 0, strength, scale);
        }
        drawMotes();
    }

    private void drawReveal(double t) {
        double rs = StartTimeline.REVEAL_START;
        double re = StartTimeline.REVEAL_END;
        if (command) {
            if (t >= re || (effects == null && t >= rs)) {
                drawArt();
            } else if (t >= rs) {
                // The art boots up as a hologram once the view has dived into the star map
                double progress = StartTimeline.revealProgress(t);
                effects.drawHologram(batch, backdrop, (progress - 0.35) / 0.65, (float) t);
            }
            return;
        }
        if (t >= rs) {
            boolean masked = t < re;
            if (masked) {
                double rp = StartTimeline.revealProgress(t);
                Gdx.gl.glEnable(GL20.GL_STENCIL_TEST);
                Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
                Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
                Gdx.gl.glColorMask(false, false, false, false);
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                for (Tile tile : tiles) {
                    double pop = StartTimeline.clamp((rp - tile.at) / StartTimeline.HEX_SPAN);
                    if (pop > 0) {
                        fillTile(tile.x, tile.y, tileR * (0.35 + 0.67 * StartTimeline.ease(pop)));
                    }
                }
                shapes.end();
                Gdx.gl.glColorMask(true, true, true, true);
                Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);
                Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
            }
            drawArt();
            if (masked) {
                Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);
            }
        }
        if (t >= rs - 0.3 && t < re + 0.5) {
            double rpRaw = (t - rs) / (re - rs);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Tile tile : tiles) {
                double since = rpRaw - tile.at;
                float a = since < 0 ? (float) (0.22 * StartTimeline.clamp(1 + since / 0.12))
                      : (float) (0.85 * (1 - StartTimeline.clamp((since - StartTimeline.HEX_SPAN) / 0.25)));
                if (a > 0.01f) {
                    shapes.setColor(rgba(mint, a));
                    outlineTile(tile.x, tile.y, tileR - 1, 1.5f * scale);
                }
            }
            shapes.end();
        }
    }

    /** The two typed lines, a blinking cursor and a rule under them. */
    private void drawGreeting(double t) {
        if (skipped || t > StartTimeline.REVEAL_END) {
            return;
        }
        double ts = StartTimeline.TEXT_START;
        int n1 = StartTimeline.lineOneTyped(t, line1.widths().length - 1);
        int n2 = StartTimeline.lineTwoTyped(t, line2.widths().length - 1);
        int lineWidth = line2.width();
        int x = (bw - lineWidth) / 2;
        int baseline2 = (int) (bh / 2.0 + line2.ascent() / 2.0);
        int baseline1 = baseline2 - line2.ascent() - (int) (10 * scale);
        batch.begin();
        batch.setColor(1, 1, 1, 1);
        if (n1 > 0) {
            float y = bh - (baseline1 - line1.ascent()) - line1.texture().getHeight();
            batch.draw(new TextureRegion(line1.texture(), 0, 0, line1.widths()[n1], line1.texture().getHeight()), x, y,
                  line1.widths()[n1], line1.texture().getHeight());
        }
        if (n2 > 0) {
            float y = bh - (baseline2 - line2.ascent()) - line2.texture().getHeight();
            batch.draw(new TextureRegion(line2.texture(), 0, 0, line2.widths()[n2], line2.texture().getHeight()), x, y,
                  line2.widths()[n2], line2.texture().getHeight());
        }
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (t < StartTimeline.REVEAL_START) {
            boolean typing = n2 < line2.widths().length - 1;
            if ((typing || ((int) (t * 3)) % 2 == 0) && t > ts + 0.5) {
                float cursorX = x + line2.widths()[n2] + 6 * scale;
                float cursorWidth = Math.max(10 * scale, line2.charWidth() / 2f);
                float cursorHeight = line2.ascent() - 6 * scale;
                shapes.setColor(rgba(mint, 1));
                shapes.rect(cursorX, bh - baseline2, cursorWidth, cursorHeight);
            }
        }
        double rule = StartTimeline.ease((t - ts - 0.4) / 1.2);
        shapes.setColor(rgba(muted, 0.6f));
        shapes.rect(x, bh - baseline2 - 18 * scale - scale, (float) (lineWidth * rule), scale);
        shapes.end();
    }

    /** The embers rising from a battle, or the dust drifting in a command post's light. */
    private void drawMotes() {
        if (!motion) {
            return;
        }
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (float[] p : particles) {
            float life = p[4] / p[5];
            if (command) {
                float a = Math.min(1, Math.min(life * 2, (1 - life) * 2)) * 0.4f;
                shapes.setColor(1f, 0.93f, 0.8f, Math.max(0, a));
            } else {
                float a = Math.min(1, Math.min(life * 3, (1 - life) * 4)) * 0.8f;
                shapes.setColor(1f, (150 + life * 80) / 255f, 60 / 255f, Math.max(0, a));
            }
            float size = p[6] * scale;
            shapes.circle(p[0] * bw + size / 2, bh - (p[1] * bh + size / 2), size / 2, 8);
        }
        shapes.end();
    }

    private void stepParticles(float dt) {
        for (int i = 0; i < particles.size(); i++) {
            float[] p = particles.get(i);
            // Dust sways as it drifts
            float sway = command ? 0.004f * (float) Math.sin(p[4] * 1.3f + p[5] * 7) : 0;
            p[0] += (p[2] + sway) * dt;
            p[1] += p[3] * dt;
            p[4] -= dt;
            if (p[4] <= 0 || p[1] < -0.05 || p[1] > 1.05) {
                particles.set(i, newParticle(false));
            }
        }
    }

    /**
     * An ember rising from the bottom, or a mote of dust appearing anywhere and slowly settling: x, y (fractions of the
     * window), their speeds, life left, its whole life and its size.
     */
    private float[] newParticle(boolean anywhere) {
        if (command) {
            float life = 6 + random.nextFloat() * 8;
            return new float[] {
                  random.nextFloat(), random.nextFloat(),
                  (random.nextFloat() - 0.5f) * 0.012f, 0.003f + random.nextFloat() * 0.008f,
                  anywhere ? random.nextFloat() * life : life, life, 0.8f + random.nextFloat() * 1.6f };
        }
        float life = 3 + random.nextFloat() * 5;
        return new float[] {
              0.2f + random.nextFloat() * 0.8f, anywhere ? random.nextFloat() : 1.02f,
              0.005f + random.nextFloat() * 0.02f, -(0.02f + random.nextFloat() * 0.05f),
              anywhere ? random.nextFloat() * life : life, life, 1.2f + random.nextFloat() * 2.2f };
    }

    private void fillTile(double cx, double cy, double r) {
        float x = (float) (cx * scale);
        float y = (float) (bh - cy * scale);
        float rr = (float) (r * scale);
        for (int i = 0; i < 6; i++) {
            double a0 = Math.PI / 3 * i;
            double a1 = Math.PI / 3 * (i + 1);
            shapes.triangle(x, y, x + rr * (float) Math.cos(a0), y - rr * (float) Math.sin(a0),
                  x + rr * (float) Math.cos(a1), y - rr * (float) Math.sin(a1));
        }
    }

    private void outlineTile(double cx, double cy, double r, float width) {
        float x = (float) (cx * scale);
        float y = (float) (bh - cy * scale);
        float rr = (float) (r * scale);
        for (int i = 0; i < 6; i++) {
            double a0 = Math.PI / 3 * i;
            double a1 = Math.PI / 3 * (i + 1);
            shapes.rectLine(x + rr * (float) Math.cos(a0), y - rr * (float) Math.sin(a0),
                  x + rr * (float) Math.cos(a1), y - rr * (float) Math.sin(a1), width);
        }
    }

    private static com.badlogic.gdx.graphics.Color rgba(java.awt.Color color, float alpha) {
        return new com.badlogic.gdx.graphics.Color(color.getRed() / 255f, color.getGreen() / 255f,
              color.getBlue() / 255f, alpha);
    }

    @Override
    public void dispose() {
        disposed = true;
        generation++;
        window.disposing();
        if (dialogs != null) {
            dialogs.dispose();
        }
        for (Object disposable : new Object[] { stage, batch, shapes, backdrop, logoTexture, effects }) {
            if (disposable instanceof com.badlogic.gdx.utils.Disposable resource) {
                resource.dispose();
            }
        }
        if (line1 != null) {
            line1.dispose();
            line2.dispose();
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.release();
        }
        briefingPage.disposeSwatches();
        briefingPage.disposeBriefingMap();
    }
}
