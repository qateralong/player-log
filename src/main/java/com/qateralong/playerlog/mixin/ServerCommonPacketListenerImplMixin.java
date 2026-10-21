package com.qateralong.playerlog.mixin;

import com.mojang.authlib.GameProfile;
import com.qateralong.playerlog.PlayerLog;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Shadow
    protected abstract GameProfile playerProfile();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void playerlog$brand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof BrandPayload brand) {
            PlayerLog.BRANDS.put(this.playerProfile().getId(), brand.brand());
        }
    }
}
