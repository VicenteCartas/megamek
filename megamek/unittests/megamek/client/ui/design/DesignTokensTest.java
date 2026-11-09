/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import megamek.client.ui.design.DesignTokens.ContrastCheck;
import megamek.client.ui.design.DesignTokens.DistinctCheck;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Palette;
import megamek.client.ui.design.DesignTokens.Presentation;
import org.junit.jupiter.api.Test;

class DesignTokensTest {
    private static final Pattern ROLE_NAME = Pattern.compile("[a-z]+(\\.[a-z][a-zA-Z]*)+");

    @Test
    void contrastMatchesWcag() {
        assertEquals(21, ColorMath.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
        assertEquals(1, ColorMath.contrast(0xFF777777, 0xFF777777), 1e-9);
        assertEquals(4.48, ColorMath.contrast(0xFF777777, 0xFFFFFFFF), 0.01);
    }

    @Test
    void opacityTokensBecomeAlphaBytes() {
        DesignTokens tokens = DesignTokens.shipped();
        assertEquals(Math.round(tokens.opacity().get("tint.hover") * 255), tokens.alpha("tint.hover"));
        assertTrue(tokens.alpha("tint.pressed") > tokens.alpha("tint.hover"));
        assertThrows(IllegalArgumentException.class, () -> tokens.alpha("no.such"));
    }

    @Test
    void deltaEMatchesTheCiede2000ReferenceData() {
        // Sharma, Wu and Dalal (2005), table 1, pairs 1 and 7.
        assertEquals(2.0425, ColorMath.deltaE2000(new double[] { 50, 2.6772, -79.7751 },
              new double[] { 50, 0, -82.7485 }), 1e-4);
        assertEquals(2.3669, ColorMath.deltaE2000(new double[] { 50, 0, 0 }, new double[] { 50, -1, 2 }), 1e-4);
    }

    @Test
    void colorsParseAndPrintBothForms() {
        assertEquals(0xFF82E2CE, ColorMath.parse("#82E2CE"));
        assertEquals(0x3D9982D3, ColorMath.parse("#9982D33D"));
        assertEquals("#9982D33D", ColorMath.hex(0x3D9982D3));
        assertEquals("#82E2CE", ColorMath.hex(0xFF82E2CE));
        assertEquals(0xFF808080, ColorMath.over(0x80FFFFFF, 0xFF000000));
    }

    @Test
    void greysLookTheSameToEveryone() {
        for (Vision vision : Vision.values()) {
            assertEquals(0, ColorMath.deltaE(0xFF808080, ColorMath.simulate(0xFF808080, vision), Vision.NORMAL), 1.0,
                  vision.name());
        }
    }

    @Test
    void everyTokenIsWellFormedAndEveryCheckNamesARealToken() {
        DesignTokens tokens = DesignTokens.shipped();
        assertFalse(tokens.colors().isEmpty());
        for (String name : tokens.colors().keySet()) {
            assertTrue(ROLE_NAME.matcher(name).matches(), "Role names are group.role: " + name);
            assertFalse(tokens.colorToken(name).use().isBlank(), name + " says what it is for");
        }
        for (ContrastCheck check : tokens.contrastChecks()) {
            check.foregrounds().forEach(tokens::colorToken);
            check.backgrounds().forEach(tokens::colorToken);
            if (check.over() != null) {
                tokens.colorToken(check.over());
            }
        }
        for (DistinctCheck check : tokens.distinctChecks()) {
            check.colors().forEach(tokens::colorToken);
        }
        assertTrue(tokens.typeStyles().containsKey("body"));
    }

    @Test
    void appModeUpperCasesOnlyOverlinesAndGameModeTheFramingLabels() {
        DesignTokens tokens = DesignTokens.shipped();
        for (String role : tokens.textRoles().keySet()) {
            assertEquals(role.equals("overline"), tokens.upperCase(role, Presentation.APP), role + " in app mode");
            if (tokens.upperCase(role, Presentation.APP)) {
                assertTrue(tokens.upperCase(role, Presentation.GAME), role + " is upper case in game mode too");
            }
        }
        for (String role : List.of("display", "headline", "title", "button", "tab")) {
            assertTrue(tokens.upperCase(role, Presentation.GAME), role + " in game mode");
        }
        assertFalse(tokens.typeStyle("body").hasCaps(), "Body text is never upper case");
        assertFalse(tokens.typeStyle("small").hasCaps(), "Table text is never upper case");
        assertTrue(tokens.roleStyle("button").capsTracking() > tokens.roleStyle("button").tracking(),
              "Upper case is tracked wider");
        assertEquals("END TURN", tokens.cased("button", Presentation.GAME, "End turn", Locale.ROOT));
        assertEquals("End turn", tokens.cased("button", Presentation.APP, "End turn", Locale.ROOT));
        assertEquals("<html>End turn", tokens.cased("button", Presentation.GAME, "<html>End turn", Locale.ROOT));
        assertEquals("\u0130PTAL", tokens.cased("button", Presentation.GAME, "iptal", Locale.forLanguageTag("tr")));
        assertThrows(IllegalArgumentException.class, () -> tokens.upperCase("no.such", Presentation.GAME));
    }

    @Test
    void gameModeCondensesTheFramingLabelsAndNamesAndAppModeNothing() {
        DesignTokens tokens = DesignTokens.shipped();
        assertEquals(75, tokens.condensedWidth());
        assertFalse(tokens.widthRule().isBlank());
        assertEquals(List.of("display", "headline", "title", "button", "tab", "overline", "name"),
              List.copyOf(tokens.condensedRoles(Presentation.GAME)));
        assertTrue(tokens.condensedRoles(Presentation.APP).isEmpty(), "App mode is all regular width");
        for (String role : tokens.textRoles().keySet()) {
            assertFalse(tokens.condensed(role, Presentation.APP), role + " in app mode");
            assertEquals(tokens.condensedRoles(Presentation.GAME).contains(role),
                  tokens.condensed(role, Presentation.GAME), role + " in game mode");
            if (tokens.upperCase(role, Presentation.GAME)) {
                assertTrue(tokens.condensed(role, Presentation.GAME), "Upper-case " + role + " is condensed");
            }
        }
        for (String role : tokens.condensedRoles(Presentation.GAME)) {
            assertEquals(500, tokens.roleStyle(role).weight(), role + " fits the one condensed static face");
        }
        assertEquals("body.strong", tokens.textRoles().get("name"));
        for (Presentation presentation : Presentation.values()) {
            assertFalse(tokens.upperCase("name", presentation), "Names keep their case in " + presentation);
        }
        assertEquals(0, tokens.roleStyle("name").tracking(false), "Condensed names keep normal tracking");
        assertThrows(IllegalArgumentException.class, () -> tokens.condensed("no.such", Presentation.GAME));
    }

    @Test
    void everyAccessibilityCheckPassesInBothModesOfBothPalettes() {
        List<String> failures = new ArrayList<>();
        for (Palette palette : Palette.values()) {
            List<TokenChecks.Result> results = TokenChecks.run(DesignTokens.shipped(palette));
            for (TokenChecks.Result result : results) {
                if (!result.passes()) {
                    failures.add(String.format("%s %s %s: %.2f < %.2f%s (%s)", palette, result.mode(),
                          result.subject(), result.value(), result.min(),
                          result.vision() == null ? "" : " for " + result.vision(), result.why()));
                }
            }
            assertTrue(results.stream().anyMatch(result -> result.mode() == Mode.LIGHT));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void theColorBlindPaletteOverridesOnlyTheStatusColors() {
        DesignTokens standard = DesignTokens.shipped(Palette.STANDARD);
        DesignTokens safe = DesignTokens.shipped(Palette.COLORBLIND);
        assertEquals(Palette.COLORBLIND, safe.tokenPalette());
        for (String name : standard.colors().keySet()) {
            boolean differs = standard.colorToken(name).dark() != safe.colorToken(name).dark()
                  || standard.colorToken(name).light() != safe.colorToken(name).light();
            assertEquals(List.of("status.danger", "status.warning", "status.success").contains(name), differs, name);
        }
        for (Mode mode : Mode.values()) {
            assertEquals(standard.color("surface.inverse", mode), safe.color("selected.primary", mode),
                  "Selection is the inverse color in both palettes");
        }
    }

    @Test
    void shippedFollowsTheActivePalette() {
        try {
            DesignTokens.setPalette(Palette.COLORBLIND);
            assertEquals(Palette.COLORBLIND, DesignTokens.shipped().tokenPalette());
        } finally {
            DesignTokens.setPalette(Palette.STANDARD);
        }
        assertEquals(Palette.STANDARD, DesignTokens.shipped().tokenPalette());
    }

    @Test
    void anOverlayAddsTokensRolesAndChecksAndLeavesTheShippedOnes() throws IOException {
        String overlay = """
              {
                "version": "mekhq-1",
                "about": "An app's own roles.",
                "color": {
                  "money.up": { "dark": "#7FD49A", "light": "#1E6B3A", "use": "Income.",
                                "colorblind": { "dark": "#7FB8F0", "light": "#1F5A99" } }
                },
                "space": { "dossier": 28 },
                "typography": {
                  "styles": { "ledger": { "size": 13, "weight": 400, "tracking": 0, "use": "Ledger rows." } },
                  "case": { "roles": { "ledger": "ledger" } }
                },
                "checks": {
                  "contrast": [ { "fg": ["money.up"], "bg": ["surface.panel"], "min": 3, "why": "Money text" } ]
                }
              }""";
        DesignTokens shipped = DesignTokens.shipped(Palette.STANDARD);
        DesignTokens merged = read(overlay, Palette.STANDARD);
        DesignTokens safe = read(overlay, Palette.COLORBLIND);

        assertEquals(0xFF7FD49A, merged.color("money.up", Mode.DARK));
        assertEquals(0xFF7FB8F0, safe.color("money.up", Mode.DARK), "an overlay role has a color-blind value");
        assertEquals(28, merged.space().get("dossier"));
        assertEquals(13, merged.roleStyle("ledger").size());
        assertEquals(shipped.contrastChecks().size() + 1, merged.contrastChecks().size());
        assertEquals(shipped.version(), merged.version(), "the overlay's own version isn't the tokens'");
        for (String name : shipped.colors().keySet()) {
            assertEquals(shipped.colorToken(name), merged.colorToken(name), name);
        }
        assertFalse(DesignTokens.shipped().colors().containsKey("money.up"), "reading adds nothing to the shipped");
        for (TokenChecks.Result result : TokenChecks.run(merged)) {
            if (result.subject().contains("money.up")) {
                assertTrue(result.passes(), result.subject());
            }
        }
    }

    @Test
    void anOverlayExtendsTheUpperCaseAndCondensedRoles() throws IOException {
        DesignTokens merged = read("""
              {
                "typography": {
                  "styles": { "callsign": { "size": 14, "weight": 500, "tracking": 0, "capsTracking": 0.04,
                                            "use": "Callsigns." } },
                  "case": { "roles": { "callsign": "callsign" }, "upper": { "game": ["callsign"] } },
                  "width": { "roles": { "game": ["callsign"] } }
                }
              }""", Palette.STANDARD);
        assertTrue(merged.upperCase("callsign", Presentation.GAME));
        assertFalse(merged.upperCase("callsign", Presentation.APP));
        assertTrue(merged.condensed("callsign", Presentation.GAME));
        assertTrue(merged.upperCase("button", Presentation.GAME), "the shipped upper-case roles stay");
    }

    @Test
    void anOverlayCannotChangeWhatTheFileShips() {
        for (String overlay : List.of(
              "{ \"color\": { \"surface.base\": { \"dark\": \"#000000\", \"light\": \"#FFFFFF\", \"use\": \"x\" } } }",
              "{ \"motion\": { \"easing\": [0, 0, 1, 1] } }",
              "{ \"typography\": { \"family\": \"Comic Sans\" } }",
              "{ \"checks\": { \"visionRule\": \"none\" } }",
              "{ \"theme\": { } }",
              "[ ]")) {
            assertThrows(IllegalArgumentException.class, () -> read(overlay, Palette.STANDARD), overlay);
        }
        assertThrows(IllegalArgumentException.class, () -> read(
              "{ \"typography\": { \"case\": { \"roles\": { \"ledger\": \"no.such.style\" } } } }", Palette.STANDARD),
              "a role names a style the merged tokens have");
    }

    @Test
    void addingABrokenOverlayFailsAsTheAppStartsAndChangesNothing() {
        int colors = DesignTokens.shipped().colors().size();
        assertThrows(IllegalStateException.class,
              () -> DesignTokens.addOverlay(DesignTokensTest.class, "no-such-overlay.json"));
        IllegalArgumentException redefining = assertThrows(IllegalArgumentException.class,
              () -> DesignTokens.addOverlay(DesignTokensTest.class, "redefining-overlay.json"));
        assertTrue(redefining.getMessage().contains("color.surface.base"), redefining.getMessage());
        assertEquals(colors, DesignTokens.shipped().colors().size());
    }

    private static DesignTokens read(String overlay, Palette palette) throws IOException {
        try (InputStream base = DesignTokens.class.getResourceAsStream(DesignTokens.RESOURCE)) {
            return DesignTokens.read(base, palette,
                  List.of(new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
