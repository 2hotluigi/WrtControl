package dev.angel.wrtcontrol.addons

import java.security.SecureRandom
import java.util.Base64

/**
 * Claves de WireGuard (Curve25519, RFC 7748) en Kotlin puro: Android no trae X25519 hasta la
 * versión 13 y la app empieza en la 8. Es la multiplicación escalar de TweetNaCl, que trabaja en
 * tiempo constante (sin ramas que dependan de la clave). Solo se usa para crear la clave del
 * teléfono y sacar su pública: el túnel lo hace wireguard-go.
 */
object X25519 {

    /** Clave privada nueva con el "clamping" de `wg genkey`, en base64 como la escribe wg. */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): String {
        val k = ByteArray(32).also(random::nextBytes)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        return Base64.getEncoder().encodeToString(k)
    }

    /** Clave precompartida (`wg genpsk`): 32 bytes al azar. */
    fun generatePresharedKey(random: SecureRandom = SecureRandom()): String =
        Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))

    /** Como `wg pubkey`. */
    fun publicKey(privateKey: String): String {
        val k = decodeKey(privateKey)
        return Base64.getEncoder().encodeToString(scalarMult(k, BASE))
    }

    /** ¿Es una clave de WireGuard (32 bytes en base64)? */
    fun isKey(text: String): Boolean = runCatching { decodeKey(text) }.isSuccess

    private fun decodeKey(text: String): ByteArray {
        val bytes = Base64.getDecoder().decode(text.trim())
        require(bytes.size == 32) { "Una clave de WireGuard tiene 32 bytes" }
        return bytes
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }
    private val A24 = longArrayOf(0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    internal fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        val z = n.copyOf()
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = unpack(p)
        val a = LongArray(16)
        val b = x.copyOf()
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        a[0] = 1
        d[0] = 1
        for (i in 254 downTo 0) {
            val r = ((z[i shr 3].toInt() and 0xff) shr (i and 7)) and 1
            sel(a, b, r)
            sel(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            mul(d, e, e)
            mul(f, a, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            mul(b, a, a)
            sub(c, d, f)
            mul(a, c, A24)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            mul(b, e, e)
            sel(a, b, r)
            sel(c, d, r)
        }
        inv(c, c)
        mul(a, a, c)
        return pack(a)
    }

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Intercambia [p] y [q] si [b] es 1, sin ramas. */
    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        val m = LongArray(16)
        carry(t); carry(t); carry(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    private fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16)
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o); carry(o)
    }

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }
}
