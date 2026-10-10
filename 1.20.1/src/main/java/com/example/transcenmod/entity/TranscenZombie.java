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

// vanilla zombie behaviour untouched: goals,navigation,targeting,movement all inherited
// adds a stat block and one attack,an explosion-4 fireball every second at the nearest living thing
// unremovable on purpose - remove() and setHealth() are no-ops so /kill and ordinary damage do nothing
// no setPos override so the mob's own navigation can still walk it around
public class TranscenZombie extends Zombie {

    // 20 ticks == one second
    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;
    // matches the follow range attribute,must stay in sync
    private static final double TARGET_RANGE = 1024.0D;

    private int fireballCooldown = 0;

    // 2 metres,walking and the mob's own navigation move in fractions of a block per tick so nothing legitimate trips this
    // /tp,another mod teleporting it,or the far-coordinate push that unloads the chunk and discards the entity - those do
    private static final double MAX_SINGLE_MOVE = 2.0D;

    // set while this mod places the entity itself,so the guard lets that one write through
    // a respawn jump always exceeds 2m: the new entity starts at the origin and is then moved to where the old one died
    private boolean allowOneRelocation;

    public TranscenZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    // setPos,absMoveTo,teleportTo and every other "move this entity" helper funnel into this method
    // so one guard here covers every relocation route,reflection and accesstransformer callers included
    // vanilla signature is (double,double,double) and final - the transformer drops the final from m_20219_
    // setPos(double,double,double) is final too and a different method,so overriding that instead does not work
    // refuses silently rather than throwing,a caller has no reason to expect anything
    // momentum is zeroed as well,so a blocked relocation cannot pass as drift
    @Override
    public void setPosRaw(double x, double y, double z) {
        // no guard at the world origin - nothing has been placed yet so there is nothing to defend
        // a spawn egg and the revival both build first and place second,and that placement is longer than the limit
        // an always-on guard rejected the game's own move and left the zombie at (0,0,0)
        boolean stillAtOrigin = this.getX() == 0.0D && this.getY() == 0.0D && this.getZ() == 0.0D;
        boolean placing = stillAtOrigin && !(x == 0.0D && y == 0.0D && z == 0.0D);
        if (placing) {
            super.setPosRaw(x, y, z);
            recordPosition();
            return;
        }

        // guard applies on both sides,a client only move desyncs the copy the player sees
        // server stays put while the screen slides away,or the reverse
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

    // keeps the watch-list entry on the last legitimate position so a revival ignores a forced teleport
    private void recordPosition() {
        // a retired zombie never records itself,without this a wipe does not stick
        // emptying the list is not enough - every position update writes every surviving zombie straight back in
        // the sweep then finds an entry with no entity,raises a replacement,which records itself too - remove,respawn,repeat
        if (this.ownGeneration != generation) {
            return;
        }
        // server only: the watch list is read by revival,which runs on the server,a client copy is never read
        if (!this.level().isClientSide) {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUUID(), new Vec3(this.getX(), this.getY(), this.getZ()));
        }
    }

    // runs action with the relocation guard off then restores it - used by the repair path placing a fresh zombie
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

    // legal only because the AccessTransformer drops final from LivingEntity#getMaxHealth
    // see the public-f line in META-INF/accesstransformer.cfg
    @Override
    public float getMaxHealth() {
        return 114514F;
    }

    @Override
    public void setHealth(float health) {
        // refuses to lose health,so the normal kill path never lands
    }

    @Override
    public void remove(RemovalReason reason) {
        // refuses removal,so /kill and ordinary death do nothing here
        // CustomEntityRemover bypasses this - it rewires the storages directly and is only used where removal is allowed
    }

    // isSunBurnTick is the one predicate vanilla consults before setting an entity alight,false removes the behaviour at source
    // putting the fire out afterwards would not help: this zombie refuses damage so it would only burn forever
    // a permanent flame obscures the model and litters fire around it
    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    // not this mod's doing,Zombie.finalizeSpawn rolls a chance to spawn small and every subclass inherits it
    // that produced one-block-tall variants among the full-size ones,wrong for a fixed model and a fixed health pool
    // both halves overridden,setBaby never sets the state and isBaby answers false whatever another path does
    @Override
    public void setBaby(boolean baby) {
        super.setBaby(false);
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    // getAmbientSound is what Mob#playAmbientSound plays on the getAmbientSoundInterval schedule,null removes the idle groan
    // hurt and death sounds are separate methods so they survive,silencing everything would make it feel absent
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    // encounter generation,bumping it retires everything already in the world
    // a boolean flag cannot do this - it stays true for the zombies the repair creates next,each removes itself on its next tick
    // the repair answers with another,and thousands of entities appear in one tick
    // a generation has no tail: each zombie carries the number it was born in and only older ones retire,nothing to reset
    private static volatile long generation;

    private long ownGeneration = generation;

    public static void beginWipe() {
        ++generation;
    }

    // true once the zombie is retired and on its way out,public so the watch-list code can refuse to record it
    // a retired zombie must not re-enter the list the wipe emptied,or the repair rebuilds it and the encounter never ends
    public boolean isRetired() {
        return this.ownGeneration != generation;
    }

    // final in vanilla and the only writer of removalReason,which is the only thing isRemoved() reads
    // that makes it the narrowest place to stop a removal - the death path,datapacks and other mods' sweeps all pass through
    // legal because the AccessTransformer drops final from m_142467_,see the public-f line in accesstransformer.cfg
    // remove() covers a different set of callers,the two together are what make the entity unremovable
    @Override
    public void setRemoved(RemovalReason reason) {
        // deliberately no super call,that would set the removal flag and stop rendering,ticking and every storage query
    }

    // a revival briefly stacks several zombies in one block and cramming damage kills them there
    // each death made the sweep raise a replacement into the same spot so it read as a duplication bug
    private static boolean isCramming(DamageSource source) {
        return source != null && "cramming".equals(source.getMsgId());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // returns false on purpose
        // hurtTime is the red overlay and the caller plays the hurt sound off the same result so true
        // triggers both,false is the only way to get a zombie that never flashes red or grunts when hit
        // only cramming is refused,everything else still reaches hurt() so another mod's weapon lands
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

        // super.setRemoved is statically bound so it bypasses this class's own refusal without weakening it
        // no other caller can reach it and nothing reflective is involved
        // no isClientSide check on purpose,removing only on the server leaves the client's copy standing on screen
        // both sides run this so both drop it
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

    // creative and spectator players are skipped,an explosion-4 fireball is pure annoyance to someone it cannot hurt
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
