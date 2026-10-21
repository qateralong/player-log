package com.qateralong.playerlog.mixin;

import com.qateralong.playerlog.EventTracker;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void playerlog$explode(Entity source, DamageSource damage, ExplosionDamageCalculator calculator, double x, double y, double z, float radius,
                                   boolean fire, Level.ExplosionInteraction interaction, ParticleOptions small, ParticleOptions large,
                                   Holder<SoundEvent> sound, CallbackInfoReturnable<Explosion> cir) {
        EventTracker.explosion((ServerLevel) (Object) this, source, damage, x, y, z);
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void playerlog$spawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        EventTracker.spawn((ServerLevel) (Object) this, entity);
    }
}
