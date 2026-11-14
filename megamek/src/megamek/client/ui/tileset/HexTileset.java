/*
 * Copyright (c) 2000-2002 Ben Mazur (bmazur@sev.org)
 * Copyright (c) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 * Copyright (C) 2002-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.tileset;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.ui.util.ImageCache;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.HexTile;
import megamek.common.board.TilesetMatcher;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListener;
import megamek.common.event.board.GameBoardChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.common.game.IGame;
import megamek.common.util.ImageUtil;
import megamek.common.util.fileUtils.MegaMekFile;

/**
 * Matches each hex with an appropriate image: the tileset's text and its matching are the common {@link TilesetMatcher};
 * this keeps the images and their caches.
 *
 * @author Ben
 */
public class HexTileset implements BoardListener, AutoCloseable {
    /** The image width of a hex image. */
    public static final int HEX_W = HexTile.WIDTH;

    /** The image height of a hex image. */
    public static final int HEX_H = HexTile.HEIGHT;

    private final File imageRoot;
    /** Which entries and files each hex selects; shared with the 3D board for its own tileset. */
    private final TilesetMatcher matcher;
    /** Each entry's images in its file order, loaded on first use. */
    private final Map<TilesetMatcher.Entry, List<Image>> entryImages = new IdentityHashMap<>();
    /** The one empty image every key file stands for ({@link TilesetMatcher#image}). */
    private Image keyImage;
    private IGame observedGame;
    private GameListener gameListener;
    private final Set<Board> observedBoards = new java.util.HashSet<>();
    private final Map<Image, String> imageSources = new IdentityHashMap<>();
    private ImageCache<Hex, Image> basesCache = new ImageCache<>();
    private ImageCache<Hex, List<Image>> superimposedCache = new ImageCache<>();
    private ImageCache<Hex, List<Image>> orthographicCache = new ImageCache<>();
    /**
     * Whether a hex is drawn with the art its designs stand for ({@link BoardEditorBlueprint#artwork(Hex)}): the 2D board
     * of a game, whose {@code .board2} holds designs instead of the legacy tokens (building art, transitions, kerbs,
     * orchards, rough). The 3D board's artwork resolves designs itself, per owner.
     */
    private boolean designs;

    /**
     * Creates new HexTileset
     */
    public HexTileset(IGame game) {
        this(game, Configuration.hexesDir());
    }

    /** Explicitly managed artwork cache, without game or board listeners. */
    public HexTileset(File imageRoot) {
        this(imageRoot, new TilesetMatcher(imageRoot));
    }

    /** The images of an already loaded tileset text, such as the 3D board's one per JVM; no listeners. */
    public HexTileset(File imageRoot, TilesetMatcher matcher) {
        this.imageRoot = imageRoot;
        this.matcher = matcher;
    }

    /** A separate artwork root lets the 3D board keep its own editable tileset. */
    public HexTileset(IGame game, File imageRoot) {
        this(imageRoot);
        designs = true;
        // The Board and Game listeners
        // The HexTileSet caches images with the hex object as key. Therefore, it must listen to Board events to
        // clear changed (but not replaced) hexes from the cache. It must listen to Game events to catch when a board
        // is entirely replaced to be able to register itself to the new board.
        observedGame = game;
        gameListener = new GameListenerAdapter() {

            @Override
            public void gameBoardNew(GameBoardNewEvent e) {
                clearAllHexes();
                replacedBoard(e);
            }

            @Override
            public void gameBoardChanged(GameBoardChangeEvent e) {
                clearAllHexes();
            }

        };
        game.addGameListener(gameListener);
        for (Board board : game.getBoards().values()) {
            if (board != null && observedBoards.add(board)) {
                board.addBoardListener(this);
            }
        }
    }

    /** Clears the image cache for the given hex. */
    public synchronized void clearHex(Hex hex) {
        basesCache.remove(hex);
        superimposedCache.remove(hex);
        orthographicCache.remove(hex);
    }

    /** Clears the image cache for all hexes. */
    public synchronized void clearAllHexes() {
        basesCache = new ImageCache<>();
        superimposedCache = new ImageCache<>();
        orthographicCache = new ImageCache<>();
    }

    /**
     * This assigns images to a hex based on the best matches it can find.
     * <p>
     * First it assigns any images to be superimposed on a hex. These images must have a match value of 1.0 to be added,
     * and any time a match of this level is achieved, any terrain involved in the match is removed from further
     * consideration.
     * <p>
     * Any terrain left is used to match a base image for the hex. This time, a match can be any value, and the first,
     * best image is used.
     */
    public synchronized Object[] assignMatch(Hex hex) {
        TilesetMatcher.Selection selection = matcher.select(designs && !hex.getAppearance().isEmpty()
              ? BoardEditorBlueprint.get().artwork(hex) : hex);
        List<Image> orthographic = images(selection.orthographic());
        List<Image> superimposed = images(selection.supers());
        Image base = image(selection.base());
        Object[] pair = new Object[] { base, superimposed, orthographic };
        basesCache.put(hex, base);
        superimposedCache.put(hex, superimposed);
        orthographicCache.put(hex, orthographic);
        return pair;
    }

    private List<Image> images(List<TilesetMatcher.Pick> picks) {
        List<Image> result = new ArrayList<>(picks.size());
        for (TilesetMatcher.Pick pick : picks) {
            result.add(image(pick));
        }
        return result;
    }

    /** The image of a picked file; a blank one when no entry was selected or the entry names no file. */
    public synchronized Image image(TilesetMatcher.Pick pick) {
        if ((pick == null) || (pick.index() < 0)) {
            return ImageUtil.createAcceleratedImage(HEX_W, HEX_H);
        }
        return entryImages.computeIfAbsent(pick.entry(), this::loadImages).get(pick.index());
    }

    /**
     * The entry's images in its file order: a blank one for a file that is a key ({@link TilesetMatcher#image}), and
     * the loader's placeholder for a file it cannot read.
     */
    private List<Image> loadImages(TilesetMatcher.Entry entry) {
        List<Image> images = new ArrayList<>(entry.files().size());
        for (String filename : entry.files()) {
            if (!TilesetMatcher.image(filename)) {
                // A key, not an image: nothing to draw, and no layer for the blank test.
                if (keyImage == null) { keyImage = ImageUtil.createAcceleratedImage(HEX_W, HEX_H); }
                images.add(keyImage);
                continue;
            }
            File imgFile = new MegaMekFile(imageRoot, filename).getFile();
            Image image = ImageUtil.loadImageFromFile(imgFile.toString());
            images.add(image);
            imageSources.put(image, filename);
        }
        return images;
    }

    /** The levels of a decorative terrain this tileset has images for: {@link TilesetMatcher#definedFluffLevels}. */
    public List<Integer> definedFluffLevels(Hex structure, int fluffTerrain) {
        return matcher.definedFluffLevels(structure, fluffTerrain);
    }

    public synchronized Image getBase(Hex hex) {
        Image i = basesCache.get(hex);
        if (i == null) {
            Object[] pair = assignMatch(hex);
            return (Image) pair[0];
        }
        return i;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Image> getSupers(Hex hex) {
        List<Image> l = superimposedCache.get(hex);
        if (l == null) {
            Object[] pair = assignMatch(hex);
            return (List<Image>) pair[1];
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Image> getOrthographic(Hex hex) {
        List<Image> o = orthographicCache.get(hex);

        if (o == null) {
            Object[] pair = assignMatch(hex);
            return (List<Image>) pair[2];
        }

        return o;
    }

    /** Reads the tileset file {@code filename} and its includes ({@link TilesetMatcher#load}). */
    public void loadFromFile(String filename) throws IOException {
        matcher.load(filename);
    }

    public Set<String> getThemes() {
        return matcher.getThemes();
    }

    public synchronized String imageSource(Image image) {
        return imageSources.getOrDefault(image, "");
    }

    @Override
    public void close() {
        if (observedGame != null) {
            observedGame.removeGameListener(gameListener);
            observedGame = null;
        }
        observedBoards.forEach(board -> board.removeBoardListener(this));
        observedBoards.clear();
        clearAllHexes();
    }

    private void replacedBoard(GameBoardNewEvent e) {
        if (observedGame == null) {
            return;
        }
        if (e.getOldBoard() != null && !observedGame.getBoards().containsValue(e.getOldBoard())) {
            e.getOldBoard().removeBoardListener(this);
            observedBoards.remove(e.getOldBoard());
        }
        if (e.getNewBoard() != null && observedBoards.add(e.getNewBoard())) {
            e.getNewBoard().addBoardListener(this);
        }
    }

    @Override
    public void boardNewBoard(BoardEvent b) {
        clearAllHexes();
    }

    @Override
    public void boardChangedHex(BoardEvent b) {
        clearHex(((Board) b.getSource()).getHex(b.getCoords()));
    }

    @Override
    public void boardChangedAllHexes(BoardEvent b) {
        clearAllHexes();
    }
}
