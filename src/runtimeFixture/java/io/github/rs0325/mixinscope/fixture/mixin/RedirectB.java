package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.RedirectCalculator;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(RedirectCalculator.class)
public abstract class RedirectB {
    @Redirect(method = "compute(I)I", at = @At(value = "INVOKE", target = "Lio/github/rs0325/mixinscope/fixture/target/RedirectCalculator;twice(I)I"), remap = false, require = 1)
    private static int change(int n) { return n * 20; }
}
