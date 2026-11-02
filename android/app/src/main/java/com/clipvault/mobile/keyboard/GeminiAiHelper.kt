package com.clipvault.mobile.keyboard

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeminiAiHelper — Cloud-powered text rewriting and grammar correction using Gemini Flash.
 * Handles API key rotation, request serialization, background execution, and main-thread callbacks.
 */
object GeminiAiHelper {

    private val apiKeys = listOf(
        "AIzaSyCOwePzEnkjw_WMi0lcNPKGtyZ0ZvF0gW4",
        "AIzaSyBM8j_ECs_FJU5RWE79V_mpy6AmAJHnamI",
        "AIzaSyAVYWaqcTFB7MtqfH_WoWrWvqK9oJw8uBQ"
    )

    fun rewriteText(textToRewrite: String, onComplete: (String?) -> Unit) {
        if (textToRewrite.isBlank()) {
            onComplete(null)
            return
        }

        Thread {
            val payload = JSONObject().apply {
                val contents = JSONArray().apply {
                    val content = JSONObject().apply {
                        val parts = JSONArray().apply {
                            val part = JSONObject().apply {
                                put(
                                    "text",
                                    "Rewrite this text to be professional, grammatically correct, and clear. Output ONLY the rewritten text, without quotes or conversational filler:\n\n$textToRewrite"
                                )
                            }
                            put(part)
                        }
                        put("parts", parts)
                    }
                    put(content)
                }
                put("contents", contents)
            }

            var result: String? = null
            for (key in apiKeys) {
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        connectTimeout = 6000
                        readTimeout = 8000
                    }

                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val rewritten = json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        result = rewritten.trim()
                        break
                    }
                } catch (_: Exception) {
                    // Try next key on network / quota errors
                }
            }

            Handler(Looper.getMainLooper()).post {
                onComplete(result)
            }
        }.start()
    }
}
