package app.trackerlens.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import app.trackerlens.core.InstalledApp
import app.trackerlens.core.TextSafety
import java.io.File

/** Thin wrapper over PackageManager: the only place that knows how Android exposes installed apps. */
class AppInventory(context: Context) {
    private val pm = context.packageManager
    private val ownPackage = context.packageName
    private val dangerousCache = HashMap<String, Boolean>()
    private val labelCache = HashMap<String, String>()

    /** Every installed package (system apps included); callers decide what is in view with [inDefaultScope]. */
    fun installedPackages(): List<PackageInfo> {
        val all = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        return all.filter { it.applicationInfo != null && it.packageName != ownPackage }
    }

    /** Shown without "Include system apps": apps the user installed, plus system apps they have updated. */
    fun inDefaultScope(info: PackageInfo): Boolean {
        val flags = info.applicationInfo?.flags ?: 0
        return flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    fun toInstalledApp(info: PackageInfo) =
        InstalledApp(info.packageName, info.longVersionCode, info.lastUpdateTime, isSystem(info), inDefaultScope(info))

    /**
     * App labels come from other apps and are stored and shown (including in notifications), so they
     * are cleaned of control and formatting characters that could fake extra lines or reorder text,
     * and length-limited on character boundaries.
     */
    fun label(info: PackageInfo): String =
        TextSafety.clean(info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName, MAX_LABEL_CHARS, fallback = "Unnamed app")

    /** Requested permission names, bounded in count and length (they come from the other app's manifest). */
    fun requestedPermissions(info: PackageInfo): Set<String> =
        info.requestedPermissions.orEmpty().asSequence().take(MAX_PERMISSIONS).map { it.take(MAX_PERMISSION_CHARS) }.toSet()

    /** Base APK plus any split APKs; feature splits can carry their own DEX code. */
    fun apkFiles(info: PackageInfo): List<File> {
        val ai = info.applicationInfo ?: return emptyList()
        return listOfNotNull(ai.sourceDir).plus(ai.splitSourceDirs.orEmpty()).map(::File)
    }

    fun isSystem(info: PackageInfo): Boolean =
        (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

    fun hasCode(info: PackageInfo): Boolean =
        (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_HAS_CODE != 0

    fun isDangerous(permission: String): Boolean = synchronized(dangerousCache) {
        dangerousCache.getOrPut(permission) {
            try {
                pm.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun permissionLabel(permission: String): String = synchronized(labelCache) {
        labelCache.getOrPut(permission) {
            try {
                // Apps can declare their own permissions with arbitrary labels, so they are cleaned like app labels.
                TextSafety.clean(pm.getPermissionInfo(permission, 0).loadLabel(pm).toString(), MAX_LABEL_CHARS, permission)
                    .replaceFirstChar { it.uppercase() }
            } catch (_: PackageManager.NameNotFoundException) {
                TextSafety.clean(permission.substringAfterLast('.'), MAX_LABEL_CHARS, "Unknown permission")
            }
        }
    }

    private companion object {
        const val MAX_LABEL_CHARS = 100
        const val MAX_PERMISSIONS = 512
        const val MAX_PERMISSION_CHARS = 255

    }
}
