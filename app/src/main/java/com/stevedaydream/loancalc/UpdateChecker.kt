package com.stevedaydream.loancalc

import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.stevedaydream.loancalc.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

// <<-- 【修正處 1】將 GITHUB_API_URL 改為 const val 並移到 Class 外部 -->>
// 請將 "YOUR_USERNAME" 和 "YOUR_REPO" 替換成您自己的 GitHub 使用者名稱和專案名稱
private const val GITHUB_API_URL = "https://api.github.com/repos/boyprince03/loanCalc/releases/latest"

data class GitHubRelease(
    val tagName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class UpdateChecker(private val context: Context) {

    private var progressDialog: Dialog? = null

    // 自動檢查更新 (給 MainActivity 啟動時呼叫)
    suspend fun checkForUpdate() {
        try {
            val latestRelease = getLatestReleaseInfo() ?: return
            val currentVersion = getCurrentAppVersion()
            val latestVersionName = latestRelease.tagName.removePrefix("v")

            if (currentVersion.isEmpty()) return

            if (isNewerVersion(latestVersionName, currentVersion)) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(latestRelease)
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error during automatic update check", e)
        }
    }

    // 手動檢查更新 (給 WebAppInterface 呼叫)
    suspend fun checkManually() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "正在檢查更新...", Toast.LENGTH_SHORT).show()
        }
        try {
            val latestRelease = getLatestReleaseInfo()
            if (latestRelease == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "檢查更新失敗，請稍後再試", Toast.LENGTH_LONG).show()
                }
                return
            }

            val currentVersion = getCurrentAppVersion()
            val latestVersionName = latestRelease.tagName.removePrefix("v")

            if (isNewerVersion(latestVersionName, currentVersion)) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(latestRelease)
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "您目前已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error during manual update check", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "檢查更新時發生錯誤: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun getLatestReleaseInfo(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL(GITHUB_API_URL).readText()
            val json = JSONObject(jsonStr)
            val tagName = json.getString("tag_name")
            val releaseNotes = json.getString("body")
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            if (assets.length() > 0) {
                downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
            }
            if (downloadUrl.isEmpty()) throw Exception("No APK asset found.")
            GitHubRelease(tagName, downloadUrl, releaseNotes)
        } catch(e: Exception) {
            Log.e("UpdateChecker", "Failed to get release info", e)
            null
        }
    }

    private fun getCurrentAppVersion(): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        val latestParts = latestVersion.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split('.').mapNotNull { it.toIntOrNull() }
        if (latestVersion.split('.').size != latestParts.size || currentVersion.split('.').size != currentParts.size) {
            Log.w("UpdateChecker", "版本號包含非數字字元，無法比對: '$latestVersion' vs '$currentVersion'")
            return false
        }
        val maxParts = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxParts) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        AlertDialog.Builder(context)
            .setTitle("發現新版本: ${release.tagName}")
            .setMessage("更新日誌:\n${release.releaseNotes}")
            .setNegativeButton("稍後再說", null)
            .setNeutralButton("背景更新") { _, _ ->
                downloadAndInstall(release, showProgressDialog = false)
            }
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(release, showProgressDialog = true)
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(release: GitHubRelease, showProgressDialog: Boolean) {
        val fileName = "app-release-${release.tagName}.apk"
        val destination = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (destination != null && destination.exists()) {
            File(destination, fileName).delete()
        }

        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("正在下載更新...")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        if (showProgressDialog) {
            showProgress()
        }

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    dismissProgress()
                    val downloadedFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                    if (downloadedFile.exists()) {
                        installApk(downloadedFile)
                    } else {
                        Log.e("UpdateChecker", "Downloaded file not found!")
                    }
                    context.unregisterReceiver(this)
                }
            }
        }

        // <<-- 【修正處 2】根據 Android 版本決定如何註冊廣播接收器 -->>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(file: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun showProgress() {
        if (progressDialog == null) {
            val builder = AlertDialog.Builder(context)
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_progress, null)
            builder.setView(view)
            builder.setCancelable(false)
            progressDialog = builder.create()
        }
        progressDialog?.show()
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
    }
}