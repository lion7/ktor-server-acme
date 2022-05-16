package com.github.lion7.ktor.server.acme

import org.eclipse.jetty.util.component.LifeCycle

class JettyStartedListener(private val onStarted: () -> Unit) : LifeCycle.Listener {

    override fun lifeCycleStarted(event: LifeCycle?) {
        onStarted()
    }

}
