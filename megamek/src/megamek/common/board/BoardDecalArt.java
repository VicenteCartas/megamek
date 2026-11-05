/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;

/**
 * The 3D board's decal art (data/models/board/decals). A decal id {@code decal/<group>/<name>} is the image
 * {@code decals/<group>/<name>.png}, so no catalog is needed to find it. {@code decals/decals.json} gives each decal's
 * footprint and a car park's slots, and decodes the legacy decal ids (the 2D tileset's images) into decals.
 */
public final class BoardDecalArt {
    /** A hex in model px; a decal without a footprint entry covers one hex. */
    private static final Footprint HEX = new Footprint(HexTile.MODEL_WIDTH, HexTile.HEIGHT, List.of(), null);

    /** A parking place in the decal's frame: model px from its centre, and the car's heading (CCW degrees). */
    public record Slot(double x, double y, double heading) { }

    /**
     * The decal's size at scale 1 in model px, centred on the object's position, its car slots, and its paint colour
     * ({@code #rrggbb}) when it is one colour to paint in any other (lettering), else null.
     */
    public record Footprint(double width, double height, List<Slot> slots, String paint) { }

    /**
     * A legacy decal id's decal. A one-hex variant is the decal at {@code scale} and {@code rotation} (CCW degrees), in
     * its paint {@code colour} ({@code #rrggbb}) where the legacy image was painted in another colour than the decal's
     * own (black letters), else null. A piece of a 7-hex set ({@code set}) is one decal at scale 1 on the set's centre
     * hex; {@code direction} is the piece's hex direction from the centre (0 N to 5 NW, the order of
     * {@link Coords#translated(int)}), -1 for the centre piece.
     */
    public record Legacy(String decal, double scale, double rotation, boolean set, int direction, String colour) {
        /** The centre hex of the set this piece at {@code at} belongs to. */
        public Coords centre(Coords at) { return direction < 0 ? at : at.translated((direction + 3) % 6); }
    }

    private record Table(Map<String, Footprint> footprints, Map<String, Legacy> legacy) { }

    private static final class Holder {
        static final Table TABLE = read(new File(Configuration.dataDir(), "models/board/decals/decals.json"));
    }

    private BoardDecalArt() { }

    /** The image of decal {@code id} by path convention; it need not exist. */
    public static File image(String id) {
        return new File(Configuration.dataDir(), "models/board/decals/" + id.substring(id.indexOf('/') + 1) + ".png");
    }

    /** The decal ids of the art a billboard can show: the PNG files of {@code decals/billboards}, in name order. */
    public static List<String> billboards() {
        File[] files = image("decal/billboards/-").getParentFile().listFiles((folder, name) -> name.endsWith(".png"));
        if (files == null) { return List.of(); }
        return Arrays.stream(files).map(file -> "decal/billboards/" + file.getName().replaceFirst("\\.png$", "")).sorted().toList();
    }

    /** The pixel width and height in the header of decal {@code id}'s PNG image. */
    public static int[] imageSize(String id) throws IOException {
        try (var input = new DataInputStream(new FileInputStream(image(id)))) {
            input.skipBytes(16);
            return new int[] { input.readInt(), input.readInt() };
        }
    }

    public static Footprint footprint(String id) { return Holder.TABLE.footprints().getOrDefault(id, HEX); }

    /** The decal a legacy decal id decodes to, or null. */
    public static Legacy legacy(String id) { return Holder.TABLE.legacy().get(id); }

    /** Whether decal {@code id} is the emblem of a legacy 7-hex set ({@link Legacy#set}). */
    public static boolean setEmblem(String id) {
        return Holder.TABLE.legacy().values().stream().anyMatch(legacy -> legacy.set() && legacy.decal().equals(id));
    }

    private static Table read(File file) {
        if (!file.isFile()) { return new Table(Map.of(), Map.of()); }
        try {
            JsonNode root = new ObjectMapper().readTree(file);
            Map<String, Footprint> footprints = new HashMap<>();
            root.path("decals").fields().forEachRemaining(entry -> {
                JsonNode size = entry.getValue().path("size");
                List<Slot> slots = new ArrayList<>();
                for (JsonNode slot : entry.getValue().path("slots")) {
                    slots.add(new Slot(slot.path("position").get(0).asDouble(), slot.path("position").get(1).asDouble(),
                          slot.path("heading").asDouble()));
                }
                footprints.put(entry.getKey(), new Footprint(size.get(0).asDouble(), size.get(1).asDouble(), List.copyOf(slots),
                      entry.getValue().hasNonNull("colour") ? entry.getValue().get("colour").asText() : null));
            });
            Map<String, Legacy> legacy = new HashMap<>();
            root.path("legacy").fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                legacy.put(entry.getKey(), new Legacy(value.path("decal").asText(), value.path("scale").asDouble(1),
                      value.path("rotation").asDouble(0), value.path("set").asBoolean(false), value.path("direction").asInt(-1),
                      value.hasNonNull("colour") ? value.get("colour").asText() : null));
            });
            return new Table(Map.copyOf(footprints), Map.copyOf(legacy));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the 3D board's decal table " + file, failure);
        }
    }
}
