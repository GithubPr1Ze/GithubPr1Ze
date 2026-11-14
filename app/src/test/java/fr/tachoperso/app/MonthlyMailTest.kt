package fr.tachoperso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class MonthlyMailTest {
    private val now = Instant.parse("2026-11-03T08:00:00Z")

    @Test
    fun recapProposeApresLectureDuMois() {
        // Lecture le 1er novembre à 0h30 (heure de Paris) : octobre est complet.
        assertEquals(YearMonth.of(2026, 10), MonthlyMail.dueMonth(Instant.parse("2026-10-31T23:30:00Z"), now))
        assertNull(MonthlyMail.readFirstMonth(Instant.parse("2026-10-31T23:30:00Z"), now))
    }

    @Test
    fun carteAViderAvantEnvoi() {
        // Lecture le 31 octobre au soir (heure de Paris) : il faut relire la carte d'abord.
        val last = Instant.parse("2026-10-31T21:00:00Z")
        assertNull(MonthlyMail.dueMonth(last, now))
        assertEquals(YearMonth.of(2026, 10), MonthlyMail.readFirstMonth(last, now))
        assertTrue(MonthlyMail.incomplete(last, YearMonth.of(2026, 10)))
        assertFalse(MonthlyMail.incomplete(last, YearMonth.of(2026, 9)))
    }

    @Test
    fun lectureTropAncienne() {
        assertNull(MonthlyMail.readFirstMonth(Instant.parse("2026-09-20T10:00:00Z"), now))
        assertNull(MonthlyMail.dueMonth(null, now))
    }
}
