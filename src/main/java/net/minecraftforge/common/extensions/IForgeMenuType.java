/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common.extensions;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;

public interface IForgeMenuType<T> {
    static <T extends AbstractContainerMenu> MenuType<T> create(IContainerFactory<T> factory) {
        return new MenuType<>(factory);
    }

    T create(int windowId, Inventory playerInv, FriendlyByteBuf extraData);

    public interface IContainerFactory<T extends AbstractContainerMenu> extends MenuType.MenuSupplier<T> {
        T create(int windowId, Inventory inv, @Nullable FriendlyByteBuf data);

        @Override
        default T create(int windowId, Inventory inv) {
            return create(windowId, inv, null);
        }
    }
}
