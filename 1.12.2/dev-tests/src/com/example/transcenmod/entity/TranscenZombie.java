package com.example.transcenmod.entity;

/**
 * Stand-in that carries the real fully qualified name of the protected entity, so CoreHooks#refuseRemoval
 * can be exercised without dragging in the real entity and its minecraft dependencies.
 *
 * It extends the stand-in Entity so it can also be handed to the stand-in World's removeEntity.
 */
public class TranscenZombie extends net.minecraft.entity.Entity {
}
