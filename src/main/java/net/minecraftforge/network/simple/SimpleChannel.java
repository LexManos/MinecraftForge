/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network.simple;

import io.netty.buffer.Unpooled;
import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkEvent.Context;
import net.minecraftforge.network.NetworkInstance;
import net.minecraftforge.network.PacketDistributor;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.ApiStatus;

import java.util.*;
import java.util.function.*;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@ApiStatus.Internal
public class SimpleChannel {
    private final NetworkInstance instance;
    private final IndexedMessageCodec indexedCodec;
    private final Optional<Consumer<NetworkEvent.ChannelRegistrationChangeEvent>> registryChangeConsumer;
    private List<Function<Boolean, ? extends List<? extends Pair<String,?>>>> loginPackets;
    private Map<Class<?>, Boolean> packetsNeedResponse;

    public SimpleChannel(NetworkInstance instance) {
        this(instance, Optional.empty());
    }

    private SimpleChannel(NetworkInstance instance, Optional<Consumer<NetworkEvent.ChannelRegistrationChangeEvent>> registryChangeNotify) {
        this.instance = instance;
        this.indexedCodec = new IndexedMessageCodec(instance);
        this.loginPackets = new ArrayList<>();
        this.packetsNeedResponse = new HashMap<>();
        instance.addListener(this::networkEventListener);
        instance.addGatherListener(this::networkLoginGather);
        this.registryChangeConsumer = registryChangeNotify;
    }

    private void networkLoginGather(final NetworkEvent.GatherLoginPayloadsEvent gatherEvent) {
        loginPackets.forEach(packetGenerator->{
            packetGenerator.apply(gatherEvent.isLocal()).forEach(p->{
                FriendlyByteBuf pb = new FriendlyByteBuf(Unpooled.buffer());
                this.indexedCodec.build(p.getRight(), pb);
                gatherEvent.add(pb, this.instance.getChannelName(), p.getLeft(), packetsNeedResponse.getOrDefault(p.getRight().getClass(), true));
            });
        });
    }

    private void networkEventListener(final NetworkEvent networkEvent) {
        if (networkEvent instanceof NetworkEvent.ChannelRegistrationChangeEvent) {
            this.registryChangeConsumer.ifPresent(l->l.accept(((NetworkEvent.ChannelRegistrationChangeEvent) networkEvent)));
        } else {
            this.indexedCodec.consume(networkEvent.getPayload(), networkEvent.getSource());
        }
    }

    public <MSG> void encodeMessage(MSG message, final FriendlyByteBuf target) {
        this.indexedCodec.build(message, target);
    }

    public <MSG> IndexedMessageCodec.MessageHandler<MSG> registerMessage(int index, Class<MSG> messageType, BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder, BiConsumer<MSG, Supplier<NetworkEvent.Context>> messageConsumer) {
        return registerMessage(index, messageType, encoder, decoder, messageConsumer, Optional.empty());
    }

    public <MSG> IndexedMessageCodec.MessageHandler<MSG> registerMessage(int index, Class<MSG> messageType, BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder, BiConsumer<MSG, Supplier<NetworkEvent.Context>> messageConsumer, final Optional<NetworkDirection> networkDirection) {
        return this.indexedCodec.addCodecIndex(index, messageType, encoder, decoder, messageConsumer, networkDirection);
    }

    private <MSG> FriendlyByteBuf toBuffer(MSG msg) {
        final FriendlyByteBuf bufIn = new FriendlyByteBuf(Unpooled.buffer());
        encodeMessage(msg, bufIn);
        return bufIn;
    }

    public <MSG> void sendToServer(MSG message) {
        sendTo(message, Minecraft.getInstance().getConnection().getConnection(), NetworkDirection.PLAY_TO_SERVER);
    }

    public <MSG> void sendTo(MSG message, Connection manager, NetworkDirection direction) {
        manager.send(toVanillaPacket(message, direction));
    }

    /**
     * Send a message to the {@link PacketDistributor.PacketTarget} from a {@link PacketDistributor} instance.
     *
     * <pre>
     *     channel.send(PacketDistributor.PLAYER.with(()->player), message)
     * </pre>
     *
     * @param target The curried target from a PacketDistributor
     * @param message The message to send
     * @param <MSG> The type of the message
     */
    public <MSG> void send(PacketDistributor.PacketTarget target, MSG message) {
        target.send(toVanillaPacket(message, target.getDirection()));
    }

    /**
     * This is public only for NetworkHooks.getEntitySpawningPacket. All other uses should use the send functions.
     * Only builds a packet for PLAY_TO_SERVER, or PLAY_TO_CLIENT
     * Login packets have their own mechanic so we can track transaction ids.
     */
    @ApiStatus.Internal
    @Deprecated
    public <MSG> Packet<?> toVanillaPacket(MSG message, NetworkDirection direction) {
        if (direction != NetworkDirection.PLAY_TO_SERVER && direction != NetworkDirection.PLAY_TO_CLIENT)
            throw new IllegalArgumentException("Invalid direction argument, must be PLAY_TO_CLIENT or PLAY_TO_SERVER, but was " + direction.name());
        return direction.buildPacket(toBuffer(message), instance.getChannelName()).self();
    }

    public <MSG> void reply(MSG msgToReply, NetworkEvent.Context context) {
        context.sendReply(instance.getChannelName(), toBuffer(msgToReply));
    }

    /**
     * Returns true if the channel is present in the given connection.
     */
    public boolean isRemotePresent(Connection manager) {
        return instance.isRemotePresent(manager);
    }

    /**
     * Build a new MessageBuilder.
     * @param type Type of message
     * @param id id in the indexed codec
     * @param <M> Type of type
     * @return a MessageBuilder
     */
    public <M> MessageBuilder<M> messageBuilder(final Class<M> type, int id) {
        return MessageBuilder.forType(this, type, id, null);
    }

    /**
     * Build a new MessageBuilder.
     * @param type Type of message
     * @param id id in the indexed codec
     * @param direction a impl direction which will be asserted before any processing of this message occurs. Use to
     *                  enforce strict sided handling to prevent spoofing.
     * @param <M> Type of type
     * @return a MessageBuilder
     */
    public <M> MessageBuilder<M> messageBuilder(final Class<M> type, int id, NetworkDirection direction) {
        return MessageBuilder.forType(this, type, id, direction);
    }

    public static class MessageBuilder<MSG>  {
        private SimpleChannel channel;
        private Class<MSG> type;
        private int id;
        private BiConsumer<MSG, FriendlyByteBuf> encoder;
        private Function<FriendlyByteBuf, MSG> decoder;
        private BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer;
        private Function<Boolean, List<Pair<String, MSG>>> loginPacketGenerators;
        private Optional<NetworkDirection> networkDirection;
        private boolean needsResponse = true;

        private static <MSG> MessageBuilder<MSG> forType(final SimpleChannel channel, final Class<MSG> type, int id, NetworkDirection networkDirection) {
            MessageBuilder<MSG> builder = new MessageBuilder<>();
            builder.channel = channel;
            builder.id = id;
            builder.type = type;
            builder.networkDirection = Optional.ofNullable(networkDirection);
            return builder;
        }

        /**
         * Set the message encoder, which writes this message to a {@link FriendlyByteBuf}.
         * <p>
         * The encoder is called <em>immediately</em> {@linkplain #send(PacketDistributor.PacketTarget, Object) when the
         * packet is sent}. This means encoding typically occurs on the main server/client thread rather than on the
         * network thread.
         * <p>
         * However, this behaviour should not be relied on, and the encoder should try to be thread-safe and not
         * interact with the current game state.
         *
         * @param encoder The message encoder.
         * @return This message builder, for chaining.
         */
        public MessageBuilder<MSG> encoder(BiConsumer<MSG, FriendlyByteBuf> encoder) {
            this.encoder = encoder;
            return this;
        }

        /**
         * Set the message decoder, which reads the message from a {@link FriendlyByteBuf}.
         * <p>
         * The decoder is called when the message is received on the network thread. The decoder should not attempt to
         * access or mutate any game state, deferring that until the {@linkplain #consumer(ToBooleanBiFunction) the
         * message is handled}.
         *
         * @param decoder The message decoder.
         * @return The message builder, for chaining.
         */
        public MessageBuilder<MSG> decoder(Function<FriendlyByteBuf, MSG> decoder) {
            this.decoder = decoder;
            return this;
        }

        public MessageBuilder<MSG> buildLoginPacketList(Function<Boolean, List<Pair<String,MSG>>> loginPacketGenerators) {
            this.loginPacketGenerators = loginPacketGenerators;
            return this;
        }

        @SuppressWarnings("deprecation")
        public MessageBuilder<MSG> markAsLoginPacket() {
            this.loginPacketGenerators = (isLocal) -> {
                try {
                    return Collections.singletonList(Pair.of(type.getName(), type.newInstance()));
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new RuntimeException("Inaccessible no-arg constructor for message "+ type.getName(), e);
                }
            };
            return this;
        }

        /**
         * Marks this packet as not needing a response when sent to the client
         */
        public MessageBuilder<MSG> noResponse() {
            this.needsResponse = false;
            return this;
        }

        /**
         * Set the message consumer, which is called once a message has been decoded. This accepts the decoded message
         * object and the message's context.
         * <p>
         * The consumer is called on the network thread, and so should not interact with most game state by default.
         * {@link NetworkEvent.Context#enqueueWork(Runnable)} can be used to handle the message on the main server or
         * client thread. Alternatively one can use {@link #consumerMainThread(BiConsumer)} to run the handler on the
         * main thread.
         *
         * @param consumer The message consumer.
         * @return The message builder, for chaining.
         * @see #consumerMainThread(BiConsumer)
         */
        public MessageBuilder<MSG> consumerNetworkThread(BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer) {
            this.consumer = consumer;
            return this;
        }

        /**
         * Set the message consumer, which is called once a message has been decoded. This accepts the decoded message
         * object and the message's context.
         * <p>
         * Unlike {@link #consumerNetworkThread(BiConsumer)}, the consumer is called on the main thread, and so can
         * interact with most game state by default.
         *
         * @param consumer The message consumer.
         * @return The message builder, for chaining.
         * @see #consumerNetworkThread(BiConsumer)
         */
        public MessageBuilder<MSG> consumerMainThread(BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer) {
            this.consumer = (msg, context) -> {
                var ctx = context.get();
                ctx.enqueueWork(() -> consumer.accept(msg, context));
                ctx.setPacketHandled(true);
            };
            return this;
        }

        public interface ToBooleanBiFunction<T, U> {
            boolean applyAsBool(T first, U second);
        }
        public interface ToBooleanTriFunction<T, U, V> {
            boolean applyAsBool(T first, U second, V third);
        }
        public interface TriConsumer<T, U, V> {
            void accept(T first, U second, V third);
        }

        /**
         * Function returning a boolean "packet handled" indication, for simpler channel building.
         * @param handler a handler
         * @return this
         * @see #consumerNetworkThread(BiConsumer)
         */
        public MessageBuilder<MSG> consumerNetworkThread(ToBooleanBiFunction<MSG, Supplier<NetworkEvent.Context>> handler) {
            this.consumer = (msg, ctx) -> {
                boolean handled = handler.applyAsBool(msg, ctx);
                ctx.get().setPacketHandled(handled);
            };
            return this;
        }

        public void add() {
            this.channel.registerMessage(this.id, this.type, this.encoder, this.decoder, this.consumer, this.networkDirection);
            if (this.loginPacketGenerators != null) {
                this.channel.loginPackets.add(this.loginPacketGenerators);
            }
            this.channel.packetsNeedResponse.put(this.type, this.needsResponse);
        }

        public class Typed<T> {
            private final Function<Supplier<NetworkEvent.Context>, T> context;

            public Typed(AttributeKey<T> key) {
                // In theory this should only reference the local variables, so the builder can be GCed
                context = ctx -> ctx.get().attr(key).get();
            }

            /**
             * Function returning a boolean "packet handled" indication, for simpler channel building.
             * @see MessageBuilder#consumerNetworkThread(BiConsumer)
             */
            public MessageBuilder<MSG> consumerNetworkThread(ToBooleanTriFunction<T, MSG, Supplier<NetworkEvent.Context>> handler) {
                final var instance = context;
                return MessageBuilder.this.consumerNetworkThread((ToBooleanBiFunction<MSG, Supplier<Context>>)(msg, ctx) -> handler.applyAsBool(instance.apply(ctx), msg, ctx));
            }

            /**
             * Set the message consumer, which is called once a message has been decoded. This accepts the decoded message
             * object and the message's context.
             * <p>
             * The consumer is called on the network thread, and so should not interact with most game state by default.
             * {@link NetworkEvent.Context#enqueueWork(Runnable)} can be used to handle the message on the main server or
             * client thread. Alternatively one can use {@link #consumerMainThread(BiConsumer)} to run the handler on the
             * main thread.
             *
             * @param consumer The message consumer.
             * @return The message builder, for chaining.
             * @see #consumerMainThread(BiConsumer)
             */
            public MessageBuilder<MSG> consumerNetworkThread(TriConsumer<T, MSG, Supplier<NetworkEvent.Context>> consumer) {
                final var instance = context;
                return MessageBuilder.this.consumerNetworkThread((BiConsumer<MSG, Supplier<NetworkEvent.Context>>)(msg, ctx) -> consumer.accept(instance.apply(ctx), msg, ctx));
            }

            /**
             * Set the message consumer, which is called once a message has been decoded. This accepts the decoded message
             * object and the message's context.
             * <p>
             * Unlike {@link #consumerNetworkThread(BiConsumer)}, the consumer is called on the main thread, and so can
             * interact with most game state by default.
             *
             * @param consumer The message consumer.
             * @return The message builder, for chaining.
             * @see #consumerNetworkThread(BiConsumer)
             */
            public MessageBuilder<MSG> consumerMainThread(TriConsumer<T, MSG, Supplier<NetworkEvent.Context>> consumer) {
                final var instance = context;
                return MessageBuilder.this.consumerMainThread((msg, ctx) -> consumer.accept(instance.apply(ctx), msg, ctx));
            }
        }

        public <T> Typed<T> context(AttributeKey<T> attribute) {
            return new Typed<>(attribute);
        }
    }
}
