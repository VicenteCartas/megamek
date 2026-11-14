/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

/**
 * The board's own GL programs, outside libGDX's ShaderProgram: linked once, from the program cache where it holds them
 * (GpuProgramCache, keyed by the driver and the complete sources), else from source, and kept there for the next run.
 * Raw LWJGL handles; render thread.
 */
final class GpuGlProgram {
    private GpuGlProgram() { }

    /** A compute program of this complete source, or 0 where it does not link (logged with {@code name}). */
    static int compute(String name, String source) {
        GpuProgramCache cache = GpuGlsl.programCache;
        // The cache keys two stages: a compute program's one source, and a marker no vertex/fragment pair holds.
        String key = cache == null ? null : cache.key(source, "compute");
        int program = GL20C.glCreateProgram();
        if (key != null && cache.load(program, key)) { return program; }
        int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        GL20C.glAttachShader(program, shader);
        if (key != null) { GpuProgramCache.retrievable(program); }
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, shader);
        GL20C.glDeleteShader(shader);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            megamek.logging.MMLogger.create(GpuGlProgram.class).error(name + ": " + GL20C.glGetProgramInfoLog(program));
            GL20C.glDeleteProgram(program);
            return 0;
        }
        if (key != null) { cache.store(program, key); }
        return program;
    }
}
