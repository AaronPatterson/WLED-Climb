package com.wledclimb.app.network

import com.wledclimb.app.palette.HoldColor
import android.util.Log
import com.wledclimb.app.grid.Wall
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WLED treats a black pixel as off; there is no separate "off" value. */
private const val OFF_COLOR = "000000"

/**
 * One OkHttpClient for the whole app. Each instance carries its own connection
 * pool and thread pools, and a new client is otherwise built per WledClient -
 * which meant a fresh pool on every "Test & save" tap and every controller
 * switch. Sharing also means connections to the wall get reused.
 */
private val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}

/**
 * [WledClient] over WLED's JSON HTTP API.
 *
 * Wraps three endpoints so far: GET/POST http://<ip>/json/state (wall on/off),
 * GET http://<ip>/json/cfg (raw grid/segment config), and GET
 * http://<ip>/2d-gaps.json (optional gap file, served straight from WLED's
 * filesystem). See https://kno.wled.ge/interfaces/json-api/ for the full API
 * this will grow into (colors, presets, etc.) in later phases.
 */
class HttpWledClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = sharedHttpClient
) : WledClient {

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun getStatus(): WledStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/json/state")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            parseStatus(response)
        }
    }

    override suspend fun setOn(on: Boolean): WledStatus = withContext(Dispatchers.IO) {
        // WLED's default response to a state-changing POST is just {"success":true};
        // "v":true asks it to reply with the full state instead, matching what GET
        // returns, so parseStatus() can handle both the same way.
        postState(JSONObject().put("on", on))
    }

    override suspend fun setBrightness(brightness: Int, on: Boolean): WledStatus =
        withContext(Dispatchers.IO) {
            // "on" is sent even though only brightness is changing. WLED derives
            // power from brightness when the field is absent, so omitting it would
            // wake a wall that was deliberately off.
            val clamped = brightness.coerceIn(MIN_USABLE_BRIGHTNESS, MAX_BRIGHTNESS)
            postState(JSONObject().put("bri", clamped).put("on", on))
        }

    override suspend fun getIdentity(): WledIdentity = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/json/info")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WLED returned HTTP ${response.code} for ${response.request.url}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from WLED")
            try {
                val info = JSONObject(body)
                // Both required. The name labels the wall; the MAC is what
                // identifies it, and a wall that cannot be identified cannot
                // own the routes drawn on it.
                val mac = info.optString("mac", "")
                if (mac.isBlank()) {
                    throw WledIdentityException(
                        "Controller at ${response.request.url.host} reports no MAC address"
                    )
                }
                WledIdentity(name = info.getString("name"), mac = mac)
            } catch (e: JSONException) {
                throw IOException("Unexpected response from ${response.request.url}: $body", e)
            }
        }
    }

    private fun postState(state: JSONObject): WledStatus {
        val payload = state.put("v", true).toString()
        val request = Request.Builder()
            .url("$baseUrl/json/state")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            parseStatus(response)
        }
    }

    override suspend fun getWall(): Wall = withContext(Dispatchers.IO) {
        // Both files at once. They do not depend on each other and the second
        // is usually a 404, so waiting for the first before asking costs a
        // round trip for nothing.
        coroutineScope {
            val config = async { fetchConfig() }
            val gaps = async { fetchGaps() }
            wallFrom(rawConfig = config.await(), rawGaps = gaps.await())
        }
    }

    private fun fetchConfig(): String {
        val request = Request.Builder()
            .url("$baseUrl/json/cfg")
            .get()
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WLED returned HTTP ${response.code} for ${response.request.url}")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty response from WLED")
            // Logged raw, because a config that will not parse is the thing
            // worth seeing and by the time it is a Wall it is gone.
            Log.d(TAG, "WLED config for $baseUrl: $body")
            body
        }
    }

    /** Null when no gap file is configured, which WLED answers with a 404. */
    private fun fetchGaps(): String? {
        val request = Request.Builder()
            .url("$baseUrl/2d-gaps.json")
            .get()
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    override suspend fun setHoldColors(pixelCount: Int, lit: Map<Int, String>) =
        withContext(Dispatchers.IO) {
            // WLED walks the "i" array in order, so a start/stop/colour triple
            // blacks out the whole wall first and each index/colour pair after
            // it lights one hold. One request, and the result doesn't depend on
            // what was already showing.
            val individualLeds = JSONArray().apply {
                put(0)
                put(pixelCount)
                put(OFF_COLOR)
                lit.forEach { (segmentIndex, color) ->
                    put(segmentIndex)
                    put(color)
                }
            }
            val payload = JSONObject()
                .put("seg", JSONObject().put("i", individualLeds))
                .toString()
            val request = Request.Builder()
                .url("$baseUrl/json/state")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("WLED returned HTTP ${response.code} for ${response.request.url}")
                }
            }
        }

    private fun parseStatus(response: Response): WledStatus {
        if (!response.isSuccessful) {
            throw IOException("WLED returned HTTP ${response.code} for ${response.request.url}")
        }
        val body = response.body?.string() ?: throw IOException("Empty response from WLED")
        return try {
            val json = JSONObject(body)
            WledStatus(on = json.getBoolean("on"), brightness = json.getInt("bri"))
        } catch (e: JSONException) {
            // Surface the actual payload so a shape mismatch (e.g. hitting /json instead
            // of /json/state, or a WLED version returning something unexpected) is obvious
            // from the error message alone, instead of needing another round trip.
            throw IOException(
                "Unexpected response from ${response.request.url} " +
                    "(expected \"on\" and \"bri\"): ${body.take(300)}",
                e
            )
        }
    }
}

private const val TAG = "HttpWledClient"
