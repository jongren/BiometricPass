package com.biometricpass.app.service

/**
 * Tracks the most recently active target application package and web domain
 * observed by [com.biometricpass.app.autofill.PassAutofillService].
 *
 * This allows the Pull-Down Notification Assistant (`下拉助手`) and Quick Settings Tile (`PassTileService`)
 * to know which foreground App the user is currently logging into, even when launched outside an Autofill session.
 */
object ActiveAppContextTracker {

    private val IGNORED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.samsung.android.biometrics.app.setting",
        "com.samsung.android.app.cocktailbarservice",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )

    @Volatile
    var lastPackageName: String = ""
        private set

    @Volatile
    var lastWebDomain: String = ""
        private set

    fun isIgnoredPackage(packageName: String, ownPackageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == ownPackageName) return true
        if (IGNORED_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        if (lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("launcher")) {
            return true
        }
        return false
    }

    fun updateActiveApp(packageName: String, webDomain: String = "", ownPackageName: String = "com.biometricpass.app") {
        if (isIgnoredPackage(packageName, ownPackageName)) return
        if (packageName != lastPackageName) {
            lastPackageName = packageName
            lastWebDomain = webDomain
        } else if (webDomain.isNotBlank()) {
            lastWebDomain = webDomain
        }
    }
}
