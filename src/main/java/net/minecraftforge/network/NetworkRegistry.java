/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DataPackRegistriesHooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.ApiStatus;

import io.netty.util.AttributeKey;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The impl registry. Tracks channels on behalf of mods.
 */
public class NetworkRegistry {
    private static final Logger LOGGER = LogManager.getLogger();
    static final Marker NETREGISTRY = MarkerManager.getMarker("NETREGISTRY");

    private static Map<ResourceLocation, NetworkInstance> instances = Collections.synchronizedMap(new HashMap<>());
    private static Map<ResourceLocation, NetworkInstance> instancesView = Collections.unmodifiableMap(instances);

    public static List<String> getServerNonVanillaNetworkMods() {
        return listRejectedVanillaMods(false);
    }

    public static List<String> getClientNonVanillaNetworkMods() {
        return listRejectedVanillaMods(true);
    }

    public static boolean acceptsVanillaClientConnections() {
        return (instances.isEmpty() || getServerNonVanillaNetworkMods().isEmpty()) && DataPackRegistriesHooks.getSyncedCustomRegistries().isEmpty();
    }

    public static boolean canConnectToVanillaServer() {
        return instances.isEmpty() || getClientNonVanillaNetworkMods().isEmpty();
    }

    private static boolean lock = false;
    @ApiStatus.Internal // Called by NETLOCK state. Prevents modders from creating more channels.
    public static void lock() {
        lock = true;
    }

    /**
     * Creates the internal {@link NetworkInstance} that tracks the channel data.
     * @param name registry name
     * @param networkProtocolVersion The protocol version string
     * @param clientValidator The client accepted predicate
     * @param serverValidator The server accepted predicate
     * @return The {@link NetworkInstance}
     * @throws IllegalArgumentException if the name already exists
     */
    private static NetworkInstance createInstance(ResourceLocation name, Supplier<String> networkProtocolVersion,
        BiPredicate<ConnectionType, String> clientValidator, BiPredicate<ConnectionType, String> serverValidator,
        Map<AttributeKey<?>, AttributeFactory<?>> attributes
    ) {
        if (lock) {
            LOGGER.error(NETREGISTRY, "Attempted to register channel {} even though registry phase is over", name);
            throw new IllegalArgumentException("Registration of impl channels is locked");
        }
        if (instances.containsKey(name)) {
            LOGGER.error(NETREGISTRY, "NetworkDirection channel {} already registered.", name);
            throw new IllegalArgumentException("NetworkDirection Channel {" + name + "} already registered");
        }
        final NetworkInstance networkInstance = new NetworkInstance(name, networkProtocolVersion, clientValidator, serverValidator, attributes);
        instances.put(name, networkInstance);
        return networkInstance;
    }

    /**
     * Find the {@link NetworkInstance}, if possible
     *
     * @param name The impl instance to lookup
     * @return The {@link Optional} {@link NetworkInstance}
     */
    static Optional<NetworkInstance> findTarget(ResourceLocation name) {
        return Optional.ofNullable(instances.get(name));
    }

    /**
     * A simple helper function to get the list of channels, and their protocol versions.
     * Used in multiple places so kept during cleanup.
     */
    static Map<ResourceLocation, String> networkVersions() {
        return networks().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getNetworkProtocolVersion()));
    }

    static Map<ResourceLocation, NetworkInstance> networks() {
        return instancesView;
    }

    private static List<String> listRejectedVanillaMods(boolean testS2C) {
        var rejected = instances.values().stream()
            .filter(ni -> {
                var accepted = testS2C ?
                    ni.tryServerVersionOnClient(ConnectionType.VANILLA, null) :
                    ni.tryClientVersionOnServer(ConnectionType.VANILLA, null);

                LOGGER.debug(NETREGISTRY, "Channel '{}' : Vanilla acceptance test: {}", ni.getChannelName(), accepted ? "ACCEPTED" : "REJECTED");
                return !accepted;
            })
            .map(NetworkInstance::getChannelName)
            .map(Object::toString)
            .collect(Collectors.toList());

        if (!rejected.isEmpty()) {
            LOGGER.error(NETREGISTRY, "Channels [{}] rejected vanilla connections", rejected.stream().collect(Collectors.joining(",")));
            return rejected.stream().collect(Collectors.toList());
        }

        LOGGER.debug(NETREGISTRY, "Accepting channel list from vanilla");
        return Collections.emptyList();
    }

    record LoginPayload(FriendlyByteBuf data, ResourceLocation channelName, String messageContext, boolean needsResponse) {}

    public static interface AttributeFactory<T> {
        /**
         * Creates a new instance to be attached to the specified connection.
         * @param direction Either {@link NetworkDirection#LOGIN_TO_CLIENT} or {@link NetworkDirection#LOGIN_TO_SERVER}
         * @param connection Vanilla's connection object
         * @param type Will return the correct ConnectionType for {@link NetworkDirection#LOGIN_TO_CLIENT}, However will always return
         * 		{@link ConnectionType#VANILLA} for {@link NetworkDirection#LOGIN_TO_SERVER} as the server hasn't identified itself yet.
         * @return A new instance of this attribute object, which will be attached to the supplied connection.
         */
        T create(NetworkDirection direction, Connection connection, ConnectionType type);
    }

    /**
     * Builder for constructing impl channels using a builder style API.
     */
    public static class ChannelBuilder {
        private final ResourceLocation channelName;
        private Supplier<String> networkProtocolVersion;
        private BiPredicate<ConnectionType, String> clientVersionTest;
        private BiPredicate<ConnectionType, String> serverVersionTest;
        private Map<AttributeKey<?>, AttributeFactory<?>> attributes = new HashMap<>();

        /**
         * The name of the channel. Must be unique.
         * @param channelName The name of the channel
         * @return the channel builder
         */
        public static ChannelBuilder named(ResourceLocation channelName) {
            return new ChannelBuilder(channelName);
        }

        private ChannelBuilder(ResourceLocation name) {
            this.channelName = name;
        }

        /**
         * The impl protocol string for this channel. This will be gathered during login and sent to
         * the remote partner, where it will be tested with against the relevant predicate.
         *
         * @param version A supplier of strings for impl protocol version testing
         * @return the channel builder
         */
        public ChannelBuilder networkProtocolVersion(String version) {
            return networkProtocolVersion(() -> version);
        }

        /**
         * The impl protocol string for this channel. This will be gathered during login and sent to
         * the remote partner, where it will be tested with against the relevant predicate.
         *
         * @param networkProtocolVersion A supplier of strings for impl protocol version testing
         * @return the channel builder
         */
        public ChannelBuilder networkProtocolVersion(Supplier<String> networkProtocolVersion) {
            if (this.networkProtocolVersion != null)
                throw new IllegalStateException("You can only set the protocol version once");
            this.networkProtocolVersion = networkProtocolVersion;
            return this;
        }

        /**
         * A predicate run on both sides, with the {@link #networkProtocolVersion(Supplier)} string from
         * the remote side, or the special value null indicating the absence of
         * the channel on the remote side.
         * @param validator A predicate for testing the version string
         * @return the channel builder
         */
        public ChannelBuilder acceptedVersions(Predicate<String> validator) {
            return acceptedVersions((type, ver) -> validator.test(ver));
        }

        /**
         * A predicate run on both sides, with the {@link #networkProtocolVersion(Supplier)} string from
         * the remote side, or the special value null indicating the absence of
         * the channel on the remote side. As well as the ConnectionType for this connection.
         * @param validator A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder acceptedVersions(BiPredicate<ConnectionType, String> validator) {
            return clientAcceptedVersions(validator)
                  .serverAcceptedVersions(validator);
        }

        /**
         * A predicate run on the client, with the {@link #networkProtocolVersion(Supplier)} string from
         * the server, or the special value null indicating the absence of
         * the channel on the remote side.
         * @param validator A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder clientAcceptedVersions(Predicate<String> validator) {
            return clientAcceptedVersions((type, ver) -> validator.test(ver));
        }

        /**
         * A predicate run on the client, with the {@link #networkProtocolVersion(Supplier)} string from
         * the server, or the special value null indicating the absence of
         * the channel on the remote side. As well as the ConnectionType for this connection.
         * @param validator A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder clientAcceptedVersions(BiPredicate<ConnectionType, String> validator) {
            this.clientVersionTest = validator;
            return this;
        }

        /**
         * A predicate run on the server, with the {@link #networkProtocolVersion(Supplier)} string from
         * the client, or the special value null indicating the absence of
         * the channel on the remote side.
         * @param validator A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder serverAcceptedVersions(Predicate<String> validator) {
            return serverAcceptedVersions((type, ver) -> validator.test(ver));
        }

        /**
         * A predicate run on the server, with the {@link #networkProtocolVersion(Supplier)} string from
         * the client, or the special value null indicating the absence of
         * the channel on the remote side. As well as the ConnectionType for this connection.
         * @param validator A predicate for testing
         * @return the channel builder
         */
        public ChannelBuilder serverAcceptedVersions(BiPredicate<ConnectionType, String> validator) {
            this.serverVersionTest = validator;
            return this;
        }

        /**
         * Sets both the client and server version tests to match the exact version specified by {@link #networkProtocolVersion(Supplier)}
         * @throws IllegalStateEception if {@link #networkProtocolVersion(Supplier)} has not been set.
         */
        public ChannelBuilder exactVersionOnly() {
            if (this.networkProtocolVersion == null)
                throw new IllegalStateException("Must set protocol version before setting version filter");

            final var version = this.networkProtocolVersion;
            return acceptedVersions((type, ver) -> version.get().equals(ver));
        }

        /**
         * Sets both the client and server version tests so that it is successful when:<br />
         * Remote version matches the exact version specified by {@link #networkProtocolVersion(Supplier)} <br />
         * or <br />
         * Remote version is missing
         * @throws IllegalStateEception if {@link #networkProtocolVersion(Supplier)} has not been set.
         */
        public ChannelBuilder exactVersionOrMissing() {
            return exactVersionOrMissingServer()
                  .exactVersionOrMissingClient();
        }

        /**
         * Sets the client version tests so that it is successful when:<br />
         * Server version matches the exact version specified by {@link #networkProtocolVersion(Supplier)} <br />
         * or <br />
         * Server version is missing
         * @throws IllegalStateEception if {@link #networkProtocolVersion(Supplier)} has not been set.
         */
        public ChannelBuilder exactVersionOrMissingServer() {
            if (this.networkProtocolVersion == null)
                throw new IllegalStateException("Must set protocol version before setting version filter");

            final var version = this.networkProtocolVersion;
            return clientAcceptedVersions((type, ver) -> ver == null || version.get().equals(ver));
        }

        /**
         * Sets the server version tests so that it is successful when:<br />
         * Client version matches the exact version specified by {@link #networkProtocolVersion(Supplier)} <br />
         * or <br />
         * Client version is missing
         * @throws IllegalStateEception if {@link #networkProtocolVersion(Supplier)} has not been set.
         */
        public ChannelBuilder exactVersionOrMissingClient() {
            if (this.networkProtocolVersion == null)
                throw new IllegalStateException("Must set protocol version before setting version filter");

            final var version = this.networkProtocolVersion;
            return serverAcceptedVersions((type, ver) -> ver == null || version.get().equals(ver));
        }

        /**
         * Sets both the client and server version tests to match anything.
         */
        public ChannelBuilder anyVersion() {
            return acceptedVersions((type, ver) -> true);
        }

        /**
         * Registers an attribute that will be attached to any new connection that is opened.
         * @see AttributeFactory#create
         *
         * @param key The unique attribute key, must begin with this channels name.
         * @param factory A factory that creates the attribute instance
         */
        public <T> ChannelBuilder attribute(AttributeKey<T> key, AttributeFactory<T> factory) {
            if (!key.name().startsWith(this.channelName.toString()))
                throw new IllegalArgumentException("Invalid attribute key " + key.name() + ", must begin with " + this.channelName);
            this.attributes.put(key, factory);
            return this;
        }

        /**
         * Create the impl instance
         * @return the {@link NetworkInstance}
         */
        private NetworkInstance createNetworkInstance() {
            return createInstance(channelName, networkProtocolVersion, clientVersionTest, serverVersionTest, attributes);
        }

        /**
         * Build a new {@link SimpleChannel} with this builder's configuration.
         *
         * @return A new {@link SimpleChannel}
         */
        public SimpleChannel simpleChannel() {
            return new SimpleChannel(createNetworkInstance());
        }

        /**
         * Build a new {@link EventNetworkChannel} with this builder's configuration.
         * @return A new {@link EventNetworkChannel}
         */
        public EventNetworkChannel eventNetworkChannel() {
            return new EventNetworkChannel(createNetworkInstance());
        }
    }
}
