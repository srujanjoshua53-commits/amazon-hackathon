package com.firebridge.aiultra

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.util.concurrent.Executors

class KeyCommandExecutor(
    private val context: Context,
    private val activitySupplier: () -> Activity?
) {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Executes an incoming remote key command on Fire OS.
     */
    fun executeKey(command: String) {
        when (command.uppercase()) {
            "VOL_UP" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                injectKeyEvent(KeyEvent.KEYCODE_VOLUME_UP)
            }
            "VOL_DOWN" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                injectKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN)
            }
            "MUTE" -> {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                injectKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE)
            }
            "PLAY_PAUSE" -> {
                audioManager?.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                )
                audioManager?.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                )
                injectKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            "REWIND" -> {
                injectKeyEvent(KeyEvent.KEYCODE_MEDIA_REWIND)
            }
            "FAST_FORWARD" -> {
                injectKeyEvent(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            }
            "UP", "DPAD_UP" -> injectKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
            "DOWN", "DPAD_DOWN" -> injectKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
            "LEFT", "DPAD_LEFT" -> injectKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT", "DPAD_RIGHT" -> injectKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
            "SELECT", "ENTER", "OK" -> injectKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
            "BACK" -> injectKeyEvent(KeyEvent.KEYCODE_BACK)
            "HOME" -> {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            "MENU" -> injectKeyEvent(KeyEvent.KEYCODE_MENU)
            else -> {
                // Try direct keycode resolution
                try {
                    val keyCode = KeyEvent.keyCodeFromString("KEYCODE_$command")
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        injectKeyEvent(keyCode)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Injects the key event using multi-tiered fallback:
     * 1. Activity dispatch if foreground
     * 2. Android Instrumentation on background thread
     * 3. Shell 'input keyevent' execution
     */
    private fun injectKeyEvent(keyCode: Int) {
        val activity = activitySupplier()
        if (activity != null && !activity.isFinishing) {
            mainHandler.post {
                try {
                    val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                    activity.dispatchKeyEvent(downEvent)
                    activity.dispatchKeyEvent(upEvent)
                } catch (e: Exception) {
                    fallbackBackgroundInjection(keyCode)
                }
            }
        } else {
            fallbackBackgroundInjection(keyCode)
        }
    }

    private fun fallbackBackgroundInjection(keyCode: Int) {
        backgroundExecutor.execute {
            try {
                val inst = Instrumentation()
                inst.sendKeyDownUpSync(keyCode)
            } catch (e: Exception) {
                // Fallback to runtime input command (useful on Fire OS dev mode)
                try {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Launches a third-party app or settings on Fire OS by package name.
     */
    fun launchApp(packageName: String) {
        mainHandler.post {
            try {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                } else {
                    // Fallback to opening App Store or showing toast
                    val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("amzn://apps/android?p=$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
