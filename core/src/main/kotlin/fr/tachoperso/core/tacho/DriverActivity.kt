package fr.tachoperso.core.tacho

import fr.tachoperso.core.util.u16be
import java.time.LocalDate
import java.time.ZoneOffset

enum class ActivityType(val label: String) {
    REST("Repos / pause"),
    AVAILABILITY("Disponibilité"),
    WORK("Travail"),
    DRIVING("Conduite"),
}

/**
 * ActivityChangeInfo (2 octets) : bits « scpaattttttttttt »
 * s = emplacement (0 conducteur, 1 second conducteur), c = équipage, p = carte retirée,
 * aa = activité, t = minute depuis 00:00 UTC.
 */
data class ActivityChange(
    val coDriverSlot: Boolean,
    val crew: Boolean,
    val cardNotInserted: Boolean,
    val type: ActivityType,
    val minute: Int,
) {
    companion object {
        fun parse(v: Int) = ActivityChange(
            coDriverSlot = v and 0x8000 != 0,
            crew = v and 0x4000 != 0,
            cardNotInserted = v and 0x2000 != 0,
            type = ActivityType.entries[(v shr 11) and 0x03],
            minute = v and 0x07FF,
        )
    }
}

data class ActivitySegment(val startMinute: Int, val endMinute: Int, val type: ActivityType, val cardNotInserted: Boolean) {
    val duration: Int get() = endMinute - startMinute
}

/** Une journée (UTC) d'activités enregistrées sur la carte. */
data class DailyActivity(
    val date: LocalDate,
    val presenceCounter: Int?,
    val distanceKm: Int,
    val changes: List<ActivityChange>,
) {
    val segments: List<ActivitySegment> by lazy {
        val valid = changes.filter { it.minute < MINUTES_PER_DAY }
        valid.mapIndexedNotNull { i, c ->
            val end = if (i + 1 < valid.size) valid[i + 1].minute else MINUTES_PER_DAY
            if (end > c.minute) ActivitySegment(c.minute, end, c.type, c.cardNotInserted) else null
        }
    }

    /** Minutes par activité, carte insérée uniquement. */
    fun minutes(type: ActivityType): Int =
        segments.filter { it.type == type && !it.cardNotInserted }.sumOf { it.duration }

    /** Minutes carte retirée (activité inconnue ou saisie manuelle). */
    val minutesCardOut: Int get() = segments.filter { it.cardNotInserted }.sumOf { it.duration }

    companion object {
        const val MINUTES_PER_DAY = 1440
    }
}

/**
 * EF Driver_Activity_Data : 2 pointeurs puis un tampon circulaire d'enregistrements journaliers
 * (CardActivityDailyRecord), chacun pouvant « faire le tour » du tampon.
 */
object DriverActivityParser {
    fun parse(data: ByteArray): List<DailyActivity> {
        if (data.size < 4 + 12) return emptyList()
        val oldest = data.u16be(0)
        val newest = data.u16be(2)
        val buf = data.copyOfRange(4, data.size)
        val size = buf.size
        if (oldest >= size || newest >= size) return emptyList()

        fun byteAt(pos: Int) = buf[Math.floorMod(pos, size)].toInt() and 0xFF
        fun u16(pos: Int) = (byteAt(pos) shl 8) or byteAt(pos + 1)
        fun u32(pos: Int) = (u16(pos).toLong() shl 16) or u16(pos + 2).toLong()

        val days = mutableListOf<DailyActivity>()
        var pos = newest
        val seen = HashSet<Int>()
        while (seen.add(pos) && days.size < 1000) {
            val prevLen = u16(pos)
            val recLen = u16(pos + 2)
            if (recLen < 12 || recLen > size) break
            val seconds = u32(pos + 4)
            val counterBytes = byteArrayOf(byteAt(pos + 8).toByte(), byteAt(pos + 9).toByte())
            val distance = u16(pos + 10)
            val changes = (0 until (recLen - 12) / 2).map { ActivityChange.parse(u16(pos + 12 + it * 2)) }
            if (seconds != 0L && seconds != 0xFFFFFFFFL) {
                val date = java.time.Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate()
                days += DailyActivity(date, DataTypes.bcd(counterBytes, 0, 2), distance, changes)
            }
            if (pos == oldest || prevLen == 0) break
            pos = Math.floorMod(pos - prevLen, size)
        }
        return days.sortedByDescending { it.date }
    }
}
