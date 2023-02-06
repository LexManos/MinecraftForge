/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.DataPackRegistriesHooks;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public class HandshakeMessages {
    /**
     * Main packet that describes the list of mods, registries, and networks the server side has. <br>
     * Should arrive before: {@link S2CRegistry}<br>
     * Should arrive after:  {@link S2CModList}<br>
     *
     * <pre>
     *   VarInt count
     *   UTF[count] mod id
     *   VarInt count
     *   {
     *      UTF Name
     *      UTF Version
     *   }[count] channels
     *   VarInt count
     *   UTF[count] registryName - This is a list of registries that we are expecting {@link S2CRegistry} packets for
     *   VarInt count
     *   UTF[count] dataRegistryName - A list of custom data driven registries that will be sending their data across using their own packets.
     *              The specific packet to follow is not defined, as it's controlled by the mod. So we just check if we have a mod that knows about this registry.
     * </pre>
     */
    record S2CModList(
        List<String> mods,
        Map<ResourceLocation, String> channels,
        List<ResourceLocation> registries,
        List<ResourceKey<? extends Registry<?>>> dataPackRegistries
    ) {
        // Default constructor because 'markAsLoginPacket' helper uses this
        S2CModList() {
            this(
                ModList.get().getMods().stream().map(IModInfo::getModId).collect(Collectors.toList()),
                NetworkRegistry.networkVersions(),
                RegistryManager.getRegistryNamesForSyncToClient(),
                List.copyOf(DataPackRegistriesHooks.getSyncedCustomRegistries())
            );
        }

        public static S2CModList decode(FriendlyByteBuf input) {
            List<String> mods = new ArrayList<>();
            int len = input.readVarInt();
            for (int x = 0; x < len; x++)
                mods.add(input.readUtf(0x100));

            Map<ResourceLocation, String> channels = new HashMap<>();
            len = input.readVarInt();
            for (int x = 0; x < len; x++)
                channels.put(input.readResourceLocation(), input.readUtf(0x100));

            List<ResourceLocation> registries = new ArrayList<>();
            len = input.readVarInt();
            for (int x = 0; x < len; x++)
                registries.add(input.readResourceLocation());

            List<ResourceKey<? extends Registry<?>>> dataPackRegistries = input.readCollection(ArrayList::new, buf -> ResourceKey.createRegistryKey(buf.readResourceLocation()));
            return new S2CModList(mods, channels, registries, dataPackRegistries);
        }

        public void encode(FriendlyByteBuf output) {
            output.writeVarInt(mods.size());
            mods.forEach(m -> output.writeUtf(m, 0x100));

            output.writeVarInt(channels.size());
            channels.forEach((k, v) -> {
                output.writeResourceLocation(k);
                output.writeUtf(v, 0x100);
            });

            output.writeVarInt(registries.size());
            registries.forEach(output::writeResourceLocation);

            Set<ResourceKey<? extends Registry<?>>> dataPackRegistries = DataPackRegistriesHooks.getSyncedCustomRegistries();
            output.writeCollection(dataPackRegistries, (buf, key) -> buf.writeResourceLocation(key.location()));
        }
    }

    /**
     * Provides additional data to the client about mods, currently the display name and version. <br>
     * Why this is special and separate from {@link S2CModList} I have no idea - Lex<br>
     * Should arrive before: {@link S2CModList}<br>
     * <pre>
     *   VarInt count
     *   {
     *     UTF Mod ID
     *     UTF Display Name
     *     UTF Version
     *   }[count] Data
     * </pre>
     */
    record S2CModData(Map<String, ConnectionData.ModData> mods) {
        S2CModData() {
            this(ModList.get().getMods().stream().collect(Collectors.toMap(IModInfo::getModId, info -> new ConnectionData.ModData(info.getDisplayName(), info.getVersion().toString()))));
        }

        public static S2CModData decode(FriendlyByteBuf input) {
            return new S2CModData(input.readMap(o -> o.readUtf(0x100), o -> new ConnectionData.ModData(o.readUtf(0x100), o.readUtf(0x100))));
        }

        public void encode(FriendlyByteBuf output) {
            output.writeMap(mods, (o, s) -> o.writeUtf(s, 0x100), (o, p) -> {
                o.writeUtf(p.displayName(), 0x100);
                o.writeUtf(p.version(), 0x100);
            });
        }
    }

    /**
     * C2S response to {@link S2CModList}, this packet is designed to inform the server what mods/data are available on the client side.
     * The channel and registries entries are treated as maps. The registry list is always empty for the time being as we have not implementd
     * client side registry caching.
     *
     * <pre>
     *   VarInt count
     *   UTF[count] mod ids
     *   VarInt count
     *   {
     *     UTF Name
     *     UTF Version
     *   }[count] Channels
     *   VarInt count
     *   {
     *     UTF Name
     *     UTF Hash - This is intended to be a hash or some magic token for registry caching, however this is not implemented
     *   }[count] Registries
     * </pre>
     *
     */
    record C2SModListReply(
        List<String> mods,
        Map<ResourceLocation, String> channels,
        Map<ResourceLocation, String> registries
    ) {
        C2SModListReply() {
            this(
                ModList.get().getMods().stream().map(IModInfo::getModId).collect(Collectors.toList()),
                NetworkRegistry.networkVersions(),
                Collections.emptyMap() // TODO: Actually implement registry caching
            );
        }

        static C2SModListReply decode(FriendlyByteBuf input) {
            List<String> mods = new ArrayList<>();
            int len = input.readVarInt();
            for (int x = 0; x < len; x++)
                mods.add(input.readUtf(0x100));

            Map<ResourceLocation, String> channels = new HashMap<>();
            len = input.readVarInt();
            for (int x = 0; x < len; x++)
                channels.put(input.readResourceLocation(), input.readUtf(0x100));

            Map<ResourceLocation, String> registries = new HashMap<>();
            len = input.readVarInt();
            for (int x = 0; x < len; x++)
                registries.put(input.readResourceLocation(), input.readUtf(0x100));

            return new C2SModListReply(mods, channels, registries);
        }

        public void encode(FriendlyByteBuf output) {
            output.writeVarInt(mods.size());
            mods.forEach(m -> output.writeUtf(m, 0x100));

            output.writeVarInt(channels.size());
            channels.forEach((k, v) -> {
                output.writeResourceLocation(k);
                output.writeUtf(v, 0x100);
            });

            output.writeVarInt(registries.size());
            registries.forEach((k, v) -> {
                output.writeResourceLocation(k);
                output.writeUtf(v, 0x100);
            });
        }
    }

    /**
     * This is just a packet to signify what we have received and processed the last packet.
     * There is no data, because it should match the transaction id during the LOGIN process.
     */
    static class C2SAcknowledge {
        public void encode(FriendlyByteBuf buf) {}

        public static C2SAcknowledge decode(FriendlyByteBuf buf) {
            return new C2SAcknowledge();
        }
    }

    /**
     * Sends registry mapping information to the client so that we can rebuild the registries. Thus allowing the server
     * world to use compact integer ids in all future networking communications. This also gives the client and
     * Opportunity to make sure that it has all the necessary registry entries for this session to work.
     * <p>
     * The format is complicated, but it's essentially the registry name and a {@link ForgeRegistry.Snaposhot}, so for specifics see that class.
     * <pre>
     *   UTF Name
     *   byte hasSnapshot
     *   if (hasSnapshot != 0)
     *     Snapshot data
     * </pre>
     */
    @ApiStatus.Internal
    public record S2CRegistry(
        ResourceLocation name,
        @Nullable ForgeRegistry.Snapshot snapshot
    ) {
        void encode(final FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(name);
            buffer.writeBoolean(snapshot != null);
            if (snapshot != null)
                buffer.writeBytes(snapshot.getPacketData());
        }

        public static S2CRegistry decode(final FriendlyByteBuf buffer) {
            var name = buffer.readResourceLocation();
            var snapshot = buffer.readBoolean() ? ForgeRegistry.Snapshot.read(buffer) : null;
            return new S2CRegistry(name, snapshot);
        }
    }


    /**
     * Used to synchronize the server's config file to the client. The file should never be written to disk, and should only be parsed as a normal config file.
     * All standard arbitrary data precautions are suggested.
     *
     * <pre>
     *   UTF FileName - Config file name on the server side typically 'modid-server.toml'
     *   VarInt count
     *   byte[count] data
     * </pre>
     */
    record S2CConfigData(
        String fileName,
        byte[] data
    ) {
        void encode(final FriendlyByteBuf buffer) {
            buffer.writeUtf(this.fileName);
            buffer.writeByteArray(this.data);
        }

        public static S2CConfigData decode(final FriendlyByteBuf buffer) {
            return new S2CConfigData(buffer.readUtf(32767), buffer.readByteArray());
        }
    }

    /**
     * Notifies the client of a channel mismatch on the server, so a {@link net.minecraftforge.client.gui.ModMismatchDisconnectedScreen} is used to notify the user of the disconnection.
     * This packet also sends the data of a channel mismatch (currently, the ids and versions of the mismatched channels) to the client for it to display the correct information in said screen.
     * <pre>
     *   VarInt count
     *   {
     *     UTF Name
     *     UTF Version
     *   }[count] Channels
     * </pre>
     */
    record S2CChannelMismatchData(Map<ResourceLocation, String> data) {
        public static S2CChannelMismatchData decode(FriendlyByteBuf input) {
            return new S2CChannelMismatchData(input.readMap(i -> new ResourceLocation(i.readUtf(0x100)), i -> i.readUtf(0x100)));
        }

        public void encode(FriendlyByteBuf output) {
            output.writeMap(data, (o, r) -> o.writeUtf(r.toString(), 0x100), (o, v) -> o.writeUtf(v, 0x100));
        }
    }
}
