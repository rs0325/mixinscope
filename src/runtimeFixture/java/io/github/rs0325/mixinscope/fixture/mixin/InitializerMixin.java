package io.github.rs0325.mixinscope.fixture.mixin;

import org.spongepowered.asm.mixin.*;
import io.github.rs0325.mixinscope.fixture.target.InitializerCalculator;

@Mixin(InitializerCalculator.class)
public abstract class InitializerMixin {
    @Unique private int fixtureInstance=7;
    @Unique private static int fixtureStatic=5;
    @Unique public int fixtureInstanceSeed(){return fixtureInstance;}
    @Unique public int fixtureStaticSeed(){return fixtureStatic;}
}
