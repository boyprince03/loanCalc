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
import android.widget.Button
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
    private var downloadedApkUri: Uri? = null // 用於儲存下載好的 URI

    private val onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        // ... onReceive 邏輯保持不變 ...
        override fun onReceive(recvContext: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && id != -1L) {
                try {
                    context.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    Log.w("UpdateChecker", "Receiver was not registered or already unregistered.")
                }
                dismissProgress()
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex >= 0) {
                        when (cursor.getInt(statusIndex)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                if (uriIndex >= 0) {
                                    val downloadedFileUriStr = cursor.getString(uriIndex)
                                    if (downloadedFileUriStr != null) {
                                        downloadedApkUri = Uri.parse(downloadedFileUriStr)
                                        Log.d("UpdateChecker", "Download successful. URI: $downloadedApkUri")
                                        // 下載成功後，直接觸發安裝（包含權限檢查）
                                        installApkWithPermissionCheck()
                                    } else {
                                        showToast("下載成功，但無法取得檔案路徑。")
                                    }
                                }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                showToast("下載失敗，請檢查網路或儲存空間。")
                            }
                        }
                    }
                }
                cursor.close()
            }
        }
    }

    // ▼▼▼▼▼ 【核心修改 1】新增權限檢查與安裝啟動器 ▼▼▼▼▼
    fun installApkWithPermissionCheck() {
        if (downloadedApkUri == null) {
            Log.e("UpdateChecker", "APK URI is null, cannot install.")
            showToast("找不到安裝檔。")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 對於 Android 8.0 (Oreo) 及以上版本
            if (context.packageManager.canRequestPackageInstalls()) {
                // 如果已經有權限，直接安裝
                performInstall(downloadedApkUri!!)
            } else {
                // 如果沒有權限，跳轉到設定頁面讓使用者授權
                showPermissionDialog()
            }
        } else {
            // 對於舊版 Android，直接安裝
            performInstall(downloadedApkUri!!)
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(context)
            .setTitle("需要安裝權限")
            .setMessage("為了更新應用程式，請在接下來的畫面中，允許「安裝未知應用程式」的權限。")
            .setPositiveButton("前往設定") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                // 我們無法直接從這裡 startActivityForResult，
                // 這需要在 Activity 中處理。但使用者授權後回來，可以再次點擊更新。
                context.startActivity(intent)
                showToast("請授權後，重新點擊更新按鈕")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    // ▲▲▲▲▲ 【核心修改 1】結束 ▲▲▲▲▲

    // ▼▼▼▼▼ 【核心修改 2】將原來的 installApk 改名為 performInstall ▼▼▼▼▼
    private fun performInstall(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error starting install activity", e)
            showToast("無法啟動安裝程式，請手動至『下載』資料夾安裝。")
        }
    }
    // ▲▲▲▲▲ 【核心修改 2】結束 ▲▲▲▲▲


    // ... 其他所有函式 (checkForUpdate, downloadAndInstall, showProgress 等) 保持原樣 ...
    suspend fun checkForUpdate(isManual: Boolean) {
        if (isManual) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "正在檢查更新...", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            val latestRelease = getLatestReleaseInfo()
            if (latestRelease == null) {
                if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "檢查更新失敗，請稍後再試", Toast.LENGTH_LONG).show()
                    }
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
                if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "您目前已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error during update check", e)
            if (isManual) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "檢查更新時發生錯誤: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun checkForUpdate() = checkForUpdate(false)
    suspend fun checkManually() = checkForUpdate(true)


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

    private fun downloadAndInstall(release: GitHubRelease, showProgressDialog: Boolean) {
        if (!isDownloadManagerEnabled()) {
            showDownloadManagerDisabledDialog()
            return
        }

        val fileName = "app-release-${release.tagName}.apk"
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("正在下載更新...")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName) // 使用公共目錄
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val destination = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val oldFile = File(destination, fileName)
        if (oldFile.exists()) {
            oldFile.delete()
        }

        downloadId = downloadManager.enqueue(request)

        if (showProgressDialog) {
            showProgress()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun showProgress() {
        if (progressDialog == null || progressDialog?.isShowing == false) {
            val builder = AlertDialog.Builder(context)
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.dialog_progress, null)
            val closeButton = view.findViewById<Button>(R.id.close_button)
            closeButton.setOnClickListener {
                dismissProgress()
                Toast.makeText(context, "請至手機的『下載』資料夾手動安裝更新", Toast.LENGTH_LONG).show()
            }
            builder.setView(view)
            builder.setCancelable(false)
            progressDialog = builder.create()
            progressDialog?.show()
        }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun isDownloadManagerEnabled(): Boolean {
        return try {
            val state = context.packageManager.getApplicationEnabledSetting("com.android.providers.downloads")
            !(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        } catch (e: Exception) {
            false
        }
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
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

}