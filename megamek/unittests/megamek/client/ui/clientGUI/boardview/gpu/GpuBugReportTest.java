/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import megamek.client.ui.PackageBugReportAction;
import megamek.common.util.BugReportBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The native bug report card's reading of the Swing dialog's HTML, and the packaging result's text. */
class GpuBugReportTest {

    @Test
    void theGuideReadsTheIntroTheNumberedStepsTheHeadingAndThePlaces() {
        GpuBugReport.Guide guide = GpuBugReport.Guide.of(
              "Things go wrong. Follow these steps:</p><blockquote><b> 1)</b> Press <i>Report</i> now.<br>"
                    + "<b>2)</b> Open an issue.<br/><br></blockquote><p>Thanks!",
              "<h3 style=\"text-align:center\">Which Bug Goes Where?</h3> <ul> <li>A battle: <b>MegaMek</b> "
                    + "<li>A unit: <b>MegaMekLab</b></ul>");
        assertEquals("Things go wrong. Follow these steps:", guide.intro());
        assertEquals(List.of("Press Report now.", "Open an issue."), guide.steps());
        assertEquals("Which Bug Goes Where?", guide.where());
        assertEquals(List.of("A battle: MegaMek", "A unit: MegaMekLab"), guide.places());
    }

    @Test
    void htmlWithoutStepsOrListGivesOnlyTheIntro() {
        GpuBugReport.Guide guide = GpuBugReport.Guide.of("<p>Just a note &amp; more</p>", "");
        assertEquals("Just a note & more", guide.intro());
        assertEquals(List.of(), guide.steps());
        assertEquals("", guide.where());
        assertEquals(List.of(), guide.places());
    }

    @ParameterizedTest
    @ValueSource(strings = { "BugReport.properties", "BugReport_de.properties" })
    void eachShippedGuideHasAnIntroStepsAHeadingAndFivePlaces(String file) throws Exception {
        Properties texts = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
              new FileInputStream(new File("resources/megamek/client", file)), StandardCharsets.UTF_8)) {
            texts.load(reader);
        }
        GpuBugReport.Guide guide = GpuBugReport.Guide.of(texts.getProperty("mainText"),
              texts.getProperty("secondaryText"));
        assertFalse(guide.intro().isBlank(), file);
        assertFalse(guide.intro().contains("<"), file + ": no markup left");
        assertTrue(guide.steps().size() >= 3, file + ": " + guide.steps());
        guide.steps().forEach(step -> assertFalse(step.matches("^\\d+\\).*"), file + ": numbers stripped, " + step));
        assertFalse(guide.where().isBlank(), file);
        assertEquals(5, guide.places().size(), file + ": " + guide.places());
    }

    @Test
    void theResultTextListsTheCautionTheEntriesAndTheSkippedOnes() {
        BugReportBundle.BundleResult result = new BugReportBundle.BundleResult(new File("report.zip"),
              List.of("megamek.log", "game.sav.gz"), List.of("huge.log"), 10);
        String text = PackageBugReportAction.resultText(result, "Careful");
        assertTrue(text.startsWith("Careful\n\n"), text);
        assertTrue(text.contains("report.zip"), text);
        assertTrue(text.contains("\n  \u2022 megamek.log\n  \u2022 game.sav.gz"), text);
        assertTrue(text.contains("\n  \u2022 huge.log"), text);
        assertFalse(text.contains("<"), "plain text: " + text);

        String clean = PackageBugReportAction.resultText(new BugReportBundle.BundleResult(new File("report.zip"),
              List.of("megamek.log"), List.of(), 1), null);
        assertFalse(clean.contains("huge.log"), clean);
        assertFalse(clean.startsWith("\n"), clean);
    }
}
