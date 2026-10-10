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

// this layer is what actually makes the egg carrier unkillable, not the events
// the lightning does victim.hurt(source,1919810) then victim.setHealth(0) as adjacent statements
// so no event can run between them: LivingHurtEvent fires and cancels, the setHealth writes anyway
// LivingEntity#kill is hurt(OUT_OF_WORLD,Float.MAX_VALUE) - cancellable damage,nothing observable after
// vanilla asks getHealth() <= 0 per tick, on death, and on the client before the death screen
// answer that question instead of pinning the stored field - a stored value sits at zero, races the asks
// LivingEntity and not Player: Mixin matches methods declared in the target's own bytecode, inherited never
// getHealth is declared on LivingEntity, so a Player target fails "no targets", fatal for a required config
// a fixed 20,not "at least 1": the carrier must read as full to health bars, damage tint, health scaling
// only while the egg is carried - otherwise the player is untouched and the state is theirs to undo
@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin {

    private static final float TRANSCENMOD_FULL_HEALTH = 20.0F;

    // not @Unique. a @Unique static merges into the target class, one copy per game
    // the first 5 calls, all server side, ate the budget and the client reported nothing
    // that read as "injection never runs on the client" and cost an investigation
    // the transcenmod$ prefix keeps it unmerged,per side - which is what the client check needs
    private static volatile boolean transcenmod$reported;

    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void transcenmod$healthOverride(CallbackInfoReturnable<Float> cir) {
        // reports once per side, logged at INFO: the death screen is the client's own reaction
        // a client still reading zero keeps asking for it whatever the server believes
        if (!transcenmod$reported) {
            transcenmod$reported = true;
            TranscenMod.LOGGER.info(
                    "[transcenmod] getHealth injection reached on {} (retaliation {})",
                    Thread.currentThread().getName(), RetaliationState.isActive());
        }

        if (!((Object) this instanceof Player)) {
            return;
        }
        Player player = (Player) (Object) this;

        // retaliation wins over the egg,it has to - a struck player dies even when immune to everything
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
