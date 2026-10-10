package com.example.transcenmod.client;

import com.example.transcenmod.CommonProxy;
import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.item.SpawnTranscenZombieItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.IRenderFactory;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The client half of the mod's setup: everything that names a client-only type.
 *
 * <p>FML constructs this instead of {@link CommonProxy} on a client, chosen by the string in
 * {@code TranscenMod}'s {@code @SidedProxy}. Because the choice is made by name, this class's imports of
 * {@code net.minecraft.client.*} never have to exist on a dedicated server.
 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    /**
     * Registers the two client event handlers.
     *
     * <p>Both are registered as instances rather than through {@code @EventBusSubscriber}: 1.12.2's
     * {@code @EventBusSubscriber} exists but is restricted to classes with static handler methods, and the
     * handlers here are instance methods so they can be reasoned about like the rest of the mod's client
     * code. Registering before delegating to {@code super} keeps this class's side-specific work first, and
     * the base implementation does nothing that either handler depends on.
     */
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());

        // The entity renderer has to be registered here, not in init. RenderManager's constructor calls
        // RenderingRegistry.loadEntityRenderers, and that constructor runs while Minecraft is being built,
        // which is after preInit and before init. Registering in init therefore filled a map nobody reads
        // again: TranscenZombie fell through getEntityClassRenderObject to EntityZombie and drew with the
        // vanilla RenderZombie, so the mod's own texture never appeared at all.
        RenderingRegistry.registerEntityRenderingHandler(TranscenZombie.class,
                new IRenderFactory<TranscenZombie>() {
                    @Override
                    public Render<? super TranscenZombie> createRenderFor(RenderManager manager) {
                        return new TranscenZombieRenderer(manager);
                    }
                });

        super.preInit(event);
    }

    /**
     * Everything left for {@code init}: the spawn egg's colours.
     *
     * <p>This one genuinely cannot move earlier -- {@code Minecraft#getItemColors()} does not exist until the
     * client has been built, so registering in {@code preInit} would be a null dereference rather than a
     * silent no-op.
     */
    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        registerSpawnEggColours();
    }

    /**
     * Tints the spawn egg with the mod's two colours.
     *
     * <p>Vanilla's {@code ItemMonsterPlacer} draws its egg with two tint indices -- 0 for the base and 1 for
     * the spots -- and reads them from {@link net.minecraft.client.renderer.color.ItemColors}. The colours
     * themselves live on {@code SpawnTranscenZombieItem} so the item and the renderer cannot disagree, and
     * are read once here rather than looked up per tint.
     */
    private void registerSpawnEggColours() {
        final int[] colors = SpawnTranscenZombieItem.getColors();
        Minecraft.getMinecraft().getItemColors().registerItemColorHandler(new IItemColor() {
            @Override
            public int colorMultiplier(ItemStack stack, int tintIndex) {
                if (tintIndex == 0) {
                    return colors[0];
                }
                if (tintIndex == 1) {
                    return colors[1];
                }
                // Not one of the egg's own layers (the layer list can grow with resource packs), so leave
                // the texture alone instead of tinting it with whichever colour happened to be first.
                return 0xFFFFFF;
            }
        }, TranscenMod.spawnEgg);
    }
}
