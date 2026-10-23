package club.rainbowkitty.polybackwards.entity;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.AssetPaths;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.pack.Pre97Overlay;

/**
 * The cushion as an item model, for older clients' displays: {@code CushionModel}'s one box with
 * each face's UVs as {@code CushionRenderer} draws a cushion facing north, sampling a copy of the
 * colour's entity texture in the block atlas, since item models cannot reach entity textures.
 * Model, texture copy and item definition ship only in the pre-97 overlay.
 */
final class CushionModels {
    private static final Identifier TEMPLATE = Polybackwards.id("cushion/template");
    // UVs in sixteenths of the 64 px texture: top and bottom squares, then the four 4 px sides.
    private static final String[][] FACES = {
        {"up", "4,0,8,4"},
        {"down", "8,4,12,0"},
        {"north", "12,4,16,5"},
        {"south", "4,4,8,5"},
        {"west", "0,4,4,5"},
        {"east", "8,4,12,5"}
    };
    // CushionModel's -0.005 px deflation, so the bottom does not fight the floor.
    private static final double INSET = 0.005;

    private CushionModels() {}

    /** Queues the template, and per colour its texture copy, model and item definition. */
    static void register() {
        Pre97Overlay.addFile(AssetPaths.model(TEMPLATE) + ".json", builder -> template());
        for (DyeColor color : DyeColor.values()) {
            Identifier model = itemModel(color);
            String vanilla = AssetPaths.texture(Identifier.withDefaultNamespace(
                    "entity/cushion/" + color.getName() + "_cushion")) + ".png";
            Pre97Overlay.addFile(AssetPaths.blockTexture(model), builder -> {
                byte[] data = builder.getDataOrSource(vanilla);
                if (data == null) {
                    Polybackwards.LOGGER.warn("Older-client cushions: no {} in the client jar",
                            vanilla);
                }
                return data;
            });
            Pre97Overlay.addFile(AssetPaths.model(model) + ".json",
                    builder -> bytes(colorModel(model)));
            Pre97Overlay.addFile(AssetPaths.itemAsset(model), builder -> bytes(item(model)));
        }
    }

    /** The item model, and its model's id, for a cushion of one colour. */
    static Identifier itemModel(DyeColor color) {
        return Polybackwards.id("cushion/" + color.getName());
    }

    /** A stack drawing the cushion of one colour, for an item display. */
    static ItemStack stack(DyeColor color) {
        ItemStack stack = new ItemStack(Items.TRIAL_KEY);
        stack.set(DataComponents.ITEM_MODEL, itemModel(color));
        return stack;
    }

    // The box, 16 x 4 x 16 px from the model's floor, with the colour's texture left open.
    private static byte[] template() {
        JsonObject element = new JsonObject();
        element.add("from", array(INSET, INSET, INSET));
        element.add("to", array(16 - INSET, 4 - INSET, 16 - INSET));
        JsonObject faces = new JsonObject();
        for (String[] face : FACES) {
            JsonObject json = new JsonObject();
            JsonArray uv = new JsonArray();
            for (String value : face[1].split(",")) {
                uv.add(Integer.parseInt(value));
            }
            json.add("uv", uv);
            json.addProperty("texture", "#cushion");
            faces.add(face[0], json);
        }
        element.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(element);
        JsonObject textures = new JsonObject();
        textures.addProperty("particle", "#cushion");
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return bytes(model);
    }

    // The template with one colour's texture copy.
    private static JsonObject colorModel(Identifier model) {
        JsonObject textures = new JsonObject();
        textures.addProperty("cushion", model.withPrefix("block/").toString());
        JsonObject json = new JsonObject();
        json.addProperty("parent", TEMPLATE.toString());
        json.add("textures", textures);
        return json;
    }

    // An item definition drawing the model.
    private static JsonObject item(Identifier model) {
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "minecraft:model");
        inner.addProperty("model", model.toString());
        JsonObject json = new JsonObject();
        json.add("model", inner);
        return json;
    }

    private static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
