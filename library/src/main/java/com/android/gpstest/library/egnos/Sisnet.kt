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
package com.android.gpstest.library.egnos

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A response line of a SISNeT server (ESA's "Signal in Space through the Internet" protocol, as
 * offered by the EGNOS Data Access Service, EDAS). Responses look like
 * `*CMD,field,...*CS`, where CS is an optional XOR checksum of the characters between the stars.
 *
 * The parser is lenient: for `*MSG` it takes the first field of at least 63 hex digits as the
 * SBAS message and the two numbers before it as GPS week and time of week, so that small
 * differences between server versions don't matter. The SBAS CRC, not the line checksum, decides
 * whether a message is used.
 */
sealed class SisnetResponse {
    /** The checksum after the second star matched, didn't match, or was absent (null) */
    abstract val checksumOk: Boolean?

    data class Message(
        val hex: String,
        val week: Int?,
        val timeOfWeek: Long?,
        override val checksumOk: Boolean?
    ) : SisnetResponse() {
        /** GPS seconds of the message, or null if the line had no time */
        val gpsSeconds: Long?
            get() {
                if (week == null || timeOfWeek == null) return null
                // Some servers send the 10-bit week; the week number rolled over twice (1999, 2019)
                val fullWeek = if (week < 1024) week + 2048 else week
                return fullWeek * SECONDS_PER_WEEK + timeOfWeek
            }
    }

    data class AuthOk(override val checksumOk: Boolean?) : SisnetResponse()

    data class Error(val text: String, override val checksumOk: Boolean?) : SisnetResponse()

    data class Other(val command: String, val line: String, override val checksumOk: Boolean?) :
        SisnetResponse()

    companion object {
        const val SECONDS_PER_WEEK = 604_800L

        fun parse(rawLine: String): SisnetResponse? {
            val line = rawLine.trim()
            if (!line.startsWith("*")) return null
            val secondStar = line.indexOf('*', 1)
            val body = if (secondStar > 0) line.substring(1, secondStar) else line.substring(1)
            val checksumText = if (secondStar > 0) line.substring(secondStar + 1).trim() else ""
            val checksumOk = if (checksumText.length == 2 && checksumText.all { it.isHex() }) {
                checksumText.toInt(16) == body.fold(0) { acc, c -> acc xor c.code }
            } else {
                null
            }
            val fields = body.split(',').map { it.trim() }
            val command = fields.first().uppercase()
            return when {
                command.contains("ERR") ->
                    Error(fields.drop(1).joinToString(","), checksumOk)
                command == "AUTH" -> AuthOk(checksumOk)
                command == "MSG" -> {
                    val hexIndex = fields.indexOfFirst { f ->
                        f.length >= SbasMessage.HEX_LENGTH && f.all { it.isHex() }
                    }
                    if (hexIndex < 0) {
                        Other(command, line, checksumOk)
                    } else {
                        val numbers = fields.subList(1, hexIndex).mapNotNull { it.toLongOrNull() }
                        val week = numbers.getOrNull(numbers.size - 2)?.toInt()
                        val tow = numbers.lastOrNull().takeIf { numbers.size >= 2 }
                        Message(fields[hexIndex], week, tow, checksumOk)
                    }
                }
                else -> Other(command, line, checksumOk)
            }
        }

        private fun Char.isHex(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/**
 * Polls a SISNeT server for the latest EGNOS message once per second (SBAS sends one message per
 * second) and hands each new one to the [Listener]. Reconnects with a growing delay after errors.
 *
 * SISNeT is plain TCP: the EDAS user name and password are sent unencrypted.
 */
class SisnetClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val listener: Listener,
    private val pollIntervalMillis: Long = 1_000L,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000
) {
    enum class State { CONNECTING, LIVE, AUTH_FAILED, ERROR, STOPPED }

    interface Listener {
        fun onState(state: State, detail: String?)

        /** A new message line; [response] has the hex, the time and the line checksum result */
        fun onMessage(response: SisnetResponse.Message)
    }

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "SISNeT").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        var backoff = MIN_BACKOFF_MILLIS
        while (running) {
            listener.onState(State.CONNECTING, "$host:$port")
            try {
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    s.soTimeout = readTimeoutMillis
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    val writer = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                    if (!authenticate(reader, writer)) {
                        listener.onState(State.AUTH_FAILED, lastError)
                        // Wrong credentials won't fix themselves quickly
                        sleep(AUTH_FAILED_RETRY_MILLIS)
                        return@use
                    }
                    listener.onState(State.LIVE, null)
                    backoff = MIN_BACKOFF_MILLIS
                    poll(reader, writer)
                }
            } catch (e: IOException) {
                if (!running) break
                listener.onState(State.ERROR, e.message ?: e.javaClass.simpleName)
                sleep(backoff)
                backoff = minOf(backoff * 2, MAX_BACKOFF_MILLIS)
            } catch (e: InterruptedException) {
                break
            } finally {
                socket = null
            }
        }
        listener.onState(State.STOPPED, null)
    }

    private var lastError: String? = null

    private fun authenticate(reader: BufferedReader, writer: OutputStreamWriter): Boolean {
        send(writer, "AUTH,$user,$password")
        // Skip a possible greeting until the reply to AUTH
        repeat(MAX_LINES_BEFORE_AUTH) {
            val line = reader.readLine() ?: throw IOException("Connection closed")
            when (val r = SisnetResponse.parse(line)) {
                is SisnetResponse.AuthOk -> return true
                is SisnetResponse.Error -> {
                    lastError = r.text
                    return false
                }
                else -> Unit
            }
        }
        lastError = "No reply to AUTH"
        return false
    }

    private fun poll(reader: BufferedReader, writer: OutputStreamWriter) {
        var lastLine: String? = null
        while (running) {
            val started = System.currentTimeMillis()
            send(writer, "MSG")
            val line = reader.readLine() ?: throw IOException("Connection closed")
            when (val r = SisnetResponse.parse(line)) {
                is SisnetResponse.Message -> {
                    // The same message is returned until the next one arrives
                    if (line != lastLine) listener.onMessage(r)
                    lastLine = line
                }
                is SisnetResponse.Error -> throw IOException(r.text)
                else -> Unit
            }
            val wait = pollIntervalMillis - (System.currentTimeMillis() - started)
            if (wait > 0) sleep(wait)
        }
    }

    private fun send(writer: OutputStreamWriter, command: String) {
        writer.write(command + "\r\n")
        writer.flush()
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            running = false
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val MIN_BACKOFF_MILLIS = 5_000L
        private const val MAX_BACKOFF_MILLIS = 120_000L
        private const val AUTH_FAILED_RETRY_MILLIS = 300_000L
        private const val MAX_LINES_BEFORE_AUTH = 5
    }
}
