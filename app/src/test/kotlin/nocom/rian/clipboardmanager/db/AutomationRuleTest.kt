package nocom.rian.clipboardmanager.db

import org.junit.Assert.*
import org.junit.Test

class AutomationRuleTest {

    @Test
    fun testMatchPatternSafely() {
        // Test normal matching
        val rule1 = AutomationRule(conditionContentPattern = "hello")
        assertTrue(rule1.matchPatternSafely("hello world"))
        assertTrue(rule1.matchPatternSafely("say hello"))
        assertFalse(rule1.matchPatternSafely("hi world"))

        // Test case insensitivity
        val rule2 = AutomationRule(conditionContentPattern = "HeLLo")
        assertTrue(rule2.matchPatternSafely("hello world"))
        assertTrue(rule2.matchPatternSafely("HELLO WORLD"))
        assertTrue(rule2.matchPatternSafely("HeLLo WORLD"))

        // Test invalid regex pattern (should catch exception and return false)
        val rule3 = AutomationRule(conditionContentPattern = "[a-z")
        assertFalse(rule3.matchPatternSafely("abc"))

        // Test empty/blank regex pattern (should match anything)
        val rule4 = AutomationRule(conditionContentPattern = "")
        assertTrue(rule4.matchPatternSafely("any text"))
        assertTrue(rule4.matchPatternSafely(null))

        // Test null text (should return true according to implementation)
        val rule5 = AutomationRule(conditionContentPattern = "hello")
        assertTrue(rule5.matchPatternSafely(null))

        // Test ReDoS immune pattern (would backtrack exponentially in standard regex)
        val redosPattern = "(a+)+$"
        val redosInput = "a".repeat(50) + "b"
        val rule6 = AutomationRule(conditionContentPattern = redosPattern)
        assertFalse(rule6.matchPatternSafely(redosInput))
    }
}
