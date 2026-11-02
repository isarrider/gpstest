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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SisnetTest {

    private val hex = SbasTestMessage.prnMask(0, listOf(1, 2, 3)).toHex()

    private fun withChecksum(body: String): String {
        val cs = body.fold(0) { acc, c -> acc xor c.code }
        return "*$body*%02X".format(cs)
    }

    @Test
    fun parse_message() {
        val r = SisnetResponse.parse(withChecksum("MSG,2443,50400,$hex")) as SisnetResponse.Message
        assertEquals(hex, r.hex)
        assertEquals(2443, r.week)
        assertEquals(50400L, r.timeOfWeek)
        assertEquals(true, r.checksumOk)
        assertEquals(2443L * 604800 + 50400, r.gpsSeconds)
    }

    @Test
    fun parse_tenBitWeek_badChecksum_noChecksum() {
        val r = SisnetResponse.parse("*MSG,342,10,$hex*00") as SisnetResponse.Message
        assertEquals(false, r.checksumOk)
        assertEquals((342L + 2048) * 604800 + 10, r.gpsSeconds)
        val noCs = SisnetResponse.parse("*MSG,2443,1,$hex") as SisnetResponse.Message
        assertNull(noCs.checksumOk)
        // A leading PRN field is tolerated: the last two numbers before the hex are the time
        val withPrn = SisnetResponse.parse("*MSG,136,2443,7,$hex") as SisnetResponse.Message
        assertEquals(7L, withPrn.timeOfWeek)
    }

    @Test
    fun parse_authAndErrors() {
        assertTrue(SisnetResponse.parse("*AUTH,alex*45") is SisnetResponse.AuthOk)
        val e = SisnetResponse.parse("*ERR,wrong password*12") as SisnetResponse.Error
        assertEquals("wrong password", e.text)
        assertNull(SisnetResponse.parse("hello"))
    }

    @Test
    fun client_authenticatesAndPolls() {
        val server = ServerSocket(0)
        val received = CopyOnWriteArrayList<SisnetResponse.Message>()
        val states = CopyOnWriteArrayList<SisnetClient.State>()
        val gotTwo = CountDownLatch(2)
        val commands = CopyOnWriteArrayList<String>()
        val serverThread = Thread {
            server.accept().use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)
                var tow = 100
                while (true) {
                    val line = reader.readLine() ?: break
                    commands.add(line)
                    when {
                        line.startsWith("AUTH") -> writer.println(withChecksum("AUTH,user"))
                        line == "MSG" -> {
                            // Every message twice, as a server returns the latest until a new one
                            writer.println(withChecksum("MSG,2443,${tow / 2},$hex"))
                            tow++
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val client = SisnetClient(
            "127.0.0.1", server.localPort, "user", "secret",
            object : SisnetClient.Listener {
                override fun onState(state: SisnetClient.State, detail: String?) {
                    states.add(state)
                }

                override fun onMessage(response: SisnetResponse.Message) {
                    received.add(response)
                    gotTwo.countDown()
                }
            },
            pollIntervalMillis = 10
        )
        client.start()
        assertTrue(gotTwo.await(5, TimeUnit.SECONDS))
        client.stop()
        server.close()
        serverThread.join(2000)

        assertEquals("AUTH,user,secret", commands.first())
        assertTrue(states.contains(SisnetClient.State.LIVE))
        // Duplicates of the same line are not reported twice
        assertTrue(received[0].timeOfWeek != received[1].timeOfWeek)
    }

    @Test
    fun client_reportsAuthFailure() {
        val server = ServerSocket(0)
        val failed = CountDownLatch(1)
        Thread {
            server.accept().use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)
                reader.readLine()
                writer.println("*ERR,authentication failed*00")
                reader.readLine()
            }
        }.apply { isDaemon = true; start() }
        var detail: String? = null
        val client = SisnetClient(
            "127.0.0.1", server.localPort, "user", "bad",
            object : SisnetClient.Listener {
                override fun onState(state: SisnetClient.State, d: String?) {
                    if (state == SisnetClient.State.AUTH_FAILED) {
                        detail = d
                        failed.countDown()
                    }
                }

                override fun onMessage(response: SisnetResponse.Message) = Unit
            }
        )
        client.start()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        client.stop()
        server.close()
        assertEquals("authentication failed", detail)
    }
}
