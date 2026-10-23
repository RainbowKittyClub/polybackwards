package club.rainbowkitty.polybackwards.standin;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.BlockResourceCreator;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import club.rainbowkitty.polybackwards.Polybackwards;

// Same-shape vanilla stand-ins for the states no pool holds; a display draws the real model over.
final class DisplayStandIns {
    // Per shape, a wooden candidate then a stone one; a state takes the one that sounds like it.
    private static final List<Block> STAIRS = List.of(Blocks.BIRCH_STAIRS, Blocks.SANDSTONE_STAIRS);
    private static final List<Block> FENCES =
            List.of(Blocks.BIRCH_FENCE, Blocks.NETHER_BRICK_FENCE);
    private static final List<Block> BUTTONS = List.of(Blocks.BIRCH_BUTTON, Blocks.STONE_BUTTON);
    private static final List<Block> PLATES =
            List.of(Blocks.BIRCH_PRESSURE_PLATE, Blocks.STONE_PRESSURE_PLATE);
    // Snow at 2 layers draws 4 px, the straw bed's base, so none shows above it; it collides at 2.
    private static final int BED_SNOW_LAYERS = 2;

    // Static only.
    private DisplayStandIns() {}

    // Every state of a fallback block that no pool held, mapped to its same-shape stand-in; the
    // stand-ins Polymer draws empty go into empty too.
    static Map<BlockState, BlockState> pick(BlockResourceCreator creator, Set<Block> fallback,
            Map<BlockState, BlockState> pooled, Function<Block, StandInKind> kinds,
            Set<BlockState> empty) {
        Map<BlockState, BlockState> picked = new IdentityHashMap<>();
        for (Block block : fallback) {
            for (BlockState real : block.getStateDefinition().getPossibleStates()) {
                if (pooled.containsKey(real)) {
                    continue;
                }
                BlockState standIn = standIn(creator, kinds.apply(block), real, empty);
                if (standIn == null) {
                    Polybackwards.LOGGER.warn("Older-client stand-ins: no display stand-in for {}",
                            real);
                } else {
                    picked.put(real, standIn);
                }
            }
        }
        return picked;
    }

    // A vanilla state 26.1 has with the real state's shape, or null when none is left.
    private static @Nullable BlockState standIn(BlockResourceCreator creator, StandInKind kind,
            BlockState real, Set<BlockState> empty) {
        Block block = real.getBlock();
        if (kind == StandInKind.COPY) {
            // The table's block keeps the client's own renderer, such as a sign's text and editor.
            Block copy = OlderClientStandIns.copyOf(block);
            return copy == null ? null : copy.withPropertiesOf(real);
        }
        if (block instanceof SlabBlock) {
            SlabType type = real.getValue(BlockStateProperties.SLAB_TYPE);
            // Polymer's empty slabs draw nothing; one is shared by every mod that asks.
            return type == SlabType.DOUBLE ? barrier(real) : requestEmpty(creator,
                    BlockModelType.getSlab(type == SlabType.BOTTOM, waterlogged(real)), empty);
        }
        if (block instanceof StairBlock) {
            return copy(STAIRS, real);
        }
        if (block instanceof FenceBlock) {
            return copy(FENCES, real);
        }
        if (block instanceof WallBlock) {
            return Blocks.SANDSTONE_WALL.withPropertiesOf(real);
        }
        if (block instanceof ButtonBlock) {
            return copy(BUTTONS, real);
        }
        if (block instanceof PressurePlateBlock) {
            return copy(PLATES, real);
        }
        if (block instanceof FlowerPotBlock) {
            return Blocks.FLOWER_POT.defaultBlockState();
        }
        if (block instanceof SpeleothemBlock) {
            return Blocks.POINTED_DRIPSTONE.withPropertiesOf(real);
        }
        if (real.hasProperty(BlockStateProperties.BED_PART)) {
            // A bed stand-in on 26.1 would draw its own block entity over the display.
            return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, BED_SNOW_LAYERS);
        }
        BlockModelType pool = kind.isPooled() && kind.fits(block) ? kind.pool(real) : null;
        if (pool == null) {
            // No vanilla shape fits (shelf mushroom): an invisible, solid, targetable full cube.
            return barrier(real);
        }
        // A full block on a barrier keeps light at the display's centre; an empty note block not.
        return pool == BlockModelType.FULL_BLOCK || pool.name().startsWith("LEAVES")
                ? barrier(real) : requestEmpty(creator, pool, empty);
    }

    // Polymer's empty state of a pool, noted in empty; null when the pool has none left.
    private static @Nullable BlockState requestEmpty(BlockResourceCreator creator,
            BlockModelType pool, Set<BlockState> empty) {
        BlockState standIn = creator.requestEmpty(pool);
        if (standIn != null) {
            empty.add(standIn);
        }
        return standIn;
    }

    // The first candidate that sounds like the real block, else the last, with its properties.
    private static BlockState copy(List<Block> candidates, BlockState real) {
        Block chosen = candidates.getLast();
        for (Block candidate : candidates) {
            if (candidate.defaultBlockState().getSoundType() == real.getSoundType()) {
                chosen = candidate;
                break;
            }
        }
        return chosen.withPropertiesOf(real);
    }

    // An invisible full cube, keeping the real state's water.
    private static BlockState barrier(BlockState real) {
        return Blocks.BARRIER.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, waterlogged(real));
    }

    // Whether a state holds water; false for one that cannot.
    private static boolean waterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }
}
