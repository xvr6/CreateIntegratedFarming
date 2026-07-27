/*
 * Copyright (C) 2025  DragonsPlus
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package plus.dragons.createintegratedfarming.common.ranching.roost;

import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.item.ItemHandlerWrapper;
import com.simibubi.create.foundation.item.ItemHelper;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import plus.dragons.createintegratedfarming.config.CIFConfig;

/**
 * A roost produces on demand rather than on a timer: a single serving of food
 * is accepted only while
 * off cooldown, immediately rolls the production loot table, and starts the
 * cooldown again.
 */
public abstract class AnimalRoostBlockEntity extends SmartBlockEntity {
    /**
     * Fraction of {@link #productionCooldown()} that a rolled cooldown may be
     * shortened by.
     */
    private static final int COOLDOWN_VARIANCE = 15;

    protected final ItemStackHandler inventory;
    public final IItemHandler outputHandler;
    /**
     * Ticks remaining until this roost accepts food again; {@code 0} means ready to
     * be fed.
     */
    protected int cooldown;

    public int productionCooldown() {
        return CIFConfig.server().roostingCooldown.get();
    }

    /**
     * Rolls a cooldown slightly shorter than {@link #productionCooldown()}, so that
     * roosts fed in
     * lockstep drift apart instead of producing in sync. Only ever shortens, never
     * exceeds the
     * configured value.
     */
    protected int rollProductionCooldown(RandomSource random) {
        int cooldown = productionCooldown();
        return cooldown - random.nextInt(cooldown / COOLDOWN_VARIANCE + 1);
    }

    /** Whether this roost is off cooldown and will accept a serving of food. */
    public boolean canFeed() {
        return cooldown <= 0;
    }

    protected abstract ResourceKey<LootTable> productionLootTable();

    public AnimalRoostBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(20);
        this.inventory = new ItemStackHandler(CIFConfig.server().roostingInventorySlotCount.get()) {
            @Override
            public int getSlotLimit(int slot) {
                return CIFConfig.server().roostingInventorySlotSize.get();
            }
        };
        this.outputHandler = new ItemHandlerWrapper(inventory) {
            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return stack;
            }
        };
    }

    public @Nullable IItemHandler getItemHandler(@Nullable Direction direction) {
        if (direction == getBlockState().getValue(HorizontalDirectionalBlock.FACING))
            return null;
        return outputHandler;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new DirectBeltInputBehaviour(this)
                .onlyInsertWhen(
                        side -> side == getBlockState().getValue(HorizontalDirectionalBlock.FACING).getOpposite())
                .considerOccupiedWhen(side -> !canFeed())
                .setInsertionHandler(this::tryInsertFrom));
    }

    @Override
    public void lazyTick() {
        if (!(level instanceof ServerLevel) || cooldown <= 0)
            return;
        cooldown = Math.max(0, cooldown - lazyTickRate);
        notifyUpdate();
    }

    /**
     * Consumes one serving of food: produces immediately and restarts the cooldown.
     * Callers are
     * expected to have checked {@link #canFeed()} and validated the food
     * beforehand.
     *
     * @param particle  food to spawn eating particles from, or
     *                  {@link ItemStack#EMPTY} for none
     * @param remainder leftover to drop in front of the roost, or
     *                  {@link ItemStack#EMPTY} for none
     * @return {@code false} if nothing was produced, in which case the serving must
     *         not be consumed
     */
    protected boolean feed(ItemStack particle, ItemStack remainder, SoundEvent sound) {
        assert level != null;
        Vec3 feedPos = feedPos();
        if (level.isClientSide) {
            if (!particle.isEmpty())
                level.addParticle(
                        new ItemParticleOption(ParticleTypes.ITEM, particle),
                        feedPos.x, feedPos.y, feedPos.z,
                        0, 0, 0);
            return true;
        }
        if (!produce())
            return false;
        if (!remainder.isEmpty())
            Containers.dropItemStack(level, feedPos.x, feedPos.y, feedPos.z, remainder.copy());
        level.playSound(
                null, worldPosition, sound, SoundSource.BLOCKS,
                1.0F, (level.random.nextFloat() - level.random.nextFloat()) * 0.2F + 1.0F);
        return true;
    }

    /**
     * Rolls the production loot table into the inventory and starts the cooldown.
     *
     * @return {@code false} when nothing fit, leaving the roost ready to be fed
     *         again
     */
    protected boolean produce() {
        if (!(level instanceof ServerLevel serverLevel))
            return false;
        var lootTable = serverLevel.getServer().reloadableRegistries().getLootTable(productionLootTable());
        var lootParams = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.BLOCK_STATE, getBlockState())
                .withParameter(LootContextParams.ORIGIN, worldPosition.getCenter())
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, this)
                .create(LootContextParamSets.BLOCK);
        boolean produced = false;
        for (var stack : lootTable.getRandomItems(lootParams)) {
            ItemStack remainder = ItemHandlerHelper.insertItem(inventory, stack, false);
            produced |= stack.getCount() != remainder.getCount();
        }
        if (!produced)
            return false;
        cooldown = rollProductionCooldown(serverLevel.random);
        serverLevel.playSound(
                null, worldPosition, SoundEvents.CHICKEN_EGG, SoundSource.BLOCKS,
                1.0F, (serverLevel.random.nextFloat() - serverLevel.random.nextFloat()) * 0.2F + 1.0F);
        notifyUpdate();
        return true;
    }

    /**
     * The point in front of the roost where food is eaten and leftovers are
     * dropped.
     */
    protected Vec3 feedPos() {
        Direction facing = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        return Vec3.atBottomCenterOf(worldPosition)
                .add(facing.getStepX() * .5f, 13 / 16f, facing.getStepZ() * .5f);
    }

    @Override
    protected void write(CompoundTag tag, Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putInt("Cooldown", cooldown);
    }

    @Override
    protected void read(CompoundTag tag, Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, productionCooldown());
    }

    @Override
    public void destroy() {
        super.destroy();
        ItemHelper.dropContents(level, worldPosition, inventory);
    }

    protected ItemStack tryInsertFrom(TransportedItemStack transported, Direction side, boolean simulate) {
        assert level != null;
        ItemStack stack = transported.stack.copy();
        if (feedItem(stack, simulate)) {
            if (!simulate)
                stack.shrink(1);
        }
        return stack;
    }

    public abstract boolean feedItem(ItemStack stack, boolean simulate);
}
