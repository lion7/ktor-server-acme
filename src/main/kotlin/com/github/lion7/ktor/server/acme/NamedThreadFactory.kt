package com.github.lion7.ktor.server.acme

import java.util.concurrent.ThreadFactory
import kotlin.concurrent.thread

class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private var counter = 0
    override fun newThread(r: Runnable): Thread = thread(start = false, isDaemon = true, name = prefix + '-' + counter++) { r.run() }
}
