package club.rainbowkitty.polybackwards.display;

import java.util.ArrayList;
import java.util.List;

import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * What an older client's displays draw for a display-fallback state: one item display per model
 * the state's blockstate draws, showing that model's grown copy from {@link DisplayFallbackModels}.
 */
public final class DisplayFallbackVisual {
    // View range multipliers; a thin block's display matters less far off, so it fades sooner.
    private static final float VIEW_RANGE = 3f;
    private static final float THIN_VIEW_RANGE = 1.1f;
    // Neighbours' copies overlap by their growth, so each display shifts a little apart.
    private static final double NUDGE = 1 / 5000.0;

    private DisplayFallbackVisual() {}

    /**
     * The displays a state at a position draws, none for a state off the display fallback.
     *
     * @return parts in blockstate order, offsets from the block's centre
     */
    public static List<Part> parts(BlockState state, BlockPos pos) {
        if (OlderClientStandIns.displayStandIn(state) == null) {
            return List.of();
        }
        List<PolymerBlockModel[]> models = OlderClientStandIns.models(state);
        RandomSource random = RandomSource.create(state.getSeed(pos));
        // A client draws an offset block, such as a sulfur spike, shifted by the same amount.
        Vec3 offset = state.getOffset(pos);
        boolean thin = state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof PressurePlateBlock;
        List<Part> parts = new ArrayList<>(models.size());
        for (int i = 0; i < models.size(); i++) {
            PolymerBlockModel model = pick(models.get(i), random);
            ItemStack stack = new ItemStack(Items.TRIAL_KEY);
            stack.set(DataComponents.ITEM_MODEL, DisplayFallbackModels.itemModel(model));
            Quaternionf rotation = new Quaternionf();
            if (!DisplayFallbackModels.bakesTurn(model)) {
                rotation.rotateY(-model.y() * Mth.DEG_TO_RAD).rotateX(model.x() * Mth.DEG_TO_RAD);
            }
            int step = Math.floorMod(i + pos.getX() + pos.getY() + pos.getZ(), 5);
            double nudge = (step - 2.5) * NUDGE;
            parts.add(new Part(stack, rotation, offset.add(nudge, nudge, nudge),
                    thin ? THIN_VIEW_RANGE : VIEW_RANGE));
        }
        return parts;
    }

    // One of a variant's weighted alternatives.
    private static PolymerBlockModel pick(PolymerBlockModel[] choices, RandomSource random) {
        if (choices.length == 1) {
            return choices[0];
        }
        int total = 0;
        for (PolymerBlockModel choice : choices) {
            total += choice.weight();
        }
        int roll = random.nextInt(Math.max(total, 1));
        for (PolymerBlockModel choice : choices) {
            roll -= choice.weight();
            if (roll < 0) {
                return choice;
            }
        }
        return choices[0];
    }

    /**
     * One display.
     *
     * @param stack the item it shows, whose item model is the grown copy
     * @param rotation its left rotation: the blockstate's turn, unless the copy bakes it
     * @param offset from the block's centre
     * @param viewRange its view range multiplier
     */
    public record Part(ItemStack stack, Quaternionfc rotation, Vec3 offset, float viewRange) {
    }
}
