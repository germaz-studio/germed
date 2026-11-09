package com.germaz.germed

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class GerMedClassifier(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("germed_mobile_v4_int8.onnx").use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    fun analyze(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val floatBuffer = FloatBuffer.allocate(1 * 3 * 256 * 256)
        
        val meanR = 0.485f
        val meanG = 0.456f
        val meanB = 0.406f
        val stdR = 0.229f
        val stdG = 0.224f
        val stdB = 0.225f

        val pixels = IntArray(256 * 256)
        scaled.getPixels(pixels, 0, 256, 0, 0, 256, 256)

        // CHW order
        // Red channel
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            floatBuffer.put((r - meanR) / stdR)
        }
        // Green channel
        for (p in pixels) {
            val g = ((p shr 8) and 0xFF) / 255.0f
            floatBuffer.put((g - meanG) / stdG)
        }
        // Blue channel
        for (p in pixels) {
            val b = (p and 0xFF) / 255.0f
            floatBuffer.put((b - meanB) / stdB)
        }
        floatBuffer.flip()

        val tensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, 256, 256))
        val results = session.run(mapOf("input_image" to tensor))

        // Extract outputs: [malignancy_risk, asymmetry, border, color, pattern]
        val riskArr = results.get(0).value as Array<FloatArray>
        val asymArr = results.get(1).value as Array<FloatArray>
        val borderArr = results.get(2).value as Array<FloatArray>
        val colorArr = results.get(3).value as Array<FloatArray>
        val patternArr = results.get(4).value as Array<FloatArray>

        val risk = riskArr[0][0]
        val asym = asymArr[0][0]
        val border = borderArr[0][0]
        val color = colorArr[0][0]
        val pattern = patternArr[0][0]

        val riskPct = (risk * 100).roundToInt().coerceIn(1, 99)

        val riskLevel: String
        val riskColor: String
        val barColor: String
        val whatThisMeans: String
        val recommendation: String

        if (risk >= 0.65f) {
            riskLevel = "High risk"
            riskColor = "#E53935"
            barColor = "#EF5350"
            whatThisMeans = "The result indicates an elevated likelihood of malignancy. Prompt dermatological examination and potential biopsy are strongly advised."
            recommendation = "Contact a dermatologist or skin cancer specialist immediately for an in-person dermoscopic evaluation."
        } else if (risk >= 0.40f) {
            riskLevel = "Moderate risk"
            riskColor = "#FB8C00"
            barColor = "#FFA726"
            whatThisMeans = "The result shows suspicious features. While not conclusively malignant, close inspection by a physician is recommended."
            recommendation = "Schedule a routine check-up with a dermatologist and avoid irritating or exposing the lesion to intense UV light."
        } else {
            riskLevel = "Low risk"
            riskColor = "#2E7D32"
            barColor = "#4CAF50"
            whatThisMeans = "The result suggests a low likelihood of skin cancer. Many benign moles can look similar, and changes over time are important."
            recommendation = "If the mole changes in size, shape, color, or starts to bleed or itch, consult a dermatologist."
        }

        val json = JSONObject().apply {
            put("risk_percentage", riskPct)
            put("risk_level", riskLevel)
            put("risk_color", riskColor)
            put("bar_color", barColor)
            put("summary", "The model estimates an $riskPct% probability that the analyzed lesion is a malignant skin tumor (e.g. melanoma).")
            put("what_this_means", whatThisMeans)
            put("recommendation", recommendation)
            put("thinking_mode", JSONObject().apply {
                put("asymmetry", ((asym * 1000).roundToInt() / 10.0))
                put("border", ((border * 1000).roundToInt() / 10.0))
                put("color", ((color * 1000).roundToInt() / 10.0))
                put("pattern", ((pattern * 1000).roundToInt() / 10.0))
            })
        }

        tensor.close()
        results.close()
        return json.toString()
    }
}
