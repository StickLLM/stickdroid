package com.ninkinc.stickdroid.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Outer and inner datagram types (link.py / Q-N11 / ENDPOINTS).
 * Max datagram 1280 B on any path. App body therefore <= 1250 B
 * (1 type + 4 idx + 8 ctr + 1 inner type + body + 16 tag).
 */
object Framing {
    const val T_INIT: Int = 0x01
    const val T_RESP: Int = 0x02
    const val T_DATA: Int = 0x04
    const val T_PUNCH: Int = 0x05
    const val D_PING: Int = 0x01
    const val D_PONG: Int = 0x02
    const val D_ECHO_REQ: Int = 0x10
    const val D_ECHO_REP: Int = 0x11
    const val D_APP: Int = 0x20
    const val MAX_DGRAM: Int = 1280
    const val MAX_APP_BODY: Int = 1250

    fun packInit(slot: ByteArray, sidx: Int, msg1: ByteArray): ByteArray {
        require(slot.size == 8 && msg1.size == 96)
        val out = ByteArray(1 + 8 + 4 + 96)
        out[0] = T_INIT.toByte()
        System.arraycopy(slot, 0, out, 1, 8)
        ByteBuffer.wrap(out, 9, 4).order(ByteOrder.BIG_ENDIAN).putInt(sidx)
        System.arraycopy(msg1, 0, out, 13, 96)
        return out
    }

    data class Init(val slot: ByteArray, val sidx: Int, val msg1: ByteArray)
    fun parseInit(pkt: ByteArray): Init? {
        if (pkt.size != 1 + 8 + 4 + 96 || pkt[0].toInt() and 0xff != T_INIT) return null
        return Init(
            pkt.copyOfRange(1, 9),
            ByteBuffer.wrap(pkt, 9, 4).order(ByteOrder.BIG_ENDIAN).int,
            pkt.copyOfRange(13, 109)
        )
    }

    data class Resp(val sidx: Int, val ridx: Int, val msg2: ByteArray)
    fun packResp(sidx: Int, ridx: Int, msg2: ByteArray): ByteArray {
        require(msg2.size == 48)
        val out = ByteArray(1 + 8 + 48)
        out[0] = T_RESP.toByte()
        ByteBuffer.wrap(out, 1, 8).order(ByteOrder.BIG_ENDIAN).putInt(sidx).putInt(ridx)
        System.arraycopy(msg2, 0, out, 9, 48)
        return out
    }
    fun parseResp(pkt: ByteArray): Resp? {
        if (pkt.size != 1 + 8 + 48 || pkt[0].toInt() and 0xff != T_RESP) return null
        val bb = ByteBuffer.wrap(pkt, 1, 8).order(ByteOrder.BIG_ENDIAN)
        return Resp(bb.int, bb.int, pkt.copyOfRange(9, 57))
    }

    fun packData(ridx: Int, ctr: Long, ct: ByteArray): ByteArray {
        val out = ByteArray(1 + 4 + 8 + ct.size)
        require(out.size <= MAX_DGRAM)
        out[0] = T_DATA.toByte()
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(ridx)
        val le = ByteBuffer.wrap(out, 5, 8).order(ByteOrder.LITTLE_ENDIAN)
        le.putLong(ctr)
        System.arraycopy(ct, 0, out, 13, ct.size)
        return out
    }

    data class Data(val ridx: Int, val ctr: Long, val ct: ByteArray)
    fun parseData(pkt: ByteArray): Data? {
        if (pkt.size < 1 + 4 + 8 + 16 || pkt[0].toInt() and 0xff != T_DATA) return null
        val ridx = ByteBuffer.wrap(pkt, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        val ctr = ByteBuffer.wrap(pkt, 5, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return Data(ridx, ctr, pkt.copyOfRange(13, pkt.size))
    }

    fun packPunch(): ByteArray = byteArrayOf(T_PUNCH.toByte()) + Crypto.random(8)

    fun inner(typ: Int, body: ByteArray): ByteArray = byteArrayOf(typ.toByte()) + body
}

/** Design-doc §6.1 fallback fragment header (pipe is datagram, not a reliable stream). */
object Fragments {
    const val HDR: Int = 7
    const val VER: Int = 1
    const val FLAG_LAST: Int = 0x01

    data class Piece(val seq: Int, val index: Int, val last: Boolean, val payload: ByteArray)

    fun pack(seq: Int, index: Int, last: Boolean, payload: ByteArray): ByteArray {
        val flags = ((VER and 0x0f) shl 4) or (if (last) FLAG_LAST else 0)
        val out = ByteArray(HDR + payload.size)
        out[0] = flags.toByte()
        out[1] = ((seq ushr 24) and 0xff).toByte()
        out[2] = ((seq ushr 16) and 0xff).toByte()
        out[3] = ((seq ushr 8) and 0xff).toByte()
        out[4] = (seq and 0xff).toByte()
        out[5] = ((index ushr 8) and 0xff).toByte()
        out[6] = (index and 0xff).toByte()
        System.arraycopy(payload, 0, out, HDR, payload.size)
        return out
    }

    fun parse(body: ByteArray): Piece? {
        if (body.size < HDR) return null
        val flags = body[0].toInt() and 0xff
        if ((flags ushr 4) != VER) return null
        val seq = ((body[1].toInt() and 0xff) shl 24) or ((body[2].toInt() and 0xff) shl 16) or
            ((body[3].toInt() and 0xff) shl 8) or (body[4].toInt() and 0xff)
        val index = ((body[5].toInt() and 0xff) shl 8) or (body[6].toInt() and 0xff)
        return Piece(seq, index, (flags and FLAG_LAST) != 0, body.copyOfRange(HDR, body.size))
    }

    fun split(seq: Int, message: ByteArray, maxBody: Int = Framing.MAX_APP_BODY): List<ByteArray> {
        val cap = maxBody - HDR
        require(cap > 0)
        if (message.isEmpty()) return listOf(pack(seq, 0, true, ByteArray(0)))
        val out = ArrayList<ByteArray>()
        var i = 0
        var idx = 0
        while (i < message.size) {
            val n = minOf(cap, message.size - i)
            val last = i + n >= message.size
            out.add(pack(seq, idx, last, message.copyOfRange(i, i + n)))
            i += n
            idx++
        }
        return out
    }

    class Reassembly(private val maxBytes: Int = 64 * 1024) {
        private val parts = HashMap<Int, HashMap<Int, ByteArray>>()
        private val lastIdx = HashMap<Int, Int>()

        fun push(p: Piece): ByteArray? {
            val bag = parts.getOrPut(p.seq) { HashMap() }
            bag[p.index] = p.payload
            if (p.last) lastIdx[p.seq] = p.index
            val last = lastIdx[p.seq] ?: return null
            if (bag.size != last + 1) return null
            val total = (0..last).sumOf { bag[it]?.size ?: return null }
            if (total > maxBytes) {
                parts.remove(p.seq); lastIdx.remove(p.seq)
                throw IllegalArgumentException("message too large")
            }
            val out = ByteArray(total)
            var o = 0
            for (i in 0..last) {
                val c = bag[i]!!
                System.arraycopy(c, 0, out, o, c.size)
                o += c.size
            }
            parts.remove(p.seq); lastIdx.remove(p.seq)
            return out
        }
    }
}
