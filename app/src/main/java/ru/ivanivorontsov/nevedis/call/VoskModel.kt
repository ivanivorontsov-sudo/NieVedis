package ru.ivanivorontsov.nevedis.call

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/** Офлайн-модель Vosk лежит в APK и один раз распаковывается во внутреннюю память приложения. */
object VoskModel {
    private const val ASSET = "vosk-model-small-ru.zip"
    private const val VERSION = "small-ru-0.22"

    fun dir(context: Context): File = File(context.filesDir, "vosk-$VERSION")

    fun isReady(context: Context): Boolean = File(dir(context), ".ready").isFile

    @Synchronized
    fun prepare(context: Context): File {
        val target = dir(context)
        if (isReady(context)) return modelRoot(target)
        target.deleteRecursively()
        target.mkdirs()
        context.assets.open(ASSET).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                val base = target.canonicalPath + File.separator
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(target, e.name)
                    require(out.canonicalPath.startsWith(base)) { "bad zip entry" }
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        File(target, ".ready").writeText(VERSION)
        return modelRoot(target)
    }

    /** В архиве модель лежит во вложенной папке vosk-model-small-ru-0.22. */
    private fun modelRoot(target: File): File =
        target.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").isDirectory } ?: target
}
