package club.rainbowkitty.polybackwards.entity;

import java.util.List;
import java.util.function.Consumer;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;

import club.rainbowkitty.polybackwards.mixin.AbstractBoatAccessor;
import club.rainbowkitty.polybackwards.mixin.VehicleEntityAccessor;

/**
 * What one poplar boat or chest boat is to older clients with the server pack: the pale oak one,
 * worn by its {@link BoatShell} through a {@link PoplarBoatElementHolder}, attached when the
 * first such client sees it.
 */
final class PoplarBoatStandIn implements PolymerEntity {
    // Hurt time, direction and damage, and bubble time: their rocking tips the hull through the
    // shell.
    private static final IntSet ROCKING = IntSet.of(
            VehicleEntityAccessor.polybackwards$hurt().id(),
            VehicleEntityAccessor.polybackwards$hurtDir().id(),
            VehicleEntityAccessor.polybackwards$damage().id(),
            AbstractBoatAccessor.polybackwards$bubbleTime().id());

    private final AbstractBoat boat;
    private final EntityType<?> standIn;
    private final BoatShell shell;
    private @Nullable PoplarBoatElementHolder holder;

    PoplarBoatStandIn(AbstractBoat boat, EntityType<?> standIn, BoatShell shell) {
        this.boat = boat;
        this.standIn = standIn;
        this.shell = shell;
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return OlderClientEntities.drawsStandIns(context) ? this.standIn : this.boat.getType();
    }

    @Override
    public void onBeforeSpawnPacket(ServerPlayer player, Consumer<Packet<?>> packetConsumer) {
        // Pairing then starts this player watching every holder on the boat, this one too.
        if (this.holder == null && OlderClientEntities.drawsStandIns(player)) {
            this.holder = new PoplarBoatElementHolder(this.boat, this.shell);
            EntityAttachment.ofTicking(this.holder, this.boat);
        }
    }

    @Override
    public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data,
            ServerPlayer player, boolean initial) {
        if (player != null && OlderClientEntities.drawsStandIns(player)) {
            data.removeIf(value -> ROCKING.contains(value.id()));
        }
    }

    @Override
    public boolean canSynchronizeToPolymerClient(ServerPlayer player) {
        return false;
    }

    @Override
    public boolean sendEmptyTrackerUpdates(ServerPlayer player) {
        return false;
    }

    // The pale oak boat a client predicts with acts as the poplar one does.
    @Override
    public boolean isPolymerEntityInteraction(ServerPlayer player, InteractionHand hand,
            ItemStack stack, ServerLevel world, InteractionResult actionResult) {
        return false;
    }
}
