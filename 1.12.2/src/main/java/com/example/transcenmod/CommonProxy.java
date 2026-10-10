package com.example.transcenmod;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * The side-safe half of the mod's setup.
 *
 * <p>Everything in here has to load on a dedicated server. The 1.16.5 build arranged the same split by
 * registering its client classes by <em>name</em> from a common method, so that the common class's constant
 * pool never named a client-only type. 1.12.2's {@code @SidedProxy} is the same idea with a declared
 * contract: FML instantiates the client subclass on a client and this one on a server, and the reference to
 * the client class exists only as a string in the annotation.
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        // Nothing side specific. Entity and item registration is driven from RegistryEvent handlers on the
        // mod class, which are side-agnostic by construction.
    }

    public void init(FMLInitializationEvent event) {
        // Nothing side specific.
    }
}
