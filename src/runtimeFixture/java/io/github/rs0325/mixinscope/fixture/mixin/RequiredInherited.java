package io.github.rs0325.mixinscope.fixture.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import io.github.rs0325.mixinscope.fixture.target.RequiredInheritedChild;
@Mixin(RequiredInheritedChild.class)
public abstract class RequiredInherited {
    @Inject(method="compute(I)I", at=@At("HEAD"), remap=false, require=1)
    private void before(int n, CallbackInfoReturnable<Integer> ci) { }
}
