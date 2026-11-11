/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSettingsFormTest {
    private PreferenceStore store;
    private ClientPreferences client;
    private final List<String> heard = new ArrayList<>();

    @BeforeEach
    void setUp() {
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
        store.addPreferenceChangeListener(event -> heard.add(event.getName()));
        client = new ClientPreferences(new PreferenceStore());
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
        assertNull(form.pageOf(CommonSettingsPane.SETTINGS_OVERLAYS));
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
}
