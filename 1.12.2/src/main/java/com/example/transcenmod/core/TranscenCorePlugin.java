package com.example.transcenmod.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * The coremod entry point declared in the jar manifest as {@code FMLCorePlugin}.
 *
 * <p>1.16.5 needed no equivalent: Sponge Mixin is discovered from the jar's {@code MixinConfigs} manifest
 * attribute and applied by a Mixin loader the mod merely depended on. 1.12.2's Forge has no such loader, so
 * the two injections that generation performed are re-expressed as a class transformer and this class is
 * what registers it.
 *
 * <p>Three manifest attributes have to accompany this for the jar to work at all, and none of them has an
 * equivalent in the newer builds:
 *
 * <ul>
 *   <li>{@code FMLCorePlugin} -- without it this class is never constructed.</li>
 *   <li>{@code FMLCorePluginContainsFMLMod} and {@code ForceLoadAsMod} -- without them Forge treats a jar
 *       carrying a coremod as coremod-only and never loads the {@code @Mod} in the same file.</li>
 *   <li>{@code FMLAT} -- without it the access transformer is silently ignored, which breaks the entity at
 *       runtime with "overrides final method func_110143_aJ" while compiling perfectly. See
 *       {@code accesstransformer.cfg} and the note in {@code build.gradle}.</li>
 * </ul>
 */
public class TranscenCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { TranscenTransformer.class.getName() };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Nothing to inject: both injections read their state from CoreHooks, which the mod pushes into
        // once it is constructed. The transformer only ever sees the class names it is handed.
    }

    @Override
    public String getAccessTransformerClass() {
        // Returning null is deliberate and does NOT mean "no access transformer". Forge reads the AT from
        // the jar itself, driven by the manifest's FMLAT attribute; a class returned here would be a
        // second, divergent source of truth for the same file.
        return null;
    }
}
