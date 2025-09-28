package com.stevedaydream.loancalc // 請替換成您自己的 package name

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

// 用來存放從 GitHub API 獲取到的版本資訊
data class GitHubRelease(
    val tagName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class UpdateChecker(private val context: Context) {

    private val GITHUB_API_URL = "https://api.github.com/repos/YOUR_USERNAME/YOUR_REPO/releases/latest"

    // 檢查更新的主函式
    suspend fun checkForUpdate() {
        try {
            val latestRelease = getLatestReleaseInfo()
            val currentVersion = getCurrentAppVersion()

            // 移除 'v' 前綴並比對版本號
            val latestVersionName = latestRelease.tagName.removePrefix("v")

            Log.d("UpdateChecker", "Current version: $currentVersion, Latest version: $latestVersionName")

            if (isNewerVersion(latestVersionName, currentVersion)) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(latestRelease)
                }
            } else {
                Log.d("UpdateChecker", "App is up to date.")
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error checking for updates", e)
        }
    }

    // 從 GitHub API 獲取最新發布的資訊
    private suspend fun getLatestReleaseInfo(): GitHubRelease = withContext(Dispatchers.IO) {
        val jsonStr = URL(GITHUB_API_URL).readText()
        val json = JSONObject(jsonStr)

        val tagName = json.getString("tag_name")
        val releaseNotes = json.getString("body")
        val assets = json.getJSONArray("assets")
        var downloadUrl = ""
        if (assets.length() > 0) {
            // 通常第一個 asset 就是我們的 APK
            downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
        }

        if (downloadUrl.isEmpty()) {
            throw Exception("No APK asset found in the latest release.")
        }

        GitHubRelease(tagName, downloadUrl, releaseNotes)
    }

    // 獲取目前 App 的版本號
    // 獲取目前 App 的版本號
    private fun getCurrentAppVersion(): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }

    // 比較版本號大小
    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        // 一個簡單的版本比較邏輯，可以根據您的版本號格式進行調整
        // 例如：1.1.0 > 1.0.1
        val latestParts = latestVersion.split('.').map { it.toInt() }
        val currentParts = currentVersion.split('.').map { it.toInt() }
        val maxParts = maxOf(latestParts.size, currentParts.size)

        for (i in 0 until maxParts) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    // 顯示更新提示對話框
    private fun showUpdateDialog(release: GitHubRelease) {
        AlertDialog.Builder(context)
            .setTitle("發現新版本: ${release.tagName}")
            .setMessage("更新日誌:\n${release.releaseNotes}")
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(release)
            }
            .setNegativeButton("稍後再說", null)
            .show()
    }

    // 下載並安裝 APK
    private fun downloadAndInstall(release: GitHubRelease) {
        val fileName = "app-release-${release.tagName}.apk"
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("正在下載更新...")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true) // 允許使用行動數據下載
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // 監聽下載完成事件
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val fileUri = downloadManager.getUriForDownloadedFile(downloadId)
                    if (fileUri != null) {
                        installApk(fileUri)
                    } else {
                        Log.e("UpdateChecker", "Downloaded file URI is null.")
                    }
                    context.unregisterReceiver(this)
                }
            }
        }
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    // 呼叫系統安裝程式
    private fun installApk(fileUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW)
        val apkUri: Uri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.resolve(fileUri.lastPathSegment!!)
            )
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            apkUri = fileUri
        }

        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error starting APK install intent", e)
        }
    }
}