package club.rainbowkitty.polybackwards.entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.Direction;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * The item display drawing one cushion for older clients with the server pack; no other client
 * watches it. It follows the cushion's colour, facing and position.
 */
final class CushionElementHolder extends ElementHolder {
    // Holders some client watches, for the probe.
    private static final Set<CushionElementHolder> DRAWN = ConcurrentHashMap.newKeySet();

    private final Cushion cushion;
    private final ItemDisplayElement display = new ItemDisplayElement();
    private DyeColor color;
    private Direction facing;

    CushionElementHolder(Cushion cushion) {
        this.cushion = cushion;
        this.color = cushion.getColor();
        this.facing = Direction.fromYRot(cushion.getYRot());
        this.display.setItemDisplayContext(ItemDisplayContext.NONE);
        // Context NONE turns an item model half a turn; this yaw turns it back.
        this.display.setYaw(180);
        // An item model's floor sits half a block under the display, the cushion's at it.
        this.display.setTranslation(new Vector3f(0, 0.5f, 0));
        this.display.setDisplaySize(1.5f, 0.5f);
        this.display.setInvisible(true);
        this.display.setTeleportDuration(0);
        this.draw();
        this.addElement(this.display);
    }

    /** What the drawn cushions hold now; server thread only. */
    static String summary() {
        int watchers = 0;
        for (CushionElementHolder holder : DRAWN) {
            watchers += holder.getWatchingPlayers().size();
        }
        return DRAWN.size() + " cushions drawn for " + watchers + " watchers";
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl player) {
        if (!OlderClientEntities.drawsStandIns(player.getPlayer())) {
            return false;
        }
        boolean started = super.startWatching(player);
        DRAWN.add(this);
        return started;
    }

    @Override
    public boolean stopWatching(ServerGamePacketListenerImpl player) {
        boolean stopped = super.stopWatching(player);
        if (this.getWatchingPlayers().isEmpty()) {
            DRAWN.remove(this);
        }
        return stopped;
    }

    @Override
    protected void onTick() {
        if (this.color != this.cushion.getColor()
                || this.facing != Direction.fromYRot(this.cushion.getYRot())) {
            this.color = this.cushion.getColor();
            this.facing = Direction.fromYRot(this.cushion.getYRot());
            this.draw();
        }
    }

    // CushionRenderer turns its north-facing model by 180 degrees less the facing's yaw.
    private void draw() {
        this.display.setItem(CushionModels.stack(this.color));
        this.display.setLeftRotation(new Quaternionf().rotateY(
                (180 - this.facing.toYRot()) * Mth.DEG_TO_RAD));
    }
}
