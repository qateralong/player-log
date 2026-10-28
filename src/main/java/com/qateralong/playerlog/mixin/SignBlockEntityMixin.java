package com.qateralong.playerlog.mixin;

import com.qateralong.playerlog.Activity;
import java.util.List;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    @Inject(method = "updateSignText", at = @At("HEAD"))
    private void playerlog$sign(Player player, boolean front, List<FilteredText> lines, CallbackInfo ci) {
        SignBlockEntity sign = (SignBlockEntity) (Object) this;
        if (!sign.isWaxed() && !sign.playerIsTooFarAwayToEdit(player.getUUID())) {
            Activity.sign(player, sign.getBlockPos(), front, lines);
        }
    }
}
