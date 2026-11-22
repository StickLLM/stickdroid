package com.ninkinc.stickdroid.core

import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CryptoException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)

object Crypto {
    private val rnd = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /**
     * HKDF-SHA256 (RFC 5869). Empty salt is HashLen zeros, matching PAIRING-SPEC §4.5
     * and kvm_vectors.py.
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val saltOrZero = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(saltOrZero, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var filled = 0
        var i = 1
        while (filled < length) {
            t = hmacSha256(prk, Bytes.concat(t, info, byteArrayOf(i.toByte())))
            val n = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, n)
            filled += n
            i++
        }
        return out
    }

    fun random(n: Int): ByteArray {
        val b = ByteArray(n)
        rnd.nextBytes(b)
        return b
    }

    fun x25519Generate(): ByteArray {
        val priv = ByteArray(32)
        X25519.generatePrivateKey(rnd, priv)
        return priv
    }

    fun x25519Public(priv: ByteArray): ByteArray {
        require(priv.size == 32)
        val pub = ByteArray(32)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return pub
    }

    fun x25519Dh(priv: ByteArray, pub: ByteArray): ByteArray {
        require(priv.size == 32 && pub.size == 32)
        val out = ByteArray(32)
        val ok = X25519.calculateAgreement(priv, 0, pub, 0, out, 0)
        if (!ok || out.all { it == 0.toByte() }) throw CryptoException("low-order point")
        return out
    }

    /** IETF ChaCha20-Poly1305 (12-byte nonce, 16-byte tag appended). */
    fun chachaEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        val n = c.processBytes(pt, 0, pt.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun chachaDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(ct.size))
        return try {
            val n = c.processBytes(ct, 0, ct.size, out, 0)
            val m = c.doFinal(out, n)
            out.copyOf(n + m)
        } catch (e: Exception) {
            throw CryptoException("decrypt failed", e)
        }
    }

    fun ed25519Public(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun ed25519Sign(seed: ByteArray, msg: ByteArray): ByteArray {
        require(seed.size == 32)
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(msg, 0, msg.size)
        return s.generateSignature()
    }

    /**
     * NaCl crypto_box (X25519 + XSalsa20-Poly1305). Output is nonce24 || mac16 || ciphertext,
     * matching PyNaCl Box.encrypt().
     */
    fun naclBox(priv: ByteArray, theirPub: ByteArray, plaintext: ByteArray, nonce: ByteArray? = null): ByteArray {
        val n = nonce ?: random(24)
        require(n.size == 24)
        val k = naclBeforenm(priv, theirPub)
        val body = secretBox(k, n, plaintext)
        return n + body
    }

    fun naclBoxOpen(priv: ByteArray, theirPub: ByteArray, boxed: ByteArray): ByteArray {
        require(boxed.size >= 24 + 16)
        val n = boxed.copyOfRange(0, 24)
        val body = boxed.copyOfRange(24, boxed.size)
        val k = naclBeforenm(priv, theirPub)
        return secretBoxOpen(k, n, body)
    }

    fun naclBeforenm(priv: ByteArray, theirPub: ByteArray): ByteArray {
        val shared = x25519Dh(priv, theirPub)
        // HSalsa20(key=shared, nonce=16 zero bytes) -> 32-byte secretbox key
        return hSalsa20(shared, ByteArray(16))
    }

    /** crypto_secretbox: mac16 || ciphertext */
    fun secretBox(key: ByteArray, nonce24: ByteArray, pt: ByteArray): ByteArray {
        val ks = xsalsaKeystream(key, nonce24, 32 + pt.size)
        val polyKey = ks.copyOfRange(0, 32)
        val ct = ByteArray(pt.size)
        for (i in pt.indices) ct[i] = (pt[i].toInt() xor ks[32 + i].toInt()).toByte()
        val mac = poly1305(polyKey, ct)
        return mac + ct
    }

    fun secretBoxOpen(key: ByteArray, nonce24: ByteArray, boxed: ByteArray): ByteArray {
        require(boxed.size >= 16)
        val mac = boxed.copyOfRange(0, 16)
        val ct = boxed.copyOfRange(16, boxed.size)
        val ks = xsalsaKeystream(key, nonce24, 32 + ct.size)
        val polyKey = ks.copyOfRange(0, 32)
        val expect = poly1305(polyKey, ct)
        if (!Bytes.ctEq(mac, expect)) throw CryptoException("nacl box auth failed")
        val pt = ByteArray(ct.size)
        for (i in ct.indices) pt[i] = (ct[i].toInt() xor ks[32 + i].toInt()).toByte()
        return pt
    }

    private fun xsalsaKeystream(key: ByteArray, nonce24: ByteArray, len: Int): ByteArray {
        val eng = XSalsa20Engine()
        eng.init(true, ParametersWithIV(KeyParameter(key), nonce24))
        val z = ByteArray(len)
        val out = ByteArray(len)
        eng.processBytes(z, 0, len, out, 0)
        return out
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val p = Poly1305()
        p.init(KeyParameter(key))
        p.update(data, 0, data.size)
        val out = ByteArray(16)
        p.doFinal(out, 0)
        return out
    }

    /**
     * HSalsa20 as in NaCl (RFC 7539 cousin): 20 rounds on the Salsa core with 16-byte nonce.
     * Used only for crypto_box_beforenm. Implemented via XSalsa20's first-stage equivalent:
     * we use BC's Salsa20Engine.setKey path by asking XSalsa20 for a zero-length process
     * after extracting through a known test. A compact implementation of the 16-word core:
     */
    private fun hSalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16)
        // Use XSalsa20Engine to get the subkey: set the 24-byte nonce as nonce16 || 8 zeros,
        // then the engine's internal subkey is HSalsa20(key, nonce16). We extract it by
        // encrypting with a second nonce of zeros after... actually we implement the core.
        return salsa20Hash(key, nonce16)
    }

    private fun salsa20Hash(key: ByteArray, nonce16: ByteArray): ByteArray {
        val sigma = intArrayOf(
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
        )
        fun le(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or
                ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or
                ((b[o + 3].toInt() and 0xff) shl 24)
        val x = IntArray(16)
        x[0] = sigma[0]
        x[5] = sigma[1]
        x[10] = sigma[2]
        x[15] = sigma[3]
        x[1] = le(key, 0); x[2] = le(key, 4); x[3] = le(key, 8); x[4] = le(key, 12)
        x[11] = le(key, 16); x[12] = le(key, 20); x[13] = le(key, 24); x[14] = le(key, 28)
        x[6] = le(nonce16, 0); x[7] = le(nonce16, 4); x[8] = le(nonce16, 8); x[9] = le(nonce16, 12)
        val z = x.copyOf()
        fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))
        repeat(10) {
            z[4] = z[4] xor rotl(z[0] + z[12], 7)
            z[8] = z[8] xor rotl(z[4] + z[0], 9)
            z[12] = z[12] xor rotl(z[8] + z[4], 13)
            z[0] = z[0] xor rotl(z[12] + z[8], 18)
            z[9] = z[9] xor rotl(z[5] + z[1], 7)
            z[13] = z[13] xor rotl(z[9] + z[5], 9)
            z[1] = z[1] xor rotl(z[13] + z[9], 13)
            z[5] = z[5] xor rotl(z[1] + z[13], 18)
            z[14] = z[14] xor rotl(z[10] + z[6], 7)
            z[2] = z[2] xor rotl(z[14] + z[10], 9)
            z[6] = z[6] xor rotl(z[2] + z[14], 13)
            z[10] = z[10] xor rotl(z[6] + z[2], 18)
            z[3] = z[3] xor rotl(z[15] + z[11], 7)
            z[7] = z[7] xor rotl(z[3] + z[15], 9)
            z[11] = z[11] xor rotl(z[7] + z[3], 13)
            z[15] = z[15] xor rotl(z[11] + z[7], 18)
            z[1] = z[1] xor rotl(z[0] + z[3], 7)
            z[2] = z[2] xor rotl(z[1] + z[0], 9)
            z[3] = z[3] xor rotl(z[2] + z[1], 13)
            z[0] = z[0] xor rotl(z[3] + z[2], 18)
            z[6] = z[6] xor rotl(z[5] + z[4], 7)
            z[7] = z[7] xor rotl(z[6] + z[5], 9)
            z[4] = z[4] xor rotl(z[7] + z[6], 13)
            z[5] = z[5] xor rotl(z[4] + z[7], 18)
            z[11] = z[11] xor rotl(z[10] + z[9], 7)
            z[8] = z[8] xor rotl(z[11] + z[10], 9)
            z[9] = z[9] xor rotl(z[8] + z[11], 13)
            z[10] = z[10] xor rotl(z[9] + z[8], 18)
            z[12] = z[12] xor rotl(z[15] + z[14], 7)
            z[13] = z[13] xor rotl(z[12] + z[15], 9)
            z[14] = z[14] xor rotl(z[13] + z[12], 13)
            z[15] = z[15] xor rotl(z[14] + z[13], 18)
        }
        // HSalsa20 output words: z0, z5, z10, z15, z6, z7, z8, z9  (no add of input)
        val words = intArrayOf(z[0], z[5], z[10], z[15], z[6], z[7], z[8], z[9])
        val out = ByteArray(32)
        var o = 0
        for (w in words) {
            out[o++] = (w and 0xff).toByte()
            out[o++] = ((w ushr 8) and 0xff).toByte()
            out[o++] = ((w ushr 16) and 0xff).toByte()
            out[o++] = ((w ushr 24) and 0xff).toByte()
        }
        return out
    }
}
