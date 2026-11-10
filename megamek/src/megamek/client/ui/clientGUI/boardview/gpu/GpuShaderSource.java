/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import megamek.common.Configuration;
import megamek.common.board.HexTile;

/**
 * Shader reads: installation overrides, checkout resources, then the bundled defaults. Each file is read once
 * ({@link #readDisk}) until the shader editor saves or reloads files, or Reload assets runs ({@link #forget}): a file
 * edited outside the game shows after Reload assets.
 */
final class GpuShaderSource {
    private static final String RESOURCES = "megamek/client/ui/clientGUI/boardview/gpu/";
    /** Each file's text as {@link #readDisk} last read it, by data folder (tests switch it) and name. */
    private static final Map<String, String> DISK = new java.util.concurrent.ConcurrentHashMap<>();

    private GpuShaderSource() { }

    /** The board hex in model units ({@link HexTile}): every shader's hex lattice derives from this one size. */
    private static final String BOARD_HEX = "#ifndef BOARD_HEX_DEFINED\n#define BOARD_HEX_DEFINED\nconst vec2 BOARD_HEX = vec2("
          + HexTile.MODEL_WIDTH + ", " + HexTile.HEIGHT + ".0);\n#endif";

    private static final Pattern INCLUDE = Pattern.compile("(?m)^([ \\t]*)// ([A-Za-z][A-Za-z0-9_-]*)(?=[ \\t]*\\r?$)");

    static String read(String name) { return read(name, Map.of()); }

    /** File choices belong to the shader variant; recursive dependencies retain the same manager/draft owner. */
    static String read(String name, Map<String, String> choices) {
        String source = currentSource(name);
        requireChoices(source, choices);
        return expand(source, choices, new LinkedHashSet<>(Set.of(name)));
    }

    private static String currentSource(String name) {
        var session = GpuShaderManager.current();
        String source = session == null ? readDisk(name) : session.read(name);
        return name.equals("scene-radiance.glsl")
              ? source.replace("RADIANCE_SCALE_VALUE", Float.toString(GpuSceneRadiance.SCALE)) : source;
    }

    private static String read(String name, Map<String, String> choices, Set<String> path) {
        if (!path.add(name)) {
            throw new IllegalStateException("Shader include cycle: " + String.join(" -> ", path) + " -> " + name);
        }
        try {
            return expand(currentSource(name), choices, path);
        } finally { path.remove(name); }
    }

    /** Delayed includes, such as atmosphere's capability-dependent sun visibility, use the same resolver. */
    static String expand(String source, Map<String, String> choices) {
        requireChoices(source, choices);
        return expand(source, choices, new LinkedHashSet<>());
    }

    private static void requireChoices(String source, Map<String, String> choices) {
        for (String choice : choices.keySet()) {
            Matcher markers = INCLUDE.matcher(source);
            boolean found = false;
            while (markers.find()) { if (markers.group(2).equals(choice)) { found = true; break; } }
            if (!found) { throw new IllegalStateException("Missing required shader include: // " + choice); }
        }
    }

    private static String expand(String source, Map<String, String> choices, Set<String> path) {
        Matcher markers = INCLUDE.matcher(source);
        StringBuilder result = new StringBuilder();
        while (markers.find()) {
            String marker = markers.group(2);
            String included = choices.containsKey(marker) ? read(choices.get(marker), choices, path)
                  : include(marker, choices, path);
            markers.appendReplacement(result, Matcher.quoteReplacement(included == null ? markers.group()
                  : markers.group(1) + included));
        }
        return markers.appendTail(result).toString();
    }

    private static String include(String marker, Map<String, String> choices, Set<String> path) {
        String file = switch (marker) {
            case "COLOR_SPACE" -> "color-space.glsl";
            case "CUTOUT_FADE" -> "cutout-fade.glsl";
            case "IMPOSTOR_FRAMES" -> "impostor-frames.glsl";
            case "IMPOSTOR_SAMPLE" -> "impostor-sample.glsl";
            case "SCENE_RADIANCE" -> "scene-radiance.glsl";
            case "OVERLAY_CONTRAST" -> "overlay-contrast.glsl";
            case "LIGHT_MODEL" -> "light-model.glsl";
            case "FULL_SIZE_MAPS" -> "full-size-maps.glsl";
            case "SURFACE_NOISE" -> "surface-noise.glsl";
            case "LAVA_LIGHTS" -> "lava-lights.glsl";
            case "SHOCKS" -> "terrain-shocks.glsl";
            case "ENVIRONMENT_LIGHTING" -> "environment-lighting.glsl";
            case "TERRAIN_PATTERNS" -> "terrain-patterns.glsl";
            case "CLOUD_SHADOW" -> "cloud-shadow.glsl";
            case "SCATTERING_PHASE" -> "scattering-phase.glsl";
            case "GROUND_LAYER" -> "ground-layer.glsl";
            case "CAMERA_DEPTH" -> "camera-depth.glsl";
            case "WEATHER_SAND" -> "weather-sand.glsl";
            case "ATMOSPHERE_FOV" -> "atmosphere-fov.glsl";
            case "ATMOSPHERE_GRADE" -> "atmosphere-grade.glsl";
            case "ATMOSPHERE_GLARE" -> "atmosphere-glare.glsl";
            case "SPACE_SKY" -> "space-sky.glsl";
            case "HEAT_RADIANCE" -> "heat-radiance.glsl";
            case "OCEAN_FINISH" -> "ocean-finish.glsl";
            case "terrain-ground-color-functions" -> "terrain-ground-color.glsl";
            case "water-uniforms" -> "water-uniforms.glsl";
            case "water-wave-functions" -> "water-wave.glsl";
            default -> null;
        };
        if (file != null) { return read(file, choices, path); }
        return switch (marker) {
            case "PARTICLE_APPEARANCE" -> read("particles-smoke.glsl", choices, path) + "\n"
                  + read("particles-fire.glsl", choices, path) + "\n" + read("particles-jet.glsl", choices, path);
            case "terrain-projection-functions" -> read("terrain-projection.glsl", choices, path);
            case "BOARD_HEX" -> BOARD_HEX;
            case "SHOCK_SECONDS" -> "const float SHOCK_SECONDS = " + GpuArtilleryBlast.SHOCK_SECONDS + ";";
            case "FULL_SIZE_LAYERS" -> GpuAssets.carpetLayers();
            case "FULL_SIZE_ICE_LAYERS" -> GpuAssets.iceLayers(false);
            case "ROAD_ICE_LAYERS" -> GpuAssets.iceLayers(true);
            // Selection comments and structural insertion points are not file includes.
            default -> null;
        };
    }

    static String magma(boolean flowing) {
        return (flowing ? "#define magmaEmissionMapFlag\n" + read("liquid-flow.glsl") : "") + read("terrain-magma.glsl",
              Map.of("MAGMA_CONDITION", flowing ? "magma-flow.glsl" : "magma-solid.glsl"));
    }

    /**
     * The material finish, light model, rain field, local lights, ambient occlusion, sky visibility and geometry shadows
     * of every lit surface.
     */
    private static String sharedLighting() {
        return read("material-finish.glsl") + read("light-model.glsl") + read("environment-lighting.glsl") + read("rain-surface.glsl")
              + read("lava-lighting.glsl") + read("ambient-occlusion.glsl") + read("sky-visibility.glsl")
              + read("surface-lighting.glsl");
    }

    /** Imported models (model.frag) share the terrain's lighting and its one entry, without its material libraries. */
    static String modelLighting(String source) {
        String functions = sharedLighting() + read("water-optics.glsl") + read("surface-shading.glsl")
              + read("model-surface.glsl");
        return replaceOnce(source, "void main() {", "// model-lighting-functions\n" + functions + "\nvoid main() {",
              "model");
    }

    /** Grass and biome templates share these already-computed vertex outputs. */
    static String coverVertex(String source) {
        return source.replace("vec4 pos = u_worldTrans * vec4(a_position, 1.0);", "vec4 pos = vec4(coverPosition, 1.0);")
              .replace("vec3 normal = normalize(u_normalMatrix * a_normal);", "vec3 normal = coverNormal;")
              .replace("v_color = a_color;", "v_color = coverColor;");
    }

    /**
     * Size only programs whose assembled stages reference the finish arrays (finish-arrays.glsl): {@code count} board
     * materials, the road materials from {@code roads} on.
     */
    static String finishPrefix(int count, int roads, String vertex, String fragment) {
        return vertex.contains("FINISH_COUNT") || fragment.contains("FINISH_COUNT")
              ? "#define FINISH_COUNT " + count + "\n#define ROAD_FINISH " + roads + "\n" : "";
    }

    /** Structural edits retain their caller's ordering, with one shared exact-anchor check. */
    static String replaceOnce(String source, String anchor, String replacement, String stage) {
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) {
            throw new IllegalStateException("Incompatible " + stage
                  + " shader: expected exactly one insertion point: " + anchor);
        }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length());
    }

    /** Every custom surface shares the light model, rain field, lighting, geometry shadows and water optics. */
    static String terrainLighting(String source) {
        String functions = sharedLighting();
        functions += read("terrain-meadow.glsl");
        functions += read("terrain-patterns.glsl");
        functions += read("water-optics.glsl");
        functions += read("terrain-hexes.glsl");
        // Fields' ground takes its crops' colours.
        if (source.contains("// sculpt-material-functions")) {
            functions += GpuBiomeVegetation.cropSource();
        }
        String ground = source.contains("// ground-surface-functions") ? read("ground-surface.glsl") : "";
        // The one lighting entry, wherever a surface is lit through it. It reads a nuclear scorch from the ground
        // damage's directory, so every terrain surface lit there shows it.
        if (source.contains("shadeSurface(") || ground.contains("shadeSurface(")) {
            functions += "#define GROUND_DAMAGE_FRAGMENT\n" + GpuGroundDamage.shaderSource();
            functions += read("surface-shading.glsl");
        }
        if (source.contains("// sculpt-material-functions")) {
            functions += read("finish-arrays.glsl");
        } else if (source.contains("roadCoatFlag")) {
            functions += "\n#if defined(roadCoatFlag) || defined(roadMapsFlag)\n" + read("finish-arrays.glsl") + "\n#endif\n";
        }
        if (source.contains("u_meadowFinish")) {
            // Meadow blades take their ground's finish; crops and sedge their own.
            functions += "\n#ifndef biomeVegetationFlag\n" + read("finish-arrays.glsl") + "\n#endif\n";
        }
        if (source.contains("// sculpt-material-functions") || source.contains("// biome-water-functions")) {
            functions += read("terrain-biome-mask.glsl");
        }
        if (source.contains("// sculpt-material-functions")) {
            functions += read("terrain-rockiness.glsl");
            functions += read("terrain-concrete.glsl");
            functions += "\n#ifdef volcanicFlag\n" + magma(false) + "\n#endif\n";
            functions += read("terrain-materials.glsl");
            functions += read("scar-material.glsl");
            functions += read("terrain-damage.glsl");
            functions += read("terrain-tundra-sward.glsl");
            functions += read("terrain-tundra.glsl");
            functions += read("terrain-biome.glsl");
        }
        if (source.contains("// magma-solid-functions") || source.contains("// magma-flow-functions")) {
            functions += magma(source.contains("// magma-flow-functions"));
            functions += read("magma-lighting.glsl");
        }
        functions += ground;
        if (source.contains("// water-lighting-functions")) {
            functions += read("water-lighting.glsl");
        }
        if (source.contains("// water-pool-functions")) {
            functions += read("liquid-flow.glsl");
            functions += read("water-interactions.glsl");
            functions += read("water-wave.glsl") + read("water-pool.glsl");
        }
        return source.replace("void main() {", "// terrain-lighting-functions\n" + functions + "\nvoid main() {");
    }

    /** A file's text, read once: a load assembles the same includes into many programs. */
    static String readDisk(String name) {
        return DISK.computeIfAbsent(Configuration.dataDir().getPath() + '\n' + name, key -> resolve(name).readString("UTF-8"));
    }

    /** Files are read from disk again: the shader editor saved or reloaded them, or Reload assets runs. */
    static void forget() { DISK.clear(); }

    private static FileHandle resolve(String name) {
        FileHandle override = new FileHandle(new File(Configuration.dataDir(), "shaders/" + name));
        if (override.exists()) { return override; }
        // Gradle runs in the megamek subproject; IDE launches may run from the repository root.
        for (String root : new String[] { "resources/", "megamek/resources/" }) {
            FileHandle source = Gdx.files.local(root + RESOURCES + name);
            if (source.exists()) { return source; }
        }
        return Gdx.files.classpath(RESOURCES + name);
    }

    record FileSource(String text, Path destination, boolean exists) { }

    static FileSource file(String name) {
        FileHandle source = resolve(name);
        boolean bundled = source.type() == com.badlogic.gdx.Files.FileType.Classpath;
        Path destination = (bundled ? new File(Configuration.dataDir(), "shaders/" + name) : source.file())
              .toPath().toAbsolutePath().normalize();
        return new FileSource(source.readString("UTF-8"), destination, !bundled);
    }

    /** Discover the same assets in a checkout and in a packaged JAR; no second list of shader filenames. */
    static Set<String> files() {
        Set<String> names = new TreeSet<>();
        try {
            var directories = GpuShaderSource.class.getClassLoader().getResources(RESOURCES);
            while (directories.hasMoreElements()) {
                var url = directories.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    var connection = (JarURLConnection) url.openConnection();
                    connection.setUseCaches(false);
                    try (var jar = connection.getJarFile()) {
                        jar.stream().map(entry -> entry.getName()).filter(name -> name.startsWith(RESOURCES))
                              .map(name -> name.substring(RESOURCES.length())).filter(GpuShaderSource::shader)
                              .forEach(names::add);
                    }
                } else if ("file".equals(url.getProtocol())) {
                    addFiles(names, Path.of(url.toURI()));
                }
            }
            for (String root : new String[] { "resources/", "megamek/resources/" }) {
                addFiles(names, Gdx.files.local(root + RESOURCES).file().toPath());
            }
            addFiles(names, new File(Configuration.dataDir(), "shaders").toPath());
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("Cannot list shader sources", failure);
        }
        return names;
    }

    private static void addFiles(Set<String> names, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) { return; }
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                  .filter(GpuShaderSource::shader).forEach(names::add);
        }
    }

    private static boolean shader(String name) {
        return !name.contains("/") && (name.endsWith(".vert") || name.endsWith(".frag") || name.endsWith(".glsl"));
    }

    /** Save explicitly, and refuse to overwrite edits made by another editor since this document was loaded. */
    static FileSource save(FileSource source, String text) throws IOException {
        Path path = source.destination();
        if (Files.exists(path) != source.exists()
              || (source.exists() && !Files.readString(path).equals(source.text()))) {
            throw new IOException("Changed outside this editor: " + path + ". Use Reload file before saving.");
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".shader-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // A saved file may also be a new override that now shadows the bundled one.
            forget();
            Files.deleteIfExists(temporary);
        }
        return new FileSource(text, path, true);
    }
}
