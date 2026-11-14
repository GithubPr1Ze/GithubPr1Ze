package fr.tachoperso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RemindersTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private fun profile(lastReadDaysAgo: Long, expiry: String) =
        DriverProfile("k", "X", "Y", "1", Instant.parse(expiry), "", now.minusSeconds(lastReadDaysAgo * 86400))

    @Test
    fun rienAvant21Jours() {
        assertTrue(Reminders.due(profile(20, "2028-05-15T23:59:59Z"), now).isEmpty())
    }

    @Test
    fun lectureParPaliers() {
        assertEquals("k:read-21", Reminders.due(profile(22, "2028-05-15T23:59:59Z"), now).single().key)
        assertEquals("k:read-25", Reminders.due(profile(26, "2028-05-15T23:59:59Z"), now).single().key)
        val r = Reminders.due(profile(30, "2028-05-15T23:59:59Z"), now).single()
        assertEquals("k:read-28-0", r.key)
        assertTrue(r.urgent)
    }

    @Test
    fun expirationParPaliers() {
        assertEquals("k:exp-90", Reminders.due(profile(1, "2027-01-31T23:59:59Z"), now).single().key)
        assertEquals("k:exp-7", Reminders.due(profile(1, "2026-11-20T23:59:59Z"), now).single().key)
        assertEquals("k:exp-15", Reminders.due(profile(1, "2026-11-26T23:59:59Z"), now).single().key)
        assertEquals("k:exp-expired", Reminders.due(profile(1, "2026-11-10T23:59:59Z"), now).single().key)
    }
}
