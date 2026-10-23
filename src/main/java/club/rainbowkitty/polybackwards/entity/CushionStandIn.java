package club.rainbowkitty.polybackwards.entity;

import java.util.List;
import java.util.function.Consumer;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.data.InteractionEntityData;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.ItemStack;

/**
 * What one cushion is to older clients: an interaction entity of the cushion's size under the
 * cushion's own id, which sits a rider where the cushion does and passes clicks and attacks to
 * it, plus its {@link CushionElementHolder}, attached when the first such client sees it.
 */
final class CushionStandIn implements PolymerEntity {
    private final Cushion cushion;
    private @Nullable CushionElementHolder holder;

    CushionStandIn(Cushion cushion) {
        this.cushion = cushion;
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return OlderClientEntities.drawsStandIns(context)
                ? EntityTypes.INTERACTION : EntityTypes.CUSHION;
    }

    @Override
    public void onBeforeSpawnPacket(ServerPlayer player, Consumer<Packet<?>> packetConsumer) {
        // Pairing then starts this player watching every holder on the cushion, this one too.
        if (this.holder == null && OlderClientEntities.drawsStandIns(player)) {
            this.holder = new CushionElementHolder(this.cushion);
            EntityAttachment.ofTicking(this.holder, this.cushion);
        }
    }

    @Override
    public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data,
            ServerPlayer player, boolean initial) {
        if (!OlderClientEntities.drawsStandIns(player)) {
            return;
        }
        // The cushion's colour sits where the interaction keeps its size.
        data.removeIf(value -> value.id() >= InteractionEntityData.WIDTH.id());
        if (initial) {
            data.add(SynchedEntityData.DataValue.create(InteractionEntityData.WIDTH,
                    this.cushion.getBbWidth()));
            data.add(SynchedEntityData.DataValue.create(InteractionEntityData.HEIGHT,
                    this.cushion.getBbHeight()));
        }
    }

    // Carries the interaction's size when the cushion has no data of its own to send.
    @Override
    public boolean sendEmptyTrackerUpdates(ServerPlayer player) {
        return OlderClientEntities.drawsStandIns(player);
    }

    @Override
    public boolean canSynchronizeToPolymerClient(ServerPlayer player) {
        return false;
    }

    // The interaction answers a click itself, so a cushion that passes it, as to a sneaking
    // player, lets Polymer use the held item as a newer client would.
    @Override
    public boolean isPolymerEntityInteraction(ServerPlayer player, InteractionHand hand,
            ItemStack stack, ServerLevel world, InteractionResult actionResult) {
        return OlderClientEntities.drawsStandIns(player);
    }
}
