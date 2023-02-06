/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.LogicalSide;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("rawtypes")
public enum NetworkDirection {
    PLAY_TO_SERVER(NetworkEvent.ClientCustomPayloadEvent::new, LogicalSide.CLIENT, ServerboundCustomPayloadPacket.class, 1, (d, i, n) -> new ServerboundCustomPayloadPacket(n, d)),
    PLAY_TO_CLIENT(NetworkEvent.ServerCustomPayloadEvent::new, LogicalSide.SERVER, ClientboundCustomPayloadPacket.class, 0, (d, i, n) -> new ClientboundCustomPayloadPacket(n, d)),
    LOGIN_TO_SERVER(NetworkEvent.ClientCustomPayloadLoginEvent::new, LogicalSide.CLIENT, ServerboundCustomQueryPacket.class, 3, (d, i, n) -> new ServerboundCustomQueryPacket(i, d)),
    LOGIN_TO_CLIENT(NetworkEvent.ServerCustomPayloadLoginEvent::new, LogicalSide.SERVER, ClientboundCustomQueryPacket.class, 2, (d, i, n) -> new ClientboundCustomQueryPacket(i, n, d));

    private final BiFunction<ICustomPacket<?>, Supplier<NetworkEvent.Context>, NetworkEvent> eventSupplier;
    private final LogicalSide logicalSide;
    private final Class<? extends Packet> packetClass;
    private final int otherWay;
    private final Factory factory;
    private NetworkDirection reply;

    private static final Reference2ReferenceArrayMap<Class<? extends Packet>, NetworkDirection> packetLookup;

    static {
        var values = values();
        packetLookup = Stream.of(values).collect(Collectors.toMap(NetworkDirection::getPacketClass, Function.identity(), (m1,m2)->m1, Reference2ReferenceArrayMap::new));
        for (var value : values)
            value.reply = values[value.otherWay];
    }

    private NetworkDirection(BiFunction<ICustomPacket<?>, Supplier<NetworkEvent.Context>, NetworkEvent> eventSupplier, LogicalSide logicalSide, Class<? extends Packet> clazz, int i, Factory factory) {
        this.eventSupplier = eventSupplier;
        this.logicalSide = logicalSide;
        this.packetClass = clazz;
        this.otherWay = i;
        this.factory = factory;
    }

    private Class<? extends Packet> getPacketClass() {
        return packetClass;
    }

    public static <T extends ICustomPacket<?>> NetworkDirection directionFor(Class<T> customPacket) {
        return packetLookup.get(customPacket);
    }

    public NetworkDirection reply() {
        return this.reply;
    }

    public NetworkEvent getEvent(final ICustomPacket<?> buffer, final Supplier<NetworkEvent.Context> manager) {
        return this.eventSupplier.apply(buffer, manager);
    }

    public LogicalSide getOriginationSide() {
        return logicalSide;
    }

    public LogicalSide getReceptionSide() {
        return reply().logicalSide;
    }

    public <T extends Packet<?>> ICustomPacket<T> buildPacket(FriendlyByteBuf data, ResourceLocation channel) {
        return buildPacket(data, channel, 0);
    }

    @SuppressWarnings("unchecked")
    public <T extends Packet<?>> ICustomPacket<T> buildPacket(FriendlyByteBuf data, ResourceLocation channel, int index) {
        return this.factory.create(data, index, channel);
    }

    private interface Factory<T extends Packet<?>> {
        ICustomPacket<T> create(FriendlyByteBuf data, Integer index, ResourceLocation channelName);
    }
}
