package com.umaai.assistant.service;

/** One immutable EngineClient generation owns this budget. Only explicit client recreation resets it. */
final class InitializationRetryBudget {
    private int failures;
    boolean canAttempt() { return failures < 2; }
    boolean recordFailure() { failures++; return canAttempt(); }
    int failures() { return failures; }
}
