/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every Swing dialog the native lobby opens goes through {@link GpuSceneDialogs}: the lobby's own on its
 * {@link GpuSceneDialogs#parent()}, the lobby services' on the same client frame, and nothing
 * else in the lobby watches the toolkit or opens a dialog of its own. The dialogs the lobby draws itself (a libGDX
 * {@link megamek.client.ui.gdx.UiDialog}) all open through {@link LobbyNativeDialogs}.
 */
class LobbyDialogEntryPointsTest {
    private static final Path GPU = Path.of("src/megamek/client/ui/clientGUI/boardview/gpu");
    private static final Path LOBBY_PANELS = Path.of("src/megamek/client/ui/panels/phaseDisplay/lobby");
    /** A dialog or chooser made in the source, and its first argument. */
    private static final Pattern DIALOG = Pattern.compile(
          "new (?!HelpDialog\\()(\\w*(?:Dialog|Chooser)\\w*)\\(\\s*([\\w.]*(?:\\(\\))?)");
    /** A help dialog takes its owner last, after its title and page. */
    private static final Pattern HELP = Pattern.compile("new HelpDialog\\([^;]*?,\\s*([\\w.]*(?:\\(\\))?)\\)");
    private static final Pattern OPTION_PANE = Pattern.compile("JOptionPane\\.show\\w+\\(\\s*([\\w.]*(?:\\(\\))?)");
    /** The owners a lobby dialog may take: the helper's parent, or the client, whose frame the dialog then uses. */
    private static final List<String> LOBBY_OWNERS = List.of("swingDialogs.parent()", "gui");
    private static final List<String> PROMPT_OWNERS = List.of("frame()", "clientGUI", "clientGUI.getFrame()");
    /** A file chooser's first argument is its folder; it takes its owner as it shows. */
    private static final Pattern FOLDER_CHOOSER = Pattern.compile("new JFileChooser\\(");
    /** The lobby's own libGDX dialogs and their one entry point, which take no Swing owner. */
    private static final List<String> NATIVE = List.of("new UiDialog(", "new LobbyNativeDialogs(");

    @Test
    void theLobbysOwnDialogsUseTheOnePath() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(GPU)) {
            for (Path file : files.filter(LobbyDialogEntryPointsTest::lobbySource).toList()) {
                checked++;
                String source = Files.readString(file);
                String name = file.getFileName().toString();
                owners(name, source, DIALOG, 2, LOBBY_OWNERS, offenders);
                owners(name, source, HELP, 1, LOBBY_OWNERS, offenders);
                owners(name, source, OPTION_PANE, 1, List.of(), offenders);
                if (source.contains("addAWTEventListener")) {
                    offenders.add(name + ": watches the toolkit itself");
                }
            }
        }
        assertTrue(checked > 5, "Lobby sources not found at " + GPU.toAbsolutePath());
        assertEquals(List.of(), offenders);
    }

@Test
    void theLobbysNativeDialogsUseTheOnePath() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(GPU)) {
            for (Path file : files.filter(LobbyDialogEntryPointsTest::lobbySource).toList()) {
                String name = file.getFileName().toString();
                String source = Files.readString(file);
                if (!name.equals("LobbyNativeDialogs.java") && source.contains("new UiDialog(")) {
                    offenders.add(name + ": makes a UiDialog itself");
                }
                if (!name.equals("GpuLobbyScreen.java") && source.contains("new LobbyNativeDialogs(")) {
                    offenders.add(name + ": makes a second native dialog entry point");
                }
            }
        }
        assertEquals(List.of(), offenders);
        String window = Files.readString(GPU.resolve("GpuLobbyWindow.java"));
        // The camo picker, player settings and bot setup are drawn by the lobby now; their Swing dialogs stay for
        // other screens
        assertFalse(window.contains("new CamoChooserDialog("));
        assertFalse(window.contains("new PortraitChooserDialog("));
        assertFalse(window.contains("new PlayerSettingsDialog("));
        assertFalse(window.contains("new BotConfigDialog("));
        assertFalse(window.contains("doAlertDialog("));
        // The planetary conditions and game options too; the window's own command opens them natively
        assertFalse(window.contains("new PlanetaryConditionsDialog("));
        assertFalse(Files.readString(GPU.resolve("GpuAtmosphereControls.java")).contains("PlanetaryConditionsDialog"),
              "the Tuning's conditions open as the window's native card");
        assertFalse(window.contains("new GameOptionsDialog("));
        assertFalse(window.contains("new AdvancedSearchMapDialog("), "the map setup's drawer filters the boards");
        String screen = Files.readString(GPU.resolve("GpuLobbyScreen.java"));
        assertTrue(screen.contains("nativeDialogs.camo("));
        assertTrue(screen.contains("nativeDialogs.portrait("));
        assertTrue(screen.contains("nativeDialogs.playerSettings("));
        assertTrue(screen.contains("nativeDialogs.botSetup("));
        assertTrue(screen.contains("nativeDialogs.conditions("));
        assertTrue(screen.contains("nativeDialogs.gameOptions("));
        assertTrue(screen.contains("nativeDialogs.message("));
        // The unit menu's facing, lance role, sprite camo and stats are drawn by the lobby too
        assertFalse(window.contains("SwingLobbyUnitActions"));
        assertTrue(window.contains("new LobbyUnitActions("));
        assertTrue(window.contains("dialogs.facing("));
        assertTrue(window.contains("dialogs.lanceRole("));
        String tools = Files.readString(GPU.resolve("GpuLobbyTools.java"));
        assertFalse(tools.contains("SwingLobbyPrompts"));
        assertTrue(tools.contains("dialogs.alphaStrikeStats("));
        assertTrue(tools.contains("dialogs.sbfStats("));
    }

    @Test
    void theLobbyWindowInstallsTheHelper() throws IOException {
        String window = Files.readString(GPU.resolve("GpuLobbyWindow.java"));
        assertTrue(window.contains("new GpuSceneDialogs("));
        assertTrue(window.contains("swingDialogs.install()"));
        assertTrue(window.contains("swingDialogs.uninstall()"));
        assertTrue(window.contains("swingDialogs.presented()"));
    }

    @Test
    void theServicesDialogsUseTheClientFrame() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String name : List.of("LobbyServices.java")) {
            String source = Files.readString(LOBBY_PANELS.resolve(name));
            owners(name, FOLDER_CHOOSER.matcher(source).replaceAll("new FolderChooser(frame(), "), DIALOG, 2,
                  PROMPT_OWNERS, offenders);
            owners(name, source, OPTION_PANE, 1, PROMPT_OWNERS, offenders);
        }
        // The lobby scene's failure shows after the window is gone, so its message stands on its own
        assertEquals(List.of("LobbyServices.java: JOptionPane.showMessageDialog(null"), offenders);
    }

    private static boolean lobbySource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") && (name.startsWith("Lobby") || name.startsWith("GpuLobby"));
    }

    private static void owners(String name, String source, Pattern pattern, int group, List<String> allowed,
          List<String> offenders) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String owner = matcher.group(group).trim();
            String made = matcher.group();
            if (!allowed.contains(owner) && !made.startsWith("new GpuSceneDialogs(")
                  && NATIVE.stream().noneMatch(made::startsWith)) {
                offenders.add(name + ": " + matcher.group().trim());
            }
        }
    }
}
