/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.Messages;
import megamek.client.ui.TableSort;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Presentation;

/**
 * The hud-v3 widgets that native views build from a UiTheme's skin (rebuild plan C.3), and the helpers they all use: a
 * message text, a change listener, a tooltip and a scrolling list. A widget shows what its view sets and reports a
 * click as a ChangeEvent; the kit only reads the skin, so it owns nothing to dispose.
 */
public final class UiKit {
    /** A dialog's greatest width, in stage units (.dlg: width 620). */
    public static final float DIALOG_WIDTH = 620;
    /** A dialog is at most this much less high than the window (.dlg: max-height calc(100vh - 140px)). */
    public static final float DIALOG_MARGIN = 140;
    /** The name of a header's count, for a view that keeps it current. */
    public static final String HEADER_COUNT = "header-count";
    /** Styles whose captions the prototype upper-cases (.b, .b.main, .b.mini, .b.brk.sm, .ltabs). */
    private static final Set<String> UPPER_CASE = Set.of("hud", "hud-main", "hud-mini", "hud-confirm",
          "hud-confirm-step", "hud-utility-small",
          "hud-tab-caps");
    /** CSS's normal line height of Roboto and Roboto Condensed, their ascent plus descent, in ems. */
    private static final float NORMAL_LINE = 1.172f;

    /** Status-line colors: plain (ink2), .ok, .w and .x. */
    public enum Tone {
        NORMAL(UiTheme.ACCENT), OK(UiTheme.MINT), WARN(UiTheme.AMBER), BAD(UiTheme.CORAL);

        /** The tone's text color, a theme token. */
        public final Color color;

        Tone(Color color) {
            this.color = color;
        }
    }

    /**
     * The text role whose case token chips follow: tokens-v0 cases chips and badges with the {@code overline} tags,
     * upper case in both the app and the game presentation.
     */
    static final String CHIP_CASE = "overline";

    /** The skin the widgets are built from: a UiTheme's, or one that a view's own skin composes with it. */
    public final Skin skin;
    private final Texture white;
    private Presentation presentation = Presentation.GAME;
    private Locale locale = Locale.getDefault();

    /** A kit over {@code skin}, which holds a UiTheme's styles; the kit only reads it. */
    public UiKit(Skin skin) {
        this.skin = skin;
        white = skin.get("white", Texture.class);
    }

    /**
     * The text case mode of the token controls the kit builds (docs/design-system/tokens.md, Text case): game, as the
     * in-game HUD is, unless a view sets app.
     */
    public Presentation presentation() {
        return presentation;
    }

    /** Builds the following token controls in {@code mode}; controls already built keep theirs. */
    public UiKit presentation(Presentation mode) {
        presentation = mode;
        return this;
    }

    /**
     * Upper-cases the following text in {@code value}, the player's language; the kit depends on no game options, so
     * its view passes it in. The system's locale until then.
     */
    public UiKit locale(Locale value) {
        locale = value;
        return this;
    }

    /** Whether text role {@code role} ("button", "tab"...) is upper case in the kit's text mode. */
    public boolean upperCase(String role) {
        return DesignTokens.shipped().upperCase(role, presentation);
    }

    /** Whether text role {@code role} ("title", "name"...) is set condensed in the kit's text mode. */
    public boolean condensed(String role) {
        return DesignTokens.shipped().condensed(role, presentation);
    }

    /** {@code text} in the case of text role {@code role} in the kit's text mode and locale. */
    public String cased(String role, String text) {
        return DesignTokens.shipped().cased(role, presentation, text, locale);
    }

    /**
     * A label for a token control's text role {@code role}, "button" or "tab", at {@code size}: hud-medium, or
     * {@link UiTheme#CAPS_FONT} with upper-cased text when the role is upper case in the kit's text mode.
     */
    public Label roleLabel(String role, String text, float size, Color color) {
        return upperCase(role) ? label(cased(role, text), UiTheme.CAPS_FONT, size, color)
              : label(text, "hud-medium", size, color);
    }

    /** A message; a pattern only with arguments, so that plain texts keep their apostrophes. */
    public static String text(String key, Object... arguments) {
        return arguments.length == 0 ? Messages.getString(key) : Messages.getString(key, arguments);
    }

    /** Runs {@code action} on every ChangeEvent of {@code actor}, such as a click on a hud button. */
    public static void onChange(Actor actor, Runnable action) {
        actor.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor target) {
                action.run();
            }
        });
    }

    /**
     * Keeps the press and the click of a control inside another clickable control (a row's remove ×) to itself: the
     * outer control is neither pressed nor clicked.
     */
    public static <T extends Actor> T ownEvents(T control) {
        control.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                event.stop();
                return false;
            }
        });
        control.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                event.stop();
            }
        });
        return control;
    }

    /**
     * A tooltip on {@code actor} in the theme's "hud" style, empty until its view sets the text. It goes when the actor
     * hides: libGDX hides a tooltip whose actor leaves the stage, but not one whose actor or panel hides under a resting
     * pointer, which then stays stuck. Its label acts only while it shows.
     */
    public TextTooltip tip(Actor actor) {
        return tip(actor, false);
    }

    /** A tooltip as {@link #tip(Actor)}; with {@code token}, the Token v0 tooltip card ({@link UiTheme#TOKEN}). */
    public TextTooltip tip(Actor actor, boolean token) {
        TextTooltip tip = new TextTooltip("", skin, token ? UiTheme.TOKEN : "hud");
        if (token) {
            fitTokenTip(tip);
        }
        actor.addListener(tip);
        tip.getActor().addAction(Actions.forever(Actions.run(() -> {
            if (!actor.ascendantsVisible()) {
                tip.hide();
            }
        })));
        return tip;
    }

    /**
     * A wrapping tooltip's card takes its style's whole wrap width; this one fits a short text and wraps a long one at
     * that width.
     */
    private void fitTokenTip(TextTooltip tip) {
        float wrapWidth = skin.get(UiTheme.TOKEN, TextTooltip.TextTooltipStyle.class).wrapWidth;
        if (wrapWidth <= 0) {
            return;
        }
        Label label = tip.getActor();
        GlyphLayout measure = new GlyphLayout();
        tip.getContainer().width(new Value() {
            @Override
            public float get(Actor context) {
                measure.setText(label.getStyle().font, label.getText());
                return Math.min(wrapWidth, (float) Math.ceil(measure.width * label.getFontScaleX()));
            }
        });
    }

    /**
     * A list that scrolls vertically in the "hud-list" style: a thin bar over the list, no flick and no overscroll. It
     * takes the wheel while the pointer is over it ({@link #wheelWhileHovered}); a press takes it too (ScrollPane).
     */
    public ScrollPane scrollList(Actor list) {
        ScrollPane scroll = new ScrollPane(list, skin, "hud-list");
        scroll.setScrollingDisabled(true, false);
        scroll.setScrollbarsOnTop(true);
        scroll.setOverscroll(false, false);
        scroll.setFlickScroll(false);
        wheelWhileHovered(scroll);
        return scroll;
    }

    /**
     * A list as {@link #scrollList(Actor)}; with {@code token}, in the Token v0 look (docs/design-system/tokens.md,
     * Lists and trees, Scrollbars): the input frame, and a pill thumb that always shows, over a rail while the pointer
     * is on the bar, lighter while hovered and lighter still while dragged. The bar takes a strip of its own beside the
     * list, {@code xs} clear of it, so nothing in the list draws under the bar.
     */
    public ScrollPane scrollList(Actor list, boolean token) {
        return scrollList(list, token, true);
    }

    /**
     * A list as {@link #scrollList(Actor, boolean)}; without {@code framed}, with no frame behind it, for a list that
     * fills a panel of its own.
     */
    public ScrollPane scrollList(Actor list, boolean token, boolean framed) {
        if (!token) {
            return scrollList(list);
        }
        ScrollPane scroll = scrollList(list);
        ScrollPane.ScrollPaneStyle rest = new ScrollPane.ScrollPaneStyle(
              skin.get(UiTheme.TOKEN_LIST, ScrollPane.ScrollPaneStyle.class));
        if (!framed) {
            rest.background = null;
        }
        ScrollPane.ScrollPaneStyle over = new ScrollPane.ScrollPaneStyle(rest);
        over.vScroll = skin.getDrawable("scroll-rail");
        over.vScrollKnob = skin.getDrawable("scroll-thumb-over");
        ScrollPane.ScrollPaneStyle down = new ScrollPane.ScrollPaneStyle(over);
        down.vScrollKnob = skin.getDrawable("scroll-thumb-down");
        scroll.setStyle(rest);
        scroll.setFadeScrollBars(false);
        // The bar takes its own strip beside the list, so rows, their washes and their trailing text end before it
        scroll.setScrollbarsOnTop(false);
        // The pointer's x in the pane while it is over it, else negative
        float[] pointer = { -1 };
        scroll.addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                pointer[0] = x;
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int index, Actor toActor) {
                if (index == -1 && (toActor == null || !toActor.isDescendantOf(scroll))) {
                    pointer[0] = -1;
                }
            }
        });
        scroll.addAction(Actions.forever(Actions.run(() -> {
            ScrollPane.ScrollPaneStyle look = rest;
            if (scroll.isScrollY() && scroll.isDragging()) {
                look = down;
            } else if (scroll.isScrollY()) {
                float right = scroll.getWidth() - (rest.background == null ? 0 : rest.background.getRightWidth());
                if (overScrollBar(pointer[0], right, scroll.getScrollBarWidth())) {
                    look = over;
                }
            }
            if (scroll.getStyle() != look) {
                scroll.setStyle(look);
            }
        })));
        return scroll;
    }

    /**
     * Whether a pointer at {@code x} in a scroll pane is on its vertical bar's strip, {@code width} wide (the bar and
     * its gap to the list) and ending at {@code right}; a negative {@code x} is a pointer elsewhere.
     */
    static boolean overScrollBar(float x, float right, float width) {
        return width > 0 && x >= 0 && x >= right - width && x <= right;
    }

    /**
     * Gives hud-v3's split pane the Token v0 divider (docs/design-system/tokens.md, Split panes): a subtle line in a
     * grab band, an accent line while the pointer is on it or drags it. Returns the split pane.
     */
    public SplitPane tokenSplit(SplitPane split) {
        SplitPane.SplitPaneStyle rest = skin.get(UiTheme.TOKEN_SPLIT,
              SplitPane.SplitPaneStyle.class);
        SplitPane.SplitPaneStyle active =
              new SplitPane.SplitPaneStyle(skin.getDrawable("token-split-active"));
        split.setStyle(rest);
        boolean[] dragging = { false };
        split.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                dragging[0] = split.isCursorOverHandle();
                return dragging[0];
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                dragging[0] = false;
            }
        });
        split.addAction(Actions.forever(Actions.run(() -> {
            SplitPane.SplitPaneStyle look =
                  dragging[0] || split.isCursorOverHandle() ? active : rest;
            if (split.getStyle() != look) {
                split.setStyle(look);
            }
        })));
        return split;
    }

    /** A horizontal choice row with the same scroll behaviour and styling as other UIKit lists. */
    public ScrollPane scrollStrip(Actor strip) {
        ScrollPane scroll = scrollList(strip);
        scroll.setScrollingDisabled(false, true);
        scroll.setFadeScrollBars(false);
        scroll.setScrollbarsOnTop(false);
        return scroll;
    }

    /** A step chart in the Token v0 look, such as damage by range ({@link UiStepChart}). */
    public UiStepChart stepChart() {
        return new UiStepChart(this);
    }

    /** A ring of arcs around a centre picture in the Token v0 look, such as damage by firing arc. */
    public UiArcDiagram arcDiagram() {
        return new UiArcDiagram(this);
    }

    /** Equal-size visual choices that fit their content and viewport, with bounded growth when space permits. */
    public UiChoiceGrid choiceGrid(float cardWidth, float gap) {
        return new UiChoiceGrid(cardWidth, gap);
    }

    /**
     * Fits a dialog's choice grid inside the available viewport, measuring its existing header/footer/padding.
     * The dialog's parent should use its preferred size. Excess choices scroll vertically in the supplied pane.
     */
    public void fitChoices(Table dialog, ScrollPane scroll, UiChoiceGrid choices, float width, float height) {
        Cell<ScrollPane> cell = dialog.getCell(scroll);
        cell.height(0);
        dialog.invalidate();
        float chromeHeight = dialog.getPrefHeight();
        float chromeWidth = dialog.getPadX() + cell.getPadLeft() + cell.getPadRight();
        choices.fit(width - chromeWidth, height - chromeHeight);
        cell.width(choices.getPrefWidth()).height(Math.min(choices.getPrefHeight(), Math.max(1, height - chromeHeight)));
        dialog.invalidateHierarchy();
    }

    /** Optional search row: collapse it when all choices are visible, but keep a filter accessible until cleared. */
    public void fitSearch(Cell<SearchField> row, boolean hiddenChoices) {
        SearchField search = row.getActor();
        boolean shown = hiddenChoices || !search.field.getText().isEmpty();
        search.setVisible(shown);
        if (!shown && search.field.hasKeyboardFocus()) { search.getStage().setKeyboardFocus(null); }
        row.height(shown ? search.getPrefHeight() : 0).pad(0, 8, shown ? 8 : 0, 8);
        row.getTable().invalidateHierarchy();
    }

    /**
     * Gives {@code actor} the stage's scroll focus while the pointer is over it, so the wheel reaches it without a
     * click, and gives the focus back when the pointer leaves it and its children, so the wheel then reaches what lies
     * there, such as the view's camera. A pane inside it that can scroll and holds the pointer, such as a list in a
     * dialog's scrolling body, keeps the wheel: the enter reaches it first, and the pane round it leaves it there.
     */
    public static void wheelWhileHovered(Actor actor) {
        actor.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                Stage stage = actor.getStage();
                if (stage == null) {
                    return;
                }
                if (actor instanceof ScrollPane pane && !pane.isScrollX() && !pane.isScrollY()) {
                    return;
                }
                Actor focus = stage.getScrollFocus();
                if (focus != null && focus != actor && focus.isDescendantOf(actor)
                      && event.getTarget().isDescendantOf(focus)) {
                    return;
                }
                stage.setScrollFocus(actor);
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                Stage stage = actor.getStage();
                if (stage != null && stage.getScrollFocus() == actor
                      && (toActor == null || !toActor.isDescendantOf(actor))) {
                    stage.setScrollFocus(null);
                }
            }
        });
    }

    /** Resolve wheel focus at dispatch time: replacing a hovered widget need not produce a Scene2D exit event. */
    public static void focusScrollAt(Stage stage, int screenX, int screenY) {
        var point = stage.screenToStageCoordinates(new com.badlogic.gdx.math.Vector2(screenX, screenY));
        stage.setScrollFocus(stage.hit(point.x, point.y, true));
    }

    /** A label in a hud font at another size and color. */
    public Label label(String text, String font, float size, Color color) {
        Label label = new Label(text, new Label.LabelStyle(skin.getFont(font), color));
        size(label, font, size);
        return label;
    }

    /**
     * Draws a label in hud font {@code font} at {@code size} stage units. Label.setFontScale replaces the font's own
     * quarter scale (S1), so the font's scale is multiplied in.
     */
    public static void size(Label label, String font, float size) {
        float tableSize = UiTheme.HUD_FONTS.stream().filter(entry -> entry.name().equals(font)).findFirst()
              .orElseThrow().size();
        label.setFontScale(label.getStyle().font.getScaleX() * size / tableSize);
    }

    /** A label in a Token v0 type style ({@link UiTheme#TYPE_STYLES}) in the color of {@code role}. */
    public Label typeLabel(String text, String type, String role) {
        Label.LabelStyle style = new Label.LabelStyle(skin.get(UiTheme.TYPE + type, Label.LabelStyle.class));
        style.fontColor = UiTheme.tokenColor(role);
        return new Label(text, style);
    }

    /**
     * A label for text role {@code textRole} ("title", "headline", "display", "overline", "name") in its type style
     * and the color of {@code colorRole}, in the kit's text mode: upper case when the role is, and in the style's
     * condensed copy (upper case at its caps tracking, or sentence case) when the role is condensed.
     */
    public Label roleTypeLabel(String textRole, String text, String colorRole) {
        String type = DesignTokens.shipped().roleStyle(textRole).name();
        boolean upper = upperCase(textRole);
        if (condensed(textRole)) {
            String copy = type + (upper ? UiTheme.CAPS : UiTheme.CONDENSED);
            if (skin.has(UiTheme.TYPE + copy, Label.LabelStyle.class)) {
                return typeLabel(cased(textRole, text), copy, colorRole);
            }
        }
        return typeLabel(cased(textRole, text), type, colorRole);
    }

    /** The prototype's caption (.cap): upper-case condensed muted text. */
    public Label caption(String text) {
        return new Label(UiTheme.upper(text), skin, "hud-caption");
    }

    /**
     * A caption as {@link #caption(String)}; with {@code token}, the overline role in text.muted, condensed in game
     * mode.
     */
    public Label caption(String text, boolean token) {
        return token ? roleTypeLabel("overline", text, "text.muted") : caption(text);
    }

    /**
     * A theme icon (a Material symbol, or an image icon a view added) at a CSS size; inside a button it takes the
     * button's text color.
     */
    public Icon icon(String name, float size, Color color) {
        Icon icon = new Icon(skin.getDrawable("icon-" + name), size);
        icon.setColor(color);
        return icon;
    }

    /**
     * A solid fill in a token colour role, such as a team's colour bar; its size is its cell's. An area filled with a
     * colour from the game, such as a player's swatch, takes {@link #solid(Color)}.
     */
    public Image solid(String role) {
        return solid(UiTheme.tokenColor(role));
    }

    /** A solid fill in {@code color}, sized by its cell, such as a player's colour swatch. */
    public Image solid(Color color) {
        return new Image(skin.newDrawable("white", color));
    }

    /**
     * A card (Token v0's {@code token-card}: surface.panel with a border.subtle edge and the md radius), padded sm and md
     * as a list card is, for one item of a list such as a lobby player; its rows are the view's.
     */
    public Table card() {
        Table card = new Table();
        card.setBackground(skin.getDrawable("token-card"));
        card.pad(space("sm"), space("md"), space("sm"), space("sm"));
        return card;
    }

    /**
     * A panel (.panel): the frame, padded only by its 2-unit rails and transparent side borders, with its rows from the
     * top; headers, lists and footers bring their own padding. A panel that pads its content itself sets its padding.
     */
    public Table panel() {
        return panel(new Table());
    }

    /** The panel's look on {@code panel}, a table that lays out more than its cells. */
    public Table panel(Table panel) {
        panel.setBackground(skin.getDrawable("panel"));
        panel.pad(2);
        panel.top();
        return panel;
    }

    /**
     * A panel as {@link #panel()}; with {@code token}, the Token v0 panel ({@link UiTheme#TOKEN_PANEL}): the edge and
     * corner ticks, padded lg all round, so its header, body and footer bring no padding of their own.
     */
    public Table panel(boolean token) {
        if (!token) {
            return panel();
        }
        Table panel = new Table();
        panel.setBackground(skin.getDrawable(UiTheme.TOKEN_PANEL));
        panel.top();
        return panel;
    }

    /**
     * A panel inside a panel (Token v0's {@link UiTheme#TOKEN_PANEL_PLAIN}): the edge and fill without the corner ticks,
     * padded lg, since only the outermost frame has ticks; such as a section card in a framed view.
     */
    public Table plainPanel() {
        Table panel = new Table();
        panel.setBackground(skin.getDrawable(UiTheme.TOKEN_PANEL_PLAIN));
        panel.top();
        return panel;
    }

    /**
     * A panel header (.phd): the upper-case title, an optional muted count (.n) beside it, and tools at the right:
     * buttons, or a count that a panel shows there.
     */
    public Table header(String title, String count, Actor... tools) {
        Table header = new Table();
        header.pad(11, 14, 8, 14);
        // .phd is at least 44 units tall; a title too long for the panel ends in an ellipsis (.phd .t).
        Label heading = new Label(UiTheme.upper(title), skin, "hud-title");
        heading.setEllipsis(true);
        header.add(heading).minHeight(25).minWidth(0);
        if (count != null) {
            Label counted = label(count, "hud-medium", 12, UiTheme.MUTED);
            counted.setName(HEADER_COUNT);
            header.add(counted).padLeft(8);
        }
        header.add().expandX();
        for (Actor tool : tools) {
            header.add(tool).padLeft(8);
        }
        return header;
    }

    /**
     * A panel header as {@link #header(String, String, Actor...)}; with {@code token}, the Token v0 header for a token
     * panel: the title in the {@code title} type (upper case in game mode) and text.primary, the count in text.muted,
     * the tools xs apart, and md above the body.
     */
    public Table header(String title, String count, boolean token, Actor... tools) {
        return token ? tokenHeader(title, count, "title", tools) : header(title, count, tools);
    }

    private Table tokenHeader(String title, String count, String type, Actor... tools) {
        Table header = new Table();
        header.padBottom(space("md"));
        Label heading = roleTypeLabel(type, title, "text.primary");
        heading.setEllipsis(true);
        header.add(heading).minWidth(0);
        if (count != null) {
            Label counted = typeLabel(count, "body", "text.muted");
            counted.setName(HEADER_COUNT);
            header.add(counted).padLeft(space("sm"));
        }
        header.add().expandX();
        for (Actor tool : tools) {
            header.add(tool).padLeft(space("xs"));
        }
        return header;
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** A close button (.b.ib with the close icon), as a header's tool, which runs {@code action}. */
    public UiButton closeButton(Runnable action) {
        return closeButton(action, false);
    }

    /** A close button as {@link #closeButton(Runnable)}; with {@code token}, the compact icon button. */
    public UiButton closeButton(Runnable action, boolean token) {
        UiButton close = button(token ? "button-icon" + UiTheme.COMPACT : "hud-icon", "close", null, null);
        onChange(close, action);
        return close;
    }

    /**
     * A dialog (.panel.dlg): a panel padded 0 18 16 18 inside its rails and side borders, and its header (.dlg .phd,
     * padded 12 0 8) with the title and a close button that runs {@code close}. The view adds its rows below the
     * header; its owner centres it, at most {@link #DIALOG_WIDTH} wide and {@link #DIALOG_MARGIN} less than the window
     * high, with a body that scrolls.
     */
    public Table dialog(String title, Runnable close) {
        Table dialog = panel();
        dialog.pad(2, 20, 18, 20);
        Table header = header(title, null, closeButton(close));
        header.pad(12, 0, 8, 0);
        dialog.add(header).growX().row();
        return dialog;
    }

    /**
     * A dialog as {@link #dialog(String, Runnable)}; with {@code token}, the Token v0 dialog card: the token panel
     * padded xl above the header and xxl elsewhere, and a header with the title in the {@code headline} type (upper
     * case in game mode) and a compact close button, xl above the body. Its owner makes it dialog.sm (420) wide or
     * wider.
     */
    public Table dialog(String title, Runnable close, boolean token) {
        if (!token) {
            return dialog(title, close);
        }
        Table dialog = panel(true);
        dialog.pad(space("xl"), space("xxl"), space("xxl"), space("xxl"));
        Table header = tokenHeader(title, null, "headline", closeButton(close, true));
        header.padBottom(space("xl"));
        dialog.add(header).growX().row();
        return dialog;
    }

    /**
     * Ends {@code panel} with its footer (.pfoot): the rule, then a foot row padded 8 14 9 14, which it returns for the
     * panel's line and actions.
     */
    public Table footer(Table panel) {
        panel.add(new Image(skin.getDrawable("rule"))).growX().height(1).row();
        Table foot = new Table();
        foot.pad(8, 14, 9, 14);
        panel.add(foot).growX();
        return foot;
    }

    /**
     * Ends {@code panel} with its footer as {@link #footer(Table)}; with {@code token}, a border.subtle hairline md
     * below the body, then the foot row md below it, inside the token panel's padding.
     */
    public Table footer(Table panel, boolean token) {
        if (!token) {
            return footer(panel);
        }
        panel.add(new Image(skin.getDrawable("token-divider"))).growX()
              .height(DesignTokens.shipped().size().get("border")).padTop(space("md")).row();
        Table foot = new Table();
        foot.padTop(space("md"));
        panel.add(foot).growX();
        return foot;
    }

    /** A list's empty state (.empty): {@code text}, wrapped and muted, as the list's only row. */
    public Label empty(Table list, String text) {
        Label empty = label(text, "hud-body", 12.5f, UiTheme.MUTED);
        empty.setWrap(true);
        list.add(empty).growX().pad(16, 14, 16, 14);
        return empty;
    }

    /**
     * A list's empty state as {@link #empty(Table, String)}; with {@code token}, the Token v0 look: {@code text} in
     * text.muted, centred in the first row's place (control.sm high).
     */
    public Label empty(Table list, String text, boolean token) {
        if (!token) {
            return empty(list, text);
        }
        Label empty = label(text, "hud-body", 13, UiTheme.tokenColor("text.muted"));
        empty.setWrap(true);
        empty.setAlignment(Align.center);
        list.add(empty).growX().minHeight(DesignTokens.shipped().size().get("control.sm"));
        return empty;
    }

    /**
     * A button in a hud style with an optional icon, caption and sub-label (.b small); captions are upper-cased where
     * the prototype does it. The parts stack in a top-right utility and above a sub-label; otherwise the icon leads the
     * caption on one line. Styles: hud (quiet, .b), hud-plain (normal-case .brow .b), hud-main (commit), hud-mini,
     * hud-icon (30-unit .b.ib), hud-seg, hud-utility (icon over label), hud-utility-row (.b.util.row) and
     * hud-utility-small (.b.brk.sm); and the token button set, button-&lt;kind&gt; and button-&lt;kind&gt;-compact
     * ({@link UiTheme#BUTTON_KINDS}), which new views use: their labels follow the kit's {@link #presentation()}.
     */
    public UiButton button(String style, String icon, String text, String sub) {
        // Labelled token buttons, tabs and segments have a caps copy; icon buttons show a glyph and have none
        String role = style.startsWith(UiTheme.TOKEN_TAB) || style.startsWith(UiTheme.TOKEN_SEGMENT) ? "tab" : "button";
        boolean tokenCaps = skin.has(style + UiTheme.CAPS, TextButton.TextButtonStyle.class) && upperCase(role);
        UiButton button = new UiButton(this, tokenCaps ? style + UiTheme.CAPS : style);
        boolean column = style.equals("hud-utility") || sub != null;
        float gap = gap(style, column);
        if (icon != null) {
            Icon image = icon(icon, iconSize(style), Color.WHITE);
            button.icons.add(image);
            button.add(image);
            if (column) {
                button.row();
            }
        }
        if (text != null) {
            if (tokenCaps) {
                button.setText(cased(role, text));
            } else {
                button.setText(UPPER_CASE.contains(style) ? UiTheme.upper(text) : text);
            }
            if (style.equals("hud-plain")) {
                // .brow .b is 12 units, half a unit smaller than hud-medium.
                size(button.getLabel(), "hud-medium", 12);
            }
            button.add(button.getLabel()).padLeft(icon != null && !column ? gap : 0)
                  .padTop(icon != null && column ? gap : 0);
        }
        if (sub != null) {
            Label small = label(sub, "hud-sub", 10, Color.WHITE);
            button.details.add(small);
            button.row();
            button.add(small).padTop(gap);
        }
        return button;
    }

    /**
     * The widest of a token button's faces: its preferred width showing each of {@code texts}, cased as a button
     * label, so a button whose text changes, or a matched pair of buttons, can keep one width. The button shows its
     * text as before afterwards.
     */
    public float faceWidth(UiButton button, String... texts) {
        CharSequence shown = button.getText();
        String kept = shown == null ? "" : shown.toString();
        float width = 0;
        for (String text : texts) {
            button.setText(cased("button", text));
            width = Math.max(width, button.getPrefWidth());
        }
        button.setText(kept);
        return width;
    }

    /** The gap between a button's parts: .b's 1 unit when they stack, a utility's 4, and the row gaps per style. */
    private static float gap(String style, boolean column) {
        if (column) {
            return style.equals("hud-utility") ? 4 : 1;
        }
        return switch (style) {
            case "hud-mini", "hud-confirm" -> 5;
            case "hud-plain" -> 7;
            default -> 6;
        };
    }

    private static float iconSize(String style) {
        if (style.startsWith("button-")) {
            return DesignTokens.shipped().size().get(style.endsWith(UiTheme.COMPACT) ? "icon.sm" : "icon.md");
        }
        return switch (style) {
            case "hud-icon" -> 16;
            case "hud-mini", "hud-confirm", "hud-confirm-clear", "hud-confirm-step" -> 13;
            case "hud-utility", "hud-utility-row" -> 19;
            default -> 17;
        };
    }

    /**
     * Segment buttons (.seg, style hud-seg) or underline tabs (.tabs: hud-tab; the log's .ltabs: hud-tab-caps); flex
     * ones share the width, natural ones keep theirs (.ovbar .seg button).
     */
    public Segmented segmented(String style, boolean flex, String... labels) {
        return new Segmented(style, flex, labels);
    }

    /** A drop-down's face (.fsel; inline: .amsel): the current choice and a chevron. Its view opens the list. */
    public UiButton select(String text, boolean inline) {
        return select(text, inline, false);
    }

    /**
     * A drop-down's face as {@link #select(String, boolean)}; with {@code token}, in the Token v0 look (the input box,
     * or a dotted underline when inline, and a text.secondary chevron).
     */
    public UiButton select(String text, boolean inline, boolean token) {
        String style = inline ? token ? UiTheme.TOKEN_SELECT_INLINE : "hud-select-inline"
              : token ? UiTheme.TOKEN_SELECT : "hud-select";
        UiButton select = new UiButton(this, style);
        select.setText(text);
        size(select.getLabel(), inline ? "hud-small" : "hud-body", inline ? 11 : 12);
        select.getLabel().setAlignment(Align.left);
        select.getLabel().setEllipsis(true);
        select.add(select.getLabel()).growX().minWidth(0);
        if (token) {
            Icon chevron = icon("chevron-down", UiTheme.FIELD_ICON, Color.WHITE);
            select.iconColors.put(chevron, skin.get(UiTheme.SelectStyle.class).chevron);
            select.add(chevron).size(UiTheme.FIELD_ICON).padLeft(DesignTokens.shipped().space().get("xs"));
        } else {
            Icon chevron = icon("chevron-down", 12, Color.WHITE);
            select.icons.add(chevron);
            select.add(chevron).padLeft(inline ? 2 : 6);
        }
        return select;
    }

    /** A checkbox (.tog): the box, mint while ticked, and its label; a click on either flips it. */
    public Checkbox checkbox(String text, boolean ticked) {
        return new Checkbox(text, ticked, false);
    }

    /**
     * A checkbox as {@link #checkbox(String, boolean)}; with {@code token}, in the Token v0 look (an input box with a
     * control edge, an accent fill and an onAccent check when ticked).
     */
    public Checkbox checkbox(String text, boolean ticked, boolean token) {
        return new Checkbox(text, ticked, token);
    }

    /**
     * A menu or popover item (.pop .it): an optional check mark (null: the item is no toggle), the label, a detail such
     * as a shortcut, and a chevron when the item opens a group. The keyboard highlight is the pressed state.
     */
    public UiButton menuRow(String text, String detail, Boolean checked, boolean group) {
        return menuRow("hud-menu-row", text, detail, checked, group);
    }

    /**
     * A menu item as {@link #menuRow(String, String, Boolean, boolean)} in {@code style}: hud-menu-row, or
     * {@link UiTheme#TOKEN_MENU_ROW} for the Token v0 look with a selection-color check and a text.secondary chevron.
     */
    public UiButton menuRow(String style, String text, String detail, Boolean checked, boolean group) {
        UiButton row = new UiButton(this, style);
        boolean token = style.equals(UiTheme.TOKEN_MENU_ROW);
        UiTheme.SelectStyle colors = token ? skin.get(UiTheme.SelectStyle.class) : null;
        float gap = token ? DesignTokens.shipped().space().get("sm") : 10;
        if (checked != null) {
            Icon mark = icon("check", 16, Color.WHITE);
            mark.setVisible(checked);
            if (token) {
                row.iconColors.put(mark, colors.check);
            } else {
                row.icons.add(mark);
            }
            row.add(mark).padRight(gap);
        }
        row.setText(text);
        row.getLabel().setAlignment(Align.left);
        row.add(row.getLabel()).growX();
        if (detail != null && !detail.isEmpty()) {
            Label shortcut = label(detail, "hud-small", 11, Color.WHITE);
            row.details.add(shortcut);
            row.add(shortcut).padLeft(gap);
        }
        if (group) {
            Icon chevron = icon("chevron-right", 12, Color.WHITE);
            if (token) {
                row.iconColors.put(chevron, colors.chevron);
            } else {
                row.icons.add(chevron);
            }
            row.add(chevron).padLeft(gap);
        }
        return row;
    }

    /** A chip (.chip, .chip.w, .chip.x). */
    public Label chip(String text, Tone tone) {
        Label chip = label(UiTheme.upper(text), "hud-button", 10.5f, tone.color);
        Label.LabelStyle style = chip.getStyle();
        style.background = skin.getDrawable(tone == Tone.WARN ? "chip-warn" : tone == Tone.BAD ? "chip-bad" : "chip");
        chip.setStyle(style);
        return chip;
    }

    /**
     * A chip in hud-v3's look, or with {@code token} Token v0's status chip: OK, WARN and BAD with their status icon
     * and a 1px edge in the status color over text.primary, NORMAL the neutral chip with a border.control edge and
     * text.secondary; small type, in upper case as the overline in either presentation.
     */
    public Actor chip(String text, Tone tone, boolean token) {
        if (!token) {
            return chip(text, tone);
        }
        String status = switch (tone) {
            case OK -> "success";
            case WARN -> "warning";
            case BAD -> "danger";
            default -> null;
        };
        if (status == null) {
            return tokenChip("neutral", null, null, text, "text.secondary");
        }
        String icon = switch (tone) {
            case OK -> "check";
            case WARN -> "warn";
            default -> "error";
        };
        return tokenChip(status, icon, "status." + status, text, "text.primary");
    }

    /**
     * Token v0's status chip without an icon, its edge in the {@link UiTheme#TOKEN_CHIP} frame {@code edge} (neutral,
     * accent, success, warning or danger) and its text in text.primary, such as a side's Battle Value edged in the
     * side's colour. It has the status chip's height, padding and type ({@link #chip(String, Tone, boolean)}).
     */
    public Table edgeChip(String text, String edge) {
        return tokenChip(edge, null, null, text, "text.primary");
    }

    /**
     * The Game Master's status chip: the status chip with a {@code status.gamemaster} edge and shield, as the Game
     * Master's toast has, so the referee never reads as an ordinary status.
     */
    public Table gameMasterChip(String text) {
        return tokenChip("gamemaster", "shield-person", "status.gamemaster", text, "text.primary");
    }

    /**
     * A status chip as {@link #chip(String, Tone, boolean)} that acts on a click: under the pointer its status color
     * washes it, pressed or checked it fills ({@link UiTheme#TOKEN_CHIP_BUTTON}). Check it for a moment to light it,
     * as when it asks to be seen.
     */
    public Button actingChip(String text, Tone tone) {
        String frame = switch (tone) {
            case OK -> "success";
            case WARN -> "warning";
            case BAD -> "danger";
            default -> "neutral";
        };
        Button chip = new Button(skin, UiTheme.TOKEN_CHIP_BUTTON + frame);
        chip.setProgrammaticChangeEvents(false);
        fillChip(chip, frame.equals("neutral") ? null : switch (tone) {
            case OK -> "check";
            case WARN -> "warn";
            default -> "error";
        }, frame.equals("neutral") ? null : "status." + frame, text,
              frame.equals("neutral") ? "text.secondary" : "text.primary");
        return chip;
    }

    /**
     * A compact whole-number stepper ({@link UiStepper}): − {@code value} +, within {@code min} and {@code max}.
     */
    public UiStepper stepper(int value, int min, int max) {
        return new UiStepper(this, null, value, min, max);
    }

    /**
     * A health bar ({@link UiHealthBar}) for {@code value} of {@code max}, such as a location's armor: click or drag
     * the bar, wheel over it, step with − and + or the arrow keys, or type the value; the change from {@code original}
     * shows at its end.
     */
    public UiHealthBar healthBar(int value, int max, int original) {
        return new UiHealthBar(this, value, max, original);
    }

    /**
     * A row of level tiles ({@link UiLevelTiles}) named {@code names}, each with a pip meter of its step lit in its
     * colour role from {@code roles}, such as how much damage to roll.
     */
    public UiLevelTiles levelTiles(String[] names, String[] roles) {
        return new UiLevelTiles(this, names, roles);
    }

    /**
     * A stepper as {@link #stepper(int, int, int)} led by {@code caption} in small text.secondary, the caption, the
     * buttons and the value all {@link UiStepper#gap()} apart: one group, which stands a larger space from the next.
     */
    public UiStepper stepper(String caption, int value, int min, int max) {
        return new UiStepper(this, caption, value, min, max);
    }

    /**
     * A selector ({@link UiStepper}): one of {@code choices}, the {@code index}th first, between chevrons that step
     * through them, as a game's settings menu picks a value; for a short ordered run of choices, such as a round, where
     * a dropdown would be a long list. Its value is the chosen index.
     */
    public UiStepper selector(List<String> choices, int index) {
        return new UiStepper(this, choices, index);
    }

    /**
     * A row of status chips that keeps each chip whole ({@link UiChipRow}): those past its width fold into
     * {@code more}'s "+n", with {@code folded} chips already left out before it.
     */
    public UiChipRow chipRow(List<Actor> chips, int folded, java.util.function.IntFunction<String> more) {
        return new UiChipRow(this, chips, folded, more);
    }

    /**
     * A filter chip ({@link UiFilterChip}): a control, so {@code control.sm} high like the compact buttons, fields and
     * selects beside it, in the status chip's type; at each end it keeps {@code md}, {@code icon.sm} and {@code xs}, so
     * its text is centred; toggling on a click, on it fills in the accent's pressed tint and a check shows in its
     * leading room. The view reads {@code isChecked()} on its change.
     */
    public UiFilterChip filterChip(String text) {
        DesignTokens tokens = DesignTokens.shipped();
        float iconSize = tokens.size().get("icon.sm");
        float vertical = (tokens.size().get("control.sm") - iconSize) / 2;
        return new UiFilterChip(skin, icon("check", iconSize, UiTheme.tokenColor("accent.primary")),
              typeLabel(cased(CHIP_CASE, text), "small", "text.secondary"), iconSize, vertical, space("md"),
              space("xs"));
    }

    /**
     * A count badge standing beside a title ({@link UiCountBadge}), such as the unread lines on a folded chat's header;
     * a badge on a button's corner is {@link UiButton#badge}. Zero hides it.
     */
    public UiCountBadge countBadge(int count) {
        return new UiCountBadge(skin, count);
    }

    /**
     * The status chip's geometry: {@code icon.sm} plus {@code xxs} above and below, 20 high whatever the type's line
     * height; {@code xs} before an icon or {@code md} before the text, {@code md} after it; small type, cased as
     * {@link #CHIP_CASE}.
     */
    private Table tokenChip(String frame, String icon, String iconRole, String text, String textRole) {
        Table chip = new Table();
        chip.setBackground(skin.getDrawable(UiTheme.TOKEN_CHIP + frame));
        fillChip(chip, icon, iconRole, text, textRole);
        return chip;
    }

    private void fillChip(Table chip, String icon, String iconRole, String text, String textRole) {
        DesignTokens tokens = DesignTokens.shipped();
        float iconSize = tokens.size().get("icon.sm");
        float xxs = space("xxs");
        float xs = space("xs");
        float md = space("md");
        chip.pad(xxs, icon == null ? md : xs, xxs, md);
        if (icon != null) {
            chip.add(icon(icon, iconSize, UiTheme.tokenColor(iconRole))).size(iconSize).padRight(xs);
        }
        chip.add(typeLabel(cased(CHIP_CASE, text), "small", textRole)).height(iconSize);
    }

    /**
     * A rail: a folded panel standing at the window's edge, as the token panel with {@code icon} at its top and
     * {@code title} read downwards in the title role. It toggles, its edge lighting on hover, and shows selected while
     * checked ({@link UiTheme#TOKEN_RAIL}); the view opens and closes the folded panel on its change.
     */
    public Button rail(String title, String icon) {
        Button rail = new Button(skin, UiTheme.TOKEN_RAIL);
        rail.top();
        rail.add(icon(icon, DesignTokens.shipped().size().get("icon.md"), UiTheme.tokenColor("text.secondary")))
              .row();
        rail.add(new UiVerticalText(roleTypeLabel("title", title, "text.secondary"))).padTop(space("md"));
        return rail;
    }

    /** A search field (.field) with its placeholder, and a clear button while it holds text. */
    public SearchField search(String placeholder) {
        return new SearchField(placeholder, false);
    }

    /**
     * A search field as {@link #search(String)}; with {@code token}, in the Token v0 look: the {@code input-search}
     * style with a leading search glyph and a compact icon button to clear it.
     */
    public SearchField search(String placeholder, boolean token) {
        return new SearchField(placeholder, token);
    }

    /**
     * Gives a field in a token {@code input} style the {@code border.strong} edge under the pointer anywhere over
     * {@code root} (the field, or a stack that draws parts over it), which scene2d's TextField lacks. Returns the field.
     */
    public static <T extends TextField> T hoverEdge(T field, Actor root) {
        if (!(field.getStyle() instanceof UiTheme.TokenFieldStyle rest) || rest.over == null) {
            return field;
        }
        TextField.TextFieldStyle over = new TextField.TextFieldStyle(rest);
        over.background = rest.over;
        root.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1) {
                    field.setStyle(over);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(root))) {
                    field.setStyle(rest);
                }
            }
        });
        return field;
    }

    /**
     * The token slider (docs/design-system/tokens.md, Sliders): a pill track and the accent fill up to the thumb, a thin
     * light bar in the game presentation and an accent disc in the app one.
     */
    public com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle tokenSliderStyle() {
        return new com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle(
              skin.get(presentation == Presentation.GAME ? UiTheme.TOKEN_BAR : UiTheme.TOKEN,
                    com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle.class));
    }

    /** A token text input (docs/design-system/tokens.md, Inputs); compact is control.sm high, otherwise control.md. */
    public UiTextField input(String text, boolean compact) {
        return new UiTextField(text, skin, compact);
    }

    /** A token password input: the text shows as bullets. */
    public UiTextField password(boolean compact) {
        UiTextField field = input("", compact);
        field.setPasswordMode(true);
        return field;
    }

    /** A token password input with a trailing reveal button that shows the text while it is on. */
    public TokenPassword tokenPassword(boolean compact) {
        return new TokenPassword(compact);
    }

    /** A token search input: a leading search glyph, the placeholder, and a clear button while it holds text. */
    public TokenSearch tokenSearch(String placeholder, boolean compact) {
        return new TokenSearch(placeholder, compact);
    }

    /** A token text area, wrapping its lines, {@code rows} lines high. */
    public UiTextField.Area area(String text, float rows) {
        UiTextField.Area area = new UiTextField.Area(text, skin);
        area.setPrefRows(rows);
        return area;
    }

    /** A token form field: {@code label} above the input, and a helper or status message below it. */
    public UiFormField formField(String label, Actor input) {
        return new UiFormField(this, label, input);
    }

    /**
     * Whether {@code input} takes the whole width of its form column, so the right edges of a form's inputs line up:
     * selects, text, search and range inputs, pick lists and rows built of them do; check boxes, radios, switches,
     * segmented controls, buttons, chips, steppers and number inputs keep their own width. A wrapping group of chips
     * fills it too: it has no width of its own and would otherwise stack its chips one per row.
     */
    public static boolean fillsFormColumn(Actor input) {
        if (input instanceof HorizontalGroup group) {
            return group.getWrap();
        }
        return !(input instanceof Button || input instanceof UiOptionBar || input instanceof UiStepper
              || input instanceof UiNumber);
    }

    /**
     * Adds a labelled row to {@code form}: {@code label} on the left, {@code labelWidth} wide and at the input's top,
     * and {@code input} beside it, across the rest of the column when {@link #fillsFormColumn} says so. Every kit form
     * built of these rows lines up its inputs' right edges.
     */
    public static Cell<Actor> formRow(Table form, Actor label, Actor input, float labelWidth) {
        float gap = DesignTokens.shipped().space().get("md");
        form.add(label).width(labelWidth).top().left().padRight(gap);
        Cell<Actor> cell = form.add(input).expandX().fill(fillsFormColumn(input) ? 1f : 0f, 0f).minWidth(0).left();
        form.row();
        return cell;
    }

    /** A token check box (docs/design-system/tokens.md, Choices): for a choice a form confirms. */
    public UiChoice check(String text, boolean checked) {
        return new UiChoice(this, UiChoice.Kind.CHECK, text, checked);
    }

    /** A token radio button; put it in a {@link #radioGroup} with the others of its choice. */
    public UiChoice radio(String text, boolean checked) {
        return new UiChoice(this, UiChoice.Kind.RADIO, text, checked);
    }

    /** A token switch: for a setting that applies at once. */
    public UiChoice toggleSwitch(String text, boolean on) {
        return new UiChoice(this, UiChoice.Kind.SWITCH, text, on);
    }

    /**
     * A card that shows a picture and opens something when clicked ({@link UiPictureCard}); {@code text} shows, muted,
     * until it has a picture, in a card {@code aspect} times as high as it is wide.
     */
    public UiPictureCard pictureCard(String text, float aspect) {
        return new UiPictureCard(this, text, aspect);
    }

    /**
     * A card that sums something up under {@code title} and opens it when clicked ({@link UiSummaryCard}), such as the
     * lobby's planetary conditions; its summary is set with {@link UiSummaryCard#summary(String)}.
     */
    public UiSummaryCard summaryCard(String title) {
        return new UiSummaryCard(this, title);
    }

    /** Makes radio buttons exclusive: exactly one stays selected, the first checked one or else the first. */
    public com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup<UiChoice> radioGroup(UiChoice... radios) {
        var group = new com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup<UiChoice>();
        group.setMinCheckCount(1);
        group.setMaxCheckCount(1);
        group.setUncheckLast(true);
        group.add(radios);
        return group;
    }

    /** Token tabs (docs/design-system/tokens.md, Tabs), the first selected; compact ones are for panels. */
    public UiTabs tokenTabs(boolean compact, String... labels) {
        return new UiTabs(this, compact, labels);
    }

    /** A token segmented control (docs/design-system/tokens.md, Segmented control), the first option selected. */
    public UiSegmented tokenSegmented(boolean compact, String... labels) {
        return new UiSegmented(this, compact, labels);
    }

    /** A token select in an input box (docs/design-system/tokens.md, Select), the first item current. */
    public UiSelect tokenSelect(boolean compact, String... items) {
        return new UiSelect(this, compact ? UiSelect.Kind.COMPACT : UiSelect.Kind.REGULAR, items);
    }

    /**
     * A whole-number span ({@link UiRangeInput}): two compact digit-only inputs with {@code minText} and
     * {@code maxText} as their placeholders; an empty end is open.
     */
    public UiRangeInput rangeInput(String minText, String maxText) {
        return new UiRangeInput(this, minText, maxText);
    }

    /**
     * Picks from a long list ({@link UiPickList}): a compact select showing {@code placeholder} that adds
     * {@code items} as removable chips; with {@code excludes}, a click switches a chip between must-have and must-not.
     */
    public UiPickList pickList(String placeholder, java.util.List<String> items, boolean excludes) {
        return new UiPickList(this, placeholder, items, excludes);
    }

    /** An inline token select: text with a dotted underline, for a value inside a sentence or a dense row. */
    public UiSelect tokenSelectInline(String... items) {
        return new UiSelect(this, UiSelect.Kind.INLINE, items);
    }

    /**
     * A sort control over a list: the caption, an inline select of {@code keys} by their {@code names} and a direction
     * button whose tooltip reads {@code ascending} or {@code descending}; it shows {@code initial} first.
     */
    public <K extends TableSort.Key> UiSortControl<K> sortControl(String caption, String ascending, String descending,
          List<K> keys, Function<K, String> names, TableSort<K> initial) {
        return new UiSortControl<>(this, caption, ascending, descending, keys, names, initial);
    }

    /**
     * A sortable column's title, {@code text} as an overline, its arrow following the name, or leading it with
     * {@link Align#right} for a column of numbers.
     */
    public UiSortHeader sortHeader(String text, int align) {
        return new UiSortHeader(this, text, align);
    }

    /** A token popup list on its own, as a gallery shows it; a select makes its own. */
    public UiListPopup tokenListPopup(String... items) {
        return new UiListPopup(this, items);
    }

    /** Shared numeric controls use the theme's track, mint fill and narrow handle. */
    public com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle sliderStyle() {
        var track = skin.newDrawable("white", UiTheme.TRACK); track.setMinHeight(4); track.setMinWidth(0);
        var knob = skin.newDrawable("white", UiTheme.FILL); knob.setMinWidth(4); knob.setMinHeight(14);
        var filled = skin.newDrawable("white", UiTheme.MINT); filled.setMinHeight(4); filled.setMinWidth(0);
        var style = new com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle(track, knob);
        style.knobBefore = filled;
        var active = skin.newDrawable("white", Color.WHITE); active.setMinWidth(4); active.setMinHeight(14);
        style.knobDown = active; style.knobOver = active;
        return style;
    }

    /** A labelled bar (.meter; compact: the overview card's .bars2). */
    public Meter meter(String caption, boolean compact) {
        return new Meter(caption, compact, new Bar(compact ? 3 : 4), false);
    }

    /**
     * A meter in hud-v3's look, or with {@code token} Token v0's progress bar: the caption above in small type and
     * text.secondary (caption type when compact), the value at the trailing end in text.primary, sentence case, over
     * a 4-high border.control pill track with a pill fill in the color {@link Meter#set} gives.
     */
    public Meter meter(String caption, boolean compact, boolean token) {
        return token ? new Meter(caption, compact, new TokenBar(), true) : meter(caption, compact);
    }

    /** A meter's caption and value over a bar that its view draws, such as a segmented or hatched bar. */
    public Meter meter(String caption, Widget bar) {
        return new Meter(caption, false, bar, false);
    }

    /** A progress bar (.progress): a meter whose value is the percentage done, over the mint share of its bar. */
    public Progress progress(String caption) {
        return new Progress(caption);
    }

    /** A rectangle of the white texel in a color, at the widget's alpha. */
    public void fill(Batch batch, Color color, float alpha, float x, float y, float width, float height) {
        if (width > 0) {
            batch.setColor(color.r, color.g, color.b, color.a * alpha);
            batch.draw(white, x, y, width, height);
        }
    }

    /** An icon Image whose preferred size is its CSS size rather than its 48-texel cell. */
    public static final class Icon extends Image {
        private final float size;

        private Icon(Drawable drawable, float size) {
            super(drawable);
            this.size = size;
        }

        @Override
        public float getPrefWidth() {
            return size;
        }

        @Override
        public float getPrefHeight() {
            return size;
        }
    }

    /** One row of segment buttons or underline tabs; its view shows the selection with {@link #select}. */
    public final class Segmented extends Table {
        /** The segments or tabs, in the order of their labels. */
        public final List<UiButton> buttons = new ArrayList<>();

        private Segmented(String style, boolean flex, String... labels) {
            if (style.startsWith(UiTheme.TOKEN_SEGMENT) || style.startsWith(UiTheme.TOKEN_TAB)) {
                tokenSections(style, flex, labels);
                return;
            }
            boolean tabs = style.startsWith("hud-tab");
            // A pill ("hud-pill"): one frame whose sections touch, a hairline between two of them.
            boolean pill = style.equals("hud-pill");
            if (tabs) {
                setBackground(skin.getDrawable("tabs"));
            } else if (pill) {
                setBackground(skin.getDrawable("pill-frame"));
                pad(1);
            }
            for (String text : labels) {
                if (pill && !buttons.isEmpty()) {
                    add(new Image(skin.getDrawable("rule"))).width(1).fillY();
                }
                UiButton button = button(style, null, text, null);
                Cell<UiButton> cell = add(button).padLeft(buttons.isEmpty() || pill ? 0 : tabs ? 18 : 6);
                if (flex) {
                    cell.growX().uniformX();
                } else if (!tabs) {
                    button.pad(6, 12, 6, 12);
                }
                buttons.add(button);
            }
        }

        /**
         * The Token v0 look: tabs side by side on a border.subtle hairline, or segments of one width that stand apart,
         * xs between them, each with its own edge (docs/design-system/tokens.md, Tabs and Segmented control).
         */
        private void tokenSections(String style, boolean flex, String... labels) {
            boolean tabs = style.startsWith(UiTheme.TOKEN_TAB);
            if (tabs) {
                setBackground(skin.getDrawable("token-tabs"));
            }
            float gap = DesignTokens.shipped().space().get("xs");
            for (String text : labels) {
                UiButton button = button(style, null, text, null);
                Cell<UiButton> cell = add(button).fillY().padLeft(tabs || buttons.isEmpty() ? 0 : gap);
                if (flex) {
                    cell.growX().uniformX();
                } else if (!tabs) {
                    cell.uniformX().fillX();
                }
                buttons.add(button);
            }
        }

        /** Presses the segment at {@code index} and releases the others; -1 releases all. */
        public Segmented select(int index) {
            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).pressed(i == index);
            }
            return this;
        }
    }

    /**
     * A checkbox (.tog): the box icon, mint while ticked, and its label. A click on the box or the label flips it and
     * fires a ChangeEvent; its view's own change ({@link #ticked}) fires none.
     */
    public final class Checkbox extends Table {
        private final Icon box;
        /** The Token v0 look's check over the box; null in the hud-v3 look. */
        private final Icon mark;
        private final ClickListener click;
        private boolean ticked;

        private Checkbox(String text, boolean initial, boolean token) {
            click = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    toggle();
                }
            };
            if (token) {
                UiTheme.ChoiceStyle colors = skin.get(UiTheme.ChoiceStyle.class);
                box = new Icon(skin.getDrawable("choice-box"), UiTheme.CHOICE_SIZE);
                mark = icon("check", UiTheme.CHOICE_SIZE, colors.mark);
                Stack indicator = new Stack(box, mark);
                add(indicator).size(UiTheme.CHOICE_SIZE).padRight(DesignTokens.shipped().space().get("sm"));
                add(label(text, "hud-body", 13, colors.text));
            } else {
                box = icon("checkbox-off", 18, UiTheme.MUTED);
                mark = null;
                add(box).padRight(8);
                add(label(text, "hud-body", 13, UiTheme.TEXT));
            }
            setTouchable(Touchable.enabled);
            addListener(click);
            ticked(initial);
        }

        @Override
        public void act(float delta) {
            if (mark != null) {
                String state = click.isPressed() ? "-down" : click.isOver() ? "-over" : "";
                box.setDrawable(skin.getDrawable("choice-box" + (ticked ? "-on" : "") + state));
            }
            super.act(delta);
        }

        /** Whether the box is ticked. */
        public boolean isTicked() {
            return ticked;
        }

        /** Ticks or clears the box without an event. */
        public Checkbox ticked(boolean value) {
            ticked = value;
            if (mark != null) {
                box.setDrawable(skin.getDrawable(value ? "choice-box-on" : "choice-box"));
                mark.setVisible(value);
                return this;
            }
            box.setDrawable(skin.getDrawable(value ? "icon-checkbox-on" : "icon-checkbox-off"));
            box.setColor(value ? UiTheme.MINT : UiTheme.MUTED);
            return this;
        }

        /** Flips the box as a click does, with a ChangeEvent. */
        public void toggle() {
            ticked(!ticked);
            ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
            fire(event);
            Pools.free(event);
        }
    }

    /** The search field and its clear button, which empties the field with a ChangeEvent from the field. */
    public final class SearchField extends Stack {
        /** The text field; its ChangeEvents report every edit, the clear button's included. */
        public final TextField field;
        /** The clear button, shown while the field holds text. */
        public final UiButton clear;

        private SearchField(String placeholder, boolean token) {
            field = new TextField("", skin, token ? "input-search" : "hud-search");
            field.setMessageText(placeholder);
            field.setProgrammaticChangeEvents(true);
            Table overlay = new Table();
            if (token) {
                clear = button("button-icon-compact", "close", null, null);
                onChange(clear, () -> field.setText(""));
                DesignTokens tokens = DesignTokens.shipped();
                float inset = tokens.size().get("border") + tokens.space().get("xs");
                float side = tokens.size().get("border") + tokens.space().get("md");
                float height = tokens.size().get("control.md");
                Icon glyph = icon("search", UiTheme.FIELD_ICON, field.getStyle().messageFontColor);
                glyph.setTouchable(Touchable.disabled);
                overlay.add(glyph).size(UiTheme.FIELD_ICON).padLeft(side);
                overlay.add().expandX();
                overlay.add(clear).size(height - 2 * inset).padRight(inset);
                hoverEdge(field, this);
            } else {
                clear = closeButton(() -> field.setText(""));
                overlay.right().add(clear).size(26).padRight(3);
            }
            add(field);
            add(overlay);
        }

        @Override
        public void act(float delta) {
            clear.setVisible(!field.getText().isEmpty());
            super.act(delta);
        }
    }

    /** The token search input and its clear button, which empties the field with a ChangeEvent from the field. */
    public final class TokenSearch extends Stack {
        /** The text field; its ChangeEvents report every edit, the clear button's included. */
        public final UiTextField field;
        /** The clear button, shown while the field holds text. */
        public final UiButton clear;

        private TokenSearch(String placeholder, boolean compact) {
            field = new UiTextField("", skin, "-search", compact);
            field.setMessageText(placeholder);
            field.setProgrammaticChangeEvents(true);
            field.hoverRoot(this);
            clear = button("button-icon-compact", "close", null, null);
            onChange(clear, () -> field.setText(""));
            DesignTokens tokens = DesignTokens.shipped();
            float inset = tokens.size().get("border") + tokens.space().get("xs");
            float side = tokens.size().get("border") + tokens.space().get("md");
            float height = tokens.size().get(compact ? "control.sm" : "control.md");
            Icon glyph = icon("search", UiTheme.FIELD_ICON, field.getStyle().messageFontColor);
            glyph.setTouchable(Touchable.disabled);
            Table overlay = new Table();
            overlay.add(glyph).size(UiTheme.FIELD_ICON).padLeft(side);
            overlay.add().expandX();
            overlay.add(clear).size(height - 2 * inset).padRight(inset);
            add(field);
            add(overlay);
            clear.setVisible(false);
        }

        @Override
        public void act(float delta) {
            clear.setVisible(!field.getText().isEmpty() && !field.isDisabled());
            super.act(delta);
        }
    }

    /**
     * The token password input and its reveal button, which shows the text as typed while it is on, its eye crossed
     * out, and as bullets again when pressed once more.
     */
    public final class TokenPassword extends Stack {
        /** The text field, in password mode while the text is hidden. */
        public final UiTextField field;
        /** The reveal button; hidden while the field is disabled or read-only. */
        public final UiButton reveal;

        private TokenPassword(boolean compact) {
            field = new UiTextField("", skin, "-reveal", compact);
            field.setPasswordMode(true);
            field.hoverRoot(this);
            reveal = button("button-icon-compact", "show", null, null);
            onChange(reveal, () -> shown(!shown()));
            DesignTokens tokens = DesignTokens.shipped();
            float inset = tokens.size().get("border") + tokens.space().get("xs");
            float height = tokens.size().get(compact ? "control.sm" : "control.md");
            Table overlay = new Table();
            overlay.add().expandX();
            overlay.add(reveal).size(height - 2 * inset).padRight(inset);
            add(field);
            add(overlay);
        }

        /** Whether the text shows as typed. */
        public boolean shown() {
            return !field.isPasswordMode();
        }

        /** Shows the text as typed, or as bullets; the button's eye shows what pressing it does next. */
        public TokenPassword shown(boolean value) {
            field.setPasswordMode(!value);
            if (!reveal.icons.isEmpty() && reveal.icons.get(0) instanceof Image icon) {
                icon.setDrawable(skin.getDrawable("icon-" + (value ? "hide" : "show")));
            }
            return this;
        }

        @Override
        public void act(float delta) {
            reveal.setVisible(!field.isDisabled());
            super.act(delta);
        }
    }

    /** A caption and value over a bar; the overview card's compact form uses smaller type and a 3-unit bar. */
    public final class Meter extends Table {
        private final Label caption;
        private final Label value;
        private final Cell<Label> valueCell;
        private final Widget bar;
        private final boolean token;

        private Meter(String caption, boolean compact, Widget bar, boolean token) {
            this.bar = bar;
            this.token = token;
            if (token) {
                String type = compact ? "caption" : "small";
                value = typeLabel("", type, "text.primary");
                this.caption = typeLabel(caption, type, "text.secondary");
                add(this.caption).left();
                valueCell = add(value).right().expandX().padLeft(space("md"));
                row();
                add(bar).colspan(2).growX().padTop(space("xs"));
                return;
            }
            float size = compact ? 9.5f : 10.5f;
            value = label("", compact ? "hud-caption" : "hud-button", compact ? size : 13, Color.WHITE);
            this.caption = label(UiTheme.upper(caption), "hud-caption", size, UiTheme.MUTED);
            Cell<Label> captionCell = add(this.caption).left();
            valueCell = add(value).right().expandX();
            if (compact) {
                // .bars2's line box, lower than the labels' cap height and descent: 18.1 units with the bar
                captionCell.height(size * NORMAL_LINE);
                valueCell.height(size * NORMAL_LINE);
            }
            row();
            add(bar).colspan(2).growX().padTop(4);
        }

        /** The caption at the left, upper-cased (.meter .l) except in the token look, such as a progress bar's step. */
        public Meter caption(String text) {
            caption.setText(token ? text : UiTheme.upper(text));
            return this;
        }

        /** The value at the right of the caption, upper-cased as the caption (.meter .l). */
        public Meter value(String text) {
            valueCell.setActor(value);
            value.setText(token ? text : UiTheme.upper(text));
            return this;
        }

        /**
         * A value of several parts that the view builds, such as a white value, an amber forecast and a muted note
         * (.meter .l b, .warn, .dim).
         */
        public Meter value(Actor parts) {
            valueCell.setActor(parts);
            return this;
        }

        /** The value and a plain bar's filled share; a bar of the view's own is set through that bar. */
        public Meter set(String text, float fraction, Color fill) {
            value(text);
            if (bar instanceof Bar plain) {
                plain.fraction = MathUtils.clamp(fraction, 0, 1);
                plain.fillColor = fill;
            } else if (bar instanceof TokenBar pill) {
                pill.fraction = MathUtils.clamp(fraction, 0, 1);
                pill.fillColor = fill;
            }
            return this;
        }
    }

    /** A token meter's bar: the border.control pill track and a pill fill from the leading edge. */
    private final class TokenBar extends Widget {
        private final Drawable track = skin.getDrawable("token-track");
        private final Drawable pill = skin.getDrawable("token-bar");
        private float fraction;
        private Color fillColor = UiTheme.tokenColor("accent.primary");

        @Override
        public float getPrefHeight() {
            return track.getMinHeight();
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            batch.setColor(1, 1, 1, alpha);
            track.draw(batch, getX(), getY(), getWidth(), getHeight());
            if (fraction > 0) {
                // A pill can't be narrower than it is high
                batch.setColor(fillColor.r, fillColor.g, fillColor.b, fillColor.a * alpha);
                pill.draw(batch, getX(), getY(), Math.max(getHeight(), getWidth() * fraction), getHeight());
            }
            batch.setPackedColor(previous);
        }
    }

    /**
     * A task's progress: the meter's caption, which may name the running step, and the share done as a whole percentage
     * and the mint share of the meter's bar.
     */
    public final class Progress extends Table {
        private final Meter meter;

        private Progress(String caption) {
            meter = new Meter(caption, false, new Bar(4), false);
            add(meter).growX();
            set(0);
        }

        /** The caption, such as the step that runs now. */
        public Progress caption(String text) {
            meter.caption(text);
            return this;
        }

        /** The share done, from 0 to 1; the percentage rounds down, so 100% means done. */
        public Progress set(float done) {
            float share = MathUtils.clamp(done, 0, 1);
            meter.set((int) (share * 100) + "%", share, UiTheme.MINT);
            return this;
        }
    }

    /** A meter's bar: a faint track and a filled share. */
    private final class Bar extends Widget {
        private final float height;
        private float fraction;
        private Color fillColor = UiTheme.MINT;

        private Bar(float height) {
            this.height = height;
        }

        @Override
        public float getPrefHeight() {
            return height;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            fill(batch, UiTheme.TRACK, alpha, getX(), getY(), getWidth(), getHeight());
            fill(batch, fillColor, alpha, getX(), getY(), getWidth() * fraction, getHeight());
            batch.setPackedColor(previous);
        }
    }
}
