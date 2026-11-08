/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.client.ui.entityreadout.EntityReadout.Row.Kind;
import megamek.client.ui.util.ViewFormatting;
import megamek.common.templates.TROView;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The Requisition dossier's Readout tab as rows a view lays out itself (gap analysis 7.3): the unit readout's Summary,
 * as the unit viewer shows it, and the Technical Readout, read from its HTML template into the same kind of rows (a
 * title, labelled lines, tables, text), as no HTML view can draw in the GPU window and the plain template pads its
 * columns for a fixed-width font.
 */
public final class RequisitionReadout {
    /** How long a labelled value may run before its row is a text section. */
    public static final int SECTION_LENGTH = 80;
    private static final MMLogger LOGGER = MMLogger.create(RequisitionReadout.class);
    private static final Pattern TOKEN = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--.*?-->|([^<]+)",
          Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Pattern HIDDEN = Pattern.compile("<(head|script|style)[^>]*>.*?</\\1>|<![a-zA-Z][^>]*>",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private RequisitionReadout() { }

    /** The unit readout of {@code entity} with every section, its Battle Value without a pilot's modifiers. */
    public static List<Row> summary(Entity entity) {
        try {
            return EntityReadout.createReadout(entity, false, false, true).getRows();
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The readout of {} could not be made", entity.getShortName());
            return List.of();
        }
    }

    /** The Technical Readout of {@code entity} as rows. */
    public static List<Row> tro(Entity entity) {
        try {
            String html = TROView.createView(entity, ViewFormatting.HTML).processTemplate();
            return (html == null) ? List.of() : fromHtml(html);
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The TRO of {} could not be made", entity.getShortName());
            return List.of();
        }
    }

    /**
     * Whether {@code row} is a text section rather than a labelled line: a {@code LABELED} row whose value runs on (a
     * break in it, or longer than {@link #SECTION_LENGTH}), as the fluff's Overview, Capabilities or History. A section
     * shows its label as a heading over its text at full width.
     */
    public static boolean isSection(Row row) {
        if (row.kind() != Kind.LABELED || row.cells().size() < 2) {
            return false;
        }
        String value = row.cells().get(1);
        return value.indexOf('\n') >= 0 || value.length() > SECTION_LENGTH;
    }

    /**
     * {@code rows} in blocks that a view reveals one after another: a block starts at a title, a text section, a
     * table's first row (or a header after a table's rows) or the row after a space, once the block before holds
     * more than titles and spaces (so a heading stays with what it heads).
     */
    public static List<List<Row>> blocks(List<Row> rows) {
        List<List<Row>> blocks = new ArrayList<>();
        List<Row> block = new ArrayList<>();
        boolean content = false;
        Row previous = null;
        for (Row row : rows) {
            boolean table = isTable(row);
            boolean starts = row.kind() == Kind.TITLE || isSection(row)
                  || (table && (previous == null || !isTable(previous)
                        || (row.kind() == Kind.TABLE_HEADER && previous.kind() == Kind.TABLE_ROW)))
                  || (previous != null && previous.kind() == Kind.SPACE);
            if (starts && content) {
                blocks.add(block);
                block = new ArrayList<>();
                content = false;
            }
            block.add(row);
            content |= row.kind() != Kind.TITLE && row.kind() != Kind.SPACE;
            previous = row;
        }
        if (!block.isEmpty()) {
            blocks.add(block);
        }
        return blocks;
    }

    /** How many of {@code cells} count: up to the last that holds more than blanks. */
    public static int filledCells(List<String> cells) {
        int count = cells.size();
        while (count > 0 && cells.get(count - 1).isBlank()) {
            count--;
        }
        return count;
    }

    /** Whether {@code row} is a table's header or row. */    public static boolean isTable(Row row) {
        return row.kind() == Kind.TABLE_HEADER || row.kind() == Kind.TABLE_ROW;
    }

    /**
     * The rows of a Technical Readout's HTML: a big font is the {@code TITLE}; a line that starts in bold and goes on
     * plain is {@code LABELED} (its colon dropped), one all in bold a {@code TITLE} of its own; a table row with header
     * cells, or all in italics, a {@code TABLE_HEADER}, any other a {@code TABLE_ROW} (cells as they are, empty ones
     * kept for the columns); the rest of the text {@code TEXT}, a paragraph's break a {@code SPACE}. Whitespace runs as
     * HTML runs it; entities are decoded.
     */
    public static List<Row> fromHtml(String html) {
        Reader reader = new Reader();
        Matcher token = TOKEN.matcher(HIDDEN.matcher(html).replaceAll(""));
        while (token.find()) {
            if (token.group(4) != null) {
                reader.text(decode(token.group(4)));
            } else if (token.group(2) != null) {
                String tag = token.group(2).toLowerCase(Locale.ROOT);
                boolean closing = !token.group(1).isEmpty();
                boolean selfClosing = token.group(3).trim().endsWith("/");
                reader.tag(tag, closing, selfClosing, token.group(3));
            }
        }
        reader.flushLine();
        reader.trimSpaces();
        return reader.rows;
    }

    /** {@code text} with its character entities decoded. */
    static String decode(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher entity = ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) {
            String name = entity.group(1);
            String value = switch (name.toLowerCase(Locale.ROOT)) {
                case "nbsp" -> "\u00A0";
                case "ensp" -> "\u2002";
                case "emsp" -> "\u2003";
                case "thinsp" -> "\u2009";
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "middot" -> "\u00B7";
                case "ndash" -> "\u2013";
                case "mdash" -> "\u2014";
                default -> {
                    if (name.startsWith("#x") || name.startsWith("#X")) {
                        yield new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
                    } else if (name.startsWith("#")) {
                        yield new String(Character.toChars(Integer.parseInt(name.substring(1))));
                    }
                    yield entity.group();
                }
            };
            entity.appendReplacement(decoded, Matcher.quoteReplacement(value));
        }
        entity.appendTail(decoded);
        return decoded.toString();
    }

    /** Collapses whitespace runs to one space and turns no-break spaces into spaces, then trims. */
    static String clean(String text) {
        return text.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    /** Walks the HTML's tags and text, gathering the rows. */
    private static final class Reader {
        private final List<Row> rows = new ArrayList<>();
        /** The line being gathered outside tables: its bold lead and the rest. */
        private final StringBuilder lead = new StringBuilder();
        private final StringBuilder rest = new StringBuilder();
        private int bold;
        private int italic;
        private int big;
        private boolean inRow;
        private boolean headerRow;
        private boolean allItalic;
        private final List<String> cells = new ArrayList<>();
        private StringBuilder cell;
        private boolean cellItalic;

        void text(String text) {
            if (cell != null) {
                cell.append(text);
                if (!text.isBlank()) {
                    cellItalic &= italic > 0;
                }
                return;
            }
            if (inRow) {
                return;
            }
            if (big > 0) {
                if (!text.isBlank()) {
                    flushLine();
                    rows.add(new Row(Kind.TITLE, List.of(clean(text))));
                }
                return;
            }
            if ((bold > 0) && clean(rest.toString()).isEmpty()) {
                lead.append(text);
            } else {
                rest.append(text);
            }
        }

        void tag(String tag, boolean closing, boolean selfClosing, String attributes) {
            switch (tag) {
                case "b", "strong" -> bold = Math.max(0, bold + (closing ? -1 : 1));
                case "i", "em" -> italic = Math.max(0, italic + (closing ? -1 : 1));
                case "font" -> {
                    if (closing) {
                        big = Math.max(0, big - 1);
                    } else if (attributes.contains("+")) {
                        big++;
                    }
                }
                case "h1", "h2", "h3", "h4" -> big = Math.max(0, big + (closing ? -1 : 1));
                case "br" -> {
                    if (cell != null) {
                        cell.append(' ');
                    } else {
                        flushLine();
                    }
                }
                case "p", "div" -> {
                    flushLine();
                    space();
                }
                case "li" -> {
                    flushLine();
                    if (!closing) {
                        rest.append("\u2022 ");
                    }
                }
                case "ul", "ol", "table" -> flushLine();
                case "tr" -> {
                    if (closing) {
                        endRow();
                    } else {
                        flushLine();
                        endRow();
                        inRow = true;
                        headerRow = false;
                        allItalic = true;
                        cells.clear();
                    }
                }
                case "td", "th" -> {
                    if (closing) {
                        endCell();
                    } else {
                        endCell();
                        if (!inRow) {
                            inRow = true;
                            allItalic = true;
                            cells.clear();
                        }
                        headerRow |= tag.equals("th");
                        cell = new StringBuilder();
                        cellItalic = true;
                        if (selfClosing) {
                            endCell();
                        }
                    }
                }
                default -> { }
            }
        }

        private void endCell() {
            if (cell == null) {
                return;
            }
            String text = clean(cell.toString());
            if (!text.isEmpty()) {
                allItalic &= cellItalic;
            }
            cells.add(text);
            cell = null;
        }

        private void endRow() {
            if (!inRow) {
                return;
            }
            endCell();
            inRow = false;
            if (cells.stream().allMatch(String::isEmpty)) {
                return;
            }
            rows.add(new Row(headerRow || allItalic ? Kind.TABLE_HEADER : Kind.TABLE_ROW, List.copyOf(cells)));
        }

        void flushLine() {
            String label = clean(lead.toString());
            String value = clean(rest.toString());
            lead.setLength(0);
            rest.setLength(0);
            if (label.isEmpty() && value.isEmpty()) {
                return;
            }
            if (label.isEmpty()) {
                rows.add(new Row(Kind.TEXT, List.of(value)));
            } else if (value.isEmpty()) {
                rows.add(new Row(Kind.TITLE, List.of(stripColon(label))));
            } else {
                rows.add(new Row(Kind.LABELED, List.of(stripColon(label), value)));
            }
        }

        private void space() {
            if (!rows.isEmpty() && rows.get(rows.size() - 1).kind() != Kind.SPACE) {
                rows.add(new Row(Kind.SPACE, List.of()));
            }
        }

        /** Drops a space that ends the rows. */
        void trimSpaces() {
            while (!rows.isEmpty() && rows.get(rows.size() - 1).kind() == Kind.SPACE) {
                rows.remove(rows.size() - 1);
            }
        }

        private static String stripColon(String label) {
            return label.endsWith(":") ? label.substring(0, label.length() - 1).trim() : label;
        }
    }
}
