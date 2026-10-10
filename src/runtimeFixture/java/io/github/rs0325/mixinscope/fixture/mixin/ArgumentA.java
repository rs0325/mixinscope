package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.ArgumentCalculator;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ArgumentCalculator.class)
public abstract class ArgumentA {
    @ModifyArg(method = "compute(I)I", at = @At(value = "INVOKE", target = "Lio/github/rs0325/mixinscope/fixture/target/ArgumentCalculator;twice(I)I"), remap = false, index = 0)
    private static int change(int n) { return n * 2; }
}
