/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import megamek.MMConstants;
import megamek.SuiteConstants;
import megamek.Version;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.logging.MMLogger;
import megamek.utilities.xml.MMXMLUtility;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The start screen's Load game page rules: which saves it lists, and what a save says about itself before the server
 * loads it (the version that wrote it and its players), read by hand since the server's reader needs a running server.
 */
final class StartSaves {
    private static final MMLogger LOGGER = MMLogger.create(StartSaves.class);

    /** Why a save can't be loaded. */
    enum Problem { UNREADABLE, NO_VERSION, WRONG_VERSION }

    /** One of a save's players: its name, and whether a bot played it or it only watched. */
    record Seat(String name, boolean bot, boolean observer) { }

    /**
     * What a save says about itself.
     *
     * @param seats   its players, in the save's order; the player takes one of them
     * @param problem why it can't be loaded, or null when it can
     * @param version the version that wrote it, or null when it has none or can't be read
     */
    record Summary(File file, List<Seat> seats, Problem problem, Version version) {
        Summary {
            seats = List.copyOf(seats);
        }

        boolean loadable() {
            return problem == null;
        }

        /** Its players' names, in the save's order. */
        List<String> players() {
            return seats.stream().map(Seat::name).toList();
        }

        /** The players a bot played, which a bot takes back when it loads. */
        List<String> bots() {
            return seats.stream().filter(Seat::bot).map(Seat::name).toList();
        }

        /** The players people played, not bots and not observers. */
        List<String> humans() {
            return seats.stream().filter(seat -> !seat.bot() && !seat.observer()).map(Seat::name).toList();
        }

        /**
         * The player who loads this save at once, without its options: {@code lastName}, when that is the save's only
         * person and so nobody else need be asked about; null when someone has to choose.
         */
        String quickPlayer(String lastName) {
            List<String> humans = humans();
            return loadable() && humans.size() == 1 && humans.get(0).equals(lastName) ? lastName : null;
        }
    }

    private StartSaves() {
    }

    /** Whether {@code file} is a save game by its name. */
    static boolean isSave(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (name.endsWith(MMConstants.SAVE_FILE_EXT) || name.endsWith(MMConstants.SAVE_FILE_GZ_EXT));
    }

    /** The saves in {@code directory}, not its subdirectories, the newest first; empty when it doesn't exist. */
    static List<File> list(File directory) {
        File[] files = directory.listFiles(StartSaves::isSave);
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files)
              .sorted(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName))
              .toList();
    }

    /** {@code file}'s name without the save extension, as the list shows it. */
    static String title(File file) {
        String name = file.getName();
        for (String extension : List.of(MMConstants.SAVE_FILE_GZ_EXT, MMConstants.SAVE_FILE_EXT)) {
            if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
                return name.substring(0, name.length() - extension.length());
            }
        }
        return name;
    }

    /**
     * How long ago {@code then} was, from {@code now}, both in epoch milliseconds: "just now", minutes, hours, yesterday
     * and days, and the date beyond a week.
     */
    static String ago(long then, long now) {
        long minutes = Math.max(0, now - then) / 60_000;
        if (minutes < 1) {
            return Messages.getString("StartScreen.agoNow");
        } else if (minutes < 60) {
            return Messages.getString(minutes == 1 ? "StartScreen.agoMinute" : "StartScreen.agoMinutes", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return Messages.getString(hours == 1 ? "StartScreen.agoHour" : "StartScreen.agoHours", hours);
        }
        long days = hours / 24;
        if (days == 1) {
            return Messages.getString("StartScreen.agoYesterday");
        } else if (days < 7) {
            return Messages.getString("StartScreen.agoDays", days);
        }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
              .format(Instant.ofEpochMilli(then).atZone(ZoneId.systemDefault()));
    }

    /** Blocks: reads {@code file}'s version and players. */
    static Summary read(File file) {
        List<Seat> players = new ArrayList<>();
        Version version = null;
        // As the old Load game dialog: a save without a version node loads; one with an empty node doesn't
        boolean versioned = true;
        try (InputStream is = new FileInputStream(file);
              InputStream in = file.getName().toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(is) : is) {
            // Raises the JAXP entity size limit if a large save exceeds it
            Document document = MMXMLUtility.parseDocument(in);
            Element game = document.getDocumentElement();
            game.normalize();
            NodeList children = game.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                switch (child.getNodeName()) {
                    case "version" -> {
                        versioned = child.hasChildNodes();
                        if (versioned) {
                            version = version(child);
                        }
                    }
                    case "players" -> playerNames(child, players);
                    default -> {
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.error(ex, "The start screen could not read the save {}", file);
            return new Summary(file, List.of(), Problem.UNREADABLE, null);
        }
        Problem problem = null;
        if (!ignoreVersion()) {
            if (!versioned) {
                problem = Problem.NO_VERSION;
            } else if (version != null && !loads(version, SuiteConstants.VERSION)) {
                problem = Problem.WRONG_VERSION;
            }
        }
        return new Summary(file, players, problem, version);
    }

    private static boolean ignoreVersion() {
        return "true".equalsIgnoreCase(System.getenv("IGNORE_VERSION_VALIDATION"));
    }

    /**
     * Whether {@code running} loads a save {@code saved} wrote: one from the same release and the same or an older
     * revision of it, as {@link Version#canLoadSaveFrom} has it, since MegaMek doesn't migrate saves across releases.
     */
    static boolean loads(Version saved, Version running) {
        return running.canLoadSaveFrom(saved);
    }

    /** The {@link Version} a save was written by, from the components stored in its version node. */
    private static Version version(Node versionNode) {
        NodeList components = versionNode.getChildNodes();
        String major = null;
        String minor = null;
        String patch = null;
        String revision = null;
        String extra = null;
        for (int i = 0; i < components.getLength(); i++) {
            Node component = components.item(i);
            if (component.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (component.getNodeName()) {
                case "major" -> major = component.getTextContent();
                case "minor" -> minor = component.getTextContent();
                case "patch" -> patch = component.getTextContent();
                // Absent from saves written by an ordinary release, and by every release before it existed
                case "revision" -> revision = component.getTextContent();
                case "snapshot", "extra" -> extra = component.getTextContent();
                default -> {
                }
            }
        }
        return new Version(major, minor, patch, revision, extra);
    }

    private static void playerNames(Node playersNode, List<Seat> seats) {
        NodeList entries = playersNode.getChildNodes();
        for (int i = 0; i < entries.getLength(); i++) {
            Node entry = entries.item(i);
            if (entry.getNodeType() != Node.ELEMENT_NODE || !"entry".equals(entry.getNodeName())) {
                continue;
            }
            NodeList entryChildren = entry.getChildNodes();
            for (int k = 0; k < entryChildren.getLength(); k++) {
                Node player = entryChildren.item(k);
                if (player.getNodeType() != Node.ELEMENT_NODE || !Player.class.getName().equals(player.getNodeName())) {
                    continue;
                }
                String name = null;
                boolean bot = false;
                boolean observer = false;
                NodeList fields = player.getChildNodes();
                for (int j = 0; j < fields.getLength(); j++) {
                    Node field = fields.item(j);
                    if (field.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }
                    switch (field.getNodeName()) {
                        case "name" -> name = field.getTextContent();
                        case "bot" -> bot = "true".equals(field.getTextContent().trim());
                        case "observer" -> observer = "true".equals(field.getTextContent().trim());
                        default -> {
                        }
                    }
                }
                if (name != null) {
                    seats.add(new Seat(name, bot, observer));
                }
            }
        }
    }
}
