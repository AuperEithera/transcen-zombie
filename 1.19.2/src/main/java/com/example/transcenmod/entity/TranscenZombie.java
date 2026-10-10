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
        // The guard does not apply while the entity is still sitting at the world origin.
        //
        // Both a spawn egg and this mod's own revival build the entity first and place it second, and that
        // placement is longer than the limit -- so a guard that was always on rejected the game's own
        // move and left every zombie at (0,0,0). The original fix was a flag set on the first write, which
        // failed for the same reason in a subtler way: construction itself writes the origin, so the flag
        // was already set before the real placement arrived, and that placement was refused too.
        //
        // Testing the position rather than counting writes removes that ordering problem. An entity at
        // exactly the origin has not been placed anywhere yet, so there is no location to defend and the
        // move is allowed; once it is anywhere else the guard engages and stays engaged.
        boolean stillAtOrigin = this.getX() == 0.0D && this.getY() == 0.0D && this.getZ() == 0.0D;
        boolean placing = stillAtOrigin
                && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing) {
            super.setPosRaw(x, y, z);
            recordPosition();
            return;
        }
        // The guard applies on both sides.
        //
        // It used to be server-only, which made no sense: the point is that the zombie cannot be moved
        // away from where it belongs, and a client-side move desynchronises the copy the player actually
        // sees -- the entity stays put on the server and slides away on screen, or the reverse. A rule
        // about where this entity is has to hold wherever the entity exists.
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

    /** Keeps the watch-list entry on wherever the zombie actually is. */
    private void recordPosition() {
        // A retired zombie never records itself.
        //
        // This is what made a wipe fail to stick, and it is why the symptom was a flicker rather than
        // nothing happening. Emptying the watch list is not enough on its own: every position update runs
        // this method, so each still-present zombie wrote itself straight back into the list the wipe had
        // just emptied. The sweep then found an entry with no entity, raised a replacement, and that
        // replacement recorded itself too -- remove, respawn, remove, respawn, on screen.
        //
        // Refusing to record a retired zombie closes the loop without a flag to reset and without a
        // suppression window: the generation already says this entity is on its way out, and it cannot
        // talk its way back in.
        if (this.ownGeneration != generation) {
            return;
        }
        // Server only: the watch list is what the revival reads, and revival runs on the server. A client
        // copy would be written and never read.
        if (!this.getLevel().isClientSide) {
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
        // Refuses removal, so /kill and ordinary death cannot get rid of it.
        //
        // No back door here, unlike setRemoved. CustomEntityRemover does not come through this method --
        // it rewires the storages directly and starts at setRemoved -- so a bypass would open a second
        // entrance that nothing needs, weakening the guard for no gain.
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
     * <p>Not the mod's own behaviour: {@code Zombie.finalizeSpawn} rolls a chance to make an ordinary
     * zombie small, and every subclass inherits it. The result here was a one-block-tall variant appearing
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
     * Set when the encounter is being wiped. A generation, not a boolean.
     *
     * <p>Each zombie records the generation it was born in. A wipe bumps the counter, which marks exactly
     * the zombies that existed before it; anything created afterwards carries the new number and is
     * untouched. A plain boolean cannot express that: it stays true forever, so every zombie the repair
     * creates afterwards also removes itself, and the repair answers by creating another -- thousands of
     * entities in a single tick. The generation has no such tail and needs no resetting.
     */
    private static volatile long generation;

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
     * <p>Unconditional, with no exemption for any caller. A version that let the mod's own teardown
     * through by consulting a flag was tried and removed: it weakened the guard, and the entity has since
     * been left exactly as it was while the wipe was rewritten not to need it.
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
     * Turns away the damage that was killing revived zombies and making them look duplicated.
     *
     * <p>The log gave this away with {@code zombie hurt by null (source cramming)}: entities crammed into
     * the same block take continuous suffocation damage, and when several zombies briefly share a spot --
     * which is exactly what a revival does -- they damage each other to death. Each death made the sweep
     * find the id missing and raise a replacement, which crowded the spot again. That cycle is what looked
     * like a duplication bug and what made the client stutter; the entity count was a symptom, not a
     * cause, and the earlier work on UUIDs, chunk loading and spawn positions was aimed at the wrong
     * thing entirely.
     *
     * <p>Only cramming is refused. Everything else still goes through {@link #hurt}, so a legitimate
     * removal by another mod's weapon is unaffected.
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

        // Reports every hit and what produced it. The retaliation path below is the only thing that
        // clears the attacker, so if this never appears then nothing downstream can have run either, and
        // the fault is in reaching hurt at all rather than in the retaliation itself.
        TranscenMod.LOGGER.info(
                "[transcenmod] zombie hurt by {} (source {}), attacker={}",
                attacker == null ? "null" : attacker.getType() + "/" + attacker.getName().getString(),
                source.getMsgId(),
                attacker == null ? "none" : attacker.getClass().getName());

        // The retaliation is not just losing health: the attacker is torn out of the world outright.
        //
        // CustomEntityRemover is the same surgery the mod uses on foreign entities, and using it here
        // means the player is cleared by the identical mechanism rather than by a second, weaker one.
        // Note what it does and does not do to a player: it detaches them from the level's storages and
        // empties their client-tracking set, so the server copy is gone at once and the client keeps
        // rendering its own copy for the usual two seconds. It deliberately does not ban the player's
        // class, so a player who respawns or rejoins comes back normally -- being deleted is a
        // consequence of the attack, not a permanent lockout.
        if (attacker instanceof net.minecraft.server.level.ServerPlayer) { net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) attacker;
            // Retaliation outranks the egg, so this is set before anything else runs. It makes the
            // injected getHealth report zero for this player, which means the clearing below cannot be
            // undone by the egg's protection -- the player was killed by the zombie, not merely detached
            // while still alive.
            com.example.transcenmod.util.RetaliationState.mark();
            TranscenMod.LOGGER.info("[transcenmod] retaliation: marked, clearing {}", player.getName().getString());
            // The death overlay is asked for first. Once the player has been detached their tracking set
            // is empty, and Forge's per-player packet send resolves the target through exactly that
            // tracking, so anything sent afterwards would go nowhere.
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

        if (this.getLevel().isClientSide) {
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
