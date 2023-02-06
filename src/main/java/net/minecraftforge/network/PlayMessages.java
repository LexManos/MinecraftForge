/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.LogicalSidedProvider;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class PlayMessages {
    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Used to spawn a custom entity without the same data restrictions as {@link ClientboundAddEntityPacket}.
     * This is the same exact format as {@link ClientboundAddEntityPacket}, with additional data at the end:
     * <pre>
     *   VarInt Length
     *   byte[Length] data
     * </pre>
     *
     * <p>
     * To customize how your entity is created clientside (instead of using the default factory provided to the
     * {@link EntityType})
     * see {@link EntityType.Builder#setCustomClientFactory}.
     */
    public record SpawnEntity(
        ClientboundAddEntityPacket vanillaData,
        @Nullable FriendlyByteBuf customData
    ) {
        SpawnEntity(Entity e) {
            this(new ClientboundAddEntityPacket(e), data(e) );
        }

        private static @Nullable FriendlyByteBuf data(Entity entity) {
            if (entity instanceof IEntityAdditionalSpawnData extra) {
                var data = new FriendlyByteBuf(Unpooled.buffer());
                extra.writeSpawnData(data);
                return data;
            }
            return null;
        }

        public static void encode(SpawnEntity msg, FriendlyByteBuf buf) {
            msg.vanillaData.write(buf);
            if (msg.customData == null)
                buf.writeVarInt(0);
            else {
                buf.writeVarInt(msg.customData.readableBytes());
                buf.writeBytes(msg.customData.slice());
            }
        }

        public static SpawnEntity decode(FriendlyByteBuf buf) {
            var vanillaData = new ClientboundAddEntityPacket(buf);
            var length = buf.readVarInt();
            if (length == 0)
                return new SpawnEntity(vanillaData, null);

            var customData = new FriendlyByteBuf(Unpooled.buffer());
            customData.writeBytes(buf, length);

            return new SpawnEntity(vanillaData, customData);
        }

        public static void handle(SpawnEntity msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                try {
                    EntityType<?> type = msg.vanillaData.getType();

                    Optional<Level> world = LogicalSidedProvider.CLIENTWORLD.get(ctx.get().getDirection().getReceptionSide());
                    Entity entity = world.map(w -> type.customClientSpawn(msg, w)).orElse(null);

                    if (entity == null) {
                        LOGGER.warn("Skipping Entity with id {}", type);
                        return;
                    }

                   entity.recreateFromPacket(msg.vanillaData);
                   world.filter(ClientLevel.class::isInstance).ifPresent(w -> ((ClientLevel) w).putNonPlayerEntity(msg.vanillaData.getId(), entity));
                   //this.postAddEntitySoundInstance(entity); No enter world sound for bees or minecarts. Modders can do this themselves.

                   if (msg.customData != null && entity instanceof IEntityAdditionalSpawnData extra)
                        extra.readSpawnData(msg.customData);

                } finally {
                    if (msg.customData != null)
                        msg.customData.release();
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Used to open a modded window on the client, gives the ability for extra data to be sent for context.
     * <pre>
     *   VarInt Menu Type
     *   VarInt Window ID
     *   UTF8String Window title comment in json format
     *   VarInt Length
     *   byte[Length] data
     * </pre>
     *
     */
    public record OpenContainer (
        MenuType<?> type,
        int windowId,
        Component name,
        FriendlyByteBuf additionalData
    ) {

        @SuppressWarnings("deprecation")
        public static void encode(OpenContainer msg, FriendlyByteBuf buf) {
            buf.writeVarInt(BuiltInRegistries.MENU.getId(msg.type));
            buf.writeVarInt(msg.windowId);
            buf.writeComponent(msg.name);
            buf.writeByteArray(msg.additionalData.readByteArray());
            msg.additionalData.readerIndex(0);
        }

        @SuppressWarnings("deprecation")
        public static OpenContainer decode(FriendlyByteBuf buf) {
            return new OpenContainer(BuiltInRegistries.MENU.byId(buf.readVarInt()), buf.readVarInt(), buf.readComponent(), new FriendlyByteBuf(Unpooled.wrappedBuffer(buf.readByteArray(32600))));
        }

        @SuppressWarnings("resource")
        public static void handle(OpenContainer msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                try {
                    MenuScreens.getScreenFactory(msg.type(), Minecraft.getInstance(), msg.windowId(), msg.name()).ifPresent(f -> {
                        AbstractContainerMenu c = msg.type().create(msg.windowId(), Minecraft.getInstance().player.getInventory(), msg.additionalData());

                        @SuppressWarnings("unchecked") Screen s = ((MenuScreens.ScreenConstructor<AbstractContainerMenu, ?>) f).create(c, Minecraft.getInstance().player.getInventory(), msg.name());
                        Minecraft.getInstance().player.containerMenu = ((MenuAccess<?>) s).getMenu();
                        Minecraft.getInstance().setScreen(s);
                    });
                } finally {
                    msg.additionalData().release();
                }

            });
            ctx.get().setPacketHandled(true);
        }
    }
}
