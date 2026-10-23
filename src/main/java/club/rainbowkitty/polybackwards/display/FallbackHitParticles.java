package club.rainbowkitty.polybackwards.display;

import java.util.List;

import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.standin.OlderClientMapper;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * Hit particles while a display-fallback block is mined, for the older clients that see its
 * displays over a stand-in that shows nothing (a barrier, Polymer's empty slabs): a client makes
 * them from the state it holds, so it would make none. Sent each tick to such clients within
 * reach, as FactoryTools does for ppb's blocks, as an item particle of the block's own display.
 */
public final class FallbackHitParticles {
    // FactoryTools sends its hit particles this far, short of 26.3's 64 for its own.
    private static final double RANGE = 32;
    // How far out of the hit face a client puts them (ClientLevel.addBreakingParticles).
    private static final double OUT = 0.1;

    private FallbackHitParticles() {}

    /** Sends one tick's hit particles of the block mined at {@code pos} through {@code face}. */
    public static void send(ServerLevel level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        Release introduced = OlderClientStandIns.introducedIn(state.getBlock());
        BlockState standIn = OlderClientStandIns.displayStandIn(state);
        if (introduced == null || standIn == null || !state.shouldSpawnTerrainParticles()
                || !OlderClientStandIns.drawsNothing(standIn)) {
            return;
        }
        BlockBoundAttachment attachment = BlockBoundAttachment.get(level, pos);
        if (attachment == null || !(attachment.holder() instanceof FallbackBlockElementHolder)) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        ItemParticleOption particle = null;
        Vec3 at = null;
        Vec3 motion = null;
        for (ServerPlayer viewer : level.players()) {
            if (!viewer.position().closerThan(centre, RANGE)
                    || OlderClientMapper.standInRelease(viewer).compareTo(introduced) >= 0) {
                continue;
            }
            if (particle == null) {
                List<DisplayFallbackVisual.Part> parts = DisplayFallbackVisual.parts(state, pos);
                if (parts.isEmpty()) {
                    return;
                }
                particle = new ItemParticleOption(ParticleTypes.ITEM,
                        ItemStackTemplate.fromNonEmptyStack(parts.getFirst().stack()));
                at = position(level, state, pos, face);
                motion = motion(level.getRandom());
            }
            level.sendParticles(viewer, particle, false, false, at.x, at.y, at.z, 0, motion.x,
                    motion.y, motion.z, 1);
        }
    }

    // A random point on the hit face of the block's shape, a little out, as a client picks one.
    private static Vec3 position(ServerLevel level, BlockState state, BlockPos pos,
            Direction face) {
        RandomSource random = level.getRandom();
        VoxelShape shape = state.getShape(level, pos);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        double x = random.nextDouble() * (box.maxX - box.minX - 2 * OUT) + OUT + box.minX;
        double y = random.nextDouble() * (box.maxY - box.minY - 2 * OUT) + OUT + box.minY;
        double z = random.nextDouble() * (box.maxZ - box.minZ - 2 * OUT) + OUT + box.minZ;
        switch (face) {
            case DOWN -> y = box.minY - OUT;
            case UP -> y = box.maxY + OUT;
            case NORTH -> z = box.minZ - OUT;
            case SOUTH -> z = box.maxZ + OUT;
            case WEST -> x = box.minX - OUT;
            case EAST -> x = box.maxX + OUT;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    // A hit particle's random motion at the 0.2 power vanilla gives it; an item particle keeps it.
    private static Vec3 motion(RandomSource random) {
        double x = random.nextFloat() * 2 - 1;
        double y = random.nextFloat() * 2 - 1;
        double z = random.nextFloat() * 2 - 1;
        double speed = (random.nextFloat() + random.nextFloat() + 1) * 0.15 * 0.4 * 0.2;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) {
            return new Vec3(0, 0.1, 0);
        }
        return new Vec3(x / length * speed, y / length * speed + 0.1, z / length * speed);
    }
}
