package com.example.transcenmod.entity;

import net.minecraft.entity.projectile.FireballEntity;

import com.example.transcenmod.TranscenMod;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.DamageSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.AttributeModifierMap;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;

import java.util.List;

public class TranscenZombie extends ZombieEntity
{

    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;
    private static final double TARGET_RANGE = 1024.0D;

    private int fireballCooldown = 0;

    private static final double MAX_SINGLE_MOVE = 2.0D;

    private boolean allowOneRelocation;

    public TranscenZombie(EntityType<? extends ZombieEntity> type, World level)
    {
        super(type, level);
    }

    @Override
    public void setRawPosition(double x, double y, double z)
    {
        // no guard while the entity still sits at the world origin
        // a spawn egg and this mod's own revival both build the entity first and place it second, and
        // that placement is longer than the limit, an always-on guard rejected the game's own move and
        // left every zombie at (0,0,0)
        // a flag set on the first write failed too, construction itself writes the origin so the flag
        // was already set before the real placement arrived
        // testing the position removes that ordering problem,an entity at exactly the origin has not
        // been placed anywhere yet so there is no location to defend, once it is elsewhere the guard
        // engages and stays engaged
        boolean stillAtOrigin = this.getPosX() == 0.0D && this.getPosY() == 0.0D && this.getPosZ() == 0.0D;
        boolean placing = stillAtOrigin
                && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing)
        {
            super.setRawPosition(x, y, z);
            recordPosition();
            return;
        }
        if (!this.allowOneRelocation)
        {
            double dx = x - this.getPosX();
            double dy = y - this.getPosY();
            double dz = z - this.getPosZ();
            if (dx * dx + dy * dy + dz * dz > MAX_SINGLE_MOVE * MAX_SINGLE_MOVE)
            {
                this.setMotion(0.0D, 0.0D, 0.0D);
                return;
            }
        }
        super.setRawPosition(x, y, z);
        recordPosition();
    }

    private void recordPosition()
    {
        // a retired zombie never records itself,this is what made a wipe fail to stick
        // emptying the list is not enough on its own: every position update runs this method so each
        // still present zombie wrote itself straight back in, the sweep then found an entry with no
        // entity raised a replacement and that replacement recorded itself too - remove respawn remove
        // respawn on screen
        // refusing a retired zombie closes the loop with no flag to reset and no suppression window
        if (this.ownGeneration != generation)
        {
            return;
        }
        if (!this.getEntityWorld().isRemote)
        {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUniqueID(), new Vector3d(this.getPosX(), this.getPosY(), this.getPosZ()));
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

    public static AttributeModifierMap.MutableAttribute createAttributes()
    {
        // ZombieEntity's own factory, not MobEntity's
        // a zombie carries attributes of its own - SPAWN_REINFORCEMENTS_CHANCE among them - and
        // ZombieEntity reads them back while it is being placed, building on MobEntity's base set left
        // them absent and that read threw a NullPointerException from inside ZombieEntity the moment a
        // spawn egg put one down
        return net.minecraft.entity.monster.ZombieEntity.func_234342_eQ_()
                .createMutableAttribute(Attributes.MAX_HEALTH, 114514.0D)
                .createMutableAttribute(Attributes.ATTACK_DAMAGE, 2048.0D)
                .createMutableAttribute(Attributes.FOLLOW_RANGE, 1024.0D)
                .createMutableAttribute(Attributes.ARMOR, 30.0D)
                .createMutableAttribute(Attributes.ARMOR_TOUGHNESS, 20.0D)
                .createMutableAttribute(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    public float getHealth()
    {
        return 114514F;
    }

    @Override
    public void setHealth(float health)
    {
    }

    @Override
    public void remove()
    {
    }

    @Override
    protected boolean isInDaylight()
    {
        return false;
    }

    @Override
    public void setChild(boolean baby)
    {
        super.setChild(false);
    }

    @Override
    public boolean isChild()
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
    protected void setDead()
    {
    }

    // returns false on purpose
    // a revival briefly stacks several zombies in one block and they suffocate each other to death
    // each death made the sweep raise a replacement into the same spot so it read as a duplication bug
    private static boolean isCramming(DamageSource source)
    {
        return source != null && "cramming".equals(source.getDamageType());
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount)
    {
        if (isCramming(source))
        {
            return false;
        }
        Entity attacker = source.getTrueSource();

        TranscenMod.LOGGER.info(
                "[transcenmod] zombie hurt by {} (source {}), attacker={}",
                attacker == null ? "null" : attacker.getType() + "/" + attacker.getName().getString(),
                source.getDamageType(),
                attacker == null ? "none" : attacker.getClass().getName());

        if (attacker instanceof net.minecraft.entity.player.ServerPlayerEntity)
        {
            net.minecraft.entity.player.ServerPlayerEntity player = (net.minecraft.entity.player.ServerPlayerEntity) attacker;
            com.example.transcenmod.util.RetaliationState.mark();
            TranscenMod.LOGGER.info("[transcenmod] retaliation: marked, clearing {}", player.getName().getString());
            TranscenMod.NETWORK.send(
                    net.minecraftforge.fml.network.PacketDistributor.PLAYER.with(() -> player),
                    new com.example.transcenmod.network.DeathScreenPacket());
            player.remove(false);
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
    public boolean canChangeDimension()
    {
        return false;
    }

    @Override
    public void tick()
    {
        super.tick();

        if (this.ownGeneration != generation)
        {
            super.setDead();
            return;
        }

        if (this.getEntityWorld().isRemote)
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
        AxisAlignedBB area = this.getBoundingBox().grow(TARGET_RANGE);
        List<LivingEntity> candidates = this.getEntityWorld().getEntitiesWithinAABB(LivingEntity.class,  area, 
                e -> e != this && e.isAlive() && !(e instanceof TranscenZombie) && !isUntargetable(e));

        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates)
        {
            double d = this.getDistanceSq(candidate);
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

        Vector3d direction = nearest.getPositionVec().add(0.0D, nearest.getHeight() * 0.5D, 0.0D)
                .subtract(this.getEyePosition(1.0F))
                .normalize();

        FireballEntity fireball = new FireballEntity(this.getEntityWorld(), this,
                direction.x, direction.y, direction.z);
        fireball.explosionPower = EXPLOSION_POWER;
        fireball.setPosition(this.getPosX(), this.getPosYEye() - 0.2D, this.getPosZ());
        this.getEntityWorld().addEntity(fireball);
    }

    private static boolean isUntargetable(LivingEntity entity)
    {
        if (entity instanceof PlayerEntity)
        {
            PlayerEntity player = (PlayerEntity) entity;
            return player.isCreative() || player.isSpectator();
        }
        return false;
    }

    public static boolean isProtected(Entity entity)
    {
        return entity instanceof TranscenZombie || entity instanceof TranscenLightningBolt;
    }
}
