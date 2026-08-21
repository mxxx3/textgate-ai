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
    fun `exact en trigger at end of text is detected and stripped, targeting English`() {
        val result = TriggerDetector.detect("Daj znać jak będziesz miał chwilę, nie ma pośpiechu ?en")
        val ready = result as TriggerDetector.Outcome.Ready
        assertEquals("Daj znać jak będziesz miał chwilę, nie ma pośpiechu ", ready.content)
        assertEquals(TriggerDetector.Target.ENGLISH, ready.target)
    }

    @Test
    fun `exact pl trigger at end of text is detected and stripped, targeting Polish`() {
        val result = TriggerDetector.detect("Let me know when you're free, no rush ?pl")
        val ready = result as TriggerDetector.Outcome.Ready
        assertEquals("Let me know when you're free, no rush ", ready.content)
        assertEquals(TriggerDetector.Target.POLISH, ready.target)
    }

    @Test
    fun `pl trigger tolerates case variation just like en does`() {
        assertTrue(TriggerDetector.detect("hello ?PL") is TriggerDetector.Outcome.Ready)
        val ready = TriggerDetector.detect("hello ?Pl") as TriggerDetector.Outcome.Ready
        assertEquals(TriggerDetector.Target.POLISH, ready.target)
    }

    @Test
    fun `trigger with nothing before it results in EmptyContent for pl too`() {
        assertTrue(TriggerDetector.detect("?pl") is TriggerDetector.Outcome.EmptyContent)
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
    fun `trigger tolerates any case of the language code (keyboard auto-capitalization)`() {
        assertTrue(TriggerDetector.detect("hello ?EN") is TriggerDetector.Outcome.Ready)
        val ready = TriggerDetector.detect("hello ?En") as TriggerDetector.Outcome.Ready
        assertEquals("hello ", ready.content)
        assertEquals(TriggerDetector.Target.ENGLISH, ready.target)
    }

    @Test
    fun `internal space plus auto-capitalized language code is tolerated (the reported real-world combo)`() {
        // This is the specific combination a real keyboard produces: the
        // optional space after "?" (tolerated on its own already) makes the
        // keyboard think a new sentence just started, so it capitalizes the
        // very next letter too — "?en" becomes "? En" / "? Pl" on-device.
        val enReady = TriggerDetector.detect("hello ? En") as TriggerDetector.Outcome.Ready
        assertEquals("hello ", enReady.content)
        assertEquals(TriggerDetector.Target.ENGLISH, enReady.target)

        val plReady = TriggerDetector.detect("hello ? Pl") as TriggerDetector.Outcome.Ready
        assertEquals("hello ", plReady.content)
        assertEquals(TriggerDetector.Target.POLISH, plReady.target)
    }

    @Test
    fun `trigger must be at the end, not followed by real content`() {
        // Trailing SPACES are tolerated (see the dedicated tests below) —
        // but any actual character after the trigger still disqualifies it.
        assertTrue(TriggerDetector.detect("hello ?enX") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("?english") is TriggerDetector.Outcome.NoTrigger)
        assertTrue(TriggerDetector.detect("hello ?en.") is TriggerDetector.Outcome.NoTrigger)
    }

    @Test
    fun `a single trailing space after the trigger is tolerated (keyboard auto-space)`() {
        val result = TriggerDetector.detect("hello ?en ")
        val ready = result as TriggerDetector.Outcome.Ready
        assertEquals("hello ", ready.content)
        assertEquals(TriggerDetector.Target.ENGLISH, ready.target)
    }

    @Test
    fun `multiple trailing spaces after the trigger are also tolerated`() {
        assertTrue(TriggerDetector.detect("hello ?en   ") is TriggerDetector.Outcome.Ready)
        assertTrue(TriggerDetector.detect("hello ?pl  ") is TriggerDetector.Outcome.Ready)
    }

    @Test
    fun `a single space between the question mark and the language code is tolerated`() {
        val enResult = TriggerDetector.detect("hello ? en")
        val enReady = enResult as TriggerDetector.Outcome.Ready
        assertEquals("hello ", enReady.content)
        assertEquals(TriggerDetector.Target.ENGLISH, enReady.target)

        val plResult = TriggerDetector.detect("hello ? pl")
        val plReady = plResult as TriggerDetector.Outcome.Ready
        assertEquals(TriggerDetector.Target.POLISH, plReady.target)
    }

    @Test
    fun `internal space and trailing spaces both being tolerated at once`() {
        val result = TriggerDetector.detect("hello ? en  ")
        val ready = result as TriggerDetector.Outcome.Ready
        assertEquals("hello ", ready.content)
        assertEquals(TriggerDetector.Target.ENGLISH, ready.target)
    }

    @Test
    fun `two spaces between the question mark and language code is NOT tolerated`() {
        // The tolerance is narrowly scoped to one specific observed keyboard
        // behavior (auto-inserting exactly one space), not general
        // whitespace fuzzing.
        assertTrue(TriggerDetector.detect("hello ?  en") is TriggerDetector.Outcome.NoTrigger)
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
