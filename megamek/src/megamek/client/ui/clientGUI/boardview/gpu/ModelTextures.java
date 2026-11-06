/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;

/** Models borrow textures from their library's cache; each cache alone owns their disposal. */
final class ModelTextures {
    private ModelTextures() { }

    /** An image as one glTF role reads it: its cache key, whether its RGB is sRGB, its pixels and its sampler. */
    private record Role(String key, boolean color, Supplier<Pixmap> pixels, RigidGlb.Image image) { }

    static Model create(ModelData data, Map<String, Texture> cache, BiFunction<String, Boolean, Texture> external) {
        File textures = GpuMaterialMaps.boardTextures();
        Map<String, RigidGlb.Image> images = data instanceof RigidGlb.Data glb ? glb.images : Map.of();
        var usages = new HashMap<String, Integer>();
        List<Map.Entry<String, Integer>> reads = new ArrayList<>();
        for (var material : data.materials) {
            if (material.textures == null) { continue; }
            for (var texture : material.textures) {
                usages.putIfAbsent(texture.fileName, texture.usage);
                reads.add(Map.entry(texture.fileName, texture.usage));
            }
        }
        if (data instanceof RigidGlb.Data glb) {
            glb.surfaces.values().stream().map(RigidGlb.Surface::map).filter(java.util.Objects::nonNull)
                  .forEach(key -> reads.add(Map.entry(key, GpuMaterialMaps.METALLIC_ROUGHNESS_USAGE)));
            glb.surfaces.values().stream().map(RigidGlb.Surface::relief).filter(java.util.Objects::nonNull)
                  .forEach(relief -> reads.add(Map.entry(relief.map(), GpuMaterialMaps.HEIGHT_USAGE)));
        }
        // A glTF role (a ModelTexture usage, or GpuMaterialMaps.METALLIC_ROUGHNESS_USAGE or HEIGHT_USAGE) decides a board
        // material map's layout. Each image's roles resolve once, together on the common pool: a board map's lookup
        // reads its files.
        Map<String, CompletableFuture<Role>> roles = new HashMap<>();
        for (var read : reads) {
            var image = images.get(read.getKey());
            if (image != null) {
                roles.computeIfAbsent(read.getValue() + ":" + read.getKey(),
                      ignored -> GpuAssets.pooled(() -> role(image, read.getKey(), read.getValue(), textures)));
            }
        }
        BiFunction<String, Integer, Role> role = (key, usage) -> {
            var image = images.get(key);
            return image == null ? null : GpuAssets.joined(roles.computeIfAbsent(usage + ":" + key,
                  ignored -> CompletableFuture.completedFuture(role(image, key, usage, textures))));
        };
        // The pixels of the images not cached yet decode together on the common pool. A texture takes its own at its
        // upload; a managed texture's next one (a context restore) decodes them again.
        Map<String, CompletableFuture<Pixmap>> ahead = new HashMap<>();
        BiFunction<String, Integer, Texture> load = (key, usage) -> {
            var read = role.apply(key, usage);
            if (read == null) { return external.apply(key, color(usage)); }
            return cache.computeIfAbsent(read.key(), ignored -> {
                Texture texture = GpuColorTexture.create(() -> {
                    var started = ahead.remove(read.key());
                    return started != null ? GpuAssets.joined(started) : read.pixels().get();
                }, true, read.color());
                texture.setFilter(filter(read.image().minFilter()), filter(read.image().magFilter()));
                texture.setWrap(wrap(read.image().wrapS()), wrap(read.image().wrapT()));
                return texture;
            });
        };
        // Decode first, so an invalid image cannot strand meshes inside a partially constructed Model.
        try {
            for (var read : reads) {
                var image = role.apply(read.getKey(), read.getValue());
                if (image != null && !cache.containsKey(image.key()) && !ahead.containsKey(image.key())) {
                    ahead.put(image.key(), GpuAssets.pooled(image.pixels()));
                }
            }
            reads.forEach(read -> load.apply(read.getKey(), read.getValue()));
        } finally {
            // Pixels a failure left untaken, before or during a texture's construction.
            ahead.values().forEach(pixels -> pixels.thenAccept(Pixmap::dispose));
            ahead.clear();
        }
        Model model = new Model(data, key -> load.apply(key, usages.getOrDefault(key, ModelTexture.USAGE_UNKNOWN)));
        // glTF permits one source image in both a color and a data role. Each role needs its own GPU storage.
        for (var source : data.materials) {
            if (source.textures == null) { continue; }
            var material = model.getMaterial(source.id);
            for (var texture : source.textures) {
                long type = switch (texture.usage) {
                    case ModelTexture.USAGE_DIFFUSE -> TextureAttribute.Diffuse;
                    case ModelTexture.USAGE_EMISSIVE -> TextureAttribute.Emissive;
                    case ModelTexture.USAGE_SPECULAR -> TextureAttribute.Specular;
                    case ModelTexture.USAGE_NORMAL -> TextureAttribute.Normal;
                    case ModelTexture.USAGE_AMBIENT -> TextureAttribute.Ambient;
                    case ModelTexture.USAGE_BUMP -> TextureAttribute.Bump;
                    case ModelTexture.USAGE_REFLECTION -> TextureAttribute.Reflection;
                    default -> 0;
                };
                var attribute = material.get(TextureAttribute.class, type);
                if (attribute != null) { attribute.textureDescription.texture = load.apply(texture.fileName, texture.usage); }
            }
        }
        if (data instanceof RigidGlb.Data glb) {
            model.materials.forEach(material -> material.set(new GpuLinearColors()));
            model.materials.forEach(material -> {
                if (glb.buildingPaint.contains(material.id)) {
                    material.set(new GpuBuildingPaint(com.badlogic.gdx.graphics.Color.WHITE));
                }
                switch (glb.emissions.getOrDefault(material.id, RigidGlb.Emission.ALWAYS)) {
                    case OFF -> material.set(new GpuModelLight(0, 0, 1));
                    case NIGHT -> material.set(new GpuModelLight(1, 0, 1, true));
                    case ALWAYS -> { }
                }
            });
            glb.surfaces.forEach((id, surface) -> {
                var material = model.getMaterial(id);
                var relief = surface.relief();
                material.set(new GpuModelMaterial(surface.roughness(), surface.metallic(), surface.scatter(),
                      surface.map() == null ? null : load.apply(surface.map(), GpuMaterialMaps.METALLIC_ROUGHNESS_USAGE),
                      surface.tint(), surface.glow(), surface.board() != null, relief == null ? new float[4]
                            : new float[] { relief.depthU(), relief.depthV(), relief.minimumLayers(), relief.maximumLayers() },
                      glb.paintMasks.contains(id), glb.boardUVs.getOrDefault(id, 0f)));
                // The heightmap is the material's bump texture (GpuModelMaterial).
                if (relief != null) {
                    material.set(TextureAttribute.createBump(load.apply(relief.map(), GpuMaterialMaps.HEIGHT_USAGE)));
                }
            });
            glb.alphaTests.forEach((id, cutoff) -> model.getMaterial(id).set(
                  new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1),
                  new FloatAttribute(FloatAttribute.AlphaTest, cutoff)));
            // Godot's alpha blending: the colour map's alpha is the opacity; nothing behind is hidden by depth. Its
            // premultiplied mode fades only the diffuse share (model.frag), so glass keeps its whole reflection.
            glb.blends.forEach((id, mode) -> {
                var material = model.getMaterial(id);
                boolean premultiplied = mode == 4;
                material.set(new BlendingAttribute(premultiplied ? GL20.GL_ONE : GL20.GL_SRC_ALPHA,
                            GL20.GL_ONE_MINUS_SRC_ALPHA, 1),
                      new com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute(GL20.GL_LEQUAL, false));
                var surface = material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
                if (premultiplied && surface != null) { material.set(surface.premultiplied()); }
            });
        }
        var owned = model.getManagedDisposables().iterator();
        while (owned.hasNext()) {
            if (owned.next() instanceof Texture) { owned.remove(); }
        }
        return model;
    }

    /** {@code image} (the model's image {@code key}) as role {@code usage} reads it; GL-free. */
    private static Role role(RigidGlb.Image image, String key, int usage, File textures) {
        boolean color = color(usage);
        var map = image.encoded() == null ? GpuMaterialMaps.modelMap(new File(image.file()), usage, textures) : null;
        Supplier<Pixmap> pixels = map != null ? map.pixels()
              : image.encoded() == null ? () -> GpuColorTexture.decode(new FileHandle(image.file()))
              : () -> GpuColorTexture.decode(image.encoded());
        return new Role((color ? "color:" : "data:") + key + (map == null ? "" : map.suffix()), color, pixels, image);
    }

    private static boolean color(int usage) {
        return usage == ModelTexture.USAGE_DIFFUSE || usage == ModelTexture.USAGE_EMISSIVE
              || usage == ModelTexture.USAGE_SPECULAR || usage == ModelTexture.USAGE_REFLECTION;
    }

    private static Texture.TextureFilter filter(int value) {
        for (var filter : Texture.TextureFilter.values()) {
            if (filter.getGLEnum() == value) { return filter; }
        }
        throw new IllegalArgumentException("Unknown texture filter: " + value);
    }

    private static Texture.TextureWrap wrap(int value) {
        for (var wrap : Texture.TextureWrap.values()) {
            if (wrap.getGLEnum() == value) { return wrap; }
        }
        throw new IllegalArgumentException("Unknown texture wrap: " + value);
    }

}
