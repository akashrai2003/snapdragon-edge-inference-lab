package ai.snapdragon.edge

import android.content.Context
import java.io.File
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model

/** Worker-thread only. One immutable acoustic model is shared by all recognition turns. */
object QuietSpeechModel {
    private var loaded: Model? = null
    private const val ASSET = "speech/vosk-en"
    private const val VERSION = "30f26242c4eb449f"

    @Synchronized
    fun get(context: Context): Model {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        loaded?.let {
            return it
        }
        val parent = File(context.noBackupFilesDir, "speech").apply { mkdirs() }
        val directory = File(parent, "english-$VERSION")
        if (!File(directory, ".complete").exists()) {
            val temporary =
                File(parent, "unpacking-$VERSION").apply {
                    deleteRecursively()
                    mkdirs()
                }
            try {
                copy(context, ASSET, temporary)
                check(File(temporary, "am/final.mdl").length() > 0)
                File(temporary, ".complete").writeText(VERSION)
                directory.deleteRecursively()
                check(temporary.renameTo(directory)) { "Speech model could not be prepared" }
            } finally {
                temporary.deleteRecursively()
            }
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(directory.absolutePath).also { loaded = it }
    }

    private fun copy(context: Context, asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            context.assets.open(asset).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } else {
            target.mkdirs()
            children.forEach { copy(context, "$asset/$it", File(target, it)) }
        }
    }
}
