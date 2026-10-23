package club.rainbowkitty.polybackwards.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Mob;

/** A mob's synced flags, private to it. */
@Mixin(Mob.class)
public interface MobAccessor {
    /** The flags' data id; bit 0 is NoAI. */
    @Accessor("DATA_MOB_FLAGS_ID")
    static EntityDataAccessor<Byte> polybackwards$mobFlags() {
        throw new AssertionError();
    }
}
