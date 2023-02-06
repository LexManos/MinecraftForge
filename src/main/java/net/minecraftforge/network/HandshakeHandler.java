/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LogMessageAdapter;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.network.ConnectionData.ModMismatchData;
import net.minecraftforge.network.NetworkRegistry.LoginPayload;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DataPackRegistriesHooks;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.ApiStatus;
import com.google.common.collect.Maps;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.minecraftforge.registries.ForgeRegistry.REGISTRIES;

/**
 * Instance responsible for handling the overall FML handshake.
 *
 * <p>An instance is created during {@link ClientIntentionPacket} handling, and attached
 * to the {@link Connection#channel()} via {@link NetworkConstants#FML_HANDSHAKE_HANDLER FML_HANDSHAKE_HANDLER}.
 *
 * <p>The {@link NetworkConstants#handshakeChannel} is a {@link SimpleChannel} with standard messages flowing in both directions.
 *
 * <p>The handshake is ticked {@link #tickLogin(NetworkManager)} from the {@link ServerLoginPacketListenerImpl#tick()} method,
 * utilizing the {@link ServerLoginPacketListenerImpl.State#NEGOTIATING NEGOTIATING} state, which is otherwise unused in vanilla code.
 *
 * <p>During client to server initiation, on the <em>server</em>, the {@link GatherLoginPayloadsEvent} is fired,
 * which solicits all registered channels at the {@link NetworkRegistry} for any
 * {@link LoginPayload} they wish to supply.
 *
 * <p>The collected {@link LoginPayload} are sent, one per tick, to the incoming client connection. Each
 * packet is indexed via {@link ServerboundCustomQueryPacket#getTransactionId() transaction id}, which is
 * the only mechanism available for tracking request/response pairs.
 *
 * <p>Each packet sent from the server may require a response from the client by specifying it in the {@link LoginPayload#needsResponse}.
 *
 * <p>Once all packets have been dispatched, we wait for all replies to be received. Once all replies are received, the
 * final login phase will commence.
 */
@ApiStatus.Internal
public class HandshakeHandler {
    private static final Marker FMLHSMARKER = MarkerManager.getMarker("FMLHANDSHAKE").setParents(NetworkConstants.NETWORK);
    private static final Logger LOGGER = LogManager.getLogger();

    static boolean tickLogin(Connection networkManager) {
        return networkManager.channel().attr(NetworkConstants.FML_HANDSHAKE_HANDLER).get().tickServer();
    }

    private List<NetworkRegistry.LoginPayload> messageList;
    private Map<Integer, ResourceLocation> sentMessages = new HashMap<>();

    private final NetworkDirection direction;
    private final Connection connection;
    private int packetPosition;
    private Map<ResourceLocation, ForgeRegistry.Snapshot> registrySnapshots;
    private Set<ResourceLocation> registriesToReceive;
    private boolean negotiationStarted = false;
    private final List<Future<Void>> pendingFutures = new ArrayList<>();

    HandshakeHandler(NetworkDirection side, Connection connection, ConnectionType type) {
        this.direction = side;
        this.connection = connection;
        if (type == ConnectionType.VANILLA) {
            this.messageList = Collections.emptyList();
            LOGGER.debug(FMLHSMARKER, "Starting new vanilla impl connection.");
        } else {
            var local = connection.isMemoryConnection();
            this.messageList = gatherLoginPayloads(this.direction, local);
            LOGGER.debug(FMLHSMARKER, "Starting new {} modded connection. Found {} messages to dispatch.", local ? "local" : "", this.messageList.size());
        }
    }

    @FunctionalInterface
    public interface HandshakeConsumer<MSG> {
        void accept(HandshakeHandler handler, MSG msg, Supplier<NetworkEvent.Context> context);
    }

    void handleServerModListOnClient(HandshakeMessages.S2CModList serverModList, Supplier<NetworkEvent.Context> c) {
        LOGGER.debug(FMLHSMARKER, "Logging into server with mod list [{}]", String.join(", ", serverModList.mods()));
        Map<ResourceLocation, String> mismatchedChannels = validateChannels(serverModList.channels(), true);
        c.get().setPacketHandled(true);
        //The connection data needs to be modified before a new ModMismatchData instance could be constructed
        NetworkHooks.appendConnectionData(c.get().getConnection(), serverModList.mods().stream().collect(Collectors.toMap(Function.identity(), s -> new ConnectionData.ModData("", ""))), serverModList.channels());
        if (!mismatchedChannels.isEmpty()) {
            LOGGER.error(FMLHSMARKER, "Terminating connection with server, mismatched mod list");
            //Populate the mod mismatch attribute with a new mismatch data instance to indicate that the disconnect happened due to a mod mismatch
            c.get().getConnection().channel().attr(NetworkConstants.FML_MOD_MISMATCH_DATA).set(ModMismatchData.channel(mismatchedChannels, NetworkHooks.getConnectionData(c.get().getConnection()), true));
            c.get().getConnection().disconnect(Component.literal("Connection closed - mismatched mod channel list"));
            return;
        }
        // Validate synced custom datapack registries, client cannot be missing any present on the server.
        List<String> missingDataPackRegistries = new ArrayList<>();
        var clientDataPackRegistries = DataPackRegistriesHooks.getSyncedCustomRegistries();
        for (var key : serverModList.dataPackRegistries()) {
            if (!clientDataPackRegistries.contains(key)) {
                ResourceLocation location = key.location();
                LOGGER.error(FMLHSMARKER, "Missing required datapack registry: {}", location);
                missingDataPackRegistries.add(key.location().toString());
            }
        }
        if (!missingDataPackRegistries.isEmpty()) {
            c.get().getConnection().disconnect(Component.translatable("fml.menu.multiplayer.missingdatapackregistries", String.join(", ", missingDataPackRegistries)));
            return;
        }
        NetworkConstants.handshakeChannel.reply(new HandshakeMessages.C2SModListReply(), c.get());

        LOGGER.debug(FMLHSMARKER, "Accepted server connection");
        // Set the modded marker on the channel so we know we got packets
        c.get().getConnection().channel().attr(NetworkConstants.FML_NETVERSION).set(NetworkConstants.NETVERSION);

        this.registriesToReceive = new HashSet<>(serverModList.registries());
        this.registrySnapshots = Maps.newHashMap();
        LOGGER.debug(REGISTRIES, "Expecting {} registries: {}", ()->this.registriesToReceive.size(), ()->this.registriesToReceive);
    }

    void handleModData(HandshakeMessages.S2CModData serverModData, Supplier<NetworkEvent.Context> c) {
        c.get().getConnection().channel().attr(NetworkConstants.FML_CONNECTION_DATA).set(new ConnectionData(serverModData.mods(), new HashMap<>()));
        c.get().setPacketHandled(true);
    }

    ResourceLocation handleIndexedMessage(int index) {
        var channel = this.sentMessages.remove(index);
        LOGGER.debug(FMLHSMARKER, "Received client indexed reply {} mapping to channel {}", index, channel);
        if (channel == null) {
            LOGGER.error(FMLHSMARKER, "Recieved unexpected index {} in client reply", channel);
        }
        return channel;
    }

    void handleClientModListOnServer(HandshakeMessages.C2SModListReply clientModList, Supplier<NetworkEvent.Context> c) {
        LOGGER.debug(FMLHSMARKER, "Received client connection with modlist [{}]",  String.join(", ", clientModList.mods()));
        var mismatchedChannels = validateChannels(clientModList.channels(), false);
        c.get().getConnection().channel().attr(NetworkConstants.FML_CONNECTION_DATA)
                .set(new ConnectionData(clientModList.mods().stream().collect(Collectors.toMap(Function.identity(), s -> new ConnectionData.ModData("", ""))), clientModList.channels()));
        c.get().setPacketHandled(true);
        if (!mismatchedChannels.isEmpty()) {
            LOGGER.error(FMLHSMARKER, "Terminating connection with client, mismatched mod list");
            NetworkConstants.handshakeChannel.reply(new HandshakeMessages.S2CChannelMismatchData(mismatchedChannels), c.get());
            c.get().getConnection().disconnect(Component.literal("Connection closed - mismatched mod channel list"));
            return;
        }
        LOGGER.debug(FMLHSMARKER, "Accepted client connection mod list");
    }

    void handleModMismatchData(HandshakeMessages.S2CChannelMismatchData modMismatchData, Supplier<NetworkEvent.Context> c) {
        if (!modMismatchData.data().isEmpty()) {
            LOGGER.error(FMLHSMARKER, "Channels [{}] rejected their client side version number",
                    modMismatchData.data().keySet().stream().map(Object::toString).collect(Collectors.joining(",")));
            LOGGER.error(FMLHSMARKER, "Terminating connection with server, mismatched mod list");
            c.get().setPacketHandled(true);
            //Populate the mod mismatch attribute with a new mismatch data instance to indicate that the disconnect happened due to a mod mismatch
            c.get().getConnection().channel().attr(NetworkConstants.FML_MOD_MISMATCH_DATA).set(ModMismatchData.channel(modMismatchData.data(), NetworkHooks.getConnectionData(c.get().getConnection()), false));
            c.get().getConnection().disconnect(Component.literal("Connection closed - mismatched mod channel list"));
        }
    }

    void handleRegistryMessage(final HandshakeMessages.S2CRegistry registryPacket, final Supplier<NetworkEvent.Context> contextSupplier){
        LOGGER.debug(FMLHSMARKER,"Received registry packet for {}", registryPacket.name());
        this.registriesToReceive.remove(registryPacket.name());
        this.registrySnapshots.put(registryPacket.name(), registryPacket.snapshot());

        boolean continueHandshake = true;
        if (this.registriesToReceive.isEmpty()) {
            continueHandshake = handleRegistryLoading(contextSupplier);
        }
        // The handshake reply isn't sent until we have processed the message
        contextSupplier.get().setPacketHandled(true);
        if (!continueHandshake) {
            LOGGER.error(FMLHSMARKER, "Connection closed, not continuing handshake");
        } else {
            NetworkConstants.handshakeChannel.reply(new HandshakeMessages.C2SAcknowledge(), contextSupplier.get());
        }
    }

    private boolean handleRegistryLoading(final Supplier<NetworkEvent.Context> contextSupplier) {
        // We use a countdown latch to suspend the impl thread pending the client thread processing the registry data
        AtomicBoolean successfulConnection = new AtomicBoolean(false);
        AtomicReference<Multimap<ResourceLocation, ResourceLocation>> registryMismatches = new AtomicReference<>();
        CountDownLatch block = new CountDownLatch(1);
        contextSupplier.get().enqueueWork(() -> {
            LOGGER.debug(FMLHSMARKER, "Injecting registry snapshot from server.");
            final Multimap<ResourceLocation, ResourceLocation> missingData = GameData.injectSnapshot(registrySnapshots, false, false);
            LOGGER.debug(FMLHSMARKER, "Snapshot injected.");
            if (!missingData.isEmpty()) {
                LOGGER.error(FMLHSMARKER, "Missing registry data for impl connection:\n{}", LogMessageAdapter.adapt(sb->
                        missingData.forEach((reg, entry)-> sb.append("\t").append(reg).append(": ").append(entry).append('\n'))));
            }
            successfulConnection.set(missingData.isEmpty());
            registryMismatches.set(missingData);
            block.countDown();
        });
        LOGGER.debug(FMLHSMARKER, "Waiting for registries to load.");
        try {
            block.await();
        } catch (InterruptedException e) {
            Thread.interrupted();
        }
        if (successfulConnection.get()) {
            LOGGER.debug(FMLHSMARKER, "Registry load complete, continuing handshake.");
        } else {
            LOGGER.error(FMLHSMARKER, "Failed to load registry, closing connection.");
            //Populate the mod mismatch attribute with a new mismatch data instance to indicate that the disconnect happened due to a mod mismatch
            this.connection.channel().attr(NetworkConstants.FML_MOD_MISMATCH_DATA).set(ModMismatchData.registry(registryMismatches.get(), NetworkHooks.getConnectionData(contextSupplier.get().getConnection())));
            this.connection.disconnect(Component.literal("Failed to synchronize registry data from server, closing connection"));
        }
        return successfulConnection.get();
    }

    void handleClientAck(final HandshakeMessages.C2SAcknowledge msg, final Supplier<NetworkEvent.Context> contextSupplier) {
        LOGGER.debug(FMLHSMARKER, "Received acknowledgement from client");
        contextSupplier.get().setPacketHandled(true);
    }

    void handleConfigSync(final HandshakeMessages.S2CConfigData msg, final Supplier<NetworkEvent.Context> contextSupplier) {
        LOGGER.debug(FMLHSMARKER, "Received config sync from server");
        ConfigSync.INSTANCE.receiveSyncedConfig(msg, contextSupplier);
        contextSupplier.get().setPacketHandled(true);
        NetworkConstants.handshakeChannel.reply(new HandshakeMessages.C2SAcknowledge(), contextSupplier.get());
    }
    /**
     * FML will send packets, from Server to Client, from the messages queue until the queue is drained. Each message
     * will be indexed, and placed into the "pending acknowledgement" queue.
     *
     * As indexed packets are received at the server, they will be removed from the "pending acknowledgement" queue.
     *
     * Once the pending queue is drained, this method returns true - indicating that login processing can proceed to
     * the next step.
     *
     * @return true if there is no more need to tick this login connection.
     */
    public boolean tickServer() {
        if (!negotiationStarted) {
            GameProfile profile = ((ServerLoginPacketListenerImpl) connection.getPacketListener()).gameProfile;
            PlayerNegotiationEvent event = new PlayerNegotiationEvent(connection, profile, pendingFutures);
            MinecraftForge.EVENT_BUS.post(event);
            negotiationStarted = true;
        }

        if (packetPosition < messageList.size()) {
            NetworkRegistry.LoginPayload message = messageList.get(packetPosition);

            LOGGER.debug(FMLHSMARKER, "Sending ticking packet info '{}' to '{}' sequence {}", message.messageContext(), message.channelName(), packetPosition);
            if (message.needsResponse())
                sentMessages.put(packetPosition, message.channelName());
            connection.send(NetworkDirection.LOGIN_TO_CLIENT.buildPacket(message.data(), message.channelName(), packetPosition).self());
            packetPosition++;
        }

        pendingFutures.removeIf(future -> {
            if (!future.isDone()) {
                return false;
            }

            try {
                future.get();
            } catch (ExecutionException ex) {
                LOGGER.error("Error during negotiation", ex.getCause());
            } catch (CancellationException | InterruptedException ex) {
                // no-op
            }

            return true;
        });

        // we're done when sentMessages is empty
        if (sentMessages.isEmpty() && packetPosition >= messageList.size()-1 && pendingFutures.isEmpty()) {
            // clear ourselves - we're done!
            this.connection.channel().attr(NetworkConstants.FML_HANDSHAKE_HANDLER).set(null);
            LOGGER.debug(FMLHSMARKER, "Handshake complete!");
            return true;
        }
        return false;
    }

    /**
     * Tests if the map matches with the supplied predicate tester
     *
     * @param incoming An @{@link Map} of name->version pairs for testing
     * @param originName A label for use in logging (where the version pairs came from)
     * @param testFunction The test function to use for testing
     * @return a map of mismatched channel ids and versions, or an empty map if all channels accept themselves
     */
    private static Map<ResourceLocation, String> validateChannels(final Map<ResourceLocation, String> incoming, boolean testS2C) {
        var originName = testS2C ? "server" : "client";
        var results = NetworkRegistry.networks().values().stream()
            .filter(ni -> {
                var incomingVersion = incoming.get(ni.getChannelName());
                var accepted = testS2C ?
                    ni.tryServerVersionOnClient(ConnectionType.MODDED, incomingVersion) :
                    ni.tryClientVersionOnServer(ConnectionType.MODDED, incomingVersion);
                LOGGER.debug(FMLHSMARKER, "Channel '{}' : Version test of '{}' from {} : {}", ni.getChannelName(), incomingVersion, originName, accepted ? "ACCEPTED" : "REJECTED");
                return !accepted;
            })
            .map(NetworkInstance::getChannelName)
            .collect(Collectors.toMap(Function.identity(), incoming::get));

        if (!results.isEmpty()) {
            LOGGER.error(FMLHSMARKER, "Channels [{}] rejected their {} side version number", results.keySet().stream().map(Object::toString).collect(Collectors.joining(",")), originName);
            return results;
        }

        LOGGER.debug(FMLHSMARKER, "Accepting channel list from {}", originName);
        return results;
    }

    /**
     * Retrieve the {@link LoginPayload} list for dispatch during {@link HandshakeHandler#tickLogin(Connection)} handling.
     * Dispatches {@link NetworkEvent.GatherLoginPayloadsEvent} to each {@link NetworkInstance}.
     *
     * @return The {@link LoginPayload} list
     * @param direction the impl direction for the request - only gathers for LOGIN_TO_CLIENT
     */
    private static List<LoginPayload> gatherLoginPayloads(final NetworkDirection direction, boolean isLocal) {
        if (direction != NetworkDirection.LOGIN_TO_CLIENT)
            return Collections.emptyList();
        List<LoginPayload> gatheredPayloads = new ArrayList<>();
        NetworkRegistry.networks().values().forEach(ni -> ni.dispatchGatherLogin(gatheredPayloads, isLocal));
        return gatheredPayloads;
    }
}
