package dev.abelman.remote

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Optional integration test against pyatv's official fake Companion server. */
class RemoteSmokeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    private var stage = "startup"

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val host = arguments.getString("host") ?: error("Pass -e host <simulator IPv4>")
            val port = arguments.getString("port")?.toInt() ?: error("Pass -e port <Companion port>")
            val pin = arguments.getString("pin") ?: "1111"
            check(host in setOf("127.0.0.1", "10.0.2.2")) { "Use only the local test simulator" }
            val store = DeviceStore(targetContext)
            val isolated = targetContext.packageName.endsWith(".qa")
            check(isolated || store.load().isEmpty()) { "Use the qa build to preserve existing pairings" }
            // The test APK has its own UID, Keystore and preferences. Never reset
            // the personal app's saved devices or select a real TV for this run.
            if (isolated) store.activeId = null
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val remote = (activity.application as RemoteApplication).remote
            val device = TvDevice("integration-simulator", "Гостиная", host, port, mapOf("rpmd" to "AppleTV6,2"))
            stage = "begin pairing"
            awaitState(remote, ConnectionState.AWAITING_PIN) { remote.choose(device) }
            stage = "finish pairing"
            awaitState(remote, ConnectionState.CONNECTED) { remote.pair(pin) }

            for (name in listOf("up", "down", "left", "right", "select", "menu", "home", "play_pause", "volume_up", "volume_down", "mute", "mute", "sleep", "wake")) {
                command(remote, name)
            }
            command(remote, "home", "double")
            command(remote, "home", "hold")
            command(remote, "text", text = "Привет, Apple TV!")
            // A request/response command fences the preceding fire-and-forget text events.
            command(remote, "select")

            check(store.load().any { it.id == device.id && it.credentials.isNotBlank() }) { "Pairing was not persisted" }
            val encrypted = targetContext.getSharedPreferences("paired_devices", Context.MODE_PRIVATE).getString("devices", "").orEmpty()
            check(!encrypted.contains("credentials") && !encrypted.contains("Гостиная")) { "Pairing store is not encrypted" }
            result.putString("stream", "\nOK: Android Python engine, PIN pairing, encrypted Companion connection, 18 remote commands, Keystore persistence.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            // Avoid printing exception text which could include secret material.
            result.putString("stream", "\nFAIL at $stage: ${error.javaClass.simpleName} at ${error.stackTrace.firstOrNull()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun awaitState(remote: RemoteRepository, expected: ConnectionState, startOperation: () -> Unit) {
        val latch = CountDownLatch(1)
        val actual = AtomicReference<ConnectionState>()
        val listener: () -> Unit = {
            actual.set(remote.state)
            if (remote.state == expected || remote.state == ConnectionState.ERROR) latch.countDown()
        }
        runOnMainSync { remote.observe(listener); startOperation() }
        val completed = latch.await(35, TimeUnit.SECONDS)
        runOnMainSync { remote.removeObserver(listener) }
        check(completed && actual.get() == expected) { "Unexpected connection state" }
    }

    private fun command(remote: RemoteRepository, name: String, action: String = "tap", text: String = "") {
        stage = "$name / $action"
        val latch = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        runOnMainSync { remote.command(name, action, text) { message -> error.set(message); latch.countDown() } }
        check(latch.await(15, TimeUnit.SECONDS) && error.get() == null) { "Command failed: $name" }
    }
}
