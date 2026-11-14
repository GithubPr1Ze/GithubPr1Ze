package fr.tachoperso.core

import fr.tachoperso.core.tacho.Generation
import fr.tachoperso.core.tacho.VehiclesParser
import fr.tachoperso.core.util.hex
import kotlin.test.Test
import kotlin.test.assertEquals

class VehiclesTest {
    @Test
    fun `utilisation de vehicule Gen2`() {
        val reg = "AB123CD".toByteArray().joinToString("") { "%02X".format(it) } + "20".repeat(6) // 13 caractères
        val vin = "VF1TEST0000000001".toByteArray().joinToString("") { "%02X".format(it) }
        // compteur début 100000, fin 100450, première/dernière utilisation, F (0x11), page de code 1
        val rec = "0186A0" + "018862" + "690AE9F6" + "690B2000" + "11" + "01" + reg + "0001" + vin
        val d = hex("0000" + rec + "00".repeat(48))
        val u = VehiclesParser.parse(d, Generation.GEN2).single()
        assertEquals("AB123CD", u.registration)
        assertEquals("F", u.nation)
        assertEquals("VF1TEST0000000001", u.vin)
        assertEquals(450, u.km)
        assertEquals("F AB123CD", u.key)
    }
}
