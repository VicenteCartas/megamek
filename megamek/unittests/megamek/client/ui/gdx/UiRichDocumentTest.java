/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.graphics.Color;
import megamek.client.ui.gdx.UiRichDocument.Align;
import megamek.client.ui.gdx.UiRichDocument.Block;
import megamek.client.ui.gdx.UiRichDocument.Kind;
import megamek.client.ui.gdx.UiRichDocument.Run;
import org.junit.jupiter.api.Test;

class UiRichDocumentTest {
    @Test
    void plainTextIsOneParagraph() {
        List<Block> blocks = UiRichDocument.parse("  Hello\n   commander  ").blocks();
        assertEquals(1, blocks.size());
        assertEquals(Kind.PARAGRAPH, blocks.getFirst().kind());
        assertEquals("Hello commander", blocks.getFirst().text());
    }

    @Test
    void nullAndEmptyReadAsNothing() {
        assertTrue(UiRichDocument.parse(null).blocks().isEmpty());
        assertTrue(UiRichDocument.parse("<html><p> </p></html>").blocks().isEmpty());
    }

    @Test
    void paragraphsAndBreaks() {
        List<Block> blocks = UiRichDocument.parse("<html><p>One<br>two</p><p>Three</p></html>").blocks();
        assertEquals(2, blocks.size());
        assertEquals("One\ntwo", blocks.get(0).text());
        assertEquals("Three", blocks.get(1).text());
    }

    @Test
    void boldItalicAndNesting() {
        List<Run> runs = UiRichDocument.parse("a <b>bold <i>both</i></b> <em>slant</em>").blocks().getFirst().runs();
        assertEquals(List.of("a ", "bold ", "both", " ", "slant"), runs.stream().map(Run::text).toList());
        assertTrue(runs.get(1).bold());
        assertFalse(runs.get(1).italic());
        assertTrue(runs.get(2).bold());
        assertTrue(runs.get(2).italic());
        assertFalse(runs.get(4).bold());
        assertTrue(runs.get(4).italic());
    }

    @Test
    void linksKeepTheirHref() {
        Run link = UiRichDocument.parse("See <a href='GLOSSARY:MORALE'>morale</a>.").blocks().getFirst().runs().get(1);
        assertEquals("morale", link.text());
        assertEquals("GLOSSARY:MORALE", link.link());
    }

    @Test
    void colorsFromSpansAndFonts() {
        List<Run> runs = UiRichDocument.parse("<span color='#ff0000'>red</span><font color=\"0000FF\">blue</font>"
              + "<span style='color: #0f0'>green</span>").blocks().getFirst().runs();
        assertEquals(Color.RED, runs.get(0).color());
        assertEquals(Color.BLUE, runs.get(1).color());
        assertEquals(new Color(0, 1, 0, 1), runs.get(2).color());
    }

    @Test
    void headingsListsAndRules() {
        List<Block> blocks = UiRichDocument.parse("<h2>Report</h2><ul><li>One</li><li>Two<ol><li>Deep</li></ol></li>"
              + "</ul><hr><p>End</p>").blocks();
        assertEquals(Kind.HEADING, blocks.get(0).kind());
        assertEquals(2, blocks.get(0).level());
        assertTrue(blocks.get(0).runs().getFirst().bold());
        assertEquals("\u2022", blocks.get(1).marker());
        assertEquals(1, blocks.get(1).depth());
        assertEquals("Two", blocks.get(2).text());
        assertEquals("1.", blocks.get(3).marker());
        assertEquals(2, blocks.get(3).depth());
        assertEquals(Kind.RULE, blocks.get(4).kind());
        assertEquals("End", blocks.get(5).text());
    }

    @Test
    void alignment() {
        List<Block> blocks = UiRichDocument.parse("<center>Mid</center><div style='text-align: right'>Right</div>"
              + "<p align=center>Also</p><p>Left</p>").blocks();
        assertEquals(List.of(Align.CENTER, Align.RIGHT, Align.CENTER, Align.LEFT),
              blocks.stream().map(Block::align).toList());
    }

    @Test
    void entitiesAndStrayMarkup() {
        Block block = UiRichDocument.parse("A &amp; B &lt;3 &#8212; &#x41; &bogus; 1 < 2</b>").blocks().getFirst();
        assertEquals("A & B <3 \u2014 A &bogus; 1 < 2", block.text());
        assertNull(block.runs().getFirst().color());
    }

    @Test
    void headAndCommentsAreSkipped() {
        assertEquals("Body", UiRichDocument.parse("<html><head><title>T</title></head><!-- note --><body>Body</body>")
              .plainText());
    }

    @Test
    void lengthCountsMarkersRulesAndText() {
        UiRichDocument document = UiRichDocument.parse("<ul><li>ab</li></ul><hr>c<br>d");
        // Marker, "ab", rule, "c", break, "d"
        assertEquals(1 + 2 + 1 + 1 + 1 + 1, document.length());
    }
}
