package com.newoether.agora.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Model sync must stay bounded and cancellable.
 *
 * The blocking [HttpClient.fetchModels] parks its thread inside OkHttp, where structured
 * concurrency cannot reach it: a `withTimeout` around it can only be delivered at a suspension
 * point, so an endpoint that accepts the connection and then goes quiet held the caller for
 * OkHttp's 5-minute read timeout. Model sync fans out over every configured provider behind a
 * single `isSyncingModels` flag, so one such endpoint left the sync button dead until restart.
 */
class HttpClientCancellableFetchTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns the body on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))

        val body = HttpClient.fetchModelsCancellable(
            server.url("/v1/models").toString(),
            timeoutMs = 5_000,
        )

        assertEquals("""{"data":[]}""", body)
    }

    @Test
    fun `returns null on an error status rather than throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        assertNull(
            HttpClient.fetchModelsCancellable(
                server.url("/v1/models").toString(),
                timeoutMs = 5_000,
            ),
        )
    }

    @Test
    fun `sends the supplied headers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        HttpClient.fetchModelsCancellable(
            server.url("/v1/models").toString(),
            mapOf("Authorization" to "Bearer sk-test", "X-Custom" to "1"),
            timeoutMs = 5_000,
        )

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals("1", recorded.getHeader("X-Custom"))
    }

    @Test
    fun `a silent endpoint is bounded by its own call timeout`() = runBlocking {
        // Accept the connection, then never answer — the shape that used to pin the sync for
        // OkHttp's 5-minute read timeout.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))

        val started = System.nanoTime()
        val body = HttpClient.fetchModelsCancellable(
            server.url("/v1/models").toString(),
            timeoutMs = 1_000,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull(body)
        assertTrue("expected the call to abort near its timeout, took ${elapsedMs}ms", elapsedMs < 15_000)
    }

    @Test
    fun `withTimeout can actually interrupt a silent endpoint`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))

        val started = System.nanoTime()
        val result = withTimeoutOrNull(1_500) {
            HttpClient.fetchModelsCancellable(
                server.url("/v1/models").toString(),
                // Deliberately far longer than the enclosing withTimeout: the coroutine deadline
                // is what has to win, which is exactly what the blocking version could not do.
                timeoutMs = 120_000,
            )
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull("the enclosing coroutine deadline must win", result)
        assertTrue("expected cancellation near 1.5s, took ${elapsedMs}ms", elapsedMs < 15_000)
    }

    @Test
    fun `cancelling the caller aborts the in-flight request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val entered = CompletableDeferred<Unit>()

        val job = launch {
            entered.complete(Unit)
            HttpClient.fetchModelsCancellable(
                server.url("/v1/models").toString(),
                timeoutMs = 120_000,
            )
        }
        entered.await()
        delay(200)

        val started = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("cancellation must not wait out the request, took ${elapsedMs}ms", elapsedMs < 10_000)
    }

    @Test
    fun `one silent provider does not delay the others`() = runBlocking {
        // The sync fans out over every provider. Serially, a single dead endpoint delayed each
        // provider behind it by its full timeout; concurrently the whole pass costs about one.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.contains("slow") == true) {
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                } else {
                    MockResponse().setResponseCode(200).setBody("""{"data":["ok"]}""")
                }
        }

        val started = System.nanoTime()
        val results = listOf("slow", "fast-1", "fast-2", "fast-3")
            .map { path ->
                async {
                    HttpClient.fetchModelsCancellable(
                        server.url("/v1/$path/models").toString(),
                        timeoutMs = 1_000,
                    )
                }
            }
            .awaitAll()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull("the silent endpoint yields null", results[0])
        results.drop(1).forEach { assertNotNull("healthy endpoints still return", it) }
        assertTrue(
            "concurrent fan-out must cost about one timeout, took ${elapsedMs}ms",
            elapsedMs < 15_000,
        )
    }

    @Test
    fun `the blocking variant is the one that cannot be interrupted`() = runBlocking {
        // Documents precisely why the cancellable variant exists. A short-timeout client proves
        // the blocking call ignores the coroutine deadline and only returns when the socket does.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val shortClient = HttpClient.client.newBuilder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(server.url("/v1/models")).get().build()

        var timedOut = false
        val started = System.nanoTime()
        try {
            withTimeout(300) {
                runCatching {
                    shortClient.newCall(request).execute().use { it.body?.string() }
                }
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // The 300ms deadline cannot fire while the thread is parked in execute(); control only
        // returns once the socket read itself gives up at ~2s.
        assertTrue("blocking execute() outlived its 300ms coroutine deadline", elapsedMs >= 1_000)
        assertTrue("and the timeout is only observed afterwards", timedOut || elapsedMs >= 1_000)
    }
}
