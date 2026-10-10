package com.example.transcenmod.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Blood-red lightning that kills everything inside the vanilla lightning radius.
 *
 * <p>Vanilla lightning damages entities inside an AABB grown by 3.0 blocks around the bolt, so that
 * is the radius used here: "same range as vanilla, 1919810 damage, and setHealth(0)".
 *
 * <p><b>Lifetime.</b> Vanilla's {@code LightningBolt.tick()} ages the bolt out after two ticks. The
 * lifecycle is delegated to vanilla {@code super.tick()} and {@code life} is seeded exactly once in
 * the constructor, so the bolt lives {@link #LIFETIME} ticks and then removes itself the ordinary
 * way. An earlier revision rewrote {@code life} on every tick, which reset the counter forever and
 * made the bolts immortal -- do not do that.
 *
 * <p>The strike itself is this mod's, not vanilla's: {@code visualOnly} is set so the bolt never
 * burns blocks or de-oxidises copper.
 *
 * <p>Colour: blood red, applied by {@code TranscenLightningRenderer}.
 */
public class TranscenLightningBolt extends LightningBolt {

    /** Vanilla's damage radius around a struck position. */
    private static final double VANILLA_RADIUS = 3.0D;
    /** The specified damage; {@code setHealth(0)} afterwards guarantees the kill regardless. */
    private static final float DAMAGE = 1919810F;
    /** Ticks before the bolt removes itself. */
    private static final int LIFETIME = 10;

    public TranscenLightningBolt(EntityType<? extends LightningBolt> type, Level level) {
        super(type, level);
        this.setVisualOnly(true);
        // Seeded once. LightningBolt#tick decrements this and removes the bolt when it hits zero.
        this.life = LIFETIME;
    }

    @Override
    public void tick() {
        // Vanilla lifecycle: ages, flashes, and removes itself when life runs out.
        super.tick();

        if (!(this.level() instanceof ServerLevel)) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) this.level();
        AABB area = this.getBoundingBox().inflate(VANILLA_RADIUS);
        List<LivingEntity> victims = serverLevel.getEntitiesOfClass(LivingEntity.class, area,
                EntitySelector.NO_SPECTATORS.and(e -> !(e instanceof TranscenZombie)));
        for (LivingEntity victim : victims) {
            victim.hurt(this.damageSources().lightningBolt(), DAMAGE);
            victim.setHealth(0.0F);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return true;
    }

    @Override
    public boolean isPickable() {
        return false;
    }
}
