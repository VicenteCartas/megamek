/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import org.junit.jupiter.api.Test;

/**
 * The form column rule: which inputs take the column's whole width, without a GL context (LobbyAdvancedSearchSmokeTest
 * lays out the rows {@link UiKit#formRow} builds).
 */
class UiFormRowTest {

    @Test
    void fieldsFillTheColumnAndFlowsKeepTheirWidth() {
        assertTrue(UiKit.fillsFormColumn(new Actor()), "a select, field or pick list fills");
        assertTrue(UiKit.fillsFormColumn(new WidgetGroup()), "a row built of them fills");
        assertFalse(UiKit.fillsFormColumn(new HorizontalGroup()), "a flow of chips keeps its width");
        assertTrue(UiKit.fillsFormColumn(new HorizontalGroup().wrap(true)),
              "a wrapping flow fills, or it would stack its chips one per row");
    }
}
