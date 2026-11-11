/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.utils.Align;

/**
 * Game text in the {@link UiRichDocument} HTML subset, wrapped to the widget's width: mixed bold and italic runs,
 * headings, bulleted and numbered lists, rules, aligned blocks, colored spans and links that call back when clicked.
 * It can type itself in, character by character behind a caret, and a click while it types shows the rest at once.
 * Its preferred width is the given wrap width, or the text's widest line when none is given; its preferred height is
 * the text's height at its current width.
 */
public class UiRichText extends Widget {
    /** The faces and colors of rich text; see {@link UiTheme#richText(float, Color, Color)}. */
    public static final class Style {
        /** The sizes of headings 1 to 3 against body text; headings 4 to 6 are bold body text. */
        public static final float[] HEADING_SCALES = { 1.45f, 1.28f, 1.12f };

        final BitmapFont regular;
        final BitmapFont bold;
        final BitmapFont italic;
        final BitmapFont boldItalic;
        final BitmapFont[] headings;
        final Color color;
        final Color link;
        final TextureRegion white;
        final float size;

        public Style(BitmapFont regular, BitmapFont bold, BitmapFont italic, BitmapFont boldItalic,
              BitmapFont[] headings, float size, Color color, Color link, TextureRegion white) {
            this.regular = regular;
            this.bold = bold;
            this.italic = italic;
            this.boldItalic = boldItalic;
            this.headings = headings.clone();
            this.color = color;
            this.link = link;
            this.white = white;
            this.size = size;
        }

        BitmapFont font(UiRichDocument.Block block, UiRichDocument.Run run) {
            if (block.kind() == UiRichDocument.Kind.HEADING && block.level() <= headings.length) {
                return headings[block.level() - 1];
            }
            if (run.bold()) {
                return run.italic() ? boldItalic : bold;
            }
            return run.italic() ? italic : regular;
        }
    }

    /** Text on one line in one face: x from the left, baseline down from the top, and its line's band. */
    private static final class Piece {
        final BitmapFont font;
        final Color color;
        final String link;
        final int start;
        String text;
        float x;
        float width;
        float baseline;
        float lineTop;
        float lineBottom;

        Piece(BitmapFont font, String text, float x, float width, Color color, String link, int start) {
            this.font = font;
            this.text = text;
            this.x = x;
            this.width = width;
            this.color = color;
            this.link = link;
            this.start = start;
        }
    }

    /** A horizontal rule, {@code y} down from the top, shown once the reveal reaches {@code at}. */
    private record Rule(float y, int at) { }

    private static final float CARET_BLINK = 0.53f;

    private final Style style;
    private final GlyphLayout measure = new GlyphLayout();
    private final List<Piece> pieces = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<Piece> line = new ArrayList<>();
    private UiRichDocument document = UiRichDocument.parse("");
    private int length;
    private float wrapWidth;
    private float laidWidth = -1;
    private float laidHeight;
    private float naturalWidth = -1;
    private float revealed = Float.MAX_VALUE;
    private float speed;
    private float caretTime;
    private Consumer<String> linkAction;
    private String hovered;
    private String pressed;
    private boolean skipOnClick = true;
    // Where the layout pass is: the next line's top, the width placed on it and the reveal index of the next character
    private float y;
    private float lineWidth;
    private int index;

    /** Rich text showing {@code html} in full. */
    public UiRichText(String html, Style style) {
        this.style = style;
        setText(html);
        addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                hover(linkAt(x, y));
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(UiRichText.this))) {
                    hover(null);
                }
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (revealing() && skipOnClick) {
                    finish();
                    return true;
                }
                pressed = linkAt(x, y);
                return pressed != null;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                String link = linkAt(x, y);
                if (link != null && link.equals(pressed) && linkAction != null) {
                    linkAction.accept(link);
                }
                pressed = null;
            }
        });
    }

    /** Shows {@code html} in full, replacing the text. */
    public void setText(String html) {
        document = UiRichDocument.parse(html);
        length = document.length();
        revealed = Float.MAX_VALUE;
        laidWidth = -1;
        naturalWidth = -1;
        invalidateHierarchy();
    }

    /** The text as read, for tests and for screen readers' plain copy. */
    public UiRichDocument document() {
        return document;
    }

    /** Calls {@code action} with a link's href when it is clicked. */
    public UiRichText onLink(Consumer<String> action) {
        linkAction = action;
        return this;
    }

    /** Wraps the text at {@code width} stage units before the widget is given a width, and prefers that width. */
    public UiRichText wrapWidth(float width) {
        wrapWidth = width;
        invalidateHierarchy();
        return this;
    }

    /** Whether a click while the text types in shows it all at once (the default), rather than passing through. */
    public UiRichText skipOnClick(boolean skip) {
        skipOnClick = skip;
        return this;
    }

    /** Types the text in from nothing at {@code charactersPerSecond}; a rate of 0 or less shows it at once. */
    public void typeIn(float charactersPerSecond) {
        speed = charactersPerSecond;
        revealed = charactersPerSecond > 0 ? 0 : Float.MAX_VALUE;
        caretTime = 0;
    }

    /** Shows the rest of the text at once. */
    public void finish() {
        revealed = Float.MAX_VALUE;
    }

    /** Whether the text is still typing in. */
    public boolean revealing() {
        return revealed < length;
    }

    /** How far the text has typed in, 0 to 1. */
    public float progress() {
        return length == 0 ? 1 : Math.min(1, revealed / length);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        caretTime += delta;
        if (revealing()) {
            revealed = Math.min(length, revealed + delta * speed);
            if (revealed >= length) {
                revealed = Float.MAX_VALUE;
            }
        }
    }

    @Override
    public float getPrefWidth() {
        if (wrapWidth > 0) {
            return wrapWidth;
        }
        if (naturalWidth < 0) {
            lay(Float.MAX_VALUE);
            naturalWidth = 0;
            for (Piece piece : pieces) {
                naturalWidth = Math.max(naturalWidth, piece.x + piece.width);
            }
            laidWidth = -1;
        }
        return naturalWidth;
    }

    @Override
    public float getPrefHeight() {
        float width = getWidth() > 0 ? getWidth() : getPrefWidth();
        if (width != laidWidth) {
            lay(width);
        }
        return laidHeight;
    }

    @Override
    public void layout() {
        float width = getWidth();
        if (width != laidWidth) {
            float before = laidHeight;
            lay(width);
            if (before != laidHeight) {
                invalidateHierarchy();
            }
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        Color tint = getColor();
        float alpha = tint.a * parentAlpha;
        float left = getX();
        float top = getY() + getHeight();
        Color before = new Color(batch.getColor());
        float thickness = Math.max(1, style.size * .07f);
        for (Rule rule : rules) {
            if (rule.at() < revealed) {
                batch.setColor(style.color.r, style.color.g, style.color.b, .3f * alpha);
                batch.draw(style.white, left, top - rule.y(), getWidth(), thickness);
            }
        }
        float caretX = Float.NaN;
        float caretY = 0;
        float caretHeight = 0;
        for (Piece piece : pieces) {
            if (piece.start >= revealed) {
                break;
            }
            int shown = (int) Math.min(piece.text.length(), revealed - piece.start);
            Color color = color(piece);
            BitmapFont font = piece.font;
            float baseline = top - piece.baseline;
            font.setColor(color.r, color.g, color.b, color.a * alpha);
            font.draw(batch, piece.text, left + piece.x, baseline + font.getCapHeight(), 0, shown, 0, Align.left,
                  false);
            float width = piece.width;
            if (shown < piece.text.length()) {
                measure.setText(font, piece.text, 0, shown, color, 0, Align.left, false, null);
                width = measure.width;
                caretX = left + piece.x + width + style.size * .08f;
                caretY = baseline;
                caretHeight = font.getCapHeight();
            } else if (revealing()) {
                caretX = left + piece.x + width + style.size * .08f;
                caretY = baseline;
                caretHeight = font.getCapHeight();
            }
            if (piece.link != null) {
                batch.setColor(color.r, color.g, color.b, color.a * alpha * (piece.link.equals(hovered) ? 1 : .55f));
                batch.draw(style.white, left + piece.x, baseline - style.size * .14f, width, thickness);
            }
            font.setColor(Color.WHITE);
        }
        if (revealing() && !Float.isNaN(caretX) && caretTime % (2 * CARET_BLINK) < CARET_BLINK * 1.4f) {
            batch.setColor(style.link.r, style.link.g, style.link.b, alpha);
            batch.draw(style.white, caretX, caretY, style.size * .5f, caretHeight);
        }
        batch.setColor(before);
    }

    private Color color(Piece piece) {
        if (piece.link != null) {
            return piece.link.equals(hovered) ? Color.WHITE : style.link;
        }
        return piece.color == null ? style.color : piece.color;
    }

    private void hover(String link) {
        if (link == null ? hovered == null : link.equals(hovered)) {
            return;
        }
        hovered = link;
        if (Gdx.graphics != null) {
            Gdx.graphics.setSystemCursor(link == null ? Cursor.SystemCursor.Arrow : Cursor.SystemCursor.Hand);
        }
    }

    /** The href of the shown link at local {@code x, y}, or null. */
    private String linkAt(float x, float y) {
        float down = getHeight() - y;
        for (Piece piece : pieces) {
            if (piece.link != null && piece.start < revealed && x >= piece.x && x <= piece.x + piece.width
                  && down >= piece.lineTop && down <= piece.lineBottom) {
                return piece.link;
            }
        }
        return null;
    }

    /** Lays the text out at {@code width}, filling pieces and rules and setting laidHeight. */
    private void lay(float width) {
        laidWidth = width;
        pieces.clear();
        rules.clear();
        y = 0;
        index = 0;
        float size = style.size;
        UiRichDocument.Block previous = null;
        for (UiRichDocument.Block block : document.blocks()) {
            if (previous != null) {
                y += gap(previous, block, size);
            }
            previous = block;
            if (block.kind() == UiRichDocument.Kind.RULE) {
                y += size * .35f;
                rules.add(new Rule(y, index++));
                y += size * .35f;
                continue;
            }
            float indent = block.depth() * size * 1.4f;
            int first = pieces.size();
            if (!block.marker().isEmpty()) {
                index++;
            }
            int markerAt = index - 1;
            lineWidth = 0;
            line.clear();
            for (UiRichDocument.Run run : block.runs()) {
                BitmapFont font = style.font(block, run);
                if ("\n".equals(run.text())) {
                    endLine(block, indent, width);
                    index++;
                    continue;
                }
                words(run, font, indent, width, block);
            }
            endLine(block, indent, width);
            if (!block.marker().isEmpty() && first < pieces.size()) {
                Piece lead = pieces.get(first);
                BitmapFont font = style.regular;
                measure.setText(font, block.marker());
                Piece marker = new Piece(font, block.marker(), indent - measure.width - size * .45f, measure.width,
                      null, null, markerAt);
                marker.baseline = lead.baseline;
                marker.lineTop = lead.lineTop;
                marker.lineBottom = lead.lineBottom;
                pieces.add(first, marker);
            }
        }
        laidHeight = y;
    }

    private static float gap(UiRichDocument.Block previous, UiRichDocument.Block block, float size) {
        if (block.kind() == UiRichDocument.Kind.ITEM && previous.kind() == UiRichDocument.Kind.ITEM) {
            return size * .25f;
        }
        if (block.kind() == UiRichDocument.Kind.HEADING) {
            return size * .9f;
        }
        if (previous.kind() == UiRichDocument.Kind.HEADING) {
            return size * .35f;
        }
        return size * .7f;
    }

    /** Places a run's words and spaces on lines, breaking before a word that would cross the width. */
    private void words(UiRichDocument.Run run, BitmapFont font, float indent, float width,
          UiRichDocument.Block block) {
        String text = run.text();
        float room = Math.max(style.size, width - indent);
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == ' ') {
                if (!line.isEmpty()) {
                    place(font, " ", run);
                }
                index++;
                i++;
                continue;
            }
            int end = text.indexOf(' ', i);
            if (end < 0) {
                end = text.length();
            }
            String word = text.substring(i, end);
            measure.setText(font, word);
            if (lineWidth + measure.width > room && hasInk()) {
                endLine(block, indent, width);
            }
            while (measure.width > room && word.length() > 1) {
                int fit = fitting(font, word, room - lineWidth);
                place(font, word.substring(0, fit), run);
                endLine(block, indent, width);
                word = word.substring(fit);
                measure.setText(font, word);
            }
            place(font, word, run);
            i = end;
        }
    }

    /** The longest prefix of {@code word}, at least one character, that fits in {@code room}. */
    private int fitting(BitmapFont font, String word, float room) {
        int fit = 1;
        while (fit < word.length()) {
            measure.setText(font, word, 0, fit + 1, Color.WHITE, 0, Align.left, false, null);
            if (measure.width > room) {
                break;
            }
            fit++;
        }
        return fit;
    }

    private boolean hasInk() {
        for (Piece piece : line) {
            if (!piece.text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private void place(BitmapFont font, String text, UiRichDocument.Run run) {
        measure.setText(font, text);
        float width = measure.width;
        if (!line.isEmpty()) {
            Piece last = line.getLast();
            if (last.font == font && Objects.equals(last.link, run.link()) && Objects.equals(last.color, run.color())
                  && last.start + last.text.length() == index) {
                last.text += text;
                measure.setText(font, last.text);
                lineWidth += measure.width - last.width;
                last.width = measure.width;
                index += text.length();
                return;
            }
        }
        line.add(new Piece(font, text, lineWidth, width, run.color(), run.link(), index));
        lineWidth += width;
        index += text.length();
    }

    /** Ends the line being placed: trims its trailing space, sets its baselines and aligns it. */
    private void endLine(UiRichDocument.Block block, float indent, float width) {
        while (!line.isEmpty() && line.getLast().text.isBlank()) {
            line.removeLast();
        }
        if (!line.isEmpty()) {
            Piece last = line.getLast();
            String trimmed = last.text.stripTrailing();
            if (trimmed.length() < last.text.length()) {
                measure.setText(last.font, trimmed);
                last.text = trimmed;
                last.width = measure.width;
            }
        }
        float ascent = 0;
        float descent = 0;
        float used = 0;
        for (Piece piece : line) {
            ascent = Math.max(ascent, piece.font.getLineHeight() + piece.font.getDescent());
            descent = Math.max(descent, -piece.font.getDescent());
            used = Math.max(used, piece.x + piece.width);
        }
        if (line.isEmpty()) {
            ascent = style.regular.getLineHeight() + style.regular.getDescent();
            descent = -style.regular.getDescent();
        }
        float shift = indent;
        if (width < Float.MAX_VALUE / 2) {
            float room = width - indent;
            if (block.align() == UiRichDocument.Align.CENTER) {
                shift += Math.max(0, (room - used) / 2);
            } else if (block.align() == UiRichDocument.Align.RIGHT) {
                shift += Math.max(0, room - used);
            }
        }
        for (Piece piece : line) {
            piece.x += shift;
            piece.baseline = y + ascent;
            piece.lineTop = y;
            piece.lineBottom = y + ascent + descent;
            pieces.add(piece);
        }
        y += ascent + descent;
        line.clear();
        lineWidth = 0;
    }
}
