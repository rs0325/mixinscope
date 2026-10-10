package io.github.rs0325.mixinscope.fixture.target;
public class OwnedOverrideChild extends ParentCalculator {
    public static int observations;
    @Override public int compute(int n) { return n*3; }
}
