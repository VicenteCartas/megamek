/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;

/**
 * The one capture of a Board as 3D scene tiles: the game ({@link GpuBoardSource}), the map preview and the editor
 * ({@link GpuMapSource}), the editor's samples and the tests all draw through it, with one {@link BoardArtwork}, one
 * invalidation rule and {@link BoardScene#captureTile}. Each hex is drawn as its {@code .board2} holds it
 * ({@link BoardSceneryLayouts#withoutTakenTokens}): the legacy scenery tokens the conversion took stay in a game's
 * memory for its 2D board only, and the conversion's objects stand for them here. Only overlays (the game's tactical
 * marking, the editor's markers) differ per view. Confined to its owner's thread: the EDT, or the samples' worker.
 */
final class BoardTiles implements AutoCloseable {
    private static final MMLogger LOGGER = MMLogger.create(BoardTiles.class);
    private final BoardArtwork artwork = new BoardArtwork();
    /** The owner's pool: its tactical pass interns into it too, and the owner retains its published tiles. */
    private final BoardScene.PixelPool pool;
    /** A game's board view's record of what its hexes have lost; null where tiles are captured without history. */
    private final java.util.function.Supplier<History> history;
    private Board board;
    /** The board's tiles in column order. */
    private List<BoardScene.Tile> tiles = List.of();
    /** Every hex is recaptured next; else only {@link #dirty}. */
    private boolean all = true;
    private final Set<Coords> dirty = new HashSet<>();
    /** A placement edit changes the pose of every saved share, including remote members. */
    private final Set<String> dirtyBuildings = new HashSet<>();
    /** The search searchAhead started, for {@code aheadBoard}'s first capture. Swing thread only. */
    private static Board aheadBoard;
    private static CompletableFuture<Map<Coords, List<BoardScene.Feature>>> ahead;
    /** The boards the tripwire reported, each once. */
    private final Set<Board> warned = Collections.newSetFromMap(new WeakHashMap<>());

    /** Tiles without history: the editor, a map preview, samples and tests draw the board as it is. */
    BoardTiles(BoardScene.PixelPool pool) {
        this(pool, null);
    }

    /** A game's tiles: they keep what shaped their ground when it is gone ({@link BoardScene.Tile#since}). */
    BoardTiles(BoardScene.PixelPool pool, java.util.function.Supplier<History> history) {
        this.pool = pool;
        this.history = history;
    }

    /**
     * What the hexes of a game's board have lost: the last tile of each hex whose footing is not what stands on it, or
     * whose ground was formed anew and is barren. The
     * board view keeps it ({@link GpuBoardState#captured}), so a new window, or a return from another board, captures
     * those hexes with their footing. Swing thread only.
     */
    static final class History {
        private java.lang.ref.WeakReference<Board> board = new java.lang.ref.WeakReference<>(null);
        private final Map<Coords, BoardScene.Tile> lost = new java.util.HashMap<>();

        /** {@code tile}, just captured at a hex of {@code board} whose tile in this capture's list was {@code previous}. */
        private BoardScene.Tile carry(Board board, BoardScene.Tile tile, BoardScene.Tile previous) {
            if (this.board.get() != board) {
                lost.clear();
                this.board = new java.lang.ref.WeakReference<>(board);
            }
            BoardScene.Tile before = previous != null ? previous : lost.get(tile.coords());
            if (before != null) { tile = tile.since(before); }
            if (tile.history() || tile.barren()) { lost.put(tile.coords(), tile); } else { lost.remove(tile.coords()); }
            return tile;
        }
    }

    /** Every tile of {@code board}, captured once: the editor's samples and tests. */
    static List<BoardScene.Tile> captureAll(Board board) {
        try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
            return capture.tiles(board, (coords, art) -> { });
        }
    }

    /**
     * The hex at {@code coords} changed, or with null every hex (or its art). The hex is recaptured with its six
     * neighbours: an edit changes their road exits and blends, and {@code Board.setHexes} fires no event for them.
     */
    void changed(Coords coords) {
        if (coords == null) {
            all = true;
            dirty.clear();
            dirtyBuildings.clear();
            artwork.clear();
            return;
        }
        int reach = 1;
        // A member can be remote from its owner. Losing its last reference changes the owner's prop fallback.
        if (board != null && board.contains(coords)) {
            // A single lane's way reaches a fork or merge beside it, and through that a two-lane road's ramp signs.
            int index = coords.getX() * board.getHeight() + coords.getY();
            if (megamek.common.board.SingleLane.single(board.getHex(coords))
                  || index < tiles.size() && BoardRoad.singleLane(tiles.get(index))) {
                reach = 2;
            }
            board.getHex(coords).getDecorations().stream().filter(object -> megamek.common.board.BuildingVolumes.of(object.asset()).present())
                  .forEach(object -> dirtyBuildings.add(object.id()));
            var current = board.getHex(coords).getBuildingReference();
            var previous = index < tiles.size() ? tiles.get(index).cover().reference() : null;
            for (var reference : new megamek.common.board.BuildingVolumes.Reference[] { current, previous }) {
                if (reference != null) {
                    dirtyBuildings.add(reference.id());
                    dirty.add(reference.anchor());
                    artwork.invalidate(reference.anchor());
                }
            }
        }
        for (int x = coords.getX() - reach; x <= coords.getX() + reach; x++) {
            for (int y = coords.getY() - reach; y <= coords.getY() + reach; y++) {
                Coords at = new Coords(x, y);
                dirty.add(at);
                artwork.invalidate(at);
            }
        }
    }

    /**
     * {@code hex} as the tile it would make at {@code coords} of {@code board}, captured as every tile is: for the
     * editor's paint preview. Its pixels go to a pool of their own, and the art cached for the coords is dropped again,
     * so the board's own tile there is captured anew from the board.
     */
    BoardScene.Tile preview(Board board, Coords coords, Hex hex) {
        return preview(board, coords, hex, board::getHex);
    }

    /** Preview neighbours come from the same pending edit, including rail corridors and ground clearance. */
    BoardScene.Tile preview(Board board, Coords coords, Hex hex, java.util.function.Function<Coords, Hex> pending) {
        return preview(board, coords, hex, pending, megamek.common.board.BuildingVolumes.referenceIds(board, pending));
    }

    BoardScene.Tile preview(Board board, Coords coords, Hex hex, java.util.function.Function<Coords, Hex> pending,
          Set<String> buildingReferences) {
        return preview(board, coords, hex, pending, buildingReferences, new BoardScene.PixelPool());
    }

    /** One pending event shares its immutable pixels, without retaining them in the document's pool. */
    BoardScene.Tile preview(Board board, Coords coords, Hex hex, java.util.function.Function<Coords, Hex> pending,
          Set<String> buildingReferences, BoardScene.PixelPool previewImages) {
        if (hex == null) { hex = pending.apply(coords); }
        if (hex == null) { return null; }
        artwork.invalidate(coords);
        try {
            return BoardScene.captureTile(hex, artwork.capture(board, coords, hex), null, previewImages, pending, buildingReferences);
        } finally { artwork.invalidate(coords); }
    }

    /** Rereads the artwork (the 3D views' asset reload); every hex is recaptured. */
    void reload() {
        artwork.reload();
        changed(null);
    }

    /**
     * Every tile of {@code board} in column order, recapturing the hexes changed since the last call, or all of them
     * for another board or size. {@code captured} sees each recaptured hex's art. A recaptured tile that is still equal
     * keeps its instance, and a call that recaptures nothing returns the same list.
     */
    List<BoardScene.Tile> tiles(Board board, BiConsumer<Coords, BoardArtwork.HexImage> captured) {
        int width = board.getWidth(), height = board.getHeight();
        // The tiles of another board, or of another size of this one, are not this board's last.
        boolean same = board == this.board && tiles.size() == width * height;
        History kept = history == null ? null : history.get();
        if (board != this.board || tiles.size() != width * height) {
            this.board = board;
            changed(null);
        }
        if (!all && dirty.isEmpty()) { return tiles; }
        Set<String> buildingReferences = megamek.common.board.BuildingVolumes.referenceIds(board, board::getHex);
        Collection<Coords> changed = all ? coordinates(board) : dirty.stream().filter(board::contains).toList();
        Map<Coords, List<BoardScene.Feature>> woods = woods(board, changed);
        List<BoardScene.Tile> next = tiles.size() == width * height ? new ArrayList<>(tiles)
              : new ArrayList<>(Collections.nCopies(width * height, null));
        for (Coords coords : changed) {
            int index = coords.getX() * height + coords.getY();
            Hex hex = drawn(board, coords);
            BoardArtwork.HexImage art = artwork.capture(board, coords, hex);
            BoardScene.Tile previous = next.get(index);
            BoardScene.Tile tile = BoardScene.captureTile(hex, art, previous, pool, board::getHex,
                  board::getBuildingAt, buildingReferences, woods.get(coords));
            if (kept != null) { tile = kept.carry(board, tile, same ? previous : null); }
            next.set(index, tile.equals(previous) ? previous : tile);
            captured.accept(coords, art);
        }
        if (!all && !dirtyBuildings.isEmpty()) {
            for (int index = 0; index < next.size(); index++) {
                var previous = next.get(index);
                var reference = previous.cover().reference();
                Coords at = previous.coords();
                if (reference == null || !dirtyBuildings.contains(reference.id()) || dirty.contains(at)) { continue; }
                Hex hex = drawn(board, at);
                // Only the owning decoration changed: reuse the member's cached terrain artwork.
                var art = artwork.capture(board, at, hex);
                var tile = BoardScene.captureTile(hex, art, previous, pool, board::getHex, board::getBuildingAt, buildingReferences);
                if (kept != null) { tile = kept.carry(board, tile, previous); }
                next.set(index, tile.equals(previous) ? previous : tile);
                captured.accept(at, art);
            }
        }
        tiles = List.copyOf(next);
        all = false;
        dirty.clear();
        dirtyBuildings.clear();
        return tiles;
    }

    /**
     * The hex as the 3D board draws it: without the tokens the conversion took. Meeting one on a board that is not
     * converted means a load site skipped the conversion: the tripwire reports it once per board and draws nothing for
     * it; the renderer never decodes legacy scenery itself.
     */
    /**
     * Runs {@code board}'s plant searches on the pool with the Swing thread free: a big board's take seconds, and they
     * draw nothing. Its first capture takes them (woods), once the caller found the result done. Call it on the Swing
     * thread, which holds the board still until then: no view of it is open yet.
     */
    static CompletableFuture<?> searchAhead(Board board) {
        List<Coords> whole = coordinates(board);
        aheadBoard = board;
        ahead = CompletableFuture.supplyAsync(() -> search(board, whole)).exceptionally(failure -> {
            LOGGER.warn(failure, "Searching the plants of {} ahead failed: its first capture searches them", board.getBoardName());
            return Map.of();
        });
        return ahead;
    }

    /** The woods of {@code hexes}: the search run ahead for this board's first capture, else one on the pool now. */
    private static Map<Coords, List<BoardScene.Feature>> woods(Board board, Collection<Coords> hexes) {
        if (board == aheadBoard) {
            var searched = ahead.join();
            aheadBoard = null;
            ahead = null;
            return searched;
        }
        return search(board, hexes);
    }

    private static List<Coords> coordinates(Board board) {
        List<Coords> hexes = new ArrayList<>(board.getWidth() * board.getHeight());
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) { hexes.add(new Coords(x, y)); }
        }
        return hexes;
    }

    private static Map<Coords, List<BoardScene.Feature>> search(Board board, Collection<Coords> hexes) {
        return hexes.parallelStream().collect(Collectors.toConcurrentMap(coords -> coords, coords -> BoardFeatures.woods(
              BoardSceneryLayouts.withoutTakenTokens(board.getHex(coords)), coords, board::getHex, BoardTreeDistribution.NATURAL)));
    }

    private Hex drawn(Board board, Coords coords) {
        Hex hex = board.getHex(coords);
        Hex drawn = BoardSceneryLayouts.withoutTakenTokens(hex);
        if (!board.isNativeFormat() && !warned.contains(board) && unconverted(hex, drawn)) {
            warned.add(board);
            LOGGER.warn("Unconverted legacy scenery (first at {} of {}): a load site skipped conversion; it is not drawn",
                  coords.getBoardNum(), board.getBoardName());
        }
        return drawn;
    }

    /**
     * Whether {@code hex} of a board that is not converted holds a token the conversion takes; {@code drawn} is the hex
     * without the tokens of a converted board. Road level 2 is a token only on a board that is not converted (the legacy
     * road-with-trees art, {@link BoardSceneryLayouts#imported}), so it is resolved that way here; the capture still draws
     * it as the native Alley finish.
     */
    static boolean unconverted(Hex hex, Hex drawn) {
        return drawn != hex || hex.terrainLevel(Terrains.ROAD) == 2
              && TilesetScenery.resolve(hex, false).layers().stream().anyMatch(TilesetScenery.Layer::taken);
    }

    @Override
    public void close() {
        artwork.close();
        tiles = List.of();
        board = null;
    }
}
