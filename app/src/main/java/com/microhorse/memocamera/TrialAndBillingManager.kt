package com.microhorse.memocamera

import android.app.Activity
import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class LicenseUiState(
    val isProUnlocked: Boolean = false,
    val isTrialActive: Boolean = true,
    val isClockTampered: Boolean = false,
    val remainingTrialHours: Int = 96,
    val remainingTrialDays: Int = 4,
    val formattedPrice: String = "¥3,800",
    val isBillingReady: Boolean = false
) {
    val canUseApp: Boolean
        get() = isProUnlocked || (isTrialActive && !isClockTampered)
}

class TrialAndBillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        const val PREFS_NAME = "memo_camera_license_prefs"
        private const val KEY_FIRST_LAUNCH_MILLIS = "first_launch_millis"
        private const val KEY_LAST_SEEN_MAX_MILLIS = "last_seen_max_millis"
        private const val KEY_PRO_UNLOCKED = "pro_unlocked"

        // Google Play Console の「アプリ内アイテム」に設定する商品ID（買い切り ¥3,800）
        const val PRODUCT_ID_PRO_LIFETIME = "memo_camera_pro_lifetime"

        // 4日間（96時間）全機能無料体験
        const val TRIAL_DAYS = 4
        const val TRIAL_DURATION_MILLIS = 4L * 24L * 60L * 60L * 1000L

        // オーナー専用の隠し永久解放コード（SHA-256ハッシュ照合）
        private const val SECRET_UNLOCK_SALT = "mh_pro_unlock_v1:"
        private const val SECRET_UNLOCK_SHA256 = "c021b49878b8a8db66d9481b0bdb3daba223dd0a0f614002e99d526bb35d455e"

        // 1時間以上の時計巻き戻しを検知する閾値
        private const val CLOCK_ROLLBACK_TOLERANCE_MILLIS = 60L * 60L * 1000L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow( evaluateLocalLicenseState() )
    val uiState: StateFlow<LicenseUiState> = _uiState.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        refreshTrialState()
        startBillingConnection()
    }

    /**
     * 3重ガード（①AutoBackup連動SharedPreferences ②MediaStore内の最古MEMO_*.jpg ③時計巻き戻し検知）
     * によって現在のトライアル＆Pro解放状態を判定する
     */
    fun refreshTrialState() {
        _uiState.value = evaluateLocalLicenseState(
            currentFormattedPrice = _uiState.value.formattedPrice,
            currentBillingReady = _uiState.value.isBillingReady
        )
    }

    private fun evaluateLocalLicenseState(
        currentFormattedPrice: String = "¥3,800",
        currentBillingReady: Boolean = false
    ): LicenseUiState {
        val now = System.currentTimeMillis()
        val isPro = prefs.getBoolean(KEY_PRO_UNLOCKED, false)

        // 1. 初回利用開始時刻の取得（SharedPreferences と MediaStore内の最古のMEMO_*.jpg のうち最も古い時刻を採用）
        val savedFirstLaunch = prefs.getLong(KEY_FIRST_LAUNCH_MILLIS, 0L)
        val earliestPhotoTime = findEarliestMemoPhotoTimestamp()

        val effectiveStartMillis = when {
            savedFirstLaunch > 0L && earliestPhotoTime > 0L -> min(savedFirstLaunch, earliestPhotoTime)
            savedFirstLaunch > 0L -> savedFirstLaunch
            earliestPhotoTime > 0L -> earliestPhotoTime
            else -> now
        }

        // 2. 時計巻き戻し検知用の過去最大時刻の更新・検証
        val lastSeenMax = prefs.getLong(KEY_LAST_SEEN_MAX_MILLIS, 0L)
        val isClockTampered = lastSeenMax > 0L && (now < lastSeenMax - CLOCK_ROLLBACK_TOLERANCE_MILLIS)
        val nextMaxMillis = max(lastSeenMax, now)

        prefs.edit()
            .putLong(KEY_FIRST_LAUNCH_MILLIS, effectiveStartMillis)
            .putLong(KEY_LAST_SEEN_MAX_MILLIS, nextMaxMillis)
            .apply()

        val elapsed = max(0L, now - effectiveStartMillis)
        val remainingMillis = max(0L, TRIAL_DURATION_MILLIS - elapsed)
        val isTrialActive = !isClockTampered && (elapsed < TRIAL_DURATION_MILLIS)

        val remainingHours = ceil(remainingMillis / (1000.0 * 60.0 * 60.0)).toInt()
        val remainingDays = ceil(remainingMillis / (1000.0 * 60.0 * 60.0 * 24.0)).toInt()

        return LicenseUiState(
            isProUnlocked = isPro,
            isTrialActive = isTrialActive,
            isClockTampered = isClockTampered,
            remainingTrialHours = remainingHours,
            remainingTrialDays = remainingDays,
            formattedPrice = currentFormattedPrice,
            isBillingReady = currentBillingReady
        )
    }

    /**
     * アンインストール＆再インストール時のリセットハック対策：
     * カメラロール（MediaStore）に残っている最古の「MEMO_yyyyMMdd_HHmmss_SSS.jpg / .mp4」の撮影日時を取得する
     */
    private fun findEarliestMemoPhotoTimestamp(): Long {
        var earliest = 0L
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("MEMO_%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        for (contentUri in uris) {
            try {
                appContext.contentResolver.query(
                    contentUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(nameCol) ?: ""
                        val dateTaken = cursor.getLong(takenCol)
                        val dateAddedSec = cursor.getLong(addedCol)

                        val parsedFromName = parseTimestampFromFileName(name)
                        val candidates = listOf(
                            parsedFromName,
                            dateTaken,
                            if (dateAddedSec > 0L) dateAddedSec * 1000L else 0L
                        ).filter { it > 1577836800000L } // 2020年以降の妥当なエポックミリ秒のみ対象

                        if (candidates.isNotEmpty()) {
                            val minCandidate = candidates.minOrNull() ?: 0L
                            if (minCandidate > 0L && (earliest == 0L || minCandidate < earliest)) {
                                earliest = minCandidate
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return earliest
    }

    private fun parseTimestampFromFileName(fileName: String): Long {
        // MEMO_yyyyMMdd_HHmmss_SSS.jpg
        return try {
            val raw = fileName.removePrefix("MEMO_").substringBefore(".")
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            sdf.parse(raw)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * オーナー専用ライセンスコードの検証（SHA-256ハッシュ照合）と永久解放
     */
    fun trySecretUnlock(input: String): Boolean {
        val normalized = input.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((SECRET_UNLOCK_SALT + normalized).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (digest == SECRET_UNLOCK_SHA256) {
            markProUnlocked()
            return true
        }
        return false
    }

    private fun markProUnlocked() {
        prefs.edit().putBoolean(KEY_PRO_UNLOCKED, true).commit()
        _uiState.value = _uiState.value.copy(isProUnlocked = true)
    }

    private fun startBillingConnection() {
        if (billingClient.isReady) {
            queryProductDetails()
            queryExistingPurchases(showToastOnResult = false)
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryExistingPurchases(showToastOnResult = false)
                }
            }

            override fun onBillingServiceDisconnected() {
                _uiState.value = _uiState.value.copy(isBillingReady = false)
            }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PRO_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, detailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                val detail = detailsList.first()
                productDetails = detail
                val price = detail.oneTimePurchaseOfferDetails?.formattedPrice ?: "¥3,800"
                _uiState.value = _uiState.value.copy(
                    formattedPrice = price,
                    isBillingReady = true
                )
            }
        }
    }

    /**
     * Google Play の購入フローを開始する
     */
    fun launchPurchaseFlow(activity: Activity) {
        val detail = productDetails
        if (!billingClient.isReady || detail == null) {
            startBillingConnection()
            Toast.makeText(
                appContext,
                "Google Playストアの課金情報を確認中です。ストア公開前またはオフライン時は「ライセンスコードを入力」から解除できます。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(detail)
                .build()
        )
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    /**
     * 機種変更・再インストール時の「購入を復元」処理
     */
    fun restorePurchases() {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryExistingPurchases(showToastOnResult = true)
                    } else {
                        Toast.makeText(
                            appContext,
                            "Google Playストアに接続できませんでした",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onBillingServiceDisconnected() {}
            })
        } else {
            queryExistingPurchases(showToastOnResult = true)
        }
    }

    private fun queryExistingPurchases(showToastOnResult: Boolean) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var foundValidPurchase = false
                for (purchase in purchasesList) {
                    if (purchase.products.contains(PRODUCT_ID_PRO_LIFETIME) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        foundValidPurchase = true
                        handleValidPurchase(purchase)
                    }
                }
                if (showToastOnResult) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (foundValidPurchase || _uiState.value.isProUnlocked) {
                            Toast.makeText(
                                appContext,
                                "✨ Pro永久ライセンスを復元しました！",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                appContext,
                                "購入履歴が見つかりませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    handleValidPurchase(purchase)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(
                            appContext,
                            "✨ ご購入ありがとうございます！Pro永久ライセンスが解放されました",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun handleValidPurchase(purchase: Purchase) {
        markProUnlocked()
        if (!purchase.isAcknowledged) {
            val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(acknowledgeParams) { }
        }
    }

    fun release() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
