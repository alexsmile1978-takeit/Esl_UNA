package com.mrg.eslscanner.data

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef

/**
 * Shared NFC tag parsing. Extracts the NDEF text record most Hanshow ESL
 * price tags carry (e.g. "C0-BF-3E-91"), falling back to reading the tag's
 * own NDEF data directly, then finally its raw serial number.
 */
object NfcReader {

    fun readValue(intent: Intent): String? {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val ndefMessage = rawMessages?.firstOrNull() as? NdefMessage

        val fromNdef = ndefMessage?.records?.firstNotNullOfOrNull { parseTextRecord(it) }
        if (fromNdef != null) return fromNdef

        tag?.let { t ->
            try {
                Ndef.get(t)?.let { ndef ->
                    ndef.connect()
                    val message = ndef.ndefMessage
                    ndef.close()
                    message?.records?.firstNotNullOfOrNull { parseTextRecord(it) }?.let { return it }
                }
            } catch (ignored: Exception) {
                // fall through to serial number
            }
            return t.id.joinToString(":") { String.format("%02X", it) }
        }
        return null
    }

    /** Decodes a Well-Known TNF "T" (text) NDEF record per the NFC Forum spec. */
    private fun parseTextRecord(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            return null
        }
        return try {
            val payload = record.payload
            val isUtf16 = (payload[0].toInt() and 0x80) != 0
            val languageCodeLength = payload[0].toInt() and 0x3F
            val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
            String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, charset).trim()
        } catch (ignored: Exception) {
            null
        }
    }
}
