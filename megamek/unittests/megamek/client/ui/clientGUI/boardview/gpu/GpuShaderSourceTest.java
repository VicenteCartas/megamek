/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A CPU check for forbidden identifiers in shipped shaders. GpuGlsl selects GLSL 3.30 or newer; legal language
 * keywords such as layout, uint and sampler2DArray must remain available. Native smoke tests check compilation.
 */
class GpuShaderSourceTest {
    /**
     * Reserved for future use through GLSL 4.60, section 3.6:
     * https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf
     */
    private static final Set<String> RESERVED = Set.of("common", "partition", "active", "asm", "class", "union",
          "enum", "typedef", "template", "this", "resource", "goto", "inline", "noinline", "public", "static",
          "extern", "external", "interface", "long", "short", "half", "fixed", "unsigned", "superp", "input",
          "output", "hvec2", "hvec3", "hvec4", "fvec2", "fvec3", "fvec4", "filter", "sizeof", "cast",
          "namespace", "using", "sampler3DRect");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
    private static final Set<String> LEGACY = Set.of("attribute", "varying", "texture2D", "texture2DLod",
          "textureCube", "gl_FragColor", "gl_FragData");

    @Test
    void shadersUseCoreSyntaxWithoutLegacyAliasesOrReservedIdentifiers() throws Exception {
        Path folder = Path.of(GpuShaderSourceTest.class
              .getResource("/megamek/client/ui/clientGUI/boardview/gpu/terrain-sculpt.frag").toURI()).getParent();
        List<String> found = new ArrayList<>();
        int shaders = 0;
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches(".*\\.(frag|vert|glsl|comp|tesc|tese)")).toList()) {
                shaders++;
                String source = COMMENT.matcher(Files.readString(file))
                      .replaceAll(match -> match.group().replaceAll("[^\\n]", " "));
                // Included modules may replace main (terrain-ice.glsl), inheriting the enclosing stage's header.
                if (!file.toString().endsWith(".glsl") && source.contains("void main(")) {
                    assertTrue(source.stripLeading().startsWith("#version 330 core"), "Standalone stage version: " + file);
                    // A depth-only stage writes no colour and declares no output: an empty main (water-depth.frag), or
                    // one that writes the depth alone (shadow-levels.frag).
                    boolean depthOnly = source.matches("(?s).*void main\\(\\)\\s*\\{\\s*\\}.*")
                          || source.contains("gl_FragDepth") && !source.contains("fragColor");
                    if (file.toString().endsWith(".frag") && !depthOnly) {
                        assertTrue(source.matches("(?s).*\\blayout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)"
                              + "\\s*out\\s+vec[234]\\s+fragColor\\s*;.*"), "Explicit output: " + file);
                    }
                }
                String[] lines = source.split("\n", -1);
                for (int line = 0; line < lines.length; line++) {
                    Matcher word = IDENTIFIER.matcher(lines[line]);
                    while (word.find()) {
                        if (RESERVED.contains(word.group()) || LEGACY.contains(word.group())) {
                            found.add(file.getFileName() + ":" + (line + 1) + " '" + word.group() + "'");
                        }
                    }
                }
            }
        }
        assertTrue(shaders > 10, "The shader resources were found: " + folder);
        assertTrue(found.isEmpty(), "Reserved or legacy GLSL identifiers in shader code: " + found);
    }

    /**
     * A family program (GpuFamilyPrograms) makes the surface families constant through the sculptFamily and
     * coverFamilies macros. A direct uniform read would still draw correctly, but keep every family's code.
     */
    @Test
    void surfaceFamiliesAreReadThroughTheirMacros() throws Exception {
        Path folder = Path.of(GpuShaderSourceTest.class
              .getResource("/megamek/client/ui/clientGUI/boardview/gpu/terrain-sculpt.frag").toURI()).getParent();
        Pattern uniform = Pattern.compile("\\bu_(sculptFamily|coverFamilies)\\b");
        List<String> reads = new ArrayList<>();
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches(".*\\.(frag|glsl)")).toList()) {
                String[] lines = COMMENT.matcher(Files.readString(file)).replaceAll("").split("\n", -1);
                for (String line : lines) {
                    String code = line.strip();
                    if (uniform.matcher(code).find() && !code.startsWith("uniform ") && !code.startsWith("#define ")) {
                        reads.add(file.getFileName() + ": " + code);
                    }
                }
            }
        }
        assertTrue(reads.isEmpty(), "Read the families through sculptFamily and coverFamilies: " + reads);
    }
}
