package io.github.rs0325.mixinscope.fixture.mixin;
import org.spongepowered.asm.mixin.*;
import io.github.rs0325.mixinscope.fixture.target.OverwriteInheritedChild;
@Mixin(OverwriteInheritedChild.class)
public abstract class InheritedOverwrite {
    @Overwrite(remap=false) public int compute(int n) { return 99; }
}
