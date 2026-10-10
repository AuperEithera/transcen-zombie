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

// vanilla zombie AI throughout, the only additions are the stat block and the fireball
// stats live in createAttributes, draw them from the code rather than trusting a second copy
// remove and setHealth are no-ops, so /kill and ordinary damage cannot delete it
public class TranscenZombie extends Zombie {

    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;
    private static final double TARGET_RANGE = 1024.0D;

    private int fireballCooldown = 0;

    // 2 blocks per write. the AI, knockback and walking all stay far below it, what crosses it is a
    // relocation: /tp, another mod teleporting the entity, a push to a far coordinate so the chunk
    // unloads and discards it
    private static final double MAX_SINGLE_MOVE = 2.0D;

    // a respawn is by definition a big jump - the replacement spawns at the world origin and is then
    // moved to where the old one died, usually far past 2 blocks - so the repair needs one pass through
    private boolean allowOneRelocation;

    public TranscenZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    // guard the lowest-level position writer: setPos,absMoveTo,teleportTo and every other move helper
    // funnel through setPosRaw, so one body check covers every relocation route including reflection
    // 1.19.2 signature is (double,double,double) and vanilla final, hence the accesstransformer on
    // m_20219_ - setPos(double,double,double) is final here and a different method, cannot be used
    // refuses silently instead of throwing and zeroes momentum,drifting a relocation into place is out
    @Override
    public void setPosRaw(double x, double y, double z) {
        // at exactly the origin the entity has not been placed yet, so there is nothing to defend and
        // the move goes through, anywhere else the guard engages
        // a spawn egg and the revival both build first and place second, and that placement is longer
        // than the limit, so an always-on guard left every zombie at 0,0,0
        // first-write flag failed too: construction writes the origin, so the flag was already set when
        // the real placement arrived
        boolean stillAtOrigin = this.getX() == 0.0D && this.getY() == 0.0D && this.getZ() == 0.0D;
        boolean placing = stillAtOrigin
                && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing) {
            super.setPosRaw(x, y, z);
            recordPosition();
            return;
        }
        // both sides, not server only. a client move desyncs the copy the player sees, the entity stays
        // put on the server and slides away on screen, or the reverse
        if (!this.allowOneRelocation) {
            double dx = x - this.getX();
            double dy = y - this.getY();
            double dz = z - this.getZ();
            if (dx * dx + dy * dy + dz * dz > MAX_SINGLE_MOVE * MAX_SINGLE_MOVE) {
                this.setDeltaMovement(0.0D, 0.0D, 0.0D);
                return;
            }
        }
        super.setPosRaw(x, y, z);
        recordPosition();
    }

    private void recordPosition() {
        // a retired zombie must not record itself, this is what made a wipe fail and why the symptom was
        // a flicker rather than nothing: emptying the list is not enough while every position update
        // writes the zombie straight back in, so the sweep finds an entry with no entity, raises a
        // replacement and that replacement records itself too - remove, respawn, remove, respawn
        if (this.ownGeneration != generation) {
            return;
        }
        // server only, the revival is what reads this list and it runs on the server
        if (!this.getLevel().isClientSide) {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUUID(), new Vec3(this.getX(), this.getY(), this.getZ()));
        }
    }

    // repair path only, a freshly built zombie has to be placed where the one it replaces died
    public void withRelocationAllowed(Runnable action) {
        boolean previous = this.allowOneRelocation;
        this.allowOneRelocation = true;
        try {
            action.run();
        } finally {
            this.allowOneRelocation = previous;
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 114514.0D)
                .add(Attributes.ATTACK_DAMAGE, 2048.0D)
                .add(Attributes.FOLLOW_RANGE, 1024.0D)
                .add(Attributes.ARMOR, 30.0D)
                .add(Attributes.ARMOR_TOUGHNESS, 20.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    public float getHealth() {
        return 114514F;
    }

    // vanilla getMaxHealth is final, the public-f line in META-INF/accesstransformer.cfg drops it
    @Override
    public float getMaxHealth() {
        return 114514F;
    }

    @Override
    public void setHealth(float health) {
        // refuses to lose health, the normal kill path cannot finish
    }

    @Override
    public void remove(RemovalReason reason) {
        // refuses removal, so /kill and ordinary death cannot delete it
        // no back door here unlike setRemoved: CustomEntityRemover rewires the storages directly and
        // starts at setRemoved, so a bypass would be a second entrance nothing uses
    }

    // isSunBurnTick is the one predicate vanilla consults before setting an entity alight, so false
    // removes the behaviour at the source
    // the zombie refuses damage, so burning would not kill it, only leave a permanent flame over the
    // model and fire on the ground
    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    // not this mod's doing: Zombie.finalizeSpawn rolls a chance to make an ordinary zombie small and
    // every subclass inherits it, which put a 1 block tall variant among the full size ones
    // the model, the health pool and the boss bar are all fixed and none of them survive being scaled
    // down
    // both halves overridden, setBaby stops the state being set and isBaby denies it to anything that
    // reads the flag - size,speed,render scale,a spawn egg's own checks
    @Override
    public void setBaby(boolean baby) {
        super.setBaby(false);
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    // Mob#playAmbientSound is the only caller, so null kills the idle groan and leaves hurt and death
    // sounds alone - they are separate methods and muting them makes the entity feel absent
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    // a generation,not a boolean. each zombie stores the generation it was born in and a wipe bumps the
    // counter, so exactly the zombies that existed before it are marked
    // a boolean stays true forever, so every zombie the repair creates afterwards removes itself too
    // and the repair answers with another - thousands of entities in a single tick
    private static volatile long generation;

    private long ownGeneration = generation;

    // marks every zombie that already exists
    public static void beginWipe() {
        ++generation;
    }

    // public so recordPosition can refuse a retired zombie, one that re-enters the list the wipe just
    // emptied gets rebuilt by the repair and the encounter never ends
    public boolean isRetired() {
        return this.ownGeneration != generation;
    }

    // setRemoved is final in vanilla and the only writer of removalReason, which is the only thing
    // isRemoved reports - so this is the narrowest place to stop a removal
    // every path that marks an entity gone comes through here, the vanilla death path,a datapack,
    // another mod's storage sweep, with or without a remove() call first
    // unconditional, no exemption for any caller: a flag that let the mod's own teardown through was
    // tried and removed for weakening the guard, the wipe was rewritten not to need it
    // the accesstransformer public-f line for m_142467_ drops the final
    @Override
    public void setRemoved(RemovalReason reason) {
        // no super call on purpose, it would set the removal flag and the entity would stop rendering,
        // being ticked and being found by every storage query
    }
    // a revival briefly shares a spot so the zombies take suffocation damage and kill each other
    // each death made the sweep raise a replacement into the same crowded spot
    private static boolean isCramming(DamageSource source) {
        return source != null && "cramming".equals(source.getMsgId());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // returns false on purpose
        // hurtTime drives the red overlay and the caller plays the hurt sound off the same return,true
        // triggers both,false is the only way to stop the flash and the grunt
        // the attacker still dies, that is this mod's whole point and independent of this value
        if (isCramming(source)) {
            return false;
        }
        Entity attacker = source.getEntity();

        // every hit is logged because the retaliation below is the only thing that clears the attacker:
        // no line here means nothing downstream ran, the fault is in reaching hurt at all
        TranscenMod.LOGGER.info(
                "[transcenmod] zombie hurt by {} (source {}), attacker={}",
                attacker == null ? "null" : attacker.getType() + "/" + attacker.getName().getString(),
                source.getMsgId(),
                attacker == null ? "none" : attacker.getClass().getName());

        // not just losing health, the attacker is torn out of the world: CustomEntityRemover detaches
        // them from the level's storages and empties their client-tracking set, so the server copy is
        // gone at once while the client keeps drawing its own for about 2s
        // the player's class is never banned, a respawn or rejoin comes back normally - being deleted
        // is a consequence of the attack, not a permanent lockout
        if (attacker instanceof net.minecraft.server.level.ServerPlayer) { net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) attacker;
            // set before anything else,must outrank the spawn egg: the injected getHealth then reports
            // zero, so the egg's protection cannot undo the clearing below
            com.example.transcenmod.util.RetaliationState.mark();
            TranscenMod.LOGGER.info("[transcenmod] retaliation: marked, clearing {}", player.getName().getString());
            // ask for the death overlay first: once the player is detached their tracking set is empty
            // and Forge's per-player send resolves the target through that tracking, so a later packet
            // goes nowhere
            TranscenMod.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new com.example.transcenmod.network.DeathScreenPacket());
            com.example.transcenmod.util.CustomEntityRemover.removeEntity(player);
            TranscenMod.LOGGER.info("[transcenmod] retaliation: removal attempted for {}", player.getName().getString());
            return false;
        }

        if (attacker instanceof LivingEntity) { LivingEntity living = (LivingEntity) attacker;
            living.setHealth(0.0F);
        }
        return false;
    }

    @Override
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();

        // a retired zombie tears itself down through super.setRemoved, which is statically bound so it
        // bypasses this class's refusal without weakening it - no other caller can reach it
        // no isClientSide check on purpose: a server-only removal leaves the client copy standing on
        // screen while the entity is gone from the world
        if (this.ownGeneration != generation) {
            super.setRemoved(RemovalReason.KILLED);
            return;
        }

        if (this.getLevel().isClientSide) {
            return;
        }
        if (++this.fireballCooldown >= FIREBALL_INTERVAL) {
            this.fireballCooldown = 0;
            this.throwFireballAtNearest();
        }
    }

    // creative and spectator players are skipped, an explosion 4 fireball only annoys someone who
    // cannot be hurt by it and creative players are usually building
    private void throwFireballAtNearest() {
        AABB area = this.getBoundingBox().inflate(TARGET_RANGE);
        List<LivingEntity> candidates = this.getLevel().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != this && e.isAlive() && !(e instanceof TranscenZombie) && !isUntargetable(e));

        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double d = this.distanceToSqr(candidate);
            if (d < best) {
                best = d;
                nearest = candidate;
            }
        }
        if (nearest == null) {
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

    private static boolean isUntargetable(LivingEntity entity) {
        if (entity instanceof Player) { Player player = (Player) entity;
            return player.isCreative() || player.isSpectator();
        }
        return false;
    }

    public static boolean isProtected(Entity entity) {
        return entity instanceof TranscenZombie || entity instanceof TranscenLightningBolt;
    }
}
