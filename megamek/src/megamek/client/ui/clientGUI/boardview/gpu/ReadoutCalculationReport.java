/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.calculationReport.CalculationReport;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.cardDrawer.ASCard;
import megamek.common.alphaStrike.conversion.ASConverter;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.units.Entity;

/**
 * A calculation report, such as a unit's Battle Value or cost, collected as readout rows for
 * {@link LobbyReadoutRows}: headers as titles, lines as three-cell table rows, a result line after a blank row.
 */
final class ReadoutCalculationReport implements CalculationReport {
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> tentative = new ArrayList<>();
    private boolean tentativeActive;

    /** The rows of {@code entity}'s Battle Value calculation. */
    static List<Row> battleValue(Entity entity) {
        ReadoutCalculationReport report = new ReadoutCalculationReport();
        entity.calculateBattleValue(report);
        return report.rows();
    }

    /** The rows of {@code entity}'s cost calculation, ammunition included. */
    static List<Row> cost(Entity entity) {
        ReadoutCalculationReport report = new ReadoutCalculationReport();
        entity.getCost(report, false);
        return report.rows();
    }

    /**
     * {@code entity}'s Alpha Strike card, {@code width} pixels wide in the card font the player chose, and the rows of
     * its conversion; no card and no rows when it cannot be converted, as a Battlefield Support Asset. Java2D, so any
     * thread.
     */
    static AlphaStrike alphaStrike(Entity entity, int width) {
        if ((entity instanceof BattlefieldSupportAsset) || !ASConverter.canConvert(entity)) {
            return new AlphaStrike(null, List.of());
        }
        ReadoutCalculationReport report = new ReadoutCalculationReport();
        AlphaStrikeElement element = ASConverter.convert(entity, true, report);
        ASCard card = ASCard.createCard(element);
        String font = GUIPreferences.getInstance().getAsCardFont();
        if ((font != null) && !font.isBlank()) {
            card.setFont(Font.decode(font));
        }
        return new AlphaStrike(card.getCardImage(width), report.rows());
    }

    /** A unit's Alpha Strike card, or null, and the rows of its conversion. */
    record AlphaStrike(@Nullable BufferedImage card, List<Row> rows) {
    }

    List<Row> rows() {
        return List.copyOf(rows);
    }

    private List<Row> target() {
        return tentativeActive ? tentative : rows;
    }

    private static String text(@Nullable String text) {
        return (text == null) ? "" : text;
    }

    @Override
    public CalculationReport addLine(@Nullable String type, @Nullable String calculation, @Nullable String result) {
        target().add(new Row(EntityReadout.Row.Kind.TABLE_ROW, List.of(text(type), text(calculation), text(result))));
        return this;
    }

    @Override
    public CalculationReport addSubHeader(String text) {
        target().add(new Row(EntityReadout.Row.Kind.TITLE, List.of(text(text))));
        return this;
    }

    @Override
    public CalculationReport addHeader(String text) {
        target().add(new Row(EntityReadout.Row.Kind.TITLE, List.of(text(text))));
        return this;
    }

    @Override
    public CalculationReport addResultLine(@Nullable String type, @Nullable String calculation,
          @Nullable String result) {
        target().add(new Row(EntityReadout.Row.Kind.SPACE, List.of()));
        return addLine(type, calculation, result);
    }

    @Override
    public JComponent toJComponent() {
        throw new UnsupportedOperationException("A readout report has no Swing component");
    }

    @Override
    public void startTentativeSection() {
        tentativeActive = true;
    }

    @Override
    public void endTentativeSection() {
        rows.addAll(tentative);
        tentative.clear();
        tentativeActive = false;
    }

    @Override
    public void discardTentativeSection() {
        tentative.clear();
        tentativeActive = false;
    }
}
