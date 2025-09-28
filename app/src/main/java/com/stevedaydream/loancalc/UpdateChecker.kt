package com.stevedaydream.loancalc

import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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

private const val GITHUB_API_URL = "https://api.github.com/repos/boyprince03/loanCalc/releases/latest"

data class GitHubRelease(
    val tagName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class UpdateChecker(private val context: Context) {

    private var progressDialog: Dialog? = null
    private var downloadId: Long = -1L

    // ▼▼▼▼▼ 【修改處 1】將 BroadcastReceiver 提升為成員變數 ▼▼▼▼▼
    private val onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(recvContext: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                dismissProgress()
                // 收到廣播後，立即反註冊自己
                context.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(id)
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = downloadManager.query(query)

                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            if (uriIndex >= 0) {
                                val downloadedFileUri = Uri.parse(cursor.getString(uriIndex))
                                val file = File(downloadedFileUri.path!!)
                                if (file.exists()) {
                                    installApk(file)
                                } else {
                                    Log.e("UpdateChecker", "Downloaded file not found!")
                                    showToast("下載檔案遺失，請重試。")
                                }
                            }
                        } else {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0
                            Log.e("UpdateChecker", "Download failed with status: $status, reason: $reason")
                            showToast("下載失敗，請檢查網路連線或儲存空間。")
                        }
                    }
                }
                cursor.close()
            }
        }
    }
    // ▲▲▲▲▲ 【修改處 1】結束 ▲▲▲▲▲

    // ... checkForUpdate 和 checkManually 函式保持不變 ...
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
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Could not get package info", e)
            ""
        }
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
            .setPositiveButton("下載更新檔") { _, _ ->
                downloadAndInstall(release, showProgressDialog = true)
            }
            .setCancelable(false)
            .show()
    }

    // ▼▼▼▼▼ 【修改處 2】重構 downloadAndInstall 函式 ▼▼▼▼▼
    private fun downloadAndInstall(release: GitHubRelease, showProgressDialog: Boolean) {
        if (!isDownloadManagerEnabled()) {
            showDownloadManagerDisabledDialog()
            return
        }

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
        downloadId = downloadManager.enqueue(request)

        if (showProgressDialog) {
            showProgress()
        }

        // 註冊我們提升為成員變數的 Receiver
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
    // ▲▲▲▲▲ 【修改處 2】結束 ▲▲▲▲▲

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
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error starting install activity", e)
            showToast("無法啟動安裝程式，請手動至下載資料夾安裝。")
        }
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
        progressDialog = null // 確保下次能重建
    }

    // ▼▼▼▼▼ 【修改處 3】新增輔助函式 ▼▼▼▼▼
    private fun isDownloadManagerEnabled(): Boolean {
        val state = context.packageManager.getApplicationEnabledSetting("com.android.providers.downloads")
        return !(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
    }

    private fun showDownloadManagerDisabledDialog() {
        AlertDialog.Builder(context)
            .setTitle("下載服務已停用")
            .setMessage("請至「設定 > 應用程式」中啟用「下載管理員」服務後再試一次。")
            .setPositiveButton("前往設定") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:com.android.providers.downloads")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // 如果找不到，開啟所有應用程式列表
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    // ▲▲▲▲▲ 【修改處 3】結束 ▲▲▲▲▲
}