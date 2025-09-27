package com.stevedaydream.loancalc

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.android.billingclient.api.*
import com.stevedaydream.loancalc.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var billingClient: BillingClient
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val adsRemoved = AtomicBoolean(false)

    // 為了方便在不上架的情況下進行本地測試，我們將商品 ID 暫時改為 Google 的靜態測試 ID。
    private val PRODUCT_ID = "android.test.purchased"


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 將主題應用於啟動畫面之後的主 Activity
        // 確保在 setContentView 之前設定主題，避免閃爍
        setTheme(R.style.Theme_LoanCalculator)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 初始化 Google Mobile Ads SDK
        MobileAds.initialize(this) {}

        // 2. 設定 WebView
        binding.webview.apply {
            // 【修改處 START】: 將 WebView 背景設為透明
            // 這樣才能讓 HTML <body.> 的背景顏色顯示出來
            setBackgroundColor(0) // 0 即 Color.TRANSPARENT
            // 【修改處 END】

            settings.javaScriptEnabled = true
            addJavascriptInterface(WebAppInterface(), "Android")
            webViewClient = WebViewClient()

            loadUrl("file:///android_asset/index.html")
        }

        // 3. 初始化 Google Play Billing
        setupBillingClient()
    }

    private fun setupBillingClient() {
        val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    handlePurchase(purchase)
                }
            } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                Log.d("Billing", "使用者取消了購買流程。")
            } else {
                Log.e("Billing", "購買時發生錯誤: ${billingResult.debugMessage}")
            }
        }

        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d("Billing", "Billing Client 成功連線。")

                    // 在連線成功後，增加一步功能支援檢查，以確認環境是否完備。
                    val featureSupportedResult = billingClient.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
                    if (featureSupportedResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.e("Billing", "此裝置不支援查詢商品詳情的特性 (isFeatureSupported failed)。錯誤: ${featureSupportedResult.debugMessage}")
                    } else {
                        Log.d("Billing", "裝置支援查詢商品詳情特性，繼續執行。")
                    }

                    queryPurchases()
                } else {
                    Log.e("Billing", "Billing Client 連線失敗。錯誤碼: ${billingResult.responseCode}, 訊息: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w("Billing", "Billing Service 中斷連線。")
            }
        })
    }

    private fun queryPurchases() {
        coroutineScope.launch {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)

            val purchasesResult = withContext(Dispatchers.IO) {
                billingClient.queryPurchasesAsync(params.build())
            }

            var isAdsRemovalPurchased = false
            if (purchasesResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                purchasesResult.purchasesList.forEach { purchase ->
                    if (purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isAdsRemovalPurchased = true
                    }
                }
            }
            adsRemoved.set(isAdsRemovalPurchased)
            updateAdVisibility()
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d("Billing", "測試購買成功並已確認。")
                        adsRemoved.set(true)
                        updateAdVisibility()
                    }
                }
            } else {
                adsRemoved.set(true)
                updateAdVisibility()
            }
        }
    }

    private fun launchPurchaseFlow() {
        if (!billingClient.isReady) {
            Log.e("Billing", "Billing Client 尚未就緒，無法啟動購買。")
            return
        }
        Log.d("Billing", "Billing Client 已就緒，準備查詢商品詳情...")

        val productList = ImmutableList.of(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList)

        billingClient.queryProductDetailsAsync(params.build()) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                Log.d("Billing", "成功查詢到商品詳情，準備啟動購買視窗。")
                val productDetails = productDetailsList[0]
                val productDetailsParamsList = ImmutableList.of(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )
                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()

                billingClient.launchBillingFlow(this, billingFlowParams)
            } else {
                Log.e("Billing", "無法查詢商品詳情。")
                Log.e("Billing", "錯誤碼 (Response Code): ${billingResult.responseCode}")
                Log.e("Billing", "錯誤訊息 (Debug Message): ${billingResult.debugMessage}")
            }
        }
    }

    private fun updateAdVisibility() {
        runOnUiThread {
            if (adsRemoved.get()) {
                binding.adView.visibility = View.GONE
                Log.d("Ads", "廣告已移除，隱藏廣告視圖。")
            } else {
                binding.adView.visibility = View.VISIBLE
                val adRequest = AdRequest.Builder().build()
                binding.adView.loadAd(adRequest)
                Log.d("Ads", "未購買移除廣告，顯示廣告。")
            }
            binding.webview.evaluateJavascript("javascript:updateUiForAds(${adsRemoved.get()});", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun removeAds() {
            runOnUiThread {
                launchPurchaseFlow()
            }
        }

        @JavascriptInterface
        fun isAdsRemoved(): Boolean {
            return adsRemoved.get()
        }
    }
}