package fr.tachoperso.core

import fr.tachoperso.core.tacho.GeoPoint
import fr.tachoperso.core.tacho.Generation
import fr.tachoperso.core.tacho.PlaceEntryType
import fr.tachoperso.core.tacho.PlacesParser
import fr.tachoperso.core.tacho.SpecificConditionType
import fr.tachoperso.core.tacho.SpecificConditionsParser
import fr.tachoperso.core.util.hex
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {
    private val time = "690AE9F6"
    private val t = Instant.ofEpochSecond(0x690AE9F6)

    @Test
    fun `coordonnees en dixiemes de minute signees`() {
        val p = GeoPoint.fromRaw(51058, 1083)!!
        assertEquals(51 + 5.8 / 60, p.latitude, 1e-9)
        assertEquals(1 + 8.3 / 60, p.longitude, 1e-9)
        assertEquals("51°5,8' N 1°8,3' E", p.label())
        val w = GeoPoint.fromRaw(-33512, -70400)!!
        assertEquals("33°51,2' S 70°40,0' O", w.label())
        assertNull(GeoPoint.fromRaw(90001, 180001))
    }

    @Test
    fun `lieux Gen2 avec position GNSS`() {
        // pointeur, puis 1 enregistrement de 21 o, puis 1 enregistrement vide
        val rec = time + "00" + "11" + "00" + "030E7E" + // début, F, région 0, 200318 km
            time + "07" + "00C772" + "00043B"            // GNSS : 7 m, 51058 / 1083
        val d = hex("0000" + rec + "00".repeat(21))
        val places = PlacesParser.places(d, Generation.GEN2)
        assertEquals(1, places.size)
        val p = places[0]
        assertEquals(t, p.time)
        assertEquals(PlaceEntryType.BEGIN, p.type)
        assertEquals("F", p.country)
        assertEquals(200318, p.odometerKm)
        assertEquals(7, p.gnss!!.accuracyM)
        assertEquals("51°5,8' N 1°8,3' E", p.gnss!!.point!!.label())
    }

    @Test
    fun `lieux Gen1 sans position`() {
        val d = hex("00" + time + "01" + "0D" + "00" + "000064")
        val p = PlacesParser.places(d, Generation.GEN1).single()
        assertEquals(PlaceEntryType.END, p.type)
        assertEquals("D", p.country)
        assertEquals(100, p.odometerKm)
        assertNull(p.gnss)
    }

    @Test
    fun `positions toutes les 3 h de conduite, longitude negative`() {
        // longitude -1°30,0' = -1300 → complément à deux FFFAEC
        val rec = time + time + "0A" + "00C772" + "FFFAEC" + "030E7E"
        val d = hex("0001" + rec + "00".repeat(18))
        val g = PlacesParser.gnssDriving(d).single()
        assertEquals(200318, g.odometerKm)
        assertEquals(10, g.gnss!!.accuracyM)
        assertTrue(g.gnss!!.point!!.longitude < 0)
        assertEquals("51°5,8' N 1°30,0' O", g.gnss!!.point!!.label())
    }

    @Test
    fun `traversees ferry train appariees`() {
        fun rec(sec: Long, type: Int) = "%08X%02X".format(sec, type)
        val t0 = 0x690AE9F6L
        // Gen2 : pointeur, ferry 51 min, ferry sans durée (même minute), hors champ, début sans fin
        val d = hex(
            "0004" + rec(t0, 3) + rec(t0 + 51 * 60, 4) + rec(t0 + 3600 * 5, 3) + rec(t0 + 3600 * 5, 4) +
                rec(t0 + 3600 * 8, 1) + rec(t0 + 3600 * 30, 3) + "00".repeat(10),
        )
        val conds = SpecificConditionsParser.parse(d, Generation.GEN2)
        assertEquals(6, conds.size)
        assertEquals(SpecificConditionType.OUT_OF_SCOPE_BEGIN, conds[4].type)
        val c = SpecificConditionsParser.crossings(conds)
        assertEquals(3, c.size)
        assertEquals(51, c[0].minutes)
        assertTrue(c[0].measured)
        assertEquals(0, c[1].minutes)
        assertTrue(!c[1].measured)
        assertNull(c[2].end)
    }
}
