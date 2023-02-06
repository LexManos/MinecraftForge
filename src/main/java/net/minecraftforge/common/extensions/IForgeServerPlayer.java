/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common.extensions;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;

public interface IForgeServerPlayer {
    private ServerPlayer self() {
        return (ServerPlayer)this;
    }


    /**
     * Request to open a GUI on the client, from the server
     *
     * Refer to {@link ConfigScreenHandler.ConfigScreenFactory} for how to provide a function to consume
     * these GUI requests on the client.
     *
     * @param menu A supplier of container properties including the registry name of the container
     */
    default OptionalInt openModdedMenu(MenuProvider menu) {
        return openModdedMenu(menu, buf -> {});
    }

    /**
     * Request to open a GUI on the client, from the server
     *
     * Refer to {@link ConfigScreenHandler.ConfigScreenFactory} for how to provide a function to consume
     * these GUI requests on the client.
     *
     * @param menu A supplier of container properties including the registry name of the container
     * @param pos A block pos, which will be encoded into the auxillary data for this request
     */
    default OptionalInt openModdedMenu(MenuProvider menu, BlockPos pos) {
        return openModdedMenu(menu, buf -> buf.writeBlockPos(pos));
    }

    /**
     * Request to open a GUI on the client, from the server
     * <p>
     * Refer to {@link ConfigScreenHandler.ConfigScreenFactory} for how to provide a function to consume
     * these GUI requests on the client.
     * <p>
     * The maximum size for #extraDataWriter is 32600 bytes.
     * <p>
     * See {@link ServerPlayer#openMenu} for implementation changes.
     *
     * @param menu A supplier of container properties including the registry name of the container
     * @param extraDataWriter Consumer to write any additional data the GUI needs
     * @throws IllegalArgumentException if extra data is larger then 32600 bytes
     */
    default OptionalInt openModdedMenu(MenuProvider menu, Consumer<FriendlyByteBuf> extraDataWriter) {
        var player = self();
        if (player.level.isClientSide)
            OptionalInt.empty();

        // Build extra data
        var output = new FriendlyByteBuf(Unpooled.buffer());
        extraDataWriter.accept(output);
        output.readerIndex(0); // reset to beginning in case modders read for whatever reason

        if (output.readableBytes() > 32600)
            throw new IllegalArgumentException("Invalid PacketBuffer for openModdedMenu, found " + output.readableBytes() + " bytes");


        if (player.containerMenu != player.inventoryMenu)
           player.closeContainer();

        player.nextContainerCounter();

        var container = menu.createMenu(player.containerCounter, player.getInventory(), player);
        if (container == null) {
            if (player.isSpectator())
                player.displayClientMessage(Component.translatable("container.spectatorCantOpen").withStyle(ChatFormatting.RED), true);

            return OptionalInt.empty();
        }

        NetworkHooks.sendOpenContainer(player, container, menu.getDisplayName(), output);

        player.initMenu(container);
        player.containerMenu = container;
        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, container));
        return OptionalInt.of(player.containerCounter);
    }
}
