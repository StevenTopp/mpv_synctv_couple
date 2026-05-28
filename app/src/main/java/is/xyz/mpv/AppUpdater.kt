package `is`.xyz.mpv

import `is`.xyz.mpv.BuildConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.concurrent.thread

object AppUpdater {
    private const val TAG = "AppUpdater"
    
    private var downloadDialog: AlertDialog? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadProgressText: TextView? = null

    @Volatile
    private var isUpdateDialogShowing = false

    fun checkAppUpdates(context: Context, manual: Boolean = false) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var updateUrl = prefs.getString("update_server_url", "http://www.monsieursteve.top:9989/version.json") 
            ?: "http://www.monsieursteve.top:9989/version.json"
        
        // Self-healing: Auto-migrate legacy/deprecated update URLs to the new official domain
        if (updateUrl.contains("127.0.0.1") || updateUrl.contains("30008") || updateUrl.contains("81.70.164.80")) {
            val newUrl = "http://www.monsieursteve.top:9989/version.json"
            Log.d(TAG, "Legacy update URL detected ($updateUrl). Migrating to $newUrl")
            prefs.edit().putString("update_server_url", newUrl).apply()
            updateUrl = newUrl
        }
        
        Log.d(TAG, "Checking updates from: $updateUrl")
        if (manual) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "正在检查应用更新...", Toast.LENGTH_SHORT).show()
            }
        }
        
        val client = OkHttpClient()
        val request = Request.Builder().url(updateUrl).build()
        
        thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Update check failed: $response")
                        if (manual) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "检查更新失败: 服务器响应错误", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@thread
                    }
                    val body = response.body?.string() ?: return@thread
                    val json = JSONObject(body)
                    
                    val serverVersionName = json.getString("versionName")
                    val updateDescription = json.getString("updateDescription")
                    val forceUpdate = json.optBoolean("forceUpdate", false)
                    
                    // Intelligent ABI detection
                    val abis = Build.SUPPORTED_ABIS.toList()
                    val targetAbi = when {
                        "arm64-v8a" in abis -> "arm64-v8a"
                        "armeabi-v7a" in abis -> "armeabi-v7a"
                        else -> "arm64-v8a" // Default fallback
                    }
                    Log.d(TAG, "Device supported ABIs: $abis, matched target: $targetAbi")
                    
                    // Dynamic serverVersionCode resolution per ABI
                    val versionCodesObj = json.optJSONObject("versionCodes")
                    val serverVersionCode = if (versionCodesObj != null) {
                        versionCodesObj.optInt(targetAbi, json.getInt("versionCode"))
                    } else {
                        json.getInt("versionCode")
                    }
                    
                    val abisObj = json.optJSONObject("abis")
                    val sha256Obj = json.optJSONObject("sha256_abis")
                    
                    val downloadUrl = if (abisObj != null) {
                        abisObj.optString(targetAbi, json.getString("updateUrl"))
                    } else {
                        json.getString("updateUrl")
                    }
                    
                    val sha256 = if (sha256Obj != null) {
                        sha256Obj.optString(targetAbi, json.getString("sha256"))
                    } else {
                        json.getString("sha256")
                    }
                    
                    val packageInfo = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        } else {
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo?.longVersionCode?.toInt() ?: BuildConfig.VERSION_CODE
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo?.versionCode ?: BuildConfig.VERSION_CODE
                    }
                    Log.d(TAG, "Update check result: server=$serverVersionCode, current=$currentVersionCode, targetAbi=$targetAbi, url=$downloadUrl")
                    
                    val serverBase = if (serverVersionCode >= 8000) {
                        if (serverVersionCode >= 8200) serverVersionCode - 8200
                        else if (serverVersionCode >= 8100) serverVersionCode - 8100
                        else serverVersionCode - 8000
                    } else {
                        serverVersionCode
                    }
                    val currentBase = if (currentVersionCode >= 8000) {
                        if (currentVersionCode >= 8200) currentVersionCode - 8200
                        else if (currentVersionCode >= 8100) currentVersionCode - 8100
                        else currentVersionCode - 8000
                    } else {
                        currentVersionCode
                    }
                    Log.d(TAG, "Comparing base version codes: serverBase=$serverBase, currentBase=$currentBase")
                    
                    if (serverBase > currentBase) {
                        Handler(Looper.getMainLooper()).post {
                            showUpdatePromptDialog(context, serverVersionName, updateDescription, downloadUrl, sha256, forceUpdate)
                        }
                    } else {
                        if (manual) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "当前已是最新版本 (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check exception", e)
                if (manual) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "检查更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showUpdatePromptDialog(context: Context, versionName: String, description: String, downloadUrl: String, sha256: String, forceUpdate: Boolean) {
        if (isUpdateDialogShowing) {
            Log.d(TAG, "Update dialog is already showing, skipping.")
            return
        }
        isUpdateDialogShowing = true

        AlertDialog.Builder(context).apply {
            setTitle("发现新版本 ($versionName)")
            setMessage(description)
            setCancelable(!forceUpdate)
            setPositiveButton("立即更新") { dialog, _ ->
                isUpdateDialogShowing = false
                dialog.dismiss()
                startApkDownload(context, downloadUrl, sha256)
            }
            if (!forceUpdate) {
                setNegativeButton("稍后再说") { dialog, _ ->
                    isUpdateDialogShowing = false
                    dialog.dismiss()
                }
            }
            setOnDismissListener {
                isUpdateDialogShowing = false
            }
            create().show()
        }
    }

    private fun startApkDownload(context: Context, downloadUrl: String, expectedSha256: String) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        val apkFile = File(updatesDir, "update.apk")
        
        // Use a background thread to calculate cached file hash if it exists
        thread {
            if (apkFile.exists()) {
                try {
                    Log.d(TAG, "Found existing update.apk in cache, verifying SHA256...")
                    val cachedSha256 = calculateFileSha256(apkFile)
                    if (cachedSha256.equals(expectedSha256, ignoreCase = true)) {
                        Log.d(TAG, "Cached APK SHA256 matches. Skipping download and launching installation.")
                        Handler(Looper.getMainLooper()).post {
                            triggerInstall(context, apkFile)
                        }
                        return@thread
                    } else {
                        Log.d(TAG, "Cached APK SHA256 mismatch ($cachedSha256 vs $expectedSha256), deleting old cache.")
                        apkFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error calculating cached file hash", e)
                }
            }
            
            // Proceed to download on the main thread
            Handler(Looper.getMainLooper()).post {
                showDownloadProgressAndStart(context, downloadUrl, expectedSha256, apkFile)
            }
        }
    }

    private fun showDownloadProgressAndStart(context: Context, downloadUrl: String, expectedSha256: String, apkFile: File) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        downloadProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
                bottomMargin = 20
            }
        }
        
        downloadProgressText = TextView(context).apply {
            text = "正在准备下载..."
            textSize = 14f
        }
        
        layout.addView(downloadProgressText)
        layout.addView(downloadProgressBar)
        
        downloadDialog = AlertDialog.Builder(context)
            .setTitle("正在下载更新包")
            .setView(layout)
            .setCancelable(false)
            .create()
            
        downloadDialog?.show()
        
        val client = OkHttpClient()
        val request = Request.Builder().url(downloadUrl).build()
        
        thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Failed to download APK: $response")
                    val body = response.body ?: throw IOException("Empty response body")
                    val contentLength = body.contentLength()
                    
                    body.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            var totalRead = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                totalRead += read
                                output.write(buffer, 0, read)
                                val progress = if (contentLength > 0) (totalRead * 100 / contentLength).toInt() else -1
                                updateDownloadProgress(progress, totalRead, contentLength)
                            }
                        }
                    }
                }
                
                updateDownloadProgressStatus("正在校验文件完整性...")
                val actualSha256 = calculateFileSha256(apkFile)
                Log.d(TAG, "Expected SHA256: $expectedSha256")
                Log.d(TAG, "Actual SHA256: $actualSha256")
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("文件校验失败：SHA256 不匹配")
                }
                
                Handler(Looper.getMainLooper()).post {
                    downloadDialog?.dismiss()
                    triggerInstall(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                Handler(Looper.getMainLooper()).post {
                    downloadDialog?.dismiss()
                    Toast.makeText(context, "下载更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateDownloadProgress(progress: Int, current: Long, total: Long) {
        Handler(Looper.getMainLooper()).post {
            downloadProgressBar?.progress = progress
            val currentMb = String.format("%.2f", current / (1024.0 * 1024.0))
            if (total > 0) {
                val totalMb = String.format("%.2f", total / (1024.0 * 1024.0))
                downloadProgressText?.text = "下载进度: $currentMb MB / $totalMb MB ($progress%)"
            } else {
                downloadProgressText?.text = "已下载: $currentMb MB"
            }
        }
    }

    private fun updateDownloadProgressStatus(status: String) {
        Handler(Looper.getMainLooper()).post {
            downloadProgressText?.text = status
        }
    }

    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun triggerInstall(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(context, "请授予安装未知应用权限以继续更新", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
