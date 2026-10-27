package club.rainbowkitty.polybackwards.pack;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import eu.pb4.polymer.resourcepack.api.PackResource;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;

import club.rainbowkitty.polybackwards.Polybackwards;

/**
 * Trim atlases for clients on resource formats 84 to 96 (26.1 and 26.2). 26.3 keys trim palettes
 * on {@code minecraft:trim_base} and reads them from {@code textures/palettes/}, which older
 * clients know neither of. The pre-97 overlay carries the pack's items atlas on 26.2's key and
 * paths, and an armor_trims atlas for the modded permutations, since 26.2 draws worn trims from
 * that atlas.
 */
public final class Pre97TrimAtlases {
    private static final String ITEMS_ATLAS = "assets/minecraft/atlases/items.json";
    private static final String ARMOR_TRIMS_ATLAS = "assets/minecraft/atlases/armor_trims.json";
    private static final String PALETTED_PERMUTATIONS = "minecraft:paletted_permutations";
    private static final String NEW_PALETTE_KEY = "minecraft:trim_base";
    private static final String OLD_PALETTE_KEY = "minecraft:trims/color_palettes/trim_palette";
    private static final String NEW_PALETTE_PREFIX = "minecraft:trim/";
    private static final String OLD_PALETTE_PREFIX = "minecraft:trims/color_palettes/";
    // The patterns 26.2's armor_trims atlas permutes, in its order.
    private static final List<String> TRIM_PATTERNS = List.of("sentry", "dune", "coast", "wild",
            "ward", "eye", "vex", "tide", "snout", "rib", "spire", "wayfinder", "shaper", "silence",
            "raiser", "host", "flow", "bolt");

    private Pre97TrimAtlases() {
    }

    /** Has the pre-97 overlay carry both atlases on every later pack build. */
    public static void register() {
        Pre97Overlay.addFile(ITEMS_ATLAS, Pre97TrimAtlases::itemsAtlas);
        Pre97Overlay.addFile(ARMOR_TRIMS_ATLAS, Pre97TrimAtlases::armorTrimsAtlas);
    }

    // The pack's items atlas on 26.2's palette key and paths, or null when nothing changes.
    private static byte @Nullable [] itemsAtlas(ResourcePackBuilder builder) {
        JsonObject downgraded = downgrade(builder, new JsonObject());
        return downgraded == null ? null : downgraded.toString().getBytes(StandardCharsets.UTF_8);
    }

    // An armor_trims atlas adding the modded permutations, or null when there are none.
    private static byte @Nullable [] armorTrimsAtlas(ResourcePackBuilder builder) {
        JsonObject modded = new JsonObject();
        downgrade(builder, modded);
        if (modded.isEmpty()) {
            return null;
        }
        Polybackwards.LOGGER.info("Pre-97 overlay: worn trims for {} modded palettes",
                modded.size());
        return armorTrimAtlas(modded).toString().getBytes(StandardCharsets.UTF_8);
    }

    // The items atlas on 26.2's key and paths, modded permutations into modded; null if the same.
    private static @Nullable JsonObject downgrade(ResourcePackBuilder builder, JsonObject modded) {
        PackResource atlas = builder.getResource(ITEMS_ATLAS);
        if (atlas == null) {
            return null;
        }
        JsonObject root = JsonParser.parseString(atlas.asString()).getAsJsonObject();
        JsonArray sources = root.getAsJsonArray("sources");
        if (sources == null) {
            return null;
        }
        boolean changed = false;
        for (JsonElement element : sources) {
            if (!(element instanceof JsonObject source)) {
                continue;
            }
            JsonElement key = source.get("palette_key");
            if (key instanceof JsonPrimitive primitive
                    && primitive.getAsString().equals(NEW_PALETTE_KEY)) {
                source.addProperty("palette_key", OLD_PALETTE_KEY);
                changed = true;
            }
            JsonObject permutations = source.getAsJsonObject("permutations");
            if (permutations == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : permutations.entrySet()) {
                String texture = oldPaletteTexture(entry.getValue().getAsString());
                if (texture == null) {
                    continue;
                }
                entry.setValue(new JsonPrimitive(texture));
                changed = true;
                if (!texture.startsWith(OLD_PALETTE_PREFIX)) {
                    modded.addProperty(entry.getKey(), texture);
                }
            }
        }
        return changed ? root : null;
    }

    // The 26.2 texture for a 26.3 palette id: vanilla's old path, a modded one's palettes/ file.
    private static @Nullable String oldPaletteTexture(String paletteId) {
        if (paletteId.startsWith(NEW_PALETTE_PREFIX)) {
            return OLD_PALETTE_PREFIX + paletteId.substring(NEW_PALETTE_PREFIX.length());
        }
        Identifier id = Identifier.tryParse(paletteId);
        if (id == null || id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return null;
        }
        return id.withPrefix("palettes/").toString();
    }

    // An armor_trims atlas adding the modded permutations; 26.2 keeps its own for vanilla ones.
    private static JsonObject armorTrimAtlas(JsonObject modded) {
        JsonArray textures = new JsonArray();
        for (String pattern : TRIM_PATTERNS) {
            textures.add("minecraft:trims/entity/humanoid/" + pattern);
            textures.add("minecraft:trims/entity/humanoid_leggings/" + pattern);
        }
        JsonObject source = new JsonObject();
        source.addProperty("type", PALETTED_PERMUTATIONS);
        source.add("textures", textures);
        source.addProperty("palette_key", OLD_PALETTE_KEY);
        source.add("permutations", modded);
        JsonArray sources = new JsonArray();
        sources.add(source);
        JsonObject root = new JsonObject();
        root.add("sources", sources);
        return root;
    }
}
