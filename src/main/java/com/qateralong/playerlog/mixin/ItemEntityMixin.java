package com.qateralong.playerlog.mixin;

import com.qateralong.playerlog.Telemetry;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    private UUID thrower;

    @Unique
    private ItemStack playerlog$before;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void playerlog$touchStart(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        this.playerlog$before = player instanceof ServerPlayer && Telemetry.tracked(self, this.thrower, player) ? self.getItem().copy() : null;
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void playerlog$touchEnd(Player player, CallbackInfo ci) {
        ItemStack before = this.playerlog$before;
        this.playerlog$before = null;
        if (before == null) {
            return;
        }
        ItemEntity self = (ItemEntity) (Object) this;
        int left = self.isRemoved() ? 0 : self.getItem().getCount();
        if (left < before.getCount()) {
            Telemetry.pickup((ServerPlayer) player, self, this.thrower, before, before.getCount() - left);
        }
    }
}
