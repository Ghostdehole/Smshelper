package com.ghost.smshelper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class FullBackupCodecTest {
    private fun document() = FullBackupCodec.document(mapOf("sms" to "{\"messages\":[]}", "rules" to "{}"), 100)

    @Test fun plainBackupRoundTripsWithoutPasswordOrDeviceKey() {
        val plain = document()
        assertFalse(FullBackupCodec.encrypted(plain))
        assertEquals(plain, FullBackupCodec.decode(FullBackupCodec.encode(plain)))
    }

    @Test fun passwordBackupIsAuthenticatedAndHasFreshRandomSaltAndNonce() {
        val password = "我自己的备份密码🔐".toCharArray()
        val first = FullBackupCodec.encode(document(), password)
        val second = FullBackupCodec.encode(document(), password)
        assertTrue(FullBackupCodec.encrypted(first))
        assertNotEquals(JSONObject(first).getString("salt"), JSONObject(second).getString("salt"))
        assertNotEquals(JSONObject(first).getString("iv"), JSONObject(second).getString("iv"))
        assertEquals(document(), FullBackupCodec.decode(first, password))
        assertEquals(document(), FullBackupCodec.decode(second, password))
    }

    @Test fun wrongPasswordAndCiphertextTamperingCannotProducePartialData() {
        val encoded = FullBackupCodec.encode(document(), "password123".toCharArray())
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decode(encoded, "wrongpassword".toCharArray()) }
        val envelope = JSONObject(encoded)
        val bytes = Base64.getDecoder().decode(envelope.getString("ciphertext"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(bytes))
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decode(envelope.toString(), "password123".toCharArray()) }
    }

    @Test fun oversizedKdfIsRejectedBeforeSpendingUnboundedCpu() {
        val malformed = JSONObject().put("format", "smshelper-backup-encrypted").put("version", 1)
            .put("kdf", "PBKDF2WithHmacSHA256").put("cipher", "AES-256-GCM").put("iterations", Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decode(malformed.toString(), "password123".toCharArray()) }
    }

    @Test fun unrecognizedVersionOrConfigurationSectionIsRejected() {
        val badVersion = JSONObject(document()).put("version", 99)
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.validate(badVersion.toString()) }
        val extra = JSONObject(document())
        extra.getJSONObject("sections").put("pin_code", JSONObject())
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.validate(extra.toString()) }
    }

    @Test fun shortExportPasswordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.encode(document(), "1234".toCharArray()) }
    }
}
