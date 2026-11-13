package ai.snapdragon.edge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

open class CueActivity : Activity() {
    protected var chat: CueChatView? = null
    private val audioOwner = "activity-${java.util.UUID.randomUUID()}"
    private val imports = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CueSession.init(this)
    }

    override fun onResume() {
        super.onResume()
        AudioGate.hold(audioOwner)
        ai.snapdragon.edge.service.VoiceWakeService.restoreFromActivity(this)
        CueSession.refresh()
    }

    override fun onPause() {
        chat?.cancelVoice()
        CueSession.persist()
        AudioGate.release(audioOwner)
        super.onPause()
    }

    override fun onDestroy() {
        chat?.dispose()
        imports.shutdownNow()
        AudioGate.release(audioOwner)
        super.onDestroy()
    }

    protected fun requestVoice() {
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 2002) {
            if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                chat?.startVoice()
            else
                Toast.makeText(
                        this,
                        "Microphone access is off. You can still type to Cue.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
        }
    }

    protected fun chooseImage() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            2003,
        )
    }

    @Deprecated("Framework activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2003 && resultCode == RESULT_OK) data?.data?.let { importImage(it) }
    }

    protected fun importImage(uri: Uri) {
        val app = applicationContext
        imports.execute {
            val result = runCatching {
                val opts =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri).use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
                require(opts.outWidth > 0 && opts.outHeight > 0)
                var sample = 1
                while (opts.outWidth / sample > 1280 || opts.outHeight / sample > 1280) sample *= 2
                val bitmap =
                    app.contentResolver.openInputStream(uri).use {
                        android.graphics.BitmapFactory.decodeStream(
                            it,
                            null,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    } ?: error("Invalid image")
                val dir = File(app.filesDir, "attachments").apply { mkdirs() }
                val file = File(dir, "${java.util.UUID.randomUUID()}.jpg")
                file.outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
                }
                bitmap.recycle()
                file.absolutePath
            }
            runOnUiThread {
                if (!isDestroyed)
                    result
                        .onSuccess { CueSession.attach(it) }
                        .onFailure {
                            Toast.makeText(
                                    this,
                                    "That image couldn’t be opened. Try another one.",
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
            }
        }
    }
}
