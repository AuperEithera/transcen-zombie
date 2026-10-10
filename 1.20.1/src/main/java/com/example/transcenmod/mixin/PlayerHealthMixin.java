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
 * <p>This is the layer that actually makes the egg carrier unkillable, and the reason the event-based
 * attempts alone were not enough.
 *
 * <p><b>Why the event layer could not do it.</b> The red lightning runs
 * {@code victim.hurt(source, 1919810)} and then, on the very next line, {@code victim.setHealth(0)}.
 * Those are two adjacent statements in one method, so there is no point between them at which an event
 * handler can run. {@code LivingHurtEvent} fires inside the first call and is cancelled successfully,
 * and the {@code setHealth(0)} afterwards writes zero anyway.
 *
 * <p><b>Why answering the question beats pinning the value.</b> Vanilla asks {@code getHealth() <= 0} to
 * decide whether a living entity is dead, and it asks repeatedly -- once per tick, again in the death
 * check, and once more on the client when it decides whether to raise the death screen. The stored field
 * can sit at zero; as long as every one of those questions is answered with a living value, no death
 * decision is ever reached. Pinning the stored value races against those questions; answering the
 * question itself does not.
 *
 * <p><b>Why the target is {@code LivingEntity}, not {@code Player}.</b> Not a stylistic choice.
 * {@code getHealth} is <i>declared</i> on {@code LivingEntity} and merely inherited by {@code Player},
 * and Mixin resolves an injection target against the methods a class declares in its own bytecode --
 * inherited methods are not candidates. Targeting {@code Player} fails outright with "could not find any
 * targets matching", and because the config is marked required that failure is fatal at startup. The
 * player check happens inside the handler instead.
 *
 * <p>Scoped to the egg being carried. With no egg in the inventory the method is untouched and the player
 * is entirely normal, so this cannot leak into a state the player cannot see or undo.
 */
@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin {

    /** Full health, as a vanilla player has. */
    private static final float TRANSCENMOD_FULL_HEALTH = 20.0F;

    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void transcenmod$healthOverride(CallbackInfoReturnable<Float> cir) {
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
