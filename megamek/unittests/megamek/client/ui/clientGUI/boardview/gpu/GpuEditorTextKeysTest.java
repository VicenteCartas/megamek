/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.LevelSculpt;
import megamek.common.board.BoardEditorBlueprint;
import org.junit.jupiter.api.Test;

/**
 * The 3D editor's text is localisable (b9-decisions.md P): its sources show no English literal directly, every message
 * key they name exists, and the editor's texts are plain text that MessageFormat accepts in every language.
 */
class GpuEditorTextKeysTest {
    private static final Path SOURCES = Path.of("src/megamek/client/ui");
    /** Every text these files show comes from a message key. */
    private static final List<String> EDITOR = List.of("clientGUI/boardview/gpu/GpuBoardEditor.java",
          "clientGUI/boardview/gpu/GpuHexSection.java", "clientGUI/boardview/gpu/GpuMapSource.java",
          "clientGUI/boardview/gpu/GpuMapHud.java", "clientGUI/boardview/gpu/GpuEditorNorth.java",
          "clientGUI/boardview/gpu/GpuEditorModelPreviews.java", "clientGUI/boardview/gpu/GpuEditorTerrainPreviews.java",
          "gdx/UiNumber.java");
    /** These name message keys too; the session's refusals of malformed commands stay developer English. */
    private static final List<String> KEYED = List.of("boardeditor/BoardEditorSession.java",
          "clientGUI/boardview/gpu/BoardEditorTerrain.java", "clientGUI/boardview/gpu/GpuBoardWindow.java");
    /**
     * Literals that read as words but are never shown: the Resize edges' key and actor-name fragments
     * (ExpandMapDialog.labelNorth ...) and a preview image's loaded marker.
     */
    private static final Set<String> IDENTIFIERS = Set.of("North", "East", "South", "West", "Ready");
    /** A message key, a key prefix ("BoardEditor.help.") or a key suffix ("Field.toolTip"). */
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Za-z0-9]*\\.[A-Za-z0-9_.]*");
    private static final Pattern UNICODE = Pattern.compile("\\\\u([0-9A-Fa-f]{4})");
    /** Markup that UIKit labels would draw as text. */
    private static final Pattern MARKUP = Pattern.compile("(?i)<\\s*/?\\s*(html|br|b|u|i|font|hr)\\b");

    /** A string literal outside comments: its source text, the code statement before it, its neighbours and its line. */
    private record Literal(String text, String statement, char before, char after, int line) { }

    @Test
    void editorSourcesShowNoLiteralTextAndNameOnlyExistingKeys() throws IOException {
        Properties english = texts("");
        List<String> problems = new ArrayList<>();
        for (String file : EDITOR) {
            for (Literal literal : literals(Files.readString(SOURCES.resolve(file)))) {
                String problem = keyProblem(literal, english);
                if (problem == null && !english.containsKey(literal.text()) && !KEY.matcher(literal.text()).matches()
                      && readsAsText(literal.text()) && !IDENTIFIERS.contains(literal.text()) && !developerText(literal)) {
                    problem = "shows a literal instead of a message key";
                }
                if (problem != null) { problems.add(file + ":" + literal.line() + " \"" + literal.text() + "\" " + problem); }
            }
        }
        for (String file : KEYED) {
            for (Literal literal : literals(Files.readString(SOURCES.resolve(file)))) {
                String problem = keyProblem(literal, english);
                if (problem != null) { problems.add(file + ":" + literal.line() + " \"" + literal.text() + "\" " + problem); }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The keys the editor builds from a tool, a sculpt mode or an Assets category or section exist for every one of them. */
    @Test
    void everyToolSculptModeAndAssetsSectionHasItsText() throws IOException {
        Properties english = texts("");
        List<String> keys = new ArrayList<>();
        for (BoardEditorSession.Tool tool : BoardEditorSession.Tool.values()) {
            String name = tool.name().toLowerCase(Locale.ROOT);
            keys.addAll(List.of("BoardEditor.tool." + name, "BoardEditor.help." + name));
        }
        for (LevelSculpt.Mode mode : LevelSculpt.Mode.values()) {
            String name = mode.name().toLowerCase(Locale.ROOT);
            keys.addAll(List.of("BoardEditor.sculpt." + name, "BoardEditor.hint." + name));
        }
        // The Assets palette: every category, subcategory and section of the blueprint, and the Theme category's two sections.
        for (var category : BoardEditorBlueprint.get().categories()) {
            keys.add("BoardEditor.palette." + category.id());
            category.subcategories().forEach(subcategory -> keys.add("BoardEditor.palette." + category.id() + "." + subcategory));
            category.sections().forEach(section -> keys.add("BoardEditor.palette." + section.category() + "." + section.id()));
            if (category.themes() != null) {
                keys.addAll(List.of("BoardEditor.palette." + category.id() + ".themes", "BoardEditor.palette." + category.id() + ".special"));
            }
        }
        assertEquals(List.of(), keys.stream().filter(key -> !english.containsKey(key)).distinct().toList());
    }

    /** UIKit labels draw plain text, and the editor formats its texts with MessageFormat, in every language. */
    @Test
    void editorTextsArePlainAndFormatInEveryLanguage() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String language : List.of("", "_de", "_es", "_ru")) {
            Properties texts = texts(language);
            for (String key : texts.stringPropertyNames()) {
                if (!key.startsWith("BoardEditor.") && !key.startsWith("FloodDialog.") && !key.startsWith("LCDialog.")
                      && !key.startsWith("ExpandMapDialog.")) { continue; }
                String value = texts.getProperty(key);
                if (MARKUP.matcher(value).find()) { problems.add("messages" + language + " " + key + " has markup"); }
                try { new MessageFormat(value); }
                catch (IllegalArgumentException broken) { problems.add("messages" + language + " " + key + ": " + broken.getMessage()); }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The scan itself: comments are skipped, and a literal knows its statement and neighbours. */
    @Test
    void theScanSkipsCommentsAndSeesConcatenation() {
        List<Literal> found = literals("""
              // "Not code"
              /* "Neither" */ label("Shown text"); key("Prefix." + id + "Field.suffix");
              throw new IllegalStateException("Developer text");
              char quote = '"'; text("Escaped \\" quote"); join(" \\u00B7 ");
              GpuShaderManager.provider("Shader diagnostic", factory);
              """);
        assertEquals(List.of("Shown text", "Prefix.", "Field.suffix", "Developer text", "Escaped \\\" quote", " · ",
              "Shader diagnostic"),
              found.stream().map(Literal::text).toList());
        assertTrue(readsAsText("Shown text") && readsAsText("Undo") && !readsAsText("editor-tool-select") && !readsAsText(" · "));
        assertEquals('+', found.get(1).after());
        assertEquals('+', found.get(2).before());
        assertTrue(developerText(found.get(3)));
        assertTrue(developerText(found.get(6)));
        assertFalse(developerText(found.getFirst()));
        assertEquals(2, found.getFirst().line());
    }

    /**
     * Null when the literal is not a key, or is one that exists: a key, a prefix of keys (followed by '+' or ending in a
     * dot) or a suffix of keys (after a '+').
     */
    private static String keyProblem(Literal literal, Properties english) {
        String text = literal.text();
        if (!KEY.matcher(text).matches() || english.containsKey(text)) { return null; }
        boolean exists = literal.after() == '+' || text.endsWith(".")
              ? english.stringPropertyNames().stream().anyMatch(key -> key.startsWith(text))
              : literal.before() == '+' && english.stringPropertyNames().stream().anyMatch(key -> key.endsWith(text));
        return exists ? null : "is no message key";
    }

    /** Whether a literal reads as text for a user: a phrase, or a capitalised word. */
    private static boolean readsAsText(String literal) {
        return literal.chars().anyMatch(Character::isLetter)
              && (literal.chars().anyMatch(Character::isWhitespace) || literal.matches("\\p{Lu}\\p{Ll}.*"));
    }

    /** Developer diagnostics, including the shader provider's first argument naming its program. */
    private static boolean developerText(Literal literal) {
        return literal.statement().contains("Exception(") || literal.statement().matches("(?s).*\\b(LOGGER|logger)\\..*")
              || literal.statement().matches("(?s).*\\bGpuShaderManager\\s*\\.\\s*provider\\s*\\(\\s*");
    }

    /** The string literals of {@code source} outside comments, in order. Text blocks are not expected here. */
    private static List<Literal> literals(String source) {
        List<Literal> found = new ArrayList<>();
        // The code of the current statement (since the last ';', '{' or '}', comments and literals blanked) and its
        // last character that is no white space.
        StringBuilder statement = new StringBuilder();
        char last = ' ';
        int line = 1, at = 0;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at);
                at = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2) + 2;
                line += (int) source.substring(at, end).chars().filter(ch -> ch == '\n').count();
                statement.append(' ');
                at = end;
            } else if (c == '\'' || c == '"') {
                assertFalse(source.startsWith("\"\"\"", at), "A text block at line " + line);
                int end = at + 1;
                while (source.charAt(end) != c) { end += source.charAt(end) == '\\' ? 2 : 1; }
                if (c == '"') {
                    int next = end + 1;
                    while (next < source.length() && Character.isWhitespace(source.charAt(next))) { next++; }
                    // Unicode escapes are characters ("·" is a separator, not text).
                    String text = UNICODE.matcher(source.substring(at + 1, end))
                          .replaceAll(escape -> String.valueOf((char) Integer.parseInt(escape.group(1), 16)));
                    found.add(new Literal(text, statement.toString(), last,
                          next < source.length() ? source.charAt(next) : ' ', line));
                }
                statement.append(c).append(c); last = c;
                at = end + 1;
            } else {
                if (c == '\n') { line++; }
                if (c == ';' || c == '{' || c == '}') { statement.setLength(0); } else { statement.append(c); }
                if (!Character.isWhitespace(c)) { last = c; }
                at++;
            }
        }
        return found;
    }

    /** One messages file from the classpath, read as the game reads it (UTF-8, non-ASCII text escaped). */
    private static Properties texts(String language) throws IOException {
        Properties texts = new Properties();
        try (InputStream in = GpuEditorTextKeysTest.class.getResourceAsStream("/megamek/client/messages" + language + ".properties")) {
            assertNotNull(in, "messages" + language + ".properties");
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return texts;
    }
}
