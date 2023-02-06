/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

public class ConnectionData {
    public record ModData(String displayName, String version) {}

    private ImmutableMap<String, ModData> modData;
    private ImmutableMap<ResourceLocation, String> channels;

    ConnectionData(Map<String, ModData> modData, Map<ResourceLocation, String> channels) {
        this.modData = ImmutableMap.copyOf(modData);
        this.channels = ImmutableMap.copyOf(channels);
    }

    /**
     * Returns the list of mods present in the remote.
     * <b>WARNING: This list is not authoritative.
     *    A mod missing from the list does not mean the mod isn't there,
     *    and similarly a mod present in the list does not mean it is there.
     *    People using hacked clients WILL hack the mod lists to make them look correct.
     *    Do not use this as an anti-cheat feature!
     *  </b>
     * @return An immutable list of MODIDs
     */
    public ImmutableList<String> getModList() {
        return modData.keySet().asList();
    }

    /**
     * Returns a map of mods and respective mod names and versions present in the remote.
     * <b>WARNING: This list is not authoritative.
     *    A mod missing from the list does not mean the mod isn't there,
     *    and similarly a mod present in the list does not mean it is there.
     *    People using hacked clients WILL hack the mod lists to make them look correct.
     *    Do not use this as an anti-cheat feature!
     *  </b>
     * @return An immutable map of MODIDs and their respective mod versions
     */
    public ImmutableMap<String, ModData> getModData() {
        return modData;
    }

    /**
     * Returns the list of impl channels present in the remote.
     * <b>WARNING: This list is not authoritative.
     *    A channel missing from the list does not mean the remote won't accept packets with that channel ID,
     *    and similarly a channel present in the list does not mean the remote won't ignore it.
     *    People using hacked clients WILL hack the channel list to make it look correct.
     *    Do not use this as an anti-cheat feature!
     *  </b>
     * @return An immutable map of channel IDs and their respective protocol versions.
     */
    public ImmutableMap<ResourceLocation, String> getChannels() {
        return channels;
    }

    /**
     * A class for holding the mod mismatch data of a failed handshake.
     * Contains a list of mismatched channels, the channels present on the side the handshake failed on, the mods with mismatching registries (if available) and the information of whether the mismatching data's origin is the server.
     * @param mismatchedModData The data of the mismatched or missing mods/channels, consisting of the mod or channel id and the version of the respective mod. If the mod version is absent, the entry is assumed to be missing on either side. If it is present, the entry gets treated as a mismatch.
     * @param presentModData The data of the channels/mods from the side the mismatch data doesn't originate from, consisting of the mod or channel id and the name and version of the respective mod. This data is useful for a proper comparison against the mismatched channel data. The data is stored like this: [id -> [modName, modVersion]]
     * @param mismatchedDataFromServer Whether the mismatched data originates from the server. Note, that the mismatched data origin does not tell us if the actual mismatch check happened on the client or the server.
     */
    public record ModMismatchData(
        Map<ResourceLocation, String> mismatchedModData,
        Map<ResourceLocation, ModData> presentModData,
        boolean mismatchedDataFromServer
    ) {
        /**
         * Creates a ModMismatchData instance from given channel mismatch data, which is processed side-aware depending on the value of mismatchedDataFromServer
         * @param mismatchedChannels The list of channels that were listed as mismatches, either because they are missing on one side or because their versions mismatched
         * @param connectionData The connection data instance responsible for collecting the server mod data.
         * @param mismatchedDataFromServer Whether the mismatched data originates from the server.
         */
        public static ModMismatchData channel(Map<ResourceLocation, String> mismatchedChannels, ConnectionData connectionData, boolean mismatchedDataFromServer)
        {
            Map<ResourceLocation, String> mismatchedChannelData = enhanceWithModVersion(mismatchedChannels, connectionData, mismatchedDataFromServer);
            Map<ResourceLocation, ModData> presentChannelData = getPresentChannelData(mismatchedChannels.keySet(), connectionData, mismatchedDataFromServer);

            return new ModMismatchData(mismatchedChannelData, presentChannelData, mismatchedDataFromServer);
        }

        /**
         * Creates a ModMismatchData instance from given mismatched registry entries. In this case, the mismatched data is always treated as originating from the client because registry entries missing on the server never cause the handshake to fail.
         * @param mismatchedRegistryData The list of mismatched registries and the associated mismatched registry entries. The data is stored like this: "registryNamespace:registryPath" -> "entryNamespace:entryPath"
         * @param connectionData The connection data instance responsible for collecting the server mod data.
         */
        public static ModMismatchData registry(Multimap<ResourceLocation, ResourceLocation> mismatchedRegistryData, ConnectionData connectionData) {
            List<ResourceLocation> mismatchedRegistryMods = mismatchedRegistryData.values().stream().map(ResourceLocation::getNamespace).distinct().map(id -> new ResourceLocation(id, "")).toList();
            var mismatchedRegistryModData = mismatchedRegistryMods.stream()
                .collect(Collectors.toMap(
                    Function.identity(),
                    id -> ModList.get().getModContainerById(id.getNamespace()).map(c -> c.getModInfo().getVersion().toString()).orElse(null))
                );
            Map<ResourceLocation, ModData> presentModData = getServerSidePresentModData(mismatchedRegistryModData.keySet(), connectionData);

            return new ModMismatchData(mismatchedRegistryModData, presentModData, false);
        }

        /**
         * @return true if this ModMismatchData instance contains channel or registry mismatches
         */
        public boolean containsMismatches() {
            return mismatchedModData != null && !mismatchedModData.isEmpty();
        }

        /**
         * Enhances a map of mismatched channels with the corresponding mod version.
         * @param mismatchedChannels The original mismatched channel list, containing the id and version of the mismatched channel.
         * @param connectionData The connection data instance responsible for collecting the server mod data.
         * @param mismatchedDataFromServer Whether the mismatched data originates from the server. The given mismatched channel list gets processed side-aware depending on the value of this parameter.
         * @return A map containing the id of the channel and the version of the corresponding mod, or NetworkRegistry.ABSENT if the channel is missing.
         */
        private static Map<ResourceLocation, String> enhanceWithModVersion(Map<ResourceLocation, String> mismatchedChannels, ConnectionData connectionData, boolean mismatchedDataFromServer) {
            Map<String, String> mismatchedModVersions;

            if (mismatchedDataFromServer) //enhance with data from the server
                mismatchedModVersions = connectionData != null ? connectionData.getModData().entrySet().stream().collect(Collectors.toMap(Entry::getKey, e -> e.getValue().version())) : Map.of();
            else { //enhance with data from the client
                mismatchedModVersions = ModList.get().getMods().stream().collect(Collectors.toMap(
                    i -> i.getModId(),
                    i -> i.getVersion().toString()
                ));
            }
            return mismatchedChannels.keySet().stream()
                .collect(Collectors.toMap(
                    Function.identity(),
                    c -> mismatchedChannels.get(c) == null ? null : mismatchedModVersions.get(c.getNamespace())
                ));
        }


        /**
         * Queries the channel data from the side the mismatched data isn't from, in order to provide a map of all the present channels for a proper comparison against the mismatched channel data.
         * @param mismatchedChannelsFilter A filter that gets used in order to only query the data of mismatched mods, because that's the only one we need for a proper comparison between mismatched and present mods.
         * @param connectionData The connection data instance responsible for collecting the server mod data.
         * @param mismatchedDataFromServer Whether the mismatched data originates from the server. The data gets queried from the side that the mismatch data does not originate from.
         * @return The data of all relevant present channels, containing the channel id and the name and version of the corresponding mod.
         */
        private static Map<ResourceLocation, ModData> getPresentChannelData(Set<ResourceLocation> mismatchedChannelsFilter, ConnectionData connectionData, boolean mismatchedDataFromServer) {
            Map<ResourceLocation, String> channelData;

            if (mismatchedDataFromServer) //mismatch data comes from the server, use client channel data
                channelData = NetworkRegistry.networkVersions();
            else //mismatch data comes from the client, use server channel data
                channelData = connectionData != null ? connectionData.getChannels() : Map.of();

            return channelData.keySet().stream().filter(mismatchedChannelsFilter::contains)
                    .map(id -> getPresentModDataFromChannel(id, connectionData, mismatchedDataFromServer))
                    .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
        }

        /**
         * Queries the mod data from a given channel id from the side the mismatched data isn't from.
         * @param channel The id of the channel the mod data should be queried for.
         * @param connectionData The connection data instance responsible for collecting the server mod data.
         * @param mismatchedDataFromServer Whether the mismatched data originates from the server. The data gets queried from the side that the mismatch data does not originate from.
         * @return The mod data corresponding to the channel id, containing the channel id and the name and version of the corresponding mod.
         */
        private static Pair<ResourceLocation, ModData> getPresentModDataFromChannel(ResourceLocation channel, ConnectionData connectionData, boolean mismatchedDataFromServer) {
            if (mismatchedDataFromServer) {
                return ModList.get().getModContainerById(channel.getNamespace())
                    .map(mod -> Pair.of(
                        channel, new ModData(
                            mod.getModInfo().getDisplayName(),
                            mod.getModInfo().getVersion().toString()
                        )
                    ))
                    .orElse(Pair.of(channel, new ModData(channel.getNamespace(), "")));
            } else {
                var modData = connectionData != null ? connectionData.getModData() : Map.<String, ModData>of();
                var modDataFromChannel = modData.getOrDefault(channel.getNamespace(), new ModData(channel.getNamespace(), ""));
                return Pair.of(channel,
                    modDataFromChannel.displayName().isEmpty()
                        ? new ModData(channel.getNamespace(), modDataFromChannel.version())
                        : modDataFromChannel
                );
            }
        }

        /**
         * Queries the mod data from the server side. Useful in case of a registry mismatch, which always gets detected on the client.
         * @param mismatchedModsFilter A filter that gets used in order to only query the data of mismatched mods, because that's the only one we need for a proper comparison between mismatched and present mods.
         * @param connectionData The connection data instance responsible for collecting the server mod data.
         * @return The mod data from the server, containing the channel id and the name and version of the corresponding mod.
         */
        private static Map<ResourceLocation, ModData> getServerSidePresentModData(Set<ResourceLocation> mismatchedModsFilter, ConnectionData connectionData) {
            var serverModData = connectionData != null ? connectionData.getModData() : Map.<String, ModData>of();
            var filter = mismatchedModsFilter.stream().map(ResourceLocation::getNamespace).collect(Collectors.toSet());
            return serverModData.entrySet().stream()
                .filter(e -> filter.contains(e.getKey()))
                .collect(Collectors.toMap(e -> new ResourceLocation(e.getKey(), ""), Entry::getValue));
        }
    }
}
