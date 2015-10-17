/* This file is part of VeinMiner.
 *
 *    VeinMiner is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation, either version 3 of
 *     the License, or (at your option) any later version.
 *
 *    VeinMiner is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with VeinMiner.
 *    If not, see <http://www.gnu.org/licenses/>.
 */

package portablejim.veinminer.core;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.FoodStats;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent;
import portablejim.veinminer.api.Permission;
import portablejim.veinminer.api.VeinminerHarvestFailedCheck;
import portablejim.veinminer.api.VeinminerNoToolCheck;
import portablejim.veinminer.api.VeinminerPostUseTool;
import portablejim.veinminer.configuration.ConfigurationSettings;
import portablejim.veinminer.lib.BlockLib;
import portablejim.veinminer.lib.MinerLogger;
import portablejim.veinminer.server.MinerServer;
import portablejim.veinminer.util.BlockID;
import portablejim.veinminer.util.ExpCalculator;
import portablejim.veinminer.util.ItemStackID;
import portablejim.veinminer.util.PlayerStatus;
import portablejim.veinminer.util.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static net.minecraftforge.event.entity.player.PlayerEvent.HarvestCheck;

/**
 * Main class that performs the work of VeinMiner. It is initialised when a
 * block is mined and then is finished after the vein is mined.
 */

public class MinerInstance {
    public MinerServer serverInstance;
    private HashSet<Point> startBlacklist;
    private ConcurrentLinkedQueue<Point> destroyQueue;
    private HashSet<Point> awaitingEntityDrop;
    private LinkedHashMap<ItemStackID, Integer> drops;
    private World world;
    private EntityPlayerMP player;
    private BlockID targetBlock;
    private boolean finished;
    private ItemStack usedItem;
    private int numBlocksMined;
    private Point initalBlock;
    private int radiusLimit;
    private int blockLimit;

    private static final int MIN_HUNGER = 1;

    public MinerInstance(World world, EntityPlayerMP player, int x, int y, int z, BlockID blockID, MinerServer server, int radiusLimit, int blockLimit) {
        startBlacklist = new HashSet<Point>();
        destroyQueue = new ConcurrentLinkedQueue<Point>();
        awaitingEntityDrop = new HashSet<Point>();
        drops = new LinkedHashMap<ItemStackID, Integer>();
        this.world = world;
        this.player = player;
        targetBlock = blockID;
        finished = false;
        serverInstance = server;
        usedItem = player.getCurrentEquippedItem();
        numBlocksMined = 1;
        initalBlock = new Point(x, y, z);
        this.radiusLimit = radiusLimit;
        this.blockLimit = blockLimit;

        serverInstance.addInstance(this);

        FMLCommonHandler.instance().bus().register(this);
    }

    public Point getInitalBlock() {
        return initalBlock;
    }

    public void cleanUp() {
        FMLCommonHandler.instance().bus().unregister(this);
    }

    private boolean shouldContinue() {
        // Item equipped
        if(!serverInstance.getConfigurationSettings().getEnableAllTools() && player.getCurrentEquippedItem() == null) {
            VeinminerNoToolCheck toolCheck = new VeinminerNoToolCheck(player);
            MinecraftForge.EVENT_BUS.post(toolCheck);

            if(toolCheck.allowTool.isAllowed()) {
                this.finished = false;
            }
            else if(toolCheck.allowTool == Permission.FORCE_DENY) {
                this.finished = true;
            }
            else {
                // Test to see if the player can mine stone.
                // If they can, they have other assistance and so should be
                // considered a tool.
                Block testBlock = Blocks.stone;
                HarvestCheck event = new HarvestCheck(player, testBlock, false);
                MinecraftForge.EVENT_BUS.post(event);
                this.finished = !event.success;
            }
        }

        if(usedItem == null) {
            if(player.getCurrentEquippedItem() != null) {
                this.finished = true;
            }
        }
        else if(player.getCurrentEquippedItem() == null || !player.getCurrentEquippedItem().isItemEqual(usedItem)) {
            this.finished = true;
        }

        // Player exists and is in correct status (correct button held)
        UUID playerName = player.getUniqueID();
        PlayerStatus playerStatus = serverInstance.getPlayerStatus(playerName);
        if(playerStatus == null) {
            this.finished = true;
        }
        else if(playerStatus == PlayerStatus.INACTIVE ||
                (playerStatus == PlayerStatus.SNEAK_ACTIVE && !player.isSneaking()) ||
                (playerStatus == PlayerStatus.SNEAK_INACTIVE && player.isSneaking())) {
            this.finished = true;
        }

        if(finished) {
            return false;
        }

        // Not hungry
        FoodStats food = player.getFoodStats();
        if(food.getFoodLevel() < MIN_HUNGER) {
            this.finished = true;

            String problem = "mod.veinminer.finished.tooHungry";
            if(serverInstance.playerHasClient(player.getUniqueID())) {
                player.addChatMessage(new ChatComponentTranslation(problem));
            }
            else {
                String translatedProblem = StatCollector.translateToLocal(problem);
                player.addChatMessage(new ChatComponentText(translatedProblem));
            }
        }

        // Experience
        int experienceMod = serverInstance.getConfigurationSettings().getExperienceMultiplier();
        if(experienceMod > 0 && ExpCalculator.getExp(player.experienceLevel, player.experience) < experienceMod) {
            this.finished = true;

            String problem = "mod.veinminer.finished.noExp";

            // Fix bugged xp
            if(player.experience < 0) player.experience = 0;
            if(player.experience > 1) player.experience = 1;
            if(player.experienceLevel < 0) player.experienceLevel = 0;
            player.addExperienceLevel(0);

            if(serverInstance.playerHasClient(player.getUniqueID())) {
                player.addChatMessage(new ChatComponentTranslation(problem));
            }
            else {
                String translatedProblem = StatCollector.translateToLocal(problem);
                player.addChatMessage(new ChatComponentText(translatedProblem));
            }
        }

        // Within mined block limits
        if (numBlocksMined >= blockLimit && blockLimit != -1) {
            MinerLogger.debug("Blocks mined: %d; Blocklimit: %d. Forcing finish.", numBlocksMined, blockLimit);
            this.finished = true;
        }

        return !this.finished;
    }

    private boolean toolAllowedForBlock(ItemStack tool, BlockID block) {
        boolean toolAllowed = false;
        ConfigurationSettings settings = serverInstance.getConfigurationSettings();
        for(String type : settings.getToolTypeNames()) {
            if(settings.toolIsOfType(tool, type)) {
                if(serverInstance.getConfigurationSettings().whiteListHasBlockId(type, block)) {
                    toolAllowed = true;
                }
            }
        }
        return toolAllowed;
    }

    private void takeHunger() {
        float hungerMod = ((float) serverInstance.getConfigurationSettings().getHungerMultiplier()) * 0.025F;
        FoodStats s = player.getFoodStats();
        NBTTagCompound nbt = new NBTTagCompound();
        s.writeNBT(nbt);
        int foodLevel = nbt.getInteger("foodLevel");
        int foodTimer = nbt.getInteger("foodTickTimer");
        float foodSaturationLevel = nbt.getFloat("foodSaturationLevel");
        float foodExhaustionLevel = nbt.getFloat("foodExhaustionLevel");

        float newExhaustion = (foodExhaustionLevel + hungerMod) % 4;
        float newSaturation = foodSaturationLevel - (float)((int)((foodExhaustionLevel + hungerMod) / 4));
        int newFoodLevel = foodLevel;
        if(newSaturation < 0) {
            newFoodLevel += newSaturation;
            newSaturation = 0;
        }
        nbt.setInteger("foodLevel", newFoodLevel);
        nbt.setInteger("foodTickTimer", foodTimer);
        nbt.setFloat("foodSaturationLevel", newSaturation);
        nbt.setFloat("foodExhaustionLevel", newExhaustion);

        s.readNBT(nbt);
    }

    private void takeExperience() {
        int targetLevel = player.experienceLevel;
        int expToTakeAway = serverInstance.getConfigurationSettings().getExperienceMultiplier();

        if(expToTakeAway == 0) {
            return;
        }

        if(expToTakeAway > player.experience * player.xpBarCap()) {
            int newExp = ExpCalculator.getExp(player.experienceLevel, player.experience) - expToTakeAway;
            while(ExpCalculator.getExp(targetLevel, 0) > newExp)
                targetLevel--;
            player.experienceLevel = targetLevel < 0 ? 0 : targetLevel;
            //expToTakeAway -= ExpCalculator.getExp(targetLevel, 0);
            int newExpTotal = newExp - ExpCalculator.getExp(targetLevel, 0);
            player.experience = Math.max(0, Math.min(1, (float)newExpTotal / player.xpBarCap()));
            player.experienceTotal = Math.max(0, newExpTotal);
            if(newExp <= 0) {
                player.experience = 0;
                player.experienceLevel = 0;
                player.experienceTotal = 0;
            }
        }
        else {
            player.addExperience(-expToTakeAway);
        }
        player.addExperienceLevel(0);
    }

    private void mineBlock(int x, int y, int z) {
        Point newPoint = new Point(x, y, z);
        BlockID newBlock = new BlockID(world, new BlockPos(x , y, z ));
        ConfigurationSettings configurationSettings = serverInstance.getConfigurationSettings();
        startBlacklist.add(newPoint);
        if(mineAllowed(newBlock, newPoint, configurationSettings)) {
            awaitingEntityDrop.add(newPoint);
            boolean success = player.theItemInWorldManager.tryHarvestBlock(new BlockPos(x, y, z));
            numBlocksMined++;

            if(!player.capabilities.isCreativeMode) {
                takeHunger();
                takeExperience();
            }

            VeinminerPostUseTool toolUsedEvent = new VeinminerPostUseTool(player);
            MinecraftForge.EVENT_BUS.post(toolUsedEvent);

            // Only go ahead if block was destroyed. Stops mining through protected areas.
            VeinminerHarvestFailedCheck continueCheck = new VeinminerHarvestFailedCheck(player, targetBlock.name, targetBlock.metadata);
            MinecraftForge.EVENT_BUS.post(continueCheck);
            if (success || continueCheck.allowContinue.isAllowed()) {
                postSuccessfulBreak(newPoint);
            } else {
                awaitingEntityDrop.remove(newPoint);
            }
        }
    }


    public void postSuccessfulBreak(Point breakPoint) {
        ArrayList<Point> surrondingPoints = getPoints(breakPoint);
        destroyQueue.addAll(surrondingPoints);
    }

    private ArrayList<Point> getPoints(Point origin) {
        ArrayList<Point> points = new ArrayList<Point>(9);
        int dimRange[] = {-1, 0, 1};
        for(int dx : dimRange) {
            for(int dy : dimRange) {
                for(int dz : dimRange) {
                    if(dx == 0 && dy == 0 && dz == 0) {
                        // If 0, 0, 0
                        continue;
                    }
                    points.add(new Point(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz));
                }
            }
        }
        Collections.shuffle(points);
        return points;
    }

    private boolean mineAllowed(BlockID newBlock, Point newBlockPos, ConfigurationSettings configSettings) {
        if(finished || !shouldContinue()) return false;
        // Ensure valid block
        if (Block.getBlockFromName(newBlock.name) == null) {
            return false;
        }
        if (!newBlock.wildcardEquals(targetBlock) && !configSettings.areBlocksCongruent(newBlock, targetBlock)
                && !BlockLib.arePickBlockEqual(newBlock, targetBlock)) {
            return false;
        }
        if (!newBlockPos.isWithinRange(initalBlock, radiusLimit) && radiusLimit > 0) {
            MinerLogger.debug("Initial block: %d,%d,%d; New block: %d,%d,%d; Radius: %.2f; Raidus limit: %d.", initalBlock.getX(), initalBlock.getY(), initalBlock.getZ(), newBlockPos.getX(), newBlockPos.getY(), newBlockPos.getZ(), Math.sqrt(initalBlock.distanceFrom(newBlockPos)), radiusLimit);
            return false;
        }
        // Block already scheduled.
        if (awaitingEntityDrop.contains(newBlockPos))
            return false;
        //noinspection SimplifiableIfStatement
        if (numBlocksMined >= blockLimit && blockLimit != -1) {
            MinerLogger.debug("Block limit is: %d; Blocks mined: %d", blockLimit, numBlocksMined);
            return false;
        }
        // Seem to get wrong result if inlined. ??!??!
        //noinspection UnnecessaryLocalVariable
        boolean result =  (configSettings.getEnableAllBlocks() || toolAllowedForBlock(usedItem, newBlock));
        return result;
    }


    @SuppressWarnings({"UnusedParameters", "UnusedDeclaration"})
    @SubscribeEvent
    public void mineScheduled(ServerTickEvent event) {
        int quantity = serverInstance.getConfigurationSettings().getBlocksPerTick();
        for(int i = 0; i < quantity; i++) {
            if(!destroyQueue.isEmpty()) {
                Point target = destroyQueue.remove();
                mineBlock(target.getX(), target.getY(), target.getZ());
            }
            else {
                // All blocks have been mined. This is done last.
                serverInstance.removeInstance(this);
                if(!drops.isEmpty()) {
                    spawnDrops();
                }
                cleanUp();
                return;
            }
        }
    }

    private void spawnDrops() {
        for(Map.Entry<ItemStackID, Integer> schedDrop : drops.entrySet()) {
            ItemStackID itemStack = schedDrop.getKey();
            String itemName = itemStack.getItemId();

            Item foundItem = Item.getByNameOrId(itemName);
            if(foundItem == null) {
                continue;
            }

            int itemDamage = itemStack.getDamage();

            int numItems = schedDrop.getValue();
            while (numItems > itemStack.getMaxStackSize()) {
                ItemStack newItemStack = new ItemStack(foundItem, itemStack.getMaxStackSize(), itemDamage);
                EntityItem newEntityItem = new EntityItem(world, initalBlock.getX(), initalBlock.getY(), initalBlock.getZ(), newItemStack);
                world.spawnEntityInWorld(newEntityItem);
                numItems -= itemStack.getMaxStackSize();
            }
            ItemStack newItemStack = new ItemStack(foundItem, numItems, itemDamage);
            newItemStack.setItemDamage(itemStack.getDamage());
            EntityItem newEntityItem = new EntityItem(world, initalBlock.getX(), initalBlock.getY(), initalBlock.getZ(), newItemStack);
            world.spawnEntityInWorld(newEntityItem);
        }
        drops.clear();
    }

    public boolean isRegistered(Point p) {
        return awaitingEntityDrop.contains(p);
    }

    public void addDrop(EntityItem entity) {
        ItemStack item = entity.getEntityItem();
        ItemStackID itemInfo = new ItemStackID(item.getItem(), item.getItemDamage(), item.getMaxStackSize());

        if(drops.containsKey(itemInfo)) {
            int oldDropNumber = drops.get(itemInfo);
            int newDropNumber = oldDropNumber + item.stackSize;
            drops.put(itemInfo, newDropNumber);
        }
        else {
            drops.put(itemInfo, item.stackSize);
        }
    }

    public boolean pointIsBlacklisted(Point point) {
        return startBlacklist.contains(point);
    }

    public void removeFromBlacklist(Point point) {
        if(startBlacklist.contains(point)) {
            startBlacklist.remove(point);
        }
    }
}
