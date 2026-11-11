package com.biometricpass.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val iconBitmap: ImageBitmap?
)

/**
 * Queries installed launchable applications so the user can bind credentials to an App's packageName.
 */
object InstalledAppHelper {

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    /** Package name -> display label (`""` sentinel when package is uninstalled/unresolvable). */
    private val labelCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var cachedAppList: List<InstalledAppInfo>? = null

    suspend fun getInstalledLaunchableApps(
        context: Context,
        forceRefresh: Boolean = false
    ): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedAppList?.let { return@withContext it }
        } else {
            labelCache.clear()
        }

        val pm = context.applicationContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val distinctPackages = LinkedHashMap<String, InstalledAppInfo>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || distinctPackages.containsKey(pkg)) continue

            val label = try {
                info.loadLabel(pm).toString().trim().ifBlank { pkg }
            } catch (_: Exception) {
                pkg
            }
            labelCache[pkg] = label

            val icon = try {
                iconCache.getOrPut(pkg) {
                    drawableToImageBitmap(info.loadIcon(pm))
                }
            } catch (_: Exception) {
                null
            }

            distinctPackages[pkg] = InstalledAppInfo(
                appName = label,
                packageName = pkg,
                iconBitmap = icon
            )
        }

        val sorted = distinctPackages.values.sortedBy { it.appName.lowercase() }
        cachedAppList = sorted
        sorted
    }

    fun getAppIcon(context: Context, packageName: String): ImageBitmap? {
        if (packageName.isBlank()) return null
        iconCache[packageName]?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableToImageBitmap(drawable).also { iconCache[packageName] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun getAppLabel(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        val cached = labelCache[packageName]
        if (cached != null) return cached.ifEmpty { null }
        val resolved = try {
            val pm = context.applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().trim()
        } catch (_: Exception) {
            ""
        }
        labelCache[packageName] = resolved
        return resolved.ifEmpty { null }
    }

    /**
     * Resolves the signing certificate SHA-256 digest(s) of [packageName] directly from [PackageManager].
     *
     * Deliberately NOT cached across calls because a package can be uninstalled and replaced by a
     * sideloaded APK with the same `packageName` while BiometricPass's process stays alive—querying
     * `GET_SIGNING_CERTIFICATES` fresh guarantees immediate detection of repackaged apps.
     */
    fun getAppSignatures(context: Context, packageName: String): AppSignatures? {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty()) return null
        return try {
            val pm = context.applicationContext.packageManager
            val pkgInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    cleanPkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                pm.getPackageInfo(cleanPkg, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val signingInfo = pkgInfo.signingInfo ?: return null
            val currentSigners = signingInfo.apkContentsSigners.orEmpty()
            val historySigners = if (signingInfo.hasMultipleSigners()) {
                emptyArray()
            } else {
                signingInfo.signingCertificateHistory.orEmpty()
            }
            val currentDigests = currentSigners.map { AppSignatureVerifier.sha256Hex(it.toByteArray()) }
            val historyDigests = historySigners.map { AppSignatureVerifier.sha256Hex(it.toByteArray()) }
            val primary = currentDigests.firstOrNull() ?: historyDigests.firstOrNull() ?: return null
            AppSignatures(
                primarySha256 = primary,
                trustedSha256Set = (currentDigests + historyDigests).toSet()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap.asImageBitmap()
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(192)
        val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(192)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
