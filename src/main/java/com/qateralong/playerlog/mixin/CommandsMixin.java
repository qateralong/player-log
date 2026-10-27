package com.qateralong.playerlog.mixin;

import com.mojang.brigadier.ParseResults;
import com.qateralong.playerlog.Telemetry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void playerlog$command(ParseResults<CommandSourceStack> results, String command, CallbackInfo ci) {
        Telemetry.command(results.getContext().getSource(), command);
    }
}
