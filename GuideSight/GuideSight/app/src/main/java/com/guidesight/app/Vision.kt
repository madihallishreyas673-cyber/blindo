package com.guidesight.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.max
import kotlin.math.min

/** dir: -1 = left, 0 = ahead, 1 = right. dist in meters (estimate). */
data class Obj(val label: String, val dir: Int, val dist: Float)

object Names {
    private val map = mapOf(
        "person" to ("Person" to "व्यक्ति"), "dog" to ("Dog" to "कुत्ता"), "cat" to ("Cat" to "बिल्ली"),
        "car" to ("Car" to "कार"), "truck" to ("Truck" to "ट्रक"), "bus" to ("Bus" to "बस"),
        "motorcycle" to ("Motorcycle" to "मोटरसाइकिल"), "bicycle" to ("Bicycle" to "साइकिल"),
        "chair" to ("Chair" to "कुर्सी"), "bench" to ("Bench" to "बेंच"), "couch" to ("Couch" to "सोफा"),
        "potted plant" to ("Plant pot" to "गमला"), "fire hydrant" to ("Fire hydrant" to "हाइड्रेंट"),
        "stop sign" to ("Stop sign" to "स्टॉप साइन"), "dining table" to ("Table" to "मेज़")
    )
    fun name(label: String): String = map[label]?.let { if (L.hi) it.second else it.first } ?: label
}

class ObjectDetectorWrapper(ctx: Context) {
    private val det: ObjectDetector

    init {
        val base = BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.45f)
            .setMaxResults(8)
            .setCategoryAllowlist(HEIGHTS.keys.toList())
            .build()
        det = ObjectDetector.createFromOptions(ctx, opts)
    }

    fun detect(bmp: Bitmap): List<Obj> {
        val res = det.detect(BitmapImageBuilder(bmp).build())
        val out = ArrayList<Obj>()
        val focalPx = bmp.height / 1.226f   // assumes ~63 degree vertical field of view
        for (d in res.detections()) {
            val label = d.categories().firstOrNull()?.categoryName() ?: continue
            val realH = HEIGHTS[label] ?: continue
            val b = d.boundingBox()
            val cx = (b.left + b.right) / 2f / bmp.width
            val dir = if (cx < 0.35f) -1 else if (cx > 0.65f) 1 else 0
            var dist = focalPx * realH / max(b.height(), 1f)
            if (b.height() > bmp.height * 0.92f) dist = min(dist, 0.8f)
            out.add(Obj(label, dir, dist.coerceIn(0.5f, 30f)))
        }
        return out
    }

    fun close() = det.close()

    companion object {
        // Typical real-world heights in meters, used to estimate distance.
        val HEIGHTS = mapOf(
            "person" to 1.7f, "dog" to 0.5f, "cat" to 0.3f, "car" to 1.5f, "truck" to 3.0f,
            "bus" to 3.0f, "motorcycle" to 1.2f, "bicycle" to 1.0f, "chair" to 0.9f,
            "bench" to 0.8f, "couch" to 0.85f, "potted plant" to 0.5f, "fire hydrant" to 0.7f,
            "stop sign" to 2.0f, "dining table" to 0.75f
        )
        val VEHICLES = setOf("car", "truck", "bus", "motorcycle", "bicycle")
    }
}

/** Very simple brightness checks. The ground-patch check is a rough heuristic, NOT a real hole detector. */
object Vision {
    private fun lum(p: Int) = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)

    private fun region(b: Bitmap, x0: Double, x1: Double, y0: Double, y1: Double): Double {
        var s = 0.0; var n = 0
        val ya = (b.height * y0).toInt(); val yb = (b.height * y1).toInt()
        val xa = (b.width * x0).toInt(); val xb = (b.width * x1).toInt()
        var y = ya
        while (y < yb) {
            var x = xa
            while (x < xb) { s += lum(b.getPixel(x, y)); n++; x += 6 }
            y += 6
        }
        return if (n == 0) 0.0 else s / n
    }

    fun brightness(b: Bitmap) = region(b, 0.0, 1.0, 0.0, 1.0)

    /** True if the ground just ahead is much darker than the ground beside it (possible hole/drain). */
    fun darkPatchAhead(b: Bitmap): Boolean {
        val c = region(b, 0.35, 0.65, 0.72, 0.92)
        val ring = (region(b, 0.05, 0.25, 0.72, 0.92) + region(b, 0.75, 0.95, 0.72, 0.92)) / 2
        return ring > 70 && c < ring * 0.4
    }
}
