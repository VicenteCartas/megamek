/*
 * Copyright (C) 2014-2026 The MegaMek Team. All Rights Reserved.
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
 * Microsoft's "Game Content Use" rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.SharedUtility;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.util.StringDrawer;
import megamek.client.ui.util.UIUtil;
import megamek.common.ToHitData;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.MiscType;
import megamek.common.game.Game;
import megamek.common.moves.ClimbingHelper;
import megamek.common.moves.MovePathSummary;
import megamek.common.moves.MoveStep;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;

/**
 * The glyphs both boards draw on a hex: a planned step's arrows, cost and announcements, a flight path indicator and
 * free hex text. Each is drawn in the hex's {@link HexTileset#HEX_W} x {@link HexTileset#HEX_H} design box; each board
 * places the box on its hex.
 */
public final class TacticalGlyphs {
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final AffineTransform shadowOffset = new AffineTransform();
    private static final AffineTransform upDownOffset = new AffineTransform();
    private static final AffineTransform stepOffset = new AffineTransform();

    static {
        shadowOffset.translate(-1, -1);
        upDownOffset.translate(-30, 0);
        stepOffset.translate(1, 1);
    }

    private static final int FPI_TEXT_SIZE = 50;
    private static final int FPI_ARROW_X_OFFSET = 14;
    private static final int FPI_ARROW_Y_OFFSET = 4;
    private static final Color FPI_OUTLINE = new Color(40, 40, 40, 200);
    private static final Color FPI_CIRCLE = new Color(255, 255, 255, 128);
    private static final int FPI_CIRCLE_RADIUS = 60;
    private static final int HEX_CENTER_X = HexTileset.HEX_W / 2;
    private static final int HEX_CENTER_Y = HexTileset.HEX_H / 2;
    private static final int FPI_TEXT_Y_OFFSET = 17;
    private static final String STRAIGHT_ARROW = "";
    private static final String FLAG = "";
    private static final String FLY_OFF = "";
    private static final String RIGHT_TURN = "";
    private static final String LEFT_TURN = "";

    /** How far a scan sweep reaches past its two ends: its widest arc and a little more. */
    public static final int SCAN_REACH = 62;
    /** Distance between one arc and the next along the line, about half a hex. */
    private static final int SCAN_ARC_SPACING = 38;
    /** The radius of the arc nearest the target. A hex is 84 wide, so this fills most of one. */
    private static final int SCAN_WIDEST_ARC = 58;
    /** How much narrower the first arc is than the last, so the run reads as a direction rather than a ladder. */
    private static final double SCAN_NARROWEST_FRACTION = 0.55;
    /** The arcs stop short of both hexes so neither the scout nor its target is drawn over. */
    private static final int SCAN_CLEARANCE = 26;
    /** The arc sweep in degrees, centred on the direction of travel. A shallow curve, not a bowl. */
    private static final int SCAN_SWEEP_DEGREES = 58;
    /** Stroke width. The sweep has to read at a glance across several hexes of terrain. */
    private static final float SCAN_STROKE_WIDTH = 5.5f;
    /** Alpha of the arc nearest the scout, and of the one nearest the target. */
    private static final int SCAN_NEAR_ALPHA = 150;
    private static final int SCAN_FAR_ALPHA = 240;

    private TacticalGlyphs() { }

    /**
     * A sensor scan's sweep from the scout's hex centre to its target's: arcs opening towards the target, widening and
     * strengthening as they travel. {@code scale} is the hex size relative to the design hex.
     */
    public static void paintScan(Graphics2D graphics, Point2D scanner, Point2D target, Color color, double scale) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double runX = target.getX() - scanner.getX();
        double runY = target.getY() - scanner.getY();
        double distance = Math.hypot(runX, runY);
        double clearance = SCAN_CLEARANCE * scale;
        double length = distance - 2 * clearance;
        double spacing = Math.max(6.0, SCAN_ARC_SPACING * scale);
        // Measured from the scout end, starting half a gap out, so the run is evenly spaced from the unit to its
        // target. Spacing the arcs as fractions of the line instead left a double-width gap at each end, which
        // read as a missing arc next to the scout.
        double firstOffset = spacing / 2.0;
        int arcCount = (int) Math.floor((length - firstOffset) / spacing) + 1;
        if ((distance == 0) || (arcCount < 1)) {
            return;
        }
        double startX = scanner.getX() + runX / distance * clearance;
        double startY = scanner.getY() + runY / distance * clearance;
        runX *= length / distance;
        runY *= length / distance;

        // Degrees run anticlockwise from east while y runs down, so the sweep's facing is the negated travel angle.
        // Each arc opens toward the target.
        double facingDegrees = Math.toDegrees(Math.atan2(-runY, runX));
        graphics.setStroke(new BasicStroke(Math.max(2.5f, (float) (SCAN_STROKE_WIDTH * scale)),
              BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 0; index < arcCount; index++) {
            double along = (firstOffset + (index * spacing)) / length;
            double centreX = startX + (runX * along);
            double centreY = startY + (runY * along);
            // the sweep widens and strengthens as it travels, so the run reads as a direction rather than a
            // ladder of identical marks, and the eye is pulled toward what is being read
            double growth = SCAN_NARROWEST_FRACTION + ((1.0 - SCAN_NARROWEST_FRACTION) * along);
            double radius = Math.max(4.0, SCAN_WIDEST_ARC * scale * growth);
            Arc2D arc = new Arc2D.Double(centreX - radius, centreY - radius, radius * 2, radius * 2,
                  facingDegrees - (SCAN_SWEEP_DEGREES / 2.0), SCAN_SWEEP_DEGREES, Arc2D.OPEN);
            int alpha = (int) (SCAN_NEAR_ALPHA + ((SCAN_FAR_ALPHA - SCAN_NEAR_ALPHA) * along));
            graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            graphics.draw(arc);
        }
    }

    /** A bombing or strafing step's hex is bordered in the step's colour, 3 wide. */
    public static boolean hasBorder(MoveStep step) {
        return step.isVTOLBombingStep() || step.isStrafingStep();
    }

    /** The step's colour: its movement type's, as MASC/supercharged or backwards movement changes it. */
    public static Color stepColor(MoveStep step, boolean isLastStep) {
        boolean isMASCOrSuperCharger = false;
        boolean isBackwards = false;

        EntityMovementType movementType = step.getMovementType(isLastStep);
        switch (movementType) {
            case MOVE_RUN:
            case MOVE_VTOL_RUN:
            case MOVE_OVER_THRUST:
                isMASCOrSuperCharger = (step.isUsingMASC() || step.isUsingSupercharger());
                break;
            case MOVE_JUMP:
                // a jump is never drawn as backwards
                break;
            default:
                if ((step.getType() == MoveStepType.BACKWARDS)
                      || (step.getType() == MoveStepType.LATERAL_LEFT_BACKWARDS)
                      || (step.getType() == MoveStepType.LATERAL_RIGHT_BACKWARDS)) {
                    isBackwards = true;
                }
                break;
        }

        return GUIP.getColorForMovement(movementType, isMASCOrSuperCharger, isBackwards);
    }

    /** The step's arrows, cost and announcements. The {@link #hasBorder} border is each board's own. */
    public static void paintStep(Graphics2D g2D, BoardGlyphContext bv, MoveStep step, boolean isLastStep) {
        Graphics graph = g2D;
        // setup some variables
        Shape moveArrow = bv.getMovementPolys()[step.getFacing()];
        Shape facingArrow = bv.getFacingPolys()[step.getFacing()];

        boolean isLastLegalStep = isLastStep &&
              (step.getMovementType(true) != EntityMovementType.MOVE_ILLEGAL);
        Color col = stepColor(step, isLastStep);

        if (bv.getGame().useVectorMove()) {
            drawActiveVectors(step, graph);
        }

        drawConditions(step, graph, col);

        Shape currentArrow;
        // draw arrows and cost for the step
        switch (step.getType()) {
            case FORWARDS:
            case SWIM:
            case BACKWARDS:
            case CHARGE:
            case DFA:
            case LATERAL_LEFT:
            case LATERAL_RIGHT:
            case LATERAL_LEFT_BACKWARDS:
            case LATERAL_RIGHT_BACKWARDS:
            case DEC:
            case DECELERATION:
            case ACC:
            case ACCELERATION:
            case LOOP:
                // forward movement arrow
                drawArrowShape(g2D, moveArrow, col);
                drawMovementCost(step, isLastStep, new Point(0, 0), graph, col, true);
                break;
            case GO_PRONE:
            case HULL_DOWN:
            case DOWN:
            case DIG_IN:
            case HIT_THE_DECK:
            case FORTIFY:
            case TAKE_COVER:
                // draw arrow indicating dropping prone
                currentArrow = upDownOffset.createTransformedShape(bv.getDownArrow());
                drawArrowShape(g2D, currentArrow, col);
                drawMovementCost(step, isLastStep, new Point(1, 15), graph, col, false);
                break;
            case GET_UP:
            case UP:
            case CAREFUL_STAND:
                // draw arrow indicating standing up
                currentArrow = upDownOffset.createTransformedShape(bv.getUpArrow());
                drawArrowShape(g2D, currentArrow, col);
                drawMovementCost(step, isLastStep, new Point(0, 15), graph, col, false);
                break;
            case CLIMB_MODE_ON:
                String climb;
                if (step.getEntity().getMovementMode() == EntityMovementMode.WIGE) {
                    climb = Messages.getString("BoardView1.WIGEClimb");
                } else {
                    climb = Messages.getString("BoardView1.Climb");
                }
                drawAnnouncement(g2D, climb, step, col);
                break;
            case CLIMB_MODE_OFF:
                String climbOff;
                if (step.getEntity().getMovementMode() == EntityMovementMode.WIGE) {
                    climbOff = Messages.getString("BoardView1.WIGEClimbOff");
                } else {
                    climbOff = Messages.getString("BoardView1.ClimbOff");
                }
                drawAnnouncement(g2D, climbOff, step, col);
                break;
            case TURN_LEFT:
            case TURN_RIGHT:
            case THRUST:
            case YAW:
            case EVADE:
            case ROLL:
                // if this is the last legal step then the facing arrow is drawn later
                if (!isLastLegalStep) {
                    // draw arrows showing the facing
                    drawArrowShape(g2D, facingArrow, col);
                }

                if (bv.getGame().useVectorMove()) {
                    drawMovementCost(step, isLastStep, new Point(0, 0), graph, col, false);
                }
                break;
            case BOOTLEGGER:
                // draw arrows showing them entering the next
                drawArrowShape(g2D, moveArrow, col);
                drawMovementCost(step, isLastStep, new Point(0, 0), graph, col, true);
                break;
            case LOAD:
                String load = Messages.getString("BoardView1.Load");
                drawAnnouncement(g2D, load, step, col);
                break;
            case DEPLOY:
                String deploy = Messages.getString("MovementDisplay.moveDeploy");
                drawAnnouncement(g2D, deploy, step, col);
                break;
            case PICKUP_CARGO:
                String pickup = Messages.getString("MovementDisplay.movePickupCargo");
                drawAnnouncement(g2D, pickup, step, col);
                break;
            case DROP_CARGO:
                String dropCargo = Messages.getString("MovementDisplay.moveDropCargo");
                drawAnnouncement(g2D, dropCargo, step, col);
                break;
            case TOW:
                String tow = Messages.getString("BoardView1.Tow");
                drawAnnouncement(g2D, tow, step, col);
                break;
            case DISCONNECT:
                String disconnect = Messages.getString("BoardView1.Disconnect");
                drawAnnouncement(g2D, disconnect, step, col);
                break;
            case LAUNCH:
            case UNDOCK:
                String launch = Messages.getString("BoardView1.Launch");
                drawAnnouncement(g2D, launch, step, col);
                break;
            case DROP:
                String drop = Messages.getString("BoardView1.Drop");
                drawAnnouncement(g2D, drop, step, col);
                break;
            case RECOVER:
                String recover = Messages.getString("BoardView1.Recover");
                if (step.isDocking()) {
                    recover = Messages.getString("BoardView1.Dock");
                }
                drawAnnouncement(g2D, recover, step, col);
                break;
            case JOIN:
                String join = Messages.getString("BoardView1.Join");
                drawAnnouncement(g2D, join, step, col);
                break;
            case UNLOAD:
                String unload = Messages.getString("BoardView1.Unload");
                drawAnnouncement(g2D, unload, step, col);
                break;
            case HOVER:
                String hover = Messages.getString("BoardView1.Hover");
                drawAnnouncement(g2D, hover, step, col);
                break;
            case LAND:
                String land = Messages.getString("BoardView1.Land");
                drawAnnouncement(g2D, land, step, col);
                break;
            case CONVERT_MODE:
                int modePos = 38;
                if (step.getMp() > 0) {
                    // draw movement cost
                    drawMovementCost(step, isLastStep, new Point(0, 0), graph, col, true);
                    modePos += 16;
                }
                // show new movement mode
                String mode = Messages.getString("BoardView1.ConversionMode."
                      + step.getMovementMode());
                graph.setFont(fitCentred(graph, new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 12), mode));
                int modeX = 42 - (graph.getFontMetrics(graph.getFont()).stringWidth(mode) / 2);
                graph.setColor(Color.darkGray);
                graph.drawString(mode, modeX, modePos - 1);
                graph.setColor(col);
                graph.drawString(mode, modeX - 1, modePos);

                break;
            default:
                break;
        }

        // draw arrows showing the facing for final step only
        if (isLastLegalStep) {
            Shape finalFacingArrow = bv.getFacingPolys()[step.getFacing()];
            drawArrowShape(g2D, finalFacingArrow, col);
        }

        if (isLastLegalStep) {
            drawTMMAndRolls(step, bv.getGame(), new Point(0, 0), graph, col, true);
        }

    }

    /** Draws the given form in the given Color col with a shadow. */
    private static void drawArrowShape(Graphics2D graph, Shape form, Color col) {
        graph.setColor(Color.darkGray);
        Shape currentArrow = stepOffset.createTransformedShape(form);
        graph.fill(currentArrow);

        graph.setColor(col);
        currentArrow = shadowOffset.createTransformedShape(currentArrow);
        graph.fill(currentArrow);
    }

    private static void drawAnnouncement(Graphics2D graph, String text, MoveStep step, Color col) {
        if (step.isPastDanger()) {
            text = "(" + text + ")";
        }
        graph.setFont(fitCentred(graph, new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 12), text));
        int posX = 42 - (graph.getFontMetrics(graph.getFont()).stringWidth(text) / 2);
        int posY = 38 + graph.getFontMetrics(graph.getFont()).getHeight();
        graph.setColor(Color.darkGray);
        graph.drawString(text, posX, posY + 1);
        graph.setColor(col);
        graph.drawString(text, posX - 1, posY);
    }

    /**
     * Draws conditions separate from the step. Allows keeping conditions on Aeros even when that step is erased
     * (advanced movement), such as evading, rolling, loading and unloading.
     */
    private static void drawConditions(MoveStep step, Graphics graph, Color col) {
        if (step.isEvading()) {
            String evade = Messages.getString("BoardView1.Evade");
            graph.setFont(fitCentred(graph, new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 12), evade));
            int evadeX = 42 - (graph.getFontMetrics(graph.getFont()).stringWidth(evade) / 2);
            graph.setColor(Color.darkGray);
            graph.drawString(evade, evadeX, 64);
            graph.setColor(col);
            graph.drawString(evade, evadeX - 1, 63);
        }

        if (step.isRolled()) {
            // Announce roll
            String roll = Messages.getString("BoardView1.Roll");
            graph.setFont(fitCentred(graph, new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 12), roll));
            int rollX = 42 - (graph.getFontMetrics(graph.getFont()).stringWidth(roll) / 2);
            graph.setColor(Color.darkGray);
            graph.drawString(roll, rollX, 18);
            graph.setColor(col);
            graph.drawString(roll, rollX - 1, 17);
        }
    }

    private static void drawActiveVectors(MoveStep step, Graphics graph) {

        /*
         * TODO: it might be better to move this to the MovementSprite so that it is visible before first step and
         *  you can't see it for all entities
         */

        int[] activeXPos = { 39, 59, 59, 40, 19, 19 };
        int[] activeYPos = { 20, 28, 52, 59, 52, 28 };

        int[] v = step.getVectors();
        for (int i = 0; i < 6; i++) {
            String active = Integer.toString(v[i]);
            graph.setFont(new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 12));
            graph.setColor(Color.darkGray);
            graph.drawString(active, activeXPos[i], activeYPos[i]);
            graph.setColor(Color.red);
            graph.drawString(active, activeXPos[i] - 1, activeYPos[i] - 1);
        }
    }

    private static Font getMovementFont() {
        String fontName = GUIP.getMoveFontType();
        int fontStyle = GUIP.getMoveFontStyle();
        int fontSize = GUIP.getMoveFontSize();
        return new Font(fontName, fontStyle, fontSize);
    }

    /**
     * Returns the font shrunk, if needed, so the text fits across the hex when drawn centred.
     */
    private static Font fitCentred(Graphics graph, Font font, String text) {
        return HexLabelFitter.fitToWidth(graph, font, text, HexLabelFitter.centredLabelWidth());
    }

    /**
     * Draws the MP cost label of the step. The font is shrunk when the label would not fit in the hex.
     */
    static void drawMovementCost(MoveStep step, boolean isLastStep,
          Point stepPos, Graphics graph, Color col, boolean shiftFlag) {
        Entity stepEntity = step.getEntity();

        StringBuilder costStringBuf = new StringBuilder();
        // For climbing steps, show the per-turn MP cost capped to walk MP
        if (step.isClimbing() && (stepEntity != null)) {
            int walkMP = stepEntity.getWalkMP();
            costStringBuf.append(Math.min(step.getMpUsed(), walkMP));
        } else {
            costStringBuf.append(SharedUtility.formatMovementPoints(stepEntity, step.getMpUsed()));
        }

        // If the step is using a road bonus, mark it.
        if (step.isOnlyPavementOrRoad()
              && (stepEntity != null) && stepEntity.isEligibleForPavementOrRoadBonus()) {
            costStringBuf.append('+');
        }

        // Show WiGE descent bonus
        costStringBuf.repeat("+", Math.max(0, step.getWiGEBonus()));

        // If the step is dangerous, mark it.
        if (step.isDanger()) {
            costStringBuf.append('*');
        }

        // If the step is past danger, mark that.
        if (step.isPastDanger()) {
            costStringBuf.insert(0, '(');
            costStringBuf.append(')');
        }

        // Show climbing turn count when climb takes multiple turns (TO:AR p.20)
        if (step.isClimbing() && (stepEntity instanceof Mek climbingMek)) {
            int walkMP = stepEntity.getWalkMP();
            int totalLevels = step.getClimbingTotalLevels();
            if ((walkMP > 0) && (totalLevels > 0)) {
                int costPerLevel = ClimbingHelper.getClimbingMPCostPerLevel(climbingMek);
                // Use the levels actually charged THIS turn (which equals chosen levels
                // for partial climbs, full delta otherwise) instead of the full climb
                // height. step.getMpUsed() only includes MP for the charged levels, so
                // subtracting (totalLevels * costPerLevel) would go negative when the
                // player picked fewer levels than the full climb and inflate
                // levelsPerTurn. Falling back to totalLevels keeps prior behavior for
                // older serialized paths where the field defaults to 0.
                int chargedLevels = step.getClimbingChargedLevels();
                int levelsForCostCalc = (chargedLevels > 0) ? chargedLevels : totalLevels;
                int nonClimbCost = step.getMpUsed() - (levelsForCostCalc * costPerLevel);
                int availablePerTurn = walkMP - Math.max(0, nonClimbCost);
                int levelsPerTurn = Math.max(1, availablePerTurn / costPerLevel);
                int turnsToClimb = (int) Math.ceil((double) totalLevels / levelsPerTurn);
                if (turnsToClimb > 1) {
                    costStringBuf.append('[').append(turnsToClimb).append("T]");
                }
            }
        }

        EntityMovementType moveType = step.getMovementType(isLastStep);
        if ((moveType == EntityMovementType.MOVE_VTOL_WALK)
              || (moveType == EntityMovementType.MOVE_VTOL_RUN)
              || (moveType == EntityMovementType.MOVE_VTOL_SPRINT)
              || (moveType == EntityMovementType.MOVE_SUBMARINE_WALK)
              || (moveType == EntityMovementType.MOVE_SUBMARINE_RUN)) {
            costStringBuf.append('{').append(step.getElevation()).append('}');
        }

        if (step.getAltitude() > 0) {
            costStringBuf.append('{').append(step.getAltitude()).append('}');
        }

        // Convert the buffer to a String and draw it.
        String costString = costStringBuf.toString();
        int costX = stepPos.x + 42;
        int availableWidth = shiftFlag ? HexLabelFitter.centredLabelWidth() : HexLabelFitter.labelWidthFrom(costX);
        Font movementFont = getMovementFont();
        graph.setFont(HexLabelFitter.fitToWidth(graph, movementFont, costString, availableWidth));
        if (shiftFlag) {
            costX -= (graph.getFontMetrics(graph.getFont()).stringWidth(costString) / 2);
        }
        graph.setColor(Color.darkGray);
        graph.drawString(costString, costX, stepPos.y + 39);
        graph.setColor(col);
        graph.drawString(costString, costX - 1, stepPos.y + 38);
        // The TMM line below is placed using the current font height; keep it where it was before any shrinking
        graph.setFont(movementFont);
    }

    private static void drawTMMAndRolls(MoveStep step, Game game, Point stepPos, Graphics graph, Color col,
          boolean shiftFlag) {

        StringBuilder subscriptStringBuf = new StringBuilder();

        ToHitData toHitData = MovePathSummary.tmm(step, game);
        subscriptStringBuf.append((toHitData.getValue() < 0) ? '-' : '+');
        subscriptStringBuf.append(toHitData.getValue());

        if (step.isUsingSupercharger() && !step.getEntity().hasWorkingMisc(MiscType.F_JET_BOOSTER)) {
            subscriptStringBuf.append(" S");
            subscriptStringBuf.append(step.getTargetNumberSupercharger());
            subscriptStringBuf.append('+');
        }

        if (step.isUsingMASC() && !step.getEntity().hasWorkingMisc(MiscType.F_JET_BOOSTER)) {
            subscriptStringBuf.append(" M");
            subscriptStringBuf.append(step.getTargetNumberMASC());
            subscriptStringBuf.append('+');
        }

        if (!subscriptStringBuf.isEmpty()) {
            // draw it below the main string.
            int subscriptY = stepPos.y + 39 + (graph.getFontMetrics(graph.getFont()).getHeight() / 2);
            String subscriptString = subscriptStringBuf.toString();
            Font subscriptFont = getMovementFont().deriveFont(getMovementFont().getSize() * 0.5f);
            graph.setFont(fitCentred(graph, subscriptFont, subscriptString));
            int subscriptX = stepPos.x + 42;
            if (shiftFlag) {
                subscriptX -= (graph.getFontMetrics(graph.getFont()).stringWidth(subscriptString) / 2);
            }
            graph.setColor(Color.darkGray);
            graph.drawString(subscriptString, subscriptX, subscriptY);
            graph.setColor(col);
            graph.drawString(subscriptString, subscriptX - 1, subscriptY - 1);
        }

    }

    /**
     * A flight path indicator in green, yellow or red for the turn status on that hex, in the symbol font (set on
     * {@code graph}): a flag for the final hex and a special arrow for flying off the map. {@code asText} draws the
     * remaining distance as text rather than glyph outlines.
     */
    public static void paintFlightPath(Graphics2D graph, BoardGlyphContext bv, TacticalMark.FlightPath indicator,
          boolean asText) {
        Game game = bv.getGame();
        MoveStep currentStep = indicator.steps().get(indicator.index());
        boolean isRepeated = (indicator.index() > 0)
              && equalType(game, currentStep, indicator.steps().get(indicator.index() - 1));
        Color green = GUIP.getOkColor();
        Color yellow = GUIP.getCautionColor();
        Color red = GUIP.getWarningColor();
        if (!isRepeated) {
            drawBackGroundCircle(graph);
        }
        if (indicator.last()) {
            if (currentStep.getVelocityLeft() > 0) {
                drawBackGroundCircle(graph);
                symbol(FLY_OFF, HEX_CENTER_X, HEX_CENTER_Y).color(green).rotate(angleForFacing(currentStep.getFacing()))
                      .draw(graph);
                drawRemainingDistance(graph, bv, currentStep, asText);
            } else {
                Color flag = canTurnForFree(currentStep) ? green
                      : canTurnWithThrustCost(game, currentStep) && !costsTooMuchToTurn(currentStep) ? yellow : red;
                symbol(FLAG, HEX_CENTER_X, HEX_CENTER_Y).color(flag).draw(graph);
            }
        } else {
            AffineTransform oldTransForm = graph.getTransform();
            graph.rotate(angleForFacing(currentStep.getFacing()), HEX_CENTER_X, HEX_CENTER_Y);
            StringDrawer straight = symbol(STRAIGHT_ARROW, HEX_CENTER_X, HEX_CENTER_Y);
            if (canTurnForFree(currentStep)) {
                if (!isRepeated) {
                    turnArrows(graph, green);
                }
                straight.color(green).draw(graph);
            } else if (canTurnWithThrustCost(game, currentStep)) {
                if (costsTooMuchToTurn(currentStep)) {
                    straight.color(red).draw(graph);
                } else {
                    if (!isRepeated) {
                        turnArrows(graph, yellow);
                    }
                    straight.color(green).draw(graph);
                }
            } else {
                straight.color(red).draw(graph);
            }
            graph.setTransform(oldTransForm);
        }
    }

    private static StringDrawer symbol(String symbol, int x, int y) {
        return new StringDrawer(symbol).at(x, y).absoluteCenter().fontSize(FPI_TEXT_SIZE).outline(FPI_OUTLINE, 2.5f);
    }

    private static void turnArrows(Graphics2D graph, Color color) {
        symbol(LEFT_TURN, HEX_CENTER_X - FPI_ARROW_X_OFFSET, HEX_CENTER_Y + FPI_ARROW_Y_OFFSET).color(color).draw(graph);
        symbol(RIGHT_TURN, HEX_CENTER_X + FPI_ARROW_X_OFFSET, HEX_CENTER_Y + FPI_ARROW_Y_OFFSET).color(color)
              .draw(graph);
    }

    private static double angleForFacing(int facing) {
        return facing * Math.PI / 3;
    }

    private static void drawBackGroundCircle(Graphics2D graph) {
        graph.setColor(FPI_CIRCLE);
        graph.fillOval(HEX_CENTER_X - FPI_CIRCLE_RADIUS / 2,
              HEX_CENTER_Y - FPI_CIRCLE_RADIUS / 2,
              FPI_CIRCLE_RADIUS,
              FPI_CIRCLE_RADIUS);
    }

    /**
     * Return true if the fighter would be able to turn at this step but doesn't have enough movement points left to
     * actually make the turn.
     */
    private static boolean costsTooMuchToTurn(MoveStep step) {
        Entity entity = step.getEntity();
        int maxMP = Integer.MAX_VALUE;
        int turnCost = Integer.MIN_VALUE;

        if (entity != null) {
            maxMP = entity.getRunMP();
            turnCost = step.asfTurnCost(step.getGame(), MoveStepType.TURN_LEFT, entity);
        }

        return ((step.getMpUsed() + turnCost) > maxMP);
    }

    /** Whether the fighter can turn at this step given its current velocity and turn restrictions. */
    private static boolean canTurnWithThrustCost(Game game, MoveStep step) {
        return step.canAeroTurn(game);
    }

    /** Whether the fighter can make a free turn at this given step. */
    private static boolean canTurnForFree(MoveStep step) {
        return step.dueFreeTurn();
    }

    /** Whether two indicators show the same turn options, so the second leaves out its circle and turn arrows. */
    private static boolean equalType(Game game, MoveStep step, MoveStep otherStep) {
        boolean bothFree = canTurnForFree(otherStep) && canTurnForFree(step);
        boolean bothWithCost = canTurnWithThrustCost(game, otherStep) && canTurnWithThrustCost(game, step);
        return bothFree || (!canTurnForFree(step) && bothWithCost)
              || (onlyStraight(game, otherStep) && onlyStraight(game, step));
    }

    private static boolean onlyStraight(Game game, MoveStep step) {
        return !canTurnForFree(step) && !canTurnWithThrustCost(game, step);
    }

    /** The distance the remaining velocity still carries the unit, on the lower centre of the hex. */
    private static void drawRemainingDistance(Graphics2D graph, BoardGlyphContext bv, MoveStep moveStep,
          boolean asText) {
        int velocity = moveStep.getVelocity();
        if (bv.getBoard().isGround()) {
            velocity *= 16;
        }

        int remainingDistance = velocity - moveStep.getDistance();
        StringDrawer text = new StringDrawer(Integer.toString(remainingDistance))
              .at(HEX_CENTER_X, HEX_CENTER_Y - FPI_TEXT_Y_OFFSET)
              .font(new Font(GUIP.getMoveFontType(), GUIP.getMoveFontStyle(), 16)).outline(FPI_OUTLINE, 2.5f)
              .color(GUIP.getOkColor()).centerX();
        if (asText) {
            text.asText();
        }
        text.draw(graph);
    }

    /** Text centred in a {@code width} x {@code height} box, outlined, as large as fits the hex shape. */
    public static void paintHexText(Graphics2D graph, String text, Color color, int width, int height, float scale) {
        UIUtil.setHighQualityRendering(graph);
        Font textFont = new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, 1000);
        FontMetrics fm = graph.getFontMetrics(textFont);
        Rectangle2D rect = fm.getStringBounds(text, graph);
        new StringDrawer(text).color(color).center().at(new Point(width / 2, height / 2))
              .outline(Color.BLACK, scale).font(hexTextFont(rect, width, height)).draw(graph);
    }

    private static Font hexTextFont(Rectangle2D rect, int width, int height) {
        float factor = 1;
        if (rect.getHeight() > height) {
            factor = height / (float) rect.getHeight();
        }

        if ((rect.getWidth() * factor) > width) {
            factor = Math.min(factor, width / (float) rect.getWidth());
        }
        // make smaller to actually fit the hex shape
        factor = factor * 0.7f;
        return new Font(MMConstants.FONT_SANS_SERIF, Font.PLAIN, (int) (factor * 1000));
    }
}
