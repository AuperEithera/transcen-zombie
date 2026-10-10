package net.minecraft.entity;

/**
 * A stand-in for a banned entity type, the thing the ban list is supposed to stop. Verifying that
 * it is blocked is what proves the ban reaches the world without going through EntityJoinWorldEvent.
 */
public class EntityZombie extends Entity {
}
