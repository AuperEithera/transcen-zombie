package com.example.transcenmod;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        // Nothing side specific. Entity and item registration is driven from RegistryEvent handlers on the
        // mod class, which are side-agnostic by construction.
    }

    public void init(FMLInitializationEvent event) {
        // Nothing side specific.
    }
}
