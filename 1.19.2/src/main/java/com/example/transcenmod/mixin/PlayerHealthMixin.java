package com.example.transcenmod.mixin;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.util.RetaliationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports full health for a player carrying this mod's spawn egg, and zero while the zombie's
 * retaliation is in progress.
 *
 * <p>This is the layer that actually makes the egg carrier unkillable, and the reason the earlier
 * event-based attempts kept failing.
 *
 * <p><b>Why nothing at the event layer was enough.</b> The red lightning runs
 * {@code victim.hurt(source, 1919810)} and then, on the very next line,
 * {@code victim.setHealth(0)}. Those are two adjacent statements in one method, so there is no point
 * between them at which an event handler can run. {@code LivingHurtEvent} fires inside the first call
 * and is cancelled successfully, and the {@code setHealth(0)} afterwards writes zero anyway. Likewise
 * {@code LivingEntity#kill}, which is what {@code /kill} calls, does
 * {@code hurt(DamageSource.OUT_OF_WORLD, Float.MAX_VALUE)} -- damage that can be cancelled, followed by
 * nothing that can be observed. Every one of these writes bypasses the damage pipeline, so a hook on
 * the damage pipeline cannot see them.
 *
 * <p><b>Why the stored health no longer decides anything.</b> Vanilla asks {@code getHealth() <= 0} to
 * decide whether a living entity is dead, and it asks repeatedly -- once per tick, again in the death
 * check, and once more on the client when it decides whether to raise the death screen. The stored
 * field can sit at zero; as long as every one of those questions is answered with a living value, no
 * death decision is ever reached and no death screen is ever requested. Pinning the stored value races
 * against those questions; answering the question itself does not.
 *
 * <p><b>Why the target is {@code LivingEntity}, not {@code Player}.</b> Not a stylistic choice.
 * {@code getHealth} is <i>declared</i> on {@code LivingEntity} and merely inherited by {@code Player},
 * and Mixin resolves an injection target against the methods a class declares in its own bytecode --
 * inherited methods are not candidates. Targeting {@code Player} fails outright with "could not find
 * any targets matching", and because the config is marked required that failure is fatal at startup.
 * The player check happens inside the handler instead.
 *
 * <p>Returning a fixed 20 rather than "at least 1" is deliberate: the request is that the carrier reads
 * as full health, so a health bar, a damage tint or a mod that scales something off current health all
 * see a consistent, healthy player rather than one permanently on the brink.
 *
 * <p>Scoped to the egg being carried. With no egg in the inventory the method is untouched and the
 * player is entirely normal, so this cannot leak into a state the player cannot see or undo.
 */
@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin {

    /** Full health, as a vanilla player has. */
    private static final float TRANSCENMOD_FULL_HEALTH = 20.0F;

    /**
     * Whether the first-call report has already been emitted.
     *
     * <p>Deliberately <b>not</b> {@code @Unique}. A {@code @Unique} static field is merged into the
     * target class, so there is exactly one copy for the whole game -- which meant the first five calls,
     * all of which happened to be server-side, consumed the entire budget and the client never reported
     * anything. That looked exactly like "the injection does not run on the client" and nearly sent this
     * investigation down a completely wrong path. A field name prefixed with the mod id is not merged
     * and stays per-side, which is what "does the client inject too" actually needs.
     */
    private static volatile boolean transcenmod$reported;

    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void transcenmod$healthOverride(CallbackInfoReturnable<Float> cir) {
        // Reports once per side. Whether the client's copy is injected matters as much as the server's:
        // the death screen is the client's reaction, and a client that still reads zero will keep asking
        // for it no matter what the server believes.
        if (!transcenmod$reported) {
            transcenmod$reported = true;
            TranscenMod.LOGGER.info(
                    "[transcenmod] getHealth injection reached on {} (retaliation {})",
                    Thread.currentThread().getName(), RetaliationState.isActive());
        }

        // Declared on LivingEntity so that Mixin can find it; narrowed here, because only players carry
        // items. Mobs and every other living entity are left completely untouched.
        if (!((Object) this instanceof Player)) {
            return;
        }
        Player player = (Player) (Object) this;

        // Retaliation first. It must win over the egg, not race with it: being struck by the zombie is
        // meant to be fatal even for a player who is otherwise immune to everything.
        if (RetaliationState.isActive()) {
            cir.setReturnValue(0.0F);
            return;
        }

        if (!TranscenEvents.isCarryingEgg(player)) {
            return;
        }
        cir.setReturnValue(TRANSCENMOD_FULL_HEALTH);
    }
}
