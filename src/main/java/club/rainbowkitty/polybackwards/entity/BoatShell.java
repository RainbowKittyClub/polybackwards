package club.rainbowkitty.polybackwards.entity;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.AssetPaths;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.pack.Pre97Overlay;

/**
 * The poplar boat's and chest boat's shells for older clients, as ppb dresses modded boats:
 * {@code BoatModel}'s hull as an item model over the pale oak stand-in, sampling a block-atlas
 * copy of the 26.3 entity texture. Paddles and a chest boat's chest stay the stand-in's. One
 * variant per carrier scale; every file ships only in the pre-97 overlay.
 */
enum BoatShell {
    BOAT("boat", false),
    CHEST_BOAT("chest_boat", true);

    // Carrier scales: far, its box (0.8 x scale) outdraws the boat's (1.104) by more than the
    // 0.6 seat offset, so it stops drawing no sooner than the boat; near, its box stays small.
    static final float FAR_SCALE = 1.41f;
    static final float NEAR_SCALE = 0.5f;
    // The carrier rides behind another passenger, so it takes the back seat.
    private static final double SEAT = -0.6;
    // Boat.rideHeight and ChestBoat.rideHeight: a third of the 0.5625 height.
    private static final double RIDE_HEIGHT = 0.5625f / 3.0f;

    private final String folder;
    private final boolean chest;

    BoatShell(String folder, boolean chest) {
        this.folder = folder;
        this.chest = chest;
    }

    // Queues each shell's texture copy, hull, scale variants and item definitions.
    static void register() {
        for (BoatShell shell : values()) {
            shell.addFiles();
        }
    }

    // The stack the carrier holds at that scale; its item never shows, only the item model.
    ItemStack stack(boolean near) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.ITEM_MODEL, this.variant(near));
        return stack;
    }

    // This shell's files, all built from the pack being built.
    private void addFiles() {
        Identifier hull = this.hull();
        String vanilla = AssetPaths.texture(Identifier.withDefaultNamespace(
                "entity/" + this.folder + "/poplar")) + ".png";
        Pre97Overlay.addFile(AssetPaths.blockTexture(hull), builder -> {
            byte[] data = builder.getDataOrSource(vanilla);
            if (data == null) {
                Polybackwards.LOGGER.warn("Older-client boat shells: no {} in the client jar",
                        vanilla);
            }
            return data;
        });
        Pre97Overlay.addFile(AssetPaths.model(hull) + ".json", builder -> bytes(this.hullModel()));
        for (boolean near : new boolean[] {false, true}) {
            Identifier variant = this.variant(near);
            JsonObject model = new JsonObject();
            model.addProperty("parent", hull.toString());
            JsonObject display = new JsonObject();
            display.add("ground", groundTransform(near ? NEAR_SCALE : FAR_SCALE));
            model.add("display", display);
            Pre97Overlay.addFile(AssetPaths.model(variant) + ".json", builder -> bytes(model));
            JsonObject reference = new JsonObject();
            reference.addProperty("type", "minecraft:model");
            reference.addProperty("model", variant.toString());
            JsonObject definition = new JsonObject();
            definition.add("model", reference);
            Pre97Overlay.addFile(AssetPaths.itemAsset(variant), builder -> bytes(definition));
        }
    }

    // The hull's elements over the texture copy.
    private JsonObject hullModel() {
        String texture = this.hull().withPrefix("block/").toString();
        JsonObject textures = new JsonObject();
        textures.addProperty("hull", texture);
        textures.addProperty("particle", texture);
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", BoatShellGeometry.elements(this.chest));
        return model;
    }

    // polybackwards:boat_shell/poplar_<kind>: the hull model, and its texture under block/.
    private Identifier hull() {
        return Polybackwards.id("boat_shell/poplar_" + this.folder);
    }

    // The hull at one carrier scale, as model and item definition.
    private Identifier variant(boolean near) {
        return Polybackwards.id("boat_shell/poplar_" + this.folder + (near ? "_near" : "_far"));
    }

    // Places the hull, baked as (z + 8, y + 8, 8 - x) of BoatModel space, over the boat: undoes
    // the carrier's scale, seat height and seat, and DolphinCarryingItemLayer's (0, 1, -1).
    private static JsonObject groundTransform(float carrierScale) {
        double y = 0.501 - (0.375 - RIDE_HEIGHT) / carrierScale;
        double z = 1 + SEAT / carrierScale;
        JsonObject ground = new JsonObject();
        ground.add("translation", BoatShellGeometry.vector(0, y * 16, z * 16));
        ground.add("scale", BoatShellGeometry.vector(1 / carrierScale, 1 / carrierScale,
                1 / carrierScale));
        return ground;
    }

    // The JSON as UTF-8 file contents.
    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
