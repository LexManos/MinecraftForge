/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import net.minecraftforge.network.HandshakeMessages.C2SAcknowledge;
import net.minecraftforge.network.HandshakeMessages.C2SModListReply;
import net.minecraftforge.network.HandshakeMessages.S2CChannelMismatchData;
import net.minecraftforge.network.HandshakeMessages.S2CConfigData;
import net.minecraftforge.network.HandshakeMessages.S2CModData;
import net.minecraftforge.network.HandshakeMessages.S2CModList;
import net.minecraftforge.network.HandshakeMessages.S2CRegistry;
import net.minecraftforge.network.PlayMessages.OpenContainer;
import net.minecraftforge.network.PlayMessages.SpawnEntity;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.RegistryManager;

import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import static net.minecraftforge.network.NetworkConstants.NETVERSION;
import static net.minecraftforge.network.NetworkConstants.FML_HANDSHAKE_RESOURCE;
import static net.minecraftforge.network.NetworkConstants.FML_HANDSHAKE_HANDLER;
import static net.minecraftforge.network.NetworkConstants.FML_PLAY_RESOURCE;
import static net.minecraftforge.network.NetworkConstants.MC_REGISTER_RESOURCE;
import static net.minecraftforge.network.NetworkConstants.MC_UNREGISTER_RESOURCE;
import static net.minecraftforge.network.NetworkDirection.LOGIN_TO_SERVER;
import static net.minecraftforge.network.NetworkDirection.LOGIN_TO_CLIENT;

@ApiStatus.Internal
class NetworkInitialization {
    public static SimpleChannel getHandshakeChannel() {
        var channel = NetworkRegistry.ChannelBuilder
            .named(FML_HANDSHAKE_RESOURCE)
            .anyVersion()
            .networkProtocolVersion(NETVERSION)
            .attribute(FML_HANDSHAKE_HANDLER, HandshakeHandler::new)
            .simpleChannel();

        channel.messageBuilder(C2SAcknowledge.class, 99, LOGIN_TO_SERVER)
            .decoder(C2SAcknowledge::decode)
            .encoder(C2SAcknowledge::encode)
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleClientAck)
            .add();

        channel.messageBuilder(S2CModData.class, 5, LOGIN_TO_CLIENT)
            .decoder(S2CModData::decode)
            .encoder(S2CModData::encode)
            .markAsLoginPacket()
            .noResponse()
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleModData)
            .add();

        channel.messageBuilder(S2CModList.class, 1, LOGIN_TO_CLIENT)
            .decoder(S2CModList::decode)
            .encoder(S2CModList::encode)
            .markAsLoginPacket()
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleServerModListOnClient)
            .add();

        channel.messageBuilder(C2SModListReply.class, 2, LOGIN_TO_SERVER)
            .decoder(C2SModListReply::decode)
            .encoder(C2SModListReply::encode)
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleClientModListOnServer)
            .add();

        channel.messageBuilder(S2CRegistry.class, 3, LOGIN_TO_CLIENT)
            .decoder(S2CRegistry::decode)
            .encoder(S2CRegistry::encode)
            .buildLoginPacketList(RegistryManager::generateRegistryPackets) //TODO: Make this non-static, and store a cache on the client.
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleRegistryMessage)
            .add();

        channel.messageBuilder(S2CConfigData.class, 4, LOGIN_TO_CLIENT)
            .decoder(S2CConfigData::decode)
            .encoder(S2CConfigData::encode)
            .buildLoginPacketList(ConfigSync.INSTANCE::syncConfigs)
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleConfigSync)
            .add();

        channel.messageBuilder(S2CChannelMismatchData.class, 6, LOGIN_TO_CLIENT)
            .decoder(S2CChannelMismatchData::decode)
            .encoder(S2CChannelMismatchData::encode)
            .context(FML_HANDSHAKE_HANDLER)
            .consumerNetworkThread(HandshakeHandler::handleModMismatchData)
            .add();

        return channel;
    }

    public static SimpleChannel getPlayChannel() {
         var channel = NetworkRegistry.ChannelBuilder
            .named(FML_PLAY_RESOURCE)
            .anyVersion()
            .networkProtocolVersion(NETVERSION)
            .simpleChannel();

        channel.messageBuilder(SpawnEntity.class, 0)
            .decoder(SpawnEntity::decode)
            .encoder(SpawnEntity::encode)
            .consumerNetworkThread(SpawnEntity::handle)
            .add();

        channel.messageBuilder(OpenContainer.class, 1)
            .decoder(OpenContainer::decode)
            .encoder(OpenContainer::encode)
            .consumerNetworkThread(OpenContainer::handle)
            .add();

        return channel;
    }

    public static List<EventNetworkChannel> buildMCRegistrationChannels() {
        var regChannel = NetworkRegistry.ChannelBuilder
            .named(MC_REGISTER_RESOURCE)
            .anyVersion()
            .networkProtocolVersion(NETVERSION)
            .eventNetworkChannel();
        regChannel.addListener(MCRegisterPacketHandler::registerListener);

        var unregChannel = NetworkRegistry.ChannelBuilder
            .named(MC_UNREGISTER_RESOURCE)
            .anyVersion()
            .networkProtocolVersion(NETVERSION)
            .eventNetworkChannel();
        unregChannel.addListener(MCRegisterPacketHandler::unregisterListener);
        return Arrays.asList(regChannel, unregChannel);
    }
}
