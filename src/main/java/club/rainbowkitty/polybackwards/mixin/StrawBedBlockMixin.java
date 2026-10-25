package club.rainbowkitty.polybackwards.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.StrawBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import club.rainbowkitty.polybackwards.standin.OlderClientMapper;

/**
 * Makes an older client's player collide with the straw bed as with its lower snow stand-in, which
 * that client walks on, so the server does not pull the player back. Others keep the real shape.
 */
@Mixin(StrawBedBlock.class)
public abstract class StrawBedBlockMixin {
    /** The collision of the stand-in the player's client is sent, else the bed's. */
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof ServerPlayer player) {
            BlockState standIn = OlderClientMapper.standInFor(
                    OlderClientMapper.standInRelease(player), state);
            if (standIn != null) {
                return standIn.getCollisionShape(level, pos);
            }
        }
        return state.getShape(level, pos);
    }
}
