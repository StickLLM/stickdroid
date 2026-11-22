package com.ninkinc.stickdroid.core

/**
 * PAIRING-SPEC §4.5 / §4.6 key derivation. Formulas follow kvm_vectors.py
 * (authoritative for qr-v1 and kvm-usb vectors).
 */
object Derive {
    fun transcriptHash(
        method: ByteArray,
        route: ByteArray,
        homeId: ByteArray,
        extra: ByteArray,
        initiatorStatic: ByteArray,
        responderStatic: ByteArray
    ): ByteArray {
        require(initiatorStatic.size == 32 && responderStatic.size == 32)
        return Crypto.sha256(
            Bytes.concat(
                Bytes.enc(Bytes.ascii("sl1/th/v1")),
                Bytes.enc(method),
                Bytes.enc(route),
                Bytes.enc(homeId),
                Bytes.enc(extra),
                initiatorStatic,
                responderStatic
            )
        )
    }

    /** QR TH uses a 32-zero initiator_static placeholder (spec §4.5 X1). */
    fun qrTh(mailbox: ByteArray, homeId: ByteArray, stickStaticPub: ByteArray): ByteArray {
        require(mailbox.size == 8 && homeId.size == 16 && stickStaticPub.size == 32)
        return transcriptHash(
            Bytes.ascii("qr"), mailbox, homeId, ByteArray(0),
            ByteArray(32), stickStaticPub
        )
    }

    fun bootPsk(methodSecret: ByteArray, th: ByteArray, method: String): ByteArray {
        return Crypto.hkdf(methodSecret, th, Bytes.ascii("sl1/$method-boot"), 32)
    }

    fun qrBootPsk(inviteSecret: ByteArray, th: ByteArray): ByteArray =
        bootPsk(inviteSecret, th, "qr")

    fun jkQr(inviteSecret: ByteArray): ByteArray =
        Crypto.hkdf(inviteSecret, ByteArray(0), Bytes.ascii("sl1/qr-join"), 32)

    fun qrJoinMac(jkQr: ByteArray, serverNonce: ByteArray, mailbox: ByteArray): ByteArray {
        require(serverNonce.size == 32 && mailbox.size == 8)
        return Crypto.hmacSha256(jkQr, Bytes.concat(Bytes.ascii("sl1/qr-join-mac"), serverNonce, mailbox))
    }

    fun envQr(inviteSecret: ByteArray, mailbox: ByteArray, homeId: ByteArray): ByteArray {
        require(mailbox.size == 8 && homeId.size == 16)
        val info = Bytes.concat(Bytes.ascii("sl1/qr-env"), Bytes.enc(mailbox), homeId)
        return Crypto.hkdf(inviteSecret, ByteArray(0), info, 32)
    }

    /**
     * QR envelope AAD (spec §4.5): enc(route) || mailbox id || direction,
     * where route is the mailbox id. dir 0x01 joiner->stick, 0x02 stick->joiner.
     */
    fun envQrAad(mailbox: ByteArray, direction: Int): ByteArray {
        require(mailbox.size == 8 && (direction == 0x01 || direction == 0x02))
        return Bytes.concat(Bytes.enc(mailbox), mailbox, byteArrayOf(direction.toByte()))
    }

    fun sealEnvQr(envK: ByteArray, mailbox: ByteArray, direction: Int, plaintext: ByteArray, nonce: ByteArray? = null): ByteArray {
        val n = nonce ?: Crypto.random(12)
        require(n.size == 12)
        return n + Crypto.chachaEncrypt(envK, n, envQrAad(mailbox, direction), plaintext)
    }

    fun openEnvQr(envK: ByteArray, mailbox: ByteArray, direction: Int, box: ByteArray): ByteArray {
        require(box.size >= 12 + 16)
        return Crypto.chachaDecrypt(envK, box.copyOfRange(0, 12), envQrAad(mailbox, direction), box.copyOfRange(12, box.size))
    }

    /**
     * pair_psk = HKDF(ikm = r_i || r_r || prior, salt = h || TH,
     *                 info = "sl1/pair-psk/v1" || initiator_static || responder_static || u32be(key_epoch))
     * prior is boot_psk for qr; empty for kvm-usb.
     * initiator/responder statics are the Noise-authenticated keys.
     */
    fun pairPsk(
        rI: ByteArray, rR: ByteArray, prior: ByteArray,
        handshakeHash: ByteArray, th: ByteArray,
        initiatorStatic: ByteArray, responderStatic: ByteArray,
        keyEpoch: Int = 1
    ): ByteArray {
        require(rI.size == 32 && rR.size == 32 && handshakeHash.size == 32 && th.size == 32)
        require(initiatorStatic.size == 32 && responderStatic.size == 32)
        val ikm = Bytes.concat(rI, rR, prior)
        val salt = Bytes.concat(handshakeHash, th)
        val info = Bytes.concat(
            Bytes.ascii("sl1/pair-psk/v1"),
            initiatorStatic, responderStatic, Bytes.u32be(keyEpoch)
        )
        return Crypto.hkdf(ikm, salt, info, 32)
    }

    fun pairConfirmKey(pairPsk: ByteArray): ByteArray =
        Crypto.hkdf(pairPsk, ByteArray(0), Bytes.ascii("sl1/pair-confirm"), 32)

    fun confirmI(kc: ByteArray, h: ByteArray): ByteArray =
        Crypto.hmacSha256(kc, Bytes.concat(Bytes.ascii("sl1/pair-confirm/i"), h))

    fun confirmR(kc: ByteArray, h: ByteArray): ByteArray =
        Crypto.hmacSha256(kc, Bytes.concat(Bytes.ascii("sl1/pair-confirm/r"), h))

    fun pSlot(pairPsk: ByteArray): ByteArray =
        Crypto.hkdf(pairPsk, ByteArray(0), Bytes.ascii("sl1/slot-secret"), 32)

    fun joinKey(pairPsk: ByteArray): ByteArray =
        Crypto.hkdf(pSlot(pairPsk), ByteArray(0), Bytes.ascii("sl1/join"), 32)

    fun envKey(pairPsk: ByteArray): ByteArray =
        Crypto.hkdf(pSlot(pairPsk), ByteArray(0), Bytes.ascii("sl1/env"), 32)

    fun slotJoinMac(jk: ByteArray, nonce: ByteArray, home: ByteArray, slot: ByteArray): ByteArray {
        require(nonce.size == 32 && home.size == 16 && slot.size == 8)
        return Crypto.hmacSha256(jk, Bytes.concat(Bytes.ascii("sl1/join"), nonce, home, slot))
    }

    const val DIR_C2S: Int = 0x01
    const val DIR_S2C: Int = 0x02
    const val ROLE_SERVER: Int = 0x01
    const val ROLE_CLIENT: Int = 0x02

    fun boxAad(home: ByteArray, slot: ByteArray, direction: Int): ByteArray {
        require(home.size == 16 && slot.size == 8)
        return Bytes.concat(Bytes.ascii("sl1/box"), home, slot, byteArrayOf(direction.toByte()))
    }

    fun sealBox(envK: ByteArray, home: ByteArray, slot: ByteArray, direction: Int, inner: ByteArray, nonce: ByteArray? = null): String {
        val n = nonce ?: Crypto.random(12)
        val raw = n + Crypto.chachaEncrypt(envK, n, boxAad(home, slot, direction), inner)
        val s = Bytes.b64u(raw)
        if (s.length > 3072) throw IllegalArgumentException("sig box too large")
        return s
    }

    fun openBox(envK: ByteArray, home: ByteArray, slot: ByteArray, direction: Int, box: String): ByteArray {
        if (box.length > 3072) throw IllegalArgumentException("bad box")
        val raw = Bytes.unb64u(box)
        if (raw.size < 12 + 16) throw IllegalArgumentException("bad box")
        return Crypto.chachaDecrypt(envK, raw.copyOfRange(0, 12), boxAad(home, slot, direction), raw.copyOfRange(12, raw.size))
    }

    fun helloInput(n: ByteArray, dev: ByteArray, role: Int, home: ByteArray?): ByteArray {
        val h = if (role == ROLE_SERVER) (home ?: throw IllegalArgumentException("server hello needs home"))
        else ByteArray(16)
        require(n.size == 32 && dev.size == 16 && h.size == 16)
        return Bytes.concat(Bytes.ascii("sl1/dev-hello/v1"), byteArrayOf(0), n, dev, byteArrayOf(role.toByte()), h)
    }

    fun helloSig(authSeed: ByteArray, n: ByteArray, dev: ByteArray, role: Int, home: ByteArray?): ByteArray =
        Crypto.ed25519Sign(authSeed, helloInput(n, dev, role, home))

    fun rotatePop(newSeed: ByteArray, n: ByteArray, dev: ByteArray, oldAk: ByteArray): ByteArray {
        val newAk = Crypto.ed25519Public(newSeed)
        return Crypto.ed25519Sign(
            newSeed,
            Bytes.concat(Bytes.ascii("sl1/dev-rotate/v1"), byteArrayOf(0), n, dev, newAk, oldAk)
        )
    }

    /** TEST-ONLY file-pairing MAC (pairfile.py), used for box interop with sticklinkd. */
    fun testPairMac(psk: ByteArray, slot: ByteArray, peerPub: ByteArray, stickPub: ByteArray): ByteArray =
        Crypto.hmacSha256(psk, Bytes.concat(Bytes.ascii("sl1/testpair"), slot, peerPub, stickPub))
}
