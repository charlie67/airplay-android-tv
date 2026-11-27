package com.airplayserver.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** D-pad friendly settings list (the equivalent of SettingsWindow on Windows). */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        list = findViewById(R.id.settings_list)
    }

    override fun onResume() {
        super.onResume()
        buildRows()
    }

    private fun buildRows() {
        val focusedId = currentFocus?.id ?: View.NO_ID
        list.removeAllViews()

        addRow(R.id.row_name, getString(R.string.setting_name), prefs.receiverName) { editName() }
        addRow(R.id.row_resolution, getString(R.string.setting_resolution),
            resolutionLabel(), getString(R.string.setting_resolution_summary)) { chooseResolution() }
        addRow(R.id.row_background, getString(R.string.setting_background),
            onOff(prefs.backgroundMode), getString(R.string.setting_background_summary)) {
            prefs.backgroundMode = !prefs.backgroundMode
            if (!prefs.backgroundMode) prefs.startOnBoot = false
            buildRows()
        }
        addRow(R.id.row_boot, getString(R.string.setting_boot),
            onOff(prefs.startOnBoot), getString(R.string.setting_boot_summary)) {
            prefs.startOnBoot = !prefs.startOnBoot
            if (prefs.startOnBoot) prefs.backgroundMode = true
            buildRows()
        }
        addRow(R.id.row_overlay, getString(R.string.setting_auto_open),
            getString(if (canOpenFromBackground()) R.string.allowed else R.string.not_allowed),
            getString(R.string.setting_auto_open_summary)) { requestOverlayPermission() }
        addRow(R.id.row_licenses, getString(R.string.setting_licenses),
            getString(R.string.licenses_value)) { showLicense() }
        addRow(R.id.row_about, getString(R.string.setting_about),
            getString(R.string.version_label, versionName())) { showAbout() }

        (if (focusedId != View.NO_ID) list.findViewById<View>(focusedId) else null)
            ?.requestFocus() ?: list.getChildAt(0)?.requestFocus()
    }

    private fun addRow(id: Int, title: String, value: String, summary: String? = null, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.settings_row, list, false)
        row.id = id
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_value).text = value
        row.findViewById<TextView>(R.id.row_summary).apply {
            text = summary ?: ""
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        row.setOnClickListener { onClick() }
        list.addView(row)
    }

    private fun onOff(value: Boolean) = getString(if (value) R.string.on else R.string.off)

    private fun resolutionLabel(): String {
        val res = prefs.resolution
        if (res != Prefs.Resolution.MATCH_TV) return res.label
        val (w, h) = prefs.displaySize()
        return getString(R.string.match_tv_label, w, h)
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(prefs.receiverName)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_AirPlayServer_Dialog)
            .setTitle(R.string.setting_name)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> saveName(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                saveName(input.text.toString())
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
    }

    private fun saveName(value: String) {
        if (value.isBlank() || Prefs.sanitizeName(value) == prefs.receiverName) return
        prefs.receiverName = value
        AirPlayService.restart(this)
        buildRows()
    }

    private fun chooseResolution() {
        val options = Prefs.Resolution.entries
        val labels = options.map {
            if (it == Prefs.Resolution.MATCH_TV) getString(R.string.match_tv) else it.label
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_AirPlayServer_Dialog)
            .setTitle(R.string.setting_resolution)
            .setSingleChoiceItems(labels, options.indexOf(prefs.resolution)) { dialog, which ->
                if (options[which] != prefs.resolution) {
                    prefs.resolution = options[which]
                    AirPlayService.restart(this)
                }
                dialog.dismiss()
                buildRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun canOpenFromBackground(): Boolean =
        Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (canOpenFromBackground()) {
            message(R.string.setting_auto_open, getString(R.string.auto_open_already))
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            // Many Android TV builds hide this screen; it can be granted over adb.
            message(R.string.setting_auto_open, getString(R.string.auto_open_adb, packageName))
        }
    }

    /** Shows the upstream AirPlayServer MIT license bundled in assets/licenses. */
    private fun showLicense() {
        val text = runCatching {
            assets.open(UPSTREAM_LICENSE).bufferedReader().use { it.readText() }
        }.getOrElse { getString(R.string.license_missing) }
        message(R.string.license_title, text.trim())
    }

    private fun showAbout() {
        message(R.string.setting_about, getString(R.string.about_text, versionName()))
    }

    private fun message(title: Int, text: String) {
        AlertDialog.Builder(this, R.style.Theme_AirPlayServer_Dialog)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    companion object {
        private const val UPSTREAM_LICENSE = "licenses/AirPlayServer-MIT.txt"
    }
}
