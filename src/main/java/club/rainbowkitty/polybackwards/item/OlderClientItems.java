package club.rainbowkitty.polybackwards.item;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.pack.Pre97Overlay;

/**
 * Shows a client that predates a vanilla item that item's own icon. Its stacks still reach the
 * client as the item ViaBackwards turns them into, but with an {@code item_model} of
 * {@code polybackwards:<namespace>/<path>}, a copy of the real item's definition in the pre-97
 * overlay. A client without the server pack keeps ViaBackwards' look-alike.
 *
 * <p>The substitute is set here rather than left to ViaBackwards, since a stack cannot carry its
 * own item's default model as a component, and the model is a copy because ViaBackwards remaps an
 * {@code item_model} naming any item it knows. Polymer keeps the real stack on the client's copy,
 * so a creative client hands back the real item. {@link ClientPlacement} swaps a substitute that
 * would place the wrong block for a pinned stand-in or an item that places nothing.
 */
public final class OlderClientItems {
    // The shipped table, from scripts/older_client_items.py.
    private static final String PATH = "/polybackwards/older_client_items.json";
    // The real item's own values, which a substitute would otherwise show its defaults for.
    private static final DataComponentType<?>[] KEPT = {
        DataComponents.ITEM_NAME, DataComponents.MAX_STACK_SIZE, DataComponents.RARITY
    };

    // Per new item, what each older release is sent instead; set once by register().
    private static Map<Item, Map<Release, Item>> substitutes = Map.of();
    // Per new item's model, the overlay's copy of it; set once by register().
    private static Map<Identifier, Identifier> modelCopies = Map.of();

    private OlderClientItems() {}

    /** Reads the table, puts a copy of each item's definition in the overlay, hooks Polymer. */
    public static void register() {
        substitutes = load();
        Map<Identifier, Identifier> copies = new HashMap<>();
        for (Item item : substitutes.keySet()) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (Pre97Overlay.requireVanillaCopy(id, modelCopy(id))) {
                copies.put(id, modelCopy(id));
            }
        }
        modelCopies = Map.copyOf(copies);
        // Routes a stack Polymer would send untouched through its item conversion, and so ours.
        PolymerItemUtils.CONTEXT_ITEM_CHECK.register((stack, context) ->
                substitute(stack.typeHolder().value(), context) != null);
        PolymerItemUtils.ITEM_MODIFICATION_EVENT.register(OlderClientItems::modify);
        Polybackwards.LOGGER.info("Older-client items: {} items, {} item definitions for the "
                + "pre-97 overlay", substitutes.size(), modelCopies.size());
    }

    /** The overlay's copy of a vanilla item's definition, as an {@code item_model} id. */
    public static Identifier modelCopy(Identifier itemId) {
        return Polybackwards.id(itemId.getNamespace() + "/" + itemId.getPath());
    }

    /** The item a client on {@code release} is sent for {@code item}; null for the item itself. */
    public static @Nullable Item substituteFor(Release release, Item item) {
        Map<Release, Item> byRelease = substitutes.get(item);
        return byRelease == null ? null : byRelease.get(release);
    }

    /**
     * What a client in {@code context} gets for {@code item}: null when the client has the item
     * or lacks the server pack.
     */
    public static ClientPlacement.@Nullable Choice choiceFor(Item item,
            @Nullable PacketContext context) {
        Item substitute = substitute(item, context);
        return substitute == null ? null
                : ClientPlacement.choose(item, substitute, ClientVersions.of(context));
    }

    /** Ids of every item in the table. */
    public static Iterable<Identifier> items() {
        return substitutes.keySet().stream().map(BuiltInRegistries.ITEM::getKey)::iterator;
    }

    // The substitute for a client with the server pack that lacks the item; else null.
    private static @Nullable Item substitute(Item item, @Nullable PacketContext context) {
        if (context == null || !substitutes.containsKey(item)) {
            return null;
        }
        Item substitute = substituteFor(ClientVersions.of(context), item);
        return substitute != null && PolymerResourcePackUtils.hasMainPack(context)
                ? substitute : null;
    }

    // Swaps the client stack's item, Polymer's or a mod's included, when the client lacks it.
    private static ItemStack modify(ItemStack original, ItemStack client, PacketContext context) {
        ClientPlacement.Choice choice = choiceFor(client.getItem(), context);
        if (choice == null) {
            return client;
        }
        ItemStack out = client.transmuteCopy(choice.item());
        if (choice.pin() != null) {
            out.set(DataComponents.BLOCK_STATE, choice.pin());
        }
        for (DataComponentType<?> type : KEPT) {
            keep(client, out, type);
        }
        Identifier model = client.get(DataComponents.ITEM_MODEL);
        out.set(DataComponents.ITEM_MODEL,
                model == null ? null : modelCopies.getOrDefault(model, model));
        return out;
    }

    // Sets the type's value from one stack on another, the item's default included.
    private static <T> void keep(ItemStack from, ItemStack to, DataComponentType<T> type) {
        to.set(type, from.get(type));
    }

    // Reads the table from this jar, skipping items this server lacks; empty when it is missing.
    private static Map<Item, Map<Release, Item>> load() {
        Map<Item, Map<Release, Item>> table = new IdentityHashMap<>();
        try (InputStream in = OlderClientItems.class.getResourceAsStream(PATH)) {
            if (in == null) {
                Polybackwards.LOGGER.warn("Older-client items: no {} in this jar", PATH);
                return table;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject items = GsonHelper.getAsJsonObject(
                        JsonParser.parseReader(reader).getAsJsonObject(), "items");
                for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                    Item item = item(entry.getKey());
                    Map<Release, Item> byRelease = substitutes(entry.getValue().getAsJsonObject());
                    if (item != null && !byRelease.isEmpty()) {
                        table.put(item, byRelease);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Polybackwards.LOGGER.error("Older-client items: cannot read {}", PATH, e);
        }
        return table;
    }

    // One entry's substitute per older release.
    private static Map<Release, Item> substitutes(JsonObject entry) {
        Map<Release, Item> byRelease = new EnumMap<>(Release.class);
        for (Release release : Release.values()) {
            if (entry.has(release.name())) {
                Item substitute = item(GsonHelper.getAsString(entry, release.name()));
                if (substitute != null) {
                    byRelease.put(release, substitute);
                }
            }
        }
        return byRelease;
    }

    // The item of that id, or null with a warning when this server lacks it.
    private static @Nullable Item item(String id) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        if (item == null) {
            Polybackwards.LOGGER.warn("Older-client items: no item {} on this server", id);
        }
        return item;
    }
}
