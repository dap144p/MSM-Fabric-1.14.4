package dev.cylox.msm.network.packet;

import com.github.steveice10.packetlib.io.NetInput;
import com.github.steveice10.packetlib.io.NetOutput;
import com.github.steveice10.packetlib.packet.Packet;

import java.io.IOException;

/**
 * Consumes the 1.14.4 server notification payload without enum lookup.
 * The secondary session has no renderer, so notification effects are unused.
 */
public final class IgnoredClientNotificationPacket implements Packet {
    public IgnoredClientNotificationPacket() {
    }

    @Override
    public void read(NetInput input) throws IOException {
        input.readUnsignedByte();
        input.readFloat();
    }

    @Override
    public void write(NetOutput output) throws IOException {
        output.writeByte(0);
        output.writeFloat(0.0F);
    }

    @Override
    public boolean isPriority() {
        return false;
    }
}
