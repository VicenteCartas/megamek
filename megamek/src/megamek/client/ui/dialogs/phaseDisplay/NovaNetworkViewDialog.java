/*
 * Copyright (C) 2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.phaseDisplay;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.Serial;
import java.util.List;
import javax.swing.*;

import megamek.client.lobby.NovaNetworkForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.util.UIUtil;
import megamek.logging.MMLogger;

/**
 * Read-only dialog for viewing Nova CEWS network configurations. Displays current network status without allowing
 * modifications. Can be opened at any time during the game from View menu; over the battle window the native card
 * shows the same {@link NovaNetworkForm} instead.
 *
 * @author MegaMek Team (with Claude Code assistance)
 */
public class NovaNetworkViewDialog extends JDialog implements ActionListener {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final MMLogger logger = MMLogger.create(NovaNetworkViewDialog.class);
    private static final int PADDING = UIUtil.scaleForGUI(10);
    private static final int PADDING_SMALL = UIUtil.scaleForGUI(5);

    private final NovaNetworkForm form;

    // UI Components
    private DefaultListModel<String> unitListModel;
    private JButton btnClose;

    public NovaNetworkViewDialog(JFrame parent, ClientGUI clientGUI) {
        super(parent, Messages.getString("NovaNetworkViewDialog.title"), true);
        logger.debug("Opening Nova CEWS Network View Dialog");
        form = NovaNetworkForm.of(clientGUI.getClient().getGame(), clientGUI.getClient().getLocalPlayer().getId());

        initializeUI();

        pack();
        setLocationRelativeTo(parent);
        logger.debug("Nova CEWS View Dialog initialized: {} player units, {} allied units",
              form.yours().size(), form.allied().size());
    }

    /**
     * Initializes the UI components.
     */
    private void initializeUI() {
        setLayout(new BorderLayout(PADDING, PADDING));
        setResizable(false);

        // Main panel with padding
        JPanel mainPanel = new JPanel(new BorderLayout(PADDING, PADDING));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));

        // Top: Instructions
        JLabel instructions = new JLabel(Messages.getString("NovaNetworkViewDialog.instructions"));
        instructions.setBorder(BorderFactory.createEmptyBorder(0, 0, PADDING, 0));
        mainPanel.add(instructions, BorderLayout.NORTH);

        // Center: Unit list
        JPanel centerPanel = new JPanel(new BorderLayout(PADDING_SMALL, PADDING_SMALL));

        JLabel unitListLabel = new JLabel(Messages.getString("NovaNetworkViewDialog.unitListLabel"));
        centerPanel.add(unitListLabel, BorderLayout.NORTH);

        unitListModel = new DefaultListModel<>();
        populateUnitList();

        JList<String> unitList = new JList<>(unitListModel);
        unitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        unitList.setVisibleRowCount(10);

        JScrollPane scrollPane = new JScrollPane(unitList);
        scrollPane.setPreferredSize(UIUtil.scaleForGUI(500, 200));
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // Bottom: Close button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, PADDING, PADDING));

        btnClose = new JButton(Messages.getString("NovaNetworkViewDialog.btnClose"));
        btnClose.addActionListener(this);

        buttonPanel.add(btnClose);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    /**
     * Populates the unit list with player's and allied Nova CEWS units.
     */
    private void populateUnitList() {
        unitListModel.clear();

        if (!form.yours().isEmpty()) {
            unitListModel.addElement(Messages.getString("NovaNetworkDialog.sectionHeader",
                  Messages.getString("NovaNetworkViewDialog.yourUnits")));
            form.yours().forEach(unit -> unitListModel.addElement(formatUnitDisplay(unit)));
        }

        if (!form.allied().isEmpty()) {
            if (!form.yours().isEmpty()) {
                unitListModel.addElement(""); // Spacer
            }
            unitListModel.addElement(Messages.getString("NovaNetworkDialog.sectionHeader",
                  Messages.getString("NovaNetworkViewDialog.alliedUnits")));
            form.allied().forEach(unit -> unitListModel.addElement(formatUnitDisplay(unit)));
        }
    }

    /**
     * Formats a unit for display in the list (read-only, no pending changes).
     */
    private static String formatUnitDisplay(NovaNetworkForm.Unit unit) {
        StringBuilder text = new StringBuilder();
        text.append(Messages.getString("NovaNetworkDialog.entityIdFormat", unit.id(), unit.name()));

        if (unit.linked()) {
            List<String> memberIds = unit.partners().stream()
                  .map(partner -> Messages.getString("NovaNetworkDialog.memberId", partner.id()))
                  .toList();
            String availability = unit.full()
                  ? Messages.getString("NovaNetworkDialog.networkFull")
                  : Messages.getString("NovaNetworkDialog.networkAvailable");
            text.append(Messages.getString("NovaNetworkDialog.networkStatus",
                  String.join(", ", memberIds), unit.networkSize(), availability));
        } else {
            text.append(Messages.getString("NovaNetworkDialog.unlinked"));
        }

        if (unit.owner() != null) {
            text.append(Messages.getString("NovaNetworkDialog.ownerSuffix", unit.owner()));
        }
        return text.toString();
    }

    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        if (actionEvent.getSource() == btnClose) {
            logger.debug("Closing Nova CEWS View Dialog");
            dispose();
        }
    }
}
