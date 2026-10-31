package io.github.hamzabhai.jhatpat.share

import io.github.hamzabhai.jhatpat.logic.ShareNames
import io.github.hamzabhai.jhatpat.server.Target
import io.github.hamzabhai.jhatpat.server.Uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Where shared items come from. Items are identified by their URI text, so this runs on the JVM too. */
interface ShareSource {
    fun open(id: String): InputStream?
    fun name(id: String): String?

    /** The size in bytes when the source knows it. */
    fun size(id: String): Long?
    fun mime(id: String): String?
}

/** How many items went through, and the names of the ones that did not. */
data class ShareResult(val sent: Int, val failed: List<String>)

/** Sends the items another app shared to the connected people, one after the other. */
class ShareUploader(private val uploader: Uploader, private val source: ShareSource) {
    /**
     * One bad item never stops the rest: an unreadable or failed item is listed in
     * [ShareResult.failed]. [onProgress] is called after each item with (items processed, total).
     */
    suspend fun send(ids: List<String>, to: Target, onProgress: (done: Int, total: Int) -> Unit): ShareResult =
        withContext(Dispatchers.IO) {
            var sent = 0
            val failed = mutableListOf<String>()
            ids.forEachIndexed { index, id ->
                val name = ShareNames.displayName(safely { source.name(id) }, index + 1, safely { source.mime(id) })
                val ok = try {
                    sendOne(id, name, to)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (ok) sent++ else failed += name
                onProgress(index + 1, ids.size)
            }
            ShareResult(sent, failed)
        }

    private fun sendOne(id: String, name: String, to: Target): Boolean {
        val size = source.size(id) ?: countBytes(id) ?: return false
        val stream = source.open(id) ?: return false
        return uploader.upload(name, size, to) { stream }.isSuccess
    }

    /** Some sources do not know a size up front; the upload needs it, so read once to count. */
    private fun countBytes(id: String): Long? {
        val stream = source.open(id) ?: return null
        return stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }
    }

    private inline fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
