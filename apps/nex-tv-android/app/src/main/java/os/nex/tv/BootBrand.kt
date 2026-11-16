package os.nex.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Logo de la empresa al prender el equipo (TV box HiSilicon con root, como los TX6698):
 *  - partición "logo" (lo que muestra el bootloader): cabecera LOGO_TABLE de 8 KiB + JPEG 1920x1080 baseline;
 *    la cabecera solo guarda el largo del JPEG (byte 120, u32 little endian), sin checksum.
 *  - /system/media/bootanimation.zip (la animación de Android): el logo aparece de a poco y queda fijo.
 * Si la empresa no cargó logo, vuelve al de NEX (res/raw). Sin root o sin partición HiSilicon no toca nada.
 * Graba solo si cambió y verifica leyendo de vuelta. Corre después de cada sync (Agent), en el mismo hilo.
 */
object BootBrand {
    private const val TAG = "nex-boot-brand"
    private const val VERSION = "1"
    private const val HEADER = 8192
    private const val LEN_OFFSET = 120
    private const val RETRY_MS = 6 * 60 * 60 * 1000L
    private const val ANIM = "/system/media/bootanimation.zip"

    private var lastFailKey = ""
    private var lastFailAt = 0L

    /** logo: el de la empresa (Agent.currentLogo) o null para el de NEX. stateDir: dónde se recuerda lo aplicado. */
    fun sync(ctx: Context, logo: File?, stateDir: File) {
        if (BuildConfig.DEMO) return
        val su = Provision.suBinary() ?: return
        val source = logo?.readBytes() ?: raw(ctx, R.raw.boot_logo_nex) ?: return
        val key = "v$VERSION:" + sha256(source).take(16)
        val applied = File(stateDir, "boot_brand")
        if (applied.readText(ifMissing = "") == key) return
        if (key == lastFailKey && SystemClock.elapsedRealtime() - lastFailAt < RETRY_MS) return

        val ok = runCatching {
            val (jpeg, anim) = if (logo == null) {
                source to (raw(ctx, R.raw.bootanim_nex) ?: error("falta la animación de NEX"))
            } else {
                val bmp = BitmapFactory.decodeByteArray(source, 0, source.size) ?: error("logo ilegible")
                try { renderLogo(bmp) to renderAnimation(bmp) } finally { bmp.recycle() }
            }
            val work = File(ctx.cacheDir, "boot-brand").apply { mkdirs() }
            writeLogoPartition(su, jpeg, work) && writeAnimation(su, anim, work)
        }.onFailure { Log.w(TAG, "no se pudo aplicar el logo de arranque", it) }.getOrDefault(false)

        if (ok) {
            applied.writeText(key)
            Log.i(TAG, "logo de arranque aplicado: ${if (logo == null) "NEX" else "empresa"}")
        } else {
            lastFailKey = key
            lastFailAt = SystemClock.elapsedRealtime()
        }
    }

    // ---- Partición logo ----

    private fun writeLogoPartition(su: String, jpeg: ByteArray, work: File): Boolean {
        val part = sh(su, "readlink -f /dev/block/by-name/logo").trim()
        if (!part.startsWith("/dev/block/")) return false.also { Log.i(TAG, "sin partición logo") }
        val headerFile = File(work, "header.bin")
        sh(su, "dd if=$part of=${headerFile.path} bs=$HEADER count=1 2>/dev/null; chmod 644 ${headerFile.path}")
        val header = headerFile.takeIf { it.length() == HEADER.toLong() }?.readBytes() ?: return false
        if (String(header, 0, 3, Charsets.US_ASCII) != "###" || String(header, 8, 10, Charsets.US_ASCII) != "LOGO_TABLE") {
            Log.i(TAG, "la partición logo no es de HiSilicon: no se toca")
            return false
        }
        val size = sh(su, "blockdev --getsize64 $part").trim().toLongOrNull() ?: return false
        if (HEADER + jpeg.size > size) return false
        val image = header.copyOf()
        val n = jpeg.size
        image[LEN_OFFSET] = n.toByte(); image[LEN_OFFSET + 1] = (n shr 8).toByte()
        image[LEN_OFFSET + 2] = (n shr 16).toByte(); image[LEN_OFFSET + 3] = (n shr 24).toByte()
        val bytes = image + jpeg
        val want = sha256(bytes)
        // Si ya tiene esta imagen (por ejemplo, el de NEX que grabó el kit), no se escribe.
        if (sh(su, "head -c ${bytes.size} $part | sha256sum").take(64) == want) return true
        val file = File(work, "logo.img").apply { writeBytes(bytes); setReadable(true, false) }
        sh(su, "dd if=${file.path} of=$part bs=$HEADER 2>/dev/null; sync")
        val got = sh(su, "head -c ${bytes.size} $part | sha256sum").take(64)
        file.delete()
        return (got == want).also { if (!it) Log.w(TAG, "la partición logo no quedó igual") }
    }

    private fun renderLogo(logo: Bitmap): ByteArray {
        val canvas = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply {
            drawColor(Color.BLACK)
            drawFitted(logo, Rect(460, 290, 1460, 790))
        }
        return ByteArrayOutputStream().use { out ->
            canvas.compress(Bitmap.CompressFormat.JPEG, 95, out)
            canvas.recycle()
            out.toByteArray()
        }
    }

    // ---- Animación ----

    private const val ANIM_W = 1000
    private const val ANIM_H = 500
    private const val FADE_FRAMES = 15

    /** desc.txt + part0 (aparece en 15 cuadros) + part1 (queda fijo), todo sin comprimir como pide Android. */
    private fun renderAnimation(logo: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            stored(zip, "desc.txt", "$ANIM_W $ANIM_H 30\np 1 0 part0\np 0 0 part1\n".toByteArray())
            for (i in 0 until FADE_FRAMES) stored(zip, "part0/%02d.png".format(i), frame(logo, (i + 1) * 255 / FADE_FRAMES))
            stored(zip, "part1/00.png", frame(logo, 255))
        }
        return out.toByteArray()
    }

    private fun frame(logo: Bitmap, alpha: Int): ByteArray {
        val bmp = Bitmap.createBitmap(ANIM_W, ANIM_H, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.BLACK)
            drawFitted(logo, Rect(50, 50, ANIM_W - 50, ANIM_H - 50), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
        }
        return ByteArrayOutputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o); bmp.recycle(); o.toByteArray() }
    }

    private fun stored(zip: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32().apply { update(data) }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = data.size.toLong(); this.crc = crc.value
        })
        zip.write(data)
        zip.closeEntry()
    }

    private fun writeAnimation(su: String, anim: ByteArray, work: File): Boolean {
        val want = sha256(anim)
        if (sh(su, "sha256sum $ANIM").take(64) == want) return true
        val file = File(work, "bootanimation.zip").apply { writeBytes(anim); setReadable(true, false) }
        // Igual que install-system.sh: la partición del sistema arranca de solo lectura. La de fábrica se guarda una vez.
        sh(su, "sys=\$(readlink -f /dev/block/by-name/system); [ -b \"\$sys\" ] && blockdev --setrw \"\$sys\"; " +
            "mount -o rw,remount / && { [ -f $ANIM.factory ] || cp $ANIM $ANIM.factory; } && cp ${file.path} $ANIM && " +
            "chown root:root $ANIM && chmod 644 $ANIM && { chcon u:object_r:system_file:s0 $ANIM 2>/dev/null; true; }; " +
            "sync; mount -o ro,remount / 2>/dev/null")
        file.delete()
        val got = sh(su, "sha256sum $ANIM").take(64)
        return (got == want).also { if (!it) Log.w(TAG, "la animación de arranque no quedó igual") }
    }

    // ---- Utilidades ----

    private fun Canvas.drawFitted(bmp: Bitmap, box: Rect, paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)) {
        val scale = minOf(box.width().toFloat() / bmp.width, box.height().toFloat() / bmp.height)
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val left = box.left + (box.width() - w) / 2
        val top = box.top + (box.height() - h) / 2
        drawBitmap(bmp, null, Rect(left, top, left + w, top + h), paint)
    }

    private fun sh(su: String, command: String): String = runCatching {
        val p = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        val text = StringBuilder()
        val reader = Thread { runCatching { text.append(p.inputStream.bufferedReader().readText()) } }.apply { start() }
        // Process.waitFor(timeout) es de Android 8: se espera a mano (minSdk 24).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (runCatching { p.exitValue(); false }.getOrDefault(true) && System.nanoTime() < deadline) Thread.sleep(100)
        if (runCatching { p.exitValue(); false }.getOrDefault(true)) p.destroy()
        reader.join(1000)
        text.toString()
    }.getOrDefault("")

    private fun raw(ctx: Context, id: Int): ByteArray? =
        runCatching { ctx.resources.openRawResource(id).use { it.readBytes() } }.getOrNull()

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun File.readText(ifMissing: String): String = runCatching { readText().trim() }.getOrDefault(ifMissing)
}
