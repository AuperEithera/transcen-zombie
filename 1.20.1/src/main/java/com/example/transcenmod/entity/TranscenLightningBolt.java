package com.example.transcenmod.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class TranscenLightningBolt extends LightningBolt
{

    private static final double VANILLA_RADIUS = 3.0D;
    private static final float DAMAGE = 1919810F;
    private static final int LIFETIME = 10;

    public TranscenLightningBolt(EntityType<? extends LightningBolt> type, Level level)
    {
        super(type, level);
        this.setVisualOnly(true);
        this.life = LIFETIME;
    }

    @Override
    public void tick()
    {
        super.tick();

        if (!(this.level() instanceof ServerLevel))
        {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) this.level();
        AABB area = this.getBoundingBox().inflate(VANILLA_RADIUS);
        List<LivingEntity> victims = serverLevel.getEntitiesOfClass(LivingEntity.class, area,
                EntitySelector.NO_SPECTATORS.and(e -> !(e instanceof TranscenZombie)));
        for (LivingEntity victim : victims)
        {
            victim.hurt(this.damageSources().lightningBolt(), DAMAGE);
            victim.setHealth(0.0F);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr)
    {
        return true;
    }

    @Override
    public boolean isPickable()
    {
        return false;
    }
}
