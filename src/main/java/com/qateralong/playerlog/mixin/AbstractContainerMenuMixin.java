package com.qateralong.playerlog.mixin;

import com.qateralong.playerlog.ContainerTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Unique
    private ContainerTracker.Snapshot playerlog$snapshot;

    @Inject(method = "clicked", at = @At("HEAD"))
    private void playerlog$before(int slot, int button, ClickType type, Player player, CallbackInfo ci) {
        this.playerlog$snapshot = player instanceof ServerPlayer ? ContainerTracker.snapshot((AbstractContainerMenu) (Object) this) : null;
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void playerlog$after(int slot, int button, ClickType type, Player player, CallbackInfo ci) {
        ContainerTracker.Snapshot snapshot = this.playerlog$snapshot;
        this.playerlog$snapshot = null;
        if (snapshot != null && player instanceof ServerPlayer serverPlayer) {
            ContainerTracker.compare((AbstractContainerMenu) (Object) this, serverPlayer, snapshot);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void playerlog$removed(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            ContainerTracker.flush(serverPlayer);
        }
    }
}
