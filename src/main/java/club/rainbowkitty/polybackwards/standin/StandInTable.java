package club.rainbowkitty.polybackwards.standin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.Release;

// The shipped table of vanilla blocks some older client lacks; scripts/older_client_blocks.py.
final class StandInTable {
    private static final String PATH = "/polybackwards/older_client_blocks.json";

    private final Map<Block, Entry> entries;

    // Wraps the parsed entries.
    private StandInTable(Map<Block, Entry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    // Reads the table from this jar; empty, with a warning, when it is missing.
    static StandInTable load() {
        Map<Block, Entry> entries = new LinkedHashMap<>();
        try (InputStream in = StandInTable.class.getResourceAsStream(PATH)) {
            if (in == null) {
                Polybackwards.LOGGER.warn("Older-client stand-ins: no {} in this jar", PATH);
                return new StandInTable(entries);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject blocks = GsonHelper.getAsJsonObject(
                        JsonParser.parseReader(reader).getAsJsonObject(), "blocks");
                for (Map.Entry<String, JsonElement> block : blocks.entrySet()) {
                    Entry entry = parseEntry(block.getKey(), block.getValue().getAsJsonObject());
                    if (entry != null) {
                        entries.put(entry.block(), entry);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Polybackwards.LOGGER.error("Older-client stand-ins: cannot read {}", PATH, e);
        }
        return new StandInTable(entries);
    }

    // Every entry, in table order.
    Collection<Entry> entries() {
        return this.entries.values();
    }

    // The entry for a block, or null when every client has it.
    @Nullable Entry get(Block block) {
        return this.entries.get(block);
    }

    // One table entry, or null with a warning when this server lacks the block.
    private static @Nullable Entry parseEntry(String id, JsonObject json) {
        Identifier blockId = Identifier.parse(id);
        Block block = BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
        if (block == null) {
            Polybackwards.LOGGER.warn("Older-client stand-ins: no block {} on this server", id);
            return null;
        }
        Block full = optionalBlock(json, "full");
        Block copy = optionalBlock(json, "copy");
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        List<Case> cases = new ArrayList<>();
        boolean multipart = json.has("multipart");
        if (multipart) {
            for (JsonElement part : GsonHelper.getAsJsonArray(json, "multipart")) {
                JsonObject object = part.getAsJsonObject();
                Predicate<BlockState> when = object.has("when")
                        ? condition(definition, GsonHelper.getAsJsonObject(object, "when"))
                        : state -> true;
                cases.add(new Case("", when, models(GsonHelper.getAsJsonArray(object, "apply"))));
            }
        } else {
            for (Map.Entry<String, JsonElement> variant
                    : GsonHelper.getAsJsonObject(json, "variants").entrySet()) {
                cases.add(new Case(variant.getKey(), variantCondition(definition, variant.getKey()),
                        models(variant.getValue().getAsJsonArray())));
            }
        }
        return new Entry(block, Release.valueOf(GsonHelper.getAsString(json, "release")),
                StandInKind.byName(GsonHelper.getAsString(json, "kind")), full, copy,
                List.copyOf(cases), multipart);
    }

    // The block a field names, or null when it is absent or this server lacks the block.
    private static @Nullable Block optionalBlock(JsonObject json, String field) {
        if (!json.has(field)) {
            return null;
        }
        String id = GsonHelper.getAsString(json, field);
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
        if (block == null) {
            Polybackwards.LOGGER.warn("Older-client stand-ins: no block {} on this server", id);
        }
        return block;
    }

    // A variant key such as "facing=east,half=lower" as a state predicate; "" matches every state.
    private static Predicate<BlockState> variantCondition(
            StateDefinition<Block, BlockState> definition, String key) {
        Predicate<BlockState> condition = state -> true;
        if (key.isEmpty()) {
            return condition;
        }
        for (String pair : key.split(",")) {
            String[] nameValue = pair.split("=", 2);
            condition = condition.and(valueIn(definition, nameValue[0], nameValue[1]));
        }
        return condition;
    }

    // A multipart "when": an OR or AND list, else every named property holding one of its values.
    private static Predicate<BlockState> condition(StateDefinition<Block, BlockState> definition,
            JsonObject when) {
        if (when.has("OR") || when.has("AND")) {
            boolean any = when.has("OR");
            List<Predicate<BlockState>> terms = new ArrayList<>();
            for (JsonElement term : GsonHelper.getAsJsonArray(when, any ? "OR" : "AND")) {
                terms.add(condition(definition, term.getAsJsonObject()));
            }
            return any ? state -> terms.stream().anyMatch(term -> term.test(state))
                    : state -> terms.stream().allMatch(term -> term.test(state));
        }
        Predicate<BlockState> condition = state -> true;
        for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
            condition = condition.and(
                    valueIn(definition, entry.getKey(), entry.getValue().getAsString()));
        }
        return condition;
    }

    // A property holding one of "a|b", or none of them after a leading "!".
    private static Predicate<BlockState> valueIn(StateDefinition<Block, BlockState> definition,
            String name, String values) {
        Property<?> property = definition.getProperty(name);
        if (property == null) {
            Polybackwards.LOGGER.warn("Older-client stand-ins: {} has no property {}",
                    definition.getOwner(), name);
            return state -> false;
        }
        boolean negated = values.startsWith("!");
        Set<String> names = Set.of((negated ? values.substring(1) : values).split("\\|"));
        return state -> names.contains(valueName(state, property)) != negated;
    }

    // A model list as Polymer models; the table leaves out fields at their default.
    private static PolymerBlockModel[] models(JsonArray array) {
        PolymerBlockModel[] models = new PolymerBlockModel[array.size()];
        for (int i = 0; i < models.length; i++) {
            JsonObject model = array.get(i).getAsJsonObject();
            Identifier id = Identifier.parse(GsonHelper.getAsString(model, "model"));
            models[i] = PolymerBlockModel.of(id, GsonHelper.getAsInt(model, "x", 0),
                    GsonHelper.getAsInt(model, "y", 0),
                    GsonHelper.getAsBoolean(model, "uvlock", false),
                    GsonHelper.getAsInt(model, "weight", 1));
        }
        return models;
    }

    // A state's value of a property, by the name blockstate files use.
    static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * One block of the table.
     *
     * @param block the block
     * @param release the release that added it
     * @param kind how it gets its stand-in
     * @param full for a slab, the full block its double variant draws; else null
     * @param copy for a copy, the vanilla block older clients get instead; else null
     * @param cases its variants, or its multipart parts
     * @param multipart whether cases are multipart parts, all matching ones drawn together
     */
    record Entry(Block block, Release release, StandInKind kind, @Nullable Block full,
            @Nullable Block copy, List<Case> cases, boolean multipart) {

        // The key of the variant a state draws, or for a multipart block the indices of the parts
        // it draws, such as "#0+4"; null for no match.
        @Nullable String variant(BlockState state) {
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < this.cases.size(); i++) {
                if (!this.cases.get(i).when().test(state)) {
                    continue;
                }
                if (!this.multipart) {
                    return this.cases.get(i).key();
                }
                parts.append(parts.isEmpty() ? "#" : "+").append(i);
            }
            return parts.isEmpty() ? null : parts.toString();
        }

        // What a state draws: one array of weighted alternatives per variant or applying part.
        List<PolymerBlockModel[]> models(BlockState state) {
            List<PolymerBlockModel[]> models = new ArrayList<>();
            for (Case part : this.cases) {
                if (part.when().test(state)) {
                    models.add(part.models());
                    if (!this.multipart) {
                        break;
                    }
                }
            }
            return models;
        }
    }

    /**
     * A variant or a multipart part.
     *
     * @param key the variant key, or "" for a multipart part
     * @param when the states it applies to
     * @param models its weighted alternatives
     */
    record Case(String key, Predicate<BlockState> when, PolymerBlockModel[] models) {
    }
}
