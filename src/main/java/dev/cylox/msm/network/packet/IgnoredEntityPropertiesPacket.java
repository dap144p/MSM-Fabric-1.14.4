package dev.cylox.msm.network.packet;

import com.github.steveice10.packetlib.io.NetInput;
import com.github.steveice10.packetlib.io.NetOutput;
import com.github.steveice10.packetlib.packet.Packet;

import java.io.IOException;

/**
 * Consumes entity attribute updates for protocol-only secondary sessions.
 * These sessions do not render or simulate entities, so attribute registries
 * newer than the 1.14.4 protocol library do not need to be interpreted.
 */
public final class IgnoredEntityPropertiesPacket implements Packet {
    private static final int MAX_ATTRIBUTES = 1024;
    private static final int MAX_MODIFIERS_PER_ATTRIBUTE = 1024;

    public IgnoredEntityPropertiesPacket() {
    }

    @Override
    public void read(NetInput input) throws IOException {
        input.readVarInt(); // Entity ID

        int attributeCount = input.readInt();
        if (attributeCount < 0 || attributeCount > MAX_ATTRIBUTES) {
            throw new IOException("Invalid entity attribute count: " + attributeCount);
        }

        for (int index = 0; index < attributeCount; index++) {
            input.readString(); // Attribute identifier
            input.readDouble(); // Base value

            int modifierCount = input.readVarInt();
            if (modifierCount < 0 || modifierCount > MAX_MODIFIERS_PER_ATTRIBUTE) {
                throw new IOException("Invalid entity attribute modifier count: " + modifierCount);
            }

            for (int modifier = 0; modifier < modifierCount; modifier++) {
                input.readUUID();
                input.readDouble();
                input.readByte(); // Modifier operation
            }
        }
    }

    @Override
    public void write(NetOutput output) throws IOException {
        output.writeVarInt(0);
        output.writeInt(0);
    }

    @Override
    public boolean isPriority() {
        return false;
    }
}
