package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import io.github.rs0325.mixinscope.fixture.target.OverwriteCalculator;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(OverwriteCalculator.class)
public abstract class OverwriteA {
    @Overwrite(remap = false)
    public static int compute(int n) { return 11; }
}
