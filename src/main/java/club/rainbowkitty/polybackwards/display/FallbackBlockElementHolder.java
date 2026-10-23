package club.rainbowkitty.polybackwards.display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemDisplayContext;

import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.standin.OlderClientMapper;

/**
 * The displays over one placed display-fallback block. Only clients older than the block that
 * hold the server pack watch it, as only they get its stand-in; its displays are built for the
 * first of them and dropped when the last leaves, so a block no such client sees costs no entities.
 */
public final class FallbackBlockElementHolder extends ElementHolder {
    // Holders with an attachment, for the probe.
    private static final Set<FallbackBlockElementHolder> LIVE = ConcurrentHashMap.newKeySet();

    private final Release introduced;
    private final List<ItemDisplayElement> displays = new ArrayList<>();

    /** A holder for a block that clients before {@code introduced} lack. */
    public FallbackBlockElementHolder(Release introduced) {
        this.introduced = introduced;
    }

    /** What the loaded display-fallback blocks hold now; server thread only. */
    public static Summary summary() {
        int drawn = 0;
        int displays = 0;
        int watchers = 0;
        for (FallbackBlockElementHolder holder : LIVE) {
            drawn += holder.displays.isEmpty() ? 0 : 1;
            displays += holder.displays.size();
            watchers += holder.getWatchingPlayers().size();
        }
        return new Summary(LIVE.size(), drawn, displays, watchers);
    }

    /** The displays this holder shows now. */
    public int displayCount() {
        return this.displays.size();
    }

    /** The displays this holder shows now, in blockstate order; empty while no one watches. */
    public List<ItemDisplayElement> displays() {
        return Collections.unmodifiableList(this.displays);
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl player) {
        if (OlderClientMapper.standInRelease(player.getPlayer()).compareTo(this.introduced) >= 0) {
            // A watcher that dropped the server pack is resent its chunks, which lands here.
            if (this.getWatchingPlayers().contains(player)) {
                this.stopWatching(player);
            }
            return false;
        }
        if (this.getWatchingPlayers().isEmpty()) {
            this.draw();
        }
        return super.startWatching(player);
    }

    @Override
    public boolean stopWatching(ServerGamePacketListenerImpl player) {
        if (this.displays.isEmpty() || this.getWatchingPlayers().size() != 1
                || !this.getWatchingPlayers().contains(player)) {
            return super.stopWatching(player);
        }
        // The removal super sends keeps this holder's id list, which dropping the displays
        // empties, so the last watcher gets its own copy.
        IntList ids = new IntArrayList(this.getEntityIds());
        for (ItemDisplayElement display : this.displays) {
            this.removeElementWithoutUpdates(display);
        }
        this.displays.clear();
        boolean stopped = super.stopWatching(player);
        if (player.isAcceptingMessages()) {
            player.send(new ClientboundRemoveEntitiesPacket(ids));
        }
        return stopped;
    }

    @Override
    public void notifyUpdate(HolderAttachment.UpdateType updateType) {
        if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE
                && !this.getWatchingPlayers().isEmpty()) {
            this.draw();
        }
    }

    @Override
    protected void onAttachmentSet(HolderAttachment attachment,
            @Nullable HolderAttachment oldAttachment) {
        LIVE.add(this);
    }

    @Override
    protected void onAttachmentRemoved(HolderAttachment oldAttachment) {
        LIVE.remove(this);
    }

    // Matches the displays to the block's state, reusing those already sent so none flashes.
    private void draw() {
        if (!(this.getAttachment() instanceof BlockAwareAttachment block)) {
            return;
        }
        List<DisplayFallbackVisual.Part> parts =
                DisplayFallbackVisual.parts(block.getBlockState(), block.getBlockPos());
        while (this.displays.size() > parts.size()) {
            this.removeElement(this.displays.removeLast());
        }
        for (int i = 0; i < parts.size(); i++) {
            DisplayFallbackVisual.Part part = parts.get(i);
            if (i < this.displays.size()) {
                ItemDisplayElement display = this.displays.get(i);
                apply(display, part);
                display.tick();
            } else {
                ItemDisplayElement display = new ItemDisplayElement();
                display.setDisplaySize(2, 2);
                display.setInvisible(true);
                display.setTeleportDuration(0);
                display.setItemDisplayContext(ItemDisplayContext.NONE);
                // Context NONE draws an item model facing the other way from its block model.
                display.setYaw(180);
                apply(display, part);
                this.displays.add(display);
                this.addElement(display);
            }
        }
    }

    // Sets what a part changes per state.
    private static void apply(ItemDisplayElement display, DisplayFallbackVisual.Part part) {
        display.setItem(part.stack());
        display.setLeftRotation(part.rotation());
        display.setOffset(part.offset());
        display.setViewRange(part.viewRange());
    }

    /**
     * The loaded display-fallback blocks.
     *
     * @param holders blocks loaded with a holder
     * @param drawn those with displays built, which some older client watches
     * @param displays display entities across them
     * @param watchers watching connections across them
     */
    public record Summary(int holders, int drawn, int displays, int watchers) {
    }
}
