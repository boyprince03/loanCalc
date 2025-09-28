package com.stevedaydream.loancalc

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.*
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.common.collect.ImmutableList
import com.stevedaydream.loancalc.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var billingClient: BillingClient
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val adsRemoved = AtomicBoolean(false)
    private val PRODUCT_ID = "android.test.purchased"
    private val updateChecker by lazy { UpdateChecker(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        setTheme(R.style.Theme_LoanCalculator)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}

        binding.webview.apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            addJavascriptInterface(WebAppInterface(), "Android")
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/index.html")
        }

        triggerAutoUpdateCheck()
        setupBillingClient()
    }

    private fun triggerAutoUpdateCheck() {
        lifecycleScope.launch {
            updateChecker.checkForUpdate()
        }
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
                    queryPurchases()
                } else {
                    Log.e("Billing", "Billing Client 連線失敗。")
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
                        Log.d("Billing", "購買成功並已確認。")
                        adsRemoved.set(true)
                        updateAdVisibility()
                        // 【新增】顯示購買成功通知
                        Toast.makeText(this, "廣告移除成功！", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // 如果是已確認的購買 (例如：剛重新安裝 App)，直接更新 UI
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

        val productList = ImmutableList.of(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList)

        billingClient.queryProductDetailsAsync(params.build()) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
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
            }
        }
    }

    private fun updateAdVisibility() {
        runOnUiThread {
            if (adsRemoved.get()) {
                binding.adView.visibility = View.GONE
            } else {
                binding.adView.visibility = View.VISIBLE
                val adRequest = AdRequest.Builder().build()
                binding.adView.loadAd(adRequest)
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

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "N/A"
            } catch (e: Exception) {
                "N/A"
            }
        }

        @JavascriptInterface
        fun manualUpdateCheck() {
            runOnUiThread {
                lifecycleScope.launch {
                    updateChecker.checkManually()
                }
            }
        }

        // 【新增】恢復購買的接口
        @JavascriptInterface
        fun restorePurchase() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "正在嘗試恢復購買...", Toast.LENGTH_SHORT).show()
                queryPurchases()
            }
        }
    }
}