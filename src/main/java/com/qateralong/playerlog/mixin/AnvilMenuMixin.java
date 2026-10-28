package com.qateralong.playerlog.mixin;

import com.qateralong.playerlog.Activity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends ItemCombinerMenu {
    private AnvilMenuMixin(MenuType<?> type, int id, Inventory inventory, ContainerLevelAccess access) {
        super(type, id, inventory, access);
    }

    @Inject(method = "onTake", at = @At("HEAD"))
    private void playerlog$rename(Player player, ItemStack stack, CallbackInfo ci) {
        Activity.rename(player, this.inputSlots.getItem(0), stack);
    }
}
