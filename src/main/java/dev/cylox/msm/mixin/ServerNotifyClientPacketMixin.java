package dev.cylox.msm.mixin;

import com.github.steveice10.mc.protocol.data.MagicValues;
import com.github.steveice10.mc.protocol.data.game.world.notify.ClientNotification;
import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerNotifyClientPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Some servers send newer game-state notifications to older protocol clients.
 * MSM does not render a world, so it can safely ignore an unknown notification
 * while continuing to decode the rest of the login/play stream.
 */
@Mixin(value = ServerNotifyClientPacket.class, remap = false)
public abstract class ServerNotifyClientPacketMixin {
    @Redirect(
            method = "read",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/steveice10/mc/protocol/data/MagicValues;key(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;",
                    remap = false
            )
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object msm$ignoreUnknownClientNotification(Class<?> keyClass, Object key) {
        if (keyClass == ClientNotification.class && Integer.valueOf(11).equals(key)) {
            return null;
        }
        return MagicValues.key((Class) keyClass, key);
    }
}
