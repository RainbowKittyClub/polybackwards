package club.rainbowkitty.polybackwards.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.vehicle.VehicleEntity;

/** A vehicle's synced hit wobble, private to it. */
@Mixin(VehicleEntity.class)
public interface VehicleEntityAccessor {
    /** The hurt time's data id. */
    @Accessor("DATA_ID_HURT")
    static EntityDataAccessor<Integer> polybackwards$hurt() {
        throw new AssertionError();
    }

    /** The hurt direction's data id. */
    @Accessor("DATA_ID_HURTDIR")
    static EntityDataAccessor<Integer> polybackwards$hurtDir() {
        throw new AssertionError();
    }

    /** The damage's data id. */
    @Accessor("DATA_ID_DAMAGE")
    static EntityDataAccessor<Float> polybackwards$damage() {
        throw new AssertionError();
    }
}
