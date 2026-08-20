package com.textgate.ai.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic, no Android framework classes involved — plain JUnit.
 *
 * Covers spec scenarios:
 *   #2  "normal field without ?en -> zero requests" (NoTrigger)
 *   #9  "text over the length limit -> zero requests" (TooLong)
 *   #10 "malformed trigger -> zero requests" (NoTrigger)
 */
class TriggerDetectorTest {

    @Test
    fun `exact trigger at end of text is detected and stripped`() {
        val result = TriggerDetector.detect("Daj znać jak będziesz miał chwilę, nie ma pośpiechu ?en")
        val ready = result as TriggerDetector.Outcome.Ready
        assertEquals("Daj znać jak będziesz miał chwilę, nie ma pośpiechu ", ready.content)
    }

    @Test
    fun `no trigger present results in NoTrigger`() {
        val result = TriggerDetector.detect("Normal typing without any trigger")
        assertTrue(result is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `null text results in NoTrigger`() {
        val result = TriggerDetector.detect(null)
        assertTrue(result is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `empty text results in NoTrigger`() {
        val result = TriggerDetector.detect("")
        assertTrue(result is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `trigger with nothing before it results in EmptyContent`() {
        val result = TriggerDetector.detect("?en")
        assertTrue(result is TriggerDetector.Outcome.EmptyContent)
    }

    @Test
    fun `trigger preceded only by whitespace results in EmptyContent`() {
        val result = TriggerDetector.detect("   ?en")
        assertTrue(result is TriggerDetector.Outcome.EmptyContent)
    }

    @Test
    fun `trigger must be an exact case-sensitive match`() {
        assertTrue(TriggerDetector.detect("hello ?EN") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("hello ?En") is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `trigger must be exactly at the end, not followed by anything`() {
        assertTrue(TriggerDetector.detect("hello ?en ") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("hello ?enX") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("?english") is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `partial or reordered trigger text does not match`() {
        assertTrue(TriggerDetector.detect("hello en?") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("hello en") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("hello ?e") is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `content over the max length is reported as TooLong and not delivered`() {
        val longContent = "a".repeat(TriggerDetector.MAX_INPUT_LENGTH + 1)
        val result = TriggerDetector.detect(longContent + TriggerDetector.TRIGGER)
        val tooLong = result as TriggerDetector.Outcome.TooLong
        assertEquals(TriggerDetector.MAX_INPUT_LENGTH + 1, tooLong.length)
        assertEquals(TriggerDetector.MAX_INPUT_LENGTH, tooLong.limit)
    }

    @Test
    fun `content exactly at the max length is Ready, not TooLong`() {
        val content = "a".repeat(TriggerDetector.MAX_INPUT_LENGTH)
        val result = TriggerDetector.detect(content + TriggerDetector.TRIGGER)
        assertTrue(result is TriggerDetector.Outcome.Ready)
    }
}
