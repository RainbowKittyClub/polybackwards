package club.rainbowkitty.polybackwards.display;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * Gives every vanilla block with display-fallback states a {@link FallbackBlockElementHolder} at
 * each placed state that has one, so older clients see the real model over its stand-in.
 */
public final class DisplayFallbackOverlay implements BlockWithElementHolder {
    private static final DisplayFallbackOverlay INSTANCE = new DisplayFallbackOverlay();

    private DisplayFallbackOverlay() {}

    /** Registers the overlay on each such block, and the models it draws; after the claims. */
    public static void register() {
        Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockState state : OlderClientStandIns.displayStandIns().keySet()) {
            blocks.add(state.getBlock());
        }
        int registered = 0;
        for (Block block : blocks) {
            // A block takes one overlay; another mod's stays, and older clients see the stand-in.
            if (BlockWithElementHolder.get(block.defaultBlockState()) != null) {
                Polybackwards.LOGGER.warn("Display fallback: {} already has an element holder",
                        block);
            } else if (BlockWithElementHolder.registerOverlay(block, INSTANCE)) {
                registered++;
            }
        }
        DisplayFallbackModels.register();
        Polybackwards.LOGGER.info("Display fallback: overlays on {} of {} blocks", registered,
                blocks.size());
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos,
            BlockState initialBlockState) {
        Release introduced = OlderClientStandIns.introducedIn(initialBlockState.getBlock());
        if (introduced == null || OlderClientStandIns.displayStandIn(initialBlockState) == null) {
            return null;
        }
        return new FallbackBlockElementHolder(introduced);
    }
}
