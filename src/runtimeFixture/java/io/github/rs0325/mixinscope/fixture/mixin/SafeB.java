package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.SafeCalculator;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SafeCalculator.class)
public abstract class SafeB {
    @Inject(method = "compute(I)I", at = @At("HEAD"), remap = false)
    private static void before(int n, CallbackInfoReturnable<Integer> ci) { SafeCalculator.observations++; }
}
