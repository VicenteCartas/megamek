/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The suite's design tokens, read from {@code design-tokens.json}: the one source of truth that the Swing and libGDX
 * themes are built from. Read-only; colors are packed 0xAARRGGBB. No AWT, so the native toolkit can use it too.
 *
 * <p>An app of the suite extends them with its own roles through an overlay ({@link #addOverlay}) rather than a copy of
 * the file: an overlay adds tokens and checks, and never changes a shipped one.</p>
 */
public final class DesignTokens {
    /** The classpath location of the token file. */
    public static final String RESOURCE = "/megamek/client/ui/design/design-tokens.json";

    /** Token groups an overlay adds named tokens to; a name the file or an earlier overlay has is an error. */
    private static final Set<String> ADDED_TOKENS = Set.of("color", "space", "radius", "size", "opacity", "motion",
          "typography.styles", "typography.case.roles");
    /** Lists an overlay appends to: the upper-case and condensed roles of each presentation, and the checks. */
    private static final Set<String> APPENDED_LISTS = Set.of("typography.case.upper.app", "typography.case.upper.game",
          "typography.width.roles.app", "typography.width.roles.game", "checks.contrast", "checks.distinct");
    /** An overlay's own description, which the merged tokens don't take. */
    private static final Set<String> OVERLAY_NOTES = Set.of("version", "about");

    private static final Map<Palette, DesignTokens> SHIPPED = new EnumMap<>(Palette.class);
    /** Guarded by {@link #SHIPPED}: the apps' overlays by their source, in the order they were added. */
    private static final Map<String, JsonNode> OVERLAYS = new LinkedHashMap<>();
    /** Guarded by {@link #SHIPPED}: the shipped file, read once. */
    private static JsonNode shippedTree;
    private static volatile Palette activePalette = Palette.STANDARD;

    /** The two color modes. Native battle views are dark only for now. */
    public enum Mode {
        DARK,
        LIGHT
    }

    /**
     * The user's color vision setting (docs/design-system/tokens.md, Palettes), independent of the {@link Mode}: the
     * standard palette uses natural status colors and is measured for normal vision; the color-blind safe palette
     * overrides the status colors so they stay apart for every simulated {@link Vision}.
     */
    public enum Palette {
        STANDARD,
        COLORBLIND
    }

    /**
     * A screen's presentation mode (docs/design-system/tokens.md, Presentation mode): whether it is an app or a game
     * screen. It sets the text case (app screens upper-case only overline tags, game screens also the short framing
     * labels), the type width (game screens set the framing labels and names condensed) and the few control shapes
     * that differ, such as the slider's thumb. The mode belongs to a screen, not to an app.
     */
    public enum Presentation {
        APP,
        GAME
    }

    /** A color role with a value per mode; {@code overlay} is the translucent board-overlay value, or null. */
    public record ColorToken(String name, int dark, int light, Integer overlay, String use) {
        public int value(Mode mode) {
            return mode == Mode.DARK ? dark : light;
        }
    }

    /**
     * A text style: size in logical pixels, CSS weight, tracking in em for sentence case, and {@code capsTracking} in
     * em for upper case, or null for a style that is never upper case.
     */
    public record TypeStyle(String name, float size, int weight, float tracking, Float capsTracking, String use) {
        /** Whether a text role may show this style in upper case. */
        public boolean hasCaps() {
            return capsTracking != null;
        }

        /** The tracking for the given case. */
        public float tracking(boolean upperCase) {
            return upperCase ? capsTracking : tracking;
        }
    }

    /** Every listed foreground must reach {@code min} contrast on every listed background, in both modes. */
    public record ContrastCheck(List<String> foregrounds, List<String> backgrounds, String over, double min,
          String why) {
    }

    /** Every pair of the listed colors must differ by at least {@code minDeltaE} for every {@link Vision}. */
    public record DistinctCheck(List<String> colors, double minDeltaE, String why) {
    }

    private final String version;
    private final Map<String, ColorToken> colors;
    private final String family;
    private final String iconFamily;
    private final String caseRule;
    private final Map<String, TypeStyle> typeStyles;
    private final Map<String, String> textRoles;
    private final Map<Presentation, Set<String>> upperRoles;
    private final String widthRule;
    private final int condensedWidth;
    private final Map<Presentation, Set<String>> condensedRoles;
    private final Map<String, Float> space;
    private final Map<String, Float> radius;
    private final Map<String, Float> size;
    private final Map<String, Float> opacity;
    private final Map<String, Float> motion;
    private final float[] easing;
    private final List<ContrastCheck> contrastChecks;
    private final List<DistinctCheck> distinctChecks;
    private final Palette tokenPalette;

    private DesignTokens(JsonNode root, Palette palette) {
        tokenPalette = palette;
        version = root.path("version").asText();
        Map<String, ColorToken> colorMap = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(root.path("color"))) {
            JsonNode node = entry.getValue();
            String name = entry.getKey();
            JsonNode overlay = node.get("overlay");
            JsonNode values = palette == Palette.COLORBLIND && node.has("colorblind") ? node.get("colorblind") : node;
            colorMap.put(name, new ColorToken(name, ColorMath.parse(required(values, "dark", name)),
                  ColorMath.parse(required(values, "light", name)),
                  overlay == null ? null : ColorMath.parse(overlay.asText()), node.path("use").asText()));
        }
        colors = Collections.unmodifiableMap(colorMap);

        JsonNode typography = root.path("typography");
        family = typography.path("family").asText();
        iconFamily = typography.path("iconFamily").asText();
        caseRule = typography.path("caseRule").asText();
        Map<String, TypeStyle> styles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(typography.path("styles"))) {
            JsonNode node = entry.getValue();
            JsonNode caps = node.get("capsTracking");
            styles.put(entry.getKey(), new TypeStyle(entry.getKey(), (float) node.path("size").asDouble(),
                  node.path("weight").asInt(400), (float) node.path("tracking").asDouble(),
                  caps == null ? null : (float) caps.asDouble(), node.path("use").asText()));
        }
        typeStyles = Collections.unmodifiableMap(styles);

        JsonNode textCase = typography.path("case");
        Map<String, String> roles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(textCase.path("roles"))) {
            String style = entry.getValue().asText();
            if (!styles.containsKey(style)) {
                throw new IllegalArgumentException("Text role " + entry.getKey() + " names no text style " + style);
            }
            roles.put(entry.getKey(), style);
        }
        textRoles = Collections.unmodifiableMap(roles);
        Map<Presentation, Set<String>> upper = new EnumMap<>(Presentation.class);
        for (Presentation presentation : Presentation.values()) {
            Set<String> upperInMode = new LinkedHashSet<>();
            for (JsonNode role : textCase.path("upper").path(presentation.name().toLowerCase(Locale.ROOT))) {
                String name = role.asText();
                String style = roles.get(name);
                if (style == null) {
                    throw new IllegalArgumentException("Upper-case role " + name + " is not a text role");
                }
                if (!styles.get(style).hasCaps()) {
                    throw new IllegalArgumentException("Text style " + style + " of upper-case role " + name
                          + " has no capsTracking");
                }
                upperInMode.add(name);
            }
            upper.put(presentation, Collections.unmodifiableSet(upperInMode));
        }
        upperRoles = Collections.unmodifiableMap(upper);

        JsonNode width = typography.path("width");
        widthRule = width.path("rule").asText();
        condensedWidth = width.path("condensed").asInt(100);
        Map<Presentation, Set<String>> condensed = new EnumMap<>(Presentation.class);
        for (Presentation presentation : Presentation.values()) {
            Set<String> condensedInMode = new LinkedHashSet<>();
            for (JsonNode role : width.path("roles").path(presentation.name().toLowerCase(Locale.ROOT))) {
                if (!roles.containsKey(role.asText())) {
                    throw new IllegalArgumentException("Condensed role " + role.asText() + " is not a text role");
                }
                condensedInMode.add(role.asText());
            }
            condensed.put(presentation, Collections.unmodifiableSet(condensedInMode));
        }
        condensedRoles = Collections.unmodifiableMap(condensed);

        space = numbers(root.path("space"));
        radius = numbers(root.path("radius"));
        size = numbers(root.path("size"));
        opacity = numbers(root.path("opacity"));
        motion = numbers(root.path("motion"));
        JsonNode curve = root.path("motion").path("easing");
        easing = new float[] { 0, 0, 1, 1 };
        if (curve.isArray() && curve.size() == 4) {
            for (int i = 0; i < 4; i++) {
                easing[i] = (float) curve.get(i).asDouble();
            }
        }

        List<ContrastCheck> contrast = new ArrayList<>();
        for (JsonNode node : root.path("checks").path("contrast")) {
            contrast.add(new ContrastCheck(strings(node.path("fg")), strings(node.path("bg")),
                  node.hasNonNull("over") ? node.get("over").asText() : null, node.path("min").asDouble(),
                  node.path("why").asText()));
        }
        contrastChecks = List.copyOf(contrast);
        List<DistinctCheck> distinct = new ArrayList<>();
        for (JsonNode node : root.path("checks").path("distinct")) {
            distinct.add(new DistinctCheck(strings(node.path("colors")), node.path("minDeltaE").asDouble(),
                  node.path("why").asText()));
        }
        distinctChecks = List.copyOf(distinct);
    }

    /** The tokens shipped with the application in the active {@link #palette()}, read once per palette. */
    public static DesignTokens shipped() {
        return shipped(activePalette);
    }

    /** The tokens shipped with the application in {@code palette}, with the apps' overlays, read once. */
    public static DesignTokens shipped(Palette palette) {
        synchronized (SHIPPED) {
            DesignTokens tokens = SHIPPED.get(palette);
            if (tokens == null) {
                tokens = new DesignTokens(overlaid(OVERLAYS), palette);
                SHIPPED.put(palette, tokens);
            }
            return tokens;
        }
    }

    /**
     * Extends the shipped tokens with an app's overlay: a token file on {@code owner}'s classpath, at {@code resource},
     * that adds the app's own color roles, text styles and roles, sizes and checks (docs/design-system/tokens.md, App
     * overlays). Adding the same overlay again does nothing. Call it as the app starts, before its first theme is
     * built; a theme built earlier keeps its tokens until it is built again, as after {@link #setPalette}.
     *
     * @throws IllegalArgumentException when the overlay changes a shipped token or one of an earlier overlay, or
     *                                  sets anything an overlay may not
     * @throws IllegalStateException    when the resource is missing
     * @throws UncheckedIOException     when it can't be read
     */
    public static void addOverlay(Class<?> owner, String resource) {
        String source = owner.getName() + ":" + resource;
        JsonNode overlay;
        try (InputStream stream = owner.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing design token overlay " + source);
            }
            overlay = new ObjectMapper().readTree(stream);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        synchronized (SHIPPED) {
            if (OVERLAYS.containsKey(source)) {
                return;
            }
            Map<String, JsonNode> overlays = new LinkedHashMap<>(OVERLAYS);
            overlays.put(source, overlay);
            // Fails here, as the app starts, rather than at the first theme
            new DesignTokens(overlaid(overlays), Palette.STANDARD);
            OVERLAYS.put(source, overlay);
            SHIPPED.clear();
        }
    }

    /** Under {@link #SHIPPED}: the shipped file with {@code overlays} merged in, in order. */
    private static JsonNode overlaid(Map<String, JsonNode> overlays) {
        if (shippedTree == null) {
            try (InputStream stream = DesignTokens.class.getResourceAsStream(RESOURCE)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing design token resource " + RESOURCE);
                }
                shippedTree = new ObjectMapper().readTree(stream);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        JsonNode merged = shippedTree;
        for (Map.Entry<String, JsonNode> overlay : overlays.entrySet()) {
            merged = overlaid(merged, overlay.getValue(), overlay.getKey());
        }
        return merged;
    }

    /**
     * {@code base} with {@code overlay} merged in, leaving both as they were: the overlay's tokens added to their
     * groups and its lists appended. {@code source} names the overlay in errors.
     *
     * @throws IllegalArgumentException when the overlay changes a token {@code base} has, or sets anything else
     */
    static JsonNode overlaid(JsonNode base, JsonNode overlay, String source) {
        if (!overlay.isObject()) {
            throw new IllegalArgumentException("Design token overlay " + source + " is not a JSON object");
        }
        ObjectNode merged = base.deepCopy();
        merge(merged, overlay, "", source);
        return merged;
    }

    private static void merge(ObjectNode target, JsonNode overlay, String path, String source) {
        for (Map.Entry<String, JsonNode> entry : fields(overlay)) {
            String key = entry.getKey();
            String at = path.isEmpty() ? key : path + "." + key;
            JsonNode value = entry.getValue();
            if (path.isEmpty() && OVERLAY_NOTES.contains(key)) {
                continue;
            }
            JsonNode existing = target.get(key);
            if (ADDED_TOKENS.contains(at) && value.isObject()) {
                ObjectNode group = existing instanceof ObjectNode object ? object : target.putObject(key);
                for (Map.Entry<String, JsonNode> token : fields(value)) {
                    if (group.has(token.getKey())) {
                        throw new IllegalArgumentException("Design token overlay " + source + " redefines " + at + "."
                              + token.getKey() + "; an overlay only adds tokens");
                    }
                    group.set(token.getKey(), token.getValue().deepCopy());
                }
            } else if (APPENDED_LISTS.contains(at) && value.isArray()) {
                ArrayNode list = existing instanceof ArrayNode array ? array : target.putArray(key);
                for (JsonNode item : value) {
                    list.add(item.deepCopy());
                }
            } else if (value.isObject() && leadsToOverlayable(at)) {
                merge(existing instanceof ObjectNode object ? object : target.putObject(key), value, at, source);
            } else {
                throw new IllegalArgumentException("Design token overlay " + source + " may not set " + at);
            }
        }
    }

    private static boolean leadsToOverlayable(String path) {
        String prefix = path + ".";
        for (String overlayable : ADDED_TOKENS) {
            if (overlayable.startsWith(prefix)) {
                return true;
            }
        }
        for (String overlayable : APPENDED_LISTS) {
            if (overlayable.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The user's color vision palette, which {@link #shipped()} resolves; standard until set. */
    public static Palette palette() {
        return activePalette;
    }

    /**
     * Sets the user's color vision palette. Themes built from {@link #shipped()} keep their colors until they are built
     * again: Swing by setting the look and feel again, libGDX by {@code UiTheme.refreshTokens()}.
     */
    public static void setPalette(Palette palette) {
        activePalette = palette;
    }

    /** Reads a token file in the standard palette. */
    public static DesignTokens read(InputStream stream) throws IOException {
        return read(stream, Palette.STANDARD);
    }

    /** Reads a token file in {@code palette}. */
    public static DesignTokens read(InputStream stream, Palette palette) throws IOException {
        return new DesignTokens(new ObjectMapper().readTree(stream), palette);
    }

    /**
     * Reads a token file in {@code palette} with {@code overlays} merged in, in order, as {@link #addOverlay} would; an
     * app's test checks its overlay this way without adding it to the shipped tokens.
     *
     * @throws IllegalArgumentException when an overlay changes a token or sets anything an overlay may not
     */
    public static DesignTokens read(InputStream stream, Palette palette, List<InputStream> overlays)
          throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode merged = mapper.readTree(stream);
        for (int i = 0; i < overlays.size(); i++) {
            merged = overlaid(merged, mapper.readTree(overlays.get(i)), "overlay " + (i + 1));
        }
        return new DesignTokens(merged, palette);
    }

    /** The palette these tokens' colors are in. */
    public Palette tokenPalette() {
        return tokenPalette;
    }

    public String version() {
        return version;
    }

    /** Every color role in file order. */
    public Map<String, ColorToken> colors() {
        return colors;
    }

    public ColorToken colorToken(String name) {
        ColorToken token = colors.get(name);
        if (token == null) {
            throw new IllegalArgumentException("Unknown color token " + name);
        }
        return token;
    }

    /** The color of a role in a mode, 0xAARRGGBB. */
    public int color(String name, Mode mode) {
        return colorToken(name).value(mode);
    }

    public String family() {
        return family;
    }

    public String iconFamily() {
        return iconFamily;
    }

    public String caseRule() {
        return caseRule;
    }

    /** Every text style in file order. */
    public Map<String, TypeStyle> typeStyles() {
        return typeStyles;
    }

    public TypeStyle typeStyle(String name) {
        TypeStyle style = typeStyles.get(name);
        if (style == null) {
            throw new IllegalArgumentException("Unknown text style " + name);
        }
        return style;
    }

    /** Every text role (button, tab, title...) and the text style it uses, in file order. */
    public Map<String, String> textRoles() {
        return textRoles;
    }

    /** The text style of {@code role}. */
    public TypeStyle roleStyle(String role) {
        String style = textRoles.get(role);
        if (style == null) {
            throw new IllegalArgumentException("Unknown text role " + role);
        }
        return typeStyles.get(style);
    }

    /** Whether text of {@code role} is upper case on a screen in {@code presentation}. */
    public boolean upperCase(String role, Presentation presentation) {
        roleStyle(role);
        return upperRoles.get(presentation).contains(role);
    }

    /** Whether text of {@code role} is set in the condensed width on a screen in {@code presentation}. */
    public boolean condensed(String role, Presentation presentation) {
        roleStyle(role);
        return condensedRoles.get(presentation).contains(role);
    }

    /** The roles set in the condensed width on a screen in {@code presentation}, in file order. */
    public Set<String> condensedRoles(Presentation presentation) {
        return condensedRoles.get(presentation);
    }

    /** The font width (wdth axis, 100 is regular) of condensed text roles. */
    public int condensedWidth() {
        return condensedWidth;
    }

    /** When text is set condensed rather than at regular width. */
    public String widthRule() {
        return widthRule;
    }

    /**
     * {@code text} as a {@code role} shows it in {@code presentation}: upper-cased by the rules of {@code locale}, or as
     * is. Strings stay sentence case in their bundles; only the drawn text changes. HTML text is never changed.
     */
    public String cased(String role, Presentation presentation, String text, Locale locale) {
        if (text == null || !upperCase(role, presentation) || text.regionMatches(true, 0, "<html>", 0, 6)) {
            return text;
        }
        return text.toUpperCase(locale);
    }

    public Map<String, Float> space() {
        return space;
    }

    public Map<String, Float> radius() {
        return radius;
    }

    public Map<String, Float> size() {
        return size;
    }

    /** Alphas from 0 to 1, such as the tint of a status color laid over a hovered or pressed face. */
    public Map<String, Float> opacity() {
        return opacity;
    }

    /** The alpha {@code opacity.name} as a 0-255 channel. */
    public int alpha(String name) {
        Float value = opacity.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown opacity " + name);
        }
        return Math.round(value * 255);
    }

    /** Durations in milliseconds. */
    public Map<String, Float> motion() {
        return motion;
    }

    /** The easing curve's control points x1, y1, x2, y2, as in CSS cubic-bezier(). */
    public float[] easing() {
        return easing.clone();
    }

    /** A duration in milliseconds, such as {@code fast}. */
    public int duration(String name) {
        Float value = motion.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown motion " + name);
        }
        return Math.round(value);
    }

    public List<ContrastCheck> contrastChecks() {
        return contrastChecks;
    }

    public List<DistinctCheck> distinctChecks() {
        return distinctChecks;
    }

    private static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        return node.properties();
    }

    private static String required(JsonNode node, String field, String token) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Color token " + token + " has no " + field + " value");
        }
        return value.asText();
    }

    private static Map<String, Float> numbers(JsonNode node) {
        Map<String, Float> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(node)) {
            if (entry.getValue().isNumber()) {
                values.put(entry.getKey(), (float) entry.getValue().asDouble());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value.asText());
        }
        return List.copyOf(values);
    }
}
