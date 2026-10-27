package link.lander.android.internal

/**
 * What the SDK core needs from Android, behind an interface so the core runs
 * in plain JVM unit tests with a fake.
 */
internal interface LanderPlatform {
    /** The app's versionName, or `0.0.0`. */
    fun appVersion(): String

    /** `Build.VERSION.RELEASE`. */
    fun osVersion(): String?

    /** Raw Play Install Referrer string, or null when the Play Store is unavailable. */
    suspend fun installReferrer(): String?

    /**
     * Small key value storage kept out of device backups, so the install ID and
     * event queue never move to another device. Blocking; called off the main
     * thread.
     */
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}
