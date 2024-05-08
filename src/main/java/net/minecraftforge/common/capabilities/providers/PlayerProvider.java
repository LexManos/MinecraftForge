/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common.capabilities.providers;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.PlayerArmorInvWrapper;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.items.wrapper.PlayerOffhandInvWrapper;

/**
 * Wrapper for {@link Player}s that exposes some of it's functionality as Forge Capabilities
 *
 * Currently exposed capabilities are: <pre>
 *   {@link ForgeCapabilities.ITEM_HANDLER_INVENTORY}
 *   {@link ForgeCapabilities.ITEM_HANDLER}:
 *     If the direction is null: ITEM_HANDLER_INVENTORY
 *     if the direction is vertical: Inventory without equipment
 *     If the direction is horizontal: Armor + off hand
 *
 * {@see LivingEntityProvider}
 * </pre>
 */
@ApiStatus.Internal
public class PlayerProvider implements ICapabilityProvider {
    private final Player player;
    private final LazyOptional<IItemHandler> items; // All the items in inventory except armor and offhand
    private final LazyOptional<IItemHandler> equipment; // Armor + Offhand
    private final LazyOptional<IItemHandler> inventory; // Everything in the inventory

    public PlayerProvider(Player player) {
        this.player = player;
        this.items = LazyOptional.of(() -> new PlayerMainInvWrapper(player.getInventory()));
        this.equipment = LazyOptional.of(() -> {
            return new CombinedInvWrapper(
                new PlayerArmorInvWrapper(player.getInventory()),
                new PlayerOffhandInvWrapper(player.getInventory())
            );
        });
        this.inventory = LazyOptional.of(() -> new PlayerInvWrapper(player.getInventory()));
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction facing) {
        if (capability == ForgeCapabilities.ITEM_HANDLER && this.player.isAlive()) {
            if (facing == null) return inventory.cast();
            else if (facing.getAxis().isVertical()) return items.cast();
            else if (facing.getAxis().isHorizontal()) return equipment.cast();
        } else if (capability == ForgeCapabilities.ITEM_HANDLER_INVENTORY && this.player.isAlive()) {
            return inventory.cast();
        }

        return LazyOptional.empty();
    }

    // TODO Invalidate player caps?
    public void invalidate() {
    }
}

