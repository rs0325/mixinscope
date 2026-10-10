package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.MethodCalculator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;

@Mixin(MethodCalculator.class)
public abstract class MethodB {
    @WrapMethod(method = "compute(I)I", remap = false)
    private static int wrap(int n, Operation<Integer> original) { return original.call(n) + 2; }
}
