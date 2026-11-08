/*
 * Copyright (C) 2020-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.panels.phaseDisplay.lobby;


import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import megamek.MMConstants;
import megamek.client.lobby.LobbyRules;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.dialogs.abstractDialogs.BVDisplayDialog;
import megamek.client.ui.dialogs.abstractDialogs.CostDisplayDialog;
import megamek.client.ui.dialogs.randomArmy.ForceGeneratorViewUi;
import megamek.client.ui.dialogs.randomArmy.RatTableModel;
import megamek.client.ui.dialogs.unitSelectorDialogs.EntityReadoutDialog;
import megamek.client.ui.entityreadout.LiveReadoutDialog;
import megamek.client.ui.models.UnitTableModel;
import megamek.client.ui.util.UIUtil;
import megamek.codeUtilities.MathUtility;
import megamek.common.Player;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;
import megamek.common.util.C3Util;
import megamek.logging.MMLogger;

/**
 * This class provides static helper functions for the lobby.
 *
 * @author Simon
 */
public class LobbyUtility {
    private static final MMLogger logger = MMLogger.create(LobbyUtility.class);

    /**
     * Returns true when the starting position of the given player is valid in the given game. This is not the case only
     * when the options "Double Blind" and "Exclusive Starting Positions" are on and the starting position overlaps with
     * that of other players, if "Teams Share Vision" is off, or enemy players, if "Teams Share Vision" is on.
     * <p>
     * See also {@link LobbyRules#isValidStartPos(Game, Player, int)}
     */
    static boolean isValidStartPos(Game game, Player player) {
        return LobbyRules.isValidStartPos(game, player);
    }

    /**
     * Returns true when the given starting position pos is valid for the given player in the given game. This is not
     * the case only when the options "Double Blind" and "Exclusive Starting Positions" are on and the starting position
     * overlaps with that of other players, if "Teams Share Vision" is off, or enemy players, if "Teams Share Vision" is
     * on.
     * <p>
     * See also {@link LobbyRules#isValidStartPos(Game, Player, int)}
     */
    static boolean isValidStartPos(Game game, Player player, int pos) {
        return LobbyRules.isValidStartPos(game, player, pos);
    }

    /**
     * Returns true when double-blind and exclusive deployment are on, meaning that player's deployment zones may not
     * overlap.
     */
    static boolean isExclusiveDeployment(Game game) {
        return LobbyRules.isExclusiveDeployment(game);
    }

    /**
     * Returns true when blind drop is on.
     */
    static boolean isBlindDrop(Game game) {
        return LobbyRules.isBlindDrop(game);
    }

    /**
     * Returns true when real blind drop is on.
     */
    static boolean isRealBlindDrop(Game game) {
        return LobbyRules.isRealBlindDrop(game);
    }

    /**
     * Returns true when teams share vision is on, regardless of whether double-blind is on.
     */
    static boolean isTeamsShareVision(Game game) {
        return LobbyRules.isTeamsShareVision(game);
    }

    /** Returns true if the given entities all belong to the same player. */
    static boolean haveSingleOwner(Collection<Entity> entities) {
        return LobbyRules.haveSingleOwner(entities);
    }

    /**
     * Returns true if any of the given entities are embarked (transported by something).
     */
    static boolean containsTransportedUnit(Collection<Entity> entities) {
        return entities.stream().anyMatch(e -> e.getTransportId() != Entity.NONE);
    }

    /**
     * Returns true when the given board name does not start with one of the control strings of MapSettings signalling a
     * random, generated or surprise board.
     */
    static boolean isBoardFile(String board) {
        return !board.startsWith(MapSettings.BOARD_GENERATED) &&
              !board.startsWith(MapSettings.BOARD_RANDOM) &&
              !board.startsWith(MapSettings.BOARD_SURPRISE);
    }

    /**
     * Draws the given text (the board name or special text) as a label on the lower edge of the image for which the
     * graphics g is given.
     */
    public static void drawMinimapLabel(String text, int w, int h, Graphics g, boolean invalid) {
        if (text.isBlank()) {
            return;
        }
        UIUtil.setHighQualityRendering(g);
        // The text size may grow with the width of the image, but no bigger than
        // 16*gui scale
        // to avoid huge text
        int fontSize = Math.min(w / 10, UIUtil.scaleForGUI(16));
        Font font = new Font(MMConstants.FONT_DIALOG, Font.PLAIN, fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        int th = fm.getAscent() + fm.getDescent(); // The text height
        int cx = (w - fm.stringWidth(text)) / 2; // The left edge for centered text
        // When the text is wider than the image, let the text start close to the left
        // edge
        cx = Math.max(w / 20, cx);
        int cy = h - th / 2;
        Color col = new Color(250, 250, 250, 140);
        if (text.startsWith(Messages.getString("ChatLounge.MapSurprise"))) {
            col = new Color(250, 250, 50, 140);
        } else if (text.startsWith(Messages.getString("ChatLounge.MapGenerated"))) {
            col = new Color(50, 50, 250, 140);
        }
        if (invalid) {
            col = GUIPreferences.getInstance().getWarningColor();
        }
        g.setColor(col);
        g.fillRoundRect(cx - 3, cy - fm.getAscent(), w - 2 * cx + 6, th, fontSize / 2, fontSize / 2);
        // Clip the text to inside the image with a margin of w/20
        g.setClip(w / 20, 0, w - w / 10, h);
        g.setColor(Color.BLACK);
        g.drawString(text, cx, cy);
        g.setClip(null);
    }

    /**
     * Removes the board size ("16x17") and file path from the given board name if it is a board file. Also,
     * reconstructs the text if it's a surprise map or generated map.
     */
    public static String cleanBoardName(String boardName, MapSettings mapSettings) {
        // Remove the file path
        if (isBoardFile(boardName)) {
            boardName = new File(boardName).getName();
        }
        // Construct the text if it's a surprise map
        if (boardName.startsWith(MapSettings.BOARD_SURPRISE)) {
            int numBoards = extractSurpriseMaps(boardName).size();
            boardName = Messages.getString("ChatLounge.MapSurprise") + " (" + numBoards + " boards)";
        }
        // Construct the text if it's a generated map
        if (boardName.startsWith(MapSettings.BOARD_GENERATED)) {
            boardName = Messages.getString("ChatLounge.MapGenerated");
        }
        // Remove board sizes ("16x17")
        String boardSize = mapSettings.getBoardWidth() + "x" + mapSettings.getBoardHeight();
        return megamek.common.board.BoardFile.withoutExtension(boardName).replace(boardSize, "").trim();
    }

    /**
     * Specialized method that returns a list of board names from the given boardsString that starts with the prefix for
     * a Surprise board.
     */
    public static ArrayList<String> extractSurpriseMaps(String boardsString) {
        if (boardsString.startsWith(MapSettings.BOARD_SURPRISE)) {
            boardsString = boardsString.substring(MapSettings.BOARD_SURPRISE.length());
        }
        String[] boards = boardsString.split("\n");
        return new ArrayList<>(Arrays.asList(boards));
    }

    /**
     * Converts an id list of the form 1,2,4,12 to a set of corresponding entities. Ignores entity ids that don't exist.
     * The resulting list may be empty but not null.
     */
    public static HashSet<Entity> getEntities(Game game, String idList) {
        StringTokenizer st = new StringTokenizer(idList, ",");
        HashSet<Entity> result = new HashSet<>();
        while (st.hasMoreTokens()) {
            int id = Integer.parseInt(st.nextToken());
            Entity entity = game.getEntity(id);
            if (entity != null) {
                result.add(entity);
            }
        }
        return result;
    }

    /**
     * Converts an id list of the form 1,2,4,12 to a set of corresponding forces. Ignores force ids that don't exist.
     * The resulting list may be empty but not null.
     */
    public static HashSet<Force> getForces(Game game, String idList) {
        StringTokenizer st = new StringTokenizer(idList, ",");
        HashSet<Force> result = new HashSet<>();
        while (st.hasMoreTokens()) {
            int id = Integer.parseInt(st.nextToken());
            Force force = game.getForces().getForce(id);
            if (force != null) {
                result.add(force);
            }
        }
        return result;
    }

    /**
     * Returns true if a and b share at least one non-hierarchic C3 system (C3i, Naval C3, Nova CEWS). Symmetrical (the
     * order of a and b does not matter).
     *
     * @deprecated Use {@link megamek.common.util.C3Util#sameNhC3System(Entity, Entity)} instead as it provides the same
     *       functionality.
     */
    @Deprecated(since = "0.50.06", forRemoval = true)
    public static boolean sameNhC3System(Entity a, Entity b) {
        return C3Util.sameNhC3System(a, b);
    }

    /** Returns the string with some content shortened like Battle Armor -> BA */
    static String abbreviateUnitName(String unitName) {
        return unitName.replace("(Standard)", "")
              .replace("Battle Armor", "BA")
              .replace("Standard", "Std.")
              .replace("Vehicle", "Veh.")
              .replace("Medium", "Med.")
              .replace("Support", "Spt.")
              .replace("Heavy", "Hvy.")
              .replace("Light", "Lgt.");
    }

    static boolean hasYellowWarning(Entity entity) {
        return (entity instanceof FighterSquadron && entity.getLoadedUnits().isEmpty()) ||
              ((entity.hasC3i() || entity.hasNavalC3()) && (entity.calculateFreeC3Nodes() == 5)) ||
              (entity.hasNovaCEWS() && (entity.calculateFreeC3Nodes() == 2)) ||
              ((entity.getC3Master() == null) && entity.hasC3S());
    }

    /**
     * Returns true when the entities can embark onto loader given the other constraints. If false, the passed errorMsg
     * contains a suitable error message for display.
     */
    static boolean validateLobbyLoad(Collection<Entity> entities, Entity loader, int bayNumber, boolean loadRear,
          StringBuilder errorMsg) {
        return LobbyRules.validateLobbyLoad(entities, loader, bayNumber, loadRear, errorMsg);
    }

    /**
     * Converts an id list of the form 1,2,4,12 to a set of corresponding entities. Ignores entity ids that don't exist.
     * The resulting list may be empty but not null.
     */
    public static HashSet<Entity> getEntities(String idList, AbstractTableModel utm) {
        StringTokenizer st = new StringTokenizer(idList, ",");
        HashSet<Entity> result = new HashSet<>();

        while (st.hasMoreTokens()) {
            int id = MathUtility.parseInt(st.nextToken(), -1);

            MekSummary mekSummary = null;

            if (utm instanceof UnitTableModel) {
                mekSummary = ((UnitTableModel) utm).getUnitAt(id);
            } else if (utm instanceof RatTableModel) {
                mekSummary = ((RatTableModel) utm).getUnitAt(id);
            } else if (utm instanceof ForceGeneratorViewUi.ChosenEntityModel) {
                mekSummary = ((ForceGeneratorViewUi.ChosenEntityModel) utm).getUnitAt(id);
            }

            if (mekSummary != null) {
                try {
                    Entity e = new MekFileParser(mekSummary.getSourceFile(), mekSummary.getEntryName()).getEntity();
                    e.setId(id);
                    result.add(e);
                } catch (EntityLoadingException ex) {
                    logger.error(ex,
                          "Unable to load Mek: {}: {}",
                          mekSummary.getSourceFile(),
                          mekSummary.getEntryName());
                }
            }
        }

        return result;
    }

    /**
     * Returns a list of the selected model row indices in the given table, taking care of row sorting, if a row sorter
     * is applied. The list may be empty but not null.
     */
    public static List<Integer> getSelectedEntities(JTable table) {
        return Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).boxed().toList();
    }

    /**
     * Shows the unit summaries for the given units, but not for hidden units (blind drop) and not for more than 10
     * units at a time (because that's likely a misclick). The Entity Readout(s) will use a game listener to stay
     * updated. Use this only for units that have been added to an actual game.
     */
    public static void liveEntityReadoutAction(Collection<Integer> entities, boolean canSeeAll, JFrame frame,
          Game game) {
        if (entities.size() > 10) {
            LobbyErrors.showTenUnits(frame);
        } else if (!canSeeAll) {
            LobbyErrors.showCannotViewHidden(frame);
        } else {
            int index = 0;
            for (int id : entities) {
                LiveReadoutDialog dialog = new LiveReadoutDialog(frame, game, id);
                dialog.setVisible(true);
                dialog.setLocation(dialog.getLocation().x + index * 10, dialog.getLocation().y + index * 10);
                index++;
            }
        }
    }

    /**
     * Shows the unit summaries for the given units, but not for hidden units (blind drop) and not for more than 10
     * units at a time (because that's likely a misclick).
     */
    public static void mekReadoutAction(Collection<Entity> entities, boolean canSeeAll, boolean modal, JFrame frame) {
        if (entities.size() > 10) {
            LobbyErrors.showTenUnits(frame);
        } else if (!canSeeAll) {
            LobbyErrors.showCannotViewHidden(frame);
        } else {
            int index = 0;

            for (Entity entity : entities) {
                mekReadout(entity, index++, modal, frame);
            }
        }
    }

    /**
     * Shows the unit summary for the given unit. Moves the dialog a bit depending on index so that multiple dialogs
     * don't appear exactly on top of each other.
     */
    public static void mekReadout(Entity entity, int index, boolean modal, JFrame frame) {
        final EntityReadoutDialog dialog = new EntityReadoutDialog(frame, entity);
        dialog.setModal(modal);
        dialog.setVisible(true);
        dialog.setLocation(dialog.getLocation().x + index * 10, dialog.getLocation().y + index * 10);
    }

    /**
     * Shows the battle value calculation for the given units, but not for hidden units (blind drop) and not for more
     * than 10 units at a time (because that's likely a misclick).
     *
     * @param entities The units to the bv report for
     */
    public static void mekBVAction(final Collection<Entity> entities, boolean canSeeAll, boolean modal, JFrame frame) {
        if (entities.size() > 10) {
            LobbyErrors.showTenUnits(frame);
        } else if (!canSeeAll) {
            LobbyErrors.showCannotViewHidden(frame);
        } else {
            for (final Entity entity : entities) {
                new BVDisplayDialog(frame, modal, entity).setVisible(true);
            }
        }
    }

    /**
     * Shows the cost calculation for the given units, but not for hidden units (blind drop) and not for more than 10
     * units at a time (because that's likely a misclick).
     *
     * @param entities The units to the cost report for
     */
    public static void mekCostAction(final Collection<Entity> entities, boolean canSeeAll, boolean modal, JFrame frame) {
        if (entities.size() > 10) {
            LobbyErrors.showTenUnits(frame);
        } else if (!canSeeAll) {
            LobbyErrors.showCannotViewHidden(frame);
        } else {
            for (final Entity entity : entities) {
                new CostDisplayDialog(frame, modal, entity).setVisible(true);
            }
        }
    }

    /**
     * Returns a command string token containing the IDs of the given entities and a leading | E.g. |2,14,44,22
     */
    public static String enToken(Collection<Integer> entities) {
        if (entities.isEmpty()) {
            return "|-1";
        }

        List<String> ids = entities.stream().map(Object::toString).collect(Collectors.toList());
        return "|" + String.join(",", ids);
    }
}
