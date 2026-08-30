package com.peek.app.pairing

import kotlin.random.Random

/**
 * Eşleşme kodu yönetimi.
 *
 * - 6 haneli rastgele eşleşme kodu üretir
 * - İki cihaz aynı kodu girince signaling sunucusunda aynı odaya düşer
 * - Kodun geçerlilik süresi / tek kullanımlık olma mantığı burada olacak
 *
 * Bu aşamada basit rastgele üretim yeterli.
 */
object PairingManager {

    private const val CODE_LENGTH = 6
    private const val CODE_CHARS = "0123456789"

    /** Yeni 6 haneli eşleşme kodu üret. */
    fun generateCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            sb.append(CODE_CHARS[Random.nextInt(CODE_CHARS.length)])
        }
        return sb.toString()
    }

    /** Kod formatını doğrula (6 haneli rakam). */
    fun isValidCode(code: String?): Boolean {
        if (code == null || code.length != CODE_LENGTH) return false
        return code.all { it.isDigit() }
    }
}
