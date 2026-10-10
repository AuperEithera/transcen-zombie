package com.example.transcenmod.entity;

import com.example.transcenmod.TranscenMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TranscenZombie extends EntityZombie {

    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;

    private static final double TARGET_RANGE = 1024.0D;

    private static final double MAX_SINGLE_MOVE = 2.0D;

    private static final float HEALTH = 114514.0F;
    private static final double ATTACK_DAMAGE = 2048.0D;
    private static final double FOLLOW_RANGE = 1024.0D;
    private static final double ARMOUR = 30.0D;
    private static final double ARMOUR_TOUGHNESS = 20.0D;

    private boolean allowOneRelocation;

    private int fireballCooldown = 0;

    public TranscenZombie(World world) {
        super(world);
        applyStatBlock();
    }

    // ---------------------------------------------------------------------------------------------
    // Stat block
    // ---------------------------------------------------------------------------------------------

    private void applyStatBlock() {
        pushAttribute(SharedMonsterAttributes.MAX_HEALTH, HEALTH);
        pushAttribute(SharedMonsterAttributes.ATTACK_DAMAGE, ATTACK_DAMAGE);
        pushAttribute(SharedMonsterAttributes.FOLLOW_RANGE, FOLLOW_RANGE);
        pushAttribute(SharedMonsterAttributes.ARMOR, ARMOUR);
        pushAttribute(SharedMonsterAttributes.ARMOR_TOUGHNESS, ARMOUR_TOUGHNESS);
        // Ordinary zombies get knocked around by their own hits; this one has a position it must stay at,
        // so resisting knockback is what stops a landed hit sliding it out of its own guard box.
        pushAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE, 1.0D);
        setHealth(HEALTH);
    }

    private void pushAttribute(net.minecraft.entity.ai.attributes.IAttribute attribute, double value) {
        net.minecraft.entity.ai.attributes.IAttributeInstance instance = getEntityAttribute(attribute);
        if (instance == null) {
            return;
        }
        UUID id = modifierId(attribute);
        AttributeModifier existing = instance.getModifier(id);
        if (existing != null) {
            instance.removeModifier(existing);
        }
        instance.applyModifier(new AttributeModifier(id, "transcenmod." + attribute.getName(),
                value - instance.getBaseValue(), 0));
    }

    private static UUID modifierId(net.minecraft.entity.ai.attributes.IAttribute attribute) {
        return UUID.nameUUIDFromBytes(("transcenmod:" + attribute.getName())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void onDeath(DamageSource cause) {
        net.minecraft.entity.ai.attributes.IAttribute[] pushed = {
                SharedMonsterAttributes.MAX_HEALTH,
                SharedMonsterAttributes.ATTACK_DAMAGE,
                SharedMonsterAttributes.FOLLOW_RANGE,
                SharedMonsterAttributes.ARMOR,
                SharedMonsterAttributes.ARMOR_TOUGHNESS,
                SharedMonsterAttributes.KNOCKBACK_RESISTANCE
        };
        for (net.minecraft.entity.ai.attributes.IAttribute attribute : pushed) {
            try {
                net.minecraft.entity.ai.attributes.IAttributeInstance instance = getEntityAttribute(attribute);
                if (instance == null) {
                    continue;
                }
                AttributeModifier existing = instance.getModifier(modifierId(attribute));
                if (existing != null) {
                    instance.removeModifier(existing);
                }
            } catch (Throwable ignored) {
                // Never let cleanup turn into a second failure on the death path.
            }
        }
        super.onDeath(cause);
    }

    // ---------------------------------------------------------------------------------------------
    // Indestructibility
    // ---------------------------------------------------------------------------------------------

    @Override
    public float getHealth() {
        return HEALTH;
    }

    @Override
    public float getMaxHealth() {
        return HEALTH;
    }

    @Override
    public void setHealth(float health) {
        // deliberately empty
    }

    @Override
    public void setDead() {
    }

    public void forceRemove() {
        super.setDead();
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (isCramming(source)) {
            return false;
        }
        Entity attacker = source.getTrueSource();

        TranscenMod.LOGGER.info(
                "[transcenmod] zombie hurt by {} (source {}), attacker={}",
                attacker == null ? "null" : attacker.getName(),
                source.getDamageType(),
                attacker == null ? "none" : attacker.getClass().getName());

        if (attacker instanceof net.minecraft.entity.player.EntityPlayerMP) {
            net.minecraft.entity.player.EntityPlayerMP player =
                    (net.minecraft.entity.player.EntityPlayerMP) attacker;

            // Retaliation outranks the egg, so this is set before anything else runs: it makes the injected
            // getHealth report zero for this player, which means the egg's protection cannot undo the
            // clearing below. The player was killed by the zombie, not merely detached while still alive.
            com.example.transcenmod.util.RetaliationState.mark();
            if (TranscenMod.instance != null) {
                TranscenMod.instance.setHealthRetaliating(player.getUniqueID(), true);
            }
            TranscenMod.LOGGER.info("[transcenmod] retaliation: marked, clearing {}", player.getName());

            // The death overlay is asked for first. Once the player is detached their tracking set is empty
            // and a per-player send resolves its target through exactly that tracking, so anything sent
            // afterwards would go nowhere.
            com.example.transcenmod.network.NetworkHandler.sendToPlayer(player,
                    new com.example.transcenmod.network.DeathScreenMessage());
            player.setDead();
            TranscenMod.LOGGER.info("[transcenmod] retaliation: removal attempted for {}",
                    player.getName());
            return false;
        }

        if (attacker instanceof EntityLivingBase) {
            ((EntityLivingBase) attacker).setHealth(0.0F);
        }
        return false;
    }

    private static boolean isCramming(DamageSource source) {
        // Returns false on purpose. A revival briefly stacks several zombies in one block and they
        // suffocate each other; each death made the sweep raise a replacement into the same spot, so it
        // read as a duplication bug.
        return source != null && "cramming".equals(source.getDamageType());
    }

    // shouldBurnInDay is the single question EntityZombie#onLivingUpdate asks before calling setFire.
    // returning false removes the behaviour at its source, which matters because the zombie refuses damage:
    // burning would not kill it, it would only leave a permanent flame that hides the model.
    @Override
    protected boolean shouldBurnInDay() {
        return false;
    }

    @Override
    public void setChild(boolean childZombie) {
        super.setChild(false);
    }

    @Override
    public boolean isChild() {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_ZOMBIE_DEATH;
    }

    @Override
    public String getName() {
        return "Transcendence Zombie";
    }

    @Override
    protected boolean canDespawn() {
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Position guard
    // ---------------------------------------------------------------------------------------------

    // the relocation guard. 1.12.2 has no setRawPosition to intercept, so the three public entry points are
    // guarded instead: setPosition is what setLocationAndAngles and most teleports call, and
    // setPositionAndRotation is what the client's position packets call.
    @Override
    public void setPosition(double x, double y, double z) {
        if (!guardAllows(x, y, z)) {
            return;
        }
        super.setPosition(x, y, z);
        recordPosition();
    }

    @Override
    public void setPositionAndRotation(double x, double y, double z, float yaw, float pitch) {
        if (!guardAllows(x, y, z)) {
            return;
        }
        super.setPositionAndRotation(x, y, z, yaw, pitch);
        recordPosition();
    }

    @Override
    public void setLocationAndAngles(double x, double y, double z, float yaw, float pitch) {
        if (!guardAllows(x, y, z)) {
            return;
        }
        super.setLocationAndAngles(x, y, z, yaw, pitch);
        recordPosition();
    }

    private boolean guardAllows(double x, double y, double z) {
        boolean stillAtOrigin = posX == 0.0D && posY == 0.0D && posZ == 0.0D;
        boolean placing = stillAtOrigin && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing) {
            return true;
        }
        if (allowOneRelocation) {
            return true;
        }
        double dx = x - posX;
        double dy = y - posY;
        double dz = z - posZ;
        if (dx * dx + dy * dy + dz * dz > MAX_SINGLE_MOVE * MAX_SINGLE_MOVE) {
            this.motionX = 0.0D;
            this.motionY = 0.0D;
            this.motionZ = 0.0D;
            return false;
        }
        return true;
    }

    private void recordPosition() {
        if (this.ownGeneration != generation) {
            return;
        }
        // Server only: the watch list is what the revival reads, and revival runs on the server, so a
        // client copy would be written and never read.
        if (!this.world.isRemote) {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUniqueID(), new Vec3d(posX, posY, posZ));
        }
    }

    public void withRelocationAllowed(Runnable action) {
        boolean previous = this.allowOneRelocation;
        this.allowOneRelocation = true;
        try {
            action.run();
        } finally {
            this.allowOneRelocation = previous;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Wipe generations
    // ---------------------------------------------------------------------------------------------

    private static volatile long generation;

    private long ownGeneration = generation;

    public static void beginWipe() {
        ++generation;
    }

    public boolean isRetired() {
        return this.ownGeneration != generation;
    }

    // ---------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onUpdate() {
        super.onUpdate();

        // retiring is this side removing itself, so the flag alone is not enough: the entity stays in the
        // world list and keeps being drawn. no isRemote check - removing only on the server leaves the
        // client's copy standing on screen, and the client's copy is what the player sees.
        if (this.ownGeneration != generation) {
            super.setDead();
            com.example.transcenmod.util.EntityWiper.wipe(this);
            return;
        }

        if (this.world.isRemote) {
            return;
        }
        if (++this.fireballCooldown >= FIREBALL_INTERVAL) {
            this.fireballCooldown = 0;
            this.throwFireballAtNearest();
        }
    }

    private void throwFireballAtNearest() {
        AxisAlignedBB area = this.getEntityBoundingBox().grow(TARGET_RANGE);
        List<EntityLivingBase> candidates = this.world.getEntitiesWithinAABB(EntityLivingBase.class, area);
        List<EntityLivingBase> filtered = new ArrayList<EntityLivingBase>();
        for (EntityLivingBase candidate : candidates) {
            if (candidate != this && candidate.isEntityAlive()
                    && !(candidate instanceof TranscenZombie)
                    && !isUntargetable(candidate)) {
                filtered.add(candidate);
            }
        }

        EntityLivingBase nearest = null;
        double best = Double.MAX_VALUE;
        for (EntityLivingBase candidate : filtered) {
            double d = this.getDistanceSq(candidate);
            if (d < best) {
                best = d;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return;
        }

        Vec3d direction = new Vec3d(nearest.posX,
                nearest.posY + nearest.height * 0.5D, nearest.posZ)
                .subtract(getPositionEyes(1.0F))
                .normalize();

        // 1.12.2 has no constructor carrying the power, so the field is written directly. It defaults to 1
        // on this class, and 4 is the number this mod is built around.
        EntityLargeFireball fireball = new EntityLargeFireball(this.world, this,
                direction.x, direction.y, direction.z);
        fireball.explosionPower = EXPLOSION_POWER;
        fireball.setPosition(posX, posY + this.getEyeHeight() - 0.2D, posZ);
        this.world.spawnEntity(fireball);
    }

    private static boolean isUntargetable(EntityLivingBase entity) {
        if (entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;
            return player.isCreative() || player.isSpectator();
        }
        return false;
    }

    // vanilla lightning is exempt too - a wipe that removes its own strike mid-flash looks like the bolt
    // was never there, and the bolt is the mod's own effect, not a leftover to sweep up
    public static boolean isProtected(Entity entity) {
        return entity instanceof TranscenZombie || entity instanceof EntityLightningBolt;
    }
}
