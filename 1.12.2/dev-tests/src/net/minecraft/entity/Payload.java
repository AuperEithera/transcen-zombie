package net.minecraft.entity;

/**
 * A stand-in shaped like the classes a coremod touches: one private field it needs widened
 * so its own renderer can read it, and one method it replaces outright to change behaviour.
 *
 * The two changes are deliberately in the same class, because that is the case the feature has to get right.
 * A filter that only knows how to drop a whole transformer cannot keep the field widened and the method
 * original at the same time.
 */
public class Payload {

    private int hidden = 7;

    private int alsoHidden = 11;

    public int value() {
        return hidden;
    }
}
