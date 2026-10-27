package com.qateralong.playerlog.mixin;

import com.qateralong.playerlog.Telemetry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void playerlog$beforeDeath(DamageSource source, CallbackInfo ci) {
        Telemetry.beforeDeath((ServerPlayer) (Object) this);
    }

    @Inject(method = "die", at = @At("RETURN"))
    private void playerlog$afterDeath(DamageSource source, CallbackInfo ci) {
        Telemetry.afterDeath((ServerPlayer) (Object) this);
    }
}
