/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import net.minecraftforge.network.ConnectionData.ModMismatchData;
import net.minecraftforge.network.filters.NetworkFilters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.config.ConfigTracker;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public class NetworkHooks {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     *  Hooks for managing the hostName value of {@link ClientIntentionPacket}
     *  TODO: Add a way for mods to append this data?
     */
    public static List<String> getIntentionExtraData() {
        return Collections.singletonList(NetworkConstants.NETVERSION);
    }

    public static List<String> parseIntentionExtraData(String host) {
        var pts = host.split("\0");
        var ret = new ArrayList<String>();
        for (int x = 1; x < pts.length; x++)
            ret.add(pts[x]);
        return ret;
    }

    public static String getFMLVersion(List<String> data) {
        if (data.isEmpty())
            return NetworkConstants.NOVERSION;

        for (var entry : data) {
            if (entry.startsWith(NetworkConstants.FMLNETMARKER))
                return entry;
        }

        return NetworkConstants.NOVERSION;
    }

    public static ConnectionType getConnectionType(final Supplier<Connection> connection) {
        return getConnectionType(connection.get().channel());
    }

    public static ConnectionType getConnectionType(ChannelHandlerContext context) {
        return getConnectionType(context.channel());
    }

    private static ConnectionType getConnectionType(Channel channel) {
        return ConnectionType.forVersionFlag(channel.attr(NetworkConstants.FML_NETVERSION).get());
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    public static Packet<ClientGamePacketListener> getEntitySpawningPacket(Entity entity) {
        // ClientboundCustomPayloadPacket is an instance of Packet<ClientGamePacketListener>
        return (Packet<ClientGamePacketListener>) NetworkConstants.playChannel.toVanillaPacket(new PlayMessages.SpawnEntity(entity), NetworkDirection.PLAY_TO_CLIENT);
    }

    public static void sendOpenContainer(ServerPlayer player, AbstractContainerMenu container, Component name, FriendlyByteBuf data) {
        var pkt = new PlayMessages.OpenContainer(container.getType(), container.containerId, name, data);
        NetworkConstants.playChannel.sendTo(pkt, player.connection.getConnection(), NetworkDirection.PLAY_TO_CLIENT);
    }


    public static boolean onCustomPayload(final ICustomPacket<?> packet, final Connection manager) {
        return NetworkRegistry.findTarget(packet.getName())
            .filter(ni -> validateSideForProcessing(packet, ni, manager))
            .map(ni -> ni.dispatch(packet.getDirection(), packet, manager))
            .orElse(Boolean.FALSE);
    }

    public static boolean onCustomPayloadServerLogin(ServerboundCustomQueryPacket pkt, Connection manager) {
        // Lets try and resuscitate the channel name so we know where to send it.
        var handshake = manager.channel().attr(NetworkConstants.FML_HANDSHAKE_HANDLER).get();
        var channel = handshake.handleIndexedMessage(pkt.getTransactionId());
        var fixed = new ServerboundCustomQueryPacket(pkt.getTransactionId(), pkt.getData(), channel);
        return onCustomPayload(fixed, manager);
    }

    private static boolean validateSideForProcessing(final ICustomPacket<?> packet, final NetworkInstance ni, final Connection manager) {
        if (packet.getDirection().getReceptionSide() != EffectiveSide.get()) {
            manager.disconnect(Component.literal("Illegal packet received, terminating connection"));
            return false;
        }
        return true;
    }

    public static void validatePacketDirection(final NetworkDirection packetDirection, final Optional<NetworkDirection> expectedDirection, final Connection connection) {
        if (packetDirection != expectedDirection.orElse(packetDirection)) {
            connection.disconnect(Component.literal("Illegal packet received, terminating connection"));
            throw new IllegalStateException("Invalid packet received, aborting connection");
        }
    }

    public static void registerServerLoginChannel(Connection con, ClientIntentionPacket packet) {
        con.channel().attr(NetworkConstants.FML_NETVERSION).set(packet.getFMLVersion());
        var type = ConnectionType.forVersionFlag(packet.getFMLVersion());
        NetworkRegistry.networks().values().forEach(ni -> ni.attachAttributes(NetworkDirection.LOGIN_TO_CLIENT, con, type));
    }

    public  static void registerClientLoginChannel(Connection con) {
        con.channel().attr(NetworkConstants.FML_NETVERSION).set(NetworkConstants.NOVERSION);
        NetworkRegistry.networks().values().forEach(ni -> ni.attachAttributes(NetworkDirection.LOGIN_TO_SERVER, con, ConnectionType.VANILLA));
    }

    public synchronized static void sendMCRegistryPackets(Connection con, String direction) {
        NetworkFilters.injectIfNecessary(con);
        var channels = NetworkRegistry.networkVersions().keySet().stream()
            .filter(rl -> !"minecraft".equals(rl.getNamespace()))
            .collect(Collectors.toSet());
        MCRegisterPacketHandler.addChannels(con, channels, NetworkDirection.valueOf(direction));
    }

    public static boolean isVanillaConnection(Connection con) {
        if (con == null || con.channel() == null)
            throw new IllegalArgumentException("Network connection is null (" + (con != null ? "CHANNEL" : "CONNECTION") + ")");
        return getConnectionType(() -> con) == ConnectionType.VANILLA;
    }

    public static void handleClientLoginSuccess(Connection manager) {
        if (isVanillaConnection(manager)) {
            LOGGER.info("Connected to a vanilla server. Catching up missing behaviour.");
            ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        } else {
            LOGGER.info("Connected to a modded server.");
        }
    }

    public static boolean tickNegotiation(Connection connection) {
        return HandshakeHandler.tickLogin(connection);
    }

    /**
     * Updates the current ConnectionData instance with new mod or channel data if the old instance did not have either of these yet,
     * or creates a new ConnectionData instance with the new data if the current ConnectionData instance doesn't exist yet.
     */
    static void appendConnectionData(Connection mgr, Map<String, ConnectionData.ModData> modData, Map<ResourceLocation, String> channels) {
        ConnectionData oldData = mgr.channel().attr(NetworkConstants.FML_CONNECTION_DATA).get();

        oldData = oldData != null ? new ConnectionData(oldData.getModData().isEmpty() ? modData : oldData.getModData(), oldData.getChannels().isEmpty() ? channels : oldData.getChannels()) : new ConnectionData(modData, channels);
        mgr.channel().attr(NetworkConstants.FML_CONNECTION_DATA).set(oldData);
    }

    @Nullable
    public static ConnectionData getConnectionData(Connection mgr) {
        return mgr.channel().attr(NetworkConstants.FML_CONNECTION_DATA).get();
    }

    @Nullable
    public static ModMismatchData getModMismatchData(Connection mgr) {
        return mgr.channel().attr(NetworkConstants.FML_MOD_MISMATCH_DATA).get();
    }

    @Nullable
    static MCRegisterPacketHandler.ChannelList getChannelList(Connection mgr) {
        return mgr.channel().attr(NetworkConstants.FML_MC_REGISTRY).get();
    }
}
