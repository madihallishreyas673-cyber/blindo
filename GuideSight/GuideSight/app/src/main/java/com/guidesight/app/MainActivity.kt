package com.guidesight.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private var speaker: Speaker? = null
    private val ui = Handler(Looper.getMainLooper())

    private val perms: List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ui.post { afterPermissions() }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.1f      // dim screen to save battery
        window.attributes = lp

        prefs = Prefs(this)
        L.hi = prefs.hindi
        buildUi()

        if (granted(Manifest.permission.CAMERA) && askedBefore()) {
            startGuide()
        } else {
            explainThenAsk()
        }
    }

    private fun askedBefore() = getSharedPreferences("guidesight", MODE_PRIVATE).getBoolean("asked", false)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply { text = "GuideSight"; textSize = 32f })
        status = TextView(this).apply { text = "Starting..."; textSize = 18f }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "\nHelper setup (one time): type the emergency contact's phone number with country code, for example +919876543210, then tap Save."
            textSize = 16f
        })
        val phone = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "Emergency contact number"
            setText(prefs.contact)
        }
        root.addView(phone)
        root.addView(Button(this).apply {
            text = "Save emergency contact"
            setOnClickListener {
                prefs.contact = phone.text.toString()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(root)
    }

    private fun explainThenAsk() {
        speaker = Speaker(this, prefs)
        speaker?.speak(
            "Welcome to GuideSight. I need your permission. I use the camera to see obstacles, " +
                "the microphone to hear your voice commands, your location to track your walking and share it in an emergency, " +
                "and text messages and phone calls to reach your emergency contact. " +
                "Please allow all permissions on the next screens."
        ) { ui.post { launcher.launch(perms.toTypedArray()) } }
    }

    private fun afterPermissions() {
        getSharedPreferences("guidesight", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        speaker?.shutdown(); speaker = null
        if (granted(Manifest.permission.CAMERA)) {
            startGuide()
        } else {
            status.text = "Camera permission is required. Open phone Settings, Apps, GuideSight, Permissions."
            val s = Speaker(this, prefs)
            s.speak("The camera permission is needed for GuideSight to work. Please ask someone to allow it in the phone settings.")
        }
    }

    private fun startGuide() {
        status.text = "GuideSight is running. Keep this screen on and just speak."
        ContextCompat.startForegroundService(this, Intent(this, GuideService::class.java))
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }
}
