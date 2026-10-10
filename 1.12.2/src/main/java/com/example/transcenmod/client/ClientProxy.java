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

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());

        RenderingRegistry.registerEntityRenderingHandler(TranscenZombie.class,
                new IRenderFactory<TranscenZombie>() {
                    @Override
                    public Render<? super TranscenZombie> createRenderFor(RenderManager manager) {
                        return new TranscenZombieRenderer(manager);
                    }
                });

        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        registerSpawnEggColours();
    }

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
