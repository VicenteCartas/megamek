/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.Supplier;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;

/** Core-GL image uploads: scene RGB uses sRGB storage, numerical maps and all alpha channels keep their values. */
final class GpuColorTexture {
    private GpuColorTexture() { }

    static boolean isColor(Texture texture) {
        return texture.getTextureData() instanceof ImageData data && data.color
              || texture.getTextureData() instanceof LinearData;
    }

    static Texture create(FileHandle file, boolean mipmaps) {
        return texture(new ImageData(() -> decode(file), mipmaps, true, true, true));
    }

    /** Numerical maps share core-GL channel layout handling, without applying an RGB transfer function. */
    static Texture createData(FileHandle file, boolean mipmaps) {
        return texture(new ImageData(() -> decode(file), mipmaps, true, true, false));
    }

    /**
     * A managed texture of pixels made on demand, such as a board material's packed layout (GpuMaterialMaps): made
     * again, not kept, when the context is restored. {@code color} selects sRGB storage for RGB.
     */
    static Texture create(Supplier<Pixmap> pixels, boolean mipmaps, boolean color) {
        return texture(new ImageData(pixels, mipmaps, true, true, color));
    }

    /**
     * A colour image and its sRGB mip levels below level 0, level 1 first: their colours averaged by alpha, as
     * premultiplied filtering averages them. glGenerateMipmap weighs the colour of transparent texels alike, so a cutout
     * would take the colour of the filler around it at distance. A level's fully transparent texels keep the plain
     * average, the image's own edge colour. No levels for an image without transparent texels: GL makes them.
     */
    record Cutout(Pixmap image, ByteBuffer[] levels) { }

    /** A managed texture of a colour image and its levels ({@link #cutoutLevels}), made again when the context is restored. */
    static Texture cutout(Supplier<Cutout> source) {
        return texture(new ImageData(source));
    }

    private static final float[] DECODE = new float[256], ENCODE = new float[255];
    static {
        for (int i = 0; i < DECODE.length; i++) { DECODE[i] = GpuColor.linear(i / 255f); }
        // Each code's upper bound in linear light: the code a linear value rounds to in sRGB, without a power.
        for (int i = 0; i < ENCODE.length; i++) { ENCODE[i] = GpuColor.linear((i + .5f) / 255f); }
    }

    /**
     * {@link Cutout}'s levels of {@code image}, which it takes, on the calling thread: a pooled decode makes them with its
     * pixels. Each level is a box filter over the whole footprint of the level above (a 3-wide level must not lose its last
     * column at 1-wide), its colours weighted by alpha in linear light.
     */
    static Cutout cutoutLevels(Pixmap image) {
        if (image.getFormat() == Pixmap.Format.RGB888 || opaque(image)) { return new Cutout(image, null); }
        if (image.getFormat() != Pixmap.Format.RGBA8888) {
            Pixmap expanded = new Pixmap(image.getWidth(), image.getHeight(), Pixmap.Format.RGBA8888);
            expanded.setBlending(Pixmap.Blending.None);
            expanded.drawPixmap(image, 0, 0);
            image.dispose();
            image = expanded;
        }
        int width = image.getWidth(), height = image.getHeight();
        float[] weighted = new float[width * height * 4], plain = new float[width * height * 3];
        byte[] bytes = new byte[width * height * 4];
        image.getPixels().get(0, bytes);
        for (int texel = 0; texel < width * height; texel++) {
            float alpha = (bytes[texel * 4 + 3] & 255) / 255f;
            for (int c = 0; c < 3; c++) {
                float value = DECODE[bytes[texel * 4 + c] & 255];
                plain[texel * 3 + c] = value;
                weighted[texel * 4 + c] = value * alpha;
            }
            weighted[texel * 4 + 3] = alpha;
        }
        var levels = new java.util.ArrayList<ByteBuffer>();
        while (width > 1 || height > 1) {
            int w = Math.max(1, width / 2), h = Math.max(1, height / 2);
            boolean halves = width == 2 * w && height == 2 * h;
            float[] nextWeighted = new float[w * h * 4], nextPlain = new float[w * h * 3];
            byte[] level = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int texel = y * w + x;
                    if (halves) {
                        int a = 2 * y * width + 2 * x, b = a + 1, d = a + width, e = d + 1;
                        for (int c = 0; c < 4; c++) {
                            nextWeighted[texel * 4 + c] = .25f * (weighted[a * 4 + c] + weighted[b * 4 + c]
                                  + weighted[d * 4 + c] + weighted[e * 4 + c]);
                        }
                        for (int c = 0; c < 3; c++) {
                            nextPlain[texel * 3 + c] = .25f * (plain[a * 3 + c] + plain[b * 3 + c] + plain[d * 3 + c] + plain[e * 3 + c]);
                        }
                    } else {
                        double top = y * (double) height / h, bottom = (y + 1) * (double) height / h;
                        double left = x * (double) width / w, right = (x + 1) * (double) width / w;
                        for (int sy = (int) top; sy < Math.ceil(bottom); sy++) {
                            for (int sx = (int) left; sx < Math.ceil(right); sx++) {
                                int from = sy * width + sx;
                                float weight = (float) ((Math.min(bottom, sy + 1) - Math.max(top, sy))
                                      * (Math.min(right, sx + 1) - Math.max(left, sx)) / ((bottom - top) * (right - left)));
                                for (int c = 0; c < 4; c++) { nextWeighted[texel * 4 + c] += weighted[from * 4 + c] * weight; }
                                for (int c = 0; c < 3; c++) { nextPlain[texel * 3 + c] += plain[from * 3 + c] * weight; }
                            }
                        }
                    }
                    float alpha = nextWeighted[texel * 4 + 3];
                    for (int c = 0; c < 3; c++) {
                        if (alpha > 0) { nextPlain[texel * 3 + c] = Math.min(1, nextWeighted[texel * 4 + c] / alpha); }
                        level[texel * 4 + c] = (byte) encode(nextPlain[texel * 3 + c]);
                    }
                    level[texel * 4 + 3] = (byte) Math.round(alpha * 255);
                }
            }
            levels.add(com.badlogic.gdx.utils.BufferUtils.newByteBuffer(level.length).put(level).flip());
            weighted = nextWeighted;
            plain = nextPlain;
            width = w;
            height = h;
        }
        return new Cutout(image, levels.toArray(ByteBuffer[]::new));
    }

    /** Whether an RGBA8888 image has no transparent texel; other layouts answer false and take the cutout levels. */
    private static boolean opaque(Pixmap image) {
        if (image.getFormat() != Pixmap.Format.RGBA8888) { return false; }
        byte[] bytes = new byte[image.getWidth() * image.getHeight() * 4];
        image.getPixels().get(0, bytes);
        for (int i = 3; i < bytes.length; i += 4) {
            if (bytes[i] != (byte) 255) { return false; }
        }
        return true;
    }

    /** The sRGB code of a linear value: as rounding GpuColor.display's, by a search of ENCODE. */
    private static int encode(float linear) {
        int low = 0, high = ENCODE.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (linear < ENCODE[middle]) { high = middle; } else { low = middle + 1; }
        }
        return low;
    }

    /** Its pixels come first: an image that cannot be decoded leaves no GL texture behind. */
    private static Texture texture(ImageData data) {
        data.prepare();
        return new Texture(data);
    }

    /** File grayscale means opaque luminance; the caller owns the returned pixels. */
    static Pixmap decode(FileHandle file) { return decodedImage(new Pixmap(file)); }

    static Pixmap decode(byte[] encoded) { return decodedImage(new Pixmap(encoded, 0, encoded.length)); }

    private static Pixmap decodedImage(Pixmap pixels) {
        if (pixels.getFormat() != Pixmap.Format.Alpha) { return pixels; }
        // libGDX names decoded one-channel PNG/JPEG grayscale "Alpha". Resolve that file-format ambiguity here:
        // file samples are opaque luminance, while caller-created Alpha Pixmaps still represent coverage.
        Pixmap expanded = null;
        try {
            // Heap arrays and bulk copies: per-texel buffer calls are slow until the JIT compiles them.
            byte[] gray = new byte[pixels.getWidth() * pixels.getHeight()], rgba = new byte[gray.length * 4];
            expanded = new Pixmap(pixels.getWidth(), pixels.getHeight(), Pixmap.Format.RGBA8888);
            pixels.getPixels().get(0, gray);
            for (int i = 0, at = 0; i < gray.length; i++, at += 4) {
                rgba[at] = rgba[at + 1] = rgba[at + 2] = gray[i];
                rgba[at + 3] = (byte) 255;
            }
            expanded.getPixels().put(0, rgba);
            return expanded;
        } catch (RuntimeException failure) {
            if (expanded != null) { expanded.dispose(); }
            throw failure;
        } finally { pixels.dispose(); }
    }

    /** The caller still owns the Pixmap; the upload completes before this method returns. */
    static Texture create(Pixmap pixels, boolean mipmaps) {
        return new Texture(data(pixels, mipmaps, false));
    }

    /** A packer's managed texture borrows its page Pixmap for context restoration and future page updates. */
    static TextureData data(Pixmap pixels, boolean mipmaps, boolean managed) {
        return new ImageData(() -> pixels, mipmaps, managed, false, true);
    }

    /** Bounded composed RGB is encoded once for sRGB8 storage; alpha remains independent eight-bit coverage. */
    static Texture ldr(float[] linear, int width, int height, boolean mipmaps) {
        var pixels = ldrPixels(linear, width, height);
        try {
            return create(pixels, mipmaps);
        } finally { pixels.dispose(); }
    }

    /** {@link #ldr}'s pixels, encoded without GL; the caller owns them. */
    static Pixmap ldrPixels(float[] linear, int width, int height) {
        if (linear.length != width * height * 4) { throw new IllegalArgumentException("RGBA image dimensions"); }
        var pixels = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            var rgba = pixels.getPixels();
            for (int index = 0; index < linear.length; index++) {
                float value = (index & 3) == 3 ? linear[index] : GpuColor.display(linear[index]);
                rgba.put(index, (byte) Math.round(Math.clamp(value, 0f, 1f) * 255));
            }
            return pixels;
        } catch (RuntimeException | Error failure) { pixels.dispose(); throw failure; }
    }

    /** CPU-composited light/color is uploaded directly, without an intermediate display encoding. */
    static Texture linear(FloatBuffer pixels, int width, int height, boolean mipmaps) {
        Texture texture = new Texture(new LinearData(width, height));
        try {
            texture.bind();
            try {
                Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, width, height, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
            } finally { Reference.reachabilityFence(pixels); }
            if (mipmaps) { Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D); }
            return texture;
        } catch (RuntimeException failure) { texture.dispose(); throw failure; }
    }

    /** Identifies linear color independently of the numerical float textures used for depth and wave fields. */
    private static final class LinearData extends GLOnlyTextureData {
        LinearData(int width, int height) { super(width, height, 0, GL30.GL_RGBA16F, GL20.GL_RGBA, GL20.GL_FLOAT); }
    }

    private static final class ImageData implements TextureData {
        private final Supplier<Pixmap> source;
        /** A cutout's image and levels instead of {@link #source} (GpuColorTexture#cutout). */
        private final Supplier<Cutout> cutouts;
        private final boolean mipmaps, managed, dispose, color;
        private Pixmap pixels;
        private ByteBuffer[] levels;
        private int width, height;

        ImageData(Supplier<Pixmap> source, boolean mipmaps, boolean managed, boolean dispose, boolean color) {
            this.source = source;
            this.cutouts = null;
            this.mipmaps = mipmaps;
            this.managed = managed;
            this.dispose = dispose;
            this.color = color;
        }

        ImageData(Supplier<Cutout> cutouts) {
            this.source = null;
            this.cutouts = cutouts;
            this.mipmaps = true;
            this.managed = true;
            this.dispose = true;
            this.color = true;
        }

        @Override public TextureDataType getType() { return TextureDataType.Custom; }
        @Override public boolean isPrepared() { return pixels != null; }
        @Override public void prepare() {
            if (pixels != null) { throw new IllegalStateException("Image texture is already prepared"); }
            if (cutouts != null) {
                Cutout cutout = cutouts.get();
                pixels = cutout.image();
                levels = cutout.levels();
            } else {
                pixels = source.get();
            }
            width = pixels.getWidth();
            height = pixels.getHeight();
        }
        @Override public void consumeCustomData(int target) {
            if (pixels == null) { throw new IllegalStateException("Image texture is not prepared"); }
            Pixmap upload = pixels;
            try {
                // Grayscale/alpha Pixmaps use legacy LUMINANCE/ALPHA external formats, unavailable in core GL.
                // Expand channel layout without resizing or changing the authored RGB transfer function.
                if (pixels.getFormat() != Pixmap.Format.RGB888 && pixels.getFormat() != Pixmap.Format.RGBA8888) {
                    upload = new Pixmap(width, height, Pixmap.Format.RGBA8888);
                    upload.setBlending(Pixmap.Blending.None);
                    upload.drawPixmap(pixels, 0, 0);
                }
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
                Gdx.gl.glTexImage2D(target, 0, color ? GL30.GL_SRGB8_ALPHA8 : GL30.GL_RGBA8, width, height, 0,
                      upload.getGLFormat(), upload.getGLType(), upload.getPixels());
                if (levels != null) {
                    for (int level = 1, w = width, h = height; level <= levels.length; level++) {
                        w = Math.max(1, w / 2);
                        h = Math.max(1, h / 2);
                        Gdx.gl.glTexImage2D(target, level, GL30.GL_SRGB8_ALPHA8, w, h, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE,
                              levels[level - 1]);
                    }
                } else if (mipmaps) {
                    Gdx.gl.glGenerateMipmap(target);
                }
            } finally {
                if (upload != pixels) { upload.dispose(); }
                if (dispose) { pixels.dispose(); }
                pixels = null;
                levels = null;
            }
        }
        @Override public Pixmap consumePixmap() { throw new UnsupportedOperationException("Image texture"); }
        @Override public boolean disposePixmap() { return false; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public Pixmap.Format getFormat() { return Pixmap.Format.RGBA8888; }
        @Override public boolean useMipMaps() { return mipmaps; }
        @Override public boolean isManaged() { return managed; }
    }

    /**
     * Raw RGBA8 storage; only a required resize decodes, filters and re-encodes colour RGB.
     * Exact RGBA images borrow the Pixmap's bytes until the synchronous upload completes.
     */
    static ByteBuffer arrayPixels(Pixmap source, int width, int height, boolean color) {
        if (source.getWidth() == width && source.getHeight() == height
              && source.getFormat() == Pixmap.Format.RGBA8888) {
            var upload = source.getPixels().duplicate();
            upload.position(0);
            upload.limit(width * height * 4);
            return upload;
        }
        var upload = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(width * height * 4);
        if (source.getWidth() == width && source.getHeight() == height) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgba = source.getPixel(x, y);
                    upload.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16))
                          .put((byte) (rgba >>> 8)).put((byte) rgba);
                }
            }
        } else {
            var decoded = new GpuLinearImage(source, color);
            var resized = new GpuLinearImage(width, height);
            resized.draw(decoded, 0, 0, decoded.width, decoded.height, 0, 0, width, height);
            var values = resized.buffer();
            for (int index = 0; index < width * height * 4; index++) {
                float value = values.get(index);
                if (color && (index & 3) != 3) { value = GpuColor.display(value); }
                upload.put((byte) Math.round(Math.clamp(value, 0f, 1f) * 255));
            }
        }
        upload.flip();
        return upload;
    }
}
