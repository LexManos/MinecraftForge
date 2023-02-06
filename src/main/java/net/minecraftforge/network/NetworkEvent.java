/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.common.util.LogicalSidedProvider;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class NetworkEvent extends Event {
    private final FriendlyByteBuf payload;
    private final Supplier<Context> source;

    private NetworkEvent(final ICustomPacket<?> payload, final Supplier<Context> source) {
        this.payload = payload.getInternalData();
        this.source = source;
    }

    public NetworkEvent(final Supplier<Context> source) {
        this.source = source;
        this.payload = null;
    }

    public FriendlyByteBuf getPayload() {
        return payload;
    }

    public Supplier<Context> getSource() {
        return source;
    }

    public static class ServerCustomPayloadEvent extends NetworkEvent {
        @ApiStatus.Internal
        ServerCustomPayloadEvent(final ICustomPacket<?> payload, final Supplier<Context> source) {
            super(payload, source);
        }
    }

    public static class ClientCustomPayloadEvent extends NetworkEvent {
        @ApiStatus.Internal
        ClientCustomPayloadEvent(final ICustomPacket<?> payload, final Supplier<Context> source) {
            super(payload, source);
        }
    }

    public static class ServerCustomPayloadLoginEvent extends ServerCustomPayloadEvent {
        @ApiStatus.Internal
        ServerCustomPayloadLoginEvent(ICustomPacket<?> payload, Supplier<Context> source) {
            super(payload, source);
        }
    }

    public static class ClientCustomPayloadLoginEvent extends ClientCustomPayloadEvent {
        @ApiStatus.Internal
        ClientCustomPayloadLoginEvent(ICustomPacket<?> payload, Supplier<Context> source) {
            super(payload, source);
        }
    }

    public static class GatherLoginPayloadsEvent extends Event {
        private final List<NetworkRegistry.LoginPayload> collected;
        private final boolean isLocal;

        @ApiStatus.Internal
        GatherLoginPayloadsEvent(final List<NetworkRegistry.LoginPayload> loginPayloadList, boolean isLocal) {
            this.collected = loginPayloadList;
            this.isLocal = isLocal;
        }

        public void add(FriendlyByteBuf buffer, ResourceLocation channelName, String context) {
            add(buffer, channelName, context, true);
        }

        public void add(FriendlyByteBuf buffer, ResourceLocation channelName, String context, boolean needsResponse) {
            collected.add(new NetworkRegistry.LoginPayload(buffer, channelName, context, needsResponse));
        }

        public boolean isLocal() {
            return isLocal;
        }
    }

    public enum RegistrationChangeType {
        REGISTER, UNREGISTER;
    }

    /**
     * Fired when the channel registration (see minecraft custom channel documentation) changes. Note the payload
     * is not exposed. This fires to the resource location that owns the channel, when it's registration changes state.
     *
     * It seems plausible that this will fire multiple times for the same state, depending on what the server is doing.
     * It just directly dispatches upon receipt.
     */
    public static class ChannelRegistrationChangeEvent extends NetworkEvent {
        private final RegistrationChangeType changeType;

        ChannelRegistrationChangeEvent(final Supplier<Context> source, RegistrationChangeType changeType) {
            super(source);
            this.changeType = changeType;
        }

        public RegistrationChangeType getRegistrationChangeType() {
            return this.changeType;
        }
    }
    /**
     * Context for {@link NetworkEvent}
     */
    public static class Context {
        /**
         * The {@link Connection} for this message.
         */
        private final Connection connection;

        /**
         * The {@link NetworkDirection} this message has been received on.
         */
        private final NetworkDirection direction;

        /**
         * The transactionID from the server for this packet
         * If this is not a LOGIN packet, then this value is meaningless.
         */
        private final int transactionID;

        private boolean packetHandled;

        Context(Connection connection, NetworkDirection direction, int index) {
            this.connection = connection;
            this.direction = direction;
            this.transactionID = index;
        }

        public NetworkDirection getDirection() {
            return direction;
        }

        public Connection getConnection() {
            return connection;
        }

        public void sendReply(final ResourceLocation channel, final FriendlyByteBuf buffer) {
            var packet = this.direction.reply().buildPacket(buffer, channel, transactionID);
            this.connection.send(packet.self());
        }

        public <T> Attribute<T> attr(AttributeKey<T> key) {
            return connection.channel().attr(key);
        }

        public void setPacketHandled(boolean handled) {
            this.packetHandled = handled;
        }

        public boolean getPacketHandled() {
            return packetHandled;
        }

        public CompletableFuture<Void> enqueueWork(Runnable runnable) {
            BlockableEventLoop<?> executor = LogicalSidedProvider.WORKQUEUE.get(getDirection().getReceptionSide());
            // Must check ourselves as Minecraft will sometimes delay tasks even when they are received on the client thread
            // Same logic as ThreadTaskExecutor#runImmediately without the join
            if (!executor.isSameThread()) {
                return executor.submitAsync(runnable); // Use the internal method so thread check isn't done twice
            } else {
                runnable.run();
                return CompletableFuture.completedFuture(null);
            }
        }

        /**
         * When available, gets the sender for packets that are sent from a client to the server.
         */
        @Nullable
        public ServerPlayer getSender() {
            if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)
                return listener.player;
            return null;
        }
    }
}
