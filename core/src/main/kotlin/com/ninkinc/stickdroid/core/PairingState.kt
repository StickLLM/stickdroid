package com.ninkinc.stickdroid.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * On-disk pairing record after a successful pair. Secrets are stored encrypted
 * by the platform layer (Android Keystore). This class is just the clear
 * in-memory / test-file form (same fields as sl_testpeer peer.json).
 */
@Serializable
data class PairingState(
    val v: Int = 1,
    val priv: String,       // b64u X25519 private
    val stickPub: String,   // b64u
    val pairPsk: String,    // b64u
    val home: String,       // b64u 16
    val slot: String,       // b64u 8
    val derpPriv: String,   // b64u
    val name: String = "phone",
    val authSeed: String? = null // optional Ed25519 seed if the client is a named device
) {
    fun sPriv(): ByteArray = Bytes.unb64u(priv, 32)
    fun stickStatic(): ByteArray = Bytes.unb64u(stickPub, 32)
    fun psk(): ByteArray = Bytes.unb64u(pairPsk, 32)
    fun homeBytes(): ByteArray = Bytes.unb64u(home, 16)
    fun slotBytes(): ByteArray = Bytes.unb64u(slot, 8)
    fun derp(): ByteArray = Bytes.unb64u(derpPriv, 32)

    fun toPeerJson(): String = buildJsonObject {
        put("v", v)
        put("priv", priv)
        put("stick_pub", stickPub)
        put("pair_psk", pairPsk)
        put("home", home)
        put("slot", slot)
        put("derp_priv", derpPriv)
        put("name", name)
    }.toString()

    companion object {
        fun fromPeerJson(text: String): PairingState {
            val o = Json.parseToJsonElement(text).jsonObject
            return PairingState(
                v = o["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                priv = o["priv"]!!.jsonPrimitive.content,
                stickPub = (o["stick_pub"] ?: o["stickPub"])!!.jsonPrimitive.content,
                pairPsk = (o["pair_psk"] ?: o["pairPsk"])!!.jsonPrimitive.content,
                home = o["home"]!!.jsonPrimitive.content,
                slot = o["slot"]!!.jsonPrimitive.content,
                derpPriv = (o["derp_priv"] ?: o["derpPriv"])!!.jsonPrimitive.content,
                name = o["name"]?.jsonPrimitive?.content ?: "phone"
            )
        }

        /** TEST-ONLY answer to a sticklinkd pair-offer (pairfile.py). */
        fun answerOffer(offerJson: String, name: String = "stickdroid", kind: String = "phone"): Pair<PairingState, String> {
            val o = Json.parseToJsonElement(offerJson).jsonObject
            require(o["t"]?.jsonPrimitive?.content == "sl1-testpair-offer" && o["v"]?.jsonPrimitive?.content == "1") {
                "not a test pairing offer"
            }
            val exp = o["exp"]!!.jsonPrimitive.content.toLong()
            if (exp < System.currentTimeMillis() / 1000) throw IllegalArgumentException("offer expired")
            val slot = Bytes.unb64u(o["slot"]!!.jsonPrimitive.content, 8)
            val psk = Bytes.unb64u(o["pair_psk"]!!.jsonPrimitive.content, 32)
            val spub = Bytes.unb64u(o["stick_pub"]!!.jsonPrimitive.content, 32)
            val home = o["home"]!!.jsonPrimitive.content
            val priv = Crypto.x25519Generate()
            val ppub = Crypto.x25519Public(priv)
            val derp = Crypto.x25519Generate()
            val mac = Derive.testPairMac(psk, slot, ppub, spub)
            val answer = buildJsonObject {
                put("t", "sl1-testpair-answer")
                put("v", 1)
                put("slot", o["slot"]!!.jsonPrimitive.content)
                put("peer_pub", Bytes.b64u(ppub))
                put("name", name)
                put("kind", kind)
                put("mac", Bytes.b64u(mac))
            }.toString()
            val st = PairingState(
                priv = Bytes.b64u(priv),
                stickPub = Bytes.b64u(spub),
                pairPsk = Bytes.b64u(psk),
                home = home,
                slot = Bytes.b64u(slot),
                derpPriv = Bytes.b64u(derp),
                name = name
            )
            return st to answer
        }
    }
}
