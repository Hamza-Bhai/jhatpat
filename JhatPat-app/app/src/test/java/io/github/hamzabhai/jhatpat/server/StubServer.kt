package io.github.hamzabhai.jhatpat.server

/** A stand-in for the Go server, run in a child JVM by [ServerProcessTest]. */
object StubServer {
    private const val LINE =
        """{"port":1234,"hostToken":"stubtoken","pin":"4321","secret":"stubsecret","ip":"192.168.43.1","hostUrl":"http://localhost:1234/host?t=stubtoken"}"""

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "ok" -> { println(LINE); drainStdin() }
            "noisy" -> { println("starting..."); System.err.println("warning: something"); println(LINE); drainStdin() }
            "env" -> { println(LINE.replace("stubtoken", System.getenv("JP_TEST_TOKEN") ?: "missing")); drainStdin() }
            "crash" -> { System.err.println("boom: cannot bind port"); System.exit(3) }
            "silent" -> Thread.sleep(30_000)
            else -> System.exit(4)
        }
    }

    private fun drainStdin() {
        while (System.`in`.read() != -1) { /* wait for the parent to close the pipe */ }
    }
}
