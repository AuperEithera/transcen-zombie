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

/**
 * Spawn egg for the Transcendence Zombie.
 *
 * <p>Right-clicking restores the protection and spawns the zombie, in that order, and says so in chat.
 *
 * <p>The order is the whole point of the method. The protection used to be switched on after the spawn had
 * already happened, which left the new zombie undefended for the rest of that tick -- long enough for a
 * removal that landed in the same tick to take, and for the zombie to be gone before the protection that
 * was meant to keep it existed. Enabling first removes that window entirely.
 *
 * <p>The message mirrors the one left-click prints. The two gestures are one toggle, so both ends of it
 * report the state; a switch that is silent in one direction is a switch the player has to test to
 * understand.
 *
 * <p>1.12.2 has no {@code ForgeSpawnEggItem}, so this is the vanilla {@code ItemMonsterPlacer} that
 * generation used as its basis instead. That parent does three things the port has to undo or replace, and
 * all three are consequences of 1.12.2 not being flattened:
 *
 * <ul>
 *   <li><b>{@code getSubItems} is overridden to add exactly one egg.</b> The inherited implementation
 *       iterates {@code EntityList.ENTITY_EGGS} and adds a stack for <em>every</em> spawnable mob in the
 *       game -- bat, cow, wither, everything -- each one still an instance of this item and therefore each
 *       one drawn with this item's model. The visible symptom is a creative tab full of identical eggs
 *       that all read as "Spawn Bat", because the name comes from each stack's NBT rather than from the
 *       item. This is not a display bug that can be tuned away; the parent method has to be replaced.</li>
 *   <li><b>{@code getItemStackDisplayName} is replaced by a plain translation lookup.</b> The inherited
 *       version appends the entity name it resolves from the stack's {@code EntityTag} NBT compound, which
 *       vanilla sets in {@code getSubItems} and which this mod's egg never sets. Leaving it inherited would
 *       make the name depend on NBT this item does not use.</li>
 *   <li><b>The entity id is not written into the stack at all.</b> Vanilla's egg carries
 *       {@code EntityTag.id} so that {@code ItemMonsterPlacer.spawnCreature} knows what to build. This egg
 *       does not use that path -- see {@link #onItemRightClick} -- so the tag would be dead weight. It also
 *       could not work: {@code spawnCreature} resolves the id through {@code EntityList}, and this entity is
 *       registered through Forge's {@code EntityEntry} registry rather than being reachable from there.</li>
 * </ul>
 */
public class SpawnTranscenZombieItem extends ItemMonsterPlacer {

    private static final int PRIMARY_COLOR = 0x8B2BE2;
    private static final int SECONDARY_COLOR = 0xE22B7A;

    public SpawnTranscenZombieItem() {
        setRegistryName(TranscenMod.MODID, "transcen_zombie_spawn_egg");
        setTranslationKey(TranscenMod.MODID + ".transcen_zombie_spawn_egg");
        setCreativeTab(TranscenMod.CREATIVE_TAB);
        setMaxStackSize(64);
    }

    /** The two tint colours, in the order the vanilla egg layers expect: base first, overlay second. */
    public static int[] getColors() {
        return new int[] { PRIMARY_COLOR, SECONDARY_COLOR };
    }

    /**
     * The name, taken straight from the lang file.
     *
     * <p>Deliberately does not call {@code super}: the parent appends the entity name it resolves from the
     * stack's {@code EntityTag}, and this egg carries no such tag. The key it looks up is
     * {@code item.transcenmod.transcen_zombie_spawn_egg.name}, which is what {@code setTranslationKey} plus
     * the vanilla {@code .name} suffix produces.
     */
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        return I18n.translateToLocal(getTranslationKey() + ".name").trim();
    }

    /**
     * Adds one egg to the creative tab, and only one.
     *
     * <p>Replaces the inherited implementation, which adds a stack per entry in {@code EntityList.ENTITY_EGGS}
     * -- every spawnable mob in the game, all sharing this item and therefore this item's model and tint.
     * The metadata is 0 because this item has no subtypes; the whole stat block it spawns is fixed.
     */
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
        if (world.isRemote) {
            return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, held);
        }
        if (!(player instanceof EntityPlayerMP)) {
            return new ActionResult<ItemStack>(EnumActionResult.PASS, held);
        }

        TranscenMod.defenceEnabled = true;
        TranscenEvents.restartEncounter();

        final double dx = player.posX + player.getLookVec().x * 2.0D;
        final double dz = player.posZ + player.getLookVec().z * 2.0D;
        final double dy = player.posY + 0.5D;
        final float yaw = player.rotationYaw;

        final TranscenZombie zombie = new TranscenZombie(world);

        // the relocation guard refuses any write longer than MAX_SINGLE_MOVE; a fresh entity is only excused
        // by the stillAtOrigin test. going through the mod's own escape hatch removes that dependence: if the
        // guard ever declines, the entity stays at the origin, whose chunk is not loaded, and spawnEntity
        // returns false for a reason nothing else reports.
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

}
