package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.BypassCalculator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BypassCalculator.class)
public abstract class BypassA {
    @WrapOperation(method = "compute(I)I", at = @At(value = "INVOKE", target = "Lio/github/rs0325/mixinscope/fixture/target/BypassCalculator;twice(I)I"), remap = false)
    private static int wrap(int n, Operation<Integer> original) { return 99; }
}
