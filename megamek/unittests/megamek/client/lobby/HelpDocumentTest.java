/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.client.lobby.HelpDocument.Block;
import megamek.client.lobby.HelpDocument.Chapter;
import megamek.client.lobby.HelpDocument.Kind;
import megamek.client.ui.Messages;
import org.junit.jupiter.api.Test;

class HelpDocumentTest {
    private static final String REPORT = """
          <html><head><style>p { }</style></head><body>
          <p class="datetimelog">Log file opened 2026-11-06T22:35:25</p><h1>Abstract Combat Auto Resolution</h1><br>
          <p><div class="blurb"><p>Forces totaling 4 units engaged in combat.</p><p>Team 2 claimed victory.</p></div></p>
          <a name='summary'><h2>&#8251; Summary</h2><br></a></p><ul><li><a href='#round-0' name='summary-round-0'>Round 0<br></a>
          <ul><li><a href='#round-0-movement'>Movement Phase<br></a></li></ul></li></ul>
          <h2>&#9678; Starting Scenario Phase</h2><br>
          Team 1 Formations<br>
          &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;<span style='color:#8686bf; font-weight: bold;'>Vicente</span> has 2 formations<br>
          <hr/><br>
          <a name="round-1"><h2>&#9684; Initiative Phase for Round 1</h2></a><br>
          <a href='#summary-round-1'>back to top</a><br>
          &#9658; Team 1 initiative rolls.<br>
          &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;Vicente rolls 9[8+1 (+1 Base)]...<br>
          <a name="round-1-firing"><h2>&#9685; Ranged Attack Phase</h2></a>
          <a href='#summary-round-1-firing'>back to top</a><br>
          <span style='color:#cc6666; font-weight: bold;'>Marauder (Bot)</span> attacks <span style='color:#8686bf'>Marauder (Vicente)</span>.<br>
          &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;Bot rolls <span class="dice">&#9861; + &#9856;</span> <span class="roll">7 (6,1)</span>...
          &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;The attack hits!<br>
          &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;Marauder (Vicente) received 2 damage<br>
          <a name="end-of-combat"><h2>&#9689; End of Combat</h2></a>
          <a href='#summary-end-of-combat'>back to top</a><br>
          <a name="victory"><h2><i>Team 2 is victorious!</i></h2></a><br>
          <a name="end-team-1"><h2>Team 1 Report:</h2></a>
          <a href='#remaining-0'>back to top</a>
          &ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;&ensp;<a name="end-player-0-remaining"><h3><span style='color:#8686bf'>Vicente</span> has 0 units remaining.</h3></a>
          </body></html>
          """;

    private static HelpDocument report() {
        return HelpDocument.battleReport(REPORT, "Summary", round -> "Round " + round);
    }

    private static List<Block> blocks(HelpDocument document, String chapter) {
        return document.chapters().stream().filter(each -> each.title().equals(chapter)).findFirst()
              .map(Chapter::blocks).orElseThrow(() -> new AssertionError(chapter + " in " + document.titles()));
    }

    private static List<String> texts(List<Block> blocks, Kind kind) {
        return blocks.stream().filter(block -> block.kind() == kind).map(Block::text).toList();
    }

    @Test
    void theReportIsChapteredBySummaryForcesRoundsAndTheEnd() {
        assertEquals(List.of("Summary", "Starting Scenario Phase", "Round 1", "End of Combat"), report().titles());
    }

    @Test
    void theSummaryLeadsWithWhoWonAndLeavesTheTableOfContentsOut() {
        List<Block> summary = blocks(report(), "Summary");
        assertEquals(new Block(Kind.CALLOUT, "Team 2 is victorious!", 0, null), summary.get(0));
        assertEquals(List.of("Forces totaling 4 units engaged in combat.", "Team 2 claimed victory."),
              texts(summary, Kind.PARAGRAPH));
        assertFalse(report().chapters().stream().flatMap(chapter -> chapter.blocks().stream())
              .anyMatch(block -> block.text().contains("Movement Phase") || block.text().contains("Log file")));
    }

    @Test
    void reportLinesKeepTheirIndentAndActorButNotTheBackToTopLinks() {
        List<Block> round = blocks(report(), "Round 1");
        assertEquals(List.of("Initiative Phase for Round 1", "Ranged Attack Phase"), texts(round, Kind.HEADING));
        List<Block> lines = round.stream().filter(block -> block.kind() == Kind.LINE).toList();
        assertEquals(List.of(
              new Block(Kind.LINE, "\u25BA Team 1 initiative rolls.", 0, null),
              new Block(Kind.LINE, "Vicente rolls 9[8+1 (+1 Base)]...", 1, "#8686bf"),
              new Block(Kind.LINE, "Marauder (Bot) attacks Marauder (Vicente).", 0, "#cc6666"),
              new Block(Kind.LINE, "Bot rolls 7 (6,1)...", 1, null),
              new Block(Kind.LINE, "The attack hits!", 1, null),
              new Block(Kind.LINE, "Marauder (Vicente) received 2 damage", 2, null)), lines);
        List<Block> forces = blocks(report(), "Starting Scenario Phase");
        assertEquals(new Block(Kind.LINE, "Vicente has 2 formations", 1, "#8686bf"), forces.get(1));
    }

    @Test
    void theEndOfCombatHasWhoWonAndEachTeamsReport() {
        List<Block> end = blocks(report(), "End of Combat");
        assertEquals(List.of(
              new Block(Kind.CALLOUT, "Team 2 is victorious!", 0, null),
              new Block(Kind.HEADING, "Team 1 Report", 0, null),
              new Block(Kind.SUBHEADING, "Vicente has 0 units remaining.", 0, "#8686bf")), end);
    }

    @Test
    void princessGuideIsChapteredByItsSectionsWithTopicsAndCommands() throws IOException {
        HelpDocument guide = HelpDocument.guide(Files.readString(
              Path.of(Messages.getString("BotConfigDialog.princessHelpPath"))));
        assertEquals(List.of("Who is Princess Bot?", "Princess Bot Configuration", "Chat Commands",
              "Replacing an Absent Player with the Princess Bot", "Princess-Compatible Optional Rules"),
              guide.titles());
        List<Block> configuration = blocks(guide, "Princess Bot Configuration");
        assertTrue(texts(configuration, Kind.HEADING).containsAll(List.of("Behavior", "Aggression", "Herding")));
        assertTrue(texts(configuration, Kind.BULLET).stream().anyMatch(text -> text.startsWith("BERSERK")));

        List<Block> commands = blocks(guide, "Chat Commands");
        int flee = texts(commands, Kind.HEADING).indexOf("Flee");
        assertEquals(0, flee, "the header row is left out and each command is a topic");
        assertTrue(texts(commands, Kind.CODE).contains("princessName: flee : edge#"));
        assertEquals(List.of("0 = north", "1 = south", "2 = west", "3 = east", "4 = nearest edge"),
              texts(commands, Kind.BULLET), "the edges stay with the Flee command");
        assertTrue(texts(commands, Kind.CODE).contains("princessName: caution : <+/->"));

        assertFalse(guide.chapters().stream().flatMap(chapter -> chapter.blocks().stream())
              .anyMatch(block -> block.text().equalsIgnoreCase("Back to Top")
                    || block.text().contains("Table of Contents")), "the navigation is left out");
    }

    @Test
    void theMapSetupHelpOpensWithItsIntroductionUnderItsTitle() throws IOException {
        HelpDocument guide = HelpDocument.guide(Files.readString(
              Path.of(Messages.getString("Lobby.help.mapSetupPath"))));
        assertEquals("Setting Up the Game Map", guide.titles().get(0));
        assertTrue(guide.titles().containsAll(List.of("Stacks and Surprise Boards", "Finding Boards")),
              guide.titles()::toString);
        assertTrue(guide.chapters().stream().flatMap(chapter -> chapter.blocks().stream())
              .filter(block -> block.kind() == Kind.BULLET).count() > 10, "the help's list items read as bullets");
    }

    @Test
    void reportIndentsCountEnSpacesByLevel() {
        assertEquals(0, HelpDocument.indent("Team 1"));
        assertEquals(1, HelpDocument.indent("\n\u2002\u2002\u2002\u2002\u2002\u2002\u2002\u2002x"));
        assertEquals(3, HelpDocument.indent("\u2002".repeat(24) + "x"));
        assertEquals("Team 1 Report", HelpDocument.headingText("\u25D9 Team 1 Report:"));
    }
}
