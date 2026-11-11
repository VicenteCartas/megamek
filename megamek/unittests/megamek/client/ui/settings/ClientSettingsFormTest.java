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
import megamek.common.preference.PreferenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSettingsFormTest {
    private PreferenceStore store;
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
        store.addPreferenceChangeListener(event -> heard.add(event.getName()));
    }

    @Test
    void aMutePreferenceShowsAsAPlaySwitch() {
        ClientSettingsForm form = new ClientSettingsForm(store);

        assertFalse(form.bool(GUIPreferences.SOUND_MUTE_CHAT));
        assertTrue(form.bool(GUIPreferences.SOUND_MUTE_MY_TURN));

        form.bool(GUIPreferences.SOUND_MUTE_CHAT, true);
        form.apply().run();

        assertFalse(store.getBoolean(GUIPreferences.SOUND_MUTE_CHAT));
    }

    @Test
    void theVolumeStaysWithinItsSlider() {
        ClientSettingsForm form = new ClientSettingsForm(store);

        form.text(GUIPreferences.MASTER_VOLUME, "250");
        assertEquals("100", form.text(GUIPreferences.MASTER_VOLUME));
        form.text(GUIPreferences.MASTER_VOLUME, "-4");
        assertEquals("0", form.text(GUIPreferences.MASTER_VOLUME));
        form.text(GUIPreferences.MASTER_VOLUME, "loud");
        assertEquals("0", form.text(GUIPreferences.MASTER_VOLUME));
    }

    @Test
    void applyWritesOnlyWhatChangedAndRevertPutsItBack() {
        ClientSettingsForm form = new ClientSettingsForm(store);

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
        ClientSettingsForm form = new ClientSettingsForm(store);
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
        ClientSettingsForm form = new ClientSettingsForm(store);

        assertEquals(ClientSettingsForm.AUDIO, form.pageOf(CommonSettingsPane.SETTINGS_AUDIO));
        assertNull(form.pageOf(CommonSettingsPane.SETTINGS_OVERLAYS));
        assertNull(form.pageOf(null));
    }

    @Test
    void searchFindsTheAudioPage() {
        ClientSettingsForm form = new ClientSettingsForm(store);

        form.search("volume");
        assertFalse(form.visiblePages().isEmpty());
        form.search("zzzz-nothing");
        assertTrue(form.visiblePages().isEmpty());
    }
}
