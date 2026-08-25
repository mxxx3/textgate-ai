package com.textgate.ai.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Covers [ModelAvailabilityStore.nextLosAngelesMidnight]'s pure date math —
 * the piece the whole RPD-reset guarantee (see TranslationOrchestrator's
 * class doc, and README's changelog entry for this version) rests on. Only
 * this `internal` static function is exercised directly; the rest of
 * [ModelAvailabilityStore] reads/writes SharedPreferences and needs a real
 * or Robolectric Context, so it is covered by manual review and the
 * project's established string-logic verification approach instead (see
 * TranslationPromptsTest's class doc for that approach's precedent).
 */
class ModelAvailabilityStoreTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `an instant just before midnight rolls to that same midnight`() {
        // 2026-08-25 23:59:59 PDT -> next LA midnight is 2026-08-26 00:00 PDT
        val now = ZonedDateTime.of(2026, 8, 25, 23, 59, 59, 0, zone).toInstant()
        val next = ModelAvailabilityStore.nextLosAngelesMidnight(now)
        val expected = ZonedDateTime.of(2026, 8, 26, 0, 0, 0, 0, zone).toInstant()
        assertEquals(expected, next)
    }

    @Test
    fun `an instant just after midnight rolls to the FOLLOWING midnight, not the one just passed`() {
        // 2026-08-26 00:00:01 PDT -> next LA midnight is 2026-08-27 00:00 PDT
        val now = ZonedDateTime.of(2026, 8, 26, 0, 0, 1, 0, zone).toInstant()
        val next = ModelAvailabilityStore.nextLosAngelesMidnight(now)
        val expected = ZonedDateTime.of(2026, 8, 27, 0, 0, 0, 0, zone).toInstant()
        assertEquals(expected, next)
    }

    @Test
    fun `reset math is correct across the spring-forward DST transition (23-hour day)`() {
        // 2026-03-08 is the US spring-forward date (2 AM -> 3 AM), a real
        // 23-wall-clock-hour local day. Starting from that day's own
        // midnight (still PST, before the transition) must roll to the
        // FOLLOWING midnight (already PDT), 23 real hours later — not a
        // naive fixed 24h/86400s addition. Verified independently via a
        // Python zoneinfo simulation (epoch-timestamp arithmetic, not raw
        // aware-datetime subtraction — Python's own datetime.__sub__ takes
        // a same-tzinfo fast path that silently ignores an offset change
        // between two datetimes sharing one tzinfo object, so it was
        // double-checked against .timestamp() diffs to avoid replicating
        // that exact pitfall into this test's expected values).
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zone).toInstant() // local midnight, still PST
        val next = ModelAvailabilityStore.nextLosAngelesMidnight(now)
        val expected = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zone).toInstant() // already PDT
        assertEquals(expected, next)
        assertEquals(23 * 3600L, expected.epochSecond - now.epochSecond)
    }

    @Test
    fun `reset math is correct across the fall-back DST transition (25-hour day)`() {
        // 2026-11-01 is the US fall-back date (2 AM -> 1 AM), a real
        // 25-wall-clock-hour local day. Starting from that day's own
        // midnight (still PDT) must roll to the FOLLOWING midnight (already
        // PST), 25 real hours later. See the spring-forward test above for
        // how these expected values were independently verified.
        val now = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone).toInstant() // local midnight, still PDT
        val next = ModelAvailabilityStore.nextLosAngelesMidnight(now)
        val expected = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, zone).toInstant() // already PST
        assertEquals(expected, next)
        assertEquals(25 * 3600L, expected.epochSecond - now.epochSecond)
    }

    @Test
    fun `midnight itself rolls forward a full day, not to itself`() {
        val now = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, zone).toInstant()
        val next = ModelAvailabilityStore.nextLosAngelesMidnight(now)
        val expected = ZonedDateTime.of(2026, 6, 2, 0, 0, 0, 0, zone).toInstant()
        assertEquals(expected, next)
    }
}
