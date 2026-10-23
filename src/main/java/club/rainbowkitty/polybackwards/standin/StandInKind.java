package club.rainbowkitty.polybackwards.standin;

import java.util.List;
import java.util.Locale;

import eu.pb4.polymer.blocks.api.BlockModelType;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * How a block new to some client gets its stand-in: the Polymer pool of each state, and the
 * properties a stand-in shares with the state it stands in for.
 */
public enum StandInKind {
    FULL_BLOCK,
    LEAVES(BlockStateProperties.WATERLOGGED),
    SLAB(BlockStateProperties.SLAB_TYPE, BlockStateProperties.WATERLOGGED),
    DOOR(BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.DOUBLE_BLOCK_HALF,
            BlockStateProperties.DOOR_HINGE, BlockStateProperties.OPEN),
    TRAPDOOR(BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HALF,
            BlockStateProperties.OPEN, BlockStateProperties.WATERLOGGED),
    FENCE_GATE(BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.IN_WALL,
            BlockStateProperties.OPEN),
    SHELF(BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.WATERLOGGED),
    PLANT,
    /** No pool fits its shape, so a display draws it over a same-shape vanilla block. */
    DISPLAY,
    /**
     * Goes out as the vanilla block the table names, with the real state's properties, and a
     * display draws the real model over it.
     */
    COPY,
    /** Left to ViaBackwards' look-alike. */
    NONE;

    private final List<Property<?>> sharedProperties;

    StandInKind(Property<?>... sharedProperties) {
        this.sharedProperties = List.of(sharedProperties);
    }

    /** The kind the table names, such as {@code fence_gate}. */
    public static StandInKind byName(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }

    /** Whether this kind takes its stand-ins from Polymer's pools. */
    public boolean isPooled() {
        return this != DISPLAY && this != COPY && this != NONE;
    }

    /** Whether a block has every property this kind reads its pool from. */
    public boolean fits(Block block) {
        BlockState state = block.defaultBlockState();
        return this.sharedProperties.stream().allMatch(state::hasProperty);
    }

    /** Properties a stand-in keeps equal to the real state's wherever both have them. */
    public List<Property<?>> sharedProperties() {
        return this.sharedProperties;
    }

    /**
     * Whether all of a block's stand-ins should come from one donor block, so that a client
     * predicting the next state of one, such as a door it opens, lands on another of them.
     */
    public boolean keepsOneDonor() {
        return this == DOOR || this == TRAPDOOR || this == FENCE_GATE;
    }

    /**
     * The pool whose states share this real state's shape, or null when none does, as for a top or
     * double slab and every state of an unpooled kind.
     */
    public @Nullable BlockModelType pool(BlockState real) {
        return switch (this) {
            case FULL_BLOCK -> BlockModelType.FULL_BLOCK;
            case LEAVES -> waterlogged(real)
                    ? BlockModelType.LEAVES_WATERLOGGED : BlockModelType.LEAVES;
            case SLAB -> real.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.BOTTOM ? null
                    : waterlogged(real)
                    ? BlockModelType.SCULK_SENSOR_WATERLOGGED : BlockModelType.SCULK_SENSOR;
            case DOOR -> BlockModelType.getDoor(doorSide(real));
            case TRAPDOOR -> BlockModelType.getTrapdoor(trapdoorSide(real), waterlogged(real));
            case FENCE_GATE -> gate(real);
            case SHELF -> BlockModelType.getShelf(
                    real.getValue(BlockStateProperties.HORIZONTAL_FACING), waterlogged(real));
            case PLANT -> BlockModelType.PLANT;
            case DISPLAY, COPY, NONE -> null;
        };
    }

    // Whether a state holds water.
    private static boolean waterlogged(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED);
    }

    // The side a door's slab lies against, which names its pool, as DefaultModelData groups them.
    private static Direction doorSide(BlockState door) {
        Direction facing = door.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (!door.getValue(BlockStateProperties.OPEN)) {
            return facing;
        }
        return door.getValue(BlockStateProperties.DOOR_HINGE) == DoorHingeSide.RIGHT
                ? facing.getCounterClockWise() : facing.getClockWise();
    }

    // getTrapdoor's side: an open trapdoor's facing, else UP when closed low and DOWN when high.
    private static Direction trapdoorSide(BlockState trapdoor) {
        if (trapdoor.getValue(BlockStateProperties.OPEN)) {
            return trapdoor.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return trapdoor.getValue(BlockStateProperties.HALF) == Half.TOP
                ? Direction.DOWN : Direction.UP;
    }

    // A gate's pool by name: getGate's axis argument does not say which facings it holds.
    private static BlockModelType gate(BlockState gate) {
        boolean northSouth =
                gate.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == Direction.Axis.Z;
        return BlockModelType.valueOf("GATE_" + (northSouth ? "NORTH_SOUTH" : "EAST_WEST")
                + (gate.getValue(BlockStateProperties.IN_WALL) ? "_INWALL" : "")
                + (gate.getValue(BlockStateProperties.OPEN) ? "_OPEN" : ""));
    }
}
