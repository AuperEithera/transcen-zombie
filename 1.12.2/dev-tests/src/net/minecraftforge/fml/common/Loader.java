package net.minecraftforge.fml.common;

/**
 * Stand-in for the class Forge loads first at launch. Its only job is to be a class that is visible to the
 * mini launch loader used by {@code verifyAllReturn} and therefore has to be defined with the transformer
 * chain running - which is the exact situation that produced the ClassCircularityError the real
 * net.minecraftforge.fml.common.Loader produced in game.
 *
 * <p>It deliberately names nothing, so the only thing that can go wrong while it is being defined is
 * the transformer chain itself.
 */
public class Loader {

    public static String name() {
        return "loader";
    }
}
