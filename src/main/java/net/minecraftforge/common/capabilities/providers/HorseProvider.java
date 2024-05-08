/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common.capabilities.providers;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.InvWrapper;
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
 *   	Alias to ITEM_HANDLER_INVENTORY
 *
 * {@see LivingEntityProvider}
 * </pre>
 */
@ApiStatus.Internal
public class HorseProvider implements ICapabilityProvider {
    private final AbstractHorse horse;
    private LazyOptional<IItemHandler> inventory;

    public HorseProvider(AbstractHorse horse) {
        this.horse = horse;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction facing) {
        if (inventory == null)
            return LazyOptional.empty();

        if (capability == ForgeCapabilities.ITEM_HANDLER && this.horse.isAlive()) {
            return inventory.cast();
        } else if (capability == ForgeCapabilities.ITEM_HANDLER_INVENTORY && this.horse.isAlive()) {
            return inventory.cast();
        }

        return LazyOptional.empty();
    }

    public void invalidate() {
        if (this.inventory == null)
            return;
        var tmp = this.inventory;
        this.inventory = null;
        tmp.invalidate();
    }

    public void createInventory(SimpleContainer container) {
        var tmp = this.inventory;

        this.inventory = LazyOptional.of(() -> new InvWrapper(container));

        if (tmp != null)
            tmp.invalidate();
    }
}

