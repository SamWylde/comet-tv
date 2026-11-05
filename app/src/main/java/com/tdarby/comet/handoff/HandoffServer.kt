package com.tdarby.comet.handoff

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small HTTP server on a plain [ServerSocket], bound to one LAN address on a random port. Kept thin:
 * accept, parse ([HandoffRequestParser]), route ([HandoffRouter]), write. It serves at most
 * [MAX_CONNECTIONS] connections at once, gives each a short read timeout and an overall deadline, and
 * [stop] closes the listening socket and every open connection, which ends all of its threads.
 * It also stops by itself when the session expires or locks. The listener's methods run on server threads.
 */
class HandoffServer(
    private val bindAddress: InetAddress,
    private val session: HandoffSession,
    private val router: HandoffRouter,
    private val listener: Listener
) {
    enum class EndReason { EXPIRED, LOCKED, FAILED }

    interface Listener {
        fun onReady(port: Int)

        /** Called once, only when the server ended by itself (not after [stop]). */
        fun onEnded(reason: EndReason)
    }

    private val serverSocket = ServerSocket()
    private val stopped = AtomicBoolean(false)
    private val connections = HashSet<Socket>()
    private val activeConnections = AtomicInteger(0)

    /** Binds and serves on a background thread (sockets are not opened on the caller's thread). */
    fun start() {
        Thread(::acceptLoop, "comet-handoff-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        shutdown()
    }

    private fun acceptLoop() {
        try {
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(bindAddress, 0), BACKLOG)
            serverSocket.soTimeout = ACCEPT_POLL_MS
        } catch (_: IOException) {
            end(EndReason.FAILED)
            return
        }
        listener.onReady(serverSocket.localPort)
        while (!stopped.get()) {
            when (session.state) {
                HandoffSession.State.EXPIRED -> return end(EndReason.EXPIRED)
                HandoffSession.State.LOCKED -> return end(EndReason.LOCKED)
                HandoffSession.State.ACTIVE -> Unit
            }
            val client = try {
                serverSocket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return end(EndReason.FAILED)
            }
            dispatch(client)
        }
    }

    private fun dispatch(client: Socket) {
        if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
            activeConnections.decrementAndGet()
            refuse(client)
            return
        }
        synchronized(connections) { connections.add(client) }
        // stop() may have run between accept() and the add above; it would not have seen this socket.
        if (stopped.get()) {
            release(client)
            return
        }
        try {
            Thread({ serve(client) }, "comet-handoff-connection").apply {
                isDaemon = true
                start()
            }
        } catch (_: Throwable) {
            release(client)
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val input = DeadlineInputStream(client.getInputStream(), CONNECTION_DEADLINE_MS)
            val response = when (val parsed = HandoffRequestParser.parse(input)) {
                is ParseResult.Ok -> router.handle(parsed.request)
                is ParseResult.Rejected -> HandoffResponse.error(parsed.status)
                ParseResult.NoRequest -> null
            }
            if (response != null) {
                client.getOutputStream().apply {
                    write(response.toBytes())
                    flush()
                }
            }
        } catch (_: Exception) {
            // A timeout, a reset or a bug in one request ends that connection only.
        } finally {
            release(client)
        }
        if (session.state == HandoffSession.State.LOCKED) end(EndReason.LOCKED)
    }

    private fun refuse(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            client.getOutputStream().write(HandoffResponse.error(503).toBytes())
        } catch (_: Exception) {
            // Nothing to do; the connection is closed next.
        } finally {
            closeQuietly(client)
        }
    }

    private fun release(client: Socket) {
        val wasTracked = synchronized(connections) { connections.remove(client) }
        closeQuietly(client)
        if (wasTracked) activeConnections.decrementAndGet()
    }

    /** Returns true for the one call that actually shut the server down. */
    private fun shutdown(): Boolean {
        if (!stopped.compareAndSet(false, true)) return false
        closeQuietly(serverSocket)
        val open = synchronized(connections) { ArrayList(connections) }
        open.forEach(::closeQuietly)
        return true
    }

    private fun end(reason: EndReason) {
        if (shutdown()) listener.onEnded(reason)
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Already closed or reset.
        }
    }

    /** Bounds the whole request, not just each read, so a client can't trickle bytes forever. */
    private class DeadlineInputStream(private val inner: InputStream, budgetMs: Long) : InputStream() {
        private val deadline = System.nanoTime() + budgetMs * 1_000_000

        override fun read(): Int {
            check()
            return inner.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            return inner.read(b, off, len)
        }

        private fun check() {
            if (System.nanoTime() - deadline > 0) throw SocketTimeoutException("request took too long")
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 2
        const val BACKLOG = 4
        const val READ_TIMEOUT_MS = 5_000
        const val CONNECTION_DEADLINE_MS = 10_000L
        const val ACCEPT_POLL_MS = 1_000
    }
}
