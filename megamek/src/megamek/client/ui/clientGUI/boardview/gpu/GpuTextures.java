/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Disposable;

/** Shared atlas ownership. Pixel changes update existing slots without invalidating mesh UVs. */
final class GpuTextures<K> implements Disposable {
    private static final int ATLAS_BLEED = 2;

    /** One packed slot. Keys with equal artwork share it; {@code users} counts them. */
    private static final class Entry {
        final BoardScene.Pixels pixels;
        final String name;
        final TextureRegion region;
        int users;

        Entry(BoardScene.Pixels pixels, String name, TextureRegion region) {
            this.pixels = pixels;
            this.name = name;
            this.region = region;
        }
    }

    private final Map<K, Entry> entries = new HashMap<>();
    /** The slots some key uses, by artwork. A slot whose last key moved on stays packed until the pages are rebuilt. */
    private final Map<BoardScene.Pixels, Entry> slots = new HashMap<>();
    // PixmapPacker owns layout and CPU pixels only; each atlas owns its managed page textures.
    private final Map<PixmapPacker.Page, Texture> pageTextures = new IdentityHashMap<>();
    private final boolean mipmaps;
    private final boolean sceneColors;
    private PixmapPacker packer;
    private TextureAtlas atlas;
    private int slotCount;
    private long packedArea, liveArea;
    private boolean retainReplaced;
    /** Its other owners (#share): each disposes it, and the last one's dispose frees it. */
    private int sharers;
    private record Pages(TextureAtlas color, PixmapPacker colorPacker) implements Disposable {
        @Override
        public void dispose() {
            if (color != null) { color.dispose(); }
            if (colorPacker != null) { colorPacker.dispose(); }
        }
    }
    private final List<Pages> retired = new ArrayList<>();
    private TextureAtlas publishedColor;

    /** Terrain keeps old atlas pages alive until all meshes borrowing them have been retired. */
    void retainReplacedPages() { retainReplaced = true; }

    void publish() { publishedColor = atlas; }

    void releaseRetiredPages() {
        retired.removeIf(pages -> {
            if (pages.color() != null && pages.color() == publishedColor) { return false; }
            pages.dispose();
            return true;
        });
    }

    private void replacePages() {
        if (!retainReplaced) { dispose(); return; }
        Pages pages = new Pages(atlas, packer);
        if (atlas != null && atlas == publishedColor) {
            retired.add(pages);
        } else { pages.dispose(); }
        atlas = null;
        packer = null;
        pageTextures.clear();
        entries.clear();
        slots.clear();
        slotCount = 0;
        packedArea = 0;
        liveArea = 0;
    }

    GpuTextures() {
        this(false);
    }

    GpuTextures(boolean mipmaps) {
        this(mipmaps, false);
    }

    /** Scene artwork samples linear RGB; UI artwork and masks retain their authored/data values. */
    GpuTextures(boolean mipmaps, boolean sceneColors) {
        this.mipmaps = mipmaps;
        this.sceneColors = sceneColors;
    }

    /** Returns true only when the atlas layout changes. Must run on the GL thread. */
    boolean update(Map<K, BoardScene.Pixels> images) {
        return !updateRegions(images).isEmpty();
    }

    /** Keys whose UVs or texture pages changed; unchanged meshes can keep their existing atlas references. */
    Set<K> updateRegions(Map<K, BoardScene.Pixels> images) {
        if (images.isEmpty()) {
            Set<K> changed = Set.copyOf(entries.keySet());
            replacePages();
            return changed;
        }
        // An edit hands back the same pixel objects for every slot it left alone. Those are told apart by reference,
        // so a large board costs one lookup per slot rather than a rebuilt index, and only the rest get a look.
        Map<K, BoardScene.Pixels> changed =
              atlas != null && entries.size() == images.size() ? changedSlots(images) : null;
        if (changed != null && changed.isEmpty()) { return Set.of(); }
        if (changed != null && retainReplaced) {
            Set<K> appended = append(changed);
            if (appended != null) { return appended; }
        }
        Map<Entry, BoardScene.Pixels> replacements = new IdentityHashMap<>();
        boolean sameLayout = atlas != null && entries.keySet().equals(images.keySet())
              && images.entrySet().stream().allMatch(item -> {
                  Entry old = entries.get(item.getKey());
                  BoardScene.Pixels pixels = item.getValue();
                  BoardScene.Pixels shared = replacements.putIfAbsent(old, pixels);
                  // A shared slot can change in place only if all of its users still agree.
                  return old.pixels.width() == pixels.width() && old.pixels.height() == pixels.height()
                        && (shared == null || shared.equals(pixels));
              });
        // Previously different images may become identical after an edit; merge those slots too.
        sameLayout &= new HashSet<>(replacements.values()).size() == replacements.size();
        // Pending terrain must not repaint the pages still displayed by the previous revision. Append changed
        // artwork to new slots; publication will switch every mesh whose UVs changed in the same frame.
        sameLayout &= !retainReplaced || replacements.entrySet().stream()
              .allMatch(item -> item.getKey().pixels.equals(item.getValue()));
        if (sameLayout) {
            Set<Texture> updated = new HashSet<>();
            Map<Entry, Entry> next = new IdentityHashMap<>();
            replacements.forEach((old, pixels) -> {
                if (!old.pixels.equals(pixels)) {
                    // PixmapPacker duplicates one edge pixel. Refresh it as well as the interior when a slot changes.
                    replace(packer, old.name, old.region, pixels);
                    Entry entry = new Entry(pixels, old.name, old.region);
                    entry.users = old.users;
                    next.put(old, entry);
                    updated.add(old.region.getTexture());
                }
            });
            // Two slots may have traded artwork; clear every replaced one before indexing the new contents.
            next.keySet().forEach(old -> slots.remove(old.pixels));
            next.values().forEach(entry -> slots.put(entry.pixels, entry));
            entries.replaceAll((key, old) -> next.getOrDefault(old, old));
            if (mipmaps) { updated.forEach(GpuTextures::regenerateMipmaps); }
            return Set.of();
        }
        Map<K, Entry> before = new HashMap<>(entries);
        Set<BoardScene.Pixels> unique = new HashSet<>(images.values());
        long area = unique.stream().mapToLong(GpuTextures::area).sum();
        long addedArea = unique.stream().filter(pixels -> !slots.containsKey(pixels))
              .mapToLong(GpuTextures::area).sum();
        int imageWidth = unique.stream().mapToInt(BoardScene.Pixels::width).max().orElse(0);
        int imageHeight = unique.stream().mapToInt(BoardScene.Pixels::height).max().orElse(0);
        // Append new artwork without moving any live slot. Compact only when discarded artwork would more
        // than double the live storage (with one page of headroom), or the page size must change.
        boolean repack = atlas == null
              || imageWidth + 3 * ATLAS_BLEED > packer.getPageWidth()
              || imageHeight + 3 * ATLAS_BLEED > packer.getPageHeight()
              || packedArea + addedArea > Math.max(2 * area, (long) packer.getPageWidth() * packer.getPageHeight());
        if (repack) {
            replacePages();
            int side = 128;
            while (side < 2048 && side * (long) side < area * 1.3) { side *= 2; }
            // Guillotine packing reserves two outer margins AND one margin on each packed rectangle.
            int width = Math.max(side, imageWidth + 3 * ATLAS_BLEED);
            int height = Math.max(side, imageHeight + 3 * ATLAS_BLEED);
            packer = new PixmapPacker(width, height, Pixmap.Format.RGBA8888, ATLAS_BLEED, true);
        }
        Map<BoardScene.Pixels, String> names = new LinkedHashMap<>();
        images.forEach((key, pixels) -> {
            if (!slots.containsKey(pixels) && !names.containsKey(pixels)) { names.put(pixels, pack(pixels)); }
        });
        if (!names.isEmpty()) {
            atlas = atlas(packer, atlas, names.values());
            names.forEach((pixels, name) -> index(pixels, name, atlas.findRegion(name)));
        }
        // Rebind every key; a slot no key uses any more leaves the index, its pixels staying packed until a repack.
        entries.clear();
        slots.values().forEach(entry -> entry.users = 0);
        images.forEach((key, pixels) -> {
            Entry entry = slots.get(pixels);
            entry.users++;
            entries.put(key, entry);
        });
        slots.values().removeIf(entry -> entry.users == 0);
        liveArea = area;
        Set<K> moved = new HashSet<>(before.keySet());
        moved.addAll(entries.keySet());
        moved.removeIf(key -> before.containsKey(key) && entries.containsKey(key)
              && before.get(key).region == entries.get(key).region);
        return moved;
    }

    /** The keys given other artwork than their slot holds, in the order given; {@code null} if a key is new. */
    private Map<K, BoardScene.Pixels> changedSlots(Map<K, BoardScene.Pixels> images) {
        Map<K, BoardScene.Pixels> changed = new LinkedHashMap<>();
        for (var item : images.entrySet()) {
            Entry old = entries.get(item.getKey());
            if (old == null) { return null; }
            if (old.pixels == item.getValue()) { continue; }
            // The same artwork under another object is still the slot's artwork.
            if (!old.pixels.equals(item.getValue())) { changed.put(item.getKey(), item.getValue()); }
        }
        return changed;
    }

    /**
     * Rebinds the changed keys to slots holding their new artwork, packing artwork no slot in use holds into the
     * pages on display: only those rectangles are uploaded, and the slots the keys vacate keep their pixels for
     * the meshes still showing them. Returns {@code null} when the pages must be rebuilt instead, which the full
     * update decides by the same rule.
     */
    private Set<K> append(Map<K, BoardScene.Pixels> changed) {
        Map<Entry, Integer> arrivals = new IdentityHashMap<>();
        Set<BoardScene.Pixels> fresh = new HashSet<>();
        long addedArea = 0;
        for (var item : changed.entrySet()) {
            BoardScene.Pixels pixels = item.getValue();
            arrivals.merge(entries.get(item.getKey()), -1, Integer::sum);
            Entry target = slots.get(pixels);
            if (target != null) {
                arrivals.merge(target, 1, Integer::sum);
            } else if (pixels.width() + 3 * ATLAS_BLEED > packer.getPageWidth()
                  || pixels.height() + 3 * ATLAS_BLEED > packer.getPageHeight()) {
                return null;
            } else if (fresh.add(pixels)) {
                addedArea += area(pixels);
            }
        }
        long area = liveArea + addedArea;
        for (var item : arrivals.entrySet()) {
            if (item.getKey().users + item.getValue() == 0) { area -= area(item.getKey().pixels); }
        }
        if (packedArea + addedArea > Math.max(2 * area, (long) packer.getPageWidth() * packer.getPageHeight())) {
            return null;
        }
        Map<BoardScene.Pixels, String> names = new LinkedHashMap<>();
        for (BoardScene.Pixels pixels : changed.values()) {
            if (fresh.contains(pixels) && !names.containsKey(pixels)) { names.put(pixels, pack(pixels)); }
        }
        if (!names.isEmpty()) { atlas = atlas(packer, atlas, names.values()); }
        names.forEach((pixels, name) -> index(pixels, name, atlas.findRegion(name)));
        changed.forEach((key, pixels) -> {
            entries.get(key).users--;
            Entry entry = slots.get(pixels);
            entry.users++;
            entries.put(key, entry);
        });
        for (Entry entry : arrivals.keySet()) {
            if (entry.users == 0) {
                slots.remove(entry.pixels);
                liveArea -= area(entry.pixels);
            }
        }
        return changed.keySet();
    }

    /** Packs artwork into the next slot and returns the slot's name. */
    private String pack(BoardScene.Pixels pixels) {
        String name = "image" + slotCount++;
        pack(packer, name, pixels);
        packedArea += area(pixels);
        return name;
    }

    private void index(BoardScene.Pixels pixels, String name, TextureRegion region) {
        slots.put(pixels, new Entry(pixels, name, region));
        liveArea += area(pixels);
    }

    private static long area(BoardScene.Pixels pixels) {
        return (long) (pixels.width() + ATLAS_BLEED) * (pixels.height() + ATLAS_BLEED);
    }

    private TextureAtlas atlas(PixmapPacker source, TextureAtlas result, Iterable<String> names) {
        if (result == null) { result = new TextureAtlas(); }
        Set<PixmapPacker.Page> created = new HashSet<>();
        // All slots are packed already. Upload a new page once, directly in its final color/data format.
        for (String name : names) {
            var page = source.getPage(name);
            if (pageTextures.containsKey(page)) { continue; }
            Pixmap pixels = page.getPixmap();
            Texture texture = new Texture(sceneColors
                  ? GpuColorTexture.data(pixels, mipmaps, true)
                  : new PixmapTextureData(pixels, pixels.getFormat(), mipmaps, false, true));
            result.getTextures().add(texture);
            pageTextures.put(page, texture);
            created.add(page);
            texture.setFilter(mipmaps ? Texture.TextureFilter.MipMapLinearLinear : Texture.TextureFilter.Linear,
                  Texture.TextureFilter.Linear);
            if (mipmaps) {
                texture.bind();
                // Keep the sampling footprint inside the artwork margin and duplicated atlas border.
                Gdx.gl.glTexParameterf(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 2);
            }
        }
        Set<Texture> updated = new HashSet<>();
        for (String name : names) {
            var page = source.getPage(name);
            var rect = source.getRect(name).bounds;
            Texture texture = pageTextures.get(page);
            TextureRegion region = result.addRegion(name, texture, rect.x, rect.y, rect.width, rect.height);
            if (!created.contains(page)) {
                upload(page.getPixmap(), region);
                updated.add(texture);
            }
        }
        if (mipmaps) { updated.forEach(GpuTextures::regenerateMipmaps); }
        return result;
    }

    private static void pack(PixmapPacker target, String name, BoardScene.Pixels pixels) {
        Pixmap pixmap = pixmap(pixels, 0);
        try {
            target.pack(name, pixmap);
        } finally {
            pixmap.dispose();
        }
    }

    private static void replace(PixmapPacker target, String name, TextureRegion region, BoardScene.Pixels pixels) {
        Pixmap page = target.getPage(name).getPixmap();
        Pixmap pixmap = pixmap(pixels, 1);
        try {
            // Keep the managed backing image in sync for context restoration as well.
            page.setBlending(Pixmap.Blending.None);
            page.drawPixmap(pixmap, region.getRegionX() - 1, region.getRegionY() - 1);
        } finally {
            pixmap.dispose();
        }
        upload(page, region);
    }

    /** Uploads one slot and its duplicated border from the page's backing image; the rest of the page stays as it is. */
    private static void upload(Pixmap page, TextureRegion region) {
        int x = region.getRegionX() - 1, y = region.getRegionY() - 1;
        int width = region.getRegionWidth() + 2, height = region.getRegionHeight() + 2;
        Pixmap part = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            part.setBlending(Pixmap.Blending.None);
            part.drawPixmap(page, x, y, width, height, 0, 0, width, height);
            region.getTexture().bind();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, x, y, width, height, part.getGLFormat(), part.getGLType(),
                  part.getPixels());
        } finally {
            part.dispose();
        }
    }

    private static void regenerateMipmaps(Texture texture) {
        texture.bind();
        Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
    }

    static Pixmap pixmap(BoardScene.Pixels image, int border) {
        Pixmap result = new Pixmap(image.width() + 2 * border, image.height() + 2 * border, Pixmap.Format.RGBA8888);
        result.setBlending(Pixmap.Blending.None);
        // One direct-buffer write loop instead of one JNI call per pixel. RGBA bytes have fixed order.
        var buffer = result.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
        if (border == 0) {
            image.writeRgba(buffer, 0, image.width() * image.height());
        } else {
            for (int y = -border; y < image.height() + border; y++) {
                int row = Math.clamp(y, 0, image.height() - 1) * image.width();
                for (int x = 0; x < border; x++) { buffer.put(image.rgba(row)); }
                image.writeRgba(buffer, row, image.width());
                for (int x = 0; x < border; x++) { buffer.put(image.rgba(row + image.width() - 1)); }
            }
        }
        return result;
    }

    TextureRegion region(K key) {
        return entries.get(key).region;
    }

    /** Whether every one of these keys has its slot already: nothing to pack or upload for them. */
    boolean holds(Set<K> keys) { return atlas != null && entries.keySet().containsAll(keys); }

    /** This atlas for one more owner, which disposes it as every owner does. */
    GpuTextures<K> share() {
        sharers++;
        return this;
    }

    @Override
    public void dispose() {
        if (sharers > 0) {
            sharers--;
            return;
        }
        publishedColor = null;
        releaseRetiredPages();
        if (atlas != null) {
            atlas.dispose();
            atlas = null;
        }
        if (packer != null) {
            packer.dispose();
            packer = null;
        }
        pageTextures.clear();
        entries.clear();
        slots.clear();
        slotCount = 0;
        packedArea = 0;
        liveArea = 0;
    }
}
