package net.minecraft.entity;

import java.util.UUID;

/**
 * Minimal stand-in for the real 1.12.2 EntityLivingBase, used only by {@code verifyTransformer}.
 *
 * <p>It reproduces the two things the injection touches and nothing else: a final getHealth()F whose body
 * reads a stored field, and getUniqueID(). The body matters -- the injected branch has to leave the
 * original body able to run and return the stored value on the vanilla path.
 *
 * <p>It also reproduces the shape that made the first version of the transformer silently fail: this method
 * ends in FRETURN, so anything appended after the original body is unreachable and the hook is never called
 * even though the constant pool mentions it.
 */
public class EntityLivingBase {

    private final UUID uuid = UUID.randomUUID();
    private float health = 7.5F;

    public UUID getUniqueID() {
        return uuid;
    }

    public final float getHealth() {
        return this.health;
    }

    public void setHealthDirectly(float value) {
        this.health = value;
    }
}
