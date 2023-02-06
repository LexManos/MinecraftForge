/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import io.netty.util.Attribute;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent.RegistrationChangeType;

import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An implementation of the minecraft:register and minecraft:unregister plugin messages which are defined
 * by Mojang in Dinnerbone's now deleted/missing blog post. These messages allow one side of the
 * connection to know what the other side is willing to listen to. This allows us to filter out packets
 * that the other side doesn't care about. In practice we don't, because some mods, and vanilla, don't
 * register all their channels correctly.
 * <p>
 * Both channels send the same format of data. There are no packet headers or any special formating to the messages.
 * They are simply a null separated list of utf8 strings. The channel name determines if it is a register or unregister action.
 * <p>
 * Even tho the spec does not define that these strings are {@link ResourceLocation}s, we decode them as such, and discard
 * any that do not fall into that format. This is a somewhat community agreed standard as it forces people to use namespaces
 * and helps prevent conflicts between channels.
 */
@ApiStatus.Internal
class MCRegisterPacketHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class ChannelList {
        /**
         * Channels known to us locally, these are channels that we have code listening for on our local instance.
         */
        private Set<ResourceLocation> local = new HashSet<>();
        private Set<ResourceLocation> localView = Collections.unmodifiableSet(local);

        /**
         * Channels the remote connection has told us they know about.
         */
        private Set<ResourceLocation> remote = new HashSet<>();
        private Set<ResourceLocation> remoteView = Collections.unmodifiableSet(remote);

        /**
         * {@return the unmodifiable set of channel locations sent by the remote side}
         * This is useful for interacting with other modloaders via the network to inspect registered network channel IDs.
         */
        public Set<ResourceLocation> getRemoteChannels() {
            return this.remoteView;
        }

        /**
         * {@return the unmodifiable set of channels that we can handle on our local side}
         */
        public Set<ResourceLocation> getLocalChannels() {
            return this.localView;
        }
    }

    static void addChannels(Connection con, Set<ResourceLocation> channels, NetworkDirection dir) {
        var known = getFrom(con).local;
        var unknown = new HashSet<>(channels);
        unknown.removeAll(known); // Only newly added
        known.addAll(unknown);
        con.send(buildPacket(NetworkConstants.MC_REGISTER_RESOURCE, dir, unknown));
    }

    static void registerListener(NetworkEvent evt) {
        var known = getFrom(evt).remote;
        var unknown = fromBuffer(evt.getPayload());
        unknown.removeAll(known);
        known.addAll(unknown);
        fireChangeEvent(evt.getSource(), unknown, RegistrationChangeType.REGISTER);
        evt.getSource().get().setPacketHandled(true);
    }

    static void unregisterListener(NetworkEvent evt) {
        var known = getFrom(evt).remote;
        var removed = fromBuffer(evt.getPayload());
        known.removeAll(removed);
        fireChangeEvent(evt.getSource(), removed, RegistrationChangeType.UNREGISTER);
        evt.getSource().get().setPacketHandled(true);
    }

    private static byte[] toByteArray(Set<ResourceLocation> locations) {
        var bos = new ByteArrayOutputStream();
        for (var rl : locations) {
            try {
                bos.write(rl.toString().getBytes(StandardCharsets.UTF_8));
                bos.write(0);
            } catch (IOException e) {} // Can never throw, but need to make the compiler happy
        }
        return bos.toByteArray();
    }


    private static Set<ResourceLocation> fromBuffer(FriendlyByteBuf buffer) {
        var all = new byte[Math.max(buffer.readableBytes(), 0)];
        buffer.readBytes(all);
        Set<ResourceLocation> ret = new HashSet<>();
        int last = 0;
        for (int cur = 0; cur < all.length; cur++) {
            if (all[cur] == '\0') {
                var name = new String(all, last, cur - last, StandardCharsets.UTF_8);
                try {
                    ret.add(new ResourceLocation(name));
                } catch (ResourceLocationException ex) {
                    LOGGER.warn("Invalid channel name received: {}. Ignoring", name);
                }
                last = cur + 1;
            }
        }
        return ret;
    }


    private static ChannelList getFrom(Connection manager) {
        return fromAttr(manager.channel().attr(NetworkConstants.FML_MC_REGISTRY));
    }

    private static ChannelList getFrom(NetworkEvent event) {
        return fromAttr(event.getSource().get().attr(NetworkConstants.FML_MC_REGISTRY));
    }

    private static ChannelList fromAttr(Attribute<ChannelList> attr) {
        attr.setIfAbsent(new ChannelList());
        return attr.get();
    }

    private static Packet<?> buildPacket(ResourceLocation action, NetworkDirection dir, Set<ResourceLocation> channels) {
        var pb = new FriendlyByteBuf(Unpooled.buffer());
        pb.writeBytes(toByteArray(channels));
        var pkt = dir.buildPacket(pb, action);
        return pkt.self();
    }

    private static void fireChangeEvent(Supplier<NetworkEvent.Context> source, Set<ResourceLocation> channels, RegistrationChangeType changeType) {
        channels.stream()
            .map(NetworkRegistry::findTarget)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .forEach(t -> t.dispatchEvent(new NetworkEvent.ChannelRegistrationChangeEvent(source, changeType)));
    }
}
