/*
 * Copyright (C) 2015-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.unitDisplay;

import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.AmmoChoices;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.HeatBuildup;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.WeaponStats;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.formatAmmo;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.heatBuildup;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.isAerospaceAttack;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.showsExtremeRange;
import static megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData.stats;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.MouseInputAdapter;

import jakarta.annotation.Nullable;
import megamek.client.ui.GBC;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.comboBoxes.MMComboBox;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.widget.BackGroundDrawer;
import megamek.client.ui.widget.SkinXMLHandler;
import megamek.client.ui.widget.UnitDisplaySkinSpecification;
import megamek.client.ui.widget.picmap.PMUtil;
import megamek.client.ui.widget.picmap.PicMap;
import megamek.common.Configuration;
import megamek.common.ToHitData;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.units.*;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.logging.MMLogger;

/**
 * Swing view of the shared weapon selection and firing presentation.
 */
public class WeaponPanel extends PicMap implements ListSelectionListener, ActionListener, IPreferenceChangeListener {
    private static final MMLogger logger = MMLogger.create(WeaponPanel.class);

    /**
     * Mouse adaptor for the weapon list. Supports rearranging the weapons to define a custom ordering.
     *
     * @author arlith
     */
    private class WeaponListMouseAdapter extends MouseInputAdapter {

        private boolean mouseDragging = false;
        private int dragSourceIndex;

        @Override
        public void mousePressed(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                Object src = e.getSource();
                if (src instanceof JList) {
                    dragSourceIndex = ((JList<?>) src).getSelectedIndex();
                    mouseDragging = true;
                }
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            mouseDragging = false;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            removeListeners();

            try {
                Object src = e.getSource();
                // Check to see if we are in a state we care about
                if (!mouseDragging || !(src instanceof JList<?> srcList)) {
                    return;
                }
                WeaponListModel srcModel = (WeaponListModel) srcList.getModel();
                int currentIndex = srcList.locationToIndex(e.getPoint());
                if (currentIndex != dragSourceIndex) {
                    int dragTargetIndex = srcList.getSelectedIndex();
                    WeaponMounted weaponAt = srcModel.getWeaponAt(dragSourceIndex);

                    if (weaponAt == null) {
                        // Somehow we found no weapon there.
                        return;
                    }

                    srcModel.swapIdx(dragSourceIndex, dragTargetIndex);
                    dragSourceIndex = currentIndex;

                    // The dragged order becomes the unit's custom order, shown in the weapon sort order drop down
                    List<WeaponMounted> order = new ArrayList<>();
                    for (int i = 0; i < srcModel.getSize(); i++) {
                        order.add(srcModel.getWeaponAt(i));
                    }
                    state.setCustomWeaponOrder(order);
                    comboWeaponSortOrder.setSelectedItem(WeaponSortOrder.CUSTOM);
                }
            } catch (Exception ex) {
                logger.error("Unable to handle unexpected drag event: {}", e.toString());

            } finally {
                // Return listeners before returning!
                addListeners();
            }
        }
    }

    final UnitDisplayPanel unitDisplayPanel;

    private MMComboBox<WeaponSortOrder> comboWeaponSortOrder;
    public JList<String> weaponList;
    private final UnitDisplayState state;
    private final Runnable stateListener = this::refreshFromState;
    private boolean syncing;
    private JScrollPane tWeaponScroll;
    private JComboBox<String> m_chAmmo;
    public JComboBox<String> m_chBayWeapon;

    private JLabel wBayWeapon;
    private JLabel wArcHeatL;
    private JLabel wMinL;
    private JLabel wShortL;
    private JLabel wMedL;
    private JLabel wLongL;
    private JLabel wExtL;
    private JLabel wAVL;
    private JLabel wNameR;
    private JLabel wHeatR;
    private JLabel wArcHeatR;
    private JLabel wDamR;
    private JLabel wMinR;
    private JLabel wShortR;
    private JLabel wMedR;
    private JLabel wLongR;
    private JLabel wExtR;
    private JLabel wShortAVR;
    private JLabel wMedAVR;
    private JLabel wLongAVR;
    private JLabel wExtAVR;
    private JLabel currentHeatBuildupR;
    private JLabel wTargetExtraInfo;
    private JLabel wRangeR;
    private JLabel wDamageTrooperL;
    private JLabel wDamageTrooperR;
    private JLabel wInfantryRange0L;
    private JLabel wInfantryRange0R;
    private JLabel wInfantryRange1L;
    private JLabel wInfantryRange1R;
    private JLabel wInfantryRange2L;
    private JLabel wInfantryRange2R;
    private JLabel wInfantryRange3L;
    private JLabel wInfantryRange3R;
    private JLabel wInfantryRange4L;
    private JLabel wInfantryRange4R;
    private JLabel wInfantryRange5L;
    private JLabel wInfantryRange5R;
    private JTextPane toHitText;
    private JTextPane wTargetInfo;
    private String shownTargetInfo;

    // I need to keep a pointer to the weapon list of the
    // currently selected mek.
    private ArrayList<AmmoMounted> vAmmo;
    private Entity entity;

    /**
     * Used to make sure that multiple removeListeners() calls (that have no cumulative effect) are not overbalanced by
     * multiple addListeners() calls. This would happen when one method that needs to use removeL [stuff ...] addL calls
     * another that needs to do the same.
     */
    private int listenerCounter = 0;

    Color[] bgColors = { Color.gray, Color.darkGray };
    int gridY;
    public static final int INTERNAL_PANE_WIDTH = 400;
    public static final int LINE_HEIGHT = 25;
    public static final Color COLOR_FG = Color.WHITE;
    public static final Color TEXT_BG = Color.DARK_GRAY;

    private static final GUIPreferences GUIP = GUIPreferences.getInstance();

    WeaponPanel(UnitDisplayPanel unitDisplayPanel) {
        this.unitDisplayPanel = unitDisplayPanel;
        state = unitDisplayPanel.getDisplayState();

        JPanel panelTop = new JPanel();
        panelTop.setOpaque(false);
        panelTop.setLayout(new GridBagLayout());
        gridY = 0;
        panelTop.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelTop.setAlignmentY(Component.TOP_ALIGNMENT);
        panelTop.setPreferredSize(new Dimension(INTERNAL_PANE_WIDTH, 20));
        // having a max size set causes odd draw issues
        panelTop.setMaximumSize(null);
        createWeaponList(panelTop);
        createWeaponDisplay(panelTop);
        createRangeDisplay(panelTop);
        createToHitDisplay(panelTop);

        JPanel panelText = new JPanel();
        panelText.setOpaque(false);
        panelText.setLayout(new GridBagLayout());
        gridY = 0;
        panelText.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelText.setAlignmentY(Component.TOP_ALIGNMENT);
        panelText.setPreferredSize(new Dimension(INTERNAL_PANE_WIDTH, 20));
        panelText.setMaximumSize(null);
        createToHitText(panelText);

        JSplitPane splitPaneMain = new JSplitPane(JSplitPane.VERTICAL_SPLIT, panelTop, panelText);
        splitPaneMain.setOpaque(false);

        JPanel panelMain = new JPanel();
        panelMain.setOpaque(false);
        panelMain.setLayout(new BoxLayout(panelMain, BoxLayout.Y_AXIS));
        panelMain.add(splitPaneMain);

        JPanel panelLower = new JPanel();
        panelLower.setOpaque(false);
        panelLower.setLayout(new GridBagLayout());
        gridY = 0;
        panelLower.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelLower.setAlignmentY(Component.TOP_ALIGNMENT);
        panelLower.setPreferredSize(new Dimension(INTERNAL_PANE_WIDTH, 20));
        panelLower.setMaximumSize(null);

        createTargetDisplay(panelLower);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, panelMain, panelLower);
        splitPane.setOpaque(false);
        this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        this.add(splitPane);

        addListeners();
        GUIP.addPreferenceChangeListener(this);

        setBackGround();
        onResize();
        state.addChangeListener(stateListener);
    }

    void setupLabel(JComponent label) {
        label.setOpaque(false);
        label.setForeground(COLOR_FG);
        label.setBackground(TEXT_BG);
    }

    void setupTextPane(JTextPane pane) {
        pane.setContentType("text/html");
        pane.setForeground(COLOR_FG);
        pane.setBackground(TEXT_BG);
        pane.setEditable(false);
        pane.setOpaque(true);
    }

    private void addSubDisplay(JPanel parent, JComponent child, int minHeight, int fill) {
        child.setMinimumSize(new Dimension(INTERNAL_PANE_WIDTH, minHeight));
        // null means allow UI to recompute
        child.setMaximumSize(new Dimension(INTERNAL_PANE_WIDTH, minHeight * 2));
        child.setPreferredSize(null);
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        child.setAlignmentY(Component.TOP_ALIGNMENT);
        child.setBackground(bgColors[(gridY++) % bgColors.length]);
        child.setOpaque(false);

        Dimension min = parent.getMinimumSize();
        min.height += minHeight;
        parent.setMinimumSize(min);

        Dimension pref = parent.getPreferredSize();
        pref.height += minHeight;
        parent.setPreferredSize(pref);

        parent.add(child, GBC.eol()
              .gridY(gridY++)
              .insets(10, 1, 10, 1)
              .weighty(1)
              .fill(fill));
    }

    private void createWeaponList(JPanel parent) {
        JLabel wSortOrder = new JLabel(
              Messages.getString("MekDisplay.WeaponSortOrder.label"),
              SwingConstants.LEFT);
        setupLabel(wSortOrder);

        JPanel pWeaponOrder = new JPanel(new GridBagLayout());
        pWeaponOrder.setOpaque(false);
        int parentGridY = 0;

        pWeaponOrder.add(wSortOrder,
              GBC.std().insets(15, 1, 1, 1).gridY(parentGridY).gridX(0));
        comboWeaponSortOrder = new MMComboBox<>("comboWeaponSortOrder", WeaponSortOrder.values());
        pWeaponOrder.add(comboWeaponSortOrder, GBC.eol()
              .fill(GridBagConstraints.HORIZONTAL)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 15, 1).gridY(parentGridY).gridX(1));
        addSubDisplay(parent, pWeaponOrder, LINE_HEIGHT, GridBagConstraints.BOTH);

        // weapon list
        weaponList = new JList<>(new DefaultListModel<>());
        WeaponListMouseAdapter mouseAdapter = new WeaponListMouseAdapter();
        weaponList.addMouseListener(mouseAdapter);
        weaponList.addMouseMotionListener(mouseAdapter);

        tWeaponScroll = new JScrollPane(weaponList);
        addSubDisplay(parent, tWeaponScroll, GUIP.getUnitDisplayWeaponListHeight(), GridBagConstraints.BOTH);

        weaponList.resetKeyboardActions();
        for (KeyListener key : weaponList.getKeyListeners()) {
            weaponList.removeKeyListener(key);
        }

        // adding Ammo choice + label
        JLabel wAmmo = new JLabel(Messages.getString("MekDisplay.Ammo"), SwingConstants.LEFT);
        setupLabel(wAmmo);
        m_chAmmo = new JComboBox<>();

        wBayWeapon = new JLabel(Messages.getString("MekDisplay.Weapon"), SwingConstants.LEFT);
        setupLabel(wBayWeapon);
        m_chBayWeapon = new JComboBox<>();

        JPanel pAmmo = new JPanel(new GridBagLayout());
        pAmmo.setOpaque(false);

        pAmmo.add(wBayWeapon, GBC.std().insets(15, 1, 1, 1).gridY(parentGridY).gridX(0));
        pAmmo.add(m_chBayWeapon, GBC.std().fill(GridBagConstraints.HORIZONTAL)
              .insets(15, 1, 15, 1).gridY(parentGridY).gridX(1));
        parentGridY++;

        pAmmo.add(wAmmo, GBC.std().insets(15, 9, 1, 1).gridY(parentGridY).gridX(0));

        pAmmo.add(m_chAmmo,
              GBC.eol().fill(GridBagConstraints.HORIZONTAL)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 9, 15, 1).gridY(parentGridY).gridX(1));

        addSubDisplay(parent, pAmmo, LINE_HEIGHT * 3 / 2, GridBagConstraints.BOTH);

    }

    private void createWeaponDisplay(JPanel parent) {
        // Adding weapon display labels
        JLabel wNameL = new JLabel(Messages.getString("MekDisplay.Name"), SwingConstants.CENTER);
        setupLabel(wNameL);
        JLabel wHeatL = new JLabel(Messages.getString("MekDisplay.Heat"), SwingConstants.CENTER);
        setupLabel(wHeatL);
        JLabel wDamL = new JLabel(Messages.getString("MekDisplay.Damage"), SwingConstants.CENTER);
        setupLabel(wDamL);
        wArcHeatL = new JLabel(Messages.getString("MekDisplay.ArcHeat"), SwingConstants.CENTER);
        setupLabel(wArcHeatL);

        wNameR = new JLabel("", SwingConstants.CENTER);
        setupLabel(wNameR);

        wHeatR = new JLabel("--", SwingConstants.CENTER);
        setupLabel(wHeatR);

        wDamR = new JLabel("--", SwingConstants.CENTER);
        setupLabel(wDamR);

        wArcHeatR = new JLabel("--", SwingConstants.CENTER);
        setupLabel(wArcHeatR);

        wDamageTrooperL = new JLabel(Messages.getString("MekDisplay.DamageTrooper"), SwingConstants.CENTER);
        setupLabel(wDamageTrooperL);

        wDamageTrooperR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wDamageTrooperR);

        JPanel pCurrentWeapon = new JPanel(new GridBagLayout());
        pCurrentWeapon.setOpaque(false);
        int parentGridY = 0;

        pCurrentWeapon.add(wNameL, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(5, 9, 1, 1).gridY(parentGridY).gridX(0).weightX(1));

        pCurrentWeapon.add(wHeatL, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 9, 1, 1).gridY(parentGridY).gridX(1).weightX(1));

        pCurrentWeapon.add(wDamL, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 9, 1, 1).gridY(parentGridY).gridX(2).weightX(1));

        pCurrentWeapon.add(wArcHeatL, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 9, 1, 1).gridY(parentGridY).gridX(3).weightX(1));

        pCurrentWeapon.add(wDamageTrooperL, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 9, 1, 1).gridY(parentGridY).gridX(3).weightX(1));
        parentGridY++;
        pCurrentWeapon.add(wNameR, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(5, 1, 1, 1).gridY(parentGridY).gridX(0).weightX(1));

        pCurrentWeapon.add(wHeatR, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 1, 1, 1).gridY(parentGridY).gridX(1).weightX(1));

        pCurrentWeapon.add(wDamR, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 1, 1, 1).gridY(parentGridY).gridX(2).weightX(1));

        pCurrentWeapon.add(wArcHeatR, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 1, 1, 1).gridY(parentGridY).gridX(3).weightX(1));

        pCurrentWeapon.add(wDamageTrooperR, GBC.std().fill(GridBagConstraints.NONE).anchor(GridBagConstraints.WEST)
              .insets(15, 1, 1, 1).gridY(parentGridY).gridX(3).weightX(1));

        addSubDisplay(parent, pCurrentWeapon, LINE_HEIGHT * 2, GridBagConstraints.NONE);
    }

    private void createRangeDisplay(JPanel parent) {
        // Adding range labels
        wMinL = new JLabel(Messages.getString("MekDisplay.Min"), SwingConstants.CENTER);
        setupLabel(wMinL);
        wShortL = new JLabel(Messages.getString("MekDisplay.Short"), SwingConstants.CENTER);
        setupLabel(wShortL);

        wMedL = new JLabel(Messages.getString("MekDisplay.Med"), SwingConstants.CENTER);
        setupLabel(wMedL);

        wLongL = new JLabel(Messages.getString("MekDisplay.Long"), SwingConstants.CENTER);
        setupLabel(wLongL);

        wExtL = new JLabel(Messages.getString("MekDisplay.Ext"), SwingConstants.CENTER);
        setupLabel(wExtL);

        wMinR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wMinR);

        wShortR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wShortR);

        wMedR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wMedR);

        wLongR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wLongR);

        wExtR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wExtR);

        wAVL = new JLabel(Messages.getString("MekDisplay.AV"), SwingConstants.CENTER);
        setupLabel(wAVL);

        wShortAVR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wShortAVR);

        wMedAVR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wMedAVR);

        wLongAVR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wLongAVR);

        wExtAVR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wExtAVR);

        wInfantryRange0L = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange0L);

        wInfantryRange0R = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange0R);

        wInfantryRange1L = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange1L);

        wInfantryRange1R = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange1R);

        wInfantryRange2L = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange2L);

        wInfantryRange2R = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange2R);

        wInfantryRange3L = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange3L);

        wInfantryRange3R = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange3R);

        wInfantryRange4L = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange4L);

        wInfantryRange4R = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange4R);

        wInfantryRange5L = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange5L);

        wInfantryRange5R = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wInfantryRange5R);

        // range panel. Its row count changes with the unit (aerospace adds the AV row), so its minimum height follows
        // its content: when space is short the parent lays out at minimum sizes, and a fixed minimum cut off the AV row.
        JPanel pRange = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(INTERNAL_PANE_WIDTH, getPreferredSize().height);
            }
        };
        pRange.setAlignmentX(Component.LEFT_ALIGNMENT);
        pRange.setAlignmentY(Component.TOP_ALIGNMENT);
        pRange.setOpaque(false);
        int parentGridY = 0;

        pRange.add(wMinL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 9, 9, 1).gridY(parentGridY).gridX(0).weightX(1));

        pRange.add(wShortL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 9, 9, 1).gridY(parentGridY).gridX(1).weightX(1));

        pRange.add(wMedL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 9, 9, 1).gridY(parentGridY).gridX(2).weightX(1));

        pRange.add(wLongL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 9, 9, 1).gridY(parentGridY).gridX(3).weightX(1));

        pRange.add(wExtL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 9, 9, 1).gridY(parentGridY).gridX(4).weightX(1));

        parentGridY++;

        pRange.add(wInfantryRange0L, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 9, 1).gridY(parentGridY).gridX(0).weightX(1));

        pRange.add(wInfantryRange1L, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 9, 1).gridY(parentGridY).gridX(1).weightX(1));

        pRange.add(wInfantryRange2L, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 9, 1).gridY(parentGridY).gridX(2).weightX(1));

        pRange.add(wInfantryRange3L, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 9, 1).gridY(parentGridY).gridX(3).weightX(1));

        pRange.add(wInfantryRange4L, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 9, 1).gridY(parentGridY).gridX(4).weightX(1));

        pRange.add(wInfantryRange5L, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 9, 9, 1).gridY(parentGridY).gridX(4).weightX(1));

        parentGridY++;
        // ----------------

        pRange.add(wMinR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(0).weightX(1));

        pRange.add(wShortR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(1).weightX(1));

        pRange.add(wMedR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(2).weightX(1));

        pRange.add(wLongR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(3).weightX(1));

        pRange.add(wExtR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(4).weightX(1));

        pRange.add(wInfantryRange0R, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(0).weightX(1));

        pRange.add(wInfantryRange1R, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(1).weightX(1));

        pRange.add(wInfantryRange2R, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(2).weightX(1));

        pRange.add(wInfantryRange3R, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(3).weightX(1));

        pRange.add(wInfantryRange4R, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(4).weightX(1));

        pRange.add(wInfantryRange5R, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(5).weightX(1));

        parentGridY++;
        // ----------------
        pRange.add(wAVL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(0).weightX(1));

        pRange.add(wShortAVR, GBC.std().fill(GridBagConstraints.NONE)
              .anchor(GridBagConstraints.WEST)
              .insets(15, 1, 9, 1).gridY(parentGridY).gridX(1).weightX(1));

        pRange.add(wMedAVR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(2).weightX(1));

        pRange.add(wLongAVR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(3).weightX(1));

        pRange.add(wExtAVR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 9, 1).gridY(parentGridY).gridX(4).weightX(1));

        pRange.setMinimumSize(new Dimension(INTERNAL_PANE_WIDTH, LINE_HEIGHT));
        pRange.setMaximumSize(new Dimension(INTERNAL_PANE_WIDTH, LINE_HEIGHT));
        pRange.setPreferredSize(new Dimension(INTERNAL_PANE_WIDTH, LINE_HEIGHT));
        addSubDisplay(parent, pRange, LINE_HEIGHT * 2, GridBagConstraints.NONE);
    }

    private void createToHitDisplay(JPanel parent) {
        // to hit panel
        JPanel pTargetInfo = new JPanel(new GridBagLayout());
        pTargetInfo.setOpaque(true);

        JLabel wRangeL = new JLabel(Messages.getString("MekDisplay.Range"), SwingConstants.LEFT);
        setupLabel(wRangeL);

        wRangeR = new JLabel("---", SwingConstants.CENTER);
        setupLabel(wRangeR);

        JLabel currentHeatBuildupL = new JLabel(Messages.getString("MekDisplay.HeatBuildup"), SwingConstants.RIGHT);
        setupLabel(currentHeatBuildupL);

        currentHeatBuildupR = new JLabel("--", SwingConstants.LEFT);
        setupLabel(currentHeatBuildupR);

        wTargetExtraInfo = new JLabel();
        setupLabel(wTargetExtraInfo);

        int parentGridY = 0;
        wTargetExtraInfo.setMinimumSize(new Dimension(20, LINE_HEIGHT));
        pTargetInfo.add(wTargetExtraInfo,
              GBC.eol().fill(GridBagConstraints.BOTH)
                    .anchor(GridBagConstraints.WEST)
                    .insets(5, 1, 5, 1).gridY(parentGridY).gridX(0));
        parentGridY++;

        pTargetInfo.add(currentHeatBuildupL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(5, 1, 1, 1).gridY(parentGridY).gridX(0));

        pTargetInfo.add(currentHeatBuildupR,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(2, 1, 1, 1).gridY(parentGridY).gridX(1));

        pTargetInfo.add(wRangeL,
              GBC.std().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(15, 1, 1, 1).gridY(parentGridY).gridX(2));

        pTargetInfo.add(wRangeR,
              GBC.eol().fill(GridBagConstraints.NONE)
                    .anchor(GridBagConstraints.WEST)
                    .insets(5, 1, 5, 1).gridY(parentGridY).gridX(3));

        addSubDisplay(parent, pTargetInfo, LINE_HEIGHT * 2, GridBagConstraints.HORIZONTAL);
    }

    private void createToHitText(JPanel parent) {
        toHitText = new JTextPane();
        setupTextPane(toHitText);

        JScrollPane toHitScroll = new JScrollPane(toHitText);
        addSubDisplay(parent, toHitScroll, LINE_HEIGHT * 3, GridBagConstraints.BOTH);
    }

    private void createTargetDisplay(JPanel parent) {
        wTargetInfo = new JTextPane();
        setupTextPane(wTargetInfo);

        // The target's details can run longer than the space below the split, so they scroll rather than get cut off
        JScrollPane targetInfoScroll = new JScrollPane(wTargetInfo);
        addSubDisplay(parent, targetInfoScroll, LINE_HEIGHT * 2, GridBagConstraints.BOTH);
    }

    public void clearToHit() {
        state.clearToHit();
    }

    public void setToHit(ToHitData toHit) {
        state.setToHit(toHit);
    }

    public void setToHit(ToHitData toHit, boolean natAptGunnery) {
        state.setToHit(toHit, natAptGunnery);
    }

    public void setToHit(String message) {
        state.setToHit(message);
    }

    public void setTarget(@Nullable Targetable target, @Nullable String extraInfo) {
        state.setTarget(target, extraInfo);
    }

    @Override
    public void onResize() {
        int w = getSize().width;
        Rectangle r = getContentBounds();
        if (r == null) {
            return;
        }
        int dx = Math.round(((w - r.width) / 2.0f));
        int minLeftMargin = 8;
        if (dx < minLeftMargin) {
            dx = minLeftMargin;
        }
        int dy = 8;
        setContentMargins(dx, dy, dx, dy);
        revalidate();
        repaint();
    }

    private void setBackGround() {
        UnitDisplaySkinSpecification udSpec = SkinXMLHandler.getUnitDisplaySkin();

        Image tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBackgroundTile()).toString());
        PMUtil.setImage(tile, this);
        int b = BackGroundDrawer.TILING_BOTH;
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_HORIZONTAL | BackGroundDrawer.V_ALIGN_TOP;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getTopLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_HORIZONTAL | BackGroundDrawer.V_ALIGN_BOTTOM;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBottomLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_VERTICAL | BackGroundDrawer.H_ALIGN_LEFT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getLeftLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_VERTICAL | BackGroundDrawer.H_ALIGN_RIGHT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getRightLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_TOP
              | BackGroundDrawer.H_ALIGN_LEFT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getTopLeftCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_BOTTOM
              | BackGroundDrawer.H_ALIGN_LEFT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBottomLeftCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_TOP
              | BackGroundDrawer.H_ALIGN_RIGHT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getTopRightCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_BOTTOM
              | BackGroundDrawer.H_ALIGN_RIGHT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBottomRightCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));
    }

    /**
     * updates fields for the specified mek
     * <p>
     * fix the ammo when it's added
     */
    public void displayMek(Entity en) { state.displayMek(en); }

    public void refreshFromState() {
        if (syncing || state.getWeaponEntity() == null) { return; }
        syncing = true;
        removeListeners();
        try {
            renderEntity(state.getWeaponEntity());
            weaponList.setSelectedIndex(state.getSelectedWeapon() == null ? -1
                  : state.weapons().indexOf(state.getSelectedWeapon()));
            weaponList.ensureIndexIsVisible(weaponList.getSelectedIndex());
            displaySelected();
            String targetInfo = UnitToolTip.wrapWithHTML(state.getTargetText());
            if (!targetInfo.equals(shownTargetInfo)) {
                shownTargetInfo = targetInfo;
                wTargetInfo.setText(targetInfo);
                // Start at the top of the new target's details, not wherever the caret was left by setText
                wTargetInfo.setCaretPosition(0);
            }
            wTargetExtraInfo.setText(UnitToolTip.wrapWithHTML(state.getTargetExtra()));
            wTargetExtraInfo.setOpaque(!state.getTargetExtra().isEmpty());
            wRangeR.setText(state.getRange());
            toHitText.setText(UnitToolTip.wrapWithHTML(state.getToHit()));
            toHitText.setCaretPosition(0);
        } finally {
            addListeners();
            syncing = false;
        }
    }

    public void disposeDisplay() {
        state.removeChangeListener(stateListener);
        GUIP.removePreferenceChangeListener(this);
    }

    private void renderEntity(Entity en) {
        removeListeners();

        // Grab a copy of the game.
        Game game = null;

        if (unitDisplayPanel.getClientGUI() != null) {
            game = unitDisplayPanel.getClientGUI().getClient().getGame();
        }

        // update pointer to weapons
        entity = en;

        // update weapon list
        weaponList.setModel(new WeaponListModel(this, en));
        ((DefaultComboBoxModel<String>) m_chAmmo.getModel()).removeAllElements();

        m_chAmmo.setEnabled(false);
        m_chBayWeapon.removeAllItems();
        m_chBayWeapon.setEnabled(false);

        for (WeaponMounted mounted : state.weapons()) {
            ((WeaponListModel) weaponList.getModel()).addWeapon(mounted);
        }
        comboWeaponSortOrder.setSelectedItem(entity.getWeaponSortOrder());

        HeatBuildup heatBuildup = heatBuildup(game, en);
        currentHeatBuildupR.setForeground(GUIP.getColorForHeat(heatBuildup.overCapacity(), Color.WHITE));
        currentHeatBuildupR.setText(heatBuildup.text());

        // change what is visible based on type
        if (entity.usesWeaponBays()) {
            m_chBayWeapon.setVisible(true);
            wBayWeapon.setVisible(true);
        } else {
            m_chBayWeapon.setVisible(false);
            wBayWeapon.setVisible(false);
        }
        if ((!entity.isLargeCraft())
              || ((game != null) && (game.getOptions()
              .booleanOption(OptionsConstants.ADVANCED_AERO_RULES_HEAT_BY_BAY)))) {
            wArcHeatL.setVisible(false);
            wArcHeatR.setVisible(false);
        } else {
            wArcHeatL.setVisible(true);
            wArcHeatR.setVisible(true);
        }

        wDamageTrooperL.setVisible(false);
        wDamageTrooperR.setVisible(false);
        wInfantryRange0L.setVisible(false);
        wInfantryRange0R.setVisible(false);
        wInfantryRange1L.setVisible(false);
        wInfantryRange1R.setVisible(false);
        wInfantryRange2L.setVisible(false);
        wInfantryRange2R.setVisible(false);
        wInfantryRange3L.setVisible(false);
        wInfantryRange3R.setVisible(false);
        wInfantryRange4L.setVisible(false);
        wInfantryRange4R.setVisible(false);
        wInfantryRange5L.setVisible(false);
        wInfantryRange5R.setVisible(false);

        if (entity.isAero() && (entity.isAirborne() || entity.usesWeaponBays())) {
            wAVL.setVisible(true);
            wShortAVR.setVisible(true);
            wMedAVR.setVisible(true);
            wLongAVR.setVisible(true);
            wExtAVR.setVisible(true);
            wMinL.setVisible(false);
            wMinR.setVisible(false);
        } else {
            wAVL.setVisible(false);
            wShortAVR.setVisible(false);
            wMedAVR.setVisible(false);
            wLongAVR.setVisible(false);
            wExtAVR.setVisible(false);
            wMinL.setVisible(true);
            wMinR.setVisible(true);
        }

        // If MaxTech range rules are in play, display the extreme range.
        if (showsExtremeRange(game, entity)) {
            wExtL.setVisible(true);
            wExtR.setVisible(true);
        } else {
            wExtL.setVisible(false);
            wExtR.setVisible(false);
        }
        onResize();
        addListeners();
    }

    public int getSelectedEntityId() {
        return state.getSelectedEntityId();
    }

    /**
     * Selects the weapon with the specified weapon ID.
     */
    public void selectWeapon(int wn) {
        state.selectWeapon(wn);
    }

    public void selectWeapon(WeaponMounted weapon) {
        state.selectWeapon(weapon);
    }

    /**
     * @return the Mounted for the selected weapon in the weapon list.
     */
    public WeaponMounted getSelectedWeapon() {
        return state.getSelectedWeapon();
    }

    /** Existing target and to-hit presentation as a complete HTML tooltip. */
    public String getTargetSummary() {
        return state.getTargetSummary();
    }

    public String getTargetName() {
        return state.getTargetName();
    }

    /** Already-computed firing solution as an HTML fragment. */
    public String getFiringSolution() {
        return state.getFiringSolution();
    }

    /** Already-computed weapon statistics, including special infantry and aerospace presentations. */
    public String getWeaponSummary() {
        StringBuilder summary = new StringBuilder(wNameR.getText());
        appendWeaponStat(summary, Messages.getString("MekDisplay.Heat"), wHeatR);
        appendWeaponStat(summary, Messages.getString("MekDisplay.Damage"), wDamR);
        appendWeaponStat(summary, wArcHeatL.getText(), wArcHeatR);
        appendWeaponStat(summary, wDamageTrooperL.getText(), wDamageTrooperR);
        summary.append("<br>");
        appendWeaponStat(summary, wMinL.getText(), wMinR);
        appendWeaponStat(summary, wShortL.getText(), wShortR);
        appendWeaponStat(summary, wMedL.getText(), wMedR);
        appendWeaponStat(summary, wLongL.getText(), wLongR);
        appendWeaponStat(summary, wExtL.getText(), wExtR);
        if (wAVL.isVisible()) {
            summary.append("<br>").append(wAVL.getText());
            appendWeaponStat(summary, wShortL.getText(), wShortAVR);
            appendWeaponStat(summary, wMedL.getText(), wMedAVR);
            appendWeaponStat(summary, wLongL.getText(), wLongAVR);
            appendWeaponStat(summary, wExtL.getText(), wExtAVR);
        }
        appendWeaponStat(summary, wInfantryRange0L.getText(), wInfantryRange0R);
        appendWeaponStat(summary, wInfantryRange1L.getText(), wInfantryRange1R);
        appendWeaponStat(summary, wInfantryRange2L.getText(), wInfantryRange2R);
        appendWeaponStat(summary, wInfantryRange3L.getText(), wInfantryRange3R);
        appendWeaponStat(summary, wInfantryRange4L.getText(), wInfantryRange4R);
        appendWeaponStat(summary, wInfantryRange5L.getText(), wInfantryRange5R);
        return summary.append("<br>").append(Messages.getString("MekDisplay.HeatBuildup"))
              .append(" ").append(currentHeatBuildupR.getText()).toString();
    }

    private static void appendWeaponStat(StringBuilder summary, String label, JLabel value) {
        if (value.isVisible()) {
            summary.append("  ").append(label).append(" ").append(value.getText());
        }
    }

    public JComboBox<String> getAmmoSelector() {
        return m_chAmmo;
    }

    /**
     * @return the AmmoMounted currently selected by the ammo selector combo box, if any. The returned AmmoMounted may
     *       or may not be the ammo that is linked to the weapon.
     */
    public Optional<AmmoMounted> getSelectedAmmo() {
        return state.getSelectedAmmo();
    }

    /**
     * Returns the equipment ID number for the weapon currently selected
     */
    public int getSelectedWeaponNum() {
        return state.getSelectedWeaponNum();
    }

    /**
     * Selects the first valid weapon in the weapon list.
     */
    public void selectFirstWeapon() {
        state.selectFirstWeapon();
    }

    public int getNextWeaponListIdx() {
        WeaponMounted next = state.getNextWeapon();
        return next == null ? -1 : state.weapons().indexOf(next);
    }

    public int getPrevWeaponListIdx() {
        WeaponMounted previous = state.getPreviousWeapon();
        return previous == null ? -1 : state.weapons().indexOf(previous);
    }

    public int getNextWeaponNum() {
        return state.getNextWeaponNum();
    }

    public WeaponMounted getNextWeapon() {
        return state.getNextWeapon();
    }

    /**
     * Selects the next valid weapon in the weapon list.
     *
     * @return The weaponId for the selected weapon
     */
    public int selectNextWeapon() {
        return state.selectNextWeapon();
    }

    /**
     * Selects the previous valid weapon in the weapon list.
     *
     * @return The weaponId for the selected weapon
     */
    public int selectPrevWeapon() {
        return state.selectPrevWeapon();
    }

    /**
     * displays the selected item from the list in the weapon display panel.
     */
    private void displaySelected() {
        removeListeners();
        try {
            // short circuit if not selected
            if (weaponList.getSelectedIndex() == -1) {
            ((DefaultComboBoxModel<String>) m_chAmmo.getModel())
                  .removeAllElements();
            m_chAmmo.setEnabled(false);
            m_chBayWeapon.removeAllItems();
            m_chBayWeapon.setEnabled(false);
            wNameR.setText("");
            wHeatR.setText("--");
            wArcHeatR.setText("---");
            wDamR.setText("--");
            wMinR.setText("---");
            wShortR.setText("---");
            wMedR.setText("---");
            wLongR.setText("---");
            wExtR.setText("---");

            wDamageTrooperL.setVisible(false);
            wDamageTrooperR.setVisible(false);
            wInfantryRange0L.setVisible(false);
            wInfantryRange0R.setVisible(false);
            wInfantryRange1L.setVisible(false);
            wInfantryRange1R.setVisible(false);
            wInfantryRange2L.setVisible(false);
            wInfantryRange2R.setVisible(false);
            wInfantryRange3L.setVisible(false);
            wInfantryRange3R.setVisible(false);
            wInfantryRange4L.setVisible(false);
            wInfantryRange4R.setVisible(false);
            wInfantryRange5L.setVisible(false);
            wInfantryRange5R.setVisible(false);

            return;
        }

        WeaponMounted mounted = ((WeaponListModel) weaponList.getModel())
              .getWeaponAt(weaponList.getSelectedIndex());
        WeaponType weaponType = mounted.getType();
        // update weapon display
        wNameR.setText(mounted.getDesc());
        // Update the range display to account for the selected ammo, or the loaded ammo if none is selected
        AmmoMounted mAmmo = getSelectedAmmo().orElse(mounted.getLinkedAmmo());
        WeaponStats stats = stats((unitDisplayPanel.getClientGUI() != null)
              ? unitDisplayPanel.getClientGUI().getClient().getGame() : null, entity, mounted, mAmmo,
              weaponType.hasFlag(WeaponType.F_CWS) && isCwsSusceptibleTarget());
        wHeatR.setText(stats.heat());
        wArcHeatR.setText(stats.arcHeat());
        showInfantryRanges(stats);
        wDamR.setText(stats.damage());
        String extendedRangeTooltip = stats.extendedByCrew()
              ? Messages.getString("MekDisplay.ObliqueArtillerymanRange.tooltip") : null;
        wLongR.setToolTipText(extendedRangeTooltip);
        wExtR.setToolTipText(extendedRangeTooltip);
        showRanges(stats.min(), stats.shortRange(), stats.mediumRange(), stats.longRange(), stats.extremeRange());
        if (!stats.attackValues().isEmpty()) {
            showAttackValues(stats.attackValues());
        }

        m_chBayWeapon.removeAllItems();
        m_chBayWeapon.setEnabled(!mounted.getBayWeapons().isEmpty());
        for (WeaponMounted member : mounted.getBayWeapons()) {
            m_chBayWeapon.addItem(formatBayWeapon(member));
        }
        if (state.getBayWeapon() != null) {
            m_chBayWeapon.setSelectedIndex(mounted.getBayWeapons().indexOf(state.getBayWeapon()));
        }
        ((DefaultComboBoxModel<String>) m_chAmmo.getModel()).removeAllElements();
        AmmoChoices choices = state.ammoChoices();
        vAmmo = new ArrayList<>(choices.ammo());
        vAmmo.forEach(ammo -> m_chAmmo.addItem(formatAmmo(entity, ammo)));
        m_chAmmo.setEnabled(choices.feed() == AmmoChoices.Feed.BINS
              || (choices.feed() == AmmoChoices.Feed.CHAIN && !vAmmo.isEmpty()));
        if (!vAmmo.isEmpty()) {
            m_chAmmo.setSelectedIndex(state.getSelectedAmmo().map(vAmmo::indexOf).orElse(-1));
        } else if (choices.feed() == AmmoChoices.Feed.FIXED || choices.feed() == AmmoChoices.Feed.CHAIN) {
            WeaponMounted ammoWeapon = state.getBayWeapon() == null ? mounted : state.getBayWeapon();
            if (ammoWeapon.getLinked() != null) { m_chAmmo.addItem(formatAmmo(entity, ammoWeapon.getLinked())); }
        }
        onResize();
        } finally {
            addListeners();
        }
    }

    private String formatBayWeapon(WeaponMounted m) {
        return m.getDesc();
    }

    /** Whether the firing display's target is a unit susceptible to Centurion Weapon Systems. */
    private boolean isCwsSusceptibleTarget() {
        return (unitDisplayPanel.getClientGUI() != null)
              && (unitDisplayPanel.getClientGUI().getCurrentPanel() instanceof FiringDisplay firingDisplay)
              && (firingDisplay.getTarget() instanceof Entity target) && target.hasQuirk("susceptible_cws");
    }

    /**
     * Shows an infantry weapon's damage per trooper and range brackets instead of the range display, or the range
     * display for any other weapon.
     */
    private void showInfantryRanges(WeaponStats stats) {
        boolean infantry = !stats.byTrooper().isEmpty();
        wDamageTrooperL.setVisible(infantry);
        wDamageTrooperR.setVisible(infantry);
        JLabel[] brackets = { wInfantryRange0L, wInfantryRange1L, wInfantryRange2L, wInfantryRange3L, wInfantryRange4L,
                              wInfantryRange5L };
        JLabel[] modifiers = { wInfantryRange0R, wInfantryRange1R, wInfantryRange2R, wInfantryRange3R,
                               wInfantryRange4R, wInfantryRange5R };
        for (int bracket = 0; bracket < brackets.length; bracket++) {
            brackets[bracket].setVisible(false);
            modifiers[bracket].setVisible(false);
        }
        if (infantry) {
            wDamageTrooperR.setText(stats.byTrooper());
            // what a nightmare to set up all the range info for infantry weapons
            for (JLabel label : List.of(wMinL, wShortL, wMedL, wLongL, wExtL, wMinR, wShortR, wMedR, wLongR, wExtR)) {
                label.setVisible(false);
            }
            for (int bracket = 0; bracket < stats.infantryRanges().size(); bracket++) {
                brackets[bracket].setText(stats.infantryRanges().get(bracket).range());
                modifiers[bracket].setText(stats.infantryRanges().get(bracket).modifier());
                brackets[bracket].setVisible(true);
                modifiers[bracket].setVisible(true);
            }
        } else {
            for (JLabel label : List.of(wShortL, wMedL, wLongL, wShortR, wMedR, wLongR)) {
                label.setVisible(true);
            }
            if (!isAerospaceAttack(entity)) {
                wMinL.setVisible(true);
                wMinR.setVisible(true);
            }
            if (stats.extremeShown()) {
                wExtL.setVisible(true);
                wExtR.setVisible(true);
            }
        }
    }

    private void showRanges(String min, String shortRange, String mediumRange, String longRange, String extremeRange) {
        wMinR.setText(min);
        wShortR.setText(shortRange);
        wMedR.setText(mediumRange);
        wLongR.setText(longRange);
        wExtR.setText(extremeRange);
    }

    private void showAttackValues(List<String> attackValues) {
        wShortAVR.setText(attackValues.get(0));
        wMedAVR.setText(attackValues.get(1));
        wLongAVR.setText(attackValues.get(2));
        wExtAVR.setText(attackValues.get(3));
    }

    @Override
    public void valueChanged(ListSelectionEvent event) {
        if (!syncing && !event.getValueIsAdjusting() && event.getSource() == weaponList) {
            state.selectWeapon(((WeaponListModel) weaponList.getModel()).getWeaponAt(weaponList.getSelectedIndex()));
        }
    }

    @Override
    public void actionPerformed(ActionEvent ev) {
        if (syncing) { return; }
        if (ev.getSource() == m_chAmmo && m_chAmmo.getSelectedIndex() >= 0 && vAmmo != null
              && m_chAmmo.getSelectedIndex() < vAmmo.size()) {
            state.selectAmmo(vAmmo.get(m_chAmmo.getSelectedIndex()));
        } else if (ev.getSource() == m_chBayWeapon && m_chBayWeapon.getSelectedIndex() >= 0
              && state.getSelectedWeapon() != null) {
            state.selectBayWeapon(state.getSelectedWeapon().getBayWeapon(m_chBayWeapon.getSelectedIndex()));
        } else if (ev.getSource() == comboWeaponSortOrder) {
            state.setWeaponSortOrder(comboWeaponSortOrder.getSelectedItem());
        }
    }

    void setWeaponComparator(final @Nullable WeaponSortOrder weaponSortOrder) {
        state.setWeaponSortOrder(weaponSortOrder);
    }

    private void addListeners() {
        if (listenerCounter >= 0) {
            comboWeaponSortOrder.addActionListener(this);
            m_chAmmo.addActionListener(this);
            m_chBayWeapon.addActionListener(this);
            weaponList.addListSelectionListener(this);
        }
        listenerCounter++;
    }

    private void removeListeners() {
        comboWeaponSortOrder.removeActionListener(this);
        m_chAmmo.removeActionListener(this);
        m_chBayWeapon.removeActionListener(this);
        weaponList.removeListSelectionListener(this);
        listenerCounter--;
    }

    public Targetable getPrevTarget() {
        return state.getPrevTarget();
    }

    public void setPrevTarget(Targetable prevTarget) {
        state.setPrevTarget(prevTarget);
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent e) {
        // Update the text size when the GUI scaling changes
        if (e.getName().equals(GUIPreferences.UNIT_DISPLAY_WEAPON_LIST_HEIGHT)) {
            tWeaponScroll.setMinimumSize(new Dimension(500, GUIP.getUnitDisplayWeaponListHeight()));
            tWeaponScroll.setPreferredSize(new Dimension(500, GUIP.getUnitDisplayWeaponListHeight()));
            tWeaponScroll.revalidate();
            tWeaponScroll.repaint();
        }
    }

    /**
     * Updates the Weapon Panel with the information for the given entity. If the given entity is `null`, this method
     * will do nothing.
     *
     * @param entity - The weapon panel will update info based on the {@link Entity} provided.
     */
    public void updateForEntity(Entity entity) {
        state.updateForEntity(entity);
    }
}
