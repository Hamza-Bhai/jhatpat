package io.github.hamzabhai.jhatpat.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ServerStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs the JhatPat server binary as a child process. The server prints one JSON startup line
 * (see [StartupInfo]); closing its standard input makes it exit (`-exit-on-stdin-close`).
 */
class ServerProcess(
    private val binary: File,
    private val args: List<String>,
    private val env: Map<String, String> = emptyMap(),
) {
    @Volatile
    private var process: Process? = null

    val isAlive: Boolean get() = process?.isAlive == true

    /** Starts the server and waits for its startup line. Throws [ServerStartException] on failure. */
    suspend fun start(timeoutMs: Long = 10_000): StartupInfo = withContext(Dispatchers.IO) { startBlocking(timeoutMs) }

    private fun startBlocking(timeoutMs: Long): StartupInfo {
        val builder = ProcessBuilder(listOf(binary.path) + args).redirectErrorStream(true)
        builder.environment().putAll(env)
        val child = try {
            builder.start()
        } catch (e: IOException) {
            throw ServerStartException("could not run ${binary.path}: ${e.message}", e)
        }
        process = child

        val ready = CompletableFuture<StartupInfo>()
        val tail = ArrayDeque<String>()
        fun output(): String = synchronized(tail) { tail.joinToString("\n") }

        Thread({
            try {
                child.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > 40) tail.removeFirst()
                    }
                    if (!ready.isDone && line.trimStart().startsWith("{")) {
                        try {
                            ready.complete(StartupInfo.parse(line))
                        } catch (_: IllegalArgumentException) {
                            // not the startup line; keep reading
                        }
                    }
                }
            } catch (_: IOException) {
                // the pipe closed with the process
            }
            if (!ready.isDone) {
                child.waitFor(2, TimeUnit.SECONDS)
                val code = if (child.isAlive) "still running" else "exit code ${child.exitValue()}"
                ready.completeExceptionally(ServerStartException("JhatPat's server stopped before it was ready ($code). Output:\n${output()}"))
            }
        }, "jhatpat-server-output").apply { isDaemon = true }.start()

        try {
            return ready.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            stop()
            throw ServerStartException("JhatPat's server did not start within ${timeoutMs / 1000.0} s. Output:\n${output()}", e)
        } catch (e: ExecutionException) {
            stop()
            throw (e.cause as? ServerStartException) ?: ServerStartException("JhatPat's server failed to start: ${e.cause?.message}", e)
        }
    }

    /** Asks the server to exit by closing its input, then forces it. Safe to call more than once. */
    fun stop() {
        val child = process ?: return
        try {
            child.outputStream.close()
        } catch (_: IOException) {
            // already closed
        }
        if (!child.waitFor(3, TimeUnit.SECONDS)) {
            child.destroy()
            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }
}
