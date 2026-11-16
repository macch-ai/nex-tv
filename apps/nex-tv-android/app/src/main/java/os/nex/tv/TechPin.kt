package os.nex.tv

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PIN del acceso técnico. Lo define cada empresa en el panel (o NEX, el global) y llega en el sync como hash
 * PBKDF2-HMAC-SHA256 con sal: {"v":1, "salt":"<hex>", "iter":<n>, "hash":"<hex>"} (ver schema-mdm-v18.sql).
 * Si el equipo nunca recibió uno, vale el de respaldo que trae el APK (BuildConfig.TECH_PIN_FALLBACK, también hash).
 * Se verifica acá y no en la UI web: el hash no sale del agente. Tras varios errores se bloquea unos minutos.
 */
object TechPin {
    private const val TAG = "nex-pin"
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60 * 1000L
    private val PIN = Regex("^[0-9]{4,8}$")

    private const val SESSION_MS = 10 * 60 * 1000L

    private var fails = 0
    private var lockedUntil = 0L
    private var session = ""
    private var sessionUntil = 0L

    /** Las acciones de técnico (UiServer, /agent/setup/...) piden la sesión que entrega un PIN correcto. */
    @Synchronized
    fun sessionOk(token: String): Boolean =
        session.isNotEmpty() && SystemClock.elapsedRealtime() < sessionUntil &&
            MessageDigest.isEqual(session.toByteArray(), token.toByteArray())

    @Synchronized
    fun check(pin: String): JSONObject {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs < lockedUntil) {
            return result(false, "Demasiados intentos. Esperá ${((lockedUntil - nowMs) / 60_000) + 1} min.")
        }
        val stored = Agent.techPinHash() ?: fallback()
        if (stored == null) {
            Log.w(TAG, "sin PIN técnico configurado")
            return result(false, "Este equipo no tiene PIN técnico.")
        }
        if (PIN.matches(pin) && matches(pin, stored)) {
            fails = 0
            session = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            sessionUntil = nowMs + SESSION_MS
            return result(true).put("session", session)
        }
        fails++
        if (fails >= MAX_FAILS) {
            fails = 0
            lockedUntil = nowMs + LOCK_MS
            Log.w(TAG, "PIN técnico bloqueado por intentos fallidos")
            return result(false, "Demasiados intentos. Esperá 5 min.")
        }
        return result(false, "PIN incorrecto. Probá de nuevo.")
    }

    private fun result(ok: Boolean, error: String? = null) =
        JSONObject().put("ok", ok).put("error", error ?: JSONObject.NULL)

    private fun fallback(): JSONObject? =
        BuildConfig.TECH_PIN_FALLBACK.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun matches(pin: String, stored: JSONObject): Boolean = runCatching {
        if (stored.optInt("v") != 1) return false
        val salt = hex(stored.getString("salt"))
        val expected = hex(stored.getString("hash"))
        val iter = stored.getInt("iter")
        if (salt.size < 8 || expected.size != 32 || iter !in 1_000..200_000) return false
        MessageDigest.isEqual(pbkdf2(pin.toByteArray(), salt, iter), expected)
    }.getOrDefault(false)

    /** PBKDF2-HMAC-SHA256 de un solo bloque (32 bytes). A mano: SecretKeyFactory no lo tiene antes de Android 8. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
