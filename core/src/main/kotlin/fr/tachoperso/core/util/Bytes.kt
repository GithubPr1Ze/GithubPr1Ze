package fr.tachoperso.core.util

/** Journal simple, injecté partout pour pouvoir diagnostiquer à distance. */
fun interface Log {
    fun log(message: String)

    companion object {
        val NONE = Log { }
    }
}

fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

fun hex(text: String): ByteArray {
    val clean = text.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "Hexadécimal invalide : $text" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

fun ByteArray.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

fun ByteArray.u24be(index: Int): Int = (u8(index) shl 16) or (u8(index + 1) shl 8) or u8(index + 2)

fun ByteArray.u32be(index: Int): Long =
    (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

fun ByteArray.u32le(index: Int): Long =
    (u8(index + 3).toLong() shl 24) or (u8(index + 2).toLong() shl 16) or
        (u8(index + 1).toLong() shl 8) or u8(index).toLong()

fun ByteArray.putU32le(index: Int, value: Int) {
    this[index] = value.toByte()
    this[index + 1] = (value shr 8).toByte()
    this[index + 2] = (value shr 16).toByte()
    this[index + 3] = (value shr 24).toByte()
}
