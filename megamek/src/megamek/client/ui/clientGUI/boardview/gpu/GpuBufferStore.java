/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.TreeMap;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

/**
 * First-fit ranges of a GL buffer's elements of {@code bytes} each, grown by copying into a buffer twice the size (the
 * GPU-driven path's pools, instances and records). A store {@code kept} holds its contents in memory too and grows
 * from those: the 4.1 way never copies buffer to buffer, which macOS's driver does not implement (its log:
 * "gldCopyBufferSubData: NEEDS IMPLEMENTATION"). Raw LWJGL; render thread.
 */
final class GpuBufferStore {
    final int bytes;
    private final boolean kept;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private ByteBuffer memory;
    int handle, capacity;
    /** Changes whenever the buffer is replaced, so vertex arrays and buffer textures point at the new one. */
    int generation;

    GpuBufferStore(int bytes, boolean kept) {
        this.bytes = bytes;
        this.kept = kept;
    }

    /** The first element of a free run of {@code size}, the buffer grown where none is free. */
    int take(int size) {
        int at = fit(size);
        if (at >= 0) { return at; }
        int grown = Math.max(Math.max(capacity * 2, capacity + size), 4096);
        int bigger = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, bigger);
        if (kept) {
            memory = MemoryUtil.memRealloc(memory, grown * bytes);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, memory, GL15C.GL_STATIC_DRAW);
        } else {
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) grown * bytes, GL15C.GL_STATIC_DRAW);
            if (handle != 0) {
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, handle);
                GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0,
                      (long) capacity * bytes);
            }
        }
        if (handle != 0) { GL15C.glDeleteBuffers(handle); }
        handle = bigger;
        give(capacity, grown - capacity);
        capacity = grown;
        generation++;
        return fit(size);
    }

    private int fit(int size) {
        for (Map.Entry<Integer, Integer> range : free.entrySet()) {
            int at = range.getKey(), length = range.getValue();
            if (length < size) { continue; }
            free.remove(at);
            if (length > size) { free.put(at + size, length - size); }
            return at;
        }
        return -1;
    }

    /** Gives back {@code size} elements from {@code at}, joined with the free runs beside them. */
    void give(int at, int size) {
        if (size <= 0) { return; }
        Map.Entry<Integer, Integer> below = free.floorEntry(at);
        if (below != null && below.getKey() + below.getValue() == at) {
            free.remove(below.getKey());
            at = below.getKey();
            size += below.getValue();
        }
        Integer above = free.remove(at + size);
        if (above != null) { size += above; }
        free.put(at, size);
    }

    /** Writes {@code data}'s remaining values from element {@code at}. */
    void write(int at, FloatBuffer data) {
        if (kept) { MemoryUtil.memCopy(MemoryUtil.memAddress(data), address(at), (long) data.remaining() * Float.BYTES); }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, handle);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) at * bytes, data);
    }

    void write(int at, ShortBuffer data) {
        if (kept) { MemoryUtil.memCopy(MemoryUtil.memAddress(data), address(at), (long) data.remaining() * Short.BYTES); }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, handle);
        GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) at * bytes, data);
    }

    /** Where element {@code at} is in memory, of a store that is kept. */
    long address(int at) { return MemoryUtil.memAddress(memory) + (long) at * bytes; }

    void dispose() {
        if (handle != 0) { GL15C.glDeleteBuffers(handle); }
        handle = 0;
        MemoryUtil.memFree(memory);
        memory = null;
    }
}
