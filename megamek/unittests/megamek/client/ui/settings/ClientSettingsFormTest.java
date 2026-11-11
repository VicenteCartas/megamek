/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.PlayerColour;
import megamek.common.equipment.SensorFamily;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSettingsFormTest {
    private static final String SENSORS = String.join(",", SensorFamily.defaultOrder().stream().map(Enum::name).toList());

    private PreferenceStore store;
    private ClientPreferences client;
    private final List<String> heard = new ArrayList<>();
    /** The bindings as they were before the test, which writes into the shared commands. */
    private final Map<KeyCommandBind, int[]> binds = new EnumMap<>(KeyCommandBind.class);

    @BeforeEach
    void setUp() {
        for (KeyCommandBind bind : KeyCommandBind.values()) {
            binds.put(bind, new int[] { bind.key, bind.modifiers });
            bind.key = bind.keyDefault;
            bind.modifiers = bind.modifiersDefault;
        }
        store = new PreferenceStore();
        store.setDefault(GUIPreferences.MASTER_VOLUME, 80);
        store.setDefault(GUIPreferences.SOUND_MUTE_CHAT, true);
        store.setDefault(GUIPreferences.SOUND_MUTE_MY_TURN, false);
        store.setDefault(GUIPreferences.SOUND_MUTE_OTHERS_TURN, true);
        store.setDefault(GUIPreferences.SOUND_PROMPT_SUPPRESS, false);
        store.setDefault(GUIPreferences.SOUND_BING_FILENAME_CHAT, "chat.wav");
        store.setDefault(GUIPreferences.SOUND_BING_FILENAME_MY_TURN, "turn.wav");
        store.setDefault(GUIPreferences.SOUND_BING_FILENAME_OTHERS_TURN, "others.wav");
        store.setDefault(GUIPreferences.GUI_SCALE, 1f);
        store.setDefault(GUIPreferences.BOARD_UI_SCALE, 1f);
        store.setDefault(GUIPreferences.COLOR_BLIND_PALETTE, false);
        store.setDefault(GUIPreferences.REDUCE_MOTION, false);
        store.setDefault(GUIPreferences.ADVANCED_KEY_REPEAT_DELAY, 0);
        store.setDefault(GUIPreferences.ADVANCED_KEY_REPEAT_RATE, 20);
        store.setDefault(GUIPreferences.TOOLTIP_DELAY, 1000);
        store.setDefault(GUIPreferences.ADVANCED_NO_SAVE_NAG, false);
        store.setDefault(GUIPreferences.NAG_FOR_PSR, true);
        store.setDefault(GUIPreferences.SENSOR_PREFERENCE_ORDER, SENSORS);
        store.setDefault(GUIPreferences.MY_UNIT_COLOR, "255 0 0");
        store.setDefault(GUIPreferences.BOARD_ECM_TRANSPARENCY, 128);
        store.setDefault(GUIPreferences.TOAST_ENABLED, true);
        store.setDefault(GUIPreferences.TOAST_REPORT_EVENTS, true);
        store.setDefault(GUIPreferences.TOAST_DURATION_SECONDS, 3);
        store.setDefault(GUIPreferences.TOAST_DRIP_SECONDS, 2);
        store.setDefault(GUIPreferences.USE_CAMO_OVERLAY, true);
        store.addPreferenceChangeListener(event -> heard.add(event.getName()));
        client = new ClientPreferences(new PreferenceStore());
    }

    @AfterEach
    void tearDown() {
        binds.forEach((bind, key) -> {
            bind.key = key[0];
            bind.modifiers = key[1];
        });
    }

    @Test
    void aMutePreferenceShowsAsAPlaySwitch() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        assertFalse(form.bool(GUIPreferences.SOUND_MUTE_CHAT));
        assertTrue(form.bool(GUIPreferences.SOUND_MUTE_MY_TURN));

        form.bool(GUIPreferences.SOUND_MUTE_CHAT, true);
        form.apply().run();

        assertFalse(store.getBoolean(GUIPreferences.SOUND_MUTE_CHAT));
    }

    @Test
    void theVolumeStaysWithinItsSlider() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.text(GUIPreferences.MASTER_VOLUME, "250");
        assertEquals("100", form.text(GUIPreferences.MASTER_VOLUME));
        form.text(GUIPreferences.MASTER_VOLUME, "-4");
        assertEquals("0", form.text(GUIPreferences.MASTER_VOLUME));
        form.text(GUIPreferences.MASTER_VOLUME, "loud");
        assertEquals("0", form.text(GUIPreferences.MASTER_VOLUME));
    }

    @Test
    void applyWritesOnlyWhatChangedAndRevertPutsItBack() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.text(GUIPreferences.MASTER_VOLUME, "35");
        form.text(GUIPreferences.SOUND_BING_FILENAME_CHAT, "mine.wav");
        assertTrue(form.changed(GUIPreferences.MASTER_VOLUME));
        form.apply().run();

        assertEquals(List.of(GUIPreferences.MASTER_VOLUME, GUIPreferences.SOUND_BING_FILENAME_CHAT).stream().sorted()
              .toList(), heard.stream().sorted().toList());
        assertEquals(35, store.getInt(GUIPreferences.MASTER_VOLUME));
        assertEquals("mine.wav", store.getString(GUIPreferences.SOUND_BING_FILENAME_CHAT));

        heard.clear();
        form.apply().run();
        assertTrue(heard.isEmpty(), "values the preferences hold are not written again");

        form.revert().run();
        assertEquals(80, store.getInt(GUIPreferences.MASTER_VOLUME));
        assertEquals("chat.wav", store.getString(GUIPreferences.SOUND_BING_FILENAME_CHAT));
    }

    @Test
    void defaultsGoBackToTheShippedValuesPageByPage() {
        store.setValue(GUIPreferences.MASTER_VOLUME, 20);
        store.setValue(GUIPreferences.SOUND_MUTE_MY_TURN, true);
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        SettingsForm.Page audio = form.page(ClientSettingsForm.AUDIO);
        assertNotNull(audio);

        assertTrue(form.customized(audio));
        assertTrue(form.customized(GUIPreferences.MASTER_VOLUME));
        assertFalse(form.customized(GUIPreferences.SOUND_MUTE_CHAT));

        form.defaults(audio);

        assertFalse(form.customized(audio));
        assertEquals("80", form.text(GUIPreferences.MASTER_VOLUME));
        assertTrue(form.bool(GUIPreferences.SOUND_MUTE_MY_TURN));
        assertEquals(20, store.getInt(GUIPreferences.MASTER_VOLUME), "nothing is written before apply");
    }

    @Test
    void anOldRouteOpensItsPageOnceNative() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        assertEquals(ClientSettingsForm.AUDIO, form.pageOf(CommonSettingsPane.SETTINGS_AUDIO));
        assertEquals(ClientSettingsForm.DISPLAY, form.pageOf(CommonSettingsPane.SETTINGS_MAIN));
        assertEquals(ClientSettingsForm.KEYBOARD, form.pageOf(CommonSettingsPane.SETTINGS_KEYBINDS));
        assertEquals(ClientSettingsForm.BOARD, form.pageOf(CommonSettingsPane.SETTINGS_GAMEBOARD));
        assertEquals(ClientSettingsForm.TRACE, form.pageOf(CommonSettingsPane.SETTINGS_OVERLAYS));
        assertNull(form.pageOf(null));
    }

    @Test
    void searchFindsTheAudioPage() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.search("volume");
        assertFalse(form.visiblePages().isEmpty());
        form.search("zzzz-nothing");
        assertTrue(form.visiblePages().isEmpty());
    }

    @Test
    void aScaleShowsAsAPercentageOnItsSteps() {
        store.setValue(GUIPreferences.GUI_SCALE, 1.5f);
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        assertEquals("150", form.text(GUIPreferences.GUI_SCALE));
        assertEquals("100", form.text(GUIPreferences.BOARD_UI_SCALE));

        form.text(GUIPreferences.GUI_SCALE, "123");
        assertEquals("120", form.text(GUIPreferences.GUI_SCALE), "a scale snaps to its slider's steps");
        form.text(GUIPreferences.BOARD_UI_SCALE, "20");
        assertEquals("50", form.text(GUIPreferences.BOARD_UI_SCALE));
        form.apply().run();

        assertEquals(1.2f, store.getFloat(GUIPreferences.GUI_SCALE), 1e-6);
        assertEquals(0.5f, store.getFloat(GUIPreferences.BOARD_UI_SCALE), 1e-6);
        assertTrue(form.customized(GUIPreferences.GUI_SCALE));

        form.defaults(form.page(ClientSettingsForm.DISPLAY));
        assertEquals("100", form.text(GUIPreferences.GUI_SCALE));
    }

    @Test
    void theLanguageIsOneMegaMekHasTextsIn() {
        client.setLocale("en_US");
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        assertEquals("en", form.text(ClientPreferences.LOCALE), "a country reads as its language");
        assertFalse(form.customized(ClientPreferences.LOCALE));
        form.text(ClientPreferences.LOCALE, "fr");
        assertEquals("en", form.text(ClientPreferences.LOCALE), "a language without texts is not taken");

        form.text(ClientPreferences.LOCALE, "de");
        form.apply().run();
        assertEquals("de", client.getLocaleString());
        assertEquals("de", client.getLocale().getLanguage(), "the client preferences hand out the new locale");

        form.revert().run();
        assertEquals("en", client.getLocale().getLanguage());
    }

    @Test
    void theAccessibilitySwitchesWriteTheirPreferences() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.bool(GUIPreferences.COLOR_BLIND_PALETTE, true);
        form.bool(GUIPreferences.REDUCE_MOTION, true);
        form.apply().run();

        assertTrue(store.getBoolean(GUIPreferences.COLOR_BLIND_PALETTE));
        assertTrue(store.getBoolean(GUIPreferences.REDUCE_MOTION));
        form.search("blind");
        assertEquals(List.of(ClientSettingsForm.DISPLAY), form.visiblePages().stream().map(SettingsForm.Page::id)
              .toList());
    }

    @Test
    void everyCommandHasItsPlaceOnce() {
        assertTrue(ClientSettingsForm.unlisted().isEmpty(), () -> "not on a Controls page: "
              + ClientSettingsForm.unlisted());
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        List<String> rows = form.pages().stream().flatMap(page -> page.sections().stream())
              .flatMap(section -> section.rows().stream()).map(SettingsForm.Row::name)
              .filter(name -> name.startsWith(ClientSettingsForm.BIND)).toList();

        for (KeyCommandBind bind : KeyCommandBind.values()) {
            long times = rows.stream().filter((ClientSettingsForm.BIND + bind.cmd)::equals).count();
            assertEquals(ClientSettingsForm.DROPPED_BINDS.contains(bind) ? 0 : 1, times, bind.cmd);
        }
    }

    @Test
    void theShippedBindingsHaveNoProblem() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        for (KeyCommandBind bind : KeyCommandBind.values()) {
            if (!ClientSettingsForm.DROPPED_BINDS.contains(bind)) {
                assertNull(form.error(ClientSettingsForm.BIND + bind.cmd), bind.cmd);
            }
        }
    }

    @Test
    void aBindingIsWrittenAndPutBackAndTheBindingsAreStoredOnce() {
        AtomicInteger stored = new AtomicInteger();
        ClientSettingsForm form = new ClientSettingsForm(store, client, stored::incrementAndGet);
        String name = ClientSettingsForm.BIND + KeyCommandBind.FIRE.cmd;
        String shipped = SettingsForm.key(KeyCommandBind.FIRE.keyDefault, KeyCommandBind.FIRE.modifiersDefault);

        assertEquals(shipped, form.text(name));
        assertEquals(shipped, form.defaultText(name));
        form.text(name, SettingsForm.key(KeyEvent.VK_F7, InputEvent.SHIFT_DOWN_MASK));
        assertTrue(form.changed(name));
        form.apply().run();

        assertEquals(KeyEvent.VK_F7, KeyCommandBind.FIRE.key);
        assertEquals(InputEvent.SHIFT_DOWN_MASK, KeyCommandBind.FIRE.modifiers);
        assertEquals(1, stored.get());
        form.apply().run();
        assertEquals(1, stored.get(), "bindings already held are not stored again");
        form.bool(GUIPreferences.REDUCE_MOTION, true);
        form.apply().run();
        assertEquals(1, stored.get(), "only a changed binding stores them");

        form.revert().run();
        assertEquals(KeyCommandBind.FIRE.keyDefault, KeyCommandBind.FIRE.key);
        assertEquals(2, stored.get());
    }

    @Test
    void anUnboundCommandReadsAsNoKey() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        String name = ClientSettingsForm.BIND + KeyCommandBind.FIRE.cmd;

        form.text(name, SettingsForm.key(0, InputEvent.CTRL_DOWN_MASK));
        assertEquals("0,0", form.text(name));
        assertNull(form.error(name));
        form.apply().run();
        assertEquals(0, KeyCommandBind.FIRE.key);
        assertTrue(form.customized(name));
    }

    @Test
    void aKeyAnExclusiveCommandHasIsAClash() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        String fire = ClientSettingsForm.BIND + KeyCommandBind.FIRE.cmd;
        KeyCommandBind exclusive = List.of(KeyCommandBind.values()).stream()
              .filter(bind -> (bind.isExclusive || bind.isMenuBar) && bind != KeyCommandBind.FIRE && bind.key != 0
                    && !ClientSettingsForm.DROPPED_BINDS.contains(bind))
              .findFirst().orElseThrow();

        form.text(fire, SettingsForm.key(exclusive.key, exclusive.modifiers));

        String problem = form.error(fire);
        assertNotNull(problem);
        assertTrue(problem.contains(megamek.client.ui.Messages.getString("KeyBinds.cmdNames." + exclusive.cmd)),
              problem);
        assertNotNull(form.error(ClientSettingsForm.BIND + exclusive.cmd), "both of them show it");
    }

    @Test
    void copyAndPasteStayTheirs() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        String fire = ClientSettingsForm.BIND + KeyCommandBind.FIRE.cmd;

        form.text(fire, SettingsForm.key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        assertNotNull(form.error(fire));
        form.text(fire, SettingsForm.key(KeyEvent.VK_V, InputEvent.ALT_DOWN_MASK));
        assertNull(form.error(fire));
    }

    @Test
    void theKeyboardPageSetsTheRepeatAndTheTooltips() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        assertEquals("1000", form.text(GUIPreferences.TOOLTIP_DELAY));
        form.text(GUIPreferences.TOOLTIP_DELAY, "240");
        form.text(GUIPreferences.ADVANCED_KEY_REPEAT_RATE, "35");
        form.apply().run();

        assertEquals(200, store.getInt(GUIPreferences.TOOLTIP_DELAY), "the delay snaps to its slider's steps");
        assertEquals(35, store.getInt(GUIPreferences.ADVANCED_KEY_REPEAT_RATE));
        form.search("hotkeys");
        assertTrue(form.visiblePages().stream().allMatch(page -> ClientSettingsForm.CONTROLS.equals(page.categoryId())));
    }

    @Test
    void theGameplayPagesAreThere() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        for (String page : List.of(ClientSettingsForm.WARNINGS, ClientSettingsForm.AUTOMATION, ClientSettingsForm.BOTS,
              ClientSettingsForm.UNITS)) {
            assertNotNull(form.page(page), page);
        }
        assertEquals(ClientSettingsForm.BOTS, form.pageOf(CommonSettingsPane.SETTINGS_AIDISPLAY));
        assertTrue(form.bool(GUIPreferences.NAG_FOR_PSR));
    }

    @Test
    void aRankingKeepsItsKnownIdsAndGainsTheMissingOnes() {
        assertEquals("B,A,C", ClientSettingsForm.order("B, nothing,B,A", List.of("A", "B", "C")));
        assertEquals("A,B,C", ClientSettingsForm.order(null, List.of("A", "B", "C")));
    }

    @Test
    void theSensorRankingIsWrittenAsItsIds() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        List<String> ids = SensorFamily.defaultOrder().stream().map(Enum::name).toList();
        assertEquals(SENSORS, form.text(GUIPreferences.SENSOR_PREFERENCE_ORDER));

        String last = ids.getLast();
        form.text(GUIPreferences.SENSOR_PREFERENCE_ORDER, last);
        String expected = ClientSettingsForm.order(last, ids);
        assertEquals(expected, form.text(GUIPreferences.SENSOR_PREFERENCE_ORDER));
        assertTrue(expected.startsWith(last + ","));
        form.apply().run();

        assertEquals(expected, store.getString(GUIPreferences.SENSOR_PREFERENCE_ORDER));
    }

    @Test
    void theAskingSwitchesThatSayNoAreTurnedAround() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        assertTrue(form.bool(GUIPreferences.ADVANCED_NO_SAVE_NAG), "the save offer is on while its no-nag is off");
        form.bool(GUIPreferences.ADVANCED_NO_SAVE_NAG, false);
        assertEquals(!client.defaultAutoEjectDisabled(), form.bool(ClientPreferences.DEFAULT_AUTO_EJECT_DISABLED));
        form.bool(ClientPreferences.DEFAULT_AUTO_EJECT_DISABLED, false);
        form.apply().run();

        assertTrue(store.getBoolean(GUIPreferences.ADVANCED_NO_SAVE_NAG));
        assertTrue(client.defaultAutoEjectDisabled());
    }

    @Test
    void theProtoMekCodesStartOnALetterOfAnAlphabet() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.text(ClientPreferences.UNIT_START_CHAR, "945");
        form.apply().run();

        assertEquals('\u03B1', client.getUnitStartChar());
    }

    @Test
    void theBattlefieldAndColourPagesAreThere() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        for (String page : List.of(ClientSettingsForm.BOARD, ClientSettingsForm.LABELS, ClientSettingsForm.MARKS,
              ClientSettingsForm.TRACE, ClientSettingsForm.PALETTE, ClientSettingsForm.FORCES,
              ClientSettingsForm.TACTICAL, ClientSettingsForm.MAP_TEXT)) {
            assertNotNull(form.page(page), page);
        }
        SettingsForm.Page palette = form.page(ClientSettingsForm.PALETTE);
        SettingsForm.Section players = palette.sections().getFirst();
        assertEquals(PlayerColour.values().length, players.rows().size());
        assertTrue(players.rows().stream().allMatch(row -> row.kind() == SettingsForm.Kind.COLOR));
        assertEquals(GUIPreferences.USE_CAMO_OVERLAY, palette.sections().getLast().rows().getFirst().name());
    }

    @Test
    void theMessagesAndFilesPagesAreThereAndTheOldRoutesFindThem() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        for (String page : List.of(ClientSettingsForm.NOTIFICATIONS, ClientSettingsForm.REPORTS,
              ClientSettingsForm.LOGS, ClientSettingsForm.FOLDERS)) {
            assertNotNull(form.page(page), page);
        }
        assertEquals(ClientSettingsForm.REPORTS, form.pageOf(CommonSettingsPane.SETTINGS_REPORT));
        assertEquals(ClientSettingsForm.LOGS, form.pageOf(CommonSettingsPane.SETTINGS_ADVANCED));
        assertEquals(ClientSettingsForm.AUTOMATION, form.pageOf(CommonSettingsPane.SETTINGS_AUTODISPLAY));
    }

    @Test
    void aToastTimingIsSetOnlyWhileItsToastsShow() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        form.bool(GUIPreferences.TOAST_ENABLED, true);
        form.bool(GUIPreferences.TOAST_REPORT_EVENTS, true);
        assertTrue(form.editable(GUIPreferences.TOAST_DURATION_SECONDS));
        assertTrue(form.editable(GUIPreferences.TOAST_DRIP_SECONDS));

        form.bool(GUIPreferences.TOAST_REPORT_EVENTS, false);
        assertTrue(form.editable(GUIPreferences.TOAST_DURATION_SECONDS));
        assertFalse(form.editable(GUIPreferences.TOAST_DRIP_SECONDS), "no reports drip in");

        form.bool(GUIPreferences.TOAST_ENABLED, false);
        assertFalse(form.editable(GUIPreferences.TOAST_DURATION_SECONDS));
        assertFalse(form.editable(GUIPreferences.TOAST_REPORT_EVENTS));
    }

    @Test
    void aPhraseListIsKeptOnePhraseALineWithoutBlanksOrRepeats() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.text(ClientPreferences.REPORT_KEYWORDS, " Hit \n\nFalls\nHit\n  \nPilot Skill ");
        assertEquals("Hit\nFalls\nPilot Skill", form.text(ClientPreferences.REPORT_KEYWORDS));
        form.apply().run();

        assertEquals("Hit\nFalls\nPilot Skill", client.getReportKeywords());
        assertEquals(List.of("Hit", "Falls"), SettingsForm.lines("Hit\r\nFalls\r\n"));
        assertEquals(List.of(), SettingsForm.lines(null));
    }

    @Test
    void aStampFormatThatIsNoPatternIsNotWritten() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        String kept = client.getStampFormat();
        assertNotNull(ClientSettingsForm.stamp(kept));

        form.text(ClientPreferences.STAMP_FORMAT, "{broken");
        assertNotNull(form.error(ClientPreferences.STAMP_FORMAT));
        form.text(ClientPreferences.GAME_LOG_FILENAME, "  ");
        assertNotNull(form.error(ClientPreferences.GAME_LOG_FILENAME));
        form.apply().run();

        assertEquals(kept, client.getStampFormat());
        assertEquals("gamelog.html", client.getGameLogFilename());
        assertNull(ClientSettingsForm.stamp("{broken"));
    }

    @Test
    void theLogNameAndStampFormatFollowTheirSwitches() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);

        form.bool(ClientPreferences.GAME_LOG_KEEP, false);
        form.bool(ClientPreferences.STAMP_FILENAMES, false);
        assertFalse(form.editable(ClientPreferences.GAME_LOG_FILENAME));
        assertFalse(form.editable(ClientPreferences.STAMP_FORMAT));
        form.bool(ClientPreferences.GAME_LOG_KEEP, true);
        form.bool(ClientPreferences.STAMP_FILENAMES, true);
        assertTrue(form.editable(ClientPreferences.GAME_LOG_FILENAME));
        assertTrue(form.editable(ClientPreferences.STAMP_FORMAT));
    }

    @Test
    void theTilesetKeepsTheCurrentOne() {
        assertTrue(ClientSettingsForm.tilesets("mine.tileset").contains("mine.tileset"));
        assertFalse(ClientSettingsForm.tilesets("notATileset.txt").contains("notATileset.txt"));
    }

    @Test
    void aColourIsShownAsHexAndWrittenAsItsParts() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        assertEquals("#ff0000", form.text(GUIPreferences.MY_UNIT_COLOR));

        form.text(GUIPreferences.MY_UNIT_COLOR, "#00FF80");
        assertEquals("#00ff80", form.text(GUIPreferences.MY_UNIT_COLOR));
        form.text(GUIPreferences.MY_UNIT_COLOR, "blue-ish");
        assertEquals("#00ff80", form.text(GUIPreferences.MY_UNIT_COLOR), "a text that is no colour is not taken");
        form.apply().run();

        assertEquals("0 255 128", store.getString(GUIPreferences.MY_UNIT_COLOR));
        form.defaults(form.page(ClientSettingsForm.FORCES));
        assertEquals("#ff0000", form.text(GUIPreferences.MY_UNIT_COLOR));
    }

    @Test
    void aKeptColourReadsInEitherForm() {
        assertEquals("#102030", ClientSettingsForm.colorOf("16 32 48", null));
        assertEquals("#102030", ClientSettingsForm.colorOf("#102030", null));
        assertEquals("#abcdef", ClientSettingsForm.colorOf("garbage", "#abcdef"));
        assertEquals("#000000", ClientSettingsForm.colorOf(null, null));
        assertEquals(-1, SettingsForm.rgb("#12345"));
        assertEquals("#0a0b0c", SettingsForm.color(0x0A0B0C));
    }

    @Test
    void anOpacityIsShownAsAPercentage() {
        ClientSettingsForm form = new ClientSettingsForm(store, client);
        assertEquals("50", form.text(GUIPreferences.BOARD_ECM_TRANSPARENCY));

        form.text(GUIPreferences.BOARD_ECM_TRANSPARENCY, "25");
        form.apply().run();

        assertEquals(64, store.getInt(GUIPreferences.BOARD_ECM_TRANSPARENCY));
    }
}
