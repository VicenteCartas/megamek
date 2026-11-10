/*
 * Copyright (C) 2020-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.overlay;

import java.awt.Font;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.PreferenceChangeEvent;

/**
 * An overlay for the BoardView that displays a selection of Planetary Conditions for the current game situation
 */
public class PlanetaryConditionsOverlay extends AbstractBoardViewOverlay {
    private static final String MSG_TEMPERATURE = Messages.getString("PlanetaryConditionsOverlay.Temperature");
    private static final String MSG_GRAVITY = Messages.getString("PlanetaryConditionsOverlay.Gravity");
    private static final String MSG_LIGHT = Messages.getString("PlanetaryConditionsOverlay.Light");
    private static final String MSG_ATMOSPHERIC_PRESSURE = Messages.getString(
          "PlanetaryConditionsOverlay.AtmosphericPressure");
    private static final String MSG_ATMOSPHERIC_TAINT = Messages.getString(
          "PlanetaryConditionsOverlay.AtmosphericTaint");
    private static final String MSG_EMI = Messages.getString("PlanetaryConditionsOverlay.EMI");
    private static final String MSG_WEATHER = Messages.getString("PlanetaryConditionsOverlay.Weather");
    private static final String MSG_WIND = Messages.getString("PlanetaryConditionsOverlay.Wind");
    private static final String MSG_DIRECTION = Messages.getString("PlanetaryConditionsOverlay.WindDirection");
    private static final String MSG_FOG = Messages.getString("PlanetaryConditionsOverlay.Fog");
    private static final String MSG_BLOWING_SAND = Messages.getString("PlanetaryConditionsOverlay.BlowingSand");

    /**
     * An overlay for the BoardView that displays a selection of Planetary Conditions for the current game situation.
     */
    public PlanetaryConditionsOverlay(BoardClientState boardView) {
        super(boardView, new Font("SansSerif", Font.PLAIN, 13));
    }

    @Override
    protected String getHeaderText() {
        return Messages.getString("PlanetaryConditionsOverlay.heading",
              KeyCommandBind.getDesc(KeyCommandBind.PLANETARY_CONDITIONS));
    }

    /** @return an ArrayList of all text lines to be shown. */
    @Override
    protected List<String> assembleTextLines() {
        List<String> result = new ArrayList<>();
        addHeader(result);

        if (clientGui != null) {
            // In a game, not the Board Editor
            PlanetaryConditions conditions = currentGame.getPlanetaryConditions();
            List<String> lines = conditionLines(conditions, boardView.getBoard().isSpace());
            // An extreme temperature always shows its line, which is then the first one
            if (!lines.isEmpty() && conditions.isExtremeTemperatureHeat()) {
                lines.set(0, colorToHex(GUIP.getPlanetaryConditionsColorHot()) + lines.get(0));
            } else if (!lines.isEmpty() && conditions.isExtremeTemperatureCold()) {
                lines.set(0, colorToHex(GUIP.getPlanetaryConditionsColorCold()) + lines.get(0));
            }
            result.addAll(lines);
        }

        return result;
    }

    /**
     * The condition lines this overlay shows below its header, as plain text without colour codes, following the
     * planetary conditions display preferences. There are none in space.
     *
     * @param conditions the game's planetary conditions
     * @param inSpace    true for a space board
     *
     * @return a new, modifiable list of the lines
     */
    public static List<String> conditionLines(PlanetaryConditions conditions, boolean inSpace) {
        List<String> result = new ArrayList<>();
        if (!inSpace) {
            int temp = conditions.getTemperature();

            boolean showDefaultConditions = GUIP.getPlanetaryConditionsShowDefaults();

            Boolean showLabel = GUIP.getPlanetaryConditionsShowLabels();
            Boolean showValue = GUIP.getPlanetaryConditionsShowValues();
            Boolean showIndicator = GUIP.getPlanetaryConditionsShowIndicators();

            String tmpStr;

            if (showDefaultConditions || (conditions.isExtremeTemperature())) {
                tmpStr = (showLabel ? MSG_TEMPERATURE + "  " : "");
                tmpStr = tmpStr + (showValue ? temp + "\u00B0C  " : ""); // Degree
                tmpStr = tmpStr + (showIndicator ?
                      (!showValue ? temp + "\u00B0C   " : "") + conditions.getTemperatureIndicator() : // Degree
                      "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || (conditions.getGravity() != 1.0)) {
                float grav = conditions.getGravity();
                tmpStr = (showLabel ? MSG_GRAVITY + "  " : "");
                tmpStr = tmpStr + (showValue ? grav + "g   " : "");
                tmpStr = tmpStr + (showIndicator ?
                      (!showValue ? grav + "g  " : "") + conditions.getGravityIndicator() :
                      "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || !conditions.getLight().isDay()) {
                tmpStr = (showLabel ? MSG_LIGHT + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getLight().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getLight().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || !conditions.getAtmosphere().isStandard()) {
                tmpStr = (showLabel ? MSG_ATMOSPHERIC_PRESSURE + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getAtmosphere().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getAtmosphere().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || !conditions.getAtmosphericTaint().isBreathable()) {
                tmpStr = (showLabel ? MSG_ATMOSPHERIC_TAINT + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getAtmosphericTaint().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getAtmosphericTaint().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || conditions.isEMI()) {
                tmpStr = (showLabel ? MSG_EMI + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getEMI().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getEMI().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || !conditions.getWeather().isClear()) {
                tmpStr = (showLabel ? MSG_WEATHER + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getWeather().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getWeather().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || !conditions.getWind().isCalm()) {
                tmpStr = (showLabel ? MSG_WIND + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getWind().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getWind().getIndicator() : "");
                result.add(tmpStr);
                tmpStr = (showLabel ? MSG_DIRECTION + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getWindDirection().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getWindDirection().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || conditions.isFog()) {
                tmpStr = (showLabel ? MSG_FOG + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getFog().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getFog().getIndicator() : "");
                result.add(tmpStr);
            }

            if (showDefaultConditions || conditions.isBlowingSand()) {
                tmpStr = (showLabel ? MSG_BLOWING_SAND + "  " : "");
                tmpStr = tmpStr + (showValue ? conditions.getBlowingSand().toString() + "  " : "");
                tmpStr = tmpStr + (showIndicator ? conditions.getBlowingSand().getIndicator() : "");
                result.add(tmpStr);
            }
        }

        return result;
    }

    /**
     * The conditions that differ from the standard ones (daylight, clear, calm, 1g, a breathable standard atmosphere,
     * no fog, EMI or blowing sand, a temperature without effects), each as one short phrase, regardless of the
     * display preferences. There are none in space.
     *
     * @param conditions the game's planetary conditions
     * @param inSpace    true for a space board
     *
     * @return a new, modifiable list of the phrases
     */
    public static List<String> notableConditions(PlanetaryConditions conditions, boolean inSpace) {
        List<String> result = new ArrayList<>();
        if (inSpace) {
            return result;
        }
        if (!conditions.getLight().isDay()) {
            result.add(conditions.getLight().toString());
        }
        if (!conditions.getWeather().isClear()) {
            result.add(conditions.getWeather().toString());
        }
        if (!conditions.getWind().isCalm()) {
            result.add(conditions.getWind().toString());
        }
        if (conditions.isFog()) {
            result.add(conditions.getFog().toString());
        }
        if (conditions.isExtremeTemperature()) {
            result.add(conditions.getTemperature() + "\u00B0C");
        }
        if (conditions.getGravity() != 1.0) {
            result.add(conditions.getGravity() + "g");
        }
        if (!conditions.getAtmosphere().isStandard()) {
            result.add(labelled(MSG_ATMOSPHERIC_PRESSURE, conditions.getAtmosphere().toString()));
        }
        if (!conditions.getAtmosphericTaint().isBreathable()) {
            result.add(labelled(MSG_ATMOSPHERIC_TAINT, conditions.getAtmosphericTaint().toString()));
        }
        if (conditions.isEMI()) {
            result.add(labelled(MSG_EMI, conditions.getEMI().toString()));
        }
        if (conditions.isBlowingSand()) {
            result.add(labelled(MSG_BLOWING_SAND, conditions.getBlowingSand().toString()));
        }
        return result;
    }

    private static String labelled(String label, String value) {
        return label.strip() + " " + value;
    }

    @Override
    protected boolean getVisibilityGUIPreference() {
        return GUIP.getShowPlanetaryConditionsOverlay();
    }

    @Override
    protected int getDistTop(Rectangle clipBounds, int overlayHeight) {
        return 30;
    }

    @Override
    protected int getDistSide(Rectangle clipBounds, int overlayWidth) {
        return clipBounds.width - (overlayWidth + 100);
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent e) {
        if (e.getName().equals(GUIPreferences.SHOW_PLANETARY_CONDITIONS_OVERLAY)) {
            setVisible((boolean) e.getNewValue());
            scheduleBoardViewRepaint();
        }
        super.preferenceChange(e);
    }

}
