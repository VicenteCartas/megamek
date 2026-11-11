/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.logging.MMLogger;

/**
 * A palette card that shows a model: the entry's {@code id} and the {@code asset} drawn.
 * <p>
 * A card the editor draws (a model's, a sample board's) has a picture by its id ({@link #picture}): the shipped one, else
 * the one the editor kept in the user's folder when it first drew the card ({@link #cache}). The editor draws a card only
 * while it has neither ({@link GpuBoardEditor}).
 * <p>
 * {@link #main} draws the shipped pictures again, exactly as the editor draws a card without one: every model card, and
 * the sample cards of the default theme. Nothing notices a model or a surface that changed after its picture was drawn:
 * run it (Gradle {@code bakeEditorThumbnails}) after changing what the palette shows, or the palette keeps the old
 * picture. Reload assets forgets the kept ones ({@link #clearCache}).
 */
record GpuEditorThumbnail(String id, String asset) {
    private static final MMLogger LOGGER = MMLogger.create(GpuEditorThumbnail.class);
    /** A change of at most this many levels in every channel of every pixel is a redraw of the same picture. */
    private static final int SAME = 2;

    /** An object's card, unless it has a picture of its own or is a decal. */
    static GpuEditorThumbnail of(BoardEditorBlueprint.Asset asset) {
        return asset.thumbnail().isEmpty() && asset.kind().equals("prop")
              ? new GpuEditorThumbnail(asset.id(), asset.id()) : null;
    }

    /** A design's card, unless it has a picture of its own or shows a sample board (a design without a model, a generated building). */
    static GpuEditorThumbnail of(BoardEditorBlueprint.Variant variant) {
        return variant.thumbnail().isEmpty() && !variant.asset().isEmpty() && !BoardMultiHexBuilding.supports(variant.asset())
              ? new GpuEditorThumbnail(variant.id(), variant.asset()) : null;
    }

    /** A category tile's id. */
    static String category(String id) { return "category/" + id; }

    /**
     * A sample card's id: a theme's card (no {@code component}), else the component in that theme in its default
     * {@code look} (""), a design (a variant id), a preset ("preset:id") or a field's choice.
     */
    static String sample(String theme, String component, String look) {
        return "sample/" + (theme.isEmpty() ? "grass" : theme) + (component.isEmpty() ? "" : "/" + component)
              + (look.isEmpty() ? "" : "/" + look);
    }

    /** The picture of the card named: the shipped one, else the one kept at its first draw; null while it has neither. */
    static File picture(String id) {
        File shipped = file(shipped(), id);
        if (shipped.isFile()) { return shipped; }
        File kept = file(cache(), id);
        return kept.isFile() ? kept : null;
    }

    /** Keeps the picture the editor drew for the card named, for the next time the card shows. */
    static void cache(String id, FrameBuffer drawn) {
        try { write(drawn, file(cache(), id)); }
        catch (RuntimeException failure) { LOGGER.warn(failure, "No kept editor picture of {}", id); }
    }

    /** Forgets the kept pictures: what they show may have changed. */
    static void clearCache() { removeOthers(cache(), cache(), List.of()); }

    private static File shipped() { return new File(Configuration.dataDir(), "board-editor/thumbnails"); }

    private static File cache() { return new File(Configuration.configDir(), "board-editor-thumbnails"); }

    /** An id's file: its path below the folder, any character a theme or a value brings and a path cannot hold replaced. */
    private static File file(File folder, String id) { return new File(folder, id.replaceAll("[^\\w/ -]", "_") + ".png"); }

    /**
     * Writes the shipped pictures into the folder named (the data folder's, if none), and removes those of no card: the
     * model cards, the category tiles, the themes' cards and the default theme's presets, designs and default looks.
     */
    public static void main(String[] args) {
        File folder = (args.length > 0 ? new File(args[0]) : shipped()).getAbsoluteFile();
        var blueprint = BoardEditorBlueprint.get();
        boolean[] complete = new boolean[1];
        var configuration = GpuBoardWindow.configuration(false);
        configuration.useVsync(false); configuration.setForegroundFPS(0);
        new Lwjgl3Application(new ApplicationAdapter() {
            private Stage stage;
            private GpuEditorModelPreviews models;
            private GpuEditorTerrainPreviews samples;
            private GpuTerrain terrain;
            private final Map<String, Image> images = new LinkedHashMap<>();

            /** A preview is drawn for an image on a stage, as a card's is. */
            private Image image(String id) {
                Image image = new Image(); stage.addActor(image); images.put(id, image);
                return image;
            }

            private void sample(String theme, String component, String look) {
                samples.add(theme, component, look, image(GpuEditorThumbnail.sample(theme, component, look)));
            }

            @Override public void create() {
                stage = new Stage(new ScreenViewport());
                models = new GpuEditorModelPreviews(); samples = new GpuEditorTerrainPreviews();
                // The samples draw with a board renderer's assets, as the editor's do with its board's.
                terrain = new GpuTerrain(); terrain.setEditable(true);
                List<GpuEditorThumbnail> cards = new ArrayList<>();
                blueprint.assets().forEach(asset -> cards.add(of(asset)));
                blueprint.variants().forEach(variant -> cards.add(of(variant)));
                cards.removeIf(java.util.Objects::isNull);
                cards.forEach(card -> models.add(card.asset(), image(card.id())));
                blueprint.categories().forEach(category -> samples.add(category, image(category(category.id()))));
                java.util.stream.Stream.of(List.of("grass"), BoardArtwork.themes(), blueprint.specialThemes())
                      .flatMap(List::stream).distinct().forEach(theme -> sample(theme, "", ""));
                for (var component : blueprint.components()) {
                    component.presets().forEach(preset -> sample("", component.id(), "preset:" + preset.id()));
                    var designs = blueprint.variants(component.id());
                    if (!designs.isEmpty()) { sample("", component.id(), ""); }
                    designs.stream().filter(design -> design.thumbnail().isEmpty() && of(design) == null)
                          .forEach(design -> sample("", component.id(), design.id()));
                }
            }

            @Override public void render() {
                models.update(); samples.update(terrain);
                if (models.pending() + samples.pending() > 0) { return; }
                int written = 0, missing = 0;
                for (var entry : images.entrySet()) {
                    FrameBuffer drawn = models.drawn(entry.getValue());
                    if (drawn == null) { drawn = samples.drawn(entry.getValue()); }
                    if (drawn == null) { missing++; System.out.println("No picture of " + entry.getKey()); }
                    else if (write(drawn, file(folder, entry.getKey()))) { written++; }
                }
                int removed = removeOthers(folder, folder, images.keySet().stream().map(id -> file(folder, id)).toList());
                System.out.println(images.size() + " cards in " + folder + ": " + written + " written, " + removed + " removed, "
                      + missing + " without a picture");
                complete[0] = missing == 0;
                samples.dispose(); terrain.dispose(); models.dispose(); stage.dispose();
                Gdx.app.exit();
            }
        }, configuration);
        // A driver's thread teardown has hung at exit (GpuGlsl.SharedCompiler).
        Runtime.getRuntime().halt(complete[0] ? 0 : 1);
    }

    /** Writes the picture unless the file holds the same one already; whether it wrote. */
    private static boolean write(FrameBuffer drawn, File file) {
        int width = drawn.getWidth(), height = drawn.getHeight();
        Pixmap picture = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        drawn.bind();
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, picture.getPixels());
        FrameBuffer.unbind();
        try {
            if (file.isFile() && same(picture, file)) { return false; }
            file.getParentFile().mkdirs();
            // A framebuffer's rows run upward, a file's downward.
            PixmapIO.writePNG(new FileHandle(file), picture, Deflater.BEST_COMPRESSION, true);
            return true;
        } finally { picture.dispose(); }
    }

    private static boolean same(Pixmap picture, File file) {
        Pixmap stored = new Pixmap(new FileHandle(file));
        try {
            int width = picture.getWidth(), height = picture.getHeight();
            if (stored.getWidth() != width || stored.getHeight() != height) { return false; }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int now = picture.getPixel(x, height - 1 - y), before = stored.getPixel(x, y);
                    for (int shift = 0; shift < 32; shift += 8) {
                        if (Math.abs((now >>> shift & 0xff) - (before >>> shift & 0xff)) > SAME) { return false; }
                    }
                }
            }
            return true;
        } finally { stored.dispose(); }
    }

    /** Removes the pictures under {@code folder} that are none of {@code kept}, and the folders this empties; their number. */
    private static int removeOthers(File root, File folder, List<File> kept) {
        int removed = 0;
        File[] files = folder.listFiles();
        if (files == null) { return 0; }
        for (File file : files) {
            if (file.isDirectory()) { removed += removeOthers(root, file, kept); }
            else if (file.getName().endsWith(".png") && !kept.contains(file) && file.delete()) { removed++; }
        }
        if (!folder.equals(root) && folder.list().length == 0) { folder.delete(); }
        return removed;
    }
}
