package com.streamcloud.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.streamcloud.app.data.plugins.PluginRuntime
import kotlinx.coroutines.launch

/**
 * Transparent host Activity that CloudStream plugins use when they open their
 * settings screen.  Plugins call openSettings(context) expecting a real
 * AppCompatActivity/FragmentActivity so they can:
 *   - Show AlertDialog / MaterialAlertDialog
 *   - Commit PreferenceFragmentCompat transactions (android.R.id.content)
 *   - Cast the Context to Activity
 *
 * We start this Activity, load the plugin, invoke its openSettings callback
 * in `onResume` (after the window token is valid), and finish when the user
 * dismisses whatever the plugin shows.
 */
class PluginSettingsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILE_PATH = "plugin_file_path"
        private const val EXTRA_PLUGIN_NAME = "plugin_name"

        fun start(context: Context, filePath: String, pluginName: String = "") {
            context.startActivity(
                Intent(context, PluginSettingsActivity::class.java)
                    .putExtra(EXTRA_FILE_PATH, filePath)
                    .putExtra(EXTRA_PLUGIN_NAME, pluginName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private var settingsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val pluginName = intent.getStringExtra(EXTRA_PLUGIN_NAME) ?: "Plugin"

        if (filePath == null) {
            finish()
            return
        }

        val root = FrameLayout(this)
        setContentView(root)

        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(ProgressBar(this@PluginSettingsActivity))
            addView(TextView(this@PluginSettingsActivity).apply {
                text = "Loading $pluginName settings…"
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            })
        }
        root.addView(loading)

        lifecycleScope.launch {
            try {
                PluginRuntime.load(this@PluginSettingsActivity, filePath)
            } catch (_: Throwable) {}

            if (!isFinishing) {
                root.removeView(loading)
                val err = PluginRuntime.openSettings(this@PluginSettingsActivity, filePath)
                if (err != null && !isFinishing) {
                    AlertDialog.Builder(this@PluginSettingsActivity)
                        .setTitle("Settings unavailable")
                        .setMessage(err.message ?: err::class.java.simpleName)
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }
    }
}
