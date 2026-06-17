package com.clipvault.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.facebook.react.ReactActivity

/**
 * ShareActivity — Android Share Target
 *
 * یہ Activity کسی بھی دوسری ایپ سے "شیئر" کے ذریعے
 * کلپ والٹ میں ٹیکسٹ محفوظ کرنے کی سہولت دیتی ہے۔
 *
 * صارف کسی بھی ایپ میں ٹیکسٹ سلیکٹ کرے → Share → ClipVault
 * اور کلپ خودکار سیو ہو جائے گی۔
 */
class ShareActivity : ReactActivity() {

  override fun getMainComponentName(): String = "main"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)

    val action = intent?.action
    val type = intent?.type

    if (action == Intent.ACTION_SEND && type?.startsWith("text/") == true) {
      val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()

      if (!sharedText.isNullOrBlank()) {
        // Background thread میں سیو کریں
        Thread {
          val saved = NativeClipStore.save(applicationContext, sharedText)
          runOnUiThread {
            if (saved) {
              Toast.makeText(
                applicationContext,
                "✅ ClipVault میں محفوظ ہو گئی",
                Toast.LENGTH_SHORT
              ).show()
            } else {
              Toast.makeText(
                applicationContext,
                "ℹ️ یہ کلپ پہلے سے موجود ہے",
                Toast.LENGTH_SHORT
              ).show()
            }
          }
        }.start()
      }
    } else if (action == Intent.ACTION_SEND_MULTIPLE && type?.startsWith("text/") == true) {
      // متعدد ٹیکسٹ آئٹمز
      val texts = intent.getStringArrayListExtra(Intent.EXTRA_TEXT)
      if (!texts.isNullOrEmpty()) {
        Thread {
          var savedCount = 0
          for (text in texts) {
            val trimmed = text.trim()
            if (trimmed.isNotBlank() && NativeClipStore.save(applicationContext, trimmed)) {
              savedCount++
            }
          }
          val count = savedCount
          runOnUiThread {
            Toast.makeText(
              applicationContext,
              if (count > 0) "✅ $count کلپس محفوظ ہو گئیں" else "ℹ️ کوئی نئی کلپ نہیں",
              Toast.LENGTH_SHORT
            ).show()
          }
        }.start()
      }
    }

    // Activity بند کریں — صارف کو ClipVault میں لے جانے کی ضرورت نہیں
    finish()
  }
}
