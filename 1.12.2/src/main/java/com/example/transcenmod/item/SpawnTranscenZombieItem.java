package com.example.transcenmod.item;

import com.example.transcenmod.TranscenMod;
import com.example.transcenmod.entity.TranscenZombie;
import com.example.transcenmod.event.TranscenEvents;
import com.example.transcenmod.network.ActivateHudMessage;
import com.example.transcenmod.network.NetworkHandler;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

public class SpawnTranscenZombieItem extends ItemMonsterPlacer {

    private static final int PRIMARY_COLOR = 0x8B2BE2;
    private static final int SECONDARY_COLOR = 0xE22B7A;

    public SpawnTranscenZombieItem() {
        setRegistryName(TranscenMod.MODID, "transcen_zombie_spawn_egg");
        setTranslationKey(TranscenMod.MODID + ".transcen_zombie_spawn_egg");
        setCreativeTab(TranscenMod.CREATIVE_TAB);
        setMaxStackSize(64);
    }

    public static int[] getColors() {
        return new int[] { PRIMARY_COLOR, SECONDARY_COLOR };
    }

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        return I18n.translateToLocal(getTranslationKey() + ".name").trim();
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (this.isInCreativeTab(tab)) {
            items.add(new ItemStack(this));
        }
    }

    // Right-clicking a block never reaches onItemRightClick: processRightClickBlock calls onItemUseFirst and
    // then onItemUse, and onItemRightClick only runs from the air path. Having just the one hook spawned the
    // zombie in the air and did nothing when the crosshair was on a block.
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        return trySpawn(world, player, hand);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        return trySpawn(world, player, hand).getType();
    }

    private ActionResult<ItemStack> trySpawn(World world, EntityPlayer player, EnumHand hand) {
        ItemStack held = player.getHeldItem(hand);

        // Sneaking turns the same item into the AllReturn switch. It is checked before the isRemote
        // return below, because the activation is a client side action: the agent runs in this JVM, and
        // the flag it mirrors lives on this side. The left click behaviour is untouched - it is handled
        // in ClientEvents and never reaches here.
        if (player.isSneaking()) {
            return activateAllReturn(world, player, held);
        }

        if (world.isRemote) {
            return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, held);
        }
        if (!(player instanceof EntityPlayerMP)) {
            return new ActionResult<ItemStack>(EnumActionResult.PASS, held);
        }

        TranscenMod.defenceEnabled = true;
        com.example.transcenmod.core.CoreHooks.setProtectionEnabled(true);
        TranscenEvents.restartEncounter();

        final double dx = player.posX + player.getLookVec().x * 2.0D;
        final double dz = player.posZ + player.getLookVec().z * 2.0D;
        final double dy = player.posY + 0.5D;
        final float yaw = player.rotationYaw;

        final TranscenZombie zombie = new TranscenZombie(world);

        zombie.withRelocationAllowed(new Runnable() {
            @Override
            public void run() {
                zombie.setLocationAndAngles(dx, dy, dz, yaw, 0.0F);
            }
        });
        zombie.onInitialSpawn(world.getDifficultyForLocation(new BlockPos(dx, dy, dz)), null);

        boolean added = world.spawnEntity(zombie);

        // the egg used to fail silently: the handler ran, the chat line printed, and no zombie existed after.
        // these values separate the ways that happens - a declined placement, spawnEntity returning false, or
        // the entity already retired by a wipe generation.
        TranscenMod.LOGGER.info("egg: want {},{},{} added={} at {},{},{} retired={} dead={}",
                (int) dx, (int) dy, (int) dz, added,
                (int) zombie.posX, (int) zombie.posY, (int) zombie.posZ,
                zombie.isRetired(), zombie.isDead);

        if (!added) {
            // fall back to the player's own feet, a chunk that is loaded by definition
            zombie.setPositionAndRotation(player.posX, player.posY, player.posZ, yaw, 0.0F);
            added = world.spawnEntity(zombie);
            TranscenMod.LOGGER.info("egg: retry at the player added={} at {},{},{}",
                    added, (int) zombie.posX, (int) zombie.posY, (int) zombie.posZ);
        }

        player.sendMessage(new TextComponentString("\u00a7aDefence enabled"));
        NetworkHandler.sendToPlayer((EntityPlayerMP) player, new ActivateHudMessage());

        if (!player.capabilities.isCreativeMode) {
            held.shrink(1);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, held);
    }

    /**
     * Shift + right click: the red line, and AllReturn on.
     *
     * The message is the client's own chat, so it is sent on the client. Server and integrated server
     * share one JVM and one LaunchClassLoader, so the flag the guards read is set by the same call.
     * On a dedicated server the client half of this is what runs, which is the half that matters: the
     * agent and the transformer both live in the client's JVM.
     */
    private ActionResult<ItemStack> activateAllReturn(World world, EntityPlayer player, ItemStack held) {
        if (!world.isRemote) {
            // the client drives this; the server side only has to not spawn anything
            return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, held);
        }
        com.example.transcenmod.core.AllReturn.set(true);
        player.sendMessage(new TextComponentString(
                "\u00a7cTranscendenceAllReturn is Activated!"));
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, held);
    }

}
