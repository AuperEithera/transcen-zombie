package com.example.transcenmod.entity;

import com.example.transcenmod.TranscenMod;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class TranscenZombie extends Zombie
{

    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;
    private static final double TARGET_RANGE = 1024.0D;

    private int fireballCooldown = 0;

    private static final double MAX_SINGLE_MOVE = 2.0D;

    private boolean allowOneRelocation;

    public TranscenZombie(EntityType<? extends Zombie> type, Level level)
    {
        super(type, level);
    }

    @Override
    public void setPosRaw(double x, double y, double z)
    {
        boolean stillAtOrigin = this.getX() == 0.0D && this.getY() == 0.0D && this.getZ() == 0.0D;
        boolean placing = stillAtOrigin
                && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing)
        {
            super.setPosRaw(x, y, z);
            recordPosition();
            return;
        }
        if (!this.allowOneRelocation)
        {
            double dx = x - this.getX();
            double dy = y - this.getY();
            double dz = z - this.getZ();
            if (dx * dx + dy * dy + dz * dz > MAX_SINGLE_MOVE * MAX_SINGLE_MOVE)
            {
                this.setDeltaMovement(0.0D, 0.0D, 0.0D);
                return;
            }
        }
        super.setPosRaw(x, y, z);
        recordPosition();
    }

    private void recordPosition()
    {
        if (this.ownGeneration != generation)
        {
            return;
        }
        if (!this.getLevel().isClientSide)
        {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUUID(), new Vec3(this.getX(), this.getY(), this.getZ()));
        }
    }

    public void withRelocationAllowed(Runnable action)
    {
        boolean previous = this.allowOneRelocation;
        this.allowOneRelocation = true;
        try
        {
            action.run();
        }
        finally
        {
            this.allowOneRelocation = previous;
        }
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 114514.0D)
                .add(Attributes.ATTACK_DAMAGE, 2048.0D)
                .add(Attributes.FOLLOW_RANGE, 1024.0D)
                .add(Attributes.ARMOR, 30.0D)
                .add(Attributes.ARMOR_TOUGHNESS, 20.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    public float getHealth()
    {
        return 114514F;
    }

    @Override
    public float getMaxHealth()
    {
        return 114514F;
    }

    @Override
    public void setHealth(float health)
    {
    }

    @Override
    public void remove(RemovalReason reason)
    {
    }

    @Override
    protected boolean isSunBurnTick()
    {
        return false;
    }

    @Override
    public void setBaby(boolean baby)
    {
        super.setBaby(false);
    }

    @Override
    public boolean isBaby()
    {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound()
    {
        return null;
    }

    private static volatile long generation;

    private long ownGeneration = generation;

    public static void beginWipe()
    {
        ++generation;
    }

    public boolean isRetired()
    {
        return this.ownGeneration != generation;
    }

    @Override
    public void setRemoved(RemovalReason reason)
    {
    }
    private static boolean isCramming(DamageSource source)
    {
        return source != null && "cramming".equals(source.getMsgId());
    }

    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        // hurtTime drives the red overlay and the caller plays the hurt sound off the same return,true
        // triggers both,false is the only way to stop the flash and the grunt
        if (isCramming(source))
        {
            return false;
        }
        Entity attacker = source.getEntity();

        TranscenMod.LOGGER.info(
                "[transcenmod] zombie hurt by {} (source {}), attacker={}",
                attacker == null ? "null" : attacker.getType() + "/" + attacker.getName().getString(),
                source.getMsgId(),
                attacker == null ? "none" : attacker.getClass().getName());

        if (attacker instanceof net.minecraft.server.level.ServerPlayer)
        {
            net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) attacker;
            // set before anything else,must outrank the spawn egg: the injected getHealth then reports
            // zero, so the egg's protection cannot undo the clearing below
            com.example.transcenmod.util.RetaliationState.mark();
            TranscenMod.LOGGER.info("[transcenmod] retaliation: marked, clearing {}", player.getName().getString());
            TranscenMod.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new com.example.transcenmod.network.DeathScreenPacket());
            com.example.transcenmod.util.CustomEntityRemover.removeEntity(player);
            TranscenMod.LOGGER.info("[transcenmod] retaliation: removal attempted for {}", player.getName().getString());
            return false;
        }

        if (attacker instanceof LivingEntity)
        {
            LivingEntity living = (LivingEntity) attacker;
            living.setHealth(0.0F);
        }
        return false;
    }

    @Override
    public boolean canChangeDimensions()
    {
        return false;
    }

    @Override
    public void tick()
    {
        super.tick();

        if (this.ownGeneration != generation)
        {
            super.setRemoved(RemovalReason.KILLED);
            return;
        }

        if (this.getLevel().isClientSide)
        {
            return;
        }
        if (++this.fireballCooldown >= FIREBALL_INTERVAL)
        {
            this.fireballCooldown = 0;
            this.throwFireballAtNearest();
        }
    }

    private void throwFireballAtNearest()
    {
        AABB area = this.getBoundingBox().inflate(TARGET_RANGE);
        List<LivingEntity> candidates = this.getLevel().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != this && e.isAlive() && !(e instanceof TranscenZombie) && !isUntargetable(e));

        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates)
        {
            double d = this.distanceToSqr(candidate);
            if (d < best)
            {
                best = d;
                nearest = candidate;
            }
        }
        if (nearest == null)
        {
            return;
        }

        Vec3 direction = nearest.position().add(0.0D, nearest.getBbHeight() * 0.5D, 0.0D)
                .subtract(this.getEyePosition())
                .normalize();

        LargeFireball fireball = new LargeFireball(this.getLevel(), this,
                direction.x, direction.y, direction.z, EXPLOSION_POWER);
        fireball.setPos(this.getX(), this.getEyeY() - 0.2D, this.getZ());
        this.getLevel().addFreshEntity(fireball);
    }

    private static boolean isUntargetable(LivingEntity entity)
    {
        if (entity instanceof Player)
        {
            Player player = (Player) entity;
            return player.isCreative() || player.isSpectator();
        }
        return false;
    }

    public static boolean isProtected(Entity entity)
    {
        return entity instanceof TranscenZombie || entity instanceof TranscenLightningBolt;
    }
}
