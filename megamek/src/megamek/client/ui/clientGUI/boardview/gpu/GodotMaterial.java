/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A board material: a Godot 4 text resource ({@code <name>.tres}) and the maps it names. Most are
 * {@code StandardMaterial3D}s, as Material Maker's "Godot/Godot 4 Standard" export writes them
 * ({@code <name>_albedo.png}, {@code _orm.png}, {@code _normal.png}, {@code _heightmap.png}, {@code _emission.png},
 * {@code _sss.png}). A material with a map StandardMaterial3D has no slot for is a {@code ShaderMaterial} of the
 * {@code <name>.gdshader} beside it: its parameters take the names of Godot's own converted shaders ({@code albedo},
 * {@code texture_albedo}, {@code roughness}, ...), its {@code texture_orm} holds occlusion, roughness and metallic in R, G
 * and B as Godot's ORMMaterial3D packs them, and each extra map is a {@code texture_<map>} parameter. As in Godot, a
 * parameter the resource omits takes the shader's declared default, and an undeclared one does not exist.
 * <p>
 * Both Material Maker's {@code format=2} spelling and Godot's own {@code format=3} files are read. A path is relative to
 * the resource's folder, or a {@code res://} path relative to the board's textures folder (the folder a Godot project
 * opens). Only the properties the board renders are read (see docs/gpu-board-materials.md); an omitted
 * StandardMaterial3D property keeps Godot's default. CPU-only: no graphics context.
 */
final class GodotMaterial {
    /** Godot's BaseMaterial3D.TextureChannel order. */
    static final int RED = 0, GREEN = 1, BLUE = 2, ALPHA = 3, GRAYSCALE = 4;
    /** Godot's BaseMaterial3D.EmissionOperator order. */
    static final int EMISSION_ADD = 0, EMISSION_MULTIPLY = 1;
    private static final String PARAMETER = "shader_parameter/";
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    // A material parameter: neither a global nor an instance uniform. Hints and the default follow the name.
    private static final Pattern UNIFORM = Pattern.compile(
          "(?<![\\w ])\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*(?::[^=;]*)?(?:=([^;]*))?;");
    private static final Pattern SECTION = Pattern.compile("\\[(\\w+)((?:\\s+\\w+=(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s\\]]+))*)\\s*]");
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)=(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s\\]]+)");
    private static final Pattern EXTERNAL = Pattern.compile("ExtResource\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\d+)\\s*\\)");

    /** A Material Maker StandardMaterial3D, or the ShaderMaterial of the shader beside it. */
    enum Kind { STANDARD, SHADER }

    final File file;
    final Kind kind;
    private final Map<String, String> properties;
    private final Map<String, File> resources;
    /** A ShaderMaterial's shader parameters in declaration order: name to declared default, or null for none. */
    private final Map<String, String> uniforms;

    private GodotMaterial(File file, Kind kind, Map<String, String> properties, Map<String, File> resources,
          Map<String, String> uniforms) {
        this.file = file;
        this.kind = kind;
        this.properties = properties;
        this.resources = resources;
        this.uniforms = uniforms;
    }

    /** {@code textures}: the folder {@code res://} paths resolve against. */
    static GodotMaterial read(File file, File textures) {
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read board material " + file, error);
        }
        Map<String, String> properties = new HashMap<>();
        Map<String, File> resources = new HashMap<>();
        Kind kind = null;
        String section = null;
        String key = null;
        StringBuilder value = new StringBuilder();
        boolean header = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (key != null) {
                // A multi-line value (an array or dictionary) continues until its brackets close.
                value.append('\n').append(trimmed);
                if (balanced(value)) { properties.put(key, value.toString()); key = null; }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith(";")) { continue; }
            Matcher match = SECTION.matcher(trimmed);
            if (match.matches()) {
                section = match.group(1);
                Map<String, String> attributes = attributes(match.group(2));
                if (section.equals("gd_resource")) {
                    kind = switch (String.valueOf(attributes.get("type"))) {
                        case "StandardMaterial3D" -> Kind.STANDARD;
                        case "ShaderMaterial" -> Kind.SHADER;
                        default -> throw new IllegalArgumentException("Board materials are Godot 4 StandardMaterial3D"
                              + " resources (Material Maker's Godot/Godot 4 Standard export) or ShaderMaterials: " + file);
                    };
                    header = true;
                } else if (section.equals("ext_resource")) {
                    String id = attributes.get("id"), path = attributes.get("path");
                    if (id == null || path == null) { throw new IllegalArgumentException("Incomplete ext_resource in " + file); }
                    resources.put(id, resolve(file, textures, path));
                }
                continue;
            }
            if (!header) { throw new IllegalArgumentException("Not a Godot text resource: " + file); }
            if (!"resource".equals(section)) { continue; }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) { throw new IllegalArgumentException("Unreadable line in " + file + ": " + trimmed); }
            key = trimmed.substring(0, equals).strip();
            value.setLength(0);
            value.append(trimmed.substring(equals + 1).strip());
            if (balanced(value)) { properties.put(key, value.toString()); key = null; }
        }
        if (!header) { throw new IllegalArgumentException("Not a Godot text resource: " + file); }
        if (key != null) { throw new IllegalArgumentException("Unterminated value of " + key + " in " + file); }
        var material = new GodotMaterial(file, kind, properties, resources, Map.of());
        if (kind == Kind.STANDARD) {
            File shader = new File(file.getParentFile(), material.name() + ".gdshader");
            if (shader.isFile()) {
                throw new IllegalArgumentException(file + " is a StandardMaterial3D, but " + shader.getName() + " beside it"
                      + " makes it a ShaderMaterial: export it with its project's Godot 4 ShaderMaterial target");
            }
            return material;
        }
        File shader = material.texture("shader");
        if (shader == null) { throw new IllegalArgumentException("A ShaderMaterial needs its shader: " + file); }
        return new GodotMaterial(file, kind, properties, resources, uniforms(shader));
    }

    /** A shader's material parameters: name to declared default (null for none), in declaration order. */
    private static Map<String, String> uniforms(File shader) {
        String code;
        try {
            code = Files.readString(shader.toPath(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("Missing required board material shader: " + shader, error);
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher match = UNIFORM.matcher(COMMENT.matcher(code).replaceAll(""));
        while (match.find()) { result.put(match.group(2), match.group(3) == null ? null : match.group(3).strip()); }
        return result;
    }

    private static boolean balanced(CharSequence value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && quoted) { i++; }
            else if (c == '"') { quoted = !quoted; }
            else if (!quoted && (c == '(' || c == '[' || c == '{')) { depth++; }
            else if (!quoted && (c == ')' || c == ']' || c == '}')) { depth--; }
        }
        return depth <= 0 && !quoted;
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> result = new HashMap<>();
        Matcher match = ATTRIBUTE.matcher(text);
        while (match.find()) { result.put(match.group(1), unquote(match.group(2))); }
        return result;
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"') { return value; }
        return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static File resolve(File file, File textures, String path) {
        File resolved = path.startsWith("res://") ? new File(textures, path.substring("res://".length()))
              : new File(file.getParentFile(), path);
        // Maps belong to the board's data: a path may not leave the textures folder.
        if (!resolved.toPath().toAbsolutePath().normalize().startsWith(textures.toPath().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Board material maps must lie in the textures folder: " + path + " in " + file);
        }
        return resolved;
    }

    /** The name of the resource without its folder or extension: Material Maker's export prefix. */
    String name() {
        String name = file.getName();
        return name.endsWith(".tres") ? name.substring(0, name.length() - ".tres".length()) : name;
    }

    boolean has(String property) { return properties.containsKey(property); }

    /** A string property's value, or null when the property is absent. */
    String text(String property) {
        String value = properties.get(property);
        return value == null ? null : unquote(value);
    }

    /** The file a texture (or shader) property names, or null when the property is absent. */
    File texture(String property) {
        String value = properties.get(property);
        if (value == null) { return null; }
        Matcher match = EXTERNAL.matcher(value);
        if (!match.matches()) {
            throw new IllegalArgumentException(property + " must name an ext_resource image, not " + value + ": " + file);
        }
        File texture = resources.get(unquote(match.group(1)));
        if (texture == null) { throw new IllegalArgumentException("Unknown ext_resource " + value + " in " + file); }
        return texture;
    }

    float number(String property, float fallback) {
        String value = properties.get(property);
        return value == null ? fallback : parseNumber(property, value);
    }

    private float parseNumber(String property, String value) {
        try {
            float number = Float.parseFloat(value);
            if (!Float.isFinite(number)) { throw new NumberFormatException(); }
            return number;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(property + " must be a finite number, not " + value + ": " + file);
        }
    }

    int integer(String property, int fallback) {
        String value = properties.get(property);
        if (value == null) { return fallback; }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(property + " must be an integer, not " + value + ": " + file);
        }
    }

    boolean flag(String property, boolean fallback) {
        String value = properties.get(property);
        if (value == null) { return fallback; }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(property + " must be true or false, not " + value + ": " + file);
        };
    }

    /** A {@code Color(r, g, b, a)} value. */
    float[] color(String property, float... fallback) {
        String value = properties.get(property);
        return value == null ? fallback : parseColor(property, value);
    }

    /** A resource's {@code Color(r, g, b, a)}, or a shader default's {@code vec4(r, g, b, a)} or {@code vec4(v)}. */
    private float[] parseColor(String property, String value) {
        String compact = value.replace(" ", "");
        if (compact.startsWith("vec4(") && compact.indexOf(',') < 0) {
            String all = compact.substring("vec4(".length(), compact.length() - 1);
            compact = "vec4(" + all + "," + all + "," + all + "," + all + ")";
        }
        int open = compact.indexOf('(');
        if (!(compact.startsWith("Color(") || compact.startsWith("vec4(")) || !compact.endsWith(")")) {
            throw new IllegalArgumentException(property + " must be a Color, not " + value + ": " + file);
        }
        String[] parts = compact.substring(open + 1, compact.length() - 1).split(",");
        float[] result = { 1, 1, 1, 1 };
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException(property + " must be a Color, not " + value + ": " + file);
        }
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].strip());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(property + " must be a Color, not " + value + ": " + file);
            }
        }
        return result;
    }

    /** A texture channel property: Godot's BaseMaterial3D.TextureChannel, red by default. */
    private int channel(String property) {
        int channel = integer(property, RED);
        if (channel < RED || channel > GRAYSCALE) {
            throw new IllegalArgumentException(property + " must be a texture channel 0-4: " + file);
        }
        return channel;
    }

    /** Whether this ShaderMaterial's shader declares a parameter (Godot ignores values of undeclared ones). */
    boolean declares(String parameter) { return kind == Kind.SHADER && uniforms.containsKey(parameter); }

    /** A map parameter: the file the resource assigns, or null when the shader does not declare it or none is set. */
    private File parameterTexture(String parameter) { return declares(parameter) ? texture(PARAMETER + parameter) : null; }

    /** A number parameter: the resource's value, else the shader's default, else Godot's zero; undeclared, absent. */
    private float parameterNumber(String parameter, float absent) {
        if (!declares(parameter)) { return absent; }
        String value = properties.getOrDefault(PARAMETER + parameter, uniforms.get(parameter));
        return value == null ? 0 : parseNumber(PARAMETER + parameter, value);
    }

    /** A colour parameter, as {@link #parameterNumber}; undeclared, white (no tint). */
    private float[] parameterColor(String parameter) {
        if (!declares(parameter)) { return new float[] { 1, 1, 1, 1 }; }
        String value = properties.getOrDefault(PARAMETER + parameter, uniforms.get(parameter));
        return value == null ? new float[4] : parseColor(PARAMETER + parameter, value);
    }

    // The properties the board renders, with Godot's defaults and enable flags.

    File albedo() {
        String property = kind == Kind.SHADER ? PARAMETER + "texture_albedo" : "albedo_texture";
        File albedo = kind == Kind.SHADER ? parameterTexture("texture_albedo") : texture(property);
        if (albedo == null) { throw new IllegalArgumentException("A board material needs its " + property + ": " + file); }
        return albedo;
    }

    float[] albedoColor() { return kind == Kind.SHADER ? parameterColor("albedo") : color("albedo_color", 1, 1, 1, 1); }

    /**
     * Godot's alpha blending: a StandardMaterial3D's {@code transparency} ALPHA (1) or ALPHA_DEPTH_PRE_PASS (4). Its
     * opacity is the albedo's alpha times {@code albedo_color}'s, as the colour map packs it.
     */
    boolean blended() {
        int transparency = kind == Kind.STANDARD ? integer("transparency", 0) : 0;
        return transparency == 1 || transparency == 4;
    }

    /** Godot's BaseMaterial3D.BlendMode: MIX (0) by default, PREMULT_ALPHA (4) for glass whose reflections stay whole. */
    int blendMode() { return kind == Kind.STANDARD ? integer("blend_mode", 0) : 0; }

    File normal() {
        if (kind == Kind.SHADER) { return parameterTexture("texture_normal"); }
        return flag("normal_enabled", false) ? texture("normal_texture") : null;
    }

    float normalScale() { return kind == Kind.SHADER ? parameterNumber("normal_scale", 1) : number("normal_scale", 1); }

    File occlusion() {
        if (kind == Kind.SHADER) { return parameterTexture("texture_orm"); }
        return flag("ao_enabled", false) ? texture("ao_texture") : null;
    }

    int occlusionChannel() { return kind == Kind.SHADER ? RED : channel("ao_texture_channel"); }

    File roughnessMap() { return kind == Kind.SHADER ? parameterTexture("texture_orm") : texture("roughness_texture"); }

    int roughnessChannel() { return kind == Kind.SHADER ? GREEN : channel("roughness_texture_channel"); }

    float roughness() { return kind == Kind.SHADER ? parameterNumber("roughness", 1) : number("roughness", 1); }

    File metallicMap() { return kind == Kind.SHADER ? parameterTexture("texture_orm") : texture("metallic_texture"); }

    int metallicChannel() { return kind == Kind.SHADER ? BLUE : channel("metallic_texture_channel"); }

    float metallic() { return kind == Kind.SHADER ? parameterNumber("metallic", 0) : number("metallic", 0); }

    /** Material Maker's tessellated material names the heightmap {@code depth_texture}; Godot 4 names it heightmap. */
    File heightmap() {
        if (kind == Kind.SHADER) { return parameterTexture("texture_heightmap"); }
        if (!flag("heightmap_enabled", false)) { return null; }
        File heightmap = texture("heightmap_texture");
        return heightmap != null ? heightmap : texture("depth_texture");
    }

    /** Godot's heightmap depth in texture UV units, times 100. */
    float heightmapScale() {
        return kind == Kind.SHADER ? parameterNumber("heightmap_scale", 5) : number("heightmap_scale", 5);
    }

    /** A StandardMaterial3D's flip; a board shader declares none (Godot compiles the flip into its converted shaders). */
    boolean heightmapFlipped() { return kind == Kind.STANDARD && flag("heightmap_flip_texture", false); }

    File emission() {
        if (kind == Kind.SHADER) { return parameterTexture("texture_emission"); }
        return flag("emission_enabled", false) ? texture("emission_texture") : null;
    }

    /** A board shader's emission is its own; it declares no StandardMaterial3D emission. */
    boolean emissionEnabled() { return kind == Kind.STANDARD && flag("emission_enabled", false); }

    /** The emission colour as Godot stores it, sRGB; black by default. */
    float[] emissionColor() { return color("emission", 0, 0, 0, 1); }

    /** How the emission colour combines with its map: {@link #EMISSION_ADD} (Godot's default) or multiply. */
    int emissionOperator() {
        int operator = integer("emission_operator", EMISSION_ADD);
        if (operator != EMISSION_ADD && operator != EMISSION_MULTIPLY) {
            throw new IllegalArgumentException("emission_operator must be 0 (add) or 1 (multiply): " + file);
        }
        return operator;
    }

    /** Godot 4 renamed emission_energy to emission_energy_multiplier; Material Maker writes the former. */
    float emissionEnergy() {
        if (kind == Kind.SHADER) { return parameterNumber("emission_energy", 1); }
        return has("emission_energy_multiplier") ? number("emission_energy_multiplier", 1) : number("emission_energy", 1);
    }

    File subsurface() {
        if (kind == Kind.SHADER) { return parameterTexture("texture_subsurface_scattering"); }
        return flag("subsurf_scatter_enabled", false) ? texture("subsurf_scatter_texture") : null;
    }

    /** Whether the material scatters at all: a StandardMaterial3D enables it, a board shader declares its strength. */
    boolean subsurfaceEnabled() {
        return kind == Kind.SHADER ? declares("subsurface_scattering_strength") : flag("subsurf_scatter_enabled", false);
    }

    float subsurfaceStrength() {
        return kind == Kind.SHADER ? parameterNumber("subsurface_scattering_strength", 0) : number("subsurf_scatter_strength", 0);
    }

    /** A map baked for the game beside this material's own, which neither its .tres nor its shader names: {@code <name>_<map>.png}. */
    File beside(String map) {
        String name = file.getName();
        return new File(file.getParentFile(), name.substring(0, name.lastIndexOf('.')) + "_" + map + ".png");
    }

    /**
     * A map StandardMaterial3D has no slot for: a ShaderMaterial's {@code texture_<map>} parameter, which its shader
     * declares (docs/gpu-board-materials.md lists them).
     */
    File extra(String map) {
        File extra = parameterTexture("texture_" + map);
        if (extra == null) {
            throw new IllegalArgumentException("A board material needs its " + PARAMETER + "texture_" + map + " (a "
                  + "ShaderMaterial whose shader declares it): " + file);
        }
        return extra;
    }
}
