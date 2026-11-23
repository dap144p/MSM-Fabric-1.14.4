package dev.cylox.msm.network;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.SubProtocol;
import com.github.steveice10.packetlib.Session;
import dev.cylox.msm.network.packet.IgnoredClientNotificationPacket;
import dev.cylox.msm.network.packet.IgnoredEntityPropertiesPacket;

/**
 * MCProtocolLib 1.14.4-2 rejects later game-notification IDs while decoding.
 * Secondary sessions do not render a world, so these client notifications can
 * be consumed without interpreting them. Other packet types remain unchanged.
 */
final class MsmMinecraftProtocol extends MinecraftProtocol {
    private static final int SERVER_NOTIFY_CLIENT_PACKET_ID = 0x1E;
    private static final int SERVER_ENTITY_PROPERTIES_PACKET_ID = 0x58;

    MsmMinecraftProtocol(String username) {
        super(username);
    }

    @Override
    protected void setSubProtocol(SubProtocol subProtocol, boolean client, Session session) {
        super.setSubProtocol(subProtocol, client, session);
        if (client && subProtocol == SubProtocol.GAME) {
            registerIncoming(SERVER_NOTIFY_CLIENT_PACKET_ID, IgnoredClientNotificationPacket.class);
            registerIncoming(SERVER_ENTITY_PROPERTIES_PACKET_ID, IgnoredEntityPropertiesPacket.class);
        }
    }
}
