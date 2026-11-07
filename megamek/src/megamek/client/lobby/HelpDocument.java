/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.Nullable;

/**
 * An HTML document read as chapters to page through, toolkit-free. A guide, such as Princess's documentation or the
 * map setup's help, has a chapter for each second-level heading: its third-level headings and its tables' rows become
 * topics, each with its paragraphs, lists and code. The auto-resolve battle report has a chapter for its summary, its
 * forces, each round and the end of the combat: each phase is a topic of report lines, indented as the report indents
 * them and tagged with the colour of the unit or player they start with.
 * <p>
 * What the dialog replaces is left out: the tables of contents, the "back to top" links, the log's time stamp and the
 * dice faces, which the type cannot draw.
 */
public record HelpDocument(List<Chapter> chapters) {
    /** What a block of a chapter is. */
    public enum Kind {
        /** A topic's title, which the blocks after it belong to: a guide's h3 or table row, a report's phase. */
        HEADING,
        /** A heading within a topic. */
        SUBHEADING,
        /** A paragraph of text. */
        PARAGRAPH,
        /** A list item; its depth is how deep its list is nested, from 0. */
        BULLET,
        /** A line that is all code, such as a chat command's syntax. */
        CODE,
        /** A report line; its depth is its indent, from 0. */
        LINE,
        /** A headline result, such as who won the battle. */
        CALLOUT
    }

    /**
     * One block of a chapter.
     *
     * @param depth a bullet's nesting or a report line's indent, else 0
     * @param color the {@code #RRGGBB} colour of the unit or player the block starts with, or null
     */
    public record Block(Kind kind, String text, int depth, @Nullable String color) {
    }

    /** One chapter: its title and its blocks in order. */
    public record Chapter(String title, List<Block> blocks) {
    }

    /** How many en spaces the battle report indents a line by for each level. */
    static final int INDENT = 8;

    private static final Pattern TOKEN = Pattern.compile(
          "(?s)<!--.*?-->|<![^>]*>|<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)");
    private static final Pattern COLOR = Pattern.compile("(?i)color\\s*:\\s*#([0-9a-f]{6})");
    /** A report's player name: a bold coloured span. */
    private static final Pattern PLAYER = Pattern.compile(
          "(?is)<span[^>]*color\\s*:\\s*#([0-9a-f]{6})[^>]*bold[^>]*>([^<]+)</span>");
    private static final Pattern ROUND = Pattern.compile("round-(\\d+)");
    private static final Pattern INDENT_RUN = Pattern.compile("[\\u2002\\u2003]{4,}");
    private static final Set<String> VOID = Set.of("br", "img", "hr", "meta", "link", "input", "col", "area", "base",
          "wbr");
    private static final Set<String> BLOCKS = Set.of("p", "div", "ul", "ol", "li", "table", "tr", "td", "th", "pre",
          "hr", "blockquote", "section", "dl", "dt", "dd", "body", "footer");

    /** A guide: a chapter for its opening (titled by its h1) when it has one, then one for each h2. */
    public static HelpDocument guide(String html) {
        return new Reader(false, "", round -> "").read(html);
    }

    /**
     * An auto-resolve battle report: a chapter for its summary, with who won first, then one for its forces, each
     * round and the end of the combat.
     *
     * @param summaryTitle the summary chapter's title
     * @param roundTitle   a round's chapter title, by its number
     */
    public static HelpDocument battleReport(String html, String summaryTitle, IntFunction<String> roundTitle) {
        HelpDocument document = new Reader(true, summaryTitle, roundTitle).read(html);
        Map<String, String> players = playerColors(html);
        if (players.isEmpty()) {
            return document;
        }
        List<Chapter> chapters = new ArrayList<>();
        for (Chapter chapter : document.chapters()) {
            List<Block> blocks = new ArrayList<>();
            for (Block block : chapter.blocks()) {
                String color = block.kind() == Kind.LINE && block.color() == null
                      ? playerColor(block.text(), players) : null;
                blocks.add(color == null ? block : new Block(block.kind(), block.text(), block.depth(), color));
            }
            chapters.add(new Chapter(chapter.title(), blocks));
        }
        return new HelpDocument(chapters);
    }

    /** The report's players and their colours, from the bold coloured names it gives them. */
    static Map<String, String> playerColors(String html) {
        Map<String, String> players = new LinkedHashMap<>();
        Matcher span = PLAYER.matcher(html);
        while (span.find()) {
            String name = clean(span.group(2));
            if (!name.isEmpty()) {
                players.putIfAbsent(name, "#" + span.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return players;
    }

    /** The colour of the player a line starts with, as an initiative roll's line does, or null. */
    private static @Nullable String playerColor(String text, Map<String, String> players) {
        String found = null;
        int longest = 0;
        for (Map.Entry<String, String> player : players.entrySet()) {
            String name = player.getKey();
            if (name.length() > longest && text.startsWith(name + " ")) {
                found = player.getValue();
                longest = name.length();
            }
        }
        return found;
    }

    /** The chapters' titles, in order. */
    public List<String> titles() {
        return chapters.stream().map(Chapter::title).toList();
    }

    /** {@code text} with whitespace runs, no-break and en spaces as one space, trimmed. */
    static String clean(String text) {
        return text.replaceAll("[\\s\\u00A0\\u2002\\u2003\\u2009]+", " ").trim();
    }

    /** A heading's text without the symbols a report puts before it and the colon after it. */
    static String headingText(String text) {
        String clean = clean(text).replaceFirst("^[^\\p{L}\\p{N}(\\[]+", "");
        return clean.endsWith(":") ? clean.substring(0, clean.length() - 1).trim() : clean;
    }

    /** How deep a report line is indented: its leading en spaces (an em space two), a level for each {@link #INDENT}. */
    static int indent(String raw) {
        int spaces = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u2002') {
                spaces++;
            } else if (c == '\u2003') {
                spaces += 2;
            } else if (!Character.isWhitespace(c) && c != '\u00A0') {
                break;
            }
        }
        return spaces == 0 ? 0 : Math.max(1, (spaces + INDENT / 2) / INDENT);
    }

    private static @Nullable String attribute(String attributes, String name) {
        Matcher value = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
              .matcher(attributes);
        if (!value.find()) {
            return null;
        }
        return value.group(1) != null ? value.group(1) : value.group(2) != null ? value.group(2) : value.group(3);
    }

    /** An open link or anchor: its name or id, and whether it links within the document. */
    private record Anchor(String name, boolean internal) {
    }

    /** Walks the HTML's tags and text, gathering the chapters. */
    private static final class Reader {
        private final boolean report;
        private final String summaryTitle;
        private final IntFunction<String> roundTitle;
        private final List<Chapter> chapters = new ArrayList<>();
        private @Nullable String chapterTitle;
        private List<Block> blocks = new ArrayList<>();
        private String documentTitle = "";
        /** The element whose content is left out, and how deep in it the reader is. */
        private @Nullable String skipped;
        private int skipDepth;
        /** Whether the reader is in a part left out up to the next h2, such as the report's table of contents. */
        private boolean skipSection;
        /** The heading being read: h1 to h6, or td for a table row's first cell. */
        private @Nullable String heading;
        private final StringBuilder headingText = new StringBuilder();
        private final StringBuilder line = new StringBuilder();
        private final StringBuilder linkText = new StringBuilder();
        private final StringBuilder codeText = new StringBuilder();
        private @Nullable String color;
        private final Deque<Anchor> anchors = new ArrayDeque<>();
        private final Deque<Integer> items = new ArrayDeque<>();
        private int links;
        private int code;
        private int pre;
        private boolean lineInPre;
        private int lists;
        private int cell;

        Reader(boolean report, String summaryTitle, IntFunction<String> roundTitle) {
            this.report = report;
            this.summaryTitle = summaryTitle;
            this.roundTitle = roundTitle;
        }

        HelpDocument read(String html) {
            Matcher token = TOKEN.matcher(html);
            while (token.find()) {
                if (token.group(4) != null) {
                    text(RequisitionReadout.decode(token.group(4)));
                } else if (token.group(2) != null) {
                    tag(token.group(2).toLowerCase(Locale.ROOT), !token.group(1).isEmpty(), token.group(3));
                }
            }
            flush();
            endChapter();
            if (report) {
                leadWithTheOutcome();
            }
            return new HelpDocument(List.copyOf(chapters));
        }

        /** Puts the report's first callout, who won, at the head of its summary too. */
        private void leadWithTheOutcome() {
            Block outcome = chapters.stream().flatMap(chapter -> chapter.blocks().stream())
                  .filter(block -> block.kind() == Kind.CALLOUT).findFirst().orElse(null);
            if (outcome == null) {
                return;
            }
            if (!chapters.isEmpty() && chapters.get(0).title().equals(summaryTitle)) {
                List<Block> summary = new ArrayList<>(chapters.get(0).blocks());
                summary.add(0, outcome);
                chapters.set(0, new Chapter(summaryTitle, List.copyOf(summary)));
            } else {
                chapters.add(0, new Chapter(summaryTitle, List.of(outcome)));
            }
        }

        private boolean hides(String tag, String attributes) {
            if (Set.of("head", "style", "script", "title", "nav", "th").contains(tag)) {
                return true;
            }
            String classes = attribute(attributes, "class");
            if (classes == null) {
                return false;
            }
            for (String each : classes.toLowerCase(Locale.ROOT).split("\\s+")) {
                if (each.equals("nav") || each.startsWith("nav-") || each.equals("datetimelog")
                      || each.equals("dice")) {
                    return true;
                }
            }
            return false;
        }

        void tag(String tag, boolean closing, String attributes) {
            boolean empty = VOID.contains(tag) || attributes.trim().endsWith("/");
            if (skipped != null) {
                if (tag.equals(skipped) && !empty) {
                    skipDepth += closing ? -1 : 1;
                    if (skipDepth == 0) {
                        skipped = null;
                    }
                }
                return;
            }
            if (!closing && !empty && hides(tag, attributes)) {
                skipped = tag;
                skipDepth = 1;
                return;
            }
            if (heading != null && !(closing && tag.equals(heading))) {
                // Inside a heading only the inline tags count; a break is a space
                if (tag.equals("br")) {
                    headingText.append(' ');
                } else {
                    inline(tag, closing, attributes);
                }
                return;
            }
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (closing) {
                        endHeading();
                    } else {
                        flush();
                        heading = tag;
                        headingText.setLength(0);
                        color = null;
                    }
                }
                case "br" -> flush();
                default -> {
                    if (BLOCKS.contains(tag)) {
                        block(tag, closing);
                    } else {
                        inline(tag, closing, attributes);
                    }
                }
            }
        }

        private void block(String tag, boolean closing) {
            if ("td".equals(heading)) {
                endHeading();
            }
            flush();
            switch (tag) {
                case "ul", "ol" -> lists = Math.max(0, lists + (closing ? -1 : 1));
                case "li" -> {
                    if (closing) {
                        items.poll();
                    } else {
                        items.push(Math.max(0, lists - 1));
                    }
                }
                case "pre" -> pre = Math.max(0, pre + (closing ? -1 : 1));
                case "tr" -> cell = 0;
                case "td" -> {
                    if (!closing && ++cell == 1) {
                        // A table row's first cell names the row's topic
                        heading = "td";
                        headingText.setLength(0);
                        color = null;
                    }
                }
                default -> { }
            }
        }

        private void inline(String tag, boolean closing, String attributes) {
            switch (tag) {
                case "a" -> {
                    if (closing) {
                        Anchor anchor = anchors.poll();
                        if (anchor != null && anchor.internal()) {
                            links--;
                        }
                    } else {
                        String href = attribute(attributes, "href");
                        String name = attribute(attributes, "name");
                        if (name == null) {
                            name = attribute(attributes, "id");
                        }
                        boolean internal = href != null && href.startsWith("#");
                        anchors.push(new Anchor(name == null ? "" : name, internal));
                        if (internal) {
                            links++;
                        }
                    }
                }
                case "code", "tt", "kbd" -> code = Math.max(0, code + (closing ? -1 : 1));
                case "span", "font" -> {
                    if (!closing && color == null) {
                        String style = attribute(attributes, "style");
                        Matcher hex = COLOR.matcher(style == null ? "" : style);
                        if (hex.find()) {
                            color = "#" + hex.group(1).toLowerCase(Locale.ROOT);
                        }
                    }
                }
                default -> { }
            }
        }

        void text(String text) {
            if (skipped != null) {
                return;
            }
            if (heading != null) {
                headingText.append(text);
                return;
            }
            if (skipSection) {
                return;
            }
            if (pre > 0) {
                append(text);
                lineInPre = true;
                return;
            }
            if (!report) {
                append(text);
                return;
            }
            // A report goes on to a new, indented line after a run of en spaces even without a break
            Matcher run = INDENT_RUN.matcher(text);
            int at = 0;
            while (run.find()) {
                append(text.substring(at, run.start()));
                if (!clean(line.toString()).isEmpty()) {
                    flush();
                }
                append(run.group());
                at = run.end();
            }
            append(text.substring(at));
        }

        private void append(String text) {
            line.append(text);
            if (links > 0) {
                linkText.append(text);
            }
            if (code > 0) {
                codeText.append(text);
            }
        }

        /** Ends the line being read as a block, unless it is empty or only a link within the document. */
        private void flush() {
            String raw = line.toString();
            String text = lineInPre ? raw.strip() : clean(raw);
            boolean onlyALink = !linkText.isEmpty() && clean(linkText.toString()).equals(text);
            if (!text.isEmpty() && !onlyALink) {
                if (report && (chapterTitle != null || !chapters.isEmpty())) {
                    add(new Block(Kind.LINE, text, indent(raw), color));
                } else if (!items.isEmpty()) {
                    add(new Block(Kind.BULLET, text, items.peek(), color));
                } else if (lineInPre || clean(codeText.toString()).equals(text)) {
                    add(new Block(Kind.CODE, text, 0, null));
                } else {
                    add(new Block(Kind.PARAGRAPH, text, 0, color));
                }
            }
            line.setLength(0);
            linkText.setLength(0);
            codeText.setLength(0);
            lineInPre = false;
            color = null;
        }

        private void add(Block block) {
            if (!skipSection) {
                blocks.add(block);
            }
        }

        private void endHeading() {
            String tag = heading;
            heading = null;
            String text = headingText(headingText.toString());
            String shade = color;
            color = null;
            if (tag == null || text.isEmpty()) {
                return;
            }
            if (tag.equals("td")) {
                add(new Block(Kind.HEADING, text, 0, shade));
                return;
            }
            int level = tag.charAt(1) - '0';
            if (report) {
                reportHeading(level, text, shade);
            } else if (level == 1) {
                if (documentTitle.isEmpty()) {
                    documentTitle = text;
                }
            } else if (level == 2) {
                startChapter(text);
            } else {
                add(new Block(level == 3 ? Kind.HEADING : Kind.SUBHEADING, text, 0, shade));
            }
        }

        /**
         * A report's heading, by the anchor it stands in: the summary's table of contents is left out, a round's
         * initiative and the end of the combat start chapters, who won is the callout, and the other phases and
         * reports are topics. A heading in no anchor, the forces, starts a chapter.
         */
        private void reportHeading(int level, String text, @Nullable String shade) {
            if (level == 1) {
                return;
            }
            if (level > 2) {
                add(new Block(Kind.SUBHEADING, text, 0, shade));
                return;
            }
            skipSection = false;
            String anchor = anchors.stream().map(Anchor::name).filter(name -> !name.isEmpty()).findFirst()
                  .orElse("");
            Matcher round = ROUND.matcher(anchor);
            if (anchor.equals("summary")) {
                skipSection = true;
            } else if (round.matches()) {
                startChapter(roundTitle.apply(Integer.parseInt(round.group(1))));
                add(new Block(Kind.HEADING, text, 0, shade));
            } else if (anchor.equals("victory")) {
                add(new Block(Kind.CALLOUT, text, 0, shade));
            } else if (anchor.isEmpty() || anchor.equals("end-of-combat")) {
                startChapter(text);
            } else {
                add(new Block(Kind.HEADING, text, 0, shade));
            }
        }

        private void startChapter(String title) {
            endChapter();
            chapterTitle = title;
        }

        private void endChapter() {
            String title = chapterTitle != null ? chapterTitle : report ? summaryTitle : documentTitle;
            if (!blocks.isEmpty()) {
                chapters.add(new Chapter(title, List.copyOf(blocks)));
            }
            blocks = new ArrayList<>();
        }
    }
}
