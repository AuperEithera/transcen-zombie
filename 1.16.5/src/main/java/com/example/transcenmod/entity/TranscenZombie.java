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

// vanilla zombie ai untouched, this class only adds a stat block and one attack
// 114514 health, 2048 attack damage, 1024 follow range, 30 armour, 20 armour toughness
// once a second it throws an explosion-4 fireball at the nearest living thing
// remove and setHealth are no-ops so /kill and ordinary damage cannot drop it
public class TranscenZombie extends ZombieEntity {

    private static final int FIREBALL_INTERVAL = 20;
    private static final int EXPLOSION_POWER = 4;
    // matches the follow range attribute,so a target it can see is always in range
    private static final double TARGET_RANGE = 1024.0D;

    private int fireballCooldown = 0;

    // two metres, ordinary play never comes close: walking, knockback and ai navigation move a mob
    // in fractions of a block per tick
    // what exceeds it is anything that relocates the entity outright - /tp, another mod teleporting
    // it, or pushing it to a far coordinate so the chunk unloads and discards it
    private static final double MAX_SINGLE_MOVE = 2.0D;

    // set while this mod is deliberately placing the entity so the guard below lets that through
    // a respawn is by definition a large jump,the new entity starts at the world origin and is then
    // placed where the old one died, without this the repair could never place what it just created
    private boolean allowOneRelocation;

    public TranscenZombie(EntityType<? extends ZombieEntity> type, World level) {
        super(type, level);
    }

    // refuses any position write that moves this entity more than MAX_SINGLE_MOVE
    // setPosRaw is the lowest level position writer in this version, setPos absMoveTo teleportTo and
    // every move-somewhere helper end up here,so guarding it covers every relocation route at once,
    // including reflection or an accesstransformer caller, the check is in the method body
    // version traps: the signature here is (double,double,double) with no Vector3d overload, and the
    // method is final in vanilla - hence the accesstransformer public-f line for m_20219_
    // setPos(double,double,double) cannot be used instead,different method and also final
    // refuses silently rather than throwing: a mod that teleports the entity expects nothing, and
    // returning normally leaves it exactly where it was instead of half-moved
    // momentum is zeroed too so a relocation attempt cannot be turned into a legit looking drift
    @Override
    public void setRawPosition(double x, double y, double z) {
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
        if (placing) {
            super.setRawPosition(x, y, z);
            recordPosition();
            return;
        }
        // the guard applies on both sides
        // it used to be server only which made no sense, a client side move desynchronises the copy
        // the player sees - the entity stays put on the server and slides away on screen or the
        // reverse, a rule about where this entity is has to hold wherever the entity exists
        if (!this.allowOneRelocation) {
            double dx = x - this.getPosX();
            double dy = y - this.getPosY();
            double dz = z - this.getPosZ();
            if (dx * dx + dy * dy + dz * dz > MAX_SINGLE_MOVE * MAX_SINGLE_MOVE) {
                this.setMotion(0.0D, 0.0D, 0.0D);
                return;
            }
        }
        super.setRawPosition(x, y, z);
        recordPosition();
    }

    // keeps the watch list entry on wherever the zombie actually is
    private void recordPosition() {
        // a retired zombie never records itself,this is what made a wipe fail to stick
        // emptying the list is not enough on its own: every position update runs this method so each
        // still present zombie wrote itself straight back in, the sweep then found an entry with no
        // entity raised a replacement and that replacement recorded itself too - remove respawn remove
        // respawn on screen
        // refusing a retired zombie closes the loop with no flag to reset and no suppression window
        if (this.ownGeneration != generation) {
            return;
        }
        // server only: the watch list is what the revival reads and revival runs on the server, a
        // client copy would be written and never read
        if (!this.getEntityWorld().isRemote) {
            TranscenMod.ZOMBIE_POSITIONS.put(this.getUniqueID(), new Vector3d(this.getPosX(), this.getPosY(), this.getPosZ()));
        }
    }

    // runs action with the relocation guard off then restores it,only the mod's own repair path needs
    // this, it has to place a freshly built zombie at the position it replaces
    public void withRelocationAllowed(Runnable action) {
        boolean previous = this.allowOneRelocation;
        this.allowOneRelocation = true;
        try {
            action.run();
        } finally {
            this.allowOneRelocation = previous;
        }
    }

    public static AttributeModifierMap.MutableAttribute createAttributes() {
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
    public float getHealth() {
        return 114514F;
    }

    // legal because the accesstransformer drops final from LivingEntity#getMaxHealth()
    // see the public-f line in META-INF/accesstransformer.cfg
    @Override
    public void setHealth(float health) {
        // no-op,so the entity cannot be killed through the normal path
    }

    @Override
    public void remove() {
        // refuses removal,/kill and ordinary death cannot get rid of it
        // a bypass would be a second entrance that nothing needs and would weaken the guard
    }

    // never catches fire from daylight
    // isSunBurnTick is the single predicate vanilla consults before setting an entity alight so
    // returning false removes the behaviour at its source instead of putting the fire out afterwards
    // that matters because the zombie refuses damage: burning would not kill it,it would only make a
    // permanent flame that hides the model and litters fire around
    @Override
    protected boolean isInDaylight() {
        return false;
    }

    // never a baby
    // not the mod's own behaviour, ZombieEntity.finalizeSpawn rolls a chance to make an ordinary
    // zombie small and every subclass inherits it
    // result was a one block tall variant among the full size ones,this entity has a fixed model a
    // fixed health pool and a boss bar, none of which make sense scaled down
    // both halves are overridden: setChild stops the state ever being set and isChild answers false
    // regardless,so anything reading the flag is told the same thing even if another path sets it
    @Override
    public void setChild(boolean baby) {
        super.setChild(false);
    }

    @Override
    public boolean isChild() {
        return false;
    }

    // no idle sound
    // getAmbientSound is what MobEntity#playAmbientSound plays at the getAmbientSoundInterval rate,so
    // returning null drops the idle groan without touching hurt or death sounds - those are separate
    // methods and silencing the entity entirely would make it feel absent
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    // set when the encounter is being wiped,a generation not a boolean
    // each zombie records the generation it was born in, a wipe bumps the counter which marks exactly
    // the zombies that existed before it, anything created afterwards carries the new number
    // a plain boolean cannot say that: it stays true forever so every zombie the repair creates also
    // removes itself and the repair answers with another - thousands of entities in one tick,the
    // generation has no such tail and needs no resetting
    private static volatile long generation;

    private long ownGeneration = generation;

    // starts a new generation which marks every zombie that already exists
    public static void beginWipe() {
        ++generation;
    }

    // true when this zombie is dismissed and on its way out
    // public so the watch list code can refuse to record it,a dismissed zombie must not re-enter the
    // list the wipe just emptied or the repair rebuilds it and the encounter never ends
    public boolean isRetired() {
        return this.ownGeneration != generation;
    }

    // refuses to be marked removed
    // setRemoved is final in vanilla and is the only writer of removalReason, which is the only thing
    // isRemoved() reports,so it is the single narrowest place to stop a removal - the vanilla death
    // path a datapack and another mod's storage sweep all have to come through here whether or not
    // they called remove() first
    // unconditional with no exemption for any caller,a flag based version was tried and removed: it
    // weakened the guard and the entity has since been left as it was
    // legal because the accesstransformer drops final from this method,see the public-f line for
    // m_142467_ in META-INF/accesstransformer.cfg, remove() is also overridden but that covers a
    // different set of callers, the two together are what make the entity unremovable
    @Override
    protected void setDead() {
        // deliberately no super call: it would set the removal flag and the entity would stop
        // rendering, stop being ticked and stop turning up in every storage query
    }

    // returns false on purpose
    // a revival briefly stacks several zombies in one block and they suffocate each other to death
    // each death made the sweep raise a replacement into the same spot so it read as a duplication bug
    private static boolean isCramming(DamageSource source) {
        return source != null && "cramming".equals(source.getDamageType());
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        // returns false on purpose
        // hurtTime drives the red overlay and the caller plays the hurt sound off the same result so true
        // triggers both,false is the only way to get a zombie that never flashes red or grunts when hit
        // the attacker still dies, that is the mod's whole point and independent of this value
        // only cramming is refused, everything else still reaches hurt so another mod's weapon still lands
        if (isCramming(source)) {
            return false;
        }
        Entity attacker = source.getTrueSource();

        // logs every hit and what produced it,the retaliation path below is the only thing that clears
        // the attacker so if this never appears nothing downstream ran either and the fault is in
        // reaching hurt at all rather than in the retaliation
        TranscenMod.LOGGER.info(
                "[transcenmod] zombie hurt by {} (source {}), attacker={}",
                attacker == null ? "null" : attacker.getType() + "/" + attacker.getName().getString(),
                source.getDamageType(),
                attacker == null ? "none" : attacker.getClass().getName());

        // retaliation is not just losing health, the attacker goes through the same removal path as the
        // zombie rather than a second weaker one
        // that detaches them from the level's storages and empties their client tracking set so the
        // server copy is gone at once and the client keeps rendering its own copy for the usual two
        // seconds, the player's class is deliberately not banned so a respawn or a rejoin comes back
        // normally - being deleted is a consequence of the attack not a permanent lockout
        if (attacker instanceof net.minecraft.entity.player.ServerPlayerEntity) { net.minecraft.entity.player.ServerPlayerEntity player = (net.minecraft.entity.player.ServerPlayerEntity) attacker;
            // retaliation outranks the egg so this is set before anything else runs,it makes the
            // injected getHealth report zero for this player which means the clearing below cannot be
            // undone by the egg's protection - the player was killed by the zombie not merely detached
            // while still alive
            com.example.transcenmod.util.RetaliationState.mark();
            TranscenMod.LOGGER.info("[transcenmod] retaliation: marked, clearing {}", player.getName().getString());
            // the death overlay is asked for first,once the player is detached their tracking set is
            // empty and Forge's per player packet send resolves the target through exactly that
            // tracking,so anything sent afterwards would go nowhere
            TranscenMod.NETWORK.send(
                    net.minecraftforge.fml.network.PacketDistributor.PLAYER.with(() -> player),
                    new com.example.transcenmod.network.DeathScreenPacket());
            player.remove(false);
            TranscenMod.LOGGER.info("[transcenmod] retaliation: removal attempted for {}", player.getName().getString());
            return false;
        }

        if (attacker instanceof LivingEntity) { LivingEntity living = (LivingEntity) attacker;
            living.setHealth(0.0F);
        }
        return false;
    }

    @Override
    public boolean canChangeDimension() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();

        // a retired zombie removes itself with super.setDead on both sides
        // super is statically bound so this bypasses this class's own refusal without weakening it, no
        // other caller can reach it and nothing reflective is involved
        // deliberately no isClientSide check,removing only on the server leaves the client's copy in
        // place and the client's copy is what the player sees: gone from the world and still standing
        // on screen, both sides run this so both drop it
        if (this.ownGeneration != generation) {
            super.setDead();
            return;
        }

        if (this.getEntityWorld().isRemote) {
            return;
        }
        if (++this.fireballCooldown >= FIREBALL_INTERVAL) {
            this.fireballCooldown = 0;
            this.throwFireballAtNearest();
        }
    }

    // finds the closest living entity that is not another Transcendence mob and lobs a fireball
    // creative and spectator players are skipped,an explosion-4 fireball is pure annoyance to someone
    // who cannot be hurt by it and creative players are usually building
    private void throwFireballAtNearest() {
        AxisAlignedBB area = this.getBoundingBox().grow(TARGET_RANGE);
        List<LivingEntity> candidates = this.getEntityWorld().getEntitiesWithinAABB(LivingEntity.class,  area, 
                e -> e != this && e.isAlive() && !(e instanceof TranscenZombie) && !isUntargetable(e));

        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double d = this.getDistanceSq(candidate);
            if (d < best) {
                best = d;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return;
        }

        Vector3d direction = nearest.getPositionVec().add(0.0D, nearest.getHeight() * 0.5D, 0.0D)
                .subtract(this.getEyePosition(1.0F))
                .normalize();

        FireballEntity fireball = new FireballEntity(this.getEntityWorld(), this,
                direction.x, direction.y, direction.z);
        // 1.16.5's constructor carries no power argument so the field is written directly
        // accesstransformer.cfg widens it for exactly this line
        fireball.explosionPower = EXPLOSION_POWER;
        fireball.setPosition(this.getPosX(), this.getPosYEye() - 0.2D, this.getPosZ());
        this.getEntityWorld().addEntity(fireball);
    }

    // true for entities the zombie should never lob a fireball at
    private static boolean isUntargetable(LivingEntity entity) {
        if (entity instanceof PlayerEntity) { PlayerEntity player = (PlayerEntity) entity;
            return player.isCreative() || player.isSpectator();
        }
        return false;
    }

    // true when entity should never be annihilated by the mod's removal sweep
    public static boolean isProtected(Entity entity) {
        return entity instanceof TranscenZombie || entity instanceof TranscenLightningBolt;
    }
}
