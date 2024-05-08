/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common.capabilities.providers;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.EntityArmorInvWrapper;
import net.minecraftforge.items.wrapper.EntityHandsInvWrapper;

/**
 * Wrapper for {@link LivingEntity}s that exposes some of it's functionality as Forge Capabilities
 *
 * Currently exposed capabilities are: <pre>
 *   {@link ForgeCapabilities.ITEM_HANDLER_EQUIPMENT}
 *   {@link ForgeCapabilities.ITEM_HANDLER_EQUIPMENT_HANDS}
 *   {@link ForgeCapabilities.ITEM_HANDLER_EQUIPMENT_ARMOR}
 *   {@link ForgeCapabilities.ITEM_HANDLER}:
 *     If the direction is null: ITEM_HANDLER_EQUIPMENT
 *     if the direction is vertical: ITEM_HANDLER_EQUIPMENT_HANDS
 *     If the direction is horizontal: ITEM_HANDLER_EQUIPMENT_ARMOR
 * </pre>
 */
@ApiStatus.Internal
public class LivingEntityProvider implements ICapabilityProvider {
    private final LivingEntity entity;
    private final LazyOptional<IItemHandlerModifiable> hands;
    private final LazyOptional<IItemHandlerModifiable> armor;
    private final LazyOptional<IItemHandlerModifiable> equipment;

    public LivingEntityProvider(LivingEntity entity) {
        this.entity = entity;
        this.hands = LazyOptional.of(() -> new EntityHandsInvWrapper(entity));
        this.armor = LazyOptional.of(() -> new EntityArmorInvWrapper(entity));
        this.equipment = LazyOptional.of(() -> new CombinedInvWrapper(hands.orElse(null), armor.orElse(null)));
    }

    public void invalidate() {
        hands.invalidate();
        armor.invalidate();
        equipment.invalidate();
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction facing) {
        if (capability == ForgeCapabilities.ITEM_HANDLER && this.entity.isAlive()) {
             if (facing == null) equipment.cast();
             else if (facing.getAxis().isVertical()) return hands.cast();
             else if (facing.getAxis().isHorizontal()) return armor.cast();
        } else if (capability == ForgeCapabilities.ITEM_HANDLER_EQUIPMENT_HANDS && this.entity.isAlive()) {
            return hands.cast();
        } else if (capability == ForgeCapabilities.ITEM_HANDLER_EQUIPMENT_ARMOR && this.entity.isAlive()) {
            return armor.cast();
        } else if (capability == ForgeCapabilities.ITEM_HANDLER_EQUIPMENT && this.entity.isAlive()) {
            return equipment.cast();
        }
        return LazyOptional.empty();
    }

    @Deprecated(forRemoval = true, since = "1.20.6")
    public static LazyOptional<IItemHandlerModifiable>[] legacy(LivingEntity entity) {
        @SuppressWarnings("unchecked")
        LazyOptional<IItemHandlerModifiable>[] ret = new LazyOptional[3];
        var wrapper = new LivingEntityProvider(entity);
        ret[0] = wrapper.hands;
        ret[1] = wrapper.armor;
        ret[2] = wrapper.equipment;
        return ret;
    }
}
