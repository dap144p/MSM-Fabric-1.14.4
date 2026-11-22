package dev.cylox.msm.mixin;

import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerInfo.class)
public interface ServerInfoAccessor {
    @Accessor("address")
    String msm$getAddress();
}
