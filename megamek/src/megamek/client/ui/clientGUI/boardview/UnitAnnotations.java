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
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Transparency;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.UnitOwnerColors;
import megamek.client.ui.util.StringDrawer;
import megamek.client.ui.util.UIUtil;
import megamek.common.Player;
import megamek.common.actions.ScanAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.FighterSquadron;
import megamek.common.units.IAero;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadVee;
import megamek.common.units.ReconCameraRules;

/**
 * Shared unit label, status and facing painter. It reads game state through drawing inputs and owns no board view.
 */
public final class UnitAnnotations {
    private final BoardGlyphContext bv;
    private Rectangle bounds;
    private Image image;

    public Image image() { return image; }

    // Statics
    private static final int SMALL = 0;
    private static final int STATUS_BAR_LENGTH = 24;
    private static final int STATUS_BAR_X = 55;
    private static final int MAX_TMM_PIPS = 6;
    private static final int BIGGER_PIP_SCALE = 2;
    private static final int BIGGER_PIP_OFFSET = 1;
    private static final int TMM_PIP_SIZE = STATUS_BAR_LENGTH / MAX_TMM_PIPS;
    private static final Color LABEL_CRITICAL_BACK = new Color(200, 0, 0, 200);
    private static final Color LABEL_SPACE_BACK = new Color(0, 0, 200, 200);
    private static final Color LABEL_GROUND_BACK = new Color(50, 50, 50, 200);
    private static final int ON_DECK_ARC_ALPHA = 100;
    /** Shortest hyphenated Mek model treated as a full designation (UM-R60) rather than a configuration (A-Z). */
    static final int MIN_DESIGNATION_LENGTH = 4;

    enum Positioning {
        LEFT, RIGHT
    }

    // Individuals
    final Entity entity;

    private final int secondaryPos;

    private Rectangle entityRect;
    private Rectangle labelRect;
    private Font labelFont;
    private Point hexOrigin;
    private boolean criticalStatus;
    private Positioning labelPos;
    private final Color labelBack;
    /** Used to color the label when this unit is selected for movement etc. */
    private boolean isSelected;

    // Keep track of ECM state, as it's too expensive to compute on the fly.
    private boolean isAffectedByECM = false;

    /**
     * Generic terms that can be removed from the end of vehicle names to create a chassis name.
     */
    private static final Set<String> REMOVABLE_NAME_PARTS = Set.of("Defense",
          "Heavy",
          "Medium",
          "Light",
          "Artillery",
          "Tank",
          "Wheeled",
          "Command",
          "Standard",
          "Hover",
          "Hovercraft",
          "Mechanized",
          "(Standard)",
          "Platoon",
          "Transport",
          "Vehicle",
          "Air",
          "Assault",
          "Mobile",
          "Platform",
          "Battle Armor",
          "Vessel",
          "Infantry",
          "Fighting",
          "Fire",
          "Support",
          "Reconnaissance",
          "Fast");

    private static final GUIPreferences GUIP = GUIPreferences.getInstance();

    public UnitAnnotations(BoardGlyphContext boardView1, final Entity entity, int secondaryPos) {
        bv = boardView1;
        this.entity = entity;
        this.secondaryPos = secondaryPos;
        if (bv.getBoard().isSpace()) {
            labelBack = LABEL_SPACE_BACK;
        } else {
            labelBack = LABEL_GROUND_BACK;
        }
        getBounds();
    }

    public Entity getEntity() {
        return entity;
    }

    private String getAdjShortName() {
        Coords position = entity.getPosition();
        boolean multipleUnits = bv.getGame().getEntitiesVector(position, entity.getBoardId(), true).size() > 4;

        if (onlyDetectedBySensors()) {
            return Messages.getString("BoardView1.sensorReturn");
        } else if (multipleUnits) {
            if (GUIP.getUnitLabelStyle() == LabelDisplayStyle.ONLY_STATUS) {
                return "";
            } else {
                return Messages.getString("BoardView1.multipleUnits");
            }
        } else {
            return labelName(entity, GUIP.getUnitLabelStyle());
        }
    }

    /**
     * The unit's name in a label style, which the label shows unless the unit is only a sensor return or shares a
     * crowded hex, and the native HUD's nameplates show as their name; empty for the style without names and for a
     * unit without a nickname in the nickname-only style.
     */
    public static String labelName(Entity entity, LabelDisplayStyle style) {
        switch (style) {
            case FULL:
                return standardLabelName(entity);
            case ABBREV:
                return abbreviatedName(entity);
            case CHASSIS:
                return reduceVehicleName(entity);
            case NICKNAME:
                if (!pilotNick(entity).isBlank()) {
                    return "\"" + pilotNick(entity).toUpperCase() + "\"";
                } else if (!unitNick(entity).isBlank()) {
                    return "'" + unitNick(entity) + "'";
                } else {
                    return reduceVehicleName(entity);
                }
            case NICKNAME_AND_ABBREVIATED: {
                String abbreviated = abbreviatedName(entity);
                if (!pilotNick(entity).isBlank()) {
                    return "\"" + pilotNick(entity).toUpperCase() + "\" (" + abbreviated + ")";
                } else if (!unitNick(entity).isBlank()) {
                    return "'" + unitNick(entity) + "' (" + abbreviated + ")";
                } else {
                    return abbreviated;
                }
            }
            case ONLY_NICKNAME:
                if (!pilotNick(entity).isBlank()) {
                    return "\"" + pilotNick(entity).toUpperCase() + "\"";
                } else if (!unitNick(entity).isBlank()) {
                    return "'" + unitNick(entity) + "'";
                } else {
                    return "";
                }
            default: // ONLY_STATUS
                return "";
        }
    }

    /**
     * Returns a shortened unit name string, mostly for vehicles. Words contained in the removableNameStrings list are
     * taken away from the end of the name until something is encountered that is not contained in that list. On Mek
     * names this will typically have no effect.
     */
    private static String reduceVehicleName(Entity entity) {
        if (!entity.isVehicle()) {
            return entity.getChassis();
        } else {
            String[] tokens = entity.getChassis().split(" ");
            int i = tokens.length - 1;
            for (;
                  i > 0;
                  i--) {
                if (!REMOVABLE_NAME_PARTS.contains(tokens[i])) {
                    break;
                }
            }
            return String.join(" ", Arrays.copyOfRange(tokens, 0, i + 1));
        }
    }

    /** Returns the label name used by the abbreviated label styles. */
    private static String abbreviatedName(Entity entity) {
        if (entity instanceof Mek) {
            return abbreviatedMekName(entity.getChassis(), entity.getModel());
        }
        return abbreviateUnitName(standardLabelName(entity));
    }

    /**
     * Returns the abbreviated label name of a Mek. A model designation with a hyphen names the Mek on its own (Marauder
     * MAD-3R shows {@code MAD-3R}), so it is shown alone. Any other model is only a configuration (Mad Cat Prime,
     * Thor A), so the chassis is kept in front of it. A blank model shows the chassis alone. Hyphenated models
     * shorter than {@link #MIN_DESIGNATION_LENGTH} are configurations too (Pariah A-Z, Ha Otoko -HR).
     *
     * @param chassis the Mek's chassis, without any reporting name
     * @param model   the Mek's model, possibly blank or {@code null}
     *
     * @return the label name, never blank unless the chassis is blank
     */
    static String abbreviatedMekName(String chassis, @Nullable String model) {
        String trimmedModel = (model == null) ? "" : model.trim();
        if (trimmedModel.isEmpty()) {
            return chassis;
        }
        if (trimmedModel.contains("-") && (trimmedModel.length() >= MIN_DESIGNATION_LENGTH)) {
            return trimmedModel;
        }
        return chassis + " " + trimmedModel;
    }

    /** Returns the string with some content shortened like Battle Armor -> BA */
    private static String abbreviateUnitName(String unitName) {
        return unitName.replace("(Standard)", "")
              .replace("Battle Armor", "BA")
              .replace("Standard", "Std.")
              .replace("Vehicle", "Veh.")
              .replace("Medium", "Med.")
              .replace("Support", "Spt.")
              .replace("Heavy", "Hvy.")
              .replace("Light", "Lgt.")
              .replace("Assault", "Asslt.")
              .replace("Transport", "Trnsp.")
              .replace("Command", "Cmd.")
              .replace("Mechanized", "Mechn.")
              .replace("Wheeled", "Whee.")
              .replace("Platoon", "Plt.")
              .replace("Artillery", "Arty.")
              .replace("Defense", "Def.")
              .replace("Hovercraft", "Hov.")
              .replace("Platoon", "Plt.")
              .replace("Reconnaissance", "Rcn.")
              .replace("Recon", "Rcn.")
              .replace("Tank", "Tk.")
              .replace("Hover ", "Hov. ");
    }

    private static String pilotNick(Entity entity) {
        if ((entity.getCrew().getSize() >= 1) && !entity.getCrew().getNickname().isBlank()) {
            return entity.getCrew().getNickname();
        } else {
            return "";
        }
    }

    private static String unitNick(Entity entity) {
        String name = entity.getShortName();
        int firstApo = name.indexOf('\'');
        int secondApo = name.indexOf('\'', name.indexOf('\'') + 1);
        if ((firstApo >= 0) && (secondApo >= 0)) {
            return name.substring(firstApo + 1, secondApo);
        } else {
            return "";
        }
    }

    private static String standardLabelName(Entity entity) {
        return entity.getShortName();
    }

    public Rectangle getBounds() {
        // Start with the hex and add the label
        bounds = new Rectangle(0, 0, bv.getHexSize().width, bv.getHexSize().height);
        updateLabel();
        bounds.add(labelRect);
        // Add space for 4 little status boxes
        if (labelPos == Positioning.RIGHT) {
            bounds.add(-4 * (labelRect.height + 2) + labelRect.x, labelRect.y);
        } else {
            bounds.add(4 * (labelRect.height + 2) + labelRect.x + labelRect.width, labelRect.y);
        }

        // Move to the board position, save this origin for correct drawing
        hexOrigin = bounds.getLocation();
        Point ePos;

        if (secondaryPos == -1) {
            ePos = bv.getHexLocation(entity.getPosition());
        } else {
            ePos = bv.getHexLocation(entity.getSecondaryPositions().get(secondaryPos));
        }

        if (ePos != null) {
            bounds.setLocation(hexOrigin.x + ePos.x, hexOrigin.y + ePos.y);
        }

        entityRect = new Rectangle(bounds.x + (int) (20 * bv.getScale()),
              bounds.y + (int) (14 * bv.getScale()),
              (int) (44 * bv.getScale()),
              (int) (44 * bv.getScale()));

        return bounds;
    }

    private void updateLabel() {
        Rectangle oldRect = new Rectangle();
        if (labelRect != null) {
            oldRect = new Rectangle(labelRect);
        }

        int face = (entity.isCommander() && !onlyDetectedBySensors()) ? Font.ITALIC : Font.PLAIN;
        labelFont = new Font(MMConstants.FONT_SANS_SERIF, face, (int) (10 * Math.max(bv.getScale(), 0.9)));

        // Check the hexes in directions 2, 5, 1, 4 if they are free of entities
        // and place the label in the direction of the first free hex
        // if none are free, the label will be centered in the current hex
        labelRect = new Rectangle(bv.getFontMetrics(labelFont).stringWidth(getAdjShortName()) + 4,
              bv.getFontMetrics(labelFont).getAscent() + 2);

        Coords position = entity.getPosition();

        if (position != null) {
            if (bv.getGame().getEntitiesVector(position.translated("SE"), entity.getBoardId(), true).isEmpty()) {
                labelRect.setLocation((int) (bv.getHexSize().width * 0.55), (int) (0.75 * bv.getHexSize().height));
                labelPos = Positioning.RIGHT;
            } else if (bv.getGame().getEntitiesVector(position.translated("NW"), entity.getBoardId(), true).isEmpty()) {
                labelRect.setLocation((int) (bv.getHexSize().width * 0.45) - labelRect.width,
                      (int) (0.25 * bv.getHexSize().height) - labelRect.height);
                labelPos = Positioning.LEFT;
            } else if (bv.getGame().getEntitiesVector(position.translated("NE"), entity.getBoardId(), true).isEmpty()) {
                labelRect.setLocation((int) (bv.getHexSize().width * 0.55),
                      (int) (0.25 * bv.getHexSize().height) - labelRect.height);
                labelPos = Positioning.RIGHT;
            } else if (bv.getGame().getEntitiesVector(position.translated("SW"), entity.getBoardId(), true).isEmpty()) {
                labelRect.setLocation((int) (bv.getHexSize().width * 0.45) - labelRect.width,
                      (int) (0.75 * bv.getHexSize().height));
                labelPos = Positioning.LEFT;
            } else {
                labelRect.setLocation(bv.getHexSize().width / 2 - labelRect.width / 2,
                      (int) (0.75 * bv.getHexSize().height));
                labelPos = Positioning.RIGHT;
            }
        }

        // If multiple units are present in a hex, fan out the labels
        // In the deployment phase; indexOf returns -1 for the current unit
        int indexEntity = bv.getGame().getEntitiesVector(position, entity.getBoardId(), true).indexOf(entity);
        int numEntity = bv.getGame().getEntitiesVector(position, entity.getBoardId(), true).size();

        if ((indexEntity != -1) && (numEntity <= 4)) {
            labelRect.y += (bv.getFontMetrics(labelFont).getAscent() + 4) * indexEntity;
        } else if (indexEntity == -1) {
            labelRect.y += (bv.getFontMetrics(labelFont).getAscent() + 4) * numEntity;
        } else {
            labelRect.y += (bv.getFontMetrics(labelFont).getAscent() + 4);
        }

        // If the label has changed, force redrawing. Necessary for the Deployment phase
        if (!labelRect.equals(oldRect)) {
            image = null;
        }
    }

    /**
     * Whether this unit is under orders to scan something in the End Phase. The label is drawn only for the player
     * who gave the order, because a scan order is not something the other side should be able to read off the board.
     *
     * @param entity the unit this sprite is drawn for
     *
     * @return {@code true} when the scanning label belongs on this unit
     */
    private boolean hasOrderedAScan(Entity entity) {
        return (entity.getPendingScan() != null) && isOwnedByTheLocalPlayer(entity);
    }

    /**
     * Whether one of the local player's units has been told to scan this unit in the End Phase. A scan aimed at a
     * hex marks nothing here, which is why the scout carries its own label as well.
     *
     * @param entity the unit this sprite is drawn for
     *
     * @return {@code true} when the scanned label belongs on this unit
     */
    private boolean isTheTargetOfAnOrderedScan(Entity entity) {
        for (Entity scanner : bv.getGame().getEntitiesVector()) {
            ScanAction order = scanner.getPendingScan();
            boolean ordersThisUnit = (order != null)
                  && order.isUnitTarget()
                  && (order.getTargetId() == entity.getId());
            if (ordersThisUnit && isOwnedByTheLocalPlayer(scanner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this unit's Recon Camera spotted a unit this turn, told only to the camera's side.
     *
     * @param entity the unit this sprite is drawn for
     *
     * @return {@code true} when the camera spotting label belongs on this unit
     */
    private boolean isSpottingWithItsCamera(Entity entity) {
        boolean hasSpotted = entity.getReconCameraSpotTargetId() != Entity.NONE;
        return hasSpotted && ReconCameraRules.isOnCameraSide(entity, bv.getLocalPlayer());
    }

    /**
     * Whether a Recon Camera on the local player's side spotted this unit this turn.
     *
     * @param entity the unit this sprite is drawn for
     *
     * @return {@code true} when the camera spotted label belongs on this unit
     */
    private boolean isSpottedByAFriendlyCamera(Entity entity) {
        // read from the spotted unit, so the mark shows even when the camera itself is not visible to this player
        return entity.isReconCameraSpottedFor(bv.getLocalPlayer());
    }

    /**
     * @param entity the unit to check
     *
     * @return {@code true} when the unit belongs to the player sitting at this client
     */
    private boolean isOwnedByTheLocalPlayer(Entity entity) {
        Player localPlayer = bv.getLocalPlayer();
        // The null check earns its place rather than duplicating Objects.equals: with no local player this has
        // to be false, and Objects.equals would call a missing local player equal to an ownerless unit.
        return (localPlayer != null) && Objects.equals(localPlayer, entity.getOwner());
    }

    private record Status(Color color, String status, boolean small) {
        Status(Color color, String key) {
            this(color, Messages.getString("BoardView1." + key), false);
        }

        Status(Color color, String key, Object... arguments) {
            this(color, Messages.getString("BoardView1." + key, arguments), false);
        }

        Status(Color color, String text, int size) {
            this(color, text, true);
        }

        Status(Color color, int damage, int size) {
            this(color, null, true);
        }
    }

    private record Bar(int length, Color color) { }
    private record Tmm(int mode, int value, Color color) { }
    private void drawStatusStrings(Graphics2D graphics2D, List<Status> statusStrings) {
        if (statusStrings.isEmpty()) {
            return;
        }

        // We pull out just the height as that is the size we want for the Rectangle. We use this for all width/height
        // variables to ensure equal size and remove the warnings from IDEA about wrong values.
        int squareEdge = labelRect.height;

        // The small info blobs
        graphics2D.setFont(labelFont);

        Rectangle rectangle = new Rectangle(labelRect.x, labelRect.y, squareEdge, squareEdge);
        if (labelPos == Positioning.LEFT) {
            rectangle.translate(labelRect.width - labelRect.height, 0);
        }

        for (Status curStatus : statusStrings) {
            if (curStatus.small) {
                if (labelPos == Positioning.RIGHT) {
                    rectangle.translate(-labelRect.height - 2, 0);
                } else {
                    rectangle.translate(labelRect.height + 2, 0);
                }
                graphics2D.setColor(labelBack);
                graphics2D.fillRoundRect(rectangle.x, rectangle.y, squareEdge, squareEdge, 5, 5);
                if (curStatus.status == null) {
                    Color damageColor = curStatus.color;
                    if (damageColor != null) {
                        graphics2D.setColor(damageColor);
                        graphics2D.fillRoundRect(rectangle.x + 2,
                              rectangle.y + 2,
                              rectangle.width - 4,
                              rectangle.height - 4,
                              5,
                              5);
                    }

                } else {
                    new StringDrawer(curStatus.status).center()
                          .color(curStatus.color)
                          .at(rectangle.x + rectangle.height / 2, rectangle.y + rectangle.height / 2)
                          .draw(graphics2D);
                }
            }
        }

        if ((bv.getScale() < 0.55) && criticalStatus) {
            // When zoomed far out, status wouldn't be readable, therefore, draw a big "!" (and the label is red)
            Font bigFont = new Font(MMConstants.FONT_SANS_SERIF, Font.BOLD, (int) (42 * bv.getScale()));
            new StringDrawer("!").at(bv.getHexSize().width / 2, bv.getHexSize().height / 2)
                  .color(GUIP.getWarningColor())
                  .outline(Color.WHITE, 1)
                  .center()
                  .font(bigFont)
                  .draw(graphics2D);
        } else {
            // Critical status texts
            Font boldFont = new Font(MMConstants.FONT_SANS_SERIF, Font.BOLD, (int) (12 * bv.getScale()));
            int y = (int) (bv.getHexSize().height / 2.0f + bv.getScale() * (statusStrings.size() - 1) * 7);
            for (Status curStatus : statusStrings) {
                if (!curStatus.small) { // Critical status
                    new StringDrawer(curStatus.status).at(bv.getHexSize().width / 2, y)
                          .color(curStatus.color)
                          .outline(Color.BLACK, 1)
                          .center()
                          .font(boldFont)
                          .draw(graphics2D);
                    y -= (int) (14 * bv.getScale());
                }
            }
        }
    }

    /**
     * Creates the sprite for this entity. Fortunately, it is no longer an extra pain to create transparent images in
     * AWT.
     */
    public void prepare() {
        prepare(statusStrings());
    }

    private List<Status> statusStrings() {
        // Gather unit conditions; the small tiles and the words are drawn apart, each kind in its own order
        ArrayList<Status> stStr = new ArrayList<>();
        for (UnitStatusWords.StatusWord tile : UnitStatusWords.statusTiles(entity, bv.getBoard().isSpace(), bv.getLocalPlayer())) {
            if (tile.key().equals("ALT")) {
                stStr.add(new Status(Color.CYAN, "A", SMALL));
            }
            stStr.add(new Status(tileColor(tile), tile.label(), SMALL));
        }

        for (UnitStatusWords.StatusWord word
              : UnitStatusWords.statusWords(entity, isAffectedByECM(), bv.getLocalPlayer())) {
            stStr.add(new Status(color(word.severity()), word.label(), false));
        }

        Color damageColor = damageColor(entity);
        if (damageColor != null) {
            stStr.add(new Status(damageColor, 0, SMALL));
        }

        criticalStatus = stStr.stream().anyMatch(status -> !status.small && status.color.equals(GUIP.getWarningColor()));
        return List.copyOf(stStr);
    }

    /** The label colour of a status word; the plain words (only "EVADE") are green. */
    private static Color color(UnitStatusWords.Severity severity) {
        return switch (severity) {
            case WARNING -> GUIP.getWarningColor();
            case CAUTION -> GUIP.getCautionColor();
            case PRECAUTION -> GUIP.getPrecautionColor();
            case INFO -> Color.GREEN;
        };
    }

    /** The label colour of a small tile: cyan for a height, pink for fieldwork, else the colour of its severity. */
    private static Color tileColor(UnitStatusWords.StatusWord tile) {
        return switch (tile.key()) {
            case "ALT", "ELEV" -> Color.CYAN;
            case "D", "E" -> Color.PINK;
            default -> color(tile.severity());
        };
    }

    private void prepare(List<Status> stStr) {
        final Board board = bv.getBoard();
        // recalculate bounds & label
        getBounds();

        // create image for buffer
        GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment()
              .getDefaultScreenDevice()
              .getDefaultConfiguration();
        image = config.createCompatibleImage(bounds.width, bounds.height, Transparency.TRANSLUCENT);
        Graphics2D graph = (Graphics2D) image.getGraphics();
        UIUtil.setHighQualityRendering(graph);

        // translate everything (=correction for label placement)
        graph.translate(-hexOrigin.x, -hexOrigin.y);

        // scale the following draws according to board zoom
        graph.scale(bv.getScale(), bv.getScale());

        boolean isAero = entity.isAero();

        if ((isAero && ((IAero) entity).isSpheroid() && !board.isSpace()) && (secondaryPos == 1)) {
            graph.setColor(Color.WHITE);
            graph.draw(bv.getFacingPolys()[entity.getFacing()]);
        }

        if (hasAnnotations()) {
            // Unit Label
            // no scaling for the label, its size is changed by varying the font size directly => better control
            graph.scale(1 / bv.getScale(), 1 / bv.getScale());

            // Label background
            if (!getAdjShortName().isBlank()) {
                graph.setColor(labelBackground());
                graph.fillRoundRect(labelRect.x, labelRect.y, labelRect.width, labelRect.height, 5, 10);

                // Draw a label border with player colors or team coloring
                Color border = labelBorder();
                if (border != null) {
                    graph.setColor(border);
                    Stroke oldStroke = graph.getStroke();
                    graph.setStroke(new BasicStroke(3));
                    graph.drawRoundRect(labelRect.x - 1,
                          labelRect.y - 1,
                          labelRect.width + 1,
                          labelRect.height + 1,
                          5,
                          10);
                    graph.setStroke(oldStroke);
                }

                // Label text
                graph.setFont(labelFont);
                new StringDrawer(getAdjShortName()).center()
                      .at(labelRect.x + labelRect.width / 2, labelRect.y + labelRect.height / 2)
                      .color(labelTextColor())
                      .draw(graph);
            }

            // Past here, everything is drawing status that shouldn't be seen on a sensor return, so we'll just quit
            // here
            if (onlyDetectedBySensors()) {
                graph.dispose();
                return;
            }

            // Draw all the status information now
            drawStatusStrings(graph, stStr);

            // from here, scale the following draws according to board zoom
            graph.scale(bv.getScale(), bv.getScale());

            // draw facing
            graph.setColor(Color.white);
            if ((entity.getFacing() != -1)
                  && !((entity instanceof ConvInfantry infantry)
                  && !infantry.hasFieldWeapon()
                  && !infantry.isTakingCover())
                  && !((entity instanceof IAero) && ((IAero) entity).isSpheroid() && !board.isSpace())) {
                // Indicate a stacked unit with the same facing that can still move
                if (shouldIndicateNotDone() && bv.getGame().getPhase().isMovement()) {
                    var tr = graph.getTransform();
                    // rotate the arrow slightly
                    graph.scale(1 / bv.getScale(), 1 / bv.getScale());
                    graph.rotate(Math.PI / 24, bv.getHexSize().width / 2.0, bv.getHexSize().height / 2.0);
                    graph.scale(bv.getScale(), bv.getScale());
                    graph.setColor(GUIP.getWarningColor());
                    graph.fill(bv.getFacingPolys()[entity.getFacing()]);
                    graph.setColor(Color.LIGHT_GRAY);
                    graph.draw(bv.getFacingPolys()[entity.getFacing()]);
                    graph.setTransform(tr);
                }

                if (!entity.isDone() && bv.getGame().getPhase().isMovement()) {
                    graph.setColor(GUIP.getWarningColor());
                    graph.fill(bv.getFacingPolys()[entity.getFacing()]);
                    graph.setColor(Color.WHITE);
                    graph.draw(bv.getFacingPolys()[entity.getFacing()]);
                } else {
                    graph.setColor(Color.GRAY);
                    graph.fill(bv.getFacingPolys()[entity.getFacing()]);
                    graph.setColor(Color.LIGHT_GRAY);
                    graph.draw(bv.getFacingPolys()[entity.getFacing()]);
                }
            }

            // highlight the active front arc for infantry that has hit the deck with a field weapon
            drawOnDeckFrontArc(graph);

            // determine secondary facing for non-meks & flipped arms; the label shows no Mek torso twist
            boolean mekTorso = ((entity instanceof Mek) || (entity instanceof ProtoMek)) && !(entity instanceof QuadVee)
                  && !entity.getArmsFlipped();
            int secFacing = mekTorso ? entity.getFacing() : twistFacing(entity);
            // draw secondary facing arrow if necessary
            if ((secFacing != -1) && (secFacing != entity.getFacing())) {
                graph.setColor(Color.GREEN);
                graph.draw(bv.getFacingPolys()[secFacing]);
            }
            if (entity.isAero() && this.bv.getGame().useVectorMove()) {
                for (int head : entity.getHeading()) {
                    graph.setColor(Color.GREEN);
                    graph.draw(bv.getFacingPolys()[head]);
                }
            }

            // armor, internal and TMM status bars
            Bar armor = armorBar();
            graph.setColor(Color.darkGray);
            graph.fillRect(STATUS_BAR_X + 1, 7, STATUS_BAR_LENGTH, 3);
            graph.setColor(Color.lightGray);
            graph.fillRect(STATUS_BAR_X, 6, STATUS_BAR_LENGTH, 3);
            graph.setColor(armor.color());
            graph.fillRect(STATUS_BAR_X, 6, armor.length(), 3);

            Bar internal = internalBar();
            if (internal != null) {
                graph.setColor(Color.darkGray);
                graph.fillRect(STATUS_BAR_X + 1, 11, STATUS_BAR_LENGTH, 3);
                graph.setColor(Color.lightGray);
                graph.fillRect(STATUS_BAR_X, 10, STATUS_BAR_LENGTH, 3);
                graph.setColor(internal.color());
                graph.fillRect(STATUS_BAR_X, 10, internal.length(), 3);
            }

            Tmm pips = tmm();
            if (pips != null) {
                int pipScaleFactor = pips.mode() == 3 || pips.mode() == 4 ? BIGGER_PIP_SCALE : 1;
                int pipOffset = pipScaleFactor == BIGGER_PIP_SCALE ? BIGGER_PIP_OFFSET : 0;
                int tmm = pips.value();
                Color tmmColor = pips.color();
                graph.setColor(Color.darkGray);
                graph.fillRect(STATUS_BAR_X - (pipOffset * (MAX_TMM_PIPS - 1)),
                      12 + TMM_PIP_SIZE,
                      STATUS_BAR_LENGTH + (pipOffset * MAX_TMM_PIPS),
                      TMM_PIP_SIZE * pipScaleFactor + 1);
                if (tmm >= 0) {
                    // draw left to right for positive TMM
                    for (int i = 0;
                          i < MAX_TMM_PIPS;
                          i++) {
                        graph.setColor(Color.DARK_GRAY);
                        graph.setColor(i < tmm ? tmmColor : Color.BLACK);
                        graph.fillRect(STATUS_BAR_X - (pipOffset * (MAX_TMM_PIPS - 1)) + 1 + i * (TMM_PIP_SIZE
                                    + pipOffset),
                              13 + TMM_PIP_SIZE,
                              TMM_PIP_SIZE - pipScaleFactor + pipOffset,
                              TMM_PIP_SIZE * pipScaleFactor - 1);
                    }
                } else {
                    // draw pips right to left for negative TMM
                    for (int i = 0;
                          i < MAX_TMM_PIPS;
                          i++) {
                        graph.setColor(Color.DARK_GRAY);
                        graph.setColor(i >= (MAX_TMM_PIPS + tmm) ? tmmColor : Color.BLACK);
                        graph.fillRect(STATUS_BAR_X - (pipOffset * MAX_TMM_PIPS - 1) + 1 + i * (TMM_PIP_SIZE
                                    + pipOffset),
                              13 + TMM_PIP_SIZE,
                              TMM_PIP_SIZE - pipScaleFactor + pipOffset,
                              TMM_PIP_SIZE * pipScaleFactor - 1);
                    }
                }
            }
        }

        graph.dispose();
    }

    private boolean hasAnnotations() {
        // A building's origin is secondary position 0; a large craft's central sprite is position 6.
        return secondaryPos == -1 || secondaryPos == 6 || entity instanceof AbstractBuildingEntity && secondaryPos == 0;
    }

    private Color labelBackground() {
        return criticalStatus ? LABEL_CRITICAL_BACK : labelBack;
    }

    private Color labelBorder() {
        if (!GUIP.getUnitLabelBorder()) {
            return null;
        }
        return UnitOwnerColors.colorFor(entity.getOwner(), bv.getLocalPlayer(), bv.getGame());
    }

    private Color labelTextColor() {
        Color color = GUIP.getUnitTextColor();
        boolean sensor = onlyDetectedBySensors();
        if (!entity.isDone() && !sensor) {
            color = GUIP.getUnitValidColor();
        }
        if (isSelected) {
            color = GUIP.getUnitSelectedColor();
        }
        return entity.isDone() && !sensor ? UIUtil.addAlpha(color, 100) : color;
    }

    private Bar armorBar() {
        double percent = entity.getArmorRemainingPercent();
        return new Bar((int) (STATUS_BAR_LENGTH * percent), barColor(percent));
    }

    private Bar internalBar() {
        if (!hasStructureBar(entity)) {
            return null;
        }
        double percent = entity.getInternalRemainingPercent();
        return new Bar((int) (STATUS_BAR_LENGTH * percent), barColor(percent));
    }

    /** Whether the label shows a structure bar under the armor bar: not for static units and fighter squadrons. */
    public static boolean hasStructureBar(Entity entity) {
        return !UnitStatusWords.isStaticEntity(entity) && !(entity instanceof FighterSquadron);
    }

    private Tmm tmm() {
        int mode = GUIP.getTMMPipMode();
        if (mode == 0 || !tmmShown(entity, bv.getGame().getPhase())) {
            return null;
        }
        return new Tmm(mode, Compute.getTargetMovementModifier(bv.getGame(), entity.getId()).getValue(),
              mode == 1 || mode == 3 ? Color.WHITE : GUIP.getColorForMovement(entity.moved));
    }

    /**
     * Whether the unit's target movement modifier means something to show in {@code phase}: once the unit has moved
     * in the movement phase, and in the firing phase; never for a static unit or an aerospace unit. Both boards use
     * it: the Legacy 2D label's pips (with its pip-mode preference) and the Tactical View's chevrons.
     */
    public static boolean tmmShown(Entity entity, GamePhase phase) {
        return !UnitStatusWords.isStaticEntity(entity) && !entity.isAero()
              && (entity.isDone() && phase.isMovement() || phase.isFiring());
    }

    /**
     * The facing the unit fires from beside its body's: the opposite facing with its arms flipped, else its secondary
     * facing (a torso twist, a turret); -1 for none. The Tactical View's twist notch shows it for every unit; the
     * Legacy 2D label leaves out a Mek's torso twist.
     */
    public static int twistFacing(Entity entity) {
        return entity.getArmsFlipped() ? (entity.getFacing() + 3) % 6 : entity.getSecondaryFacing();
    }

    /**
     * Highlights the three front-arc hexsides for conventional infantry that has hit the deck while carrying an active
     * field weapon. While on the deck such a unit may only fire in its front arc (TO:AR p.106), so the player must
     * designate a facing; filling the active arc makes that choice obvious at a glance instead of having to infer it
     * from the single facing arrow. Mirrors the directional emphasis used for the Taking Cover posture.
     *
     * @param graph the entity sprite graphics context, already scaled to the board zoom
     */
    private void drawOnDeckFrontArc(Graphics2D graph) {
        if (!(entity instanceof ConvInfantry infantry)
              || !infantry.isHitTheDeck()
              || !infantry.hasActiveFieldWeapon()) {
            return;
        }
        int facing = entity.getFacing();
        if (facing == -1) {
            return;
        }
        Color arcColor = GUIP.getPrecautionColor();
        graph.setColor(new Color(arcColor.getRed(), arcColor.getGreen(), arcColor.getBlue(), ON_DECK_ARC_ALPHA));
        Shape[] facingShapes = bv.getFacingPolys();
        for (int arcOffset = -1; arcOffset <= 1; arcOffset++) {
            int arcDirection = ((facing + arcOffset) + 6) % 6;
            graph.fill(facingShapes[arcDirection]);
        }
    }

    /**
     * Returns true when an indicator should be shown that a unit with the same facing as this unit is stacked below it
     * and can still move.
     */
    private boolean shouldIndicateNotDone() {
        var hexEntities = bv.getGame().getEntitiesVector(entity.getPosition(), entity.getBoardId());
        return hexEntities.stream()
              .filter(e -> hexEntities.indexOf(entity) > hexEntities.indexOf(e))
              .filter(e -> e.getFacing() == entity.getFacing())
              .anyMatch(e -> !e.isDone());
    }

    /**
     * The colour of the label's damage-level tile, which the native HUD's nameplates and unit card show too: green
     * for light damage, the caution colour for moderate, the warning colour for heavy and black for crippled; null for
     * an undamaged unit or while the client hides damage levels.
     */
    public static @Nullable Color damageColor(Entity entity) {
        if (!GUIP.getShowDamageLevel()) {
            return null;
        }
        return switch (entity.getDamageLevel()) {
            case Entity.DMG_CRIPPLED -> Color.black;
            case Entity.DMG_HEAVY -> GUIP.getWarningColor();
            case Entity.DMG_MODERATE -> GUIP.getCautionColor();
            case Entity.DMG_LIGHT -> Color.green;
            default -> null;
        };
    }

    /**
     * We only want to show double-blind visibility indicators on our own meks and teammates' meks (assuming the `team
     * vision` option is on).
     */
    private boolean trackThisEntitiesVisibilityInfo(Entity e) {
        return EntityVisibilityUtils.trackThisEntitiesVisibilityInfo(bv.getLocalPlayer(), e);
    }

    /**
     * @return determine if this unit is only detected by an enemies sensor and hence should only be a
     *       sensor return.
     */
    public boolean onlyDetectedBySensors() {
        return EntityVisibilityUtils.onlyDetectedBySensors(bv.getLocalPlayer(), entity);
    }

    /** The colour of an armor or structure bar with this share remaining, on the label and the native nameplates. */
    public static Color barColor(double percentRemaining) {
        if (percentRemaining <= .25) {
            return GUIP.getWarningColor();
        } else if (percentRemaining <= .75) {
            return GUIP.getCautionColor();
        } else {
            return new Color(16, 196, 16);
        }
    }

    /**
     * Overrides to provide for a smaller sensitive area.
     */
    public boolean isInside(Point point) {
        return entityRect.contains(point.x, point.y);
    }

    public Coords getPosition() {
        return entity.getPosition();
    }

    /**
     * @deprecated No indicated uses.
     */
    @Deprecated(since = "0.50.06", forRemoval = true)
    public String getPlayerColor() {
        if (onlyDetectedBySensors()) {
            // TODO : Make me customizable
            return "C0C0C0";
        } else {
            return entity.getOwner().getColour().getHexString();
        }
    }

    public boolean isAffectedByECM() {
        return isAffectedByECM;
    }

    public void setAffectedByECM(boolean isAffectedByECM) {
        boolean changed = isAffectedByECM != this.isAffectedByECM;
        this.isAffectedByECM = isAffectedByECM;
        // We need to prepare the icon again if the value changed
        if (changed) {
            prepare();
        }
    }

    /** Marks the entity as selected for movement etc., recoloring the label */
    public void setSelected(boolean status) {
        if (isSelected != status) {
            isSelected = status;
            prepare();
        }
    }

    /**
     * Returns if the entity is marked as selected for movement etc., recoloring the label
     */
    public boolean getSelected() {
        return isSelected;
    }

}
