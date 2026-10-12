package com.wledclimb.app.e2e

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A WLED controller on a real socket, for the end-to-end tests.
 *
 * Real rather than a fake [com.wledclimb.app.network.WledClient] because these
 * tests drive the app as shipped: the address is typed into the setup screen,
 * the real HTTP client talks to this, and nothing in the app is swapped out to
 * make that possible. What WLED does with the requests is modelled only as far
 * as the app can observe - power, brightness, identity, the matrix config, and
 * which holds were last pushed.
 *
 * [reachable] can be switched off and on without the address changing, which
 * is what an unplugged controller looks like to the app: the same address, and
 * nothing answering on it.
 */
class FakeWledController : Closeable {

    private val server = MockWebServer()

    @Volatile var on = true
    @Volatile var brightness = 128
    @Volatile var name = "Climbing Wall"
    @Volatile var mac = "b0cbd8e23458"
    @Volatile var config = THREE_BY_THREE
    @Volatile var gaps: String? = null
    @Volatile var reachable = true

    private val pushed = CopyOnWriteArrayList<Map<Int, String>>()

    /** Every set of holds pushed, oldest first: segment index to hex colour. */
    val pushes: List<Map<Int, String>> get() = pushed.toList()

    /** What the wall is showing now, as far as the app has told it. */
    val showing: Map<Int, String> get() = pushed.lastOrNull().orEmpty()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)

            // MockWebServer asks this, not dispatch, whether to drop a new
            // connection before reading anything - so "unreachable" has to be
            // answered here as well. Answered only in dispatch, the connection
            // stays open and the client is sent a 200 with an empty body,
            // which the app quite rightly reports as not a WLED matrix rather
            // than as nothing being there.
            override fun peek(): MockResponse =
                if (reachable) super.peek() else MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        }
        // An explicit loopback address rather than "localhost", which can
        // resolve to whatever the machine's hosts file says - Docker Desktop
        // maps it to kubernetes.docker.internal - and so read differently in
        // the error messages here than on CI.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /** What someone types into the setup screen: host and port, no scheme. */
    val address: String get() = "127.0.0.1:${server.port}"

    private fun state() = JSONObject().put("on", on).put("bri", brightness).toString()

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun respond(request: RecordedRequest): MockResponse {
        // A connection opened while the controller was answering can still be
        // in the client's pool. Closing after the request, with nothing
        // written, is what an unplugged controller looks like from there.
        if (!reachable) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

        return when (request.path) {
            "/json/info" -> json(
                JSONObject().put("ver", "16.0.1").put("name", name).put("mac", mac).toString()
            )

            "/json/cfg" -> json(config)

            "/2d-gaps.json" -> gaps?.let(::json) ?: MockResponse().setResponseCode(404)

            "/json/state" -> {
                if (request.method == "POST") apply(JSONObject(request.body.readUtf8()))
                json(state())
            }

            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun apply(body: JSONObject) {
        if (body.has("on")) on = body.getBoolean("on")
        if (body.has("bri")) brightness = body.getInt("bri")
        val segment = body.optJSONObject("seg") ?: return
        val leds = segment.optJSONArray("i") ?: return
        // The first three entries black the whole wall out; after them come
        // index/colour pairs, one per lit hold.
        val lit = mutableMapOf<Int, String>()
        var i = 3
        while (i + 1 < leds.length()) {
            lit[leds.getInt(i)] = leds.getString(i + 1)
            i += 2
        }
        pushed += lit
    }

    override fun close() = server.shutdown()

    companion object {
        /** One 3x3 panel: nine holds, small enough to fit any screen. */
        const val THREE_BY_THREE = """
            {"hw":{"led":{"matrix":{"panels":[
                {"b":false,"r":false,"v":false,"s":false,"x":0,"y":0,"h":3,"w":3}
            ]}}}}
        """

        /** A plain strip with no matrix, which the app has to refuse. */
        const val ONE_DIMENSIONAL = """{"hw":{"led":{"total":30}}}"""

        /** WLED's gap file for a 3x3 wall with no light in the middle. */
        const val CENTRE_GAP = "[1,1,1,1,-1,1,1,1,1]"
    }
}
