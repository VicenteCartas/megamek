/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;

/**
 * Theme for every native view: its color tokens, the Roboto type scale, the icon sheet
 * and a skin styles and drawables, whose textures and fonts the theme owns. A window creates one on its GL
 * thread and disposes it there, after its stage; the tokens are shared and read-only.
 */
public final class UiTheme implements Disposable {
    public static final Color TEXT = Color.valueOf("F1F4F0");
    public static final Color ACCENT = Color.valueOf("CBD3D0");
    public static final Color MUTED = Color.valueOf("9AA6A3");
    public static final Color DISABLED = Color.valueOf("5F6B69");
    public static final Color PANEL = rgba(18, 24, 26, .92f);
    public static final Color PANEL2 = rgba(28, 36, 38, .97f);
    public static final Color POP = Color.valueOf("172022");
    public static final Color LINE = rgba(255, 255, 255, .08f);
    public static final Color RAIL = rgba(158, 170, 164, .6f);
    public static final Color CORNER = Color.valueOf("EEF5EF");
    public static final Color TICK = Color.valueOf("75817C");
    public static final Color MINT = Color.valueOf("82E2CE");
    public static final Color CORAL = Color.valueOf("EC9189");
    public static final Color AMBER = Color.valueOf("E6C991");
    public static final Color VIOLET = Color.valueOf("B9A8FF");
    public static final Color BLIP = Color.valueOf("FFAE66");
    public static final Color FILL = Color.valueOf("F0F2EE");
    public static final Color FILL_INK = Color.valueOf("16201E");
    // Secondary text on a filled surface: a pressed button's sub-label, a highlighted popover item's detail.
    public static final Color FILL_MUTED = Color.valueOf("39433F");
    public static final Color MAIN = Color.valueOf("F1F3F2");
    public static final Color QUIET = rgba(174, 187, 180, .24f);
    public static final Color HOVER = Color.valueOf("E8F1EB");
    /** A bar's track. */
    public static final Color TRACK = new Color(1, 1, 1, .1f);

    public static final String ROBOTO = "Roboto/Roboto-VariableFont_wdth,wght.ttf";
    /** Roboto's italics; its named instances are numbered as the upright face's. */
    public static final String ROBOTO_ITALIC = "Roboto/Roboto-Italic-VariableFont_wdth,wght.ttf";
    // FreeType's 1-based named instances of the shipped variable Roboto, in its fvar order.
    public static final int REGULAR = 4;
    public static final int MEDIUM = 5;
    public static final int BOLD = 7;
    public static final int CONDENSED_MEDIUM = 14;
    public static final int CONDENSED_SEMIBOLD = 15;
    public static final int CONDENSED_BOLD = 16;

    /** One step of the type scale: Roboto instance, size in stage units, CSS tracking (em), label color. */
    public record HudFont(String name, int instance, float size, float tracking, Color color) { }

    /**
     * The label style upper-cased and condensed: the font of the token buttons' and tabs' labels in game mode
     * (docs/design-system/tokens.md, Text case and Type width). Fonts bake their letter spacing in, so caps text at its
     * own tracking needs its own font.
     */
    public static final String CAPS_FONT = "hud-medium-caps";

    /** The single font table; each entry is also registered as a Label style of the same name. */
    public static final List<HudFont> HUD_FONTS = List.of(
          new HudFont("hud-body", REGULAR, 13, 0, TEXT),
          new HudFont("hud-small", REGULAR, 11.5f, 0, MUTED),
          new HudFont("hud-medium", MEDIUM, 12.5f, 0, TEXT),
          new HudFont("hud-utility", MEDIUM, 10.5f, .02f, ACCENT),
          new HudFont("hud-sub", CONDENSED_MEDIUM, 10, .04f, MUTED),
          new HudFont("hud-caption", CONDENSED_SEMIBOLD, 10.5f, .14f, MUTED),
          new HudFont("hud-mini", CONDENSED_SEMIBOLD, 11, .06f, ACCENT),
          new HudFont("hud-button", CONDENSED_SEMIBOLD, 12.5f, .08f, ACCENT),
          new HudFont("hud-name", CONDENSED_BOLD, 13.5f, .05f, TEXT),
          new HudFont("hud-title", CONDENSED_BOLD, 16, .07f, TEXT),
          new HudFont("hud-heading", CONDENSED_BOLD, 20, .04f, TEXT),
          new HudFont("hud-phase", CONDENSED_BOLD, 23, .02f, TEXT),
          new HudFont("hud-main", CONDENSED_BOLD, 17, .1f, TEXT),
          // The token buttons' and tabs' label in game mode: upper case and condensed at the label style's caps tracking
          new HudFont(CAPS_FONT, CONDENSED_MEDIUM, 12.5f, DesignTokens.shipped().roleStyle("button").tracking(true),
                TEXT));

    /** The prefix of a Token v0 type style's Label style name, such as {@code type-body}. */
    public static final String TYPE = "type-";
    /**
     * The suffix of a type style's condensed game mode copy in sentence case, such as {@code type-body.strong-condensed}
     * for the name role; its upper-case game mode copy ends in {@link #CAPS}.
     */
    public static final String CONDENSED = "-condensed";

    /**
     * Token v0's type scale in regular-width Roboto, read from the tokens; each entry is registered as a Label style
     * named TYPE + name.
     */
    public static final List<HudFont> TYPE_STYLES = DesignTokens.shipped().typeStyles().values().stream()
          .map(UiTheme::typeFont).toList();

    /** A type style as a Roboto instance; a style upper case even in app mode (overline) at its caps tracking. */
    private static HudFont typeFont(DesignTokens.TypeStyle style) {
        DesignTokens tokens = DesignTokens.shipped();
        int instance = switch (style.weight()) {
            case 400 -> REGULAR;
            case 500 -> MEDIUM;
            default -> throw new IllegalStateException("No Roboto instance for weight " + style.weight());
        };
        boolean upper = tokens.textRoles().containsKey(style.name())
              && tokens.upperCase(style.name(), DesignTokens.Presentation.APP);
        return new HudFont(style.name(), instance, style.size(), style.tracking(upper), TEXT);
    }

    /** The condensed Roboto instance of {@code style}; every condensed role is medium (docs/design-system/tokens.md). */
    static int condensedInstance(DesignTokens.TypeStyle style) {
        if (style.weight() != 500) {
            throw new IllegalStateException("No condensed Roboto instance for weight " + style.weight());
        }
        return CONDENSED_MEDIUM;
    }

    private static final String ICON_FONT = "Icons/MaterialSymbolsRounded[FILL,GRAD,opsz,wght].ttf";
    private static final int ICON_SIZE = 48;
    private static final int ICON_CELL = 64;
    /** The font icons' live area in their cell: Material Symbols keep 20 of their 24 units, texels 12 to 52. */
    private static final int ICON_LIVE = 40;
    /**
     * Material Symbols Rounded codepoints baked once as icon-name; the first thirteen keep the earlier names. Help is
     * help_center, the prototype's question mark in a rounded square; tune marks the developer tuning utility; walk is
     * the prototype's footprints; list is its bulleted list (shot 11); wireframe is the open grid of three lines each
     * way (U+F016). arrow-right, arrow-up, arrow-down and triangle-left are image uses only: Labels draw U+2190-U+2193
     * and U+25C2 as text through FALLBACK_FONTS.
     */
    public static final Map<String, Integer> ICONS = Map.ofEntries(
          Map.entry("target", 0xE1B3), Map.entry("move", 0xE569), Map.entry("group", 0xE241),
          Map.entry("orders", 0xE241), Map.entry("info", 0xE88E), Map.entry("unit", 0xE9E0),
          Map.entry("hex", 0xEB39), Map.entry("close", 0xE5CD), Map.entry("search", 0xE8B6),
          // link, link_off: two doors of a building as one opening, or apart (the editor's building designer)
          Map.entry("link", 0xE157), Map.entry("link-off", 0xE16F),
          Map.entry("arrow", 0xE5CC), Map.entry("lock", 0xE897), Map.entry("unlock", 0xE898), Map.entry("checkbox-off", 0xE835),
          // A password's reveal button: visibility while hidden, visibility_off while shown
          Map.entry("show", 0xE8F4), Map.entry("hide", 0xE8F5),
          Map.entry("checkbox-on", 0xE834),
          Map.entry("tactical", 0xE3B4), Map.entry("map", 0xE55B), Map.entry("report", 0xE873),
          Map.entry("help", 0xF1C0), Map.entry("settings", 0xE8B8), Map.entry("menu", 0xE5D2),
          Map.entry("chat", 0xE0B7), Map.entry("grid", 0xE9B0),
          Map.entry("list", 0xE896), Map.entry("check", 0xE5CA), Map.entry("remove", 0xE15B), Map.entry("flame", 0xEF55),
          Map.entry("chevron-down", 0xE5CF), Map.entry("chevron-up", 0xE5CE), Map.entry("twist-left", 0xE419),
          Map.entry("twist-right", 0xE41A), Map.entry("more", 0xE5D3), Map.entry("undo", 0xE166),
          Map.entry("locate", 0xE55C), Map.entry("play", 0xE037), Map.entry("pause", 0xE034),
          Map.entry("skip", 0xE044), Map.entry("warn", 0xE002), Map.entry("progress", 0xE9D0), Map.entry("error", 0xE000), Map.entry("walk", 0xF87D),
          Map.entry("chevron-left", 0xE5CB), Map.entry("chevron-right", 0xE5CC), Map.entry("expand", 0xF1CE), Map.entry("grip", 0xE945),
          // unfold_more and unfold_less: a tree's Expand all and Collapse all, as the Client Settings sidebar draws them.
          Map.entry("unfold-more", 0xE5D7), Map.entry("unfold-less", 0xE5D6), Map.entry("tag", 0xE9EF),
          Map.entry("layers", 0xE53B), Map.entry("replay", 0xE042), Map.entry("rewind", 0xE020),
          Map.entry("star", 0xE838), Map.entry("star-outline", 0xE838), Map.entry("tune", 0xE429),
          Map.entry("arrow-right", 0xE941), Map.entry("arrow-up", 0xE986), Map.entry("arrow-down", 0xE984),
          Map.entry("triangle-left", 0xE5DE), Map.entry("wireframe", 0xF016),
          // deployed_code: an isometric cube, the 3D preview of a board
          Map.entry("cube", 0xF720),
          // The welcome's links: open_in_new, menu_book (the wiki) and forum (Discord)
          Map.entry("open-in-new", 0xE89E), Map.entry("book", 0xEA19), Map.entry("forum", 0xE0BF),
          // The random army's table browser: folder and casino (a die, a table to roll on)
          Map.entry("folder", 0xE2C7), Map.entry("die", 0xEB40),
          // The random army's generators: military_tech (Battle Value), balance, flag (a faction's table),
          // account_tree (a formation) and filter_alt (the unit filter)
          Map.entry("military-tech", 0xEA3F), Map.entry("balance", 0xEAF6), Map.entry("flag", 0xE153),
          Map.entry("account-tree", 0xE97A), Map.entry("filter", 0xEF4F),
          // shield_person: the Game Master's toast (Token v0) and seat; cancel: a no in the seat's vote
          Map.entry("shield-person", 0xF650), Map.entry("cancel", 0xE5C9),
          // The lobby's player cards: more_vert, add, check_circle, radio_button_unchecked, hourglass_empty,
          // smart_toy (a bot) and palette (camouflage)
          Map.entry("more-vert", 0xE5D4), Map.entry("add", 0xE145), Map.entry("check-circle", 0xE86C),
          Map.entry("radio-unchecked", 0xE836), Map.entry("hourglass", 0xE88B), Map.entry("bot", 0xF06C),
          Map.entry("palette", 0xE40A), Map.entry("build", 0xE869),
          // The lobby's unit cards: hub (a C3 network) and delete
          Map.entry("hub", 0xE9F4), Map.entry("delete", 0xE872),
          // The lobby's top bar: gavel, Game options (the rules of the game)
          Map.entry("gavel", 0xE90E),
          // The Tactical View's unit badges: power_settings_new, vertical_align_bottom, anchor, sensors_off, sensors,
          // security, visibility, terrain, visibility_off (a TAG link's crosshair is "locate").
          Map.entry("power", 0xE8AC), Map.entry("prone", 0xE258), Map.entry("anchor", 0xF1CD),
          Map.entry("jammed", 0xE51F), Map.entry("ecm", 0xE51E), Map.entry("eccm", 0xE32A),
          Map.entry("spotting", 0xE8F4), Map.entry("hull-down", 0xE564), Map.entry("hidden", 0xE8F5),
          Map.entry("tool-select", 0xF82F), Map.entry("tool-paint", 0xE3AE), Map.entry("tool-erase", 0xE6D0),
          Map.entry("tool-sculpt", 0xE3F7),
          // Board editor layer types: domain, factory, nature, format_paint, stacks, train, water, signpost,
          // table_restaurant (the shipped font has no bridge; a deck on legs), propane_tank, rule, directions_car, pets.
          Map.entry("building", 0xE7EE), Map.entry("factory", 0xEBBC), Map.entry("tree", 0xE406),
          Map.entry("decal", 0xE243), Map.entry("stack", 0xF500), Map.entry("train", 0xE570), Map.entry("water", 0xF084),
          Map.entry("road", 0xEB91), Map.entry("bridge", 0xEAC6), Map.entry("fuel-tank", 0xEC13), Map.entry("rules", 0xF1C2),
          Map.entry("car", 0xE531), Map.entry("animal", 0xE91D));
    /**
     * Icons the prototype draws filled. The shipped font has no filled named instance, so their outlines are closed:
     * every texel that the glyph's outline encloses becomes opaque.
     */
    private static final Set<String> FILLED_ICONS = Set.of("play", "pause", "rewind", "star");

    /** A shipped face (OFL, in fonts/) for the characters a font lacks, drawn at this share of the font's size. */
    private record Fallback(String path, float size) { }

    /**
     * The faces that supply the characters Roboto and Noto Sans lack, first match wins. Noto Sans Symbols 2 has marks
     * such as U+25C2, U+2715 and the stars U+2605 and U+2606; Bitter has the arrows U+2190-U+2193. They are drawn in
     * its Bold weight at 0.8 of the font's size: small and low like the prototype's, with shafts about as thick as
     * Roboto's hyphen, which stay visible at every sub-pixel position in every hud font at 1 pixel per unit, down to
     * the 10-unit hud-sub. Thinner shafts (Medium at 0.65) vanished at some positions in the 10-11-unit fonts.
     */
    private static final List<Fallback> FALLBACK_FONTS = List.of(
          new Fallback("Noto Symbols 2/NotoSansSymbols2-Regular.ttf", 1),
          new Fallback("Bitter/Bitter-Bold.ttf", .8f));
    /**
     * Characters no shipped face has, drawn as a lookalike that one has, instead of a blank: the midline ellipsis
     * U+22EF as the ellipsis U+2026, and the white diamond with a centred dot U+27D0 as the white diamond containing a
     * black small diamond U+25C8. Texts the views show from MegaMek use both.
     */
    private static final Map<Character, Character> LOOKALIKES = Map.of('⋯', '…', '⟐', '◈');
    // Texels of transparent white around each hud glyph's ink that its quad takes in, so the quad is at least 7 texels
    // high and wide: 1.75 units, which always covers a pixel centre, even for a hyphen drawn at 10 units from an
    // 11.5-unit face. They are the packer's margin, not libGDX's glyph padding, which is transparent black and would
    // darken the filtered ink edges.
    private static final int GLYPH_PAD = 2;
    // Texels between two hud glyphs' quads: mip level 1 (2x2-texel averages), filtered bilinearly, reaches up to 3
    // texels past a quad, so with the next glyph's own GLYPH_PAD it never meets that glyph's ink.
    private static final int GLYPH_PADDING = 2;
    // Board labels shrink along both axes and at oblique angles. Their quads include eight transparent texels;
    // the wider atlas gap also leaves room for trilinear/anisotropic samples at the deepest permitted mip (3).
    private static final int WORLD_GLYPH_PAD = 8;
    private static final int WORLD_GLYPH_PADDING = 64;

    /** The skin of the hud-* styles and drawables; it holds every texture and font the theme makes. */
    public final Skin skin = new Skin();
    private boolean refreshing;
    private final File fonts;
    private final List<FreeTypeFontGenerator> generators = new ArrayList<>();
    // The hud-* fonts share glyph pages instead of allocating a 1024x1024 page each.
    private final PixmapPacker hudGlyphs = new PixmapPacker(1024, 1024, Pixmap.Format.RGBA8888,
          GLYPH_PADDING + 2 * GLYPH_PAD, false, new PixmapPacker.GuillotineStrategy());
    private final PixmapPacker worldGlyphs = new PixmapPacker(1024, 1024, Pixmap.Format.RGBA8888,
          WORLD_GLYPH_PADDING, false, new PixmapPacker.GuillotineStrategy());
    // The first face of the FALLBACK_FONTS chain; every font of this theme draws the characters it lacks from it.
    private final Generator fallback;

    /** Builds the theme from the shipped faces in {@code fonts} (MegaMek's fonts directory), on the GL thread. */
    public UiTheme(File fonts) {
        this.fonts = fonts;
        Pixmap white = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        white.setColor(Color.WHITE);
        white.fill();
        skin.add("white", new Texture(white));
        white.dispose();
        Generator next = null;
        for (Fallback face : FALLBACK_FONTS.reversed()) {
            next = new Generator(fontFile(fonts, face.path()), 0, face.size(), next);
            generators.add(next);
        }
        fallback = next;
        // Transparent white keeps linearly filtered glyph edges from darkening.
        hudGlyphs.setTransparentColor(new Color(1, 1, 1, 0));
        worldGlyphs.setTransparentColor(new Color(1, 1, 1, 0));
        for (HudFont hud : HUD_FONTS) {
            BitmapFont face = font(hud.name(), ROBOTO, hud.instance(), hud.size(), hud.tracking(), 0, hudGlyphs);
            skin.add(hud.name(), new Label.LabelStyle(face, hud.color()));
        }
        for (HudFont type : TYPE_STYLES) {
            BitmapFont face = font(TYPE + type.name(), ROBOTO, type.instance(), type.size(), type.tracking(), 0,
                  hudGlyphs);
            skin.add(TYPE + type.name(), new Label.LabelStyle(face, type.color()));
        }
        // Game mode's copies of the styles its roles show condensed: upper case at the caps tracking, or sentence case
        DesignTokens tokens = DesignTokens.shipped();
        for (String role : tokens.condensedRoles(DesignTokens.Presentation.GAME)) {
            DesignTokens.TypeStyle style = tokens.roleStyle(role);
            boolean upper = tokens.upperCase(role, DesignTokens.Presentation.GAME);
            String name = TYPE + style.name() + (upper ? CAPS : CONDENSED);
            if (!skin.has(name, Label.LabelStyle.class)) {
                BitmapFont face = font(name, ROBOTO, condensedInstance(style), style.size(), style.tracking(upper), 0,
                      hudGlyphs);
                skin.add(name, new Label.LabelStyle(face, TEXT));
            }
        }
        // A count badge's digits: caption's size in label's medium face
        skin.add(TOKEN_BADGE, new Label.LabelStyle(font(TOKEN_BADGE, ROBOTO, MEDIUM, 10.5f, 0, 0, hudGlyphs),
              TEXT));
        hudGlyphs.getPages().forEach(page -> bindGlyphPage(page.getTexture(), false));

        hudDrawables();
        TextButton.TextButtonStyle hud = quietButton("hud-button", 8, 12, 36);
        skin.add("hud", hud);
        skin.add("hud-mini", quietButton("hud-mini", 3, 9, 26));
        TextButton.TextButtonStyle danger = quietButton("hud-mini", 3, 9, 26);
        danger.fontColor = CORAL;
        danger.overFontColor = Color.WHITE;
        danger.over = box("button-danger-over", alpha(CORAL, .35f), CORAL, 1, 3, null, 3, 9, 26);
        danger.down = danger.over;
        skin.add("hud-danger", danger);
        TextButton.TextButtonStyle deleteIcon = new TextButton.TextButtonStyle(danger);
        deleteIcon.up = flat(Color.CLEAR, 0, 0);
        deleteIcon.over = flat(alpha(CORAL, .15f), 0, 0);
        deleteIcon.down = flat(alpha(CORAL, .3f), 0, 0);
        deleteIcon.overFontColor = Color.valueOf("FF554C");
        skin.add("hud-delete-icon", deleteIcon);
        skin.add("hud-seg", quietButton("hud-medium", 6, 4, 0));
        // The sections of a pill: bare, the hovered one lighter, the pressed one filled.
        TextButton.TextButtonStyle section = new TextButton.TextButtonStyle();
        section.font = skin.getFont("hud-medium");
        section.over = skin.getDrawable("pill-section-over");
        section.down = section.over;
        section.checked = skin.getDrawable("pill-section-on");
        section.checkedOver = section.checked;
        section.checkedDown = section.checked;
        section.fontColor = ACCENT;
        section.overFontColor = Color.WHITE;
        section.checkedFontColor = FILL_INK;
        section.checkedOverFontColor = FILL_INK;
        section.checkedDownFontColor = FILL_INK;
        section.disabledFontColor = DISABLED;
        skin.add("hud-pill", section);
        TextButton.TextButtonStyle hudMain = new TextButton.TextButtonStyle();
        hudMain.font = skin.getFont("hud-main");
        hudMain.up = skin.getDrawable("button-main");
        hudMain.over = skin.getDrawable("button-main-over");
        hudMain.down = hudMain.over;
        hudMain.disabled = skin.getDrawable("button-main-disabled");
        hudMain.fontColor = FILL_INK;
        hudMain.overFontColor = Color.valueOf("0D1412");
        hudMain.disabledFontColor = Color.valueOf("6F7A78");
        skin.add("hud-main", hudMain);
        // ImageTextButton lays out in a row, so a utility places an icon Image above this style's label.
        TextButton.TextButtonStyle utility = utility(hud, "hud-utility", 6, 9, 5, 62, 56);
        skin.add("hud-utility", utility);
        // At W <= 1350 a utility is at least 54 units wide, with 7 units of side padding (.b.util).
        skin.add("hud-utility-narrow", utility(hud, "hud-utility", 6, 7, 5, 54, 56));
        skin.add("bracket", utility.up, Drawable.class);
        skin.add("bracket-over", utility.over, Drawable.class);
        skin.add("bracket-checked", utility.checked, Drawable.class);
        // The row utility (.b.util.row) and the small bracket button (.b.brk.sm).
        skin.add("hud-utility-row", utility(hud, "hud-medium", 0, 14, 0, 62, 36));
        skin.add("hud-utility-small", utility(hud, "hud-mini", 4, 10, 4, 0, 28));
        // Popover items (.pop .it): filled under the pointer or the keyboard (checked); details use FILL_MUTED then.
        TextButton.TextButtonStyle hudMenuRow = new TextButton.TextButtonStyle();
        hudMenuRow.font = skin.getFont("hud-body");
        hudMenuRow.up = flat(Color.CLEAR, 8, 14);
        hudMenuRow.over = flat(FILL, 8, 14);
        hudMenuRow.down = hudMenuRow.over;
        hudMenuRow.checked = hudMenuRow.over;
        hudMenuRow.disabled = hudMenuRow.up;
        hudMenuRow.fontColor = TEXT;
        hudMenuRow.overFontColor = FILL_INK;
        hudMenuRow.downFontColor = FILL_INK;
        hudMenuRow.checkedFontColor = FILL_INK;
        hudMenuRow.disabledFontColor = DISABLED;
        skin.add("hud-menu-row", hudMenuRow);
        // The lists' thin scroll bar (.list).
        skin.add("hud-list", scrollStyle(4, 24));

        Drawable selection = skin.newDrawable("white", alpha(MINT, .3f));
        TextField.TextFieldStyle hudField = new TextField.TextFieldStyle(skin.getFont("hud-body"), TEXT,
              skin.newDrawable("white", TEXT), selection, skin.getDrawable("field"));
        hudField.focusedBackground = skin.getDrawable("field-focused");
        hudField.messageFont = hudField.font;
        hudField.messageFontColor = DISABLED;
        skin.add("hud", hudField);
        kitStyles(hud);
        tokenButtons();
        // Hover tooltips: hud-small text on the panel surface, wrapped at 340 units.
        TextTooltip.TextTooltipStyle tooltip = new TextTooltip.TextTooltipStyle(
              skin.get("hud-small", Label.LabelStyle.class), skin.getDrawable("panel"));
        tooltip.wrapWidth = 340;
        skin.add("hud", tooltip);
        icons();
        tokenStyles();
    }

    /**
     * Builds the styles again from the design tokens after the user's palette changed ({@link DesignTokens#setPalette});
     * widgets made afterwards use the new colors. hud-v3's own styles do not depend on the tokens. {@code more} adds a
     * view's own token styles again under the same names. The old textures are not disposed, since widgets built
     * before may still draw them.
     */
    public void refreshTokens(Runnable more) {
        refreshing = true;
        try {
            tokenButtons();
            tokenStyles();
            more.run();
        } finally {
            refreshing = false;
        }
    }

    private void tokenStyles() {
        tokenFields();
        tokenChoices();
        tokenOptions();
        tokenSelects();
        tokenSelection();
        tokenInputs();
        tokenLists();
        tokenContainers();
        tokenStatus();
    }

    /** Upper-cases titles, captions and buttons at display time, so source strings stay searchable. */
    public static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    /**
     * A generator for a file in {@code fonts}, MegaMek's fonts directory; instance > 0 selects that named instance of
     * a variable font.
     */
    public static FreeTypeFontGenerator generator(File fonts, String path, int instance) {
        return new Generator(fontFile(fonts, path), instance, 1, null);
    }

    private static FileHandle fontFile(File fonts, String path) {
        return new FileHandle(new File(fonts, path));
    }

    /** A CSS rgba() color: channels of 0 to 255 and an alpha of 0 to 1. */
    public static Color rgba(int red, int green, int blue, float alpha) {
        return new Color(red / 255f, green / 255f, blue / 255f, alpha);
    }

    /** A token's hue at another alpha, so a derived surface follows its token. */
    public static Color alpha(Color token, float alpha) {
        return new Color(token.r, token.g, token.b, alpha);
    }

    /** The tint that turns a skin drawable of the {@code base} color into {@code color} at {@code alpha}. */
    public static Color tint(Color color, Color base, float alpha) {
        return new Color(color.r / base.r, color.g / base.g, color.b / base.b, alpha);
    }

    /**
     * Registers a resource of a view that adds its own styles in this look, as the battle view's skin does. Each name
     * has one owner: a name the skin already holds for {@code type}, the theme's or the view's, throws, except while
     * {@link #refreshTokens} builds the token styles again over their old selves.
     */
    public <T> T add(String name, T resource, Class<?> type) {
        if (!refreshing && skin.has(name, type)) {
            throw new IllegalStateException("The skin already has the " + type.getSimpleName() + " \"" + name + "\"");
        }
        skin.add(name, resource, type);
        return resource;
    }

    /** A flat fill from the skin's white texel, with padding. */
    public Drawable flat(Color color, float vertical, float horizontal) {
        Drawable drawable = skin.newDrawable("white", color);
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        return drawable;
    }

    /** The frames, buttons, rows, fields, selects, chips, the badge and the rule, all owned by the skin. */
    private void hudDrawables() {
        Texture white = skin.get("white", Texture.class);
        skin.add("panel", pad(new HudFrame(white, PANEL, RAIL, CORNER, 13, null), 12, 14), Drawable.class);
        skin.add("panel-foe", pad(new HudFrame(white, PANEL, RAIL, CORAL, 13, null), 12, 14), Drawable.class);
        skin.add("panel-pop", pad(new HudFrame(white, POP, RAIL, CORNER, 13, null), 6, 0), Drawable.class);

        Color faint = rgba(255, 255, 255, .012f);
        box("button", faint, QUIET, 1, 3, null, 8, 12, 36);
        box("button-over", rgba(255, 255, 255, .04f), HOVER, 1, 3, null, 8, 12, 36);
        box("button-checked", FILL, FILL, 1, 3, null, 8, 12, 36);
        box("button-disabled", faint, alpha(QUIET, .1f), 1, 3, null, 8, 12, 36);
        box("button-auto", faint, Color.WHITE, 1, 3, null, 8, 12, 36);
        box("button-main", MAIN, Color.valueOf("E5EEE7"), 1, 3, null, 8, 12, 46);
        box("button-main-over", Color.WHITE, Color.WHITE, 1, 3, null, 8, 12, 46);
        box("button-main-disabled", rgba(255, 255, 255, .08f), Color.CLEAR, 1, 3, null, 8, 12, 46);
        Color rowFill = rgba(255, 255, 255, .015f);
        box("row", rowFill, alpha(QUIET, .16f), 1, 3, null, 7, 10, 0);
        box("row-over", rowFill, alpha(HOVER, .55f), 1, 3, null, 7, 10, 0);
        box("row-selected", alpha(MINT, .07f), MINT, 1, 3, MINT, 7, 10, 0);
        box("field", rgba(255, 255, 255, .05f), rgba(255, 255, 255, .18f), 1, 3, null, 7, 10, 0);
        box("field-focused", alpha(MINT, .06f), MINT, 1, 3, null, 7, 10, 0);
        // Drop-down selects (.fsel).
        box("select", rgba(255, 255, 255, .04f), alpha(QUIET, .3f), 1, 3, null, 6, 8, 0);
        // Chips (.chip, .chip.w, .chip.x): opaque borders without a fill.
        box("chip", Color.CLEAR, Color.valueOf("4B5654"), 1, 3, null, 2, 7, 0);
        box("chip-warn", Color.CLEAR, Color.valueOf("6F6035"), 1, 3, null, 2, 7, 0);
        box("chip-bad", Color.CLEAR, Color.valueOf("7A3F3A"), 1, 3, null, 2, 7, 0);
        // The board's hex hover card (GpuHexTip): a faint plate without rails or border.
        box("tip", rgba(12, 17, 19, .74f), null, 0, 3, null, 4, 8, 0);
        // A utility's count badge (.b.util .badge).
        box("badge", AMBER, null, 0, 8.5f, null, 0, 4, 17).setMinWidth(17);
        // A pill split in sections (UiKit.segmented "hud-pill"): one frame, the hovered and the pressed section in it.
        box("pill-frame", faint, QUIET, 1, 3, null, 0, 0, 0);
        box("pill-section-over", rgba(255, 255, 255, .06f), null, 0, 2, null, 0, 0, 0);
        box("pill-section-on", FILL, null, 0, 2, null, 0, 0, 0);
        Drawable rule = skin.newDrawable("white", LINE);
        rule.setMinHeight(1);
        skin.add("rule", rule, Drawable.class);
        // A vertical split pane's divider ("hud-split"): the rule across a 9-unit grab band, with a short grip.
        Drawable grip = skin.newDrawable("white", QUIET);
        Drawable divider = new BaseDrawable() {
            @Override public void draw(Batch batch, float x, float y, float width, float height) {
                rule.draw(batch, x, y + (height - 1) / 2, width, 1);
                grip.draw(batch, x + (width - 28) / 2, y + (height - 3) / 2, 28, 3);
            }
        };
        divider.setMinHeight(9);
        skin.add("hud-split", new com.badlogic.gdx.scenes.scene2d.ui.SplitPane.SplitPaneStyle(divider));
    }

    /**
     * The button set's kinds (docs/design-system/tokens.md, Buttons). Each is a {@link TokenButtonStyle} named
     * {@code button-<kind>}, and {@code button-<kind>-compact} at the compact size.
     */
    public static final List<String> BUTTON_KINDS = List.of("primary", "secondary", "quiet", "danger", "toggle",
          "icon", "special");
    /** The suffix of a button style's compact size. */
    public static final String COMPACT = "-compact";
    /**
     * The suffix of a labelled button style's game mode copy, which draws its label in {@link #CAPS_FONT}, and of a
     * type style's upper-case condensed game mode copy.
     */
    public static final String CAPS = "-caps";
    /**
     * The done face of a primary button, such as Ready once the player is ready: a status.success face with the
     * text.onAccent label, leaning toward the label on hover and press; it has the compact and caps copies a token
     * button has.
     */
    public static final String DONE_BUTTON = "button-done";

    /** A button style built from the design tokens, with the focus ring its button draws over its face. */
    public static final class TokenButtonStyle extends TextButton.TextButtonStyle {
        /** The focus ring's outer and inner one-unit lines; the inner one is drawn one unit inside the outer. */
        public Drawable focusOuter;
        public Drawable focusInner;
        /** The details' color (null: hud-v3's muted one); disabled details take the disabled font color. */
        public Color detailFontColor;
        /** The face and label of a checked button while disabled (null: the disabled ones, as in Scene2D). */
        public Drawable checkedDisabled;
        public Color checkedDisabledFontColor;
        /** Whether the face is cream (surface.inverse) at rest, as the primary's is, or only while checked. */
        public boolean creamUp;
        public boolean creamChecked;
        /**
         * The focus ring of a cream face: a gap outside it, drawn over the button's bounds grown by
         * {@link #CHOICE_RING}. Where an ancestor would clip it the button draws {@link #creamFocusOuter} and
         * {@link #creamFocusInner} inside instead.
         */
        public Drawable focusRing;
        public Drawable creamFocusOuter;
        public Drawable creamFocusInner;

        public TokenButtonStyle() {
        }

        /** A copy of {@code style}, focus ring and details' color included. */
        public TokenButtonStyle(TokenButtonStyle style) {
            super(style);
            focusOuter = style.focusOuter;
            focusInner = style.focusInner;
            detailFontColor = style.detailFontColor;
            checkedDisabled = style.checkedDisabled;
            checkedDisabledFontColor = style.checkedDisabledFontColor;
            creamUp = style.creamUp;
            creamChecked = style.creamChecked;
            focusRing = style.focusRing;
            creamFocusOuter = style.creamFocusOuter;
            creamFocusInner = style.creamFocusInner;
        }
    }

    /** The token button set in dark mode, the only mode native views have for now. */
    private void tokenButtons() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        int raised = tokens.color("surface.raised", mode);
        int hover = tokens.color("surface.hover", mode);
        int pressed = ColorMath.withAlpha(hover, Math.min(255, ColorMath.alpha(hover) * 2));
        int danger = tokens.color("status.danger", mode);
        Color control = token(tokens.color("border.control", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        Color strong = token(tokens.color("border.strong", mode));
        Color ring = token(tokens.color("focus.ring", mode));
        float radius = tokens.radius().get("md");
        float border = tokens.size().get("border");

        box("tb-secondary", token(raised), control, border, radius, null, 0, 0, 0);
        box("tb-secondary-over", token(ColorMath.over(hover, raised)), strong, border, radius, null, 0, 0, 0);
        box("tb-secondary-down", token(ColorMath.over(pressed, raised)), strong, border, radius, null, 0, 0, 0);
        box("tb-disabled", token(raised), subtle, border, radius, null, 0, 0, 0);
        // The primary face is the inverse surface, hud-v3's cream, as is every control that is on, so mint stays for
        // focus. A surface wash barely shows on it: hover and press lean it toward the label color, press further.
        int inverse = tokens.color("surface.inverse", mode);
        int onInverse = tokens.color("text.onInverse", mode);
        int inverseHover = ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.hover")), inverse);
        int inversePressed = ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.pressed")), inverse);
        box("tb-primary", token(inverse), token(inverse), border, radius, null, 0, 0, 0);
        box("tb-primary-over", token(inverseHover), strong, border, radius, null, 0, 0, 0);
        box("tb-primary-down", token(inversePressed), strong, border, radius, null, 0, 0, 0);
        // Ready's done face: status.success with the onAccent label, leaning toward the label on hover and press
        int success = tokens.color("status.success", mode);
        int onAccent = tokens.color("text.onAccent", mode);
        box("tb-done", token(success), token(success), border, radius, null, 0, 0, 0);
        box("tb-done-over", token(ColorMath.over(ColorMath.withAlpha(onAccent, tokens.alpha("tint.hover")), success)),
              strong, border, radius, null, 0, 0, 0);
        box("tb-done-down",
              token(ColorMath.over(ColorMath.withAlpha(onAccent, tokens.alpha("tint.pressed")), success)), strong,
              border, radius, null, 0, 0, 0);
        box("tb-danger", token(raised), token(danger), border, radius, null, 0, 0, 0);
        box("tb-danger-over", token(ColorMath.over(ColorMath.withAlpha(danger, tokens.alpha("tint.hover")), raised)),
              token(danger), border, radius, null, 0, 0, 0);
        box("tb-danger-down",
              token(ColorMath.over(ColorMath.withAlpha(danger, tokens.alpha("tint.pressed")), raised)),
              token(danger), border, radius, null, 0, 0, 0);
        // A checked toggle looks like the primary button; disabled, it stays a faint cream with no edge
        int inverseDisabled = tokens.color("surface.inverseDisabled", mode);
        box("tb-on-disabled", token(ColorMath.over(inverseDisabled, raised)), null, 0, radius, null, 0, 0, 0);
        box("tb-quiet-over", token(hover), null, 0, radius, null, 0, 0, 0);
        box("tb-quiet-down", token(pressed), null, 0, radius, null, 0, 0, 0);
        // A checked icon toggle is a cream square without an edge, as its quiet faces have none
        box("tb-quiet-on", token(inverse), null, 0, radius, null, 0, 0, 0);
        box("tb-quiet-on-over", token(inverseHover), null, 0, radius, null, 0, 0, 0);
        box("tb-quiet-on-down", token(inversePressed), null, 0, radius, null, 0, 0, 0);
        box("tb-quiet-on-disabled", token(inverseDisabled), null, 0, radius, null, 0, 0, 0);
        box("tb-focus", Color.CLEAR, ring, border, radius, null, 0, 0, 0);
        box("tb-focus-inner", Color.CLEAR, ring, border, Math.max(radius - border, 0), null, 0, 0, 0);
        box("tb-focus-inverse", Color.CLEAR, token(tokens.color("text.primary", mode)), border, radius, null, 0, 0, 0);
        box("tb-focus-inverse-inner", Color.CLEAR, token(onInverse), border, Math.max(radius - border, 0), null, 0, 0,
              0);
        float focusWidth = tokens.size().get("focus.width");
        box("tb-focus-ring", Color.CLEAR, ring, focusWidth, radius + CHOICE_RING, null, 0, 0, 0);

        Color text = token(tokens.color("text.primary", mode));
        Color secondaryText = token(tokens.color("text.secondary", mode));
        Color disabledText = token(tokens.color("text.disabled", mode));
        float vertical = tokens.space().get("xxs");
        for (boolean compact : new boolean[] { false, true }) {
            float height = tokens.size().get(compact ? "control.sm" : "control.md");
            float side = tokens.space().get(compact ? "md" : "lg");
            String suffix = compact ? COMPACT : "";
            for (String kind : BUTTON_KINDS) {
                boolean square = kind.equals("icon");
                float horizontal = square ? 0 : side;
                TokenButtonStyle style = new TokenButtonStyle();
                style.font = skin.getFont("hud-medium");
                style.fontColor = secondaryText;
                style.overFontColor = text;
                style.downFontColor = text;
                style.checkedFontColor = text;
                style.checkedOverFontColor = text;
                style.checkedDownFontColor = text;
                style.disabledFontColor = disabledText;
                style.focusOuter = skin.getDrawable("tb-focus");
                style.focusInner = skin.getDrawable("tb-focus-inner");
                style.focusRing = skin.getDrawable("tb-focus-ring");
                style.creamFocusOuter = skin.getDrawable("tb-focus-inverse");
                style.creamFocusInner = skin.getDrawable("tb-focus-inverse-inner");
                Color label = token(onInverse);
                String face = switch (kind) {
                    case "primary", "danger", "quiet" -> kind;
                    default -> "secondary";
                };
                if (kind.equals("special")) {
                    special(style, tokens, mode, vertical, side, height);
                } else if (square || kind.equals("quiet")) {
                    style.up = sized(flat(Color.CLEAR, 0, 0), vertical, horizontal, height, square);
                    style.over = sized(resized("tb-quiet-over", 0, 0, 0), vertical, horizontal, height, square);
                    style.down = sized(resized("tb-quiet-down", 0, 0, 0), vertical, horizontal, height, square);
                    style.disabled = style.up;
                } else {
                    style.up = sized(resized("tb-" + face, 0, 0, 0), vertical, horizontal, height, false);
                    style.over = sized(resized("tb-" + face + "-over", 0, 0, 0), vertical, horizontal, height, false);
                    style.down = sized(resized("tb-" + face + "-down", 0, 0, 0), vertical, horizontal, height, false);
                    style.disabled = sized(resized("tb-disabled", 0, 0, 0), vertical, horizontal, height, false);
                }
                switch (kind) {
                    case "primary" -> {
                        style.fontColor = label;
                        style.overFontColor = label;
                        style.downFontColor = label;
                        style.checkedFontColor = label;
                        style.checkedOverFontColor = label;
                        style.checkedDownFontColor = label;
                        style.focusOuter = style.creamFocusOuter;
                        style.focusInner = style.creamFocusInner;
                        style.creamUp = true;
                    }
                    case "danger" -> {
                        style.fontColor = token(danger);
                        style.overFontColor = token(danger);
                        style.downFontColor = token(danger);
                    }
                    case "toggle" -> {
                        style.checked = sized(resized("tb-primary", 0, 0, 0), vertical, side, height, false);
                        style.checkedOver = sized(resized("tb-primary-over", 0, 0, 0), vertical, side, height, false);
                        style.checkedDown = sized(resized("tb-primary-down", 0, 0, 0), vertical, side, height, false);
                        style.checkedDisabled = sized(resized("tb-on-disabled", 0, 0, 0), vertical, side, height,
                              false);
                        onInverseWhenChecked(style, label);
                    }
                    case "icon" -> {
                        style.checked = sized(resized("tb-quiet-on", 0, 0, 0), vertical, 0, height, true);
                        style.checkedOver = sized(resized("tb-quiet-on-over", 0, 0, 0), vertical, 0, height, true);
                        style.checkedDown = sized(resized("tb-quiet-on-down", 0, 0, 0), vertical, 0, height, true);
                        style.checkedDisabled = sized(resized("tb-quiet-on-disabled", 0, 0, 0), vertical, 0, height,
                              true);
                        onInverseWhenChecked(style, label);
                    }
                    case "special" -> onInverseWhenChecked(style, label);
                    default -> { }
                }
                skin.add("button-" + kind + suffix, style, TextButton.TextButtonStyle.class);
                if (!square) {
                    TokenButtonStyle caps = new TokenButtonStyle(style);
                    caps.font = skin.getFont(CAPS_FONT);
                    skin.add("button-" + kind + suffix + CAPS, caps, TextButton.TextButtonStyle.class);
                }
                if (kind.equals("primary")) {
                    TokenButtonStyle done = new TokenButtonStyle(style);
                    done.up = sized(resized("tb-done", 0, 0, 0), vertical, horizontal, height, false);
                    done.over = sized(resized("tb-done-over", 0, 0, 0), vertical, horizontal, height, false);
                    done.down = sized(resized("tb-done-down", 0, 0, 0), vertical, horizontal, height, false);
                    Color onDone = token(onAccent);
                    done.fontColor = onDone;
                    done.overFontColor = onDone;
                    done.downFontColor = onDone;
                    skin.add(DONE_BUTTON + suffix, done, TextButton.TextButtonStyle.class);
                    TokenButtonStyle doneCaps = new TokenButtonStyle(done);
                    doneCaps.font = skin.getFont(CAPS_FONT);
                    skin.add(DONE_BUTTON + suffix + CAPS, doneCaps, TextButton.TextButtonStyle.class);
                }
            }
        }
    }

    /** Gives a checked {@code style} the cream face's {@code label} color, disabled too, and its outside focus ring. */
    private static void onInverseWhenChecked(TokenButtonStyle style, Color label) {
        style.checkedFontColor = label;
        style.checkedOverFontColor = label;
        style.checkedDownFontColor = label;
        style.checkedDisabledFontColor = label;
        style.creamChecked = true;
    }

    /**
     * The special button's faces: corner ticks at rest, a full frame under the pointer; selected, the cream face of
     * every control that is on.
     */
    private void special(TokenButtonStyle style, DesignTokens tokens, Mode mode, float vertical, float side,
          float height) {
        Texture white = skin.get("white", Texture.class);
        int raised = tokens.color("surface.raised", mode);
        int hover = tokens.color("surface.hover", mode);
        int pressed = ColorMath.withAlpha(hover, Math.min(255, ColorMath.alpha(hover) * 2));
        int inverse = tokens.color("surface.inverse", mode);
        int onInverse = tokens.color("text.onInverse", mode);
        float tick = tokens.size().get("tick.control");
        Color strong = token(tokens.color("border.strong", mode));
        style.up = sized(new HudFrame(white, token(raised), null, token(tokens.color("border.control", mode)), tick,
              null), vertical, side, height, false);
        style.over = sized(new HudFrame(white, token(ColorMath.over(hover, raised)), null, null, 0, strong), vertical,
              side, height, false);
        style.down = sized(new HudFrame(white, token(ColorMath.over(pressed, raised)), null, null, 0, strong),
              vertical, side, height, false);
        style.checked = sized(new HudFrame(white, token(inverse), null, null, 0, token(inverse)), vertical, side,
              height, false);
        style.checkedOver = sized(new HudFrame(white, token(ColorMath.over(ColorMath.withAlpha(onInverse,
              tokens.alpha("tint.hover")), inverse)), null, null, 0, strong), vertical, side, height, false);
        style.checkedDown = sized(new HudFrame(white, token(ColorMath.over(ColorMath.withAlpha(onInverse,
              tokens.alpha("tint.pressed")), inverse)), null, null, 0, strong), vertical, side, height, false);
        style.checkedDisabled = sized(new HudFrame(white, token(ColorMath.over(
              tokens.color("surface.inverseDisabled", mode), raised)), null, null, 0, null), vertical, side, height,
              false);
        style.disabled = sized(new HudFrame(white, token(raised), null, token(tokens.color("border.subtle", mode)),
              tick, null), vertical, side, height, false);
        Color ring = token(tokens.color("focus.ring", mode));
        style.focusOuter = new HudFrame(white, Color.CLEAR, null, null, 0, ring);
        style.focusInner = null;
        style.focusRing = new HudFrame(white, Color.CLEAR, null, null, 0, ring, tokens.size().get("focus.width"));
        style.creamFocusOuter = new HudFrame(white, Color.CLEAR, null, null, 0, token(tokens.color("text.primary",
              mode)), tokens.size().get("border"));
        style.creamFocusInner = new HudFrame(white, Color.CLEAR, null, null, 0, token(onInverse),
              tokens.size().get("border"));
    }

    /** Pads {@code drawable} and gives it the control height; a square one is as wide as it is high. */
    private static Drawable sized(Drawable drawable, float vertical, float horizontal, float height, boolean square) {
        pad(drawable, vertical, horizontal);
        drawable.setMinHeight(height);
        drawable.setMinWidth(square ? height : 0);
        return drawable;
    }

    /**
     * The text input variants (docs/design-system/tokens.md, Inputs): plain, search or reveal (a password with its
     * reveal button), each with a status or read-only. Each is a {@link TokenFieldStyle} named {@code input<variant>},
     * and {@code input<variant>-compact} at the compact size.
     */
    public static final List<String> INPUT_VARIANTS = List.of("", "-error", "-warning", "-readonly", "-search",
          "-search-error", "-search-warning", "-search-readonly", "-reveal", "-reveal-error", "-reveal-warning",
          "-reveal-readonly");
    /** The size of an input's status and search glyphs (icon.sm). */
    public static final float FIELD_ICON = DesignTokens.shipped().size().get("icon.sm");
    /** The gap between an input's text and its trailing status icon (space.xs). */
    public static final float FIELD_ICON_GAP = DesignTokens.shipped().space().get("xs");

    /** A text input style built from the design tokens, with the edge under the pointer and an optional status icon. */
    public static final class TokenFieldStyle extends TextField.TextFieldStyle {
        /** The background under the pointer; a status edge keeps its color. */
        public Drawable over;
        /** The status icon drawn at the trailing edge, in {@link #iconColor}; null for none. */
        public Drawable icon;
        public Color iconColor;
    }

    /**
     * The token inputs in dark mode: an input fill and control edge, strong under the pointer, the focus ring in place
     * of the edge, a status edge and icon; read-only has no fill and a subtle edge but primary text.
     */
    private void tokenFields() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        Color input = token(tokens.color("surface.input", mode));
        Color base = token(tokens.color("surface.base", mode));
        Color danger = token(tokens.color("status.danger", mode));
        Color warning = token(tokens.color("status.warning", mode));
        float radius = tokens.radius().get("md");
        float border = tokens.size().get("border");
        box("in", input, token(tokens.color("border.control", mode)), border, radius, null, 0, 0, 0);
        box("in-over", input, token(tokens.color("border.strong", mode)), border, radius, null, 0, 0, 0);
        box("in-focus", input, token(tokens.color("focus.ring", mode)), tokens.size().get("focus.width"), radius, null,
              0, 0, 0);
        box("in-error", input, danger, border, radius, null, 0, 0, 0);
        box("in-warning", input, warning, border, radius, null, 0, 0, 0);
        box("in-quiet", base, token(tokens.color("border.subtle", mode)), border, radius, null, 0, 0, 0);
        Drawable selection = skin.newDrawable("white",
              token(ColorMath.over(tokens.color("selected.background", mode), tokens.color("surface.input", mode))));
        Color text = token(tokens.color("text.primary", mode));
        Drawable cursor = skin.newDrawable("white", text);
        cursor.setMinWidth(border);

        float vertical = border + tokens.space().get("xs");
        float side = border + tokens.space().get("md");
        float iconSpace = tokens.size().get("icon.sm") + tokens.space().get("xs");
        for (boolean compact : new boolean[] { false, true }) {
            float height = tokens.size().get(compact ? "control.sm" : "control.md");
            for (String variant : INPUT_VARIANTS) {
                boolean search = variant.startsWith("-search");
                boolean reveal = variant.startsWith("-reveal");
                String state = search || reveal ? variant.substring("-search".length()) : variant;
                boolean status = state.equals("-error") || state.equals("-warning");
                float left = search ? side + iconSpace : side;
                // The clear or reveal button and a status icon sit at the trailing edge, the icon innermost
                float right = (search || reveal ? side + height - 2 * vertical : side) + (status ? iconSpace : 0);
                String rest = status ? "in" + state : "in";
                TokenFieldStyle style = new TokenFieldStyle();
                style.font = skin.getFont("hud-body");
                style.fontColor = text;
                style.focusedFontColor = text;
                style.messageFont = style.font;
                style.messageFontColor = token(tokens.color("text.muted", mode));
                style.cursor = cursor;
                style.selection = selection;
                style.background = field(rest, vertical, left, right, height);
                style.over = rest.equals("in") ? field("in-over", vertical, left, right, height) : style.background;
                style.focusedBackground = field("in-focus", vertical, left, right, height);
                // Disabled: no fill and a subtle edge; read-only is the same box with primary text
                style.disabledBackground = field("in-quiet", vertical, left, right, height);
                style.disabledFontColor = state.equals("-readonly") ? text
                      : token(tokens.color("text.disabled", mode));
                if (status) {
                    style.icon = skin.getDrawable(state.equals("-error") ? "icon-error" : "icon-warn");
                    style.iconColor = state.equals("-error") ? danger : warning;
                }
                skin.add("input" + variant + (compact ? COMPACT : ""), style, TextField.TextFieldStyle.class);
            }
        }
    }

    private Drawable field(String box, float vertical, float left, float right, float height) {
        NinePatchDrawable drawable = resized(box, vertical, left, height);
        drawable.setRightWidth(right);
        return drawable;
    }

    /** The edge of a check box or radio button and the height of a switch's track (icon.sm). */
    public static final float CHOICE_SIZE = DesignTokens.shipped().size().get("icon.sm");
    /** A switch's track length, as a multiple of its height. */
    public static final float SWITCH_LENGTH = 1.75f;
    /** The space between a switch's track edge and its thumb. */
    public static final float SWITCH_INSET = 2;
    /** The diameter of the dot in a selected radio button. */
    public static final float RADIO_DOT = 8;
    /** The focus ring's width plus its gap from the indicator: the room a choice keeps around it. */
    public static final float CHOICE_RING = DesignTokens.shipped().size().get("focus.width")
          + DesignTokens.shipped().size().get("focus.gap");
    /** The state suffixes of the choice drawables {@code choice-<shape><state>}, in {@link UiChoice}'s order. */
    public static final List<String> CHOICE_STATES = List.of("", "-over", "-down", "-disabled", "-on", "-on-over",
          "-on-down", "-on-disabled");

    /** The colors of a choice's label and marks; its faces are the {@code choice-*} drawables. */
    public static final class ChoiceStyle {
        public Color text;
        public Color disabledText;
        /** The check, dash, dot or on-thumb on the cream fill, disabled too. */
        public Color mark;
        /** An off switch's thumb. */
        public Color thumb;
        /** An off switch's thumb while disabled. */
        public Color disabledMark;
    }

    /**
     * The token choices in dark mode: a box, circle or pill in the input fill and control edge, strong under the
     * pointer; on, the cream fill of the primary button, leaning toward its mark on hover and further when pressed;
     * disabled, no fill and a subtle edge, or a faint cream fill when on. The focus ring sits a gap away, so it never
     * hides the state.
     */
    private void tokenChoices() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        int input = tokens.color("surface.input", mode);
        int hover = tokens.color("surface.hover", mode);
        int pressed = ColorMath.withAlpha(hover, Math.min(255, ColorMath.alpha(hover) * 2));
        int inverse = tokens.color("surface.inverse", mode);
        int onInverse = tokens.color("text.onInverse", mode);
        int inverseHover = ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.hover")), inverse);
        int inversePressed = ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.pressed")), inverse);
        int inverseDisabled = ColorMath.over(tokens.color("surface.inverseDisabled", mode),
              tokens.color("surface.base", mode));
        Color base = token(tokens.color("surface.base", mode));
        Color control = token(tokens.color("border.control", mode));
        Color strong = token(tokens.color("border.strong", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        Color ring = token(tokens.color("focus.ring", mode));
        float border = tokens.size().get("border");
        float width = tokens.size().get("focus.width");
        float gap = tokens.size().get("focus.gap");
        Map<String, Float> radii = Map.of("box", tokens.radius().get("sm"), "radio", CHOICE_SIZE / 2,
              "track", CHOICE_SIZE / 2);
        radii.forEach((shape, radius) -> {
            // A disc or pill is all corner, so it needs its minimum height to keep its shape
            float height = shape.equals("box") ? 0 : CHOICE_SIZE;
            String name = "choice-" + shape;
            box(name, token(input), control, border, radius, null, 0, 0, height);
            box(name + "-over", token(ColorMath.over(hover, input)), strong, border, radius, null, 0, 0, height);
            box(name + "-down", token(ColorMath.over(pressed, input)), strong, border, radius, null, 0, 0, height);
            box(name + "-disabled", base, subtle, border, radius, null, 0, 0, height);
            box(name + "-on", token(inverse), token(inverse), border, radius, null, 0, 0, height);
            box(name + "-on-over", token(inverseHover), strong, border, radius, null, 0, 0, height);
            box(name + "-on-down", token(inversePressed), strong, border, radius, null, 0, 0, height);
            box(name + "-on-disabled", token(inverseDisabled), null, 0, radius, null, 0, 0, height);
            box(name + "-focus", Color.CLEAR, ring, width, radius + gap + width, null, 0, 0,
                  height == 0 ? 0 : height + 2 * (gap + width));
        });
        box("choice-dot", Color.WHITE, null, 0, RADIO_DOT / 2, null, 0, 0, RADIO_DOT);
        float thumb = CHOICE_SIZE - 2 * SWITCH_INSET;
        box("choice-thumb", Color.WHITE, null, 0, thumb / 2, null, 0, 0, thumb);
        ChoiceStyle style = new ChoiceStyle();
        style.text = token(tokens.color("text.primary", mode));
        style.disabledText = token(tokens.color("text.disabled", mode));
        style.mark = token(onInverse);
        style.thumb = token(tokens.color("text.secondary", mode));
        style.disabledMark = style.disabledText;
        skin.add("default", style);
    }

    /** The colors of tabs and segmented controls; their faces are the {@code tab-*} and {@code seg-*} drawables. */
    public static final class OptionStyle {
        /** An option at rest. */
        public Color text;
        /** An option under the pointer, pressed or selected. */
        public Color activeText;
        /** A selected segment's label on its cream face, disabled too. */
        public Color onText;
        public Color disabledText;
        /** The selected tab's underline. */
        public Color underline;
        public Color disabledUnderline;
        /** The hairline under a tab strip and the dividers between unselected sections. */
        public Color hairline;
        /** The focus ring inside a tab. */
        public Color ring;
        /** A one-unit white texel, tinted for underlines and hairlines. */
        public Drawable line;
    }

    /**
     * Tabs and segmented controls in dark mode (docs/design-system/tokens.md, Tabs and Segmented control). A tab is a
     * label on the window surface with a wash under the pointer and an underline in the selection color when selected.
     * A segmented control is a row of separate raised segments, each with its own edge; the selected one has the cream
     * face of a checked toggle, faint while disabled, and its focus ring sits a gap outside it.
     */
    private void tokenOptions() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        int raised = tokens.color("surface.raised", mode);
        int hover = tokens.color("surface.hover", mode);
        int pressed = ColorMath.withAlpha(hover, Math.min(255, ColorMath.alpha(hover) * 2));
        int inverse = tokens.color("surface.inverse", mode);
        int onInverse = tokens.color("text.onInverse", mode);
        Color control = token(tokens.color("border.control", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        Color selectedEdge = token(tokens.color("selected.primary", mode));
        Color ring = token(tokens.color("focus.ring", mode));
        float border = tokens.size().get("border");
        float focus = tokens.size().get("focus.width");
        float radius = tokens.radius().get("md");

        // Tabs: a square wash under the pointer; pressed looks the same, as in FlatLaf
        skin.add("tab-over", skin.newDrawable("white", token(hover)), Drawable.class);

        // Segments stand apart, each a raised box with its own edge, like a row of toggle buttons
        int raisedHover = ColorMath.over(hover, raised);
        Color strong = token(tokens.color("border.strong", mode));
        box("seg-item", token(raised), control, border, radius, null, 0, 0, 0);
        box("seg-item-disabled", token(raised), subtle, border, radius, null, 0, 0, 0);
        box("seg-over", token(raisedHover), strong, border, radius, null, 0, 0, 0);
        box("seg-down", token(ColorMath.over(pressed, raised)), strong, border, radius, null, 0, 0, 0);
        box("seg-on", token(inverse), token(inverse), border, radius, null, 0, 0, 0);
        box("seg-on-over", token(ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.hover")), inverse)),
              strong, border, radius, null, 0, 0, 0);
        box("seg-on-down", token(ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.pressed")),
              inverse)), strong, border, radius, null, 0, 0, 0);
        box("seg-on-disabled", token(ColorMath.over(tokens.color("surface.inverseDisabled", mode), raised)), null, 0,
              radius, null, 0, 0, 0);
        box("seg-focus", Color.CLEAR, ring, focus, radius, null, 0, 0, 0);
        // The selected segment's ring sits a gap outside its cream face, or, where clipped, two lines inside it
        box("seg-on-focus", Color.CLEAR, ring, focus, radius + CHOICE_RING, null, 0, 0, 0);
        box("seg-on-focus-outer", Color.CLEAR, token(tokens.color("text.primary", mode)), border, radius, null, 0, 0,
              0);
        box("seg-on-focus-inner", Color.CLEAR, token(onInverse), border, Math.max(radius - border, 0), null, 0, 0, 0);

        OptionStyle style = new OptionStyle();
        style.text = token(tokens.color("text.secondary", mode));
        style.activeText = token(tokens.color("text.primary", mode));
        style.onText = token(onInverse);
        style.disabledText = token(tokens.color("text.disabled", mode));
        style.underline = selectedEdge;
        style.disabledUnderline = subtle;
        style.hairline = subtle;
        style.ring = ring;
        style.line = skin.newDrawable("white");
        skin.add("default", style);
    }

    /** The colors and drawables of a select and its popup list ({@link UiSelect}, {@link UiListPopup}). */
    public static final class SelectStyle {
        /** The face at rest, under the pointer or open, with the focus ring, and disabled: the input boxes. */
        public Drawable face;
        public Drawable faceOver;
        public Drawable faceFocus;
        public Drawable faceDisabled;
        /** The focus ring around an inline select, which has no box. */
        public Drawable ring;
        /** The popup list's frame. */
        public Drawable popup;
        /** The wash under the row at the pointer or the keyboard. */
        public Drawable rowOver;
        /** The inline select's dotted underline in white, tinted by the batch color per state. */
        public Drawable line;
        public Color text;
        public Color placeholder;
        /** An inline select's value at rest; under the pointer, open or focused it takes {@link #text}. */
        public Color inlineText;
        public Color disabledText;
        public Color chevron;
        /** The check before the current value in the popup list. */
        public Color check;
        public Color underline;
        public Color underlineOver;
        public Color underlineDisabled;
    }

    /**
     * Selects and their popup list in dark mode (docs/design-system/tokens.md, Select). The face is the input box; the
     * inline variant has a dotted underline instead. The popup list is a popover frame with a control edge: the row
     * at the pointer or the keyboard gets the hover wash, and the current value a check in the selection color, which keeps
     * meaning selected.
     */
    private void tokenSelects() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        Color control = token(tokens.color("border.control", mode));
        float border = tokens.size().get("border");
        box("sel-ring", Color.CLEAR, token(tokens.color("focus.ring", mode)), tokens.size().get("focus.width"),
              tokens.radius().get("md"), null, 0, 0, 0);
        box("sel-popup", token(tokens.color("surface.popover", mode)), control, border, tokens.radius().get("md"),
              null, 0, 0, 0);
        box("sel-row-over", token(tokens.color("surface.hover", mode)), null, 0, tokens.radius().get("sm"), null, 0,
              0, 0);

        SelectStyle style = new SelectStyle();
        style.face = skin.getDrawable("in");
        style.faceOver = skin.getDrawable("in-over");
        style.faceFocus = skin.getDrawable("in-focus");
        style.faceDisabled = skin.getDrawable("in-quiet");
        style.ring = skin.getDrawable("sel-ring");
        style.popup = skin.getDrawable("sel-popup");
        style.rowOver = skin.getDrawable("sel-row-over");
        style.line = new Dashes(skin.get("white", Texture.class), Color.WHITE, border, 2, 2, false);
        style.text = token(tokens.color("text.primary", mode));
        style.placeholder = token(tokens.color("text.muted", mode));
        style.inlineText = token(tokens.color("text.secondary", mode));
        style.disabledText = token(tokens.color("text.disabled", mode));
        style.chevron = token(tokens.color("text.secondary", mode));
        style.check = token(tokens.color("selected.primary", mode));
        style.underline = control;
        style.underlineOver = token(tokens.color("border.strong", mode));
        style.underlineDisabled = token(tokens.color("border.subtle", mode));
        skin.add("default", style);
    }

    /** The token look of hud-v3's segmented control ({@code UiKit.segmented}); add {@link #COMPACT} for 28 high. */
    public static final String TOKEN_SEGMENT = "token-segment";
    /** The token look of hud-v3's underline tabs ({@code UiKit.segmented}); add {@link #COMPACT} for 28 high. */
    public static final String TOKEN_TAB = "token-tab";
    /** The token look of hud-v3's drop-down face ({@code UiKit.select}). */
    public static final String TOKEN_SELECT = "token-select";
    /** The token look of hud-v3's inline drop-down face. */
    public static final String TOKEN_SELECT_INLINE = "token-select-inline";
    /** The token look of hud-v3's menu row ({@code UiKit.menuRow}, {@code UiMenuList}). */
    public static final String TOKEN_MENU_ROW = "token-menu-row";

    /**
     * Token v0 styles for hud-v3's own selection widgets, so migrating restyles them rather than replacing them
     * (docs/design-system/tokens.md, Segmented control, Tabs, Select, Choices and Menus). The widgets also read the
     * {@code token-tabs}, {@code token-divider}, {@code token-popup} and {@code choice-box} drawables.
     */
    private void tokenSelection() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        Texture white = skin.get("white", Texture.class);
        Color text = token(tokens.color("text.primary", mode));
        Color secondaryText = token(tokens.color("text.secondary", mode));
        Color disabledText = token(tokens.color("text.disabled", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        Color selectedEdge = token(tokens.color("selected.primary", mode));
        float xs = tokens.space().get("xs");
        float border = tokens.size().get("border");
        float underline = tokens.size().get("selection.width");

        skin.add("token-tabs", new Dashes(white, subtle, border, 0, 0, false), Drawable.class);
        skin.add("token-divider", skin.newDrawable("white", subtle), Drawable.class);
        skin.add("token-popup", resized("sel-popup", xs, xs, 0), Drawable.class);
        for (boolean compact : new boolean[] { false, true }) {
            float height = tokens.size().get(compact ? "control.sm" : "control.md");
            float side = tokens.space().get(compact ? "md" : "lg");
            String suffix = compact ? COMPACT : "";

            TokenButtonStyle segment = new TokenButtonStyle();
            segment.font = skin.getFont("hud-medium");
            segment.fontColor = secondaryText;
            segment.overFontColor = text;
            segment.downFontColor = text;
            segment.checkedFontColor = text;
            segment.disabledFontColor = disabledText;
            segment.up = sized(resized("seg-item", 0, 0, 0), 0, side, height, false);
            segment.over = sized(resized("seg-over", 0, 0, 0), 0, side, height, false);
            segment.down = sized(resized("seg-down", 0, 0, 0), 0, side, height, false);
            segment.checked = sized(resized("seg-on", 0, 0, 0), 0, side, height, false);
            segment.checkedOver = sized(resized("seg-on-over", 0, 0, 0), 0, side, height, false);
            segment.checkedDown = sized(resized("seg-on-down", 0, 0, 0), 0, side, height, false);
            segment.checkedDisabled = sized(resized("seg-on-disabled", 0, 0, 0), 0, side, height, false);
            onInverseWhenChecked(segment, token(DesignTokens.shipped().color("text.onInverse", Mode.DARK)));
            segment.disabled = sized(resized("seg-item-disabled", 0, 0, 0), 0, side, height, false);
            skin.add(TOKEN_SEGMENT + suffix, segment, TextButton.TextButtonStyle.class);

            TokenButtonStyle tab = new TokenButtonStyle();
            tab.font = skin.getFont("hud-medium");
            tab.fontColor = secondaryText;
            tab.overFontColor = text;
            tab.downFontColor = text;
            tab.checkedFontColor = text;
            tab.disabledFontColor = disabledText;
            tab.up = sized(flat(Color.CLEAR, 0, 0), 0, side, height, false);
            tab.over = sized(skin.newDrawable("tab-over"), 0, side, height, false);
            tab.down = tab.over;
            tab.checked = sized(new Dashes(white, selectedEdge, underline, 0, 0, false), 0, side, height, false);
            tab.checkedOver = tab.checked;
            tab.checkedDown = tab.checked;
            tab.disabled = tab.up;
            skin.add(TOKEN_TAB + suffix, tab, TextButton.TextButtonStyle.class);
            for (TokenButtonStyle section : List.of(segment, tab)) {
                TokenButtonStyle caps = new TokenButtonStyle(section);
                caps.font = skin.getFont(CAPS_FONT);
                skin.add((section == tab ? TOKEN_TAB : TOKEN_SEGMENT) + suffix + CAPS, caps,
                      TextButton.TextButtonStyle.class);
            }
        }

        TokenButtonStyle select = new TokenButtonStyle();
        select.font = skin.getFont("hud-body");
        select.fontColor = text;
        select.disabledFontColor = disabledText;
        float faceLeft = border + tokens.space().get("md");
        float faceRight = border + tokens.space().get("sm");
        float regular = tokens.size().get("control.md");
        select.up = resized("in", 0, faceLeft, regular);
        select.up.setRightWidth(faceRight);
        select.over = resized("in-over", 0, faceLeft, regular);
        select.over.setRightWidth(faceRight);
        select.down = select.over;
        select.disabled = resized("in-quiet", 0, faceLeft, regular);
        select.disabled.setRightWidth(faceRight);
        skin.add(TOKEN_SELECT, select, TextButton.TextButtonStyle.class);
        TokenButtonStyle inline = new TokenButtonStyle();
        inline.font = skin.getFont("hud-small");
        inline.fontColor = secondaryText;
        inline.overFontColor = text;
        inline.downFontColor = text;
        inline.disabledFontColor = disabledText;
        // The dotted underline hugs the text, as an inline link's does
        float underlineGap = tokens.space().get("xxs");
        inline.up = sized(new Dashes(white, token(tokens.color("border.control", mode)), border, 2, 2, false),
              underlineGap, 0, 0, false);
        inline.over = sized(new Dashes(white, token(tokens.color("border.strong", mode)), border, 2, 2, false),
              underlineGap, 0, 0, false);
        inline.down = inline.over;
        inline.disabled = sized(new Dashes(white, subtle, border, 2, 2, false), underlineGap, 0, 0, false);
        skin.add(TOKEN_SELECT_INLINE, inline, TextButton.TextButtonStyle.class);

        // The popup's xs padding insets the rows' wash; text starts xs + md from the popup's edge
        TokenButtonStyle row = new TokenButtonStyle();
        row.font = skin.getFont("hud-body");
        row.fontColor = text;
        row.disabledFontColor = disabledText;
        row.detailFontColor = token(tokens.color("text.muted", mode));
        float rowSide = tokens.space().get("md") - xs;
        float compactHeight = tokens.size().get("control.sm");
        row.up = sized(flat(Color.CLEAR, 0, 0), 0, rowSide, compactHeight, false);
        row.over = sized(resized("sel-row-over", 0, 0, 0), 0, rowSide, compactHeight, false);
        row.down = row.over;
        row.checked = row.over;
        row.checkedOver = row.over;
        row.checkedDown = row.over;
        row.disabled = row.up;
        skin.add(TOKEN_MENU_ROW, row, TextButton.TextButtonStyle.class);
    }

    /** A libGDX color from a token's ARGB value. */
    private static Color token(int argb) {
        Color color = new Color();
        Color.argb8888ToColor(color, argb);
        return color;
    }

    /** The shipped token color {@code role} (such as text.secondary) in dark mode, the only mode native views have. */
    public static Color tokenColor(String role) {
        return token(DesignTokens.shipped().color(role, Mode.DARK));
    }

    /** Pads a drawable: {@code vertical} units above and below its content, {@code horizontal} beside it. */
    public static <T extends Drawable> T pad(T drawable, float vertical, float horizontal) {
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        return drawable;
    }

    /**
     * A standalone utility (.b.brk): corner ticks at rest, a full outline under the pointer, filled when checked.
     * Checked utilities keep their fill when hovered or held.
     */
    private TextButton.TextButtonStyle utility(TextButton.TextButtonStyle hud, String font, float top,
          float horizontal, float bottom, float minWidth, float minHeight) {
        Texture white = skin.get("white", Texture.class);
        Color outline = Color.valueOf("F5F7F3");
        UnaryOperator<HudFrame> size = frame -> {
            pad(frame, top, horizontal).setBottomHeight(bottom);
            frame.setMinWidth(minWidth);
            frame.setMinHeight(minHeight);
            return frame;
        };
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(hud);
        style.font = skin.getFont(font);
        style.up = size.apply(new HudFrame(white, rgba(25, 33, 33, .9f), null, TICK, 8, null));
        style.over = size.apply(new HudFrame(white, rgba(25, 33, 33, .95f), null, null, 0, outline));
        style.down = style.over;
        style.checked = size.apply(new HudFrame(white, FILL, null, null, 0, outline));
        style.checkedOver = style.checked;
        style.checkedDown = style.checked;
        style.disabled = style.up;
        return style;
    }

    /**
     * The styles only the toolkit's widgets use: the 30-unit icon button (.b.ib), the normal-case buttons (.brow .b),
     * list rows (.row), drop-down faces (.fsel, .amsel), underline tabs (.tabs, .ltabs), the search field, the dot,
     * UiList's flying row, its shadow and its slot, and UiAngleDial's parts.
     */
    private void kitStyles(TextButton.TextButtonStyle hud) {
        Texture white = skin.get("white", Texture.class);
        // .brow .b: a quiet button in 500 12px Roboto without tracking or upper-casing, at least 34 units tall.
        skin.add("hud-plain", quietButton("hud-medium", 8, 12, 34));
        Drawable none = flat(Color.CLEAR, 0, 0);
        none.setMinWidth(30);
        none.setMinHeight(30);
        TextButton.TextButtonStyle icon = new TextButton.TextButtonStyle(hud);
        icon.up = none;
        icon.over = box("button-icon-over", rgba(255, 255, 255, .04f), alpha(HOVER, .6f), 1, 3, null, 0, 0, 30);
        icon.over.setMinWidth(30);
        icon.down = icon.over;
        icon.checked = resized("button-checked", 0, 0, 30);
        icon.checked.setMinWidth(30);
        // Use the icon-sized checked face while hovered or held, with the shared checked text colors.
        icon.checkedOver = icon.checked;
        icon.checkedDown = icon.checked;
        icon.disabled = none;
        skin.add("hud-icon", icon);

        TextButton.TextButtonStyle row = new TextButton.TextButtonStyle();
        row.font = skin.getFont("hud-body");
        row.fontColor = TEXT;
        row.up = skin.getDrawable("row");
        row.over = skin.getDrawable("row-over");
        row.down = row.over;
        // .row.sel comes after .row:hover, so a selected row keeps its look under the pointer.
        row.checked = skin.getDrawable("row-selected");
        row.checkedOver = row.checked;
        row.checkedDown = row.checked;
        skin.add("hud-row", row);

        TextButton.TextButtonStyle select = new TextButton.TextButtonStyle();
        select.font = skin.getFont("hud-body");
        select.fontColor = TEXT;
        select.disabledFontColor = DISABLED;
        select.up = skin.getDrawable("select");
        skin.add("hud-select", select);
        TextButton.TextButtonStyle inline = new TextButton.TextButtonStyle(select);
        inline.font = skin.getFont("hud-small");
        inline.fontColor = ACCENT;
        inline.overFontColor = TEXT;
        // .amsel: no box, a dotted underline, two units of room for the chevron.
        Drawable dotted = new Dashes(white, Color.valueOf("7C8886"), 1, 1, 1, false);
        dotted.setRightWidth(2);
        inline.up = dotted;
        skin.add("hud-select-inline", inline);

        TextButton.TextButtonStyle tab = new TextButton.TextButtonStyle();
        tab.font = skin.getFont("hud-medium");
        tab.fontColor = MUTED;
        tab.checkedFontColor = Color.WHITE;
        tab.up = flat(Color.CLEAR, 0, 0);
        tab.checked = new Dashes(white, MINT, 2, 0, 0, false);
        for (Drawable state : List.of(tab.up, tab.checked)) {
            state.setTopHeight(6);
            state.setBottomHeight(8);
        }
        skin.add("hud-tab", tab);
        // The log's tabs (.ltabs span): the same underline with upper-case condensed captions.
        TextButton.TextButtonStyle capsTab = new TextButton.TextButtonStyle(tab);
        capsTab.font = skin.getFont("hud-button");
        skin.add("hud-tab-caps", capsTab);
        // The tab bar's hairline under every tab (.tabs border-bottom).
        skin.add("tabs", new Dashes(white, LINE, 1, 0, 0, false), Drawable.class);

        NinePatchDrawable search = new NinePatchDrawable((NinePatchDrawable) skin.getDrawable("field"));
        search.setRightWidth(30);
        NinePatchDrawable searchFocused = new NinePatchDrawable((NinePatchDrawable) skin.getDrawable("field-focused"));
        searchFocused.setRightWidth(30);
        // .field's box: 13-unit text at its normal line height (15.2) with 7 units of padding and a 1-unit border
        // above and below, where the field itself measures the font's cap height and descent (30)
        for (NinePatchDrawable box : List.of(search, searchFocused)) {
            box.setMinHeight(31.2f);
        }
        TextField.TextFieldStyle field = new TextField.TextFieldStyle(skin.get("hud", TextField.TextFieldStyle.class));
        field.background = search;
        field.focusedBackground = searchFocused;
        skin.add("hud-search", field);

        // The unread dot (.b.util .dotb).
        box("dot", AMBER, null, 0, 3.5f, null, 0, 0, 7).setMinWidth(7);

        // A reorderable list's flying row: the selected row's look (.row.sel) on an opaque raised face over a soft
        // shadow; and the slot it will drop into, recessed and outlined in mint.
        box("row-lifted", rgba(31, 46, 46, .96f), MINT, 1, 3, MINT, 0, 0, 0);
        shadow("shadow", 9, 3, .5f);
        box("row-slot", rgba(0, 0, 0, .22f), alpha(MINT, .5f), 1, 3, null, 0, 0, 0);

        // UiAngleDial, after MekBay's dials: a raised scale around a recessed well, a dark hub for the value, the
        // needle's grip, and a white round-ended stroke that the dial tints for its ticks, needle and turn.
        float dial = UiAngleDial.SIZE, well = UiAngleDial.WELL, hub = UiAngleDial.HUB, grip = UiAngleDial.KNOB;
        box("dial-face", rgba(255, 255, 255, .06f), rgba(255, 255, 255, .14f), 1, dial / 2, null, 0, 0, dial);
        box("dial-well", rgba(0, 0, 0, .32f), LINE, 1, well / 2, null, 0, 0, well);
        box("dial-hub", rgba(8, 12, 13, .96f), alpha(MINT, .5f), 1.5f, hub / 2, null, 0, 0, hub);
        box("dial-knob", MINT, POP, 2, grip / 2, null, 0, 0, grip);
        box("dial-stroke", Color.WHITE, null, 0, UiAngleDial.STROKE / 2, null, 0, 0, UiAngleDial.STROKE);
        PickerStyle picker = new PickerStyle();
        picker.discs = "dial";
        picker.accent = MINT;
        picker.major = ACCENT;
        picker.minor = TICK;
        picker.line = MUTED;
        picker.hover = TEXT;
        picker.label = MUTED;
        picker.labelOn = MINT;
        skin.add("default", picker);
    }

    /**
     * The colors of {@link UiAngleDial} and {@link UiHexSides}: {@code default} is hud-v3's, {@link #TOKEN} Token
     * v0's. The dial's discs are the {@code <discs>-face}, {@code -well}, {@code -hub} and {@code -knob} drawables.
     */
    public static final class PickerStyle {
        public String discs;
        /** The dial's zero tick, turn and needle, and the chosen hex sides. */
        public Color accent;
        /** The dial's ticks every 30 degrees and every 10. */
        public Color major;
        public Color minor;
        /** A hex side at rest and under the pointer. */
        public Color line;
        public Color hover;
        /** A hex side's direction at rest and while chosen; under the pointer it takes {@link #hover}. */
        public Color label;
        public Color labelOn;
    }

    /** The name of the Token v0 styles of hud-v3's own inputs: the slider style and the pickers' colors. */
    public static final String TOKEN = "token";
    /** The token slider of the game presentation, whose thumb is a thin bar (tokens.md, Sliders). */
    public static final String TOKEN_BAR = "token-slider-bar";

    /**
     * Token v0 styles for hud-v3's own inputs (docs/design-system/tokens.md, Inputs and Sliders): the slider's pill
     * track, accent fill and inverse disc, and the pickers' discs and colors. The text fields use the {@code input} styles.
     */
    private void tokenInputs() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        int accent = tokens.color("accent.primary", mode);
        Color control = token(tokens.color("border.control", mode));
        Color strong = token(tokens.color("border.strong", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        float border = tokens.size().get("border");
        float track = tokens.space().get("xs");
        float thumb = tokens.size().get("icon.sm");
        // The thumb is a handle, so it takes the inverse face and leans toward its ink like the primary button
        int inverse = tokens.color("surface.inverse", mode);
        int onInverse = tokens.color("text.onInverse", mode);
        int inverseHover = ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.hover")), inverse);
        int inversePressed = ColorMath.over(ColorMath.withAlpha(onInverse, tokens.alpha("tint.pressed")), inverse);

        Slider.SliderStyle slider = new Slider.SliderStyle();
        slider.background = box("slider-track", control, null, 0, track / 2, null, 0, 0, track);
        slider.background.setMinWidth(0);
        slider.knobBefore = box("slider-fill", token(accent), null, 0, track / 2, null, 0, 0, track);
        slider.knobBefore.setMinWidth(0);
        slider.knob = box("slider-thumb", token(inverse), token(inverse), border, thumb / 2, null, 0, 0, thumb);
        slider.knob.setMinWidth(thumb);
        slider.knobOver = box("slider-thumb-over", token(inverseHover), strong, border, thumb / 2, null, 0, 0, thumb);
        slider.knobOver.setMinWidth(thumb);
        slider.knobDown = box("slider-thumb-down", token(inversePressed), strong, border, thumb / 2, null, 0, 0,
              thumb);
        slider.knobDown.setMinWidth(thumb);
        slider.disabledBackground = box("slider-track-disabled", subtle, null, 0, track / 2, null, 0, 0, track);
        slider.disabledBackground.setMinWidth(0);
        slider.disabledKnob = box("slider-thumb-disabled", token(tokens.color("text.disabled", mode)), null, 0,
              thumb / 2, null, 0, 0, thumb);
        slider.disabledKnob.setMinWidth(thumb);
        skin.add(TOKEN, slider);

        // The game presentation's thumb: hud-v3's flat bar, xs wide and icon.sm long, in the inverse color
        Slider.SliderStyle bar = new Slider.SliderStyle(slider);
        float barWidth = tokens.space().get("xs");
        float barRadius = 0;
        bar.knob = box("slider-bar", token(inverse), null, 0, barRadius, null, 0, 0,
              thumb);
        bar.knob.setMinWidth(barWidth);
        bar.knobOver = box("slider-bar-over", token(inverseHover), null, 0, barRadius, null, 0,
              0, thumb);
        bar.knobOver.setMinWidth(barWidth);
        bar.knobDown = bar.knobOver;
        bar.disabledKnob = box("slider-bar-disabled", token(tokens.color("text.disabled", mode)), null, 0, barRadius,
              null, 0, 0, thumb);
        bar.disabledKnob.setMinWidth(barWidth);
        skin.add(TOKEN_BAR, bar);

        float dial = UiAngleDial.SIZE, well = UiAngleDial.WELL, hub = UiAngleDial.HUB, grip = UiAngleDial.KNOB;
        Color base = token(tokens.color("surface.base", mode));
        box("token-dial-face", token(tokens.color("surface.raised", mode)), control, border, dial / 2, null, 0, 0,
              dial);
        box("token-dial-well", token(tokens.color("surface.input", mode)), control, border, well / 2, null, 0, 0,
              well);
        // The hub keeps hud-v3's mint ring, which the needle stops at, so the value inside stays on the plain base
        box("token-dial-hub", base, token(accent), tokens.size().get("selection.width"), hub / 2, null, 0, 0, hub);
        box("token-dial-knob", token(accent), base, 2, grip / 2, null, 0, 0, grip);
        PickerStyle picker = new PickerStyle();
        picker.discs = "token-dial";
        picker.accent = token(accent);
        picker.major = strong;
        picker.minor = control;
        picker.line = control;
        picker.hover = token(tokens.color("text.primary", mode));
        picker.label = token(tokens.color("text.muted", mode));
        picker.labelOn = token(tokens.color("text.secondary", mode));
        skin.add(TOKEN, picker);
    }

    /** The token look of hud-v3's list and tree rows ({@code hud-row}, {@code UiTree}); control.sm high. */
    public static final String TOKEN_ROW = "token-row";
    /** The token look of hud-v3's scrolling list ({@code hud-list}): the input frame and the quiet scroll bar. */
    public static final String TOKEN_LIST = "token-list";
    /** The token look of hud-v3's split pane divider ({@code hud-split}). */
    public static final String TOKEN_SPLIT = "token-split";
    /** The Button style of a card that opens something ({@code UiPictureCard}): its edge in border.strong on hover. */
    public static final String TOKEN_PICTURE_CARD = "token-picture-card";

    /**
     * Token v0 styles for hud-v3's own lists, trees, grids and splits (docs/design-system/tokens.md, Lists and trees,
     * Scrollbars and Split panes). Rows have no box: a hover wash, and a selected wash with a marker in the selection color at the
     * leading edge. The scroll bar's hover and drag looks are the {@code scroll-rail}, {@code scroll-thumb-over} and
     * {@code scroll-thumb-down} drawables, and the divider's is {@code token-split-active}; {@code UiKit} swaps them in.
     */
    private void tokenLists() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        Color control = token(tokens.color("border.control", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        Color selectedEdge = token(tokens.color("selected.primary", mode));
        Color hover = token(tokens.color("surface.hover", mode));
        float border = tokens.size().get("border");
        float xs = tokens.space().get("xs");
        float sm = tokens.space().get("sm");
        float md = tokens.space().get("md");
        float radius = tokens.radius().get("sm");
        float markerWidth = tokens.size().get("selection.width");
        float markerHeight = tokens.size().get("icon.sm");
        float height = tokens.size().get("control.sm");

        Drawable marker = skin.newDrawable("white", selectedEdge);
        NinePatchDrawable selectedWash = box("list-row-selected", token(tokens.color("selected.background", mode)),
              null, 0, radius, null, 0, 0, 0);
        Drawable selected = new BaseDrawable() {
            @Override
            public void draw(Batch batch, float x, float y, float width, float rowHeight) {
                selectedWash.draw(batch, x, y, width, rowHeight);
                marker.draw(batch, x, y + Math.round((rowHeight - markerHeight) / 2), markerWidth, markerHeight);
            }
        };
        TokenButtonStyle row = new TokenButtonStyle();
        row.font = skin.getFont("hud-body");
        row.fontColor = token(tokens.color("text.primary", mode));
        row.disabledFontColor = token(tokens.color("text.disabled", mode));
        row.detailFontColor = token(tokens.color("text.secondary", mode));
        row.up = rowPadding(flat(Color.CLEAR, 0, 0), markerWidth + sm, md, height);
        row.over = rowPadding(box("list-row-over", hover, null, 0, radius, null, 0, 0, 0), markerWidth + sm, md,
              height);
        row.down = row.over;
        row.checked = rowPadding(selected, markerWidth + sm, md, height);
        row.checkedOver = row.checked;
        row.checkedDown = row.checked;
        row.disabled = row.up;
        skin.add(TOKEN_ROW, row, TextButton.TextButtonStyle.class);
        // A reorderable list's flying row on the raised surface, and the slot it will drop into
        box("token-row-lifted", token(tokens.color("surface.raised", mode)), control, border, radius, null, 0, 0, 0);
        box("token-row-slot", token(tokens.color("surface.base", mode)), subtle, border, radius, null, 0, 0, 0);

        // The frame pads xs, which insets the rows' shape. The bar sits inside the frame's edge, beside the rows rather
        // than over them (UiKit.scrollList), xs clear of them, so no row content or wash runs under it.
        float xxs = tokens.space().get("xxs");
        float bar = sm + 2 * xxs;
        ScrollPane.ScrollPaneStyle list = new ScrollPane.ScrollPaneStyle();
        list.background = box("list-frame", token(tokens.color("surface.input", mode)), control, border,
              tokens.radius().get("md"), null, xs, xs, 0);
        list.vScroll = scrollStrip(null, bar, xs);
        list.vScrollKnob = scrollThumb("scroll-thumb", control, sm, height, xxs);
        skin.add(TOKEN_LIST, list);
        skin.add("scroll-rail", scrollStrip(box("scroll-rail-pill", hover, null, 0, bar / 2, null, 0, 0, 0), bar, xs),
              Drawable.class);
        scrollThumb("scroll-thumb-over", token(tokens.color("text.muted", mode)), sm, height, xxs);
        scrollThumb("scroll-thumb-down", token(tokens.color("text.secondary", mode)), sm, height, xxs);

        box("token-card", token(tokens.color("surface.panel", mode)), subtle, border, tokens.radius().get("md"), null,
              0, 0, 0);
        // A selected card keeps its surface under an accent edge; a card that takes a drop rises, under the same edge
        Color accentEdge = token(tokens.color("accent.primary", mode));
        box("token-card-selected", token(tokens.color("surface.panel", mode)), accentEdge, border,
              tokens.radius().get("md"), null, 0, 0, 0);
        box("token-card-drop", token(tokens.color("surface.raised", mode)), accentEdge, 2 * border,
              tokens.radius().get("md"), null, 0, 0, 0);
        // A card that opens something takes the border.strong edge under the pointer
        Button.ButtonStyle pictureCard = new Button.ButtonStyle();
        pictureCard.up = skin.getDrawable("token-card");
        pictureCard.over = box("token-card-over", token(tokens.color("surface.panel", mode)),
              token(tokens.color("border.strong", mode)), border, tokens.radius().get("md"), null, 0, 0, 0);
        pictureCard.down = pictureCard.over;
        pictureCard.disabled = pictureCard.up;
        skin.add(TOKEN_PICTURE_CARD, pictureCard);

        float band = md;
        float active = tokens.size().get("selection.width");
        Drawable rest = skin.newDrawable("white", subtle);
        Drawable accent = skin.newDrawable("white", token(tokens.color("accent.primary", mode)));
        skin.add(TOKEN_SPLIT, new com.badlogic.gdx.scenes.scene2d.ui.SplitPane.SplitPaneStyle(
              splitLine(rest, border, band)));
        skin.add("token-split-active", splitLine(accent, active, band), Drawable.class);
    }

    /** The token look of hud-v3's panel ({@code panel}): a paper roll edge, the corner ticks and lg padding. */
    public static final String TOKEN_PANEL = "token-panel";
    /** The token panel with status.danger ticks, which replaces {@code panel-foe}; its header carries the icon. */
    public static final String TOKEN_PANEL_DANGER = "token-panel-danger";
    /** The plain frame, the edge only, which frames panels inside panels. */
    public static final String TOKEN_FRAME = "token-frame";
    /**
     * The panel's fill and padding inside the plain frame's edge, with no ticks: a card that sits within an outer frame
     * that has them, such as the start screen's corners.
     */
    public static final String TOKEN_PANEL_PLAIN = "token-panel-plain";
    /** The rails frame, which replaces {@code panel-rails}: the overlay panel with border.control rails, no corners. */
    public static final String TOKEN_RAILS = "token-rails";
    /**
     * The Button style of a folded panel's rail ({@code UiKit.rail}): the token panel, its edge in border.control on
     * hover, and the selection's wash, edge and ticks while checked.
     */
    public static final String TOKEN_RAIL = "token-rail";

    /**
     * Token v0 styles for hud-v3's containers (docs/design-system/tokens.md, Panels, Dialogs and Tooltips): the panel
     * and dialog card frame, its danger variant, the plain frame, the panel over the board
     * ({@code token-panel-overlay}, on the translucent overlay surface) and the {@code token} tooltip card.
     */
    private void tokenContainers() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        Texture white = skin.get("white", Texture.class);
        Color panel = token(tokens.color("surface.panel", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        Color strong = token(tokens.color("border.strong", mode));
        float border = tokens.size().get("border");
        float tick = tokens.size().get("tick.panel");
        float sm = tokens.space().get("sm");
        float md = tokens.space().get("md");
        float lg = tokens.space().get("lg");

        // Panels are paper rolls: the edge runs along the top and bottom, and the corner ticks close the sides
        skin.add(TOKEN_PANEL, pad(new HudFrame(white, panel, null, strong, tick, subtle, border).paperRoll(), lg, lg),
              Drawable.class);
        skin.add(TOKEN_PANEL_DANGER, pad(new HudFrame(white, panel, null,
              token(tokens.color("status.danger", mode)), tick, subtle, border).paperRoll(), lg, lg), Drawable.class);
        Integer overlay = tokens.colorToken("surface.panel").overlay();
        Color overlayPanel = overlay == null ? panel : token(overlay);
        skin.add(TOKEN_PANEL + "-overlay", pad(new HudFrame(white, overlayPanel, null, strong, tick, subtle, border)
              .paperRoll(), lg, lg), Drawable.class);
        skin.add(TOKEN_FRAME, pad(new HudFrame(white, Color.CLEAR, null, null, 0, subtle, border), sm, md),
              Drawable.class);
        skin.add(TOKEN_PANEL_PLAIN, pad(new HudFrame(white, panel, null, null, 0, subtle, border), lg, lg),
              Drawable.class);
        // HudFrame's rails are tick.width (2) high
        skin.add(TOKEN_RAILS, pad(new HudFrame(white, overlayPanel, token(tokens.color("border.control", mode)), null,
              0, null), sm, md), Drawable.class);

        // A rail is the panel as a toggle: its edge lights on hover, and it takes the selection's wash and edge
        Color selectedEdge = token(tokens.color("selected.primary", mode));
        Drawable railOver = pad(new HudFrame(white, panel, null, strong, tick,
              token(tokens.color("border.control", mode)), border).paperRoll(), lg, lg);
        Drawable railOn = pad(new HudFrame(white, token(ColorMath.over(tokens.color("selected.background", mode),
              tokens.color("surface.panel", mode))), null, selectedEdge, tick, selectedEdge, border).paperRoll(), lg,
              lg);
        Button.ButtonStyle rail = new Button.ButtonStyle();
        rail.up = skin.getDrawable(TOKEN_PANEL);
        rail.over = railOver;
        rail.down = railOver;
        rail.checked = railOn;
        rail.checkedOver = railOn;
        rail.checkedDown = railOn;
        skin.add(TOKEN_RAIL, rail);

        Label.LabelStyle tipText = new Label.LabelStyle(skin.get(TYPE + "small", Label.LabelStyle.class));
        tipText.fontColor = token(tokens.color("text.primary", mode));
        TextTooltip.TextTooltipStyle tip = new TextTooltip.TextTooltipStyle(tipText, box("token-tip",
              token(tokens.color("surface.popover", mode)), token(tokens.color("border.control", mode)), border,
              tokens.radius().get("md"), null, sm, md, 0));
        tip.wrapWidth = 280;
        skin.add(TOKEN, tip);
    }

    /** The Label style of a token count badge's digits: caption's size in the medium face. */
    public static final String TOKEN_BADGE = "token-badge";
    /** The prefix of a token chip's frame, followed by neutral, success, warning, danger, accent or gamemaster. */
    public static final String TOKEN_CHIP = "token-chip-";
    /**
     * The prefix of an acting chip's Button style, followed by the frame's tone: the chip's frame at rest, washed in its
     * status color under the pointer, filled pressed or checked (lit).
     */
    public static final String TOKEN_CHIP_BUTTON = "token-chip-button-";
    /**
     * The Button style of a filter chip ({@link UiFilterChip}): the neutral chip at rest, washed under the pointer, and
     * filled in the accent's pressed tint with its accent edge while on.
     */
    public static final String TOKEN_FILTER_CHIP = "token-filter-chip";
    /** The prefix of a token toast's card, followed by the level's name in lower case. */
    public static final String TOKEN_TOAST = "token-toast-";

    /**
     * Token v0 styles for hud-v3's status elements (docs/design-system/tokens.md, Chips and badges, Progress bars and
     * Toasts): the status chips' frames, the count badge with its ring and the dot, a meter's pill track and fill
     * ({@code token-track}, and {@code token-bar} tinted when drawn) and a toast card for each level.
     */
    private void tokenStatus() {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = Mode.DARK;
        float border = tokens.size().get("border");
        float radius = tokens.radius().get("md");
        float xs = tokens.space().get("xs");
        float md = tokens.space().get("md");
        float lg = tokens.space().get("lg");
        float chip = tokens.size().get("icon.sm") + 2 * tokens.space().get("xxs");
        String[][] tones = { { "neutral", "border.control" }, { "success", "status.success" },
                             { "warning", "status.warning" }, { "danger", "status.danger" },
                             { "accent", "accent.primary" }, { "gamemaster", "status.gamemaster" } };
        for (String[] tone : tones) {
            box(TOKEN_CHIP + tone[0], Color.CLEAR, token(tokens.color(tone[1], mode)), border, radius, null, 0, 0, chip);
            // A chip that acts: its status color washes it under the pointer, and fills it pressed or lit
            int edge = tokens.color(tone[1], mode);
            box(TOKEN_CHIP + tone[0] + "-over", token(ColorMath.withAlpha(edge, tokens.alpha("tint.hover"))),
                  token(edge), border, radius, null, 0, 0, chip);
            box(TOKEN_CHIP + tone[0] + "-lit", token(ColorMath.withAlpha(edge, tokens.alpha("tint.pressed"))),
                  token(edge), border, radius, null, 0, 0, chip);
            Button.ButtonStyle acting = new Button.ButtonStyle();
            acting.up = skin.getDrawable(TOKEN_CHIP + tone[0]);
            acting.over = skin.getDrawable(TOKEN_CHIP + tone[0] + "-over");
            acting.down = skin.getDrawable(TOKEN_CHIP + tone[0] + "-lit");
            acting.checked = acting.down;
            acting.checkedOver = acting.down;
            skin.add(TOKEN_CHIP_BUTTON + tone[0], acting);
        }
        Button.ButtonStyle filter = new Button.ButtonStyle();
        filter.up = skin.getDrawable(TOKEN_CHIP + "neutral");
        filter.over = skin.getDrawable(TOKEN_CHIP + "neutral-over");
        filter.down = skin.getDrawable(TOKEN_CHIP + "accent-lit");
        filter.checked = filter.down;
        filter.checkedOver = filter.down;
        skin.add(TOKEN_FILTER_CHIP, filter);

        // The badge's ring is drawn inside its box, so the box is the badge plus the ring on each side
        float ring = tokens.space().get("xxs");
        float badge = tokens.size().get("icon.sm") + 2 * ring;
        box(TOKEN_BADGE, token(tokens.color("accent.primary", mode)), token(tokens.color("surface.panel", mode)), ring,
              badge / 2, null, 0, xs + ring, badge).setMinWidth(badge);
        float dot = tokens.space().get("md");
        box("token-dot", token(tokens.color("accent.primary", mode)), null, 0, dot / 2, null, 0, 0, dot)
              .setMinWidth(dot);

        float track = tokens.space().get("xs");
        box("token-track", token(tokens.color("border.control", mode)), null, 0, track / 2, null, 0, 0, track)
              .setMinWidth(track);
        box("token-bar", Color.WHITE, null, 0, track / 2, null, 0, 0, track).setMinWidth(track);

        Color popover = token(tokens.color("surface.popover", mode));
        Color subtle = token(tokens.color("border.subtle", mode));
        String[][] levels = {{"info", "status.info"}, {"success", "status.success"}, {"warning", "status.warning"},
                             {"error", "status.danger"}, {"gamemaster", "status.gamemaster"}};
        for (String[] level : levels) {
            // The bar is 3 wide inside the edge; the close button sits xs from the trailing edge
            NinePatchDrawable card = box(TOKEN_TOAST + level[0], popover, subtle, border, radius,
                  token(tokens.color(level[1], mode)), md, 0, 0);
            card.setLeftWidth(border + 3 + lg);
            card.setRightWidth(xs);
        }
    }

    /** Pads a row drawable {@code leading} units before its content and {@code trailing} after; control height. */
    private static Drawable rowPadding(Drawable drawable, float leading, float trailing, float height) {
        drawable.setLeftWidth(leading);
        drawable.setRightWidth(trailing);
        drawable.setTopHeight(0);
        drawable.setBottomHeight(0);
        drawable.setMinHeight(height);
        drawable.setMinWidth(0);
        return drawable;
    }

    /**
     * A scroll bar's pill thumb, {@code width} wide and at least {@code length} long, {@code inset} from the bar's
     * trailing side. The scroll pane puts a thumb against that side, so the drawable is wider than the pill by the inset.
     */
    private Drawable scrollThumb(String name, Color color, float width, float length, float inset) {
        NinePatchDrawable pill = box(name + "-pill", color, null, 0, width / 2, null, 0, 0, 0);
        Drawable thumb = new BaseDrawable() {
            @Override
            public void draw(Batch batch, float x, float y, float drawnWidth, float height) {
                pill.draw(batch, x, y, Math.max(0, drawnWidth - inset), height);
            }
        };
        thumb.setMinWidth(width + inset);
        thumb.setMinHeight(length);
        skin.add(name, thumb, Drawable.class);
        return thumb;
    }

    /**
     * The scroll bar's strip, {@code bar} wide plus a {@code gap} on the content's side; {@code look} (null: nothing)
     * fills only the bar, so the gap stays clear between the content and the bar.
     */
    private static Drawable scrollStrip(Drawable look, float bar, float gap) {
        Drawable strip = new BaseDrawable() {
            @Override
            public void draw(Batch batch, float x, float y, float width, float height) {
                if (look != null) {
                    look.draw(batch, x + width - bar, y, bar, height);
                }
            }
        };
        strip.setMinWidth(bar + gap);
        return strip;
    }

    /** A divider line {@code thickness} thick across the middle of a grab band {@code band} high (or wide). */
    private static Drawable splitLine(Drawable line, float thickness, float band) {
        Drawable divider = new BaseDrawable() {
            @Override
            public void draw(Batch batch, float x, float y, float width, float height) {
                if (width >= height) {
                    line.draw(batch, x, y + Math.round((height - thickness) / 2), width, thickness);
                } else {
                    line.draw(batch, x + Math.round((width - thickness) / 2), y, thickness, height);
                }
            }
        };
        divider.setMinHeight(band);
        divider.setMinWidth(band);
        return divider;
    }

    /**
     * Registers a rounded box as a nine-patch: fill, border (none when null), optional 3-unit left bar; sizes in
     * stage units. It is rasterised with analytic coverage at twice the stage density and drawn at half scale. A
     * radius of half the minimum height gives a disc or a pill that can be drawn at that height.
     */
    public NinePatchDrawable box(String name, Color fill, Color border, float borderWidth, float radius, Color bar,
          float vertical, float horizontal, float minHeight) {
        Color edge = border == null ? fill : border;
        int round = Math.round(radius * 2);
        int line = border == null ? 0 : Math.round(borderWidth * 2);
        int barEnd = bar == null ? 0 : line + 6;
        // Corners hold the rounding and the bar plus two plain texels, so filtering the stretched middle never
        // picks them up; a disc or a pill is all corner, so it keeps its diameter.
        int split = radius * 2 == minHeight ? round : Math.max(round, Math.max(line, barEnd)) + 2;
        int size = 2 * split + 4;
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        Color pixel = new Color();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float outer = coverage(x, y, size, 0, round);
                float inner = coverage(x, y, size, line, Math.max(round - line, 0));
                pixel.set(0, 0, 0, 0);
                over(pixel, fill, inner);
                if (x < barEnd) {
                    over(pixel, bar, inner);
                }
                over(pixel, edge, outer - inner);
                // Straight alpha; empty texels take the edge color, so filtering adds no dark fringe.
                if (pixel.a > 0) {
                    pixel.set(pixel.r / pixel.a, pixel.g / pixel.a, pixel.b / pixel.a, pixel.a);
                } else {
                    pixel.set(edge.r, edge.g, edge.b, 0);
                }
                pixels.drawPixel(x, y, Color.rgba8888(pixel));
            }
        }
        Texture texture = new Texture(pixels);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        add(name + "-texture", texture, Texture.class);
        NinePatch patch = new NinePatch(texture, split, split, split, split);
        patch.scale(.5f, .5f);
        NinePatchDrawable drawable = new NinePatchDrawable(patch);
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        drawable.setMinWidth(0);
        drawable.setMinHeight(minHeight);
        return add(name, drawable, Drawable.class);
    }

    /** Coverage of texel (x, y) by a rounded square inset in a size-texel box, from its signed distance. */
    private static float coverage(int x, int y, int size, float inset, float radius) {
        return MathUtils.clamp(.5f - distance(x, y, size, inset, radius), 0, 1);
    }

    /** The signed distance in texels (negative inside) of texel (x, y)'s centre from a rounded square's edge. */
    private static float distance(int x, int y, int size, float inset, float radius) {
        float half = size / 2f;
        float edge = half - inset - radius;
        float qx = Math.abs(x + .5f - half) - edge;
        float qy = Math.abs(y + .5f - half) - edge;
        return (float) Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /**
     * Registers a soft shadow (CSS box-shadow's blur) of a rounded box as a nine-patch: black whose alpha eases from
     * {@code alpha} to none across {@code blur} units on each side of the box's edge, rasterised at twice the stage
     * density. Its padding is the blur: drawn that much larger than the box on every side, the falloff centres on the
     * box's edge. Its minimum size is the smallest it draws without overlapping its corners.
     */
    private void shadow(String name, float blur, float radius, float alpha) {
        int soft = Math.round(blur * 2);
        int round = Math.round(radius * 2);
        // Corners hold the falloff outside and inside the edge and the rounding, so the stretched middle is uniform.
        int split = 2 * soft + round + 2;
        int size = 2 * split + 4;
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float across = MathUtils.clamp((distance(x, y, size, soft, round) + soft) / (2 * soft), 0, 1);
                pixels.drawPixel(x, y, Color.rgba8888(0, 0, 0, alpha * (1 - across * across * (3 - 2 * across))));
            }
        }
        Texture texture = new Texture(pixels);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        add(name + "-texture", texture, Texture.class);
        NinePatch patch = new NinePatch(texture, split, split, split, split);
        patch.scale(.5f, .5f);
        add(name, pad(new NinePatchDrawable(patch), blur, blur), Drawable.class);
    }

    /** Premultiplied source-over of a straight-alpha color at the given coverage. */
    private static void over(Color premultiplied, Color color, float coverage) {
        float alpha = color.a * coverage;
        premultiplied.r = color.r * alpha + premultiplied.r * (1 - alpha);
        premultiplied.g = color.g * alpha + premultiplied.g * (1 - alpha);
        premultiplied.b = color.b * alpha + premultiplied.b * (1 - alpha);
        premultiplied.a = alpha + premultiplied.a * (1 - alpha);
    }

    /** A copy of the registered nine-patch {@code name} with other padding and minimum height. */
    public NinePatchDrawable resized(String name, float vertical, float horizontal, float minHeight) {
        NinePatchDrawable drawable = (NinePatchDrawable) skin.newDrawable(name);
        drawable.setTopHeight(vertical);
        drawable.setBottomHeight(vertical);
        drawable.setLeftWidth(horizontal);
        drawable.setRightWidth(horizontal);
        drawable.setMinHeight(minHeight);
        return drawable;
    }

    /**
     * A quiet button: ink2 label, white on hover, filled when checked, dim when disabled. Checked buttons keep their
     * fill and contrasting text when hovered or held.
     */
    private TextButton.TextButtonStyle quietButton(String font, float vertical, float horizontal, float minHeight) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = skin.getFont(font);
        style.up = resized("button", vertical, horizontal, minHeight);
        style.over = resized("button-over", vertical, horizontal, minHeight);
        style.down = style.over;
        style.checked = resized("button-checked", vertical, horizontal, minHeight);
        style.checkedOver = style.checked;
        style.checkedDown = style.checked;
        style.disabled = resized("button-disabled", vertical, horizontal, minHeight);
        style.fontColor = ACCENT;
        style.overFontColor = Color.WHITE;
        style.checkedFontColor = FILL_INK;
        style.checkedOverFontColor = FILL_INK;
        style.checkedDownFontColor = FILL_INK;
        style.disabledFontColor = DISABLED;
        return style;
    }

    /** The prototype's thin list scroll bar: a #4a5553 knob on a transparent track. */
    public ScrollPane.ScrollPaneStyle scrollStyle(float width, float knobHeight) {
        ScrollPane.ScrollPaneStyle style = new ScrollPane.ScrollPaneStyle();
        style.vScroll = skin.newDrawable("white", Color.CLEAR);
        style.vScrollKnob = skin.newDrawable("white", Color.valueOf("4A5553"));
        style.vScroll.setMinWidth(width);
        style.vScrollKnob.setMinWidth(width);
        style.vScrollKnob.setMinHeight(knobHeight);
        style.hScroll = skin.newDrawable("white", alpha(QUIET, .3f));
        style.hScrollKnob = skin.newDrawable("white", Color.valueOf("738581"));
        style.hScroll.setMinHeight(width);
        style.hScrollKnob.setMinHeight(width);
        style.hScrollKnob.setMinWidth(knobHeight);
        return style;
    }

    /** Bakes the Material Symbols glyphs once into one mipmapped sheet of em-square cells, then frees the face. */
    private void icons() {
        List<String> names = ICONS.keySet().stream().sorted().toList();
        int columns = 8;
        Pixmap sheet = new Pixmap(columns * ICON_CELL, (names.size() + columns - 1) / columns * ICON_CELL,
              Pixmap.Format.RGBA8888);
        sheet.setBlending(Pixmap.Blending.None);
        // Transparent white, so the mipmaps average coverage only.
        sheet.setColor(1, 1, 1, 0);
        sheet.fill();
        int margin = (ICON_CELL - ICON_SIZE) / 2;
        FreeType.Library library = FreeType.initFreeType();
        try {
            FreeType.Face face = library.newFace(fontFile(fonts, ICON_FONT), 0);
            try {
                face.setPixelSizes(0, ICON_SIZE);
                for (int i = 0; i < names.size(); i++) {
                    int codepoint = ICONS.get(names.get(i));
                    if (face.getCharIndex(codepoint) == 0 || !face.loadChar(codepoint, FreeType.FT_LOAD_DEFAULT)
                          || !face.getGlyph().renderGlyph(FreeType.FT_RENDER_MODE_NORMAL)) {
                        throw new IllegalStateException("The icon font has no glyph for icon-" + names.get(i));
                    }
                    FreeType.GlyphSlot slot = face.getGlyph();
                    Pixmap glyph = slot.getBitmap().getPixmap(Pixmap.Format.RGBA8888, Color.WHITE, 1);
                    if (FILLED_ICONS.contains(names.get(i))) {
                        closeOutline(glyph);
                    }
                    // Icons are drawn on the em square above the baseline; keep that box and its margins.
                    sheet.drawPixmap(glyph, i % columns * ICON_CELL + margin + slot.getBitmapLeft(),
                          i / columns * ICON_CELL + margin + ICON_SIZE - slot.getBitmapTop());
                    glyph.dispose();
                }
            } finally {
                face.dispose();
            }
        } finally {
            library.dispose();
        }
        Texture texture = new Texture(sheet, true);
        sheet.dispose();
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        skin.add("icon-texture", texture);
        for (int i = 0; i < names.size(); i++) {
            TextureRegion region = new TextureRegion(texture, i % columns * ICON_CELL + margin,
                  i / columns * ICON_CELL + margin, ICON_SIZE, ICON_SIZE);
            skin.add("icon-" + names.get(i), new TextureRegionDrawable(region), Drawable.class);
        }
    }

    /**
     * Adds a symbol image as icon-{@code name}, for a view's own symbol that the icon font lacks. The image's alpha is
     * the symbol and its colour is ignored, so a black or a white image will do. Its longer side spans the font icons'
     * live area, centred, so it lines up, scales and tints as they do. Call it on the GL thread; the theme owns the
     * icon's mipmapped texture and the caller keeps the image.
     */
    public void addIcon(String name, Pixmap image) {
        // Checked before the texture exists, which a refused name would leave unowned.
        if (skin.has("icon-" + name, Drawable.class)) {
            throw new IllegalStateException("The skin already has the icon-" + name);
        }
        Pixmap cell = iconCell(image);
        Texture texture = add("icon-" + name + "-texture", new Texture(cell, true), Texture.class);
        cell.dispose();
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        int margin = (ICON_CELL - ICON_SIZE) / 2;
        add("icon-" + name, new TextureRegionDrawable(new TextureRegion(texture, margin, margin, ICON_SIZE,
              ICON_SIZE)), Drawable.class);
    }

    /**
     * A symbol image as a sheet cell: white, its coverage the image's alpha box-filtered so that the image's longer
     * side spans the live area.
     */
    private static Pixmap iconCell(Pixmap image) {
        int width = image.getWidth();
        int height = image.getHeight();
        // Image texels per cell texel, and the cell's corner in image texels.
        float step = Math.max(width, height) / (float) ICON_LIVE;
        float left = (width - ICON_CELL * step) / 2;
        float top = (height - ICON_CELL * step) / 2;
        Pixmap cell = new Pixmap(ICON_CELL, ICON_CELL, Pixmap.Format.RGBA8888);
        cell.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < ICON_CELL; y++) {
            float top0 = top + y * step;
            for (int x = 0; x < ICON_CELL; x++) {
                float left0 = left + x * step;
                // The image texels under this cell texel, each weighted by the area they share.
                float alpha = 0;
                for (int row = Math.max(0, (int) top0); row < Math.min(height, top0 + step); row++) {
                    float rowShare = Math.min(top0 + step, row + 1) - Math.max(top0, row);
                    for (int column = Math.max(0, (int) left0); column < Math.min(width, left0 + step); column++) {
                        float share = rowShare * (Math.min(left0 + step, column + 1) - Math.max(left0, column));
                        alpha += share * (image.getPixel(column, row) & 0xFF);
                    }
                }
                // Transparent white around the symbol, as in the font sheet, so the mipmaps average coverage only.
                cell.drawPixel(x, y, Color.rgba8888(1, 1, 1, alpha / (255 * step * step)));
            }
        }
        return cell;
    }

    /**
     * Fills a white glyph's enclosed interior: texels that no path of mostly transparent texels (4-connected) joins to
     * the bitmap's border become opaque, except the outline's outer edge, which keeps its antialiasing.
     */
    private static void closeOutline(Pixmap glyph) {
        int width = glyph.getWidth();
        int height = glyph.getHeight();
        boolean[] outside = new boolean[width * height];
        ArrayDeque<Integer> open = new ArrayDeque<>();
        for (int x = 0; x < width; x++) {
            reach(glyph, outside, open, x, 0);
            reach(glyph, outside, open, x, height - 1);
        }
        for (int y = 0; y < height; y++) {
            reach(glyph, outside, open, 0, y);
            reach(glyph, outside, open, width - 1, y);
        }
        while (!open.isEmpty()) {
            int index = open.poll();
            int x = index % width;
            int y = index / width;
            reach(glyph, outside, open, x - 1, y);
            reach(glyph, outside, open, x + 1, y);
            reach(glyph, outside, open, x, y - 1);
            reach(glyph, outside, open, x, y + 1);
        }
        glyph.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!outside(outside, width, height, x, y) && !outside(outside, width, height, x - 1, y)
                      && !outside(outside, width, height, x + 1, y) && !outside(outside, width, height, x, y - 1)
                      && !outside(outside, width, height, x, y + 1)) {
                    glyph.drawPixel(x, y, 0xFFFFFFFF);
                }
            }
        }
    }

    private static void reach(Pixmap glyph, boolean[] outside, ArrayDeque<Integer> open, int x, int y) {
        int index = y * glyph.getWidth() + x;
        if (x >= 0 && y >= 0 && x < glyph.getWidth() && y < glyph.getHeight() && !outside[index]
              && (glyph.getPixel(x, y) & 0xFF) < 128) {
            outside[index] = true;
            open.add(index);
        }
    }

    /** Texels beyond the bitmap count as outside. */
    private static boolean outside(boolean[] outside, int width, int height, int x, int y) {
        return x < 0 || y < 0 || x >= width || y >= height || outside[y * width + x];
    }

    /**
     * A font for a view's own styles in this look, registered as {@code name}: the face at {@code path} in the fonts
     * directory at {@code size} stage units, on pages of its own, with the theme's fallback faces.
     */
    public BitmapFont font(String name, String path, float size) {
        return font(name, path, 0, size, 0, 0, null);
    }

    /**
     * {@link #font(String, String, float)} with FreeType's round white border {@code border} stage units wide around
     * every glyph, for drawing tinted under the borderless font as its outline. Its glyphs advance as the borderless
     * font's do, but a line's first glyph starts at its border, {@code border} to the left of the borderless ink.
     */
    public BitmapFont font(String name, String path, float size, float border) {
        return font(name, path, 0, size, 0, border, null);
    }

    /**
     * The faces of {@link UiRichText} body text at {@code size} stage units in {@code color}, with links in
     * {@code link}: Roboto regular, bold, italic and bold italic, and bold headings a step up the scale. They are made
     * on first use and kept for the theme's life, so every view asking for the same size and colors shares them.
     */
    public UiRichText.Style richText(float size, Color color, Color link) {
        String prefix = String.format(Locale.ROOT, "rich-%.1f-", size);
        String key = prefix + color + "-" + link;
        if (skin.has(key, UiRichText.Style.class)) {
            return skin.get(key, UiRichText.Style.class);
        }
        BitmapFont[] headings = new BitmapFont[UiRichText.Style.HEADING_SCALES.length];
        for (int i = 0; i < headings.length; i++) {
            headings[i] = richFont(prefix + "h" + (i + 1), ROBOTO, BOLD, size * UiRichText.Style.HEADING_SCALES[i]);
        }
        UiRichText.Style style = new UiRichText.Style(richFont(prefix + "regular", ROBOTO, REGULAR, size),
              richFont(prefix + "bold", ROBOTO, BOLD, size), richFont(prefix + "italic", ROBOTO_ITALIC, REGULAR, size),
              richFont(prefix + "bold-italic", ROBOTO_ITALIC, BOLD, size), headings, size, new Color(color),
              new Color(link), new TextureRegion(skin.get("white", Texture.class)));
        return add(key, style, UiRichText.Style.class);
    }

    private BitmapFont richFont(String name, String path, int instance, float size) {
        return skin.has(name, BitmapFont.class) ? skin.getFont(name) : font(name, path, instance, size, 0, 0, null);
    }

    /** A board label font, with an optional outline, on padded, mipmapped and anisotropically filtered pages. */
    public BitmapFont worldFont(String name, String path, float size, float border) {
        return font(name, path, 0, size, 0, border, worldGlyphs);
    }

    /**
     * Rasterised at 4x and drawn at a quarter, so fractional CSS sizes such as 10.5 work; tracking is in em. Characters
     * the face lacks come from the FALLBACK_FONTS chain. The hud fonts (those on the shared pages) lost thin strokes,
     * so "AS7-D" read "AS7 D": a glyph quad thinner than a pixel, such as a 4-texel hyphen drawn at 10 units, covers no
     * pixel centre at some sub-pixel positions, and linear filtering minified 4:1 reads only two of every four texel
     * rows. Their glyphs are therefore mipmapped, which marks them for Generator: their quads take in GLYPH_PAD
     * texels of the packer's transparent margin, and their pages are sampled from mip level 0 or 1 (see
     * bindGlyphPage), whichever is nearer.
     */
    private BitmapFont font(String name, String path, int instance, float size, float tracking, float border,
          PixmapPacker packer) {
        boolean world = packer == worldGlyphs;
        FreeTypeFontGenerator generator = new Generator(fontFile(fonts, path), instance, 1, fallback, world);
        generators.add(generator);
        FreeTypeFontGenerator.FreeTypeFontParameter settings = new FreeTypeFontGenerator.FreeTypeFontParameter();
        settings.size = Math.round(size * 4);
        settings.borderWidth = Math.round(border * 4);
        settings.borderColor = Color.WHITE;
        settings.borderStraight = false;
        // FreeType widens every glyph's advance by the border; take it back so the text keeps its width.
        settings.spaceX = Math.round(tracking * size * 4) - (int) settings.borderWidth;
        settings.packer = packer;
        settings.incremental = true;
        settings.minFilter = Texture.TextureFilter.Linear;
        settings.magFilter = Texture.TextureFilter.Linear;
        boolean mipmapped = packer != null;
        if (mipmapped) {
            settings.genMipMaps = true;
            settings.minFilter = world ? Texture.TextureFilter.MipMapLinearLinear
                  : Texture.TextureFilter.MipMapLinearNearest;
        }
        BitmapFont font = generator.generateFont(settings);
        if (mipmapped) {
            // GlyphLayout subtracts these from a line's first and last glyph, so lines measure their ink as before.
            font.getData().padLeft = world ? WORLD_GLYPH_PAD : GLYPH_PAD;
            font.getData().padRight = font.getData().padLeft;
        }
        if (world) {
            worldGlyphs.getPages().forEach(page -> bindGlyphPage(page.getTexture(), true));
        }
        font.getData().setScale(0.25f);
        font.setUseIntegerPositions(false);
        return add(name, font, BitmapFont.class);
    }

    @Override
    public void dispose() {
        skin.dispose();
        // The shared glyph pages belong to the theme, not to the fonts packed into them.
        for (PixmapPacker packer : List.of(hudGlyphs, worldGlyphs)) {
            for (PixmapPacker.Page page : packer.getPages()) {
                if (page.getTexture() != null) {
                    page.getTexture().dispose();
                }
            }
            packer.dispose();
        }
        generators.forEach(FreeTypeFontGenerator::dispose);
    }

    /**
     * A generator that takes each character its face lacks from the first face of its fallback chain that has it. That
     * face renders it at its share of this font's size, on this font's baseline and into this font's pages, so one
     * BitmapFont draws the whole text. A mipmapped glyph's quad grows into its margin with its ink kept in place,
     * and a glyph written straight into a mipmapped page texture (incremental glyphs after the first layout) rebuilds
     * that page's mipmaps.
     */
    private static final class Generator extends FreeTypeFontGenerator {
        private final float size;
        private final Generator fallback;
        private final boolean world;

        /** size is the face's share of the size of the fonts it serves as a fallback. */
        Generator(FileHandle file, int instance, float size, Generator fallback) {
            this(file, instance, size, fallback, false);
        }

        Generator(FileHandle file, int instance, float size, Generator fallback, boolean world) {
            // FreeType takes the named instance from bits 16-30 of the face index.
            super(file, instance << 16);
            this.size = size;
            this.fallback = fallback;
            this.world = world;
        }

        @Override
        protected BitmapFont.Glyph createGlyph(char c, FreeTypeBitmapFontData data, FreeTypeFontParameter parameter,
              FreeType.Stroker stroker, float baseLine, PixmapPacker packer) {
            BitmapFont.Glyph glyph = createUnpaddedGlyph(c, data, parameter, stroker, baseLine, packer);
            if (glyph == null || !parameter.genMipMaps) {
                return glyph;
            }
            // The requesting font owns the margins and filtering, including for a borrowed fallback glyph.
            int pad = world ? WORLD_GLYPH_PAD : GLYPH_PAD;
            if (glyph.width > 0 && glyph.height > 0) {
                glyph.srcX -= pad;
                glyph.srcY -= pad;
                glyph.width += 2 * pad;
                glyph.height += 2 * pad;
                glyph.yoffset -= pad;
            } else {
                // An inkless glyph, such as the space, spans its advance, as libGDX makes the space's width.
                glyph.width = glyph.xadvance + 2 * pad;
            }
            glyph.xoffset -= pad;
            Texture page = packer.getPages().get(glyph.page).getTexture();
            if (page != null) {
                bindGlyphPage(page, world);
                if (packer.getPackToTexture()) { Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D); }
            }
            return glyph;
        }

        private BitmapFont.Glyph createUnpaddedGlyph(char c, FreeTypeBitmapFontData data, FreeTypeFontParameter parameter,
              FreeType.Stroker stroker, float baseLine, PixmapPacker packer) {
            if (c != 0 && !hasGlyph(c)) {
                for (Generator next = fallback; next != null; next = next.fallback) {
                    if (next.hasGlyph(c)) {
                        // A fallback face serves fonts of every size; scaleForPixelHeight is the public way to size it.
                        next.scaleForPixelHeight(Math.round(parameter.size * next.size));
                        return next.createUnpaddedGlyph(c, data, parameter, stroker, baseLine, packer);
                    }
                }
                // No shipped face has it: its lookalike, drawn under its own code.
                Character lookalike = LOOKALIKES.get(c);
                BitmapFont.Glyph glyph = lookalike == null ? null
                      : createUnpaddedGlyph(lookalike, data, parameter, stroker, baseLine, packer);
                if (glyph != null) {
                    glyph.id = c;
                }
                return glyph;
            }
            return super.createGlyph(c, data, parameter, stroker, baseLine, packer);
        }
    }

    /**
     * HUD pages stop at mip 1 to retain thin strokes at 1 pixel per stage unit. World pages use trilinear/anisotropic
     * sampling through mip 3; deeper levels would average unrelated glyphs together across even their wider margins.
     */
    private static void bindGlyphPage(Texture page, boolean world) {
        page.bind();
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, world ? 3 : 1);
        if (world) { page.setAnisotropicFilter(8); }
    }

    /** Physical pixels per stage unit, from the orthographic projection the stage draws with. */
    public static float pixelScale(Batch batch) {
        return batch.getProjectionMatrix().val[Matrix4.M00] * Gdx.graphics.getBackBufferWidth() / 2;
    }

    /** {@code value} in stage units, rounded to whole pixels at {@code scale} pixels per unit. */
    public static float snap(float value, float scale) {
        return Math.round(value * scale) / scale;
    }

    /** A frame: flat fill, optional 2-unit rails top and bottom, corner strokes or an outline, pixel-snapped. */
    public static final class HudFrame extends BaseDrawable {
        private static final float STROKE = 2;
        private final Texture white;
        private final Color fill;
        private final Color rail;
        private final Color corner;
        private final float cornerLength;
        private final Color outline;
        private final float edge;
        private boolean sides = true;
        private final Color tint = new Color();

        /** The fill, then rails, an outline and corner strokes of {@code cornerLength} units; each may be null. */
        public HudFrame(Texture white, Color fill, Color rail, Color corner, float cornerLength, Color outline) {
            this(white, fill, rail, corner, cornerLength, outline, STROKE);
        }

        /** A frame as above whose outline is {@code edge} units wide; rails and corner strokes stay 2 units. */
        public HudFrame(Texture white, Color fill, Color rail, Color corner, float cornerLength, Color outline,
              float edge) {
            this.white = white;
            this.fill = fill;
            this.rail = rail;
            this.corner = corner;
            this.cornerLength = cornerLength;
            this.outline = outline;
            this.edge = edge;
        }

        /** Makes this a paper roll: the outline runs along the top and bottom only, and the corner strokes close it. */
        public HudFrame paperRoll() {
            sides = false;
            return this;
        }

        @Override
        public void draw(Batch batch, float x, float y, float width, float height) {
            float scale = pixelScale(batch);
            float left = snap(x, scale);
            float bottom = snap(y, scale);
            float right = snap(x + width, scale);
            float top = snap(y + height, scale);
            float line = Math.max(1, Math.round(STROKE * scale)) / scale;
            float previous = batch.getPackedColor();
            tint.set(batch.getColor());
            rect(batch, fill, left, bottom, right - left, top - bottom);
            if (rail != null) {
                rect(batch, rail, left, top - line, right - left, line);
                rect(batch, rail, left, bottom, right - left, line);
            }
            if (outline != null) {
                float stroke = Math.max(1, Math.round(edge * scale)) / scale;
                rect(batch, outline, left, top - stroke, right - left, stroke);
                rect(batch, outline, left, bottom, right - left, stroke);
                if (sides) {
                    rect(batch, outline, left, bottom + stroke, stroke, top - bottom - 2 * stroke);
                    rect(batch, outline, right - stroke, bottom + stroke, stroke, top - bottom - 2 * stroke);
                }
            }
            if (corner != null) {
                float length = Math.min(snap(cornerLength, scale), Math.min(right - left, top - bottom) / 2);
                rect(batch, corner, left, bottom, length, line);
                rect(batch, corner, left, bottom, line, length);
                rect(batch, corner, right - length, bottom, length, line);
                rect(batch, corner, right - line, bottom, line, length);
                rect(batch, corner, left, top - line, length, line);
                rect(batch, corner, left, top - length, line, length);
                rect(batch, corner, right - length, top - line, length, line);
                rect(batch, corner, right - line, top - length, line, length);
            }
            batch.setPackedColor(previous);
        }

        private void rect(Batch batch, Color color, float x, float y, float width, float height) {
            batch.setColor(color.r * tint.r, color.g * tint.g, color.b * tint.b, color.a * tint.a);
            batch.draw(white, x, y, width, height);
        }
    }

    /**
     * A pixel-snapped line along the bottom edge, or a box outline when {@code box}; solid when {@code dash} is 0,
     * otherwise dashes of {@code dash} units with {@code gap} units between them (CSS dashed and dotted borders).
     */
    public static final class Dashes extends BaseDrawable {
        private final Texture white;
        private final Color color;
        private final float thickness;
        private final float dash;
        private final float gap;
        private final boolean box;

        /** A line or outline {@code thickness} units wide in {@code color}, drawn from the {@code white} texel. */
        public Dashes(Texture white, Color color, float thickness, float dash, float gap, boolean box) {
            this.white = white;
            this.color = color;
            this.thickness = thickness;
            this.dash = dash;
            this.gap = gap;
            this.box = box;
        }

        @Override
        public void draw(Batch batch, float x, float y, float width, float height) {
            float scale = pixelScale(batch);
            float left = snap(x, scale);
            float bottom = snap(y, scale);
            float right = snap(x + width, scale);
            float top = snap(y + height, scale);
            float line = Math.max(1, Math.round(thickness * scale)) / scale;
            float previous = batch.getPackedColor();
            Color tint = batch.getColor();
            batch.setColor(color.r * tint.r, color.g * tint.g, color.b * tint.b, color.a * tint.a);
            horizontal(batch, left, right, bottom, line);
            if (box) {
                horizontal(batch, left, right, top - line, line);
                vertical(batch, bottom + line, top - line, left, line);
                vertical(batch, bottom + line, top - line, right - line, line);
            }
            batch.setPackedColor(previous);
        }

        private void horizontal(Batch batch, float from, float to, float y, float line) {
            float step = dash > 0 ? dash + gap : to - from;
            for (float x = from; x < to; x += step) {
                batch.draw(white, x, y, Math.min(dash > 0 ? dash : step, to - x), line);
            }
        }

        private void vertical(Batch batch, float from, float to, float x, float line) {
            float step = dash > 0 ? dash + gap : to - from;
            for (float y = from; y < to; y += step) {
                batch.draw(white, x, y, line, Math.min(dash > 0 ? dash : step, to - y));
            }
        }
    }

    /**
     * A flat fill (none when null) under edges of one color, drawn from the white texel at the batch color's alpha
     * and not pixel-snapped. Each side's edge is as wide as given, in the order of CSS border widths (top, right,
     * bottom, left; 0 for none), solid or {@link #dashed}; the top and bottom edges span the box and the side edges
     * run between them. Views draw their tags, badges and cards with it.
     */
    public static final class EdgeBox extends BaseDrawable {
        private final Texture white;
        private final Color fill;
        private final Color edge;
        private final float top;
        private final float right;
        private final float bottom;
        private final float left;
        private float dash;

        /** The fill and edges of the given CSS border widths, drawn from the {@code white} texel. */
        public EdgeBox(Texture white, Color fill, Color edge, float top, float right, float bottom, float left) {
            this.white = white;
            this.fill = fill;
            this.edge = edge;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.left = left;
        }

        /** Dashes of {@code length} units with gaps as long instead of solid edges (a CSS dashed border). */
        public EdgeBox dashed(float length) {
            dash = length;
            return this;
        }

        @Override
        public void draw(Batch batch, float x, float y, float width, float height) {
            float alpha = batch.getColor().a;
            float previous = batch.getPackedColor();
            if (fill != null) {
                batch.setColor(fill.r, fill.g, fill.b, fill.a * alpha);
                batch.draw(white, x, y, width, height);
            }
            batch.setColor(edge.r, edge.g, edge.b, edge.a * alpha);
            float step = dash > 0 ? 2 * dash : width;
            for (float along = 0; along < width; along += step) {
                float length = Math.min(dash > 0 ? dash : width, width - along);
                if (bottom > 0) {
                    batch.draw(white, x + along, y, length, bottom);
                }
                if (top > 0) {
                    batch.draw(white, x + along, y + height - top, length, top);
                }
            }
            float side = height - bottom - top;
            step = dash > 0 ? 2 * dash : side;
            for (float along = bottom; along < height - top; along += step) {
                float length = Math.min(dash > 0 ? dash : side, height - top - along);
                if (left > 0) {
                    batch.draw(white, x, y + along, left, length);
                }
                if (right > 0) {
                    batch.draw(white, x + width - right, y + along, right, length);
                }
            }
            batch.setPackedColor(previous);
        }
    }
}
