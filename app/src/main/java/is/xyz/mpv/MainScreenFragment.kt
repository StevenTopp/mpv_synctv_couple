package `is`.xyz.mpv

import `is`.xyz.filepicker.DocumentPickerFragment
import `is`.xyz.mpv.preferences.PreferenceActivity
import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.concurrent.thread
import android.widget.Toast
import android.view.Gravity

class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding

    private lateinit var documentTreeOpener: ActivityResultLauncher<Uri?>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var playerLauncher: ActivityResultLauncher<Intent>

    private var firstRun = false
    private var returningFromPlayer = false

    private var prev = ""
    private var prevData: String? = null
    private var lastPath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstRun = savedInstanceState == null

        documentTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { root ->
                requireContext().contentResolver.takePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveChoice("doc", root.toString())

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", root.toString())
                filePickerLauncher.launch(i)
            }
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            it.data?.getStringExtra("last_path")?.let { path ->
                lastPath = path
            }
            it.data?.getStringExtra("path")?.let { path ->
                playFile(path)
            }
        }
        playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // we don't care about the result but remember that we've been here
            returningFromPlayer = true
            Log.v(TAG, "returned from player ($it)")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMainScreenBinding.bind(view)

        Utils.handleInsetsAsPadding(binding.root)

        binding.docBtn.setOnClickListener {
            try {
                documentTreeOpener.launch(null)
            } catch (e: ActivityNotFoundException) {
                // Android TV doesn't come with a document picker and certain versions just throw
                // instead of handling this gracefully
                binding.docBtn.isEnabled = false
            }
        }
        binding.urlBtn.setOnClickListener {
            saveChoice("url")
            val helper = Utils.OpenUrlDialog(requireContext())
            with (helper) {
                builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                    playFile(helper.text)
                }
                builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                create().show()
            }
        }
        binding.filepickerBtn.setOnClickListener {
            saveChoice("file")
            val i = Intent(context, FilePickerActivity::class.java)
            i.putExtra("skip", FilePickerActivity.FILE_PICKER)
            if (lastPath != "")
                i.putExtra("default_path", lastPath)
            filePickerLauncher.launch(i)
        }
        binding.settingsBtn.setOnClickListener {
            saveChoice("") // will reset
            startActivity(Intent(context, PreferenceActivity::class.java))
        }
        binding.syncBtn.setOnClickListener {
            val i = Intent(context, MPVActivity::class.java)
            i.putExtra("filepath", "synctv://")
            playerLauncher.launch(i)
        }

        if (BuildConfig.DEBUG) {
            binding.settingsBtn.setOnLongClickListener { showDebugMenu(); true }
        }

        onConfigurationChanged(view.resources.configuration)

        view.postDelayed({
            checkAppUpdates()
        }, 1000)
        applyNightMode()
    }

    private fun showDebugMenu() {
        assert(BuildConfig.DEBUG)
        val context = requireContext()
        with (AlertDialog.Builder(context)) {
            setItems(DEBUG_ACTIVITIES) { dialog, idx ->
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_MAIN)
                intent.setClassName(context, "${context.packageName}.${DEBUG_ACTIVITIES[idx]}")
                startActivity(intent)
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // phone screens are too small to show the action buttons alongside the logo
        if (!Utils.isXLargeTablet(requireContext())) {
            binding.logo.isVisible = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstRun) {
            restoreChoice()
        } else if (returningFromPlayer) {
            restoreChoice(prev, prevData)
        }
        firstRun = false
        returningFromPlayer = false

        context?.let { ctx ->
            val updatesDir = File(ctx.cacheDir, "updates")
            val apkFile = File(updatesDir, "update.apk")
            if (apkFile.exists()) {
                val pm = ctx.packageManager
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                if (info != null) {
                    val packageInfo = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        } else {
                            pm.getPackageInfo(ctx.packageName, 0)
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
                    
                    val serverVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        info.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        info.versionCode
                    }
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
                    
                    if (serverBase > currentBase) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || pm.canRequestPackageInstalls()) {
                            AppUpdater.triggerInstall(ctx, apkFile)
                        }
                    } else {
                        android.util.Log.d("mpv", "Cached APK is obsolete (cached:${serverVersionCode} <= current:${currentVersionCode}). Deleting.")
                        apkFile.delete()
                    }
                }
            }
        }
        applyNightMode()
    }

    private fun saveChoice(type: String, data: String? = null) {
        if (prev != type)
            lastPath = ""
        prev = type
        prevData = data

        if (!binding.switch1.isChecked)
            return
        binding.switch1.isChecked = false
        with (PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()) {
            putString("MainScreenFragment_remember", type)
            if (data == null)
                remove("MainScreenFragment_remember_data")
            else
                putString("MainScreenFragment_remember_data", data)
            commit()
        }
    }

    private fun restoreChoice() {
        with (PreferenceManager.getDefaultSharedPreferences(requireContext())) {
            restoreChoice(
                getString("MainScreenFragment_remember", "") ?: "",
                getString("MainScreenFragment_remember_data", "")
            )
        }
    }

    private fun restoreChoice(type: String, data: String?) {
        when (type) {
            "doc" -> {
                val uri = Uri.parse(data)
                // check that we can still access the folder
                if (!DocumentPickerFragment.isTreeUsable(requireContext(), uri))
                    return

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", uri.toString())
                if (lastPath != "")
                    i.putExtra("default_path", lastPath)
                filePickerLauncher.launch(i)
            }
            "url" -> binding.urlBtn.callOnClick()
            "file" -> binding.filepickerBtn.callOnClick()
        }
    }

    private fun playFile(filepath: String) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            i = Intent()
            i.putExtra("filepath", filepath)
        }
        i.setClass(requireContext(), MPVActivity::class.java)
        playerLauncher.launch(i)
    }

    private fun checkAppUpdates() {
        val context = context ?: return
        AppUpdater.checkAppUpdates(context, false)
    }

    private fun applyNightMode() {
        val context = context ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val nightMode = prefs.getBoolean("night_mode", false)
        
        val actionBar = (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar
        actionBar?.title = "一起看电影吖"
        val barColor = if (nightMode) 0xFF8F76AD.toInt() else 0xFF6FA4E3.toInt()
        actionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(barColor))

        val themeRes = if (nightMode) R.drawable.main_bg_night else R.drawable.main_bg_day
        val cardRes = if (nightMode) R.drawable.card_bg_night else R.drawable.card_bg_day
        
        binding.mainScrollView.setBackgroundResource(themeRes)
        binding.syncBtn.setBackgroundResource(cardRes)
        binding.docBtn.setBackgroundResource(cardRes)
        binding.filepickerBtn.setBackgroundResource(cardRes)
        binding.settingsBtn.setBackgroundResource(cardRes)
        
        // Text Colors
        val titleColor = if (nightMode) 0xFFF8FAFC.toInt() else 0xFF1E293B.toInt()
        val subtitleColor = if (nightMode) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
        
        // SyncTV special text colors
        val syncTitleColor = if (nightMode) 0xFFF8FAFC.toInt() else 0xFF015efb.toInt()
        val syncSubtitleColor = if (nightMode) 0xFF94A3B8.toInt() else 0xFF3b82f6.toInt()
        
        binding.synctvTitle.setTextColor(syncTitleColor)
        binding.synctvSubtitle.setTextColor(syncSubtitleColor)
        
        binding.docTitle.setTextColor(titleColor)
        binding.docSubtitle.setTextColor(subtitleColor)
        
        binding.filepickerTitle.setTextColor(titleColor)
        binding.filepickerSubtitle.setTextColor(subtitleColor)
        
        binding.settingsTitle.setTextColor(titleColor)
        binding.settingsSubtitle.setTextColor(subtitleColor)
        
        // Icon Tints
        val tintColor = if (nightMode) 0xFFF8FAFC.toInt() else 0xFF015efb.toInt()
        binding.docIcon.setColorFilter(tintColor)
        binding.filepickerIcon.setColorFilter(tintColor)
        binding.settingsIcon.setColorFilter(tintColor)
        
        if (nightMode) {
            binding.logo.setImageResource(R.mipmap.mpv_launcher_foreground)
            binding.logo.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            binding.logo.setImageResource(R.mipmap.mpv_launcher_icon)
            binding.logo.clearColorFilter()
        }
    }

    companion object {
        private const val TAG = "mpv"

        // list of debug or testing activities that can be launched
        private val DEBUG_ACTIVITIES = arrayOf(
            "IntentTestActivity",
            "CodecInfoActivity"
        )
    }
}
