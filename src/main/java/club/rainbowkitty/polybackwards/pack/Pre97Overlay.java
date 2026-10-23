package club.rainbowkitty.polybackwards.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.api.PackResource;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.api.metadata.PackMcMeta;
import net.fabricmc.fabric.api.event.Event;
import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.mixin.DefaultRPBuilderAccessor;

/**
 * Pack overlay for clients on resource formats 84 to 96 (26.1 and 26.2) when the pack is built from
 * 26.3 assets. Ships each vanilla model, item definition and texture new in 26.3 that the pack
 * reaches: one a served blockstate, model or item definition names, then what each copy names in
 * turn, such as the bed templates under Blockus beds or poplar leaves on Blockus hedges. Newer
 * clients keep reading the base pack unchanged.
 */
public final class Pre97Overlay {
    private static final String DIRECTORY = "polybackwards_pre97";
    private static final PackFormat FIRST_FORMAT = new PackFormat(84, 0);
    private static final PackFormat LAST_FORMAT = new PackFormat(96, Integer.MAX_VALUE);
    private static final String VANILLA_ASSETS = "assets/minecraft/";
    private static final String ASSET_LIST = "/polybackwards/pre97_assets.txt";
    // Vanilla assets some 26.1.x or 26.2 client lacks, as pack paths.
    private static final Set<String> NEW_ASSETS = readAssetList();
    // Item definitions the overlay carries whether or not the pack names them.
    private static final Set<String> REQUIRED_ITEMS = ConcurrentHashMap.newKeySet();
    // Vanilla item definitions the overlay carries under another id, by that id's path.
    private static final Map<String, String> COPIES = new ConcurrentHashMap<>();
    // Files of the overlay's own, by pack path, each built from the pack being built.
    private static final Map<String, Function<ResourcePackBuilder, byte @Nullable []>> FILES =
            new ConcurrentHashMap<>();

    private final ResourcePackBuilder builder;
    private boolean wroteFiles;

    private Pre97Overlay(ResourcePackBuilder builder) {
        this.builder = builder;
    }

    /** Attaches the overlay to every main-pack build, after default-phase tasks add models. */
    public static void register() {
        Event<Consumer<ResourcePackBuilder>> event =
                PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT;
        // A later phase queues our pre-finish task behind those registered on the default phase.
        Identifier phase = Polybackwards.id("after_default_phase");
        event.addPhaseOrdering(Event.DEFAULT_PHASE, phase);
        event.register(phase, builder -> new Pre97Overlay(builder).attach());
    }

    /**
     * Makes every later pack build ship the vanilla item definition {@code itemId} to older
     * clients, with what it draws, even when nothing in the pack names it.
     *
     * @return whether older clients lack that item definition, so the overlay carries it
     */
    public static boolean requireVanilla(Identifier itemId) {
        String path = AssetPaths.itemAsset(itemId);
        if (!NEW_ASSETS.contains(path)) {
            return false;
        }
        REQUIRED_ITEMS.add(path);
        return true;
    }

    /**
     * Makes every later pack build ship the vanilla item definition {@code itemId} to older
     * clients as item definition {@code copyId} instead, with what it draws. For a stack whose
     * {@code item_model} must not name an item, since ViaBackwards remaps any it knows.
     *
     * @return whether older clients lack that item definition, so the overlay carries the copy
     */
    public static boolean requireVanillaCopy(Identifier itemId, Identifier copyId) {
        String path = AssetPaths.itemAsset(itemId);
        if (!NEW_ASSETS.contains(path)) {
            return false;
        }
        COPIES.put(AssetPaths.itemAsset(copyId), path);
        return true;
    }

    /**
     * Makes every later pack build ship a file only older clients read, at pack path
     * {@code path}, such as a model of what newer clients draw in code.
     *
     * @param data builds the file from the pack being built; null leaves it out
     */
    public static void addFile(String path,
            Function<ResourcePackBuilder, byte @Nullable []> data) {
        FILES.put(path, data);
    }

    // Queues the overlay's files and the pack.mcmeta entry that points clients at them.
    private void attach() {
        // Pre-finish tasks run after the build event, where Polymer writes stand-in blockstates.
        this.builder.addPreFinishTask(ignored -> this.writeOverlay());
        // pack.mcmeta is serialised before pre-finish tasks run, so the overlay entry goes in here.
        this.builder.addResourceConverter(this::convertMetadata);
    }

    // Copies each new vanilla asset the pack reaches from the client jar, .mcmeta included.
    private void writeOverlay() {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> reached = new HashSet<>();
        Consumer<String> reach = path -> {
            if (NEW_ASSETS.contains(path) && reached.add(path)) {
                pending.add(path);
            }
        };
        this.builder.forEachResource((path, resource) ->
                collectReferences(path, () -> this.servedJson(path, resource), reach));
        REQUIRED_ITEMS.forEach(reach);
        int models = 0;
        int items = 0;
        int textures = 0;
        for (Map.Entry<String, String> copy : COPIES.entrySet()) {
            if (this.builder.getResource(copy.getKey()) != null) {
                continue;
            }
            byte[] data = this.builder.getDataOrSource(copy.getValue());
            if (data == null) {
                Polybackwards.LOGGER.warn("Pre-97 overlay: no {} in the client jar",
                        copy.getValue());
                continue;
            }
            this.builder.addData(DIRECTORY + "/" + copy.getKey(), data);
            items++;
            collectReferences(copy.getValue(), () -> parse(data), reach);
        }
        int own = 0;
        for (Map.Entry<String, Function<ResourcePackBuilder, byte @Nullable []>> file
                : FILES.entrySet()) {
            byte[] data = file.getValue().apply(this.builder);
            if (data != null) {
                this.builder.addData(DIRECTORY + "/" + file.getKey(), data);
                own++;
            }
        }
        while (!pending.isEmpty()) {
            String path = pending.poll();
            // A pack that ships the file itself serves it to older clients as well.
            if (this.builder.getResource(path) != null) {
                continue;
            }
            byte[] data = this.builder.getDataOrSource(path);
            if (data == null) {
                Polybackwards.LOGGER.warn("Pre-97 overlay: no {} in the client jar", path);
                continue;
            }
            this.builder.addData(DIRECTORY + "/" + path, data);
            if (path.endsWith(".png")) {
                textures++;
                byte[] meta = this.builder.getDataOrSource(path + ".mcmeta");
                if (meta != null) {
                    this.builder.addData(DIRECTORY + "/" + path + ".mcmeta", meta);
                }
                continue;
            }
            if (path.startsWith(VANILLA_ASSETS + "items/")) {
                items++;
            } else {
                models++;
            }
            collectReferences(path, () -> parse(data), reach);
        }
        this.wroteFiles = models + items + textures + own > 0;
        if (this.wroteFiles) {
            Polybackwards.LOGGER.info("Pre-97 overlay: {} models, {} item definitions and {} "
                    + "textures new in 26.3 for older clients, and {} files of its own", models,
                    items, textures, own);
        }
    }

    // The file's JSON as the pack serves it, once converters have rewritten or dropped it.
    private JsonElement servedJson(String path, PackResource resource) {
        PackResource served = resource;
        if (this.builder instanceof DefaultRPBuilderAccessor converters) {
            try {
                served = converters.polybackwards$convertResource(path, resource);
            } catch (RuntimeException e) {
                // The pack's own write hits the same failure; the file as added is the best guess.
                served = resource;
            }
        }
        return served == null ? null : served.asJson();
    }

    // Passes reach the pack path of each model, texture or item definition the file names.
    private static void collectReferences(String path, Supplier<JsonElement> json,
            Consumer<String> reach) {
        String[] parts = path.split("/", 4);
        if (parts.length < 4 || !parts[0].equals("assets") || !path.endsWith(".json")) {
            return;
        }
        switch (parts[2]) {
            case "blockstates" -> {
                if (json.get() instanceof JsonObject blockState) {
                    collectBlockStateModels(blockState, reach);
                }
            }
            case "models" -> {
                if (json.get() instanceof JsonObject model) {
                    collectModelReferences(model, reach);
                }
            }
            case "items" -> collectItemModels(json.get(), reach);
            default -> { }
        }
    }

    // Reaches the model of every variant and multipart case.
    private static void collectBlockStateModels(JsonObject blockState, Consumer<String> reach) {
        if (blockState.get("variants") instanceof JsonObject variants) {
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                collectVariantModels(entry.getValue(), reach);
            }
        }
        if (blockState.get("multipart") instanceof JsonArray cases) {
            for (JsonElement entry : cases) {
                if (entry instanceof JsonObject part) {
                    collectVariantModels(part.get("apply"), reach);
                }
            }
        }
    }

    // A variant is one model object or a weighted list of them.
    private static void collectVariantModels(JsonElement variant, Consumer<String> reach) {
        if (variant instanceof JsonArray choices) {
            for (JsonElement choice : choices) {
                collectVariantModels(choice, reach);
            }
        } else if (variant instanceof JsonObject model) {
            reachModel(model.get("model"), reach);
        }
    }

    // Reaches the model's parent and textures, an object's texture by its sprite.
    private static void collectModelReferences(JsonObject model, Consumer<String> reach) {
        reachModel(model.get("parent"), reach);
        if (!(model.get("textures") instanceof JsonObject textures)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            JsonElement value = entry.getValue();
            if (value instanceof JsonObject sprite) {
                value = sprite.get("sprite");
            }
            Identifier texture = assetId(value);
            if (texture != null) {
                reach.accept(AssetPaths.texture(texture) + ".png");
            }
        }
    }

    // Item models nest; a string "model", or a special model's "base", names a model.
    private static void collectItemModels(JsonElement element, Consumer<String> reach) {
        if (element instanceof JsonArray list) {
            for (JsonElement entry : list) {
                collectItemModels(entry, reach);
            }
        } else if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String key = entry.getKey();
                if (entry.getValue() instanceof JsonPrimitive value
                        && (key.equals("model") || key.equals("base"))) {
                    reachModel(value, reach);
                } else {
                    collectItemModels(entry.getValue(), reach);
                }
            }
        }
    }

    // Reaches the model a JSON string names, if it names one.
    private static void reachModel(JsonElement value, Consumer<String> reach) {
        Identifier model = assetId(value);
        if (model != null) {
            reach.accept(AssetPaths.model(model) + ".json");
        }
    }

    // The asset id a JSON string holds, or null for a missing value or a "#" texture reference.
    private static Identifier assetId(JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()
                || primitive.getAsString().startsWith("#")) {
            return null;
        }
        return Identifier.tryParse(primitive.getAsString());
    }

    // The file's JSON, or null when it does not parse.
    private static JsonElement parse(byte[] data) {
        try {
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The listed assets as pack paths under assets/minecraft/.
    private static Set<String> readAssetList() {
        try (InputStream in = Pre97Overlay.class.getResourceAsStream(ASSET_LIST)) {
            if (in == null) {
                Polybackwards.LOGGER.warn("Pre-97 overlay: no {} in this jar", ASSET_LIST);
                return Set.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> VANILLA_ASSETS + line)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            Polybackwards.LOGGER.warn("Pre-97 overlay: could not read {}", ASSET_LIST, e);
            return Set.of();
        }
    }

    // Widens pack.mcmeta down to format 84 and adds the overlay entry, once there are files.
    private PackResource convertMetadata(String path, PackResource resource) {
        if (!this.wroteFiles || !path.equals(AssetPaths.PACK_METADATA)) {
            return resource;
        }
        PackMcMeta.Builder meta = this.builder.getPackMcMetaBuilder();
        PackMetadataSection pack = meta.metadata();
        PackFormat min = pack.supportedFormats().minInclusive();
        // Claims 84 as well, so older clients list the pack as compatible.
        meta.metadata(new PackMetadataSection(pack.description(), new InclusiveRange<>(
                min.compareTo(FIRST_FORMAT) > 0 ? FIRST_FORMAT : min,
                pack.supportedFormats().maxInclusive())));
        meta.addOverlay(new InclusiveRange<>(FIRST_FORMAT, LAST_FORMAT), DIRECTORY);
        return PackResource.fromString(meta.build().asString());
    }
}
