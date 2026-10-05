package af.shizuku.manager.harness

import af.shizuku.manager.adb.AdbMessage
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import af.shizuku.manager.adb.AdbProtocol.A_AUTH
import af.shizuku.manager.adb.AdbProtocol.A_CLSE
import af.shizuku.manager.adb.AdbProtocol.A_CNXN
import af.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import af.shizuku.manager.adb.AdbProtocol.A_OKAY
import af.shizuku.manager.adb.AdbProtocol.A_OPEN
import af.shizuku.manager.adb.AdbProtocol.A_VERSION
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A loopback adbd speaking the plain (non-TLS) AUTH handshake. Each connection that offers the key
 * while it is not yet authorised counts as one dialog ([offers]) and then waits, as adbd does, for
 * the user's answer: [accept], [reject] (adbd closes the connection) or nothing at all. Connections
 * that close before saying anything (the starter's port probes) are ignored.
 */
class FakeAdbd(
    private val onShell: (String) -> Unit = {},
) : Closeable {
    enum class Answer { ACCEPT, REJECT, SILENT }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    private val offerCount = AtomicInteger(0)
    val offers: Int get() = offerCount.get()
    private val offered = Semaphore(0)
    private val answers = LinkedBlockingQueue<Answer>()
    private val open = CopyOnWriteArrayList<Socket>()

    /** The key has been accepted once; adbd then answers its signature without a dialog. */
    @Volatile
    var authorized = false

    /** Runs on adbd's thread when a client says hello, before it can record or offer anything. */
    @Volatile
    var onHello: () -> Unit = {}

    init {
        Thread({ acceptLoop() }, "fake-adbd").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Waits for the next not-yet-awaited key offer, calling [pump] while it waits (the caller may
     * be the thread other work needs, e.g. Robolectric's main looper).
     */
    fun awaitOffer(
        timeoutMs: Long = 30_000,
        pump: () -> Unit = {},
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!offered.tryAcquire(10, TimeUnit.MILLISECONDS)) {
            check(System.currentTimeMillis() < deadline) { "no key offer within ${timeoutMs}ms (offers=$offers)" }
            pump()
        }
    }

    fun accept() = answers.put(Answer.ACCEPT)

    fun reject() = answers.put(Answer.REJECT)

    fun silent() = answers.put(Answer.SILENT)

    /** Drops every connection, as a dying peer process would. */
    fun dropAll() = open.forEach { runCatching { it.close() } }

    override fun close() {
        runCatching { server.close() }
        dropAll()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            open += s
            Thread({ serve(s) }, "fake-adbd-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            val input = DataInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val hello = read(input) ?: return
            if (hello.command != A_CNXN) return
            onHello()
            write(output, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20)))
            val signature = read(input) ?: return
            check(signature.command == A_AUTH && signature.arg0 == ADB_AUTH_SIGNATURE) { "expected the token signature" }
            if (!authorized) {
                write(output, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20)))
                val key = read(input) ?: return
                check(key.command == A_AUTH && key.arg0 == ADB_AUTH_RSAPUBLICKEY) { "expected the public key" }
                offerCount.incrementAndGet()
                offered.release()
                when (awaitAnswer(s)) {
                    Answer.ACCEPT -> authorized = true
                    Answer.REJECT, null -> return
                    Answer.SILENT -> {
                        while (read(input) != null) Unit
                        return
                    }
                }
            }
            write(output, AdbMessage(A_CNXN, A_VERSION, A_MAXDATA, "device::"))
            while (true) {
                val message = read(input) ?: return
                if (message.command != A_OPEN) continue
                val command = message.data?.let { String(it).trimEnd('\u0000') }.orEmpty()
                if (command.startsWith("shell:")) onShell(command)
                write(output, AdbMessage(A_OKAY, REMOTE_ID, message.arg0, ByteArray(0)))
                write(output, AdbMessage(A_CLSE, REMOTE_ID, message.arg0, ByteArray(0)))
            }
        } catch (_: IOException) {
        } finally {
            open -= s
            runCatching { s.close() }
        }
    }

    // Polls rather than blocking on the queue so that a connection the client has already closed
    // gives up and cannot consume an answer meant for a later one. Null: the peer went away.
    private fun awaitAnswer(s: Socket): Answer? {
        s.soTimeout = 100
        try {
            while (true) {
                answers.poll()?.let { return it }
                try {
                    if (s.getInputStream().read() == -1) return null
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            if (!s.isClosed) s.soTimeout = 0
        }
    }

    private fun read(input: DataInputStream): AdbMessage? {
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val crc = b.int
        val magic = b.int
        val data = ByteArray(length).also { input.readFully(it) }
        return AdbMessage(command, arg0, arg1, length, crc, magic, data).also { it.validateOrThrow() }
    }

    private fun write(
        output: OutputStream,
        message: AdbMessage,
    ) {
        output.write(message.toByteArray())
        output.flush()
    }

    private companion object {
        const val REMOTE_ID = 100
    }
}
