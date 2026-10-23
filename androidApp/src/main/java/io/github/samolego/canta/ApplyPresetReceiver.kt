package io.github.samolego.canta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets automation apps (MacroDroid, Tasker, `adb shell am broadcast`) uninstall
 * the apps of a saved preset, without opening Canta.
 *
 *     am broadcast -n io.github.samolego.canta/.ApplyPresetReceiver \
 *         -a io.github.samolego.canta.action.APPLY_PRESET \
 *         --es preset "Default Removals" --es token "<secret>"
 *
 * Extras:
 *  - [EXTRA_PRESET]: the preset's name (or its uuid).
 *  - [EXTRA_TOKEN]: must equal the build-time `automation.token`; with no
 *    token configured the receiver ignores every broadcast.
 *  - [EXTRA_RESET_TO_FACTORY] (optional boolean): also reset updated system apps.
 *
 * Only apps that are still installed for the user are touched, so running the
 * same preset repeatedly is cheap. Shizuku must be running and authorized.
 */
class ApplyPresetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPLY_PRESET) return
        if (!tokenMatches(intent.getStringExtra(EXTRA_TOKEN))) {
            Log.w(TAG, "Rejected broadcast: missing or wrong token")
            return
        }
        val presetKey = intent.getStringExtra(EXTRA_PRESET)
        if (presetKey.isNullOrBlank()) {
            Log.w(TAG, "Rejected broadcast: no '$EXTRA_PRESET' extra")
            return
        }
        val resetToFactory = intent.getBooleanExtra(EXTRA_RESET_TO_FACTORY, false)

        val app = context.applicationContext as CantaApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A broadcast gets about ten seconds, so stop a little before that.
                withTimeoutOrNull(BROADCAST_BUDGET_MS) { apply(app, presetKey, resetToFactory) }
                    ?: Log.w(TAG, "Timed out; run the broadcast again to finish the rest")
            } catch (e: Exception) {
                Log.e(TAG, "Applying preset '$presetKey' failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun apply(app: CantaApplication, presetKey: String, resetToFactory: Boolean) {
        val deps = app.dependencies
        val preset = deps.presetStore.presetsFlow.first()
            .firstOrNull { it.uuid == presetKey || it.name == presetKey }
        if (preset == null) {
            Log.w(TAG, "No preset named '$presetKey'")
            return
        }
        if (!deps.handler.isAuthorized) {
            Log.w(TAG, "Shizuku is not running or Canta is not authorized")
            return
        }

        val installed = deps.handler.loadApps()
            .filter { !it.isUninstalled }
            .map { it.packageName }
            .toSet()
        val targets = preset.apps.filter { it in installed }
        Log.i(TAG, "Preset '${preset.name}': ${targets.size} of ${preset.apps.size} apps still installed")

        var done = 0
        deps.handler.uninstallApps(targets, resetToFactory).collect { result ->
            if (result.success) done++ else Log.w(TAG, "${result.packageName}: ${result.message}")
        }
        Log.i(TAG, "Preset '${preset.name}': removed $done of ${targets.size}")
    }

    private fun tokenMatches(given: String?): Boolean {
        val expected = BuildConfig.AUTOMATION_TOKEN
        if (expected.isEmpty() || given == null) return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    companion object {
        private const val TAG = "ApplyPresetReceiver"
        private const val BROADCAST_BUDGET_MS = 8_000L

        const val ACTION_APPLY_PRESET = "io.github.samolego.canta.action.APPLY_PRESET"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_RESET_TO_FACTORY = "reset_to_factory"
    }
}
