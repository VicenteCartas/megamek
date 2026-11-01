/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

import megamek.logging.MMLogger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Linked shader programs kept from earlier runs, so a launch loads unchanged programs instead of compiling them. The
 * drivers' own caches do not suffice: Intel's stops storing once it reaches its size cap. An entry is named by the
 * SHA-256 of the driver (vendor, renderer and version string, which carries the build) and both complete stages, and
 * holds the driver's binary format, a CRC-32C and the binary. A damaged entry, or one the driver rejects, is deleted
 * and the program compiles from source. A driver without binary formats (macOS may report none) caches nothing.
 * Opening the cache, and every 256 MB it writes, deletes entries unused for 30 days, then the least recently used
 * beyond 1 GB, the size of NVIDIA's and Mesa's own caches: one board takes about 55 MB per lighting configuration on
 * NVIDIA.
 */
final class GpuProgramCache {
    private static final MMLogger LOGGER = MMLogger.create(GpuProgramCache.class);
    /** {@code -Dmegamek.gpu.programCache=false} compiles every program from source, for timing the compiler. */
    static final String PROPERTY = "megamek.gpu.programCache";
    /** {@code -Dmegamek.gpu.programCacheDir=...} keeps the entries elsewhere, so test runs never evict the player's. */
    static final String DIRECTORY_PROPERTY = "megamek.gpu.programCacheDir";
    static final long CAPACITY = 1L << 30;
    /**
     * Part of every key. Without the parallel compiler, programs used to link on the render thread after it drew to the
     * window, and Intel's binaries keep that variant, which compiles again at its first scene draw. Programs now link
     * on a context that never draws (GpuGlsl), so those entries miss once.
     */
    private static final String KEY_SALT = "linked where nothing draws";
    static final Duration UNUSED = Duration.ofDays(30);
    /** A partial write this old belongs to no running MegaMek. */
    private static final Duration ABANDONED = Duration.ofDays(1);
    private static final Pattern ENTRY = Pattern.compile("[0-9a-f]{64}\\.bin");
    private static final Pattern PARTIAL = Pattern.compile("[0-9a-f]{64}-\\d+\\.tmp");
    private static final int MAGIC = 0x4D4D5042, VERSION = 1, HEADER = 20;
    /**
     * Intel's own shader cache on Windows. Once a file there reaches {@link #INTEL_CACHE_CAP}, the driver stores
     * nothing more. The loading stage tells the player to clears the folder.
     */
    static final Path INTEL_CACHE = Path.of(System.getProperty("user.home"), "AppData", "LocalLow", "Intel",
          "ShaderCache");
    static final long INTEL_CACHE_CAP = 512L << 20;
    private final Path directory;
    private final String driver;
    private final int[] formats;
    /** Bytes written since the last pruning: a long session, such as live shader editing, prunes again. */
    private final AtomicLong unpruned = new AtomicLong();
    /** Diagnostics: entries loaded into programs, entries stored, and entries discarded as damaged or rejected. */
    final AtomicInteger loaded = new AtomicInteger(), stored = new AtomicInteger(), discarded = new AtomicInteger();

    record Binary(int format, byte[] data) { }

    GpuProgramCache(Path directory, String driver, int... formats) {
        this.directory = directory;
        this.driver = driver;
        this.formats = formats.clone();
    }

    /**
     * The cache for the current context's driver, or null when the driver offers no binary format, the property turns
     * it off or the directory cannot be created. Call on a thread whose context is current.
     */
    static GpuProgramCache open(Path directory) {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) { return null; }
        var capabilities = GL.getCapabilities();
        if (!capabilities.OpenGL41 && !capabilities.GL_ARB_get_program_binary) { return null; }
        int[] formats = new int[Math.max(0, GL11.glGetInteger(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS))];
        if (formats.length == 0) { return null; }
        GL11.glGetIntegerv(GL41C.GL_PROGRAM_BINARY_FORMATS, formats);
        try {
            Files.createDirectories(directory);
        } catch (IOException | RuntimeException failure) {
            LOGGER.info("GPU program cache unavailable in {}: {}", directory, failure.toString());
            return null;
        }
        var cache = new GpuProgramCache(directory, GL11.glGetString(GL11.GL_VENDOR) + '\n'
              + GL11.glGetString(GL11.GL_RENDERER) + '\n' + GL11.glGetString(GL11.GL_VERSION) + '\n' + KEY_SALT, formats);
        cache.prune();
        return cache;
    }

    /** Whether a file in {@code folder} has reached {@code cap} bytes; false when it cannot be read. Only reads sizes. */
    static boolean full(Path folder, long cap) {
        try (var files = Files.list(folder)) {
            return files.anyMatch(file -> file.toFile().length() >= cap);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** The directory the property names, else {@code standard}. */
    static Path directory(Path standard) {
        String directory = System.getProperty(DIRECTORY_PROPERTY, "");
        return directory.isBlank() ? standard : Path.of(directory);
    }

    private void prune() {
        try {
            prune(directory, Instant.now(), UNUSED, CAPACITY);
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug(failure, "Could not prune the GPU program cache in {}", directory);
        }
    }

    /** The entry name of these complete stages on this driver. */
    String key(String vertex, String fragment) {
        return key(driver, vertex, fragment);
    }

    /** SHA-256 of the driver and both stages, separated by NUL, which neither a driver string nor GLSL contains. */
    static String key(String driver, String vertex, String fragment) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] { driver, vertex, fragment }) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("Every Java platform provides SHA-256", missing);
        }
    }

    /** Whether there is an entry for these stages; loading it can still find it damaged or rejected. */
    boolean has(String key) {
        return Files.isRegularFile(directory.resolve(key + ".bin"));
    }

    /**
     * Loads the entry into the unlinked program on the current thread's context. False leaves the program to compile
     * from source: there is no entry, or a damaged or rejected one, which is deleted.
     */
    boolean load(int program, String key) {
        Binary binary = read(key);
        if (binary == null) { return false; }
        ByteBuffer data = MemoryUtil.memAlloc(binary.data().length);
        try {
            GL41C.glProgramBinary(program, binary.format(), data.put(binary.data()).flip());
        } finally {
            MemoryUtil.memFree(data);
        }
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != 0) {
            loaded.incrementAndGet();
            return true;
        }
        // A driver update that kept its version string, for instance.
        LOGGER.info("GPU program binary {} rejected by the driver; compiling from source", key);
        discard(key);
        return false;
    }

    /** Before a source link, so that the driver keeps a binary to retrieve. */
    static void retrievable(int program) {
        GL41C.glProgramParameteri(program, GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);
    }

    /** Keeps a successfully linked program; a failed write only costs the next run a compile. */
    void store(int program, String key) {
        int length = GL20C.glGetProgrami(program, GL41C.GL_PROGRAM_BINARY_LENGTH);
        if (length <= 0) { return; }
        ByteBuffer data = MemoryUtil.memAlloc(length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer written = stack.mallocInt(1), format = stack.mallocInt(1);
            GL41C.glGetProgramBinary(program, written, format, data);
            byte[] bytes = new byte[written.get(0)];
            data.get(0, bytes);
            if (write(key, format.get(0), bytes)) { stored.incrementAndGet(); }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** The valid entry, marked as just used; null when there is none and, after deleting it, when it is unusable. */
    Binary read(String key) {
        Path file = directory.resolve(key + ".bin");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException failure) {
            LOGGER.debug(failure, "Could not read GPU program binary {}", key);
            return null;
        }
        var header = ByteBuffer.wrap(bytes);
        if (bytes.length >= HEADER && header.getInt() == MAGIC && header.getInt() == VERSION) {
            int format = header.getInt(), length = header.getInt(), checksum = header.getInt();
            if (length > 0 && length == bytes.length - HEADER && supported(format)
                  && checksum(bytes, HEADER, length) == checksum) {
                try {
                    Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
                } catch (IOException ignored) {
                    // Only its pruning order changes.
                }
                return new Binary(format, Arrays.copyOfRange(bytes, HEADER, bytes.length));
            }
        }
        LOGGER.info("GPU program binary {} is damaged or of another format; compiling from source", key);
        discard(key);
        return null;
    }

    /**
     * Writes the whole entry to a uniquely named partial file, then moves it into place, so neither a reader nor
     * another writer, also in another MegaMek, sees part of one.
     */
    boolean write(String key, int format, byte[] data) {
        Path partial = null;
        try {
            partial = Files.createTempFile(directory, key + "-", ".tmp");
            var header = ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(VERSION).putInt(format).putInt(data.length)
                  .putInt(checksum(data, 0, data.length));
            try (var output = Files.newOutputStream(partial)) {
                output.write(header.array());
                output.write(data);
            }
            Files.move(partial, directory.resolve(key + ".bin"), StandardCopyOption.REPLACE_EXISTING,
                  StandardCopyOption.ATOMIC_MOVE);
            if (unpruned.addAndGet(data.length) > CAPACITY / 4) {
                unpruned.set(0);
                prune();
            }
            return true;
        } catch (IOException failure) {
            LOGGER.debug(failure, "Could not store GPU program binary {}", key);
            try {
                if (partial != null) { Files.deleteIfExists(partial); }
            } catch (IOException ignored) {
                // Pruned once abandoned.
            }
            return false;
        }
    }

    private void discard(String key) {
        discarded.incrementAndGet();
        try {
            Files.deleteIfExists(directory.resolve(key + ".bin"));
        } catch (IOException failure) {
            LOGGER.debug(failure, "Could not delete GPU program binary {}", key);
        }
    }

    private boolean supported(int format) {
        for (int candidate : formats) {
            if (candidate == format) { return true; }
        }
        return false;
    }

    private static int checksum(byte[] data, int offset, int length) {
        var crc = new CRC32C();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    /**
     * Deletes entries unused for longer than {@code unused}, then, most recently used first, every entry beyond
     * {@code capacity} bytes, and partial writes abandoned for a day. Other files are left alone.
     */
    static void prune(Path directory, Instant now, Duration unused, long capacity) throws IOException {
        record Entry(Path file, Instant used, long size) { }
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                Instant used = attributes.lastModifiedTime().toInstant();
                if (!attributes.isRegularFile()) { continue; }
                if (ENTRY.matcher(name).matches()) {
                    entries.add(new Entry(file, used, attributes.size()));
                } else if (PARTIAL.matcher(name).matches() && used.isBefore(now.minus(ABANDONED))) {
                    Files.deleteIfExists(file);
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::used).reversed());
        long kept = 0;
        for (Entry entry : entries) {
            kept += entry.size();
            if (kept > capacity || entry.used().isBefore(now.minus(unused))) { Files.deleteIfExists(entry.file()); }
        }
    }
}
