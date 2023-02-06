/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;
import net.minecraftforge.network.NetworkRegistry.AttributeFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.util.AttributeKey;

public class NetworkInstance {
    private final ResourceLocation channelName;
    private final String networkProtocolVersion;
    private final BiPredicate<ConnectionType, String> clientValidator;
    private final BiPredicate<ConnectionType, String> serverValidator;
    private final Map<AttributeKey<?>, AttributeFactory<?>> attributes;
    private final IEventBus networkEventBus;

    NetworkInstance(ResourceLocation channelName, Supplier<String> networkProtocolVersion, BiPredicate<ConnectionType, String> clientValidator, BiPredicate<ConnectionType, String> serverValidator, Map<AttributeKey<?>, AttributeFactory<?>> attributes) {
        this.channelName = channelName;
        this.networkProtocolVersion = networkProtocolVersion.get();
        this.clientValidator = clientValidator;
        this.serverValidator = serverValidator;
        this.attributes = attributes;
        this.networkEventBus = BusBuilder.builder().setExceptionHandler(this::handleError).useModLauncher().build();
    }

    private void handleError(IEventBus iEventBus, Event event, IEventListener[] iEventListeners, int i, Throwable throwable) {
    }

    public ResourceLocation getChannelName() {
        return channelName;
    }

    public <T extends NetworkEvent> void addListener(Consumer<T> eventListener) {
        this.networkEventBus.addListener(eventListener);
    }

    public void addGatherListener(Consumer<NetworkEvent.GatherLoginPayloadsEvent> eventListener) {
        this.networkEventBus.addListener(eventListener);
    }

    public void registerObject(final Object object) {
        this.networkEventBus.register(object);
    }

    public void unregisterObject(final Object object) {
        this.networkEventBus.unregister(object);
    }

    boolean dispatch(final NetworkDirection side, final ICustomPacket<?> packet, final Connection manager) {
        final NetworkEvent.Context context = new NetworkEvent.Context(manager, side, packet.getIndex());
        this.networkEventBus.post(side.getEvent(packet, () -> context));
        return context.getPacketHandled();
    }

    String getNetworkProtocolVersion() {
        return networkProtocolVersion;
    }

    boolean tryServerVersionOnClient(ConnectionType type, String version) {
        return this.clientValidator.test(type, version);
    }

    boolean tryClientVersionOnServer(ConnectionType type, String version) {
        return this.serverValidator.test(type, version);
    }

    void dispatchGatherLogin(final List<NetworkRegistry.LoginPayload> loginPayloadList, boolean isLocal) {
        this.networkEventBus.post(new NetworkEvent.GatherLoginPayloadsEvent(loginPayloadList, isLocal));
    }

    void dispatchEvent(final NetworkEvent networkEvent) {
        this.networkEventBus.post(networkEvent);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    void attachAttributes(NetworkDirection direction, Connection connection, ConnectionType type) {
        this.attributes.forEach((AttributeKey k, AttributeFactory v) -> {
            connection.channel().attr(k).compareAndSet(null, v.create(direction, connection, type));
        });
    }

    public boolean isRemotePresent(Connection manager) {
        var connectionData = NetworkHooks.getConnectionData(manager);
        var channelList = NetworkHooks.getChannelList(manager);
        return (connectionData != null && connectionData.getChannels().containsKey(channelName))
                // if it's not in the fml connection data, let's check if it's sent by another modloader.
                || (channelList != null && channelList.getRemoteChannels().contains(channelName));
    }
}
