package com.stevedaydream.loancalc

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        // 【*** 程式碼修正處 START ***】
        // 我們不再使用預設的 WebViewClient，而是建立一個客製化的版本
        webView.webViewClient = object : WebViewClient() {
            // 覆寫 shouldOverrideUrlLoading 方法，這是處理連結點擊的關鍵
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // 取得使用者點擊的連結網址
                val url = request?.url.toString()

                // 判斷網址是否為外部網站連結
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    // 如果是，建立一個 ACTION_VIEW 的 Intent 來呼叫外部瀏覽器
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    // 返回 true，表示我們已經處理了這個點擊事件，WebView 不需要再做任何事
                    true
                } else {
                    // 如果不是，返回 false，讓 WebView 自己處理（例如載入本地資源）
                    false
                }
            }
        }
        // 【*** 程式碼修正處 END ***】

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }
}

