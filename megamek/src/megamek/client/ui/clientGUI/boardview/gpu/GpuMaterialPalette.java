/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPictureCard;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;

/**
 * Tuning → Terrain → Material palette: every board material ({@link GpuMaterialMaps#boardTextures}) side by side as a
 * gallery card over the battle, each on a flat tile, a cube or a sphere as the game draws it (GpuMaterialSwatches). GPU
 * thread: a few swatches are drawn at the start of each frame, on the chosen shape first and then on the others, so the
 * gallery fills in as it is watched and a change of shape shows the drawn ones at once. Opening and Reload assets
 * reread the folder.
 */
final class GpuMaterialPalette {
    /** The card's width: six swatches side by side, with the card's padding and the scroll bar. */
    static final float WIDTH = 6 * (GpuMaterialSwatches.SIZE + 12) + 96;
    private static final int PER_FRAME = 4;

    /** How the swatches are laid out; an order by look ({@code drawn}) sorts the drawn ones, the rest follow by folder. */
    enum Order {
        FOLDER(false), SIMILARITY(true), BRIGHTNESS(true), SIZE(false), CHANGED(false);

        private final boolean drawn;

        Order(boolean drawn) {
            this.drawn = drawn;
        }

        String title() {
            return Messages.getString("GpuBoard.palette.order." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** A material's files on disk: its {@code .tres} and every {@code <stem>_*.png} beside it. */
    record Material(String name, long bytes, long modified, int maps, int albedo) {
        /** The material's name without its folder. */
        String title() {
            return name.substring(name.lastIndexOf('/') + 1);
        }

        /** Its path, then the albedo's size, its bytes on disk, how many maps and when it last changed. */
        String detail() {
            String amount = bytes < 1_000_000 ? (bytes + 500) / 1000 + " KB"
                  : String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
            String files = Messages.getString("GpuBoard.palette.files", amount, String.valueOf(maps),
                  String.format(Locale.ROOT, "%tF %<tR", modified));
            return name + "\n" + (albedo > 0
                  ? Messages.getString("GpuBoard.palette.albedo", String.valueOf(albedo)) + ", " + files : files);
        }

        static Material read(String name) {
            File resource = new File(GpuMaterialMaps.boardTextures(), name + ".tres");
            String stem = resource.getName().substring(0, resource.getName().length() - ".tres".length());
            File[] maps = resource.getParentFile().listFiles((folder, file) -> file.startsWith(stem + "_")
                  && file.endsWith(".png"));
            maps = maps == null ? new File[0] : maps;
            long bytes = resource.length(), modified = resource.lastModified();
            for (File map : maps) {
                bytes += map.length();
                modified = Math.max(modified, map.lastModified());
            }
            File albedo = new File(resource.getParentFile(), stem + "_albedo.png");
            return new Material(name, bytes, modified, maps.length, albedo.isFile() ? GpuMaterialMaps.size(albedo)[0] : 0);
        }
    }

    /** A material drawn on one shape: its image (for the orders by look) and texture, or why it could not be drawn. */
    private record Swatch(BufferedImage image, Texture texture, String failure) {}

    /** One material in the gallery: its card and its swatch on each shape drawn so far, kept until Reload or Close. */
    private final class Tile {
        final Material material;
        final Table cell = new Table();
        final UiPictureCard card;
        final TextTooltip tip;
        final Swatch[] swatches = new Swatch[GpuMaterialSwatches.Shape.values().length];

        Tile(Material material) {
            this.material = material;
            card = new UiPictureCard(kit, Messages.getString("GpuBoard.palette.pending"), 1);
            card.setName("palette-" + material.name());
            tip = kit.tip(card, true);
            tip.getActor().setText(material.detail());
            Label title = kit.typeLabel(material.title(), "small", "text.secondary");
            title.setEllipsis(true);
            title.setAlignment(Align.center);
            cell.add(card).width(GpuMaterialSwatches.SIZE).row();
            cell.add(title).width(GpuMaterialSwatches.SIZE).padTop(space("xxs"));
        }

        void draw(GpuMaterialSwatches renderer, GpuMaterialSwatches.Shape on) {
            try {
                BufferedImage image = renderer.render(material.name(), on);
                swatches[on.ordinal()] = new Swatch(image, GpuStartScreen.texture(image, false), null);
            } catch (RuntimeException failure) {
                swatches[on.ordinal()] = new Swatch(null, null, material.name() + ": " + failure.getMessage());
            }
        }

        /** Shows its swatch on {@code on}, or that it is still to be drawn. */
        void show(GpuMaterialSwatches.Shape on) {
            Swatch swatch = swatches[on.ordinal()];
            if (swatch == null) {
                card.picture(null, 1, Messages.getString("GpuBoard.palette.pending"));
                tip.getActor().setText(material.detail());
            } else if (swatch.texture() == null) {
                card.picture(null, 1, Messages.getString("GpuBoard.palette.failed"));
                tip.getActor().setText(swatch.failure());
            } else {
                card.picture(new TextureRegionDrawable(swatch.texture()), 1, null);
                tip.getActor().setText(material.detail());
            }
        }

        BufferedImage image(GpuMaterialSwatches.Shape on) {
            Swatch swatch = swatches[on.ordinal()];
            return swatch == null ? null : swatch.image();
        }

        void release() {
            for (int index = 0; index < swatches.length; index++) {
                if (swatches[index] != null && swatches[index].texture() != null) {
                    swatches[index].texture().dispose();
                }
                swatches[index] = null;
            }
        }
    }

    private final UiKit kit;
    private final Table grid = new Table();
    private final Label status;
    private final UiSegmented shape;
    private final UiSelect order;
    private final int columns;
    private final Consumer<Runnable> nextFrame;
    private final List<Tile> tiles = new ArrayList<>();
    /** The current render pass; a newer one (Reload assets) or closing the card ends the older. */
    private int pass;
    private boolean closed;

    /**
     * @param bodyWidth    the width the card's body has in this window
     * @param reloadAssets the board's Reload assets, on this thread; null without a board
     * @param close        closes the card
     * @param nextFrame    runs its work at the start of the next frame, with the default framebuffer bound
     */
    GpuMaterialPalette(UiKit kit, UiDialog dialog, float bodyWidth, Runnable reloadAssets, Runnable close,
          Consumer<Runnable> nextFrame) {
        this.kit = kit;
        this.nextFrame = nextFrame;
        float gap = space("md");
        columns = Math.max(1, (int) ((bodyWidth + gap) / (GpuMaterialSwatches.SIZE + gap)));
        Table body = dialog.body();
        body.top().left();
        GpuMaterialSwatches.Shape[] shapes = GpuMaterialSwatches.Shape.values();
        String[] shapeNames = new String[shapes.length];
        for (int index = 0; index < shapes.length; index++) {
            shapeNames[index] = Messages.getString("GpuBoard.palette.shape."
                  + shapes[index].name().toLowerCase(Locale.ROOT));
        }
        shape = kit.tokenSegmented(true, shapeNames);
        shape.setName("palette-shape");
        shape.select(0);
        UiKit.onChange(shape, this::showShape);
        String[] orderNames = new String[Order.values().length];
        for (Order each : Order.values()) {
            orderNames[each.ordinal()] = each.title();
        }
        order = kit.tokenSelect(true, orderNames);
        order.setName("palette-order");
        kit.tip(order, true).getActor().setText(Messages.getString("GpuBoard.palette.order.tooltip"));
        UiKit.onChange(order, this::arrange);
        Table bar = new Table();
        bar.add(kit.formField(Messages.getString("GpuBoard.palette.shape"), shape)).left();
        bar.add(kit.formField(Messages.getString("GpuBoard.palette.order"), order)).left().padLeft(space("lg"))
              .width(space("xxxl") * 4);
        bar.add().growX();
        body.add(bar).growX().row();
        grid.top().left();
        grid.setName("palette-grid");
        body.add(grid).growX().padTop(space("lg")).row();
        var reload = dialog.tool(Messages.getString("GpuBoard.palette.reload"), () -> {
            if (reloadAssets != null) {
                reloadAssets.run();
            }
            render();
        });
        reload.setName("palette-reload");
        kit.tip(reload, true).getActor().setText(Messages.getString("GpuBoard.palette.reload.tooltip"));
        status = kit.typeLabel("", "small", "text.muted");
        status.setName("palette-status");
        dialog.leading().add(status).left();
        dialog.primary(Messages.getString("Close"), close).setName("palette-close");
        render();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** GPU thread, once the card has closed: ends the pass and gives the textures back. */
    void dispose() {
        closed = true;
        pass++;
        tiles.forEach(Tile::release);
        tiles.clear();
    }

    /**
     * Rereads the folder and draws every material, a few a frame: on the chosen shape first, then on the others, so a
     * later change of shape shows them at once.
     */
    private void render() {
        tiles.forEach(Tile::release);
        tiles.clear();
        materials().stream().map(Material::read).map(Tile::new).forEach(tiles::add);
        arrange();
        int number = ++pass;
        List<Tile> drawing = List.copyOf(tiles);
        status.setText(drawing.isEmpty()
              ? Messages.getString("GpuBoard.palette.empty", GpuMaterialMaps.boardTextures().getPath())
              : Messages.getString("GpuBoard.palette.drawing", drawing.size()));
        if (drawing.isEmpty()) {
            return;
        }
        nextFrame.accept(new Runnable() {
            private GpuMaterialSwatches renderer;

            @Override
            public void run() {
                if (pass != number || closed) {
                    release();
                    return;
                }
                if (renderer == null) {
                    renderer = new GpuMaterialSwatches();
                }
                GpuMaterialSwatches.Shape chosen = selectedShape();
                boolean wasComplete = drawn(drawing, chosen) == drawing.size();
                for (int count = 0; count < PER_FRAME; count++) {
                    Tile tile = next(drawing, chosen);
                    if (tile == null) {
                        break;
                    }
                    GpuMaterialSwatches.Shape on = tile.swatches[chosen.ordinal()] == null ? chosen : missing(tile);
                    tile.draw(renderer, on);
                    if (on == chosen) {
                        tile.show(chosen);
                    }
                }
                if (!wasComplete) {
                    showProgress();
                    if (drawn(drawing, chosen) == drawing.size() && selectedOrder().drawn) {
                        arrange();
                    }
                }
                if (next(drawing, chosen) != null) {
                    nextFrame.accept(this);
                    return;
                }
                release();
            }

            private void release() {
                if (renderer != null) {
                    renderer.dispose();
                    renderer = null;
                }
            }
        });
    }

    /** The next material to draw: one still missing on {@code chosen}, else one missing on another shape. */
    private static Tile next(List<Tile> drawing, GpuMaterialSwatches.Shape chosen) {
        for (Tile tile : drawing) {
            if (tile.swatches[chosen.ordinal()] == null) {
                return tile;
            }
        }
        for (Tile tile : drawing) {
            if (missing(tile) != null) {
                return tile;
            }
        }
        return null;
    }

    private static GpuMaterialSwatches.Shape missing(Tile tile) {
        for (GpuMaterialSwatches.Shape each : GpuMaterialSwatches.Shape.values()) {
            if (tile.swatches[each.ordinal()] == null) {
                return each;
            }
        }
        return null;
    }

    /** How many of {@code drawing} are done on {@code on}, drawn or failed. */
    private static int drawn(List<Tile> drawing, GpuMaterialSwatches.Shape on) {
        return (int) drawing.stream().filter(tile -> tile.swatches[on.ordinal()] != null).count();
    }

    private GpuMaterialSwatches.Shape selectedShape() {
        return GpuMaterialSwatches.Shape.values()[Math.max(0, shape.getSelectedIndex())];
    }

    /** Another shape: the swatches already drawn on it show at once, the rest as the pass reaches them. */
    private void showShape() {
        GpuMaterialSwatches.Shape chosen = selectedShape();
        tiles.forEach(tile -> tile.show(chosen));
        if (!tiles.isEmpty()) {
            showProgress();
        }
        if (selectedOrder().drawn) {
            arrange();
        }
    }

    private void showProgress() {
        status.setText(Messages.getString("GpuBoard.palette.progress",
              String.valueOf(drawn(tiles, selectedShape())), String.valueOf(tiles.size())));
    }

    private Order selectedOrder() {
        return Order.values()[Math.max(0, order.getSelectedIndex())];
    }

    /** Lays the swatches out in the chosen order (a pass re-sorts when it ends, for an order by look). */
    private void arrange() {
        grid.clearChildren();
        float gap = space("md");
        GpuMaterialSwatches.Shape chosen = selectedShape();
        List<Tile> ordered = order(tiles, selectedOrder(), tile -> tile.material, tile -> tile.image(chosen));
        for (int index = 0; index < ordered.size(); index++) {
            grid.add(ordered.get(index).cell).top().padRight(index % columns == columns - 1 ? 0 : gap)
                  .padBottom(gap);
            if (index % columns == columns - 1) {
                grid.row();
            }
        }
    }

    /**
     * {@code items} in {@code order}: by folder as given, by look from their drawn {@code image} (null while undrawn,
     * which then follow by folder), or by their {@code material}'s files.
     */
    static <T> List<T> order(List<T> items, Order order, Function<T, Material> material,
          Function<T, BufferedImage> image) {
        return switch (order) {
            case FOLDER -> List.copyOf(items);
            case SIMILARITY -> similar(items, image);
            case BRIGHTNESS -> brightness(items, image);
            case SIZE -> items.stream()
                  .sorted(Comparator.comparingLong(item -> -material.apply(item).bytes())).toList();
            case CHANGED -> items.stream()
                  .sorted(Comparator.comparingLong(item -> -material.apply(item).modified())).toList();
        };
    }

    /**
     * The drawn swatches from the darkest to the brightest: the mean luminance of the material's own pixels, without
     * the backdrop (the swatch's corner colour) that a cutout or a sphere leaves; the rest follow.
     */
    private static <T> List<T> brightness(List<T> items, Function<T, BufferedImage> images) {
        Map<T, Double> brightness = new LinkedHashMap<>();
        for (T item : items) {
            BufferedImage image = images.apply(item);
            if (image != null) {
                int backdrop = image.getRGB(0, 0), count = 0;
                double sum = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int rgb = image.getRGB(x, y);
                        if (rgb == backdrop) {
                            continue;
                        }
                        sum += .299 * (rgb >> 16 & 255) + .587 * (rgb >> 8 & 255) + .114 * (rgb & 255);
                        count++;
                    }
                }
                brightness.put(item, count == 0 ? 0 : sum / count);
            }
        }
        List<T> result = new ArrayList<>(brightness.keySet().stream()
              .sorted(Comparator.comparingDouble(brightness::get)).toList());
        items.stream().filter(item -> !brightness.containsKey(item)).forEach(result::add);
        return result;
    }

    /**
     * The drawn swatches as one chain from the darkest, each followed by the remaining one it looks most like (nearest
     * {@link #look}); swatches not drawn yet follow by folder.
     */
    private static <T> List<T> similar(List<T> items, Function<T, BufferedImage> images) {
        Map<T, float[]> looks = new LinkedHashMap<>();
        List<T> undrawn = new ArrayList<>();
        for (T item : items) {
            BufferedImage image = images.apply(item);
            if (image != null) {
                looks.put(item, look(image));
            } else {
                undrawn.add(item);
            }
        }
        List<T> chain = new ArrayList<>();
        T last = looks.keySet().stream().min(Comparator.comparingDouble(item -> sum(looks.get(item)))).orElse(null);
        while (last != null) {
            float[] previous = looks.remove(last);
            chain.add(last);
            last = looks.keySet().stream().min(Comparator.comparingDouble(item -> distance(previous, looks.get(item))))
                  .orElse(null);
        }
        chain.addAll(undrawn);
        return chain;
    }

    /** A swatch's look: the mean colour and the luminance contrast of each cell of a 4 x 4 grid over its image. */
    private static float[] look(BufferedImage image) {
        int cells = 4, cell = image.getWidth() / cells;
        float[] look = new float[cells * cells * 4];
        for (int index = 0; index < cells * cells; index++) {
            double r = 0, g = 0, b = 0, luminance = 0, square = 0;
            int count = 0;
            for (int y = index / cells * cell; y < (index / cells + 1) * cell; y++) {
                for (int x = index % cells * cell; x < (index % cells + 1) * cell; x++) {
                    int rgb = image.getRGB(x, y);
                    double red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                    double value = .299 * red + .587 * green + .114 * blue;
                    r += red;
                    g += green;
                    b += blue;
                    luminance += value;
                    square += value * value;
                    count++;
                }
            }
            double mean = luminance / count;
            look[index * 4] = (float) (r / count);
            look[index * 4 + 1] = (float) (g / count);
            look[index * 4 + 2] = (float) (b / count);
            look[index * 4 + 3] = (float) Math.sqrt(Math.max(0, square / count - mean * mean));
        }
        return look;
    }

    private static double sum(float[] look) {
        double sum = 0;
        for (float value : look) {
            sum += value;
        }
        return sum;
    }

    private static double distance(float[] a, float[] b) {
        double sum = 0;
        for (int index = 0; index < a.length; index++) {
            sum += (a[index] - b[index]) * (a[index] - b[index]);
        }
        return sum;
    }

    /** Every {@code .tres} under the board's material folder, by its path there without the extension. */
    static List<String> materials() {
        Path root = GpuMaterialMaps.boardTextures().toPath();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".tres"))
                  .map(path -> root.relativize(path).toString().replace('\\', '/'))
                  .map(path -> path.substring(0, path.length() - ".tres".length())).sorted().toList();
        } catch (IOException failure) {
            return List.of();
        }
    }

    // ---- For the tests ----

    int tileCount() {
        return tiles.size();
    }

    /** The materials with a swatch on the chosen shape. */
    int drawnCount() {
        GpuMaterialSwatches.Shape chosen = selectedShape();
        return (int) tiles.stream().filter(tile -> tile.swatches[chosen.ordinal()] != null
              && tile.swatches[chosen.ordinal()].texture() != null).count();
    }

    /** The materials with a swatch on every shape. */
    int completeCount() {
        return (int) tiles.stream().filter(tile -> missing(tile) == null).count();
    }
}
