/*
 * Copyright (C) 2013 Jay Lawson
 * Copyright (C) 2013-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.Serial;
import javax.swing.*;

import megamek.MegaMek;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.CloseAction;
import megamek.client.ui.dialogs.unitEditor.DamageEditorDiagram;
import megamek.client.ui.dialogs.unitEditor.UnitDamageControls;
import megamek.client.ui.dialogs.unitEditor.UnitDamagePanelBuilder;
import megamek.client.ui.dialogs.unitEditor.UnitDamageSpecBuilder;
import megamek.client.ui.preferences.JSplitPanePreference;
import megamek.client.ui.preferences.JWindowPreference;
import megamek.client.ui.preferences.PreferencesNode;
import megamek.client.ui.util.UIUtil;
import megamek.common.units.DamageEditApplier;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * This dialog will allow the user to edit the damage and status characteristics of a unit. It is used by MekHQ and
 * MegaMekLab, so don't go messing things up for them by changing a bunch of stuff. MegaMek's own lobby and Game Master
 * damage editor is the native {@code megamek.client.lobby.UnitDamageForm}.
 *
 * @author Jay Lawson (jaylawson39 at yahoo.com)
 */
public class UnitEditorDialog extends JDialog {
    private static final MMLogger LOGGER = MMLogger.create(UnitEditorDialog.class);

    @Serial
    private static final long serialVersionUID = 8144354264100884817L;

    private final Entity entity;

    /** Names the dialog for the stored size and position. */
    private static final String DIALOG_NAME = "unitEditorDialog";

    /** The smallest the dialog may be resized to, before GUI scaling, so the paperdoll and panel stay usable. */
    private static final int MIN_DIALOG_WIDTH = 650;
    private static final int MIN_DIALOG_HEIGHT = 500;

    /** The editor's controls, built by the panel builder and read back by the applier. */
    private final UnitDamageControls controls = new UnitDamageControls();
    /** The armor diagram beside the location panels; absent for conventional infantry, which has no diagram. */
    private DamageEditorDiagram diagram;

    /** The window to center on the first time this dialog is opened, before it has a remembered position. */
    private final JFrame parent;

    /**
     * Opens the damage editor for the unit, as used by MekHQ and MegaMekLab. The edits are applied to the unit itself
     * when the dialog is confirmed.
     *
     * @param parent the parent frame
     * @param entity the unit to edit damage for
     */
    public UnitEditorDialog(JFrame parent, Entity entity) {
        super(parent, true);
        this.entity = entity;
        this.parent = parent;
        initComponents();
    }

    private void initComponents() {
        getContentPane().setLayout(new BorderLayout());

        setTitle("Edit damage for " + entity.getDisplayName());

        JPanel panMain = new JPanel(new GridBagLayout());
        JPanel panButtons = new JPanel(new GridLayout(1, 2));

        UnitDamagePanelBuilder panelBuilder = new UnitDamagePanelBuilder(entity, controls);
        if (entity.isConventionalInfantry()) {
            GridBagConstraints gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 0;
            gridBagConstraints.anchor = GridBagConstraints.NORTHWEST;
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 1.0;
            panMain.add(panelBuilder.initInfantryPanel(), gridBagConstraints);
        } else {
            panelBuilder.build();
            diagram = new DamageEditorDiagram(entity, controls);
            GridBagConstraints gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 0;
            gridBagConstraints.insets = new Insets(4, 4, 4, 4);
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 1.0;
            panMain.add(diagram, gridBagConstraints);
        }

        getContentPane().add(new JScrollPane(panMain), BorderLayout.CENTER);

        JButton butOK = new JButton(Messages.getString("Okay"));
        butOK.addActionListener(evt -> commitDamageAndClose());
        JButton butCancel = new JButton(Messages.getString("Cancel"));
        butCancel.addActionListener(evt -> setVisible(false));

        panButtons.add(butOK);
        panButtons.add(butCancel);

        getContentPane().add(panButtons, BorderLayout.PAGE_END);

        String closeAction = "closeAction";
        final KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, closeAction);
        getRootPane().getInputMap(JComponent.WHEN_FOCUSED).put(escape, closeAction);
        getRootPane().getActionMap().put(closeAction, new CloseAction(this));

        // The armor diagram builds its map sets when it becomes displayable, so it can only be drawn after the
        // first pack. It sizes itself to its content, so pack again once it has been drawn and enlarged.
        pack();
        if (diagram != null) {
            diagram.refresh();
            diagram.enlargeToFillDialog();
        }
        pack();

        // leave room for scroll bars and never grow beyond the screen
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(getWidth() + UIUtil.scaleForGUI(30), (int) (screenSize.width * 0.9)),
              Math.min(getHeight() + UIUtil.scaleForGUI(30), (int) (screenSize.height * 0.9)));

        // Keep a usable floor when the user resizes, but never force the dialog larger than what already fits.
        Dimension minimumSize = UIUtil.scaleForGUI(MIN_DIALOG_WIDTH, MIN_DIALOG_HEIGHT);
        minimumSize.width = Math.min(minimumSize.width, getWidth());
        minimumSize.height = Math.min(minimumSize.height, getHeight());
        setMinimumSize(minimumSize);

        // Center on the parent first, for the first time the dialog is ever opened. setPreferences comes after, so
        // that a size, position and divider the user chose before win over both the centering and the size worked
        // out above; centering afterwards would throw the remembered position away.
        setLocationRelativeTo(parent);
        setPreferences();
    }

    /**
     * Restores the size, position and divider location the user last left the dialog with, and keeps them up to
     * date as the dialog is moved, resized and its divider dragged.
     */
    private void setPreferences() {
        try {
            setName(DIALOG_NAME);
            PreferencesNode preferences = MegaMek.getMMPreferences().forClass(UnitEditorDialog.class);
            preferences.manage(new JWindowPreference(this));
            if (diagram != null) {
                preferences.manage(new JSplitPanePreference(diagram));
            }
        } catch (Exception ex) {
            // a dialog that cannot remember where it was is still perfectly usable
            LOGGER.error(ex, "Could not set the preferences of the unit editor dialog");
        }
    }

    /** Applies the shown damage to the unit and closes the dialog; the caller sends the edited unit on. */
    private void commitDamageAndClose() {
        DamageEditSpec spec = new UnitDamageSpecBuilder(entity, controls).build();
        new DamageEditApplier(entity, spec).applyToEntity();
        setVisible(false);
    }

    /** The editor's controls, so that a test can set them the way a user would. */
    UnitDamageControls controlsForTesting() {
        return controls;
    }

    /** The armor diagram, so that a test can drive it the way a user would; {@code null} for conventional infantry. */
    DamageEditorDiagram diagramForTesting() {
        return diagram;
    }
}
