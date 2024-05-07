package net.minecraftforge.network.simple;

import io.netty.util.AttributeKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkProtocol;

/**
 * Author: MrCrayfish
 */
public interface SimpleProtocolFactory
{
    /**
     * Creates a builder grouping together all packets under the same protocol.
     * This will validate that the protocol matches before the packet is sent or received.
     */
    <NEWBUF extends FriendlyByteBuf, NEWBASE> SimpleProtocol<NEWBUF, NEWBASE> protocol(NetworkProtocol<NEWBUF> protocol);

    /**
     * Creates a builder grouping together all packets under the same protocol.
     * This will validate that the protocol matches before the packet is sent or received.
     */
    <NEWBUF extends FriendlyByteBuf, CTX, NEWBASE extends SimplePacket<CTX>> SimpleContextProtocol<NEWBUF, NEWBASE> protocol(AttributeKey<CTX> context, NetworkProtocol<NEWBUF> protocol);
}
