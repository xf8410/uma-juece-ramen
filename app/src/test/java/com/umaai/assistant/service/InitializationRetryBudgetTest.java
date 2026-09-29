package com.umaai.assistant.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class InitializationRetryBudgetTest {
    @Test public void initializationCrashWithoutRunningTaskStopsAfterSecondFailure() {
        InitializationRetryBudget budget=new InitializationRetryBudget();
        assertTrue(budget.canAttempt());assertTrue(budget.recordFailure());assertFalse(budget.recordFailure());
        for(int incomingSnapshot=0;incomingSnapshot<100;incomingSnapshot++)assertFalse(budget.canAttempt());
        assertEquals(2,budget.failures());
    }
    @Test public void onlyNewExplicitClientGenerationGetsFreshRetryBudget() {
        InitializationRetryBudget old=new InitializationRetryBudget();old.recordFailure();old.recordFailure();
        InitializationRetryBudget userRetry=new InitializationRetryBudget();assertTrue(userRetry.canAttempt());assertFalse(old.canAttempt());
    }
}
