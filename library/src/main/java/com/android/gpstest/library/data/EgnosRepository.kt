/*
 * Copyright (C) 2026 The GPSTest contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.gpstest.library.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.android.gpstest.library.R
import com.android.gpstest.library.egnos.EgnosAccumulator
import com.android.gpstest.library.egnos.EgnosSnapshot
import com.android.gpstest.library.egnos.SbasMessage
import com.android.gpstest.library.egnos.SisnetClient
import com.android.gpstest.library.egnos.SisnetResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EgnosRepository"

/** Connection to the EGNOS message source (SISNeT server of EDAS) */
enum class EgnosConnection {
    /** EGNOS messages are turned off in Settings */
    OFF,

    /** Turned on, but server or user name are missing */
    NOT_CONFIGURED,

    /** Turned on, but tracking isn't started, so the connection isn't open */
    IDLE,
    CONNECTING,
    LIVE,
    AUTH_FAILED,
    ERROR
}

/** State of the EGNOS message monitor for the UI */
data class EgnosUiState(
    val connection: EgnosConnection = EgnosConnection.OFF,
    /** Error text from the server or the network, if any */
    val detail: String? = null,
    /** "host:port" of the SISNeT server */
    val server: String = "",
    val snapshot: EgnosSnapshot = EgnosSnapshot(),
    /** SystemClock.elapsedRealtime() when the last message arrived, or null */
    val lastMessageElapsedMillis: Long? = null,
    /** Lines whose SISNeT checksum didn't match (the SBAS CRC still decides) */
    val checksumErrors: Long = 0
) {
    val enabled: Boolean get() = connection != EgnosConnection.OFF
}

/**
 * Receives the EGNOS messages from a SISNeT server of the EGNOS Data Access Service (EDAS) while
 * tracking is started, decodes them and publishes the result. The phone's GNSS chipset doesn't
 * give apps the SBAS messages, so they come from the internet: EDAS is free, but needs a
 * registration (https://egnos-user-support.essp-sas.eu/), and each user enters their own login.
 */
@Singleton
class EgnosRepository @Inject constructor(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "EGNOS") }

    private val _state = MutableStateFlow(EgnosUiState(connection = initialConnection()))
    val state: StateFlow<EgnosUiState> = _state

    // Only accessed from the executor thread
    private var accumulator = EgnosAccumulator()
    private var client: SisnetClient? = null
    private var active = false
    private var checksumErrors = 0L
    private var lastMessageElapsed: Long? = null
    private var connection = initialConnection()
    private var detail: String? = null

    private val keys: Set<String> = setOf(
        R.string.pref_key_egnos_enabled,
        R.string.pref_key_egnos_host,
        R.string.pref_key_egnos_port,
        R.string.pref_key_egnos_user,
        R.string.pref_key_egnos_password
    ).map { context.getString(it) }.toSet()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in keys) executor.execute { restart(resetData = true) }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** Opens the connection (if turned on and configured). Called when tracking starts. */
    fun start() = executor.execute {
        // The service observes its flows again whenever the app comes back; keep the connection
        if (active) return@execute
        active = true
        restart(resetData = false)
    }

    /** Closes the connection. Called when tracking stops; the last data stays visible. */
    fun stop() = executor.execute {
        active = false
        restart(resetData = false)
    }

    private fun restart(resetData: Boolean) {
        client?.stop()
        client = null
        if (resetData) {
            accumulator = EgnosAccumulator()
            checksumErrors = 0
            lastMessageElapsed = null
        }
        val settings = settings()
        connection = when {
            settings == null -> initialConnection()
            !active -> EgnosConnection.IDLE
            else -> EgnosConnection.CONNECTING
        }
        detail = null
        if (settings != null && active) {
            client = SisnetClient(
                settings.host, settings.port, settings.user, settings.password, clientListener
            ).also { it.start() }
        }
        publish()
    }

    private val clientListener = object : SisnetClient.Listener {
        override fun onState(state: SisnetClient.State, detail: String?) = executor.execute {
            if (client == null) return@execute
            connection = when (state) {
                SisnetClient.State.CONNECTING -> EgnosConnection.CONNECTING
                SisnetClient.State.LIVE -> EgnosConnection.LIVE
                SisnetClient.State.AUTH_FAILED -> EgnosConnection.AUTH_FAILED
                SisnetClient.State.ERROR -> EgnosConnection.ERROR
                SisnetClient.State.STOPPED -> if (active) EgnosConnection.ERROR else EgnosConnection.IDLE
            }
            this@EgnosRepository.detail = if (state == SisnetClient.State.CONNECTING) null else detail
            if (state == SisnetClient.State.ERROR || state == SisnetClient.State.AUTH_FAILED) {
                Log.w(TAG, "SISNeT $state: $detail")
            }
            publish()
        }

        override fun onMessage(response: SisnetResponse.Message) = executor.execute {
            if (client == null) return@execute
            if (response.checksumOk == false) checksumErrors++
            val message = try {
                SbasMessage.fromHex(response.hex)
            } catch (e: IllegalArgumentException) {
                accumulator.addCorrupted()
                publish()
                return@execute
            }
            accumulator.add(message, response.gpsSeconds ?: gpsSecondsNow())
            lastMessageElapsed = SystemClock.elapsedRealtime()
            publish()
        }
    }

    private fun publish() {
        _state.value = EgnosUiState(
            connection = connection,
            detail = detail,
            server = settings()?.let { "${it.host}:${it.port}" } ?: "",
            snapshot = accumulator.snapshot(),
            lastMessageElapsedMillis = lastMessageElapsed,
            checksumErrors = checksumErrors
        )
    }

    private data class Settings(val host: String, val port: Int, val user: String, val password: String)

    /** The connection settings, or null if EGNOS is off or not configured */
    private fun settings(): Settings? {
        if (!enabled()) return null
        val host = prefs.getString(context.getString(R.string.pref_key_egnos_host), "")!!.trim()
        val port = prefs.getString(context.getString(R.string.pref_key_egnos_port), "")!!.trim()
            .toIntOrNull()?.takeIf { it in 1..65535 }
        val user = prefs.getString(context.getString(R.string.pref_key_egnos_user), "")!!.trim()
        val password = prefs.getString(context.getString(R.string.pref_key_egnos_password), "")!!
        if (host.isEmpty() || port == null || user.isEmpty()) return null
        return Settings(host, port, user, password)
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(context.getString(R.string.pref_key_egnos_enabled), false)

    private fun initialConnection(): EgnosConnection =
        if (enabled()) EgnosConnection.NOT_CONFIGURED else EgnosConnection.OFF

    companion object {
        private const val GPS_EPOCH_UNIX_SECONDS = 315_964_800L
        private const val GPS_LEAP_SECONDS = 18L

        /** The current GPS time in seconds, from the device clock, for lines without a time */
        fun gpsSecondsNow(): Long =
            System.currentTimeMillis() / 1000 - GPS_EPOCH_UNIX_SECONDS + GPS_LEAP_SECONDS
    }
}
