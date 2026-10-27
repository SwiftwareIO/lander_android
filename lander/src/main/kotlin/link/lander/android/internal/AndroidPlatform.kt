package link.lander.android.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** [LanderPlatform] for a real app. */
internal class AndroidPlatform(
    private val context: Context,
    private val logger: LanderLogger,
) : LanderPlatform {
    private val appVersion: String by lazy {
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    override fun appVersion(): String = appVersion

    override fun osVersion(): String? = Build.VERSION.RELEASE

    // Storage: one file per key in noBackupFilesDir, which Android never backs
    // up, so the install ID is never restored onto another device.

    private val dir: File by lazy { File(context.noBackupFilesDir, STORAGE_DIR) }

    private fun file(key: String): File {
        require(KEY_PATTERN.matches(key)) { "Storage keys must match $KEY_PATTERN" }
        dir.mkdirs()
        return File(dir, key)
    }

    @Synchronized
    override fun read(key: String): String? {
        val f = file(key)
        return if (f.exists()) f.readText() else null
    }

    @Synchronized
    override fun write(key: String, value: String) {
        val f = file(key)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(f)) {
            f.writeText(value)
            tmp.delete()
        }
    }

    @Synchronized
    override fun delete(key: String) {
        file(key).delete()
    }

    // Play Install Referrer

    override suspend fun installReferrer(): String? = connect(retriesLeft = 1)

    private suspend fun connect(retriesLeft: Int): String? {
        val client = try {
            InstallReferrerClient.newBuilder(context).build()
        } catch (e: Exception) {
            logger.log("Install referrer unavailable: $e")
            return null
        }
        var retry = false
        try {
            val code = suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                fun finish(code: Int) {
                    if (done.compareAndSet(false, true)) cont.resume(code)
                }
                try {
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) = finish(responseCode)
                        override fun onInstallReferrerServiceDisconnected() = finish(DISCONNECTED)
                    })
                } catch (e: Exception) {
                    // Thrown on devices without the Play Store or with a broken one.
                    logger.log("Install referrer connection failed: $e")
                    finish(FAILED)
                }
            }
            when (code) {
                InstallReferrerClient.InstallReferrerResponse.OK -> {
                    // getInstallReferrer is a binder call; keep it off the main thread.
                    return withContext(Dispatchers.IO) {
                        try {
                            client.installReferrer.installReferrer
                        } catch (e: Exception) {
                            logger.log("Install referrer unavailable: $e")
                            null
                        }
                    }
                }
                InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE, DISCONNECTED ->
                    retry = retriesLeft > 0
                else -> logger.log("Install referrer not available (code $code)")
            }
        } finally {
            try {
                client.endConnection()
            } catch (_: Exception) {
            }
        }
        if (!retry) return null
        // The Play Store service was busy or updating. Try once more shortly.
        delay(1_000)
        return connect(retriesLeft - 1)
    }

    private companion object {
        const val STORAGE_DIR = "lander"
        val KEY_PATTERN = Regex("^[a-z0-9_]{1,64}$")
        const val DISCONNECTED = -1000
        const val FAILED = -1001
    }
}
