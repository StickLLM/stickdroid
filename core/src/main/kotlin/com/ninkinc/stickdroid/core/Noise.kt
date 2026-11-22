package com.ninkinc.stickdroid.core

/**
 * Noise_IKpsk2_25519_ChaChaPoly_SHA256 (Noise rev 34), matching
 * sticklink/overlay/.../noise.py and server/vectors/ikpsk2-v2.json.
 *
 * Roles: initiator = phone / AI KVM / desktop; responder = the stick.
 * PSK = pair_psk (later sessions) or boot_psk (QR pairing handshake).
 *
 *   <- s
 *   ...
 *   -> e, es, s, ss        (msg1, 96 B with empty payload)
 *   <- e, ee, se, psk      (msg2, 48 B with empty payload)
 *
 * Prologue v2 (96 B): "sl1/net" || 0x01 || home(16) || slot(8) || init_pub(32) || resp_pub(32)
 *
 * Transport: explicit 64-bit little-endian counter (also the ChaCha nonce),
 * empty AD, 64-entry sliding replay window (commit after AEAD verifies).
 */
class NoiseError(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)

object Noise {
    val PROTOCOL: ByteArray = Bytes.ascii("Noise_IKpsk2_25519_ChaChaPoly_SHA256")
    const val NET_VER: Int = 0x01
    const val HASHLEN: Int = 32
    const val TAGLEN: Int = 16
    // Kotlin Long shifts are modulo 64, so (1L shl 64) is 1, not 2^64.
    // Spec/Q-N7: rekey before ctr reaches 2^60. Treat any negative (signed overflow) as exhausted.
    const val MAX_NONCE: Long = 1L shl 60

    fun prologue(home: ByteArray, slot: ByteArray, initiatorPub: ByteArray, responderPub: ByteArray): ByteArray {
        require(home.size == 16 && slot.size == 8 && initiatorPub.size == 32 && responderPub.size == 32) {
            "home 16 B, slot 8 B, static pubs 32 B"
        }
        return Bytes.concat(Bytes.ascii("sl1/net"), byteArrayOf(NET_VER.toByte()), home, slot, initiatorPub, responderPub)
    }

    internal fun noiseHkdf(ck: ByteArray, ikm: ByteArray, n: Int): Array<ByteArray> {
        val t = Crypto.hmacSha256(ck, ikm)
        val o1 = Crypto.hmacSha256(t, byteArrayOf(0x01))
        val o2 = Crypto.hmacSha256(t, Bytes.concat(o1, byteArrayOf(0x02)))
        return if (n == 2) arrayOf(o1, o2)
        else arrayOf(o1, o2, Crypto.hmacSha256(t, Bytes.concat(o2, byteArrayOf(0x03))))
    }

    fun aeadNonce(n: Long): ByteArray {
        val out = ByteArray(12)
        var v = n
        for (i in 0 until 8) {
            out[4 + i] = (v and 0xff).toByte()
            v = v ushr 8
        }
        return out
    }

    fun aeadEncrypt(k: ByteArray, n: Long, ad: ByteArray, pt: ByteArray): ByteArray {
        if (n < 0 || n >= MAX_NONCE) throw NoiseError("nonce exhausted")
        return Crypto.chachaEncrypt(k, aeadNonce(n), ad, pt)
    }

    fun aeadDecrypt(k: ByteArray, n: Long, ad: ByteArray, ct: ByteArray): ByteArray {
        try {
            return Crypto.chachaDecrypt(k, aeadNonce(n), ad, ct)
        } catch (e: CryptoException) {
            throw NoiseError("decrypt failed", e)
        }
    }

    class Sym(prologue: ByteArray) {
        var h: ByteArray = if (PROTOCOL.size > HASHLEN) Crypto.sha256(PROTOCOL) else PROTOCOL.copyOf(HASHLEN)
        var ck: ByteArray = h.copyOf()
        var k: ByteArray? = null
        var n: Long = 0

        init { mixHash(prologue) }

        fun mixHash(d: ByteArray) { h = Crypto.sha256(h, d) }

        fun mixKey(ikm: ByteArray) {
            val o = noiseHkdf(ck, ikm, 2)
            ck = o[0]; k = o[1]; n = 0
        }

        fun mixKeyAndHash(ikm: ByteArray) {
            val o = noiseHkdf(ck, ikm, 3)
            ck = o[0]; mixHash(o[1]); k = o[2]; n = 0
        }

        fun encHash(pt: ByteArray): ByteArray {
            val key = k
            val ct = if (key == null) pt else {
                val c = aeadEncrypt(key, n, h, pt); n++; c
            }
            mixHash(ct)
            return ct
        }

        fun decHash(ct: ByteArray): ByteArray {
            val key = k
            val pt = if (key == null) ct else {
                val p = aeadDecrypt(key, n, h, ct); n++; p
            }
            mixHash(ct)
            return pt
        }

        fun split(): Pair<ByteArray, ByteArray> {
            val o = noiseHkdf(ck, ByteArray(0), 2)
            return o[0] to o[1]
        }
    }

    class Session(
        val sendK: ByteArray,
        val recvK: ByteArray,
        val handshakeHash: ByteArray,
        val remoteStatic: ByteArray
    ) {
        var sendN: Long = 0
            private set
        var maxRecv: Long = -1
            private set
        var bitmap: Long = 0
            private set

        fun encrypt(pt: ByteArray): Pair<Long, ByteArray> {
            val n = sendN
            sendN++
            return n to aeadEncrypt(sendK, n, ByteArray(0), pt)
        }

        fun decrypt(n: Long, ct: ByteArray): ByteArray {
            if (n < 0 || n >= MAX_NONCE) throw NoiseError("bad counter")
            if (n <= maxRecv - WINDOW) throw NoiseError("replay (old)")
            val offset = maxRecv - n
            if (n <= maxRecv && offset in 0 until WINDOW && ((bitmap ushr offset.toInt()) and 1L) == 1L) {
                throw NoiseError("replay")
            }
            val pt = aeadDecrypt(recvK, n, ByteArray(0), ct)
            if (n > maxRecv) {
                val shift = n - maxRecv
                bitmap = if (maxRecv >= 0) {
                    val sh = if (shift >= 64) 0L else (bitmap shl shift.toInt())
                    // WINDOW is 64: mask is all bits (Kotlin 1L shl 64 == 1)
                    (sh or 1L) and WINDOW_MASK
                } else 1L
                maxRecv = n
            } else {
                bitmap = bitmap or (1L shl (maxRecv - n).toInt())
            }
            return pt
        }

        companion object {
            const val WINDOW: Int = 64
            const val WINDOW_MASK: Long = -1L // 64-bit mask
        }
    }

    class Initiator(
        val sPriv: ByteArray,
        val rsPub: ByteArray,
        val psk: ByteArray,
        prologue: ByteArray,
        ePriv: ByteArray? = null
    ) {
        val e: ByteArray = ePriv ?: Crypto.x25519Generate()
        val sym = Sym(prologue)

        init {
            require(psk.size == 32) { "psk 32 B" }
            sym.mixHash(rsPub)
        }

        fun writeMsg1(payload: ByteArray = ByteArray(0)): ByteArray {
            val ePub = Crypto.x25519Public(e)
            sym.mixHash(ePub)
            sym.mixKey(ePub)                             // psk modifier: e also mixes the key
            sym.mixKey(Crypto.x25519Dh(e, rsPub))        // es
            val cS = sym.encHash(Crypto.x25519Public(sPriv))
            sym.mixKey(Crypto.x25519Dh(sPriv, rsPub))    // ss
            return Bytes.concat(ePub, cS, sym.encHash(payload))
        }

        fun readMsg2(msg: ByteArray): Pair<Session, ByteArray> {
            if (msg.size < 32 + TAGLEN) throw NoiseError("msg2 too short")
            val re = msg.copyOfRange(0, 32)
            sym.mixHash(re)
            sym.mixKey(re)
            sym.mixKey(Crypto.x25519Dh(e, re))           // ee
            sym.mixKey(Crypto.x25519Dh(sPriv, re))       // se
            sym.mixKeyAndHash(psk)                       // psk
            val payload = sym.decHash(msg.copyOfRange(32, msg.size))
            val (k1, k2) = sym.split()
            return Session(k1, k2, sym.h.copyOf(), rsPub) to payload
        }
    }

    class Responder(
        val sPriv: ByteArray,
        prologue: ByteArray,
        ePriv: ByteArray? = null
    ) {
        val e: ByteArray = ePriv ?: Crypto.x25519Generate()
        val sym = Sym(prologue)
        var re: ByteArray? = null
        var rs: ByteArray? = null

        init { sym.mixHash(Crypto.x25519Public(sPriv)) }

        /** Returns (initiator static pub, payload). Caller must pin-check before writeMsg2. */
        fun readMsg1(msg: ByteArray): Pair<ByteArray, ByteArray> {
            if (msg.size < 32 + 48 + TAGLEN) throw NoiseError("msg1 too short")
            re = msg.copyOfRange(0, 32)
            sym.mixHash(re!!)
            sym.mixKey(re!!)
            sym.mixKey(Crypto.x25519Dh(sPriv, re!!))     // es
            rs = sym.decHash(msg.copyOfRange(32, 80))
            if (rs!!.all { it == 0.toByte() }) throw NoiseError("low-order remote static")
            sym.mixKey(Crypto.x25519Dh(sPriv, rs!!))     // ss
            val payload = sym.decHash(msg.copyOfRange(80, msg.size))
            return rs!! to payload
        }

        fun writeMsg2(psk: ByteArray, payload: ByteArray = ByteArray(0)): Pair<Session, ByteArray> {
            require(psk.size == 32) { "psk 32 B" }
            val ePub = Crypto.x25519Public(e)
            sym.mixHash(ePub)
            sym.mixKey(ePub)
            sym.mixKey(Crypto.x25519Dh(e, re!!))         // ee
            sym.mixKey(Crypto.x25519Dh(e, rs!!))         // se
            sym.mixKeyAndHash(psk)
            val msg = Bytes.concat(ePub, sym.encHash(payload))
            val (k1, k2) = sym.split()
            return Session(k2, k1, sym.h.copyOf(), rs!!) to msg
        }
    }
}
