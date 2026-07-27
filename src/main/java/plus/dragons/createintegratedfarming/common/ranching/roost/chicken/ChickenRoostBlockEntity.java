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

package plus.dragons.createintegratedfarming.common.ranching.roost.chicken;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.fluids.FluidStack;
import plus.dragons.createintegratedfarming.common.ranching.roost.AnimalRoostBlockEntity;
import plus.dragons.createintegratedfarming.common.registry.CIFDataMaps;
import plus.dragons.createintegratedfarming.common.registry.CIFLootTables;

public class ChickenRoostBlockEntity extends AnimalRoostBlockEntity {
    @Override
    protected ResourceKey<LootTable> productionLootTable() {
        return CIFLootTables.CHICKEN_ROOST;
    }

    public ChickenRoostBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public boolean feedItem(ItemStack stack, boolean simulate) {
        if (!canFeed())
            return false;
        var food = stack.getItemHolder().getData(CIFDataMaps.CHICKEN_FOOD_ITEMS);
        if (food == null)
            return false;
        if (simulate)
            return true;
        return feed(stack, food.usingConvertsTo().orElse(ItemStack.EMPTY), SoundEvents.CHICKEN_AMBIENT);
    }

    public int feedFluid(FluidStack fluid, boolean simulate) {
        if (!canFeed())
            return 0;
        var food = fluid.getFluidHolder().getData(CIFDataMaps.CHICKEN_FOOD_FLUIDS);
        if (food == null)
            return 0;
        if (simulate)
            return food.amount();
        return feed(ItemStack.EMPTY, ItemStack.EMPTY, SoundEvents.CHICKEN_AMBIENT) ? food.amount() : 0;
    }
}
