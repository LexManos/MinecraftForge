/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common.capabilities;

import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.items.IItemHandler;

/*
 * References to  Forge's built in capabilities.
 * Modders are recommended to use their own CapabilityTokens for 3rd party caps to maintain soft dependencies.
 * However, since nobody has a soft dependency on Forge, we expose this as API.
 */
public class ForgeCapabilities {
    public static final Capability<IEnergyStorage> ENERGY = CapabilityManager.get(new CapabilityToken<>(){});
    public static final Capability<IFluidHandler> FLUID_HANDLER = CapabilityManager.get(new CapabilityToken<>(){});
    public static final Capability<IFluidHandlerItem> FLUID_HANDLER_ITEM = CapabilityManager.get(new CapabilityToken<>(){});

    private static final CapabilityToken<IItemHandler> ITEM_HANDLER_TYPE = new CapabilityToken<>(){};
    public static final Capability<IItemHandler> ITEM_HANDLER = CapabilityManager.get(ITEM_HANDLER_TYPE);
    // This can be thought of as basically ITEM_HANDLER, but can be differentiated between others.
    public static final Capability<IItemHandler> ITEM_HANDLER_INVENTORY = CapabilityManager.get(ITEM_HANDLER_TYPE, "forge", "inventory");
    // IItemHandlers for specific EquipmentSlot.Type's
    public static final Capability<IItemHandler> ITEM_HANDLER_EQUIPMENT = CapabilityManager.get(ITEM_HANDLER_TYPE, "forge", "equipment");
    public static final Capability<IItemHandler> ITEM_HANDLER_EQUIPMENT_HANDS = CapabilityManager.get(ITEM_HANDLER_TYPE, "forge", "equipment_hands");
    public static final Capability<IItemHandler> ITEM_HANDLER_EQUIPMENT_ARMOR = CapabilityManager.get(ITEM_HANDLER_TYPE, "forge", "equipment_armor");
}
