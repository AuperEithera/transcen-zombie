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

// vanilla zombie AI untouched; this class adds a stat block, one attack, and the layers that make the
// entity impossible to remove.
// 114514 health, 2048 attack damage, 1024 follow range, 30 armour, 20 toughness, and an explosion-4
// fireball at the nearest living thing once a second.
// the stat block goes through attribute modifiers rather than a fresh attribute map: 1.12.2 shares one map
// instance across every EntityZombie, so editing it would restat every ordinary zombie in the game too.
// onDeath removes the modifiers so nothing writes a pushed value back into that shared state.
// the position guard sits on setPosition / setPositionAndRotation / setLocationAndAngles. 1.12.2 has no
// setRawPosition to intercept, and those three are what /tp and EntityPlayerMP's teleport route through.
public class TranscenZombie extends EntityZombie {

    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;

    /** Matches the follow range attribute, so a target it can see is always in range. */
    private static final double TARGET_RANGE = 1024.0D;

    /**
     * Two metres. Ordinary play never comes close -- walking, knockback and AI navigation move a mob in
     * fractions of a block per tick.
     *
     * <p>What exceeds it is anything that relocates the entity outright: {@code /tp}, another mod
     * teleporting it, or pushing it to a far coordinate so the chunk unloads and discards it.
     */
    private static final double MAX_SINGLE_MOVE = 2.0D;

    /** The stat block, applied as modifiers so the shared zombie attribute map stays untouched. */
    private static final float HEALTH = 114514.0F;
    private static final double ATTACK_DAMAGE = 2048.0D;
    private static final double FOLLOW_RANGE = 1024.0D;
    private static final double ARMOUR = 30.0D;
    private static final double ARMOUR_TOUGHNESS = 20.0D;

    /**
     * Set while this mod is deliberately placing the entity so the guard below lets that through.
     *
     * <p>A respawn is by definition a large jump: the new entity starts at the world origin and is then
     * placed where the old one died. Without this the repair could never place what it just created.
     */
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

    /**
     * Applies one attribute as a fixed-UUID modifier.
     *
     * <p>The UUID is derived from the attribute name rather than drawn at random, which makes the modifier
     * idempotent: re-applying it replaces the existing entry instead of stacking a second one on top. The
     * modifier's operation is 0 (additive to the base value), so the amount is the difference between what
     * the shared map already carries and the target.
     */
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

    /**
     * Removes the pushed values on death.
     *
     * <p>{@code EntityLivingBase#onDeath} ends with {@code setHealth(getMaxHealth())}, and for a modifier
     * with operation 0 that reads the <em>base</em> value -- 20 for a zombie -- and then writes it back
     * through {@code setBaseAttribute}, which is the one path that permanently restats the shared attribute
     * map for every zombie in the game. Stripping the modifiers first removes the write.
     *
     * <p>The entity still cannot be removed, so this runs only when something outside this mod reaches the
     * vanilla death path; the point is to make that harmless rather than to allow it.
     */
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

    /**
     * A fixed health read.
     *
     * <p>Paired with {@link #setHealth}, which is a no-op, this is what makes the entity unkillable:
     * {@code setHealth(0)} writes a value nothing ever reads back, and every vanilla and mod-side "is it
     * dead" test asks this method instead.
     *
     * <p>{@code getHealth} is {@code final} on {@code EntityLivingBase} in this generation, so this
     * override only compiles because {@code META-INF/accesstransformer.cfg} drops the finality -- and that
     * file is only read because the jar manifest declares it as {@code FMLAT}.
     */
    @Override
    public float getHealth() {
        return HEALTH;
    }

    /** The same fixed read, for the bar and for anything that scales off the maximum. */
    @Override
    public float getMaxHealth() {
        return HEALTH;
    }

    /** No-op, so the entity cannot be killed through the ordinary damage path. */
    @Override
    public void setHealth(float health) {
        // deliberately empty
    }

    @Override
    public void setDead() {
        // Refuses removal. {@code setDead} is the only writer of the removal flag in this generation, and
        // it is what {@code /kill}, the void, a datapack and another mod's storage sweep all have to come
        // through whether or not they called {@code attackEntityFrom} first. Deliberately no super call: it
        // would set the flag and the entity would stop rendering, stop ticking and disappear from every
        // storage query.
        //
        // A retired zombie removes itself with {@code super.setDead()} from {@link #onUpdate}; super is
        // statically bound, so that bypasses this refusal without weakening it for anyone else.
    }

    // unconditional removal, for this mod's own wipe. the refusal above is total, so a wipe calling
    // setDead() on the zombie would land on that override and the entity would stay exactly where it was.
    // super.setDead() rather than a reflective write: the guard is bypassed by static binding and nothing
    // else. the only caller is TranscenEvents#clearAllZombies, which has already bumped the generation.
    public void forceRemove() {
        super.setDead();
    }

    /**
     * Returns false on purpose for every hit except cramming.
     *
     * <p>{@code hurtTime} drives the red overlay and the caller plays the hurt sound off the same result, so
     * true triggers both. False is the only way to get a zombie that never flashes red or grunts when hit.
     * The attacker still dies -- that is the mod's whole point and independent of this value.
     */
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

        // Retaliation is not just losing health: the attacker goes through the same removal path as the
        // zombie rather than a second, weaker one. That detaches them from the level's storages and empties
        // their client tracking set, so the server copy is gone at once and the client keeps rendering its
        // own copy for the usual two seconds. The player's class is deliberately not banned, so a respawn
        // or a rejoin comes back normally -- being deleted is a consequence of the attack, not a permanent
        // lockout.
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

    /** Never a baby. */
    @Override
    public void setChild(boolean childZombie) {
        super.setChild(false);
    }

    @Override
    public boolean isChild() {
        return false;
    }

    /**
     * No idle sound.
     *
     * <p>{@code getAmbientSound} is what {@code EntityLiving#playAmbientSound} plays at the
     * {@code getAmbientSoundInterval} rate, so returning null drops the idle groan without touching hurt or
     * death sounds -- those are separate methods, and silencing the entity entirely would make it feel
     * absent.
     */
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    /** The death sound, for the one path that still reaches it. */
    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_ZOMBIE_DEATH;
    }

    /** Reports its own type to the logger without a null check. */
    @Override
    public String getName() {
        return "Transcendence Zombie";
    }

    /** The entity never despawns on its own, whatever {@code EntityLiving}'s idle timer believes. */
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

    /**
     * Refuses any position write that moves this entity more than {@link #MAX_SINGLE_MOVE}.
     *
     * <p>Refuses silently rather than throwing: a mod that teleports the entity expects nothing, and
     * returning normally leaves it exactly where it was instead of half-moved. Momentum is zeroed too, so a
     * relocation attempt cannot be turned into a legitimately-looking drift.
     */
    private boolean guardAllows(double x, double y, double z) {
        // No guard while the entity still sits at the world origin. A spawn egg and this mod's own revival
        // both build the entity first and place it second, and that placement is longer than the limit; an
        // always-on guard rejected the game's own move and left every zombie at (0,0,0). A "first write"
        // flag failed too, because construction itself writes the origin. Testing the position removes that
        // ordering problem: an entity at exactly the origin has not been placed anywhere yet, so there is
        // no location to defend; once it is elsewhere the guard engages and stays engaged.
        boolean stillAtOrigin = posX == 0.0D && posY == 0.0D && posZ == 0.0D;
        boolean placing = stillAtOrigin && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing) {
            return true;
        }
        if (allowOneRelocation) {
            return true;
        }
        // The guard applies on both sides. It used to be server only, which made no sense: a client-side
        // move desynchronises the copy the player sees, so the entity stays put on the server and slides
        // away on screen, or the reverse. A rule about where this entity is has to hold wherever the entity
        // exists.
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

    /**
     * Keeps the watch list entry on wherever the zombie actually is.
     */
    private void recordPosition() {
        // A retired zombie never records itself; this is what made a wipe fail to stick. Emptying the list
        // is not enough on its own, because every position update runs this method and each still present
        // zombie wrote itself straight back in -- the sweep then found an entry with no entity, raised a
        // replacement, and that replacement recorded itself too.
        if (this.ownGeneration != generation) {
            return;
        }
        // Server only: the watch list is what the revival reads, and revival runs on the server, so a
        // client copy would be written and never read.
        if (!this.world.isRemote) {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUniqueID(), new Vec3d(posX, posY, posZ));
        }
    }

    /**
     * Runs {@code action} with the relocation guard off, then restores it.
     *
     * <p>Only this mod's own repair path needs it: that path has to place a freshly built zombie at the
     * position it replaces, which is by definition a jump longer than the guard allows.
     */
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

    /**
     * Set when the encounter is being wiped: a generation, not a boolean.
     *
     * <p>Each zombie records the generation it was born in; a wipe bumps the counter, which marks exactly
     * the zombies that existed before it, and anything created afterwards carries the new number. A plain
     * boolean cannot say that: it stays true forever, so every zombie the repair creates also removes itself
     * and the repair answers with another -- thousands of entities in one tick. The generation has no such
     * tail and needs no resetting.
     */
    private static volatile long generation;

    private long ownGeneration = generation;

    /** Starts a new generation, which marks every zombie that already exists. */
    public static void beginWipe() {
        ++generation;
    }

    /**
     * True when this zombie is dismissed and on its way out.
     *
     * <p>Public so the watch-list code can refuse to record it: a dismissed zombie must not re-enter the
     * list the wipe just emptied, or the repair rebuilds it and the encounter never ends.
     */
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

    /**
     * Finds the closest living entity that is not another Transcendence mob and lobs a fireball.
     *
     * <p>Creative and spectator players are skipped: an explosion-4 fireball is pure annoyance to someone
     * who cannot be hurt by it, and creative players are usually building.
     */
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

    /** True for entities the zombie should never lob a fireball at. */
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
