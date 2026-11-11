package com.biometricpass.app

import android.app.Application
import androidx.room.withTransaction
import com.biometricpass.app.crypto.CryptoManager
import com.biometricpass.app.data.local.CredentialDatabase
import com.biometricpass.app.data.repository.CredentialRepository
import com.biometricpass.app.service.QuickActionNotificationHelper
import com.biometricpass.app.util.AppLog
import com.biometricpass.app.util.InstalledAppHelper
import com.biometricpass.app.util.PublicSuffixList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BiometricPassApp : Application() {

    lateinit var cryptoManager: CryptoManager
        private set

    lateinit var database: CredentialDatabase
        private set

    lateinit var repository: CredentialRepository
        private set

    /** Offline Public Suffix List for website matching (parsed once, ~10k rules from assets). */
    val publicSuffixList: PublicSuffixList by lazy {
        try {
            assets.open(PUBLIC_SUFFIX_ASSET).bufferedReader(Charsets.UTF_8).useLines { PublicSuffixList.parse(it) }
        } catch (e: Exception) {
            AppLog.w("BiometricPassApp", "Failed to load bundled PSL asset; falling back to default rule", e)
            PublicSuffixList.DEFAULT_RULE_ONLY
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        cryptoManager = CryptoManager(this)
        database = CredentialDatabase.getInstance(this)
        repository = CredentialRepository(
            credentialDao = database.credentialDao(),
            cryptoManager = cryptoManager,
            runInTransaction = { block -> database.withTransaction { block() } },
            publicSuffixList = { publicSuffixList },
            appSignatureProvider = { pkg -> InstalledAppHelper.getAppSignatures(this, pkg) }
        )
        // Parse the Public Suffix List in the background so the first Autofill request never waits on it.
        thread(name = "psl-preload", isDaemon = true) { publicSuffixList }
        // Lets CryptoManager finish a key rotation interrupted by a crash. Only invoked on unlock when a
        // pending rotation exists (rare), so briefly blocking for this database check is acceptable.
        cryptoManager.vaultKeyVerifier = { candidate ->
            runBlocking(Dispatchers.IO) { repository.isKeyCompatibleWithVault(candidate) }
        }
        // Respect the user's choice: never re-enable a Quick Access notification they dismissed.
        QuickActionNotificationHelper.showIfEnabled(this)
    }

    private companion object {
        const val PUBLIC_SUFFIX_ASSET = "public_suffix_list.dat"
    }
}
