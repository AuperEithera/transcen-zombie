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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The Transcendence Zombie.
 *
 * <p>It runs on completely vanilla zombie AI -- goal selectors, navigation, target selection and
 * movement are all inherited and untouched. What this class adds is a stat block and one attack:
 *
 * <ul>
 *   <li>114514 health, 2048 attack damage, 1024 follow range, 30 armour, 20 armour toughness;</li>
 *   <li>once every second it throws an explosion-4 fireball at the nearest living thing.</li>
 * </ul>
 *
 * <p>It is also deliberately unremovable: {@code remove} and {@code setHealth} are no-ops, so
 * {@code /kill} and ordinary damage cannot get rid of it. Nothing here touches {@code setPos}, so
 * the AI is free to walk it around.
 */
public class TranscenZombie extends Zombie {

    /** 20 ticks == one second. */
    private static final int FIREBALL_INTERVAL = 20;
    /** LargeFireball explosion radius. */
    private static final int EXPLOSION_POWER = 4;
    /** Search radius for the nearest target, matching the follow range attribute. */
    private static final double TARGET_RANGE = 1024.0D;

    private int fireballCooldown = 0;

    /**
     * How far this entity may be moved in a single position write before the move is refused.
     *
     * <p>Two metres. Ordinary play never comes close: walking, knockback and the AI's own navigation
     * all move a mob in fractions of a block per tick. What exceeds it is anything that relocates the
     * entity outright -- {@code /tp}, another mod teleporting it away, or the common trick of pushing
     * an entity to a far-away coordinate so the chunk system unloads and discards it.
     */
    private static final double MAX_SINGLE_MOVE = 2.0D;

    /**
     * Set while this mod is deliberately placing the entity, so the guard below lets that placement
     * through.
     *
     * <p>Needed because a respawn is by definition a large jump: the new entity starts at the world
     * origin and is then positioned where the old one died, which is usually far more than two metres.
     * Without this the repair could never place the zombie it just created.
     */
    private boolean allowOneRelocation;

    public TranscenZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    /**
     * Refuses any position write that would move this entity more than {@link #MAX_SINGLE_MOVE}.
     *
     * <p>{@code setPosRaw} is the lowest-level position writer in this version, and {@code setPos},
     * {@code absMoveTo}, {@code teleportTo} and every "move this entity somewhere" helper end up here.
     * Guarding it therefore covers every relocation route at once, including one that arrives through
     * reflection or an AccessTransformer, because the check lives in the method body rather than in
     * the caller.
     *
     * <p>Two details of this version matter and both were got wrong first time round. The signature
     * here is {@code (double, double, double)}, not a {@code Vec3} overload, and the method is
     * {@code final} in vanilla -- which is why the AccessTransformer drops the final for
     * {@code m_20219_} and why the original draft's simpler approach of overriding
     * {@code setPos(double,double,double)} cannot be used: in this version {@code setPos} is final and
     * a different method entirely.
     *
     * <p>Silently refuses rather than throwing: a mod that teleports the entity has no reason to expect
     * anything, and returning normally leaves it exactly where it was instead of half-moved. Momentum
     * is zeroed too, so a relocation attempt cannot be turned into a legitimate-looking drift.
     */
    @Override
    public void setPosRaw(double x, double y, double z) {
        // The guard does not apply while the entity is still at the world origin.
        //
        // A spawn egg and the revival both build the entity first and place it second, and that placement
        // is far longer than the limit -- so a guard that was always on rejected the game's own move and
        // left the zombie at (0,0,0). An entity sitting at exactly the origin has not been placed anywhere
        // yet, so there is no location to defend and the move is allowed; once it is anywhere else the
        // guard engages and stays engaged.
        boolean stillAtOrigin = this.getX() == 0.0D && this.getY() == 0.0D && this.getZ() == 0.0D;
        boolean placing = stillAtOrigin && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing) {
            super.setPosRaw(x, y, z);
            recordPosition();
            return;
        }

        // The guard applies on both sides. A client-side move desynchronises the copy the player actually
        // sees -- the entity stays put on the server and slides away on screen, or the reverse -- so a
        // rule about where this entity is has to hold wherever the entity exists.
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

    /**
     * Keeps the watch-list entry pointing at wherever the zombie actually is now, so a revival puts it
     * back at the last place it legitimately stood rather than at a position a teleport tried to force
     * on it.
     */
    private void recordPosition() {
        // A retired zombie never records itself.
        //
        // This is what made a wipe fail to stick, and why the symptom was a flicker rather than nothing
        // happening. Emptying the watch list is not enough on its own: every position update runs this
        // method, so each still-present zombie wrote itself straight back into the list the wipe had just
        // emptied. The sweep then found an entry with no entity, raised a replacement, and that
        // replacement recorded itself too -- remove, respawn, remove, respawn, on screen.
        if (this.ownGeneration != generation) {
            return;
        }
        // Server only: the watch list is what the revival reads, and revival runs on the server. A client
        // copy would be written and never read.
        if (!this.level().isClientSide) {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUUID(), new Vec3(this.getX(), this.getY(), this.getZ()));
        }
    }

    /**
     * Runs {@code action} with the relocation guard disabled, then restores it.
     *
     * <p>Used by this mod's own repair path, which has to place a freshly built zombie at the position
     * it is replacing.
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

    /**
     * Legal because the AccessTransformer removes {@code final} from
     * {@code LivingEntity#getMaxHealth()}; see the {@code public-f} line in
     * META-INF/accesstransformer.cfg.
     */
    @Override
    public float getMaxHealth() {
        return 114514F;
    }

    @Override
    public void setHealth(float health) {
        // Refuses to lose health, so the entity cannot be killed through the normal path.
    }

    @Override
    public void remove(RemovalReason reason) {
        // Refuses removal, so /kill and ordinary death cannot get rid of it. Note that
        // CustomEntityRemover does not go through this method; it rewires the storages directly and
        // is used only on entities the mod is allowed to remove.
    }

    /**
     * Never catches fire from daylight.
     *
     * <p>{@code isSunBurnTick} is the single predicate vanilla consults before setting an entity
     * alight, so returning {@code false} removes the behaviour at its source rather than putting the
     * fire out afterwards. That matters here because the zombie refuses damage: burning would not
     * kill it, it would only make it a permanent flame that obscures the model and litters the area
     * with fire.
     */
    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    /**
     * Never a baby.
     *
     * <p>Not this mod's own behaviour: {@code Zombie.finalizeSpawn} rolls a chance to make an ordinary
     * zombie small, and every subclass inherits it. The result was a one-block-tall variant appearing
     * among the full-size ones, which is not what this entity is meant to be -- it has a fixed model, a
     * fixed health pool and a boss bar, none of which make sense scaled down.
     *
     * <p>Both halves are overridden. {@code setBaby} stops the state ever being set, and {@code isBaby}
     * answers false regardless, so anything that reads the flag -- size, speed, the rendering scale, a
     * spawn egg's own checks -- is told the same thing even if some other path sets it behind this class.
     */
    @Override
    public void setBaby(boolean baby) {
        super.setBaby(false);
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    /**
     * No idle sound.
     *
     * <p>{@code getAmbientSound} is what {@code Mob#playAmbientSound} plays at the interval
     * {@code getAmbientSoundInterval} reports, so returning {@code null} removes the idle groan
     * without touching hurt or death sounds -- those are separate methods, and silencing the entity
     * entirely would make it feel absent.
     */
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    /**
     * The current encounter generation. Bumping it retires everything already in the world.
     *
     * <p><b>Why a generation and not a flag.</b> A boolean cannot express this. It stays true forever, so
     * every zombie created afterwards -- including the ones the repair makes -- reads it on its next tick
     * and removes itself at once; the repair then answers by creating another, and thousands of entities
     * appear in a single tick. A generation has no such tail: each zombie records the number it was born
     * in, a wipe bumps the counter, and only the zombies that existed before the bump are affected.
     * Anything created afterwards carries the new number and is untouched. There is nothing to reset.
     */
    private static volatile long generation;

    /** The generation this zombie belongs to. */
    private long ownGeneration = generation;

    /** Starts a new generation, marking every zombie that already exists. */
    public static void beginWipe() {
        ++generation;
    }

    /**
     * True when this zombie has been dismissed and is on its way out.
     *
     * <p>Public so the watch-list code can refuse to record it. A dismissed zombie must not be able to
     * re-enter the list the wipe just emptied, or the repair rebuilds it and the encounter never ends.
     */
    public boolean isRetired() {
        return this.ownGeneration != generation;
    }

    /**
     * Refuses to be marked removed.
     *
     * <p>{@code setRemoved} is {@code final} in vanilla and is the <b>only</b> writer of the
     * {@code removalReason} field, which in turn is the only thing {@code isRemoved()} reports. That
     * makes it the single narrowest place to stop a removal: everything that ultimately marks an
     * entity as gone -- the vanilla death path, a datapack, another mod's storage sweep -- has to come
     * through here, whether or not it bothered to call {@code remove()} first.
     *
     * <p>Legal because the AccessTransformer drops {@code final} from this method; see the
     * {@code public-f} line for {@code m_142467_} in META-INF/accesstransformer.cfg. {@code remove()}
     * is also overridden, but that covers a different set of callers -- the two together are what make
     * the entity unremovable rather than merely hard to kill.
     */
    @Override
    public void setRemoved(RemovalReason reason) {
        // Deliberately does not call super: calling it would set the removal flag and the entity
        // would stop rendering, being ticked, and being found by every storage query.
    }

    /**
     * Returns {@code false} on purpose.
     *
     * <p>{@code LivingEntity#hurt} is what produces the hurt feedback: {@code hurtTime} drives the
     * red damage overlay while burning, and the caller plays the hurt sound off the same result. A
     * {@code true} return is exactly what triggers both, so reporting "no damage taken" is the only
     * way to get a zombie that neither flashes red nor grunts when it is burning or being hit.
     *
     * <p>The attacker still dies; that is the mod's whole point and independent of this return
     * value.
     */
    /**
     * Turns away the damage that entities packed into one block deal each other.
     *
     * <p>A revival briefly puts several zombies in the same spot, and cramming damage would kill them
     * there. Each death made the sweep find the id missing and raise a replacement, which crowded the spot
     * again -- a cycle that reads as a duplication bug. Only cramming is refused; everything else still
     * goes through {@link #hurt}, so a legitimate removal by another mod's weapon is unaffected.
     */
    private static boolean isCramming(DamageSource source) {
        return source != null && "cramming".equals(source.getMsgId());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isCramming(source)) {
            return false;
        }
        Entity attacker = source.getEntity();
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

        // A retired zombie removes itself, with super.setRemoved, on both sides.
        //
        // super is statically bound, so this bypasses this class's own refusal without weakening it --
        // no other caller can reach it, and nothing reflective is involved. The teardown belongs to the
        // entity.
        //
        // Deliberately no isClientSide check. Removing only on the server leaves the client's copy in
        // place, and the client's copy is what the player sees: the entity is gone from the world and
        // still standing on screen. Both sides run this, so both drop it.
        if (this.ownGeneration != generation) {
            super.setRemoved(RemovalReason.KILLED);
            return;
        }

        if (this.level().isClientSide) {
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
     * <p>Creative and spectator players are skipped: an explosion-4 fireball is pure annoyance to
     * someone who cannot be hurt by it, and creative players are usually building.
     */
    private void throwFireballAtNearest() {
        AABB area = this.getBoundingBox().inflate(TARGET_RANGE);
        List<LivingEntity> candidates = this.level().getEntitiesOfClass(LivingEntity.class, area,
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

        LargeFireball fireball = new LargeFireball(this.level(), this,
                direction.x, direction.y, direction.z, EXPLOSION_POWER);
        fireball.setPos(this.getX(), this.getEyeY() - 0.2D, this.getZ());
        this.level().addFreshEntity(fireball);
    }

    /** True for entities the zombie should never lob a fireball at. */
    private static boolean isUntargetable(LivingEntity entity) {
        if (entity instanceof Player) { Player player = (Player) entity;
            return player.isCreative() || player.isSpectator();
        }
        return false;
    }

    /** True when {@code entity} should never be annihilated by the mod's removal sweep. */
    public static boolean isProtected(Entity entity) {
        return entity instanceof TranscenZombie || entity instanceof TranscenLightningBolt;
    }
}
