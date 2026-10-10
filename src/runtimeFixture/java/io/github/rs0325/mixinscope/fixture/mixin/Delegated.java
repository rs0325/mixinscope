package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.DelegatedCalculator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Unique;

@Mixin(DelegatedCalculator.class)
public abstract class Delegated {
    @WrapOperation(method = "compute(I)I", at = @At(value = "INVOKE", target = "Lio/github/rs0325/mixinscope/fixture/target/DelegatedCalculator;twice(I)I"), remap = false)
    private static int wrap(int n, Operation<Integer> original) { return helper(n, original); }

    @Unique
    private static int helper(int n, Operation<Integer> original) { return original.call(n) + 1; }
}
