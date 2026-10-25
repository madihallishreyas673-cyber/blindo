package com.guidesight.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class GuideService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var vibrator: Vibrator
    private val handler = Handler(Looper.getMainLooper())
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private var detector: ObjectDetectorWrapper? = null

    @Volatile private var lastObjs: List<Obj> = emptyList()
    @Volatile private var lastBitmap: Bitmap? = null
    @Volatile private var lastLoc: Location? = null
    private var lastLocTime = 0L
    private val said = ConcurrentHashMap<String, Long>()
    private var lastFrame = 0L
    private var hazardStreak = 0
    private var destroyed = false
    private var recognizer: SpeechRecognizer? = null
    private val stepTimes = ArrayList<Long>()
    private var lastSpeedSaid = 0f
    private var lastSpeedTime = 0L
    private var sharing = false

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        L.hi = prefs.hindi
        startAsForeground()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        speaker = Speaker(this, prefs)
        speaker.speak(L.t("GuideSight is ready", "गाइडसाइट तैयार है"))

        exec.execute {
            try {
                detector = ObjectDetectorWrapper(this)
            } catch (e: Exception) {
                say(L.t("Object detection could not start", "वस्तु पहचान शुरू नहीं हो सकी"), true)
            }
        }
        startCamera()
        startLocation()
        startSteps()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { fused.removeLocationUpdates(locCb) } catch (_: Exception) {}
        try { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(stepListener) } catch (_: Exception) {}
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        exec.shutdown()
        detector?.close()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("gs", "GuideSight", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "gs")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GuideSight is running")
            .setContentText("Say a command at any time")
            .setOngoing(true)
            .build()
        var type = 0
        if (has(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (has(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, 1, n, type)
    }

    // ---------------------------------------------------------------- helpers

    private fun say(text: String, urgent: Boolean = false) {
        handler.post { if (!destroyed) speaker.speak(text, urgent) }
    }

    /** Returns true at most once per cooldown for a given key (anti-spam). */
    private fun ready(key: String, cooldownMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - (said[key] ?: 0L) < cooldownMs) return false
        said[key] = now
        return true
    }

    private fun buzz(urgent: Boolean) {
        if (!prefs.vibration) return
        try {
            val e = if (urgent) VibrationEffect.createWaveform(longArrayOf(0, 400, 120, 400, 120, 400), -1)
            else VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(e)
        } catch (_: Exception) {}
    }

    private fun phrase(o: Obj): String {
        val d = Math.round(o.dist * 2) / 2f
        val ds = if (d % 1f == 0f) d.toInt().toString() else d.toString()
        val n = Names.name(o.label)
        return if (L.hi) {
            val where = when (o.dir) { -1 -> "बाएं"; 1 -> "दाएं"; else -> "सामने" }
            "$where $n, $ds मीटर"
        } else {
            val where = when (o.dir) { -1 -> "on your left"; 1 -> "on your right"; else -> "ahead" }
            "$n $where, $ds ${if (d == 1f) "meter" else "meters"}"
        }
    }

    // ---------------------------------------------------------------- camera + detection

    private fun startCamera() {
        if (!has(Manifest.permission.CAMERA)) {
            say(L.t("The camera permission is off", "कैमरा अनुमति बंद है"), true); return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec, ImageAnalysis.Analyzer { analyze(it) })
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                say(L.t("The camera is not available", "कैमरा उपलब्ध नहीं है"), true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < 500) return          // ~2 frames per second saves battery
            lastFrame = now
            val det = detector ?: return

            val raw = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            val bmp = if (rot != 0) {
                val m = Matrix(); m.postRotate(rot.toFloat())
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            lastBitmap = bmp

            if (Vision.brightness(bmp) < 35) {
                if (ready("dark", 20000)) say(L.t("It is too dark, please turn on a light", "बहुत अंधेरा है, कृपया रोशनी जलाइए"))
                return
            }

            val objs = det.detect(bmp)
            lastObjs = objs
            hazardStreak = if (Vision.darkPatchAhead(bmp)) hazardStreak + 1 else 0

            val parts = ArrayList<String>()
            var urgent = false
            var hazard = false

            if (hazardStreak >= 3) {
                hazard = true
                if (ready("hazard", 4000)) {
                    urgent = true
                    parts.add(L.t("Danger! Possible hole or drain ahead. Stop.", "खतरा! आगे गड्ढा या नाली हो सकती है। रुकिए।"))
                }
            }

            val near = objs.filter { it.dist <= 6f }.sortedBy { it.dist }
            for (o in near.take(2)) {
                val vehicle = o.label in ObjectDetectorWrapper.VEHICLES
                val dangerous = o.dist < 1.5f || (vehicle && o.dist < 5f && o.dir == 0)
                if (ready("o_${o.label}_${o.dir}", if (dangerous) 3000 else 6000)) {
                    parts.add((if (dangerous && vehicle) L.t("Danger! ", "खतरा! ") else "") + phrase(o))
                    if (dangerous) urgent = true
                }
            }

            val blocked = near.filter { it.dist < 2.5f }
            val l = blocked.any { it.dir == -1 }
            val c = blocked.any { it.dir == 0 }
            val r = blocked.any { it.dir == 1 }
            val path = when { !c -> "clear"; !l -> "left"; !r -> "right"; else -> "stop" }
            if (path == "stop") urgent = true
            if (!hazard) {
                val (text, cd) = when (path) {
                    "clear" -> L.t("Path clear", "रास्ता साफ है") to 20000L
                    "left" -> L.t("Move left", "बाएं चलिए") to 4000L
                    "right" -> L.t("Move right", "दाएं चलिए") to 4000L
                    else -> L.t("Stop", "रुकिए") to 3000L
                }
                if (ready("path_$path", cd)) parts.add(text)
            }

            if (parts.isNotEmpty()) {
                val msg = parts.joinToString(". ")
                val closeObj = near.any { it.dist < 2f }
                handler.post {
                    if (destroyed) return@post
                    if (urgent) { buzz(true); speaker.speak(msg, true) }
                    else { if (closeObj) buzz(false); if (!speaker.busy) speaker.speak(msg) }
                }
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    // ---------------------------------------------------------------- location, steps, speed

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            lastLoc = l
            lastLocTime = SystemClock.elapsedRealtime()
            val s = currentSpeedKmh()
            val t = SystemClock.elapsedRealtime()
            if (abs(s - lastSpeedSaid) >= 3f && t - lastSpeedTime > 20000) {
                lastSpeedSaid = s; lastSpeedTime = t
                speaker.speak(speedText(s))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            say(L.t("The location permission is off", "लोकेशन अनुमति बंद है")); return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            say(L.t("GPS is off. Please turn on location in settings", "जीपीएस बंद है। कृपया सेटिंग में लोकेशन चालू करें"))
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000).build()
        fused.requestLocationUpdates(req, locCb, Looper.getMainLooper())
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = SystemClock.elapsedRealtime()
            stepTimes.add(t)
            stepTimes.removeAll { t - it > 8000 }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun startSteps() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        sm.registerListener(stepListener, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** Uses GPS speed when fresh and accurate, otherwise estimates from step count (0.7 m per step). */
    private fun currentSpeedKmh(): Float {
        val l = lastLoc
        val now = SystemClock.elapsedRealtime()
        if (l != null && l.hasSpeed() && now - lastLocTime < 8000 && l.accuracy < 30f) return l.speed * 3.6f
        stepTimes.removeAll { now - it > 8000 }
        return stepTimes.size / 8f * 0.7f * 3.6f
    }

    private fun speedText(s: Float): String {
        if (s < 0.5f) return L.t("You are standing still", "आप रुके हुए हैं")
        val v = String.format(Locale.US, "%.1f", s)
        return L.t("You are walking at $v kilometers per hour", "आप $v किलोमीटर प्रति घंटे की गति से चल रहे हैं")
    }

    // ---------------------------------------------------------------- voice commands

    private fun startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            say(L.t("The microphone permission is off", "माइक्रोफोन अनुमति बंद है")); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say(L.t("Voice recognition is not available on this phone", "इस फोन पर आवाज़ पहचान उपलब्ध नहीं है")); return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.let { handleCommand(it) }
                handler.postDelayed({ listenOnce() }, 500)
            }
            override fun onError(error: Int) { handler.postDelayed({ listenOnce() }, 1200) }
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(t: Int, p: android.os.Bundle?) {}
        })
        listenOnce()
    }

    private fun listenOnce() {
        if (destroyed) return
        if (speaker.busy) { handler.postDelayed({ listenOnce() }, 800); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (L.hi) "hi-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { recognizer?.startListening(i) } catch (e: Exception) { handler.postDelayed({ listenOnce() }, 2000) }
    }

    private fun hit(t: String, vararg k: String) = k.any { t.contains(it) }

    private fun handleCommand(text: String) {
        val t = text.lowercase()
        when {
            hit(t, "in front", "around me", "सामने क्या") -> describe()
            hit(t, "where am i", "कहाँ हूँ", "कहां हूं", "कहाँ हूं") -> whereAmI()
            hit(t, "how fast", "my speed", "कितनी तेज", "रफ्तार") -> say(speedText(currentSpeedKmh()))
            hit(t, "read", "पढ़") -> readText()
            hit(t, "call", "कॉल", "फोन") -> callContact()
            hit(t, "help me", "sos", "मदद", "बचाओ") -> sos()
            hit(t, "stop sharing", "शेयर बंद") -> { sharing = false; say(L.t("Location sharing stopped", "लोकेशन शेयरिंग बंद")) }
            hit(t, "share", "लोकेशन भेज") -> shareLocation()
            hit(t, "louder", "volume up", "आवाज़ बढ़ा", "आवाज बढ़ा") -> { prefs.volume += 0.2f; say(L.t("Louder", "आवाज़ बढ़ा दी")) }
            hit(t, "quieter", "softer", "volume down", "आवाज़ कम", "आवाज कम") -> { prefs.volume -= 0.2f; say(L.t("Quieter", "आवाज़ कम कर दी")) }
            hit(t, "faster", "तेज बोल", "तेज़ बोल") -> { prefs.rate += 0.2f; say(L.t("Speaking faster", "अब तेज़ बोलूंगा")) }
            hit(t, "slower", "धीरे बोल") -> { prefs.rate -= 0.2f; say(L.t("Speaking slower", "अब धीरे बोलूंगा")) }
            hit(t, "hindi", "हिंदी", "हिन्दी") -> { prefs.hindi = true; L.hi = true; speaker.applyLanguage(); say("अब मैं हिंदी में बोलूंगा") }
            hit(t, "english", "अंग्रेजी", "अंग्रेज़ी") -> { prefs.hindi = false; L.hi = false; speaker.applyLanguage(); say("Now speaking English") }
            hit(t, "vibration off", "वाइब्रेशन बंद") -> { prefs.vibration = false; say(L.t("Vibration off", "वाइब्रेशन बंद")) }
            hit(t, "vibration on", "वाइब्रेशन चालू") -> { prefs.vibration = true; buzz(false); say(L.t("Vibration on", "वाइब्रेशन चालू")) }
            hit(t, "exit", "quit", "बंद करो") -> { say(L.t("Goodbye", "अलविदा")); handler.postDelayed({ stopSelf() }, 2500) }
        }
    }

    private fun describe() {
        val near = lastObjs.filter { it.dist <= 8f }.sortedBy { it.dist }.take(3)
        if (near.isEmpty()) say(L.t("I do not see any important objects ahead", "आगे कोई ज़रूरी वस्तु नहीं दिख रही"))
        else say(near.joinToString(". ") { phrase(it) })
    }

    @Suppress("DEPRECATION")
    private fun whereAmI() {
        val l = lastLoc
        if (l == null) {
            say(L.t("I cannot find your location yet. Please check that GPS is on", "अभी लोकेशन नहीं मिली। कृपया जीपीएस चालू करें")); return
        }
        exec.execute {
            var msg: String? = null
            try {
                val a = Geocoder(this, if (L.hi) Locale("hi", "IN") else Locale.getDefault())
                    .getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                msg = a?.getAddressLine(0)
            } catch (_: Exception) {}
            say(msg?.let { L.t("You are near $it", "आप $it के पास हैं") }
                ?: L.t("Your position is latitude ${"%.4f".format(Locale.US, l.latitude)}, longitude ${"%.4f".format(Locale.US, l.longitude)}",
                    "आपकी स्थिति अक्षांश ${"%.4f".format(Locale.US, l.latitude)}, देशांतर ${"%.4f".format(Locale.US, l.longitude)} है"))
        }
    }

    private fun readText() {
        val b = lastBitmap
        if (b == null) { say(L.t("The camera image is not ready yet", "कैमरा चित्र अभी तैयार नहीं है")); return }
        say(L.t("Reading", "पढ़ रहा हूँ"))
        val rec = if (L.hi) TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { r ->
                val s = r.text.trim()
                if (s.isEmpty()) say(L.t("I cannot find any text. Move closer or add more light", "कोई लिखावट नहीं मिली। पास जाइए या रोशनी बढ़ाइए"))
                else say(L.t("The text says: ", "लिखा है: ") + s.take(400).replace("\n", ". "))
            }
            .addOnFailureListener { say(L.t("I could not read the text", "मैं लिखावट नहीं पढ़ सका")) }
    }

    // ---------------------------------------------------------------- contact, SMS, SOS

    private fun mapLink(): String? = lastLoc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }

    @Suppress("DEPRECATION")
    private fun sendSms(msg: String): Boolean {
        val num = prefs.contact
        if (num.isBlank()) {
            say(L.t("No emergency contact is saved. Please ask someone to save one in the app", "कोई आपातकालीन संपर्क सहेजा नहीं है। कृपया किसी से ऐप में सहेजने को कहें"), true)
            return false
        }
        if (!has(Manifest.permission.SEND_SMS)) {
            say(L.t("The SMS permission is off", "एसएमएस अनुमति बंद है"), true); return false
        }
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(msg), null, null)
            true
        } catch (e: Exception) {
            say(L.t("The message could not be sent", "संदेश नहीं भेजा जा सका"), true); false
        }
    }

    private val shareTick = object : Runnable {
        override fun run() {
            if (!sharing || destroyed) return
            mapLink()?.let { sendSms("My live location: $it") }
            handler.postDelayed(this, 180000)
        }
    }

    private fun shareLocation() {
        val link = mapLink()
        if (link == null) { say(L.t("I cannot find your location yet", "अभी लोकेशन नहीं मिली")); return }
        if (sendSms("I am sharing my location with you: $link")) {
            sharing = true
            handler.removeCallbacks(shareTick)
            handler.postDelayed(shareTick, 180000)
            say(L.t("Location sent. I will update it every 3 minutes. Say stop sharing to end.", "लोकेशन भेज दी। हर 3 मिनट में अपडेट भेजूंगा। रोकने के लिए शेयरिंग बंद बोलिए।"))
        }
    }

    private fun sos() {
        buzz(true)
        val link = mapLink()
        val ok = sendSms("HELP! I need help urgently. " + (link?.let { "My location: $it" } ?: "My location is unknown."))
        if (ok) {
            sharing = link != null
            if (sharing) { handler.removeCallbacks(shareTick); handler.postDelayed(shareTick, 60000) }
            say(L.t("Help alert sent to your emergency contact", "आपातकालीन संपर्क को मदद का संदेश भेज दिया"), true)
        }
    }

    private fun callContact() {
        val num = prefs.contact
        if (num.isBlank()) { say(L.t("No emergency contact is saved", "कोई आपातकालीन संपर्क सहेजा नहीं है")); return }
        if (!has(Manifest.permission.CALL_PHONE)) { say(L.t("The phone permission is off", "फोन अनुमति बंद है")); return }
        try {
            say(L.t("Calling your contact", "आपके संपर्क को कॉल कर रहा हूँ"))
            @SuppressLint("MissingPermission")
            val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
            tm.placeCall(Uri.fromParts("tel", num, null), null)
        } catch (e: Exception) {
            say(L.t("The call could not be made", "कॉल नहीं हो सकी"))
        }
    }
}
