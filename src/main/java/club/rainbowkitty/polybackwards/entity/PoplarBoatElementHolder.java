package club.rainbowkitty.polybackwards.entity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.SimpleEntityElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import club.rainbowkitty.polybackwards.mixin.MobAccessor;

/**
 * The shell over one poplar boat for older clients with the server pack, as ppb's
 * {@code ShellCarrier}: an invisible dolphin riding the boat last holds the {@link BoatShell}.
 * A living passenger's body turns with the boat every frame, so the shell stays on the hull. No
 * other client watches it.
 */
final class PoplarBoatElementHolder extends ElementHolder {
    // Holders some client watches, for the probe.
    private static final Set<PoplarBoatElementHolder> DRAWN = ConcurrentHashMap.newKeySet();
    private static final Scoreboard TEAMS = new Scoreboard();
    // Each watcher's carrier scale is re-picked this often: near inside 10 blocks, far past 14.
    private static final int CHECK_INTERVAL = 10;
    private static final double NEAR_SQR = 10 * 10;
    private static final double FAR_SQR = 14 * 14;

    private final AbstractBoat boat;
    private final SimpleEntityElement dolphin = new SimpleEntityElement(EntityTypes.DOLPHIN);
    // Rides ahead of the dolphin on an empty boat, so the dolphin never takes the lone seat.
    private final ItemDisplayElement placeholder = new ItemDisplayElement();
    private final IntList emptySeats;
    private final IntList riddenSeats;
    // Each carrier scale with the shell variant made for it.
    private final List<Packet<? super ClientGamePacketListener>> nearShell;
    private final List<Packet<? super ClientGamePacketListener>> farShell;
    private final ClientboundSetPlayerTeamPacket joinTeam;
    private final ClientboundSetPlayerTeamPacket leaveTeam;
    private final Set<ServerGamePacketListenerImpl> near = new ReferenceOpenHashSet<>();
    private int age;

    PoplarBoatElementHolder(AbstractBoat boat, BoatShell shell) {
        this.boat = boat;
        this.dolphin.setInvisible(true);
        this.dolphin.setSilent(true);
        this.dolphin.getSyncedData().set(MobAccessor.polybackwards$mobFlags(), (byte) 1);
        this.dolphin.ignorePositionUpdates();
        this.dolphin.setInteractionHandler(VirtualElement.InteractionHandler.redirect(boat));
        this.placeholder.ignorePositionUpdates();
        this.placeholder.setInteractionHandler(VirtualElement.InteractionHandler.redirect(boat));
        this.emptySeats = IntList.of(this.placeholder.getEntityId(), this.dolphin.getEntityId());
        this.riddenSeats = IntList.of(this.dolphin.getEntityId());
        // Spreads every carrier's scale checks over the interval.
        this.age = this.dolphin.getEntityId();
        this.nearShell = this.scaleAndShell(BoatShell.NEAR_SCALE, shell.stack(true));
        this.farShell = this.scaleAndShell(BoatShell.FAR_SCALE, shell.stack(false));
        // A team that never collides, so the carrier pushes nobody on or beside the boat.
        PlayerTeam team =
                new PlayerTeam(TEAMS, "polybackwards_shell_" + this.dolphin.getEntityId());
        team.setCollisionRule(Team.CollisionRule.NEVER);
        team.getPlayers().add(this.dolphin.getUuid().toString());
        this.joinTeam = ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true);
        this.leaveTeam = ClientboundSetPlayerTeamPacket.createRemovePacket(team);
        this.addPassengerElement(this.placeholder);
        this.addPassengerElement(this.dolphin);
    }

    /** What the drawn shells hold now; server thread only. */
    static String summary() {
        int watchers = 0;
        for (PoplarBoatElementHolder holder : DRAWN) {
            watchers += holder.getWatchingPlayers().size();
        }
        return DRAWN.size() + " boat shells drawn for " + watchers + " watchers";
    }

    // Read as each passenger packet for the boat is built, so the placeholder leaves and returns
    // with the riders.
    @Override
    public IntList getAttachedPassengerEntityIds() {
        return this.boat.getPassengers().isEmpty() ? this.emptySeats : this.riddenSeats;
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl player) {
        if (!OlderClientEntities.drawsStandIns(player.getPlayer())) {
            return false;
        }
        // Position updates are off, so a late watcher gets the carrier where the boat is now.
        this.dolphin.updateLastSyncedPos();
        this.placeholder.updateLastSyncedPos();
        this.dolphin.setYaw(this.boat.getYRot());
        boolean started = super.startWatching(player);
        DRAWN.add(this);
        return started;
    }

    @Override
    public boolean stopWatching(ServerGamePacketListenerImpl player) {
        if (!super.stopWatching(player)) {
            return false;
        }
        this.near.remove(player);
        if (player.isAcceptingMessages()) {
            player.send(this.leaveTeam);
        }
        if (this.getWatchingPlayers().isEmpty()) {
            DRAWN.remove(this);
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void startWatchingExtraPackets(ServerGamePacketListenerImpl player,
            Consumer<Packet<ClientGamePacketListener>> packets) {
        boolean close = this.wantsNear(player.getPlayer(), false);
        if (close) {
            this.near.add(player);
        }
        for (Packet<? super ClientGamePacketListener> packet
                : close ? this.nearShell : this.farShell) {
            packets.accept((Packet<ClientGamePacketListener>) packet);
        }
        packets.accept(this.joinTeam);
    }

    @Override
    protected void onTick() {
        if (++this.age % CHECK_INTERVAL != 0) {
            return;
        }
        for (ServerGamePacketListenerImpl connection : this.getWatchingPlayers()) {
            boolean was = this.near.contains(connection);
            boolean now = this.wantsNear(connection.getPlayer(), was);
            if (now == was) {
                continue;
            }
            if (now) {
                this.near.add(connection);
            } else {
                this.near.remove(connection);
            }
            // One bundle, so the scale and the shell change on the same client tick.
            connection.send(new ClientboundBundlePacket(now ? this.nearShell : this.farShell));
        }
    }

    // Riders and close watchers get the small carrier; the far one draws as far as the boat.
    private boolean wantsNear(ServerPlayer player, boolean wasNear) {
        if (this.boat.hasPassenger(player)) {
            return true;
        }
        double distance = player.distanceToSqr(this.boat);
        return distance < NEAR_SQR || wasNear && distance <= FAR_SQR;
    }

    // The carrier's scale and held shell at that scale.
    private List<Packet<? super ClientGamePacketListener>> scaleAndShell(float scale,
            ItemStack stack) {
        AttributeInstance attribute = new AttributeInstance(Attributes.SCALE, instance -> { });
        attribute.setBaseValue(scale);
        return List.of(
                new ClientboundUpdateAttributesPacket(this.dolphin.getEntityId(),
                        List.of(attribute)),
                // A dragon head, which no dolphin layer draws, widens the client's culling box
                // by 0.5, so the driver ahead of the small carrier still sees the shell.
                new ClientboundSetEquipmentPacket(this.dolphin.getEntityId(), List.of(
                        Pair.of(EquipmentSlot.MAINHAND, stack),
                        Pair.of(EquipmentSlot.HEAD, new ItemStack(Items.DRAGON_HEAD)))));
    }
}
