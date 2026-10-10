package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.ChainCalculator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChainCalculator.class)
public abstract class ChainB {
    @WrapOperation(method = "compute(I)I", at = @At(value = "INVOKE", target = "Lio/github/rs0325/mixinscope/fixture/target/ChainCalculator;twice(I)I"), remap = false)
    private static int wrap(int n, Operation<Integer> original) { return original.call(n) + 2; }
}
