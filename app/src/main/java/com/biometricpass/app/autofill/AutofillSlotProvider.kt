package com.biometricpass.app.autofill

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local-only slot gate provider for Android 10 (API 29) Autofill `FillUi` datasets.
 *
 * Why this exists:
 * - On Android 11+ (API 30+), `AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET = true`
 *   tells `system_server` (`Session.setAuthenticationResultLocked`) not to replace the authenticated
 *   `Dataset` in `FillResponse.getDatasets()` with the returned unauthenticated `Dataset`.
 * - On Android 10 (API 29), `EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET` does not exist yet, so
 *   `Session.setAuthenticationResultLocked` unconditionally executes:
 *   `response.getDatasets().set(datasetIndex, resultDataset)`.
 *   If `resultDataset` remained visible in `FillUi`, tapping "✨ 產生新密碼" a second time would simply
 *   paste the previously generated password (`resultDataset.getAuthentication() == null`) instead of
 *   launching `GenerateAndSaveActivity`.
 *
 * How this works on Android 10:
 * - `PassAutofillService` pre-populates `MAX_SLOTS` authenticated datasets per category (`"cred"`, `"pick"`, `"gen"`),
 *   each containing `setImageViewUri(R.id.autofill_item_slot_gate, slotUri)`.
 * - During `FillUi.<init>`, `RemoteViews.applyWithTheme` invokes `ImageView.setImageURI(slotUri)` ->
 *   `ImageView.getDrawableFromUri(slotUri)` -> `ContentResolver.openTypedAssetFileDescriptor(slotUri, ...)`.
 * - For the single active slot (`slotIndex == activeSlot`), [openTypedAssetFile] throws [FileNotFoundException]
 *   (a subclass of `IOException`), which `ImageView.getDrawableFromUri` catches and ignores, allowing
 *   `RemoteViews.applyWithTheme` to succeed so the active slot is displayed in `FillUi`.
 * - For all inactive future slots (`slotIndex != activeSlot`), [openTypedAssetFile] throws [IllegalStateException]
 *   (a `RuntimeException`), which propagates out of `ImageView.getDrawableFromUri` into `RemoteViews.applyWithTheme`
 *   and is caught by `FillUi.<init>`'s `catch (RuntimeException e)` block, skipping those datasets.
 * - When `GenerateAndSaveActivity` or `AuthAndPickActivity` finishes filling a slot, it returns a consumed
 *   `Dataset` with `RemoteViews(packageName, 0)` (permanently skipped by `FillUi.<init>`) and calls
 *   [advanceSlot], promoting `slotIndex + 1` to be the active authenticated dataset for the next popup.
 */
class AutofillSlotProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? {
        enforceCallerAndCheckSlot(uri)
        return null
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        enforceCallerAndCheckSlot(uri)
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        enforceCallerAndCheckSlot(uri)
        return null
    }

    private fun enforceCallerAndCheckSlot(uri: Uri) {
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.myUid()) {
            throw SecurityException("Unauthorized caller UID: $callingUid")
        }

        // Expected URI path: /slot/<sessionToken>/<category>/<slotIndex>
        val segments = uri.pathSegments
        if (segments.size < 4 || segments[0] != "slot") {
            throw FileNotFoundException("Invalid slot URI: $uri")
        }

        val sessionToken = segments[1].toIntOrNull()
            ?: throw FileNotFoundException("Invalid sessionToken in URI: $uri")
        val category = segments[2]
        val slotIndex = segments[3].toIntOrNull()
            ?: throw FileNotFoundException("Invalid slotIndex in URI: $uri")

        val activeIndex = getActiveSlot(sessionToken, category)
        if (slotIndex == activeIndex) {
            // Active slot: throw FileNotFoundException (IOException) so ImageView.getDrawableFromUri
            // catches it cleanly and FillUi adds this Dataset to the dropdown popup!
            throw FileNotFoundException("Slot $slotIndex is active for $sessionToken:$category")
        } else {
            // Inactive slot: throw IllegalStateException (RuntimeException) so RemoteViews.applyWithTheme
            // throws ActionException and FillUi.<init> skips adding this Dataset!
            throw IllegalStateException("Slot $slotIndex is inactive (active=$activeIndex) for $sessionToken:$category")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val CATEGORY_CREDENTIAL = "cred"
        const val CATEGORY_PICKER = "pick"
        const val CATEGORY_GENERATOR = "gen"

        /** Number of pre-allocated authenticated dataset slots per category on Android 10. */
        const val MAX_SLOTS_API29 = 8

        /** Number of most recent Autofill sessions whose slot state is retained. */
        private const val MAX_TRACKED_SESSIONS = 20

        private val sessionTokenCounter = AtomicInteger(1)
        private val activeSlots = ConcurrentHashMap<String, AtomicInteger>()

        fun newSessionToken(): Int {
            val token = sessionTokenCounter.incrementAndGet()
            // Evict only the oldest sessions, never ones that may still be shown in a FillUi popup.
            val oldestKeptToken = token - MAX_TRACKED_SESSIONS
            activeSlots.keys.removeIf { key ->
                (key.substringBefore(':').toIntOrNull() ?: 0) <= oldestKeptToken
            }
            activeSlots["$token:$CATEGORY_CREDENTIAL"] = AtomicInteger(0)
            activeSlots["$token:$CATEGORY_PICKER"] = AtomicInteger(0)
            activeSlots["$token:$CATEGORY_GENERATOR"] = AtomicInteger(0)
            return token
        }

        fun getActiveSlot(sessionToken: Int, category: String): Int {
            if (sessionToken <= 0 || category.isBlank()) return 0
            return activeSlots["$sessionToken:$category"]?.get() ?: 0
        }

        fun advanceSlot(sessionToken: Int, category: String) {
            if (sessionToken <= 0 || category.isBlank()) return
            val counter = activeSlots.getOrPut("$sessionToken:$category") { AtomicInteger(0) }
            counter.updateAndGet { current ->
                if (current + 1 < MAX_SLOTS_API29) current + 1 else current
            }
        }

        fun buildSlotUri(
            packageName: String,
            sessionToken: Int,
            category: String,
            slotIndex: Int
        ): Uri {
            return Uri.parse("content://$packageName.autofill_slot/slot/$sessionToken/$category/$slotIndex")
        }
    }
}
