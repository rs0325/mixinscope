package io.github.rs0325.mixinscope.fixture.target;

public final class MethodCalculator {
    public static int calls;
    public static int twice(int n) { calls++; return n * 2; }
    public static int compute(int n) { return twice(n); }
}
