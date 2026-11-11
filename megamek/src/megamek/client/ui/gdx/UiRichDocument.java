/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;

/**
 * The HTML subset that game text is written in, read into blocks of styled runs for {@link UiRichText}: paragraphs and
 * divisions, line breaks, bold and italic, headings, bulleted and numbered lists, horizontal rules, centred or
 * right-aligned blocks, coloured spans and fonts, and links. Other tags are dropped and keep their text; whitespace
 * collapses as in HTML. The reader never fails: unclosed and stray tags are tolerated.
 */
public final class UiRichDocument {
    /** What a block is. */
    public enum Kind { PARAGRAPH, HEADING, ITEM, RULE }

    /** How a block's lines sit across its width. */
    public enum Align { LEFT, CENTER, RIGHT }

    /**
     * A stretch of text in one style; a run of a single {@code '\n'} is a line break. {@code color} is null for the
     * text's own color and {@code link} null when the run is not a link.
     */
    public record Run(String text, boolean bold, boolean italic, Color color, String link) {
        boolean sameStyle(boolean bold, boolean italic, Color color, String link) {
            return this.bold == bold && this.italic == italic && Objects.equals(this.color, color)
                  && Objects.equals(this.link, link);
        }
    }

    /**
     * A block: its kind, its heading level (1-6, 0 when not a heading), its list depth (1 for a top-level item, 0
     * outside lists), its item marker ("•" or "3."; empty when not an item), its alignment and its runs.
     */
    public record Block(Kind kind, int level, int depth, String marker, Align align, List<Run> runs) {
        /** The block's text without styles, line breaks as {@code '\n'}. */
        public String text() {
            StringBuilder text = new StringBuilder();
            for (Run run : runs) {
                text.append(run.text());
            }
            return text.toString();
        }
    }

    private static final Map<String, String> ENTITIES = Map.ofEntries(Map.entry("amp", "&"), Map.entry("lt", "<"),
          Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", "\u00A0"),
          Map.entry("mdash", "\u2014"), Map.entry("ndash", "\u2013"), Map.entry("hellip", "\u2026"),
          Map.entry("bull", "\u2022"), Map.entry("middot", "\u00B7"), Map.entry("rsquo", "\u2019"),
          Map.entry("lsquo", "\u2018"), Map.entry("rdquo", "\u201D"), Map.entry("ldquo", "\u201C"),
          Map.entry("copy", "\u00A9"), Map.entry("reg", "\u00AE"), Map.entry("trade", "\u2122"),
          Map.entry("times", "\u00D7"), Map.entry("deg", "\u00B0"), Map.entry("plusmn", "\u00B1"));

    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(Map.entry("black", "000000"),
          Map.entry("white", "FFFFFF"), Map.entry("red", "FF0000"), Map.entry("green", "008000"),
          Map.entry("lime", "00FF00"), Map.entry("blue", "0000FF"), Map.entry("yellow", "FFFF00"),
          Map.entry("orange", "FFA500"), Map.entry("gray", "808080"), Map.entry("grey", "808080"),
          Map.entry("silver", "C0C0C0"), Map.entry("cyan", "00FFFF"), Map.entry("aqua", "00FFFF"),
          Map.entry("magenta", "FF00FF"), Map.entry("purple", "800080"), Map.entry("maroon", "800000"),
          Map.entry("navy", "000080"), Map.entry("teal", "008080"), Map.entry("olive", "808000"),
          Map.entry("gold", "FFD700"), Map.entry("pink", "FFC0CB"), Map.entry("brown", "A52A2A"));

    private static final java.util.Set<String> SKIPPED = Set.of("head", "title", "style", "script");

    private final List<Block> blocks;

    private UiRichDocument(List<Block> blocks) {
        this.blocks = Collections.unmodifiableList(blocks);
    }

    /** The document's blocks, in order; empty blocks are left out. */
    public List<Block> blocks() {
        return blocks;
    }

    /** The number of characters the document shows, as {@link UiRichText} counts them in its reveal. */
    public int length() {
        int length = 0;
        for (Block block : blocks) {
            length += block.marker().isEmpty() ? 0 : 1;
            length += block.kind() == Kind.RULE ? 1 : 0;
            for (Run run : block.runs()) {
                length += run.text().length();
            }
        }
        return length;
    }

    /** The document's text without styles: blocks on lines of their own, rules as empty lines. */
    public String plainText() {
        StringBuilder text = new StringBuilder();
        for (Block block : blocks) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            if (!block.marker().isEmpty()) {
                text.append(block.marker()).append(' ');
            }
            text.append(block.text());
        }
        return text.toString();
    }

    /** Reads {@code html}; null reads as an empty document. Plain text without tags reads as one paragraph. */
    public static UiRichDocument parse(String html) {
        Reader reader = new Reader();
        if (html != null) {
            reader.read(html);
        }
        reader.endBlock();
        return new UiRichDocument(reader.blocks);
    }

    /** An open element and the style it sets for what it holds. */
    private record Frame(String tag, boolean bold, boolean italic, Color color, String link, Align align) { }

    /** An open list: numbered or bulleted, and its next item's number. */
    private static final class ListFrame {
        final boolean ordered;
        int next = 1;

        ListFrame(boolean ordered) {
            this.ordered = ordered;
        }
    }

    private static final class Reader {
        final List<Block> blocks = new ArrayList<>();
        final Deque<Frame> frames = new ArrayDeque<>();
        final Deque<ListFrame> lists = new ArrayDeque<>();
        List<Run> runs = new ArrayList<>();
        Kind kind = Kind.PARAGRAPH;
        int level;
        String marker = "";
        Align blockAlign;
        // Whether the text so far ends in collapsible whitespace, or nothing has been written on the line yet
        boolean space = true;
        int skipping;

        Reader() {
            frames.push(new Frame("", false, false, null, null, Align.LEFT));
        }

        void read(String html) {
            int i = 0;
            int length = html.length();
            while (i < length) {
                char c = html.charAt(i);
                if (c == '<') {
                    if (html.startsWith("<!--", i)) {
                        int end = html.indexOf("-->", i + 4);
                        i = end < 0 ? length : end + 3;
                        continue;
                    }
                    int end = tagEnd(html, i + 1);
                    if (end < 0) {
                        // A lone '<' is text
                        text("<");
                        i++;
                        continue;
                    }
                    tag(html.substring(i + 1, end));
                    i = end + 1;
                } else if (c == '&') {
                    int end = html.indexOf(';', i + 1);
                    String entity = end > i + 1 && end - i <= 10 ? entity(html.substring(i + 1, end)) : null;
                    if (entity == null) {
                        text("&");
                        i++;
                    } else {
                        text(entity);
                        i = end + 1;
                    }
                } else {
                    int next = i;
                    while (next < length && html.charAt(next) != '<' && html.charAt(next) != '&') {
                        next++;
                    }
                    text(html.substring(i, next));
                    i = next;
                }
            }
        }

        /** The index of the '>' closing the tag whose name starts at {@code from}, or -1 if this is no tag. */
        private static int tagEnd(String html, int from) {
            if (from >= html.length()) {
                return -1;
            }
            char first = html.charAt(from);
            if (!Character.isLetter(first) && first != '/' && first != '!') {
                return -1;
            }
            char quote = 0;
            for (int i = from; i < html.length(); i++) {
                char c = html.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    return i;
                }
            }
            return -1;
        }

        private static String entity(String name) {
            if (name.startsWith("#")) {
                try {
                    int code = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                          ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return ENTITIES.get(name.toLowerCase(Locale.ROOT));
        }

        void tag(String body) {
            if (body.startsWith("!") || body.startsWith("?")) {
                return;
            }
            boolean closing = body.startsWith("/");
            String rest = closing ? body.substring(1) : body;
            int nameEnd = 0;
            while (nameEnd < rest.length() && (Character.isLetterOrDigit(rest.charAt(nameEnd)))) {
                nameEnd++;
            }
            String name = rest.substring(0, nameEnd).toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                return;
            }
            String attributes = rest.substring(nameEnd);
            if (SKIPPED.contains(name)) {
                skipping = Math.max(0, skipping + (closing ? -1 : 1));
                return;
            }
            if (closing) {
                close(name);
            } else {
                open(name, attributes);
            }
        }

        private void open(String name, String attributes) {
            Frame top = frames.peek();
            switch (name) {
                case "br" -> lineBreak();
                case "hr" -> {
                    endBlock();
                    blocks.add(new Block(Kind.RULE, 0, 0, "", Align.LEFT, List.of()));
                }
                case "b", "strong" -> push(name, true, top.italic(), top.color(), top.link(), top.align());
                case "i", "em", "cite" -> push(name, top.bold(), true, top.color(), top.link(), top.align());
                case "a" -> {
                    String href = attribute(attributes, "href");
                    push(name, top.bold(), top.italic(), top.color(), href == null ? top.link() : href, top.align());
                }
                case "font", "span" -> push(name, top.bold(), top.italic(), color(attributes, top.color()),
                      top.link(), top.align());
                case "p", "div", "center", "blockquote", "tr", "table" -> {
                    endBlock();
                    Align align = "center".equals(name) ? Align.CENTER : align(attributes, top.align());
                    push(name, top.bold(), top.italic(), color(attributes, top.color()), top.link(), align);
                }
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    endBlock();
                    push(name, true, top.italic(), color(attributes, top.color()), top.link(),
                          align(attributes, top.align()));
                    kind = Kind.HEADING;
                    level = name.charAt(1) - '0';
                }
                case "ul", "ol" -> {
                    endBlock();
                    lists.push(new ListFrame("ol".equals(name)));
                    push(name, top.bold(), top.italic(), top.color(), top.link(), top.align());
                }
                case "li" -> {
                    endBlock();
                    if (lists.isEmpty()) {
                        lists.push(new ListFrame(false));
                        push("ul", top.bold(), top.italic(), top.color(), top.link(), top.align());
                    }
                    ListFrame list = lists.peek();
                    kind = Kind.ITEM;
                    marker = list.ordered ? (list.next++) + "." : "\u2022";
                    push(name, top.bold(), top.italic(), top.color(), top.link(), top.align());
                }
                case "td", "th" -> {
                    if (!runs.isEmpty()) {
                        text(" ");
                    }
                    push(name, "th".equals(name) || top.bold(), top.italic(), top.color(), top.link(), top.align());
                }
                default -> {
                    // Unknown tags keep their text and set no style
                }
            }
        }

        private void close(String name) {
            if (!contains(name)) {
                return;
            }
            boolean block = switch (name) {
                case "p", "div", "center", "blockquote", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6", "li",
                      "ul", "ol" -> true;
                default -> false;
            };
            if (block) {
                endBlock();
            }
            while (frames.size() > 1) {
                Frame frame = frames.pop();
                if (("ul".equals(frame.tag()) || "ol".equals(frame.tag())) && !lists.isEmpty()) {
                    lists.pop();
                }
                if (frame.tag().equals(name)) {
                    break;
                }
            }
        }

        private boolean contains(String name) {
            for (Frame frame : frames) {
                if (frame.tag().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        private void push(String tag, boolean bold, boolean italic, Color color, String link, Align align) {
            frames.push(new Frame(tag, bold, italic, color, link, align));
        }

        private void lineBreak() {
            if (blockAlign == null) {
                blockAlign = frames.peek().align();
            }
            runs.add(new Run("\n", false, false, null, null));
            space = true;
        }

        void text(String raw) {
            if (skipping > 0 || raw.isEmpty()) {
                return;
            }
            StringBuilder text = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                    if (!space) {
                        text.append(' ');
                        space = true;
                    }
                } else {
                    text.append(c);
                    space = false;
                }
            }
            if (text.isEmpty()) {
                return;
            }
            if (blockAlign == null) {
                blockAlign = frames.peek().align();
            }
            Frame top = frames.peek();
            if (!runs.isEmpty()) {
                Run last = runs.getLast();
                if (!"\n".equals(last.text()) && last.sameStyle(top.bold(), top.italic(), top.color(), top.link())) {
                    runs.set(runs.size() - 1, new Run(last.text() + text, last.bold(), last.italic(), last.color(),
                          last.link()));
                    return;
                }
            }
            runs.add(new Run(text.toString(), top.bold(), top.italic(), top.color(), top.link()));
        }

        /** Closes the block being written, trimming its trailing spaces and line breaks; empty blocks are dropped. */
        void endBlock() {
            while (!runs.isEmpty()) {
                Run last = runs.getLast();
                String trimmed = "\n".equals(last.text()) ? "" : last.text().stripTrailing();
                if (!trimmed.isEmpty()) {
                    runs.set(runs.size() - 1, new Run(trimmed, last.bold(), last.italic(), last.color(),
                          last.link()));
                    break;
                }
                runs.removeLast();
            }
            if (!runs.isEmpty()) {
                int depth = kind == Kind.ITEM ? lists.size() : 0;
                Align align = blockAlign == null ? Align.LEFT : blockAlign;
                blocks.add(new Block(kind, level, depth, marker, align, List.copyOf(runs)));
            }
            runs = new ArrayList<>();
            kind = Kind.PARAGRAPH;
            level = 0;
            marker = "";
            blockAlign = null;
            space = true;
        }

        /** The value of attribute {@code name} in a tag's attribute text, unquoted; null when it has none. */
        static String attribute(String attributes, String name) {
            String lower = attributes.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int at = lower.indexOf(name, from);
                if (at < 0) {
                    return null;
                }
                from = at + name.length();
                boolean startsName = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1))
                      && lower.charAt(at - 1) != '-';
                int i = from;
                while (i < lower.length() && Character.isWhitespace(lower.charAt(i))) {
                    i++;
                }
                if (!startsName || i >= lower.length() || lower.charAt(i) != '=') {
                    continue;
                }
                i++;
                while (i < lower.length() && Character.isWhitespace(lower.charAt(i))) {
                    i++;
                }
                if (i >= lower.length()) {
                    return "";
                }
                char quote = attributes.charAt(i);
                if (quote == '"' || quote == '\'') {
                    int end = attributes.indexOf(quote, i + 1);
                    return attributes.substring(i + 1, end < 0 ? attributes.length() : end);
                }
                int end = i;
                while (end < attributes.length() && !Character.isWhitespace(attributes.charAt(end))
                      && attributes.charAt(end) != '/') {
                    end++;
                }
                return attributes.substring(i, end);
            }
        }

        /** A CSS property's value in a style attribute, or null. */
        private static String style(String attributes, String property) {
            String style = attribute(attributes, "style");
            if (style == null) {
                return null;
            }
            for (String declaration : style.split(";")) {
                int colon = declaration.indexOf(':');
                if (colon > 0 && declaration.substring(0, colon).trim().equalsIgnoreCase(property)) {
                    return declaration.substring(colon + 1).trim();
                }
            }
            return null;
        }

        private static Color color(String attributes, Color fallback) {
            String value = attribute(attributes, "color");
            if (value == null) {
                value = style(attributes, "color");
            }
            Color color = value == null ? null : parseColor(value);
            return color == null ? fallback : color;
        }

        private static Align align(String attributes, Align fallback) {
            String value = attribute(attributes, "align");
            if (value == null) {
                value = style(attributes, "text-align");
            }
            if (value == null) {
                return fallback;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "center" -> Align.CENTER;
                case "right" -> Align.RIGHT;
                case "left" -> Align.LEFT;
                default -> fallback;
            };
        }
    }

    /** A CSS or HTML color: #RGB, #RRGGBB, RRGGBB, rgb(r, g, b) or one of the common names; null if unreadable. */
    public static Color parseColor(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("rgb(") && text.endsWith(")")) {
            String[] parts = text.substring(4, text.length() - 1).split(",");
            if (parts.length != 3) {
                return null;
            }
            try {
                return new Color(Integer.parseInt(parts[0].trim()) / 255f, Integer.parseInt(parts[1].trim()) / 255f,
                      Integer.parseInt(parts[2].trim()) / 255f, 1);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String hex = NAMED_COLORS.getOrDefault(text, text.startsWith("#") ? text.substring(1) : text);
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(hex, 16);
            return new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
