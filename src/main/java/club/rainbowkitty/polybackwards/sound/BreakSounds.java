package club.rainbowkitty.polybackwards.sound;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.standin.OlderClientMapper;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * Block break effects for a client that lacks the block. The break event makes a client play the
 * break sound of the state it holds, a stand-in or ViaBackwards' look-alike, so where that sounds
 * unlike the block, a client with the server pack is sent the block's own break sound and the
 * same particles as separate packets instead.
 */
public final class BreakSounds {
    // A particle's cell edge in the client's own break effect (ClientLevel.addDestroyBlockEffect).
    private static final double CELL = 0.25;

    private BreakSounds() {}

    /**
     * Sends {@code player} the replacement for {@code packet} and returns true, or returns false
     * when the packet goes out as it is.
     */
    public static boolean replace(ServerPlayer player, ClientboundLevelEventPacket packet) {
        if (packet.getType() != LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK
                || packet.isGlobalEvent() || player.connection == null) {
            return false;
        }
        BlockState real = Block.stateById(packet.getData());
        Release added = OlderClientStandIns.introducedIn(real.getBlock());
        PacketContext context = player.connection.getPacketContext();
        Release client = ClientVersions.of(context);
        if (real.isAir() || added == null || client.compareTo(added) >= 0
                || !PolymerResourcePackUtils.hasMainPack(context)) {
            return false;
        }
        BlockState standIn = OlderClientMapper.standInFor(client, real);
        SoundType sound = real.getSoundType();
        if (standIn != null && sameBreak(sound, standIn.getSoundType())) {
            return false;
        }
        BlockPos pos = packet.getPos();
        // OlderClientSounds swaps the event as the packet is encoded for this client.
        player.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound.getBreakSound()),
                SoundSource.BLOCKS, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F,
                player.level().getRandom().nextLong()));
        sendParticles(player, pos, real, standIn != null ? standIn : real);
        return true;
    }

    // Whether two sound types make the same break sound.
    private static boolean sameBreak(SoundType a, SoundType b) {
        return a.getBreakSound() == b.getBreakSound() && a.getVolume() == b.getVolume()
                && a.getPitch() == b.getPitch();
    }

    // The break particles over the shape the client holds, one packet per box; Polymer sends the
    // real state's particles as that client's stand-in, as it does the break event.
    private static void sendParticles(ServerPlayer player, BlockPos pos, BlockState real,
            BlockState shown) {
        if (!shown.shouldSpawnTerrainParticles()) {
            return;
        }
        BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, real);
        shown.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).forAllBoxes(
                (x1, y1, z1, x2, y2, z2) -> {
                    double width = Math.min(1.0, x2 - x1);
                    double height = Math.min(1.0, y2 - y1);
                    double depth = Math.min(1.0, z2 - z1);
                    int count = cells(width) * cells(height) * cells(depth);
                    player.connection.send(new ClientboundLevelParticlesPacket(particle, false,
                            false, pos.getX() + x1 + width / 2, pos.getY() + y1 + height / 2,
                            pos.getZ() + z1 + depth / 2, (float) (width / 4),
                            (float) (height / 4), (float) (depth / 4), 0.0F, count));
                });
    }

    // Particles along one edge of a box, at least two.
    private static int cells(double width) {
        return Math.max(2, Mth.ceil(width / CELL));
    }
}
