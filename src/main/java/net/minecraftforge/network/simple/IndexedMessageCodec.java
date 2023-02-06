/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network.simple;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectArrayMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class IndexedMessageCodec
{
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Marker SIMPLENET = MarkerManager.getMarker("SIMPLENET");
    private final Short2ObjectArrayMap<MessageHandler<?>> indicies = new Short2ObjectArrayMap<>();
    private final Object2ObjectArrayMap<Class<?>, MessageHandler<?>> types = new Object2ObjectArrayMap<>();
    private final NetworkInstance networkInstance;

    public IndexedMessageCodec() {
        this(null);
    }
    public IndexedMessageCodec(final NetworkInstance instance) {
        this.networkInstance = instance;
    }

    @SuppressWarnings("unchecked")
    public <MSG> MessageHandler<MSG> findMessageType(final MSG msgToReply) {
        return (MessageHandler<MSG>) types.get(msgToReply.getClass());
    }

    @SuppressWarnings("unchecked")
    <MSG> MessageHandler<MSG> findIndex(final short i) {
        return (MessageHandler<MSG>) indicies.get(i);
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    class MessageHandler<MSG>
    {
        private final Optional<BiConsumer<MSG, FriendlyByteBuf>> encoder;
        private final Optional<Function<FriendlyByteBuf, MSG>> decoder;
        private final int index;
        private final BiConsumer<MSG,Supplier<NetworkEvent.Context>> messageConsumer;
        private final Class<MSG> messageType;
        private final Optional<NetworkDirection> networkDirection;

        public MessageHandler(int index, Class<MSG> messageType, BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder, BiConsumer<MSG, Supplier<NetworkEvent.Context>> messageConsumer, final Optional<NetworkDirection> networkDirection)
        {
            this.index = index;
            this.messageType = messageType;
            this.encoder = Optional.ofNullable(encoder);
            this.decoder = Optional.ofNullable(decoder);
            this.messageConsumer = messageConsumer;
            this.networkDirection = networkDirection;
            indicies.put((short)(index & 0xff), this);
            types.put(messageType, this);
        }

        MSG newInstance() {
            try {
                return messageType.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                LOGGER.error("Invalid login message", e);
                throw new RuntimeException(e);
            }
        }
    }

    private static <M> void tryDecode(FriendlyByteBuf payload, Supplier<NetworkEvent.Context> context, MessageHandler<M> codec)
    {
        codec.decoder.map(d->d.apply(payload))
            .ifPresent(m->codec.messageConsumer.accept(m, context));
    }

    private static <M> void tryEncode(FriendlyByteBuf target, M message, MessageHandler<M> codec) {
        codec.encoder.ifPresent(encoder->{
            target.writeByte(codec.index & 0xff);
            encoder.accept(message, target);
        });
    }

    public <MSG> void build(MSG message, FriendlyByteBuf target)
    {
        @SuppressWarnings("unchecked")
        MessageHandler<MSG> messageHandler = (MessageHandler<MSG>)types.get(message.getClass());
        if (messageHandler == null) {
            LOGGER.error(SIMPLENET, "Received invalid message {} on channel {}", message.getClass().getName(), Optional.ofNullable(networkInstance).map(NetworkInstance::getChannelName).map(Objects::toString).orElse("MISSING CHANNEL"));
            throw new IllegalArgumentException("Invalid message "+message.getClass().getName());
        }
        tryEncode(target, message, messageHandler);
    }

    void consume(FriendlyByteBuf payload, Supplier<NetworkEvent.Context> context) {
        if (payload == null || !payload.isReadable()) {
            LOGGER.error(SIMPLENET, "Received empty payload on channel {}", Optional.ofNullable(networkInstance).map(NetworkInstance::getChannelName).map(Objects::toString).orElse("MISSING CHANNEL"));
            //context.get().setPacketHandled(true);
            // Without setting this to handled, it will disconnect. Is there any case where we would NOT want to disconnect a invalid packet on a known channel?
            return;
        }
        short discriminator = payload.readUnsignedByte();
        final MessageHandler<?> messageHandler = indicies.get(discriminator);
        if (messageHandler == null) {
            LOGGER.error(SIMPLENET, "Received invalid discriminator byte {} on channel {}", discriminator, Optional.ofNullable(networkInstance).map(NetworkInstance::getChannelName).map(Objects::toString).orElse("MISSING CHANNEL"));
            return;
        }
        NetworkHooks.validatePacketDirection(context.get().getDirection(), messageHandler.networkDirection, context.get().getConnection());
        tryDecode(payload, context, messageHandler);
    }

    <MSG> MessageHandler<MSG> addCodecIndex(int index, Class<MSG> messageType, BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder, BiConsumer<MSG, Supplier<NetworkEvent.Context>> messageConsumer, final Optional<NetworkDirection> networkDirection) {
        return new MessageHandler<>(index, messageType, encoder, decoder, messageConsumer, networkDirection);
    }
}
