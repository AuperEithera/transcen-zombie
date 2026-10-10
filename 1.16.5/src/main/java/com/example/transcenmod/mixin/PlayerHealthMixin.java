package com.example.transcenmod.mixin;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.util.RetaliationState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the layer that actually makes the egg carrier unkillable,no damage event ever was enough
// red lightning runs attackEntityFrom then setHealth(0) on the very next line no tick between them
// LivingHurtEvent cancels inside the first call and the setHealth(0) writes zero regardless,/kill bypasses it all
// vanilla asks getHealth() <= 0 every tick and again for the client death screen so answer the question
// the stored field may sit at zero - pinning it only races those asks
// target LivingEntity here - getHealth is declared there, PlayerEntity fails target matching fatally at startup
// fixed 20 not "at least 1" so the bar and health scaled mods read a healthy player, egg scoped only
@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin {

    private static final float TRANSCENMOD_FULL_HEALTH = 20.0F;

    // deliberately not @Unique,a @Unique static is merged into the target so there is one copy for the whole game
    // the first five calls were all server side and burned the budget, the client never reported
    // that looked exactly like the injection not running on the client, the mod id prefix keeps it per side
    private static volatile boolean transcenmod$reported;

    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void transcenmod$healthOverride(CallbackInfoReturnable<Float> cir) {
        // once per side matters - the death screen is the client's own reaction
        // a client still reading zero keeps asking for it whatever the server believes
        if (!transcenmod$reported) {
            transcenmod$reported = true;
            TranscenMod.LOGGER.info(
                    "[transcenmod] getHealth injection reached on {} (retaliation {})",
                    Thread.currentThread().getName(), RetaliationState.isActive());
        }

        // narrowed here since only players carry items,mobs and every other living entity stay untouched
        if (!((Object) this instanceof PlayerEntity)) {
            return;
        }
        PlayerEntity player = (PlayerEntity) (Object) this;

        // retaliation checked first so it wins rather than races the egg
        // being struck by the zombie is meant to be fatal even for an otherwise immune carrier
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
