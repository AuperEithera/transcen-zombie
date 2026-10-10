package com.example.transcenmod.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.IPacket;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkHooks;

import java.util.List;

// not LightningBoltEntity - vanilla one is client-excluded so subclasses never reach the client
// plain Entity syncs the same way the zombie does, drawing is unchanged
public class TranscenLightningBolt extends Entity {

    private static final double VANILLA_RADIUS = 3.0D;
    private static final float DAMAGE = 1919810F;
    private static final int LIFETIME = 10;

    // vanilla thunder 10000.0F impact 2.0F,doubled
    private static final float THUNDER_VOLUME = 20000.0F;
    private static final float IMPACT_VOLUME = 4.0F;

    private int age;
    private boolean struck;

    public TranscenLightningBolt(EntityType<? extends TranscenLightningBolt> type, World level) {
        super(type, level);
        this.ignoreFrustumCheck = true;
    }

    @Override
    protected void registerData() {
    }

    @Override
    public void tick() {
        super.tick();
        this.age++;

        if (!(this.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        if (!this.struck) {
            this.struck = true;
            this.strike();
        }
        // own counter,not the vanilla one
        if (this.age >= LIFETIME) {
            this.remove();
        }
    }

    private void strike() {
        this.playThunder();
        ServerWorld serverLevel = (ServerWorld) this.getEntityWorld();
        AxisAlignedBB area = this.getBoundingBox().grow(VANILLA_RADIUS);
        List<LivingEntity> victims = serverLevel.getEntitiesWithinAABB(LivingEntity.class, area,
                e -> !(e instanceof TranscenZombie));
        for (LivingEntity victim : victims) {
            victim.attackEntityFrom(new net.minecraft.util.DamageSource("lightningBolt"), DAMAGE);
            victim.setHealth(0.0F);
        }
    }

    // plain Entity has no strike noise of its own
    private void playThunder() {
        World level = this.getEntityWorld();
        double x = this.getPosX();
        double y = this.getPosY();
        double z = this.getPosZ();
        level.playSound(null, x, y, z, net.minecraft.util.SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
                net.minecraft.util.SoundCategory.WEATHER,
                THUNDER_VOLUME, 0.8F + this.rand.nextFloat() * 0.2F);
        level.playSound(null, x, y, z, net.minecraft.util.SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT,
                net.minecraft.util.SoundCategory.WEATHER,
                IMPACT_VOLUME, 0.5F + this.rand.nextFloat() * 0.2F);
    }

    @Override
    public IPacket<?> createSpawnPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    // short-lived so culling would only lose it
    @Override
    public boolean isInRangeToRenderDist(double distanceSqr) {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    protected void writeAdditional(net.minecraft.nbt.CompoundNBT tag) {
    }

    @Override
    protected void readAdditional(net.minecraft.nbt.CompoundNBT tag) {
    }
}
