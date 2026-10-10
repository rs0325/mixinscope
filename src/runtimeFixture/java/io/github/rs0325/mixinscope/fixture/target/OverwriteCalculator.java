package io.github.rs0325.mixinscope.fixture.target;

public class OverwriteCalculator {
    public static int calls, observations;
    public static int twice(int n) { calls++; return n * 2; }
    public static int compute(int n) { return twice(n); }
}
