package net.minecraft.entity;

/**
 * Minimal stand-in for the real 1.12.2 Entity, used only by {@code verifyEntityGuard}.
 *
 * It carries the one thing the injected guards look at on an entity: a class identity for
 * CoreHooks#refuseRemoval / #refuseAdd to test, and the isDead flag the vanilla removal path sets.
 */
public class Entity {

    public boolean isDead;

    public void setDead() {
        this.isDead = true;
    }
}
