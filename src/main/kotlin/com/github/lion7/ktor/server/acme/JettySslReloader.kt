package com.github.lion7.ktor.server.acme

import io.ktor.util.collections.*
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.component.Container
import org.eclipse.jetty.util.ssl.SslContextFactory

class JettySslReloader : Container.Listener {

    private val sslContextFactories = ConcurrentSet<SslContextFactory>()

    override fun beanAdded(parent: Container, child: Any?) {
        if (child is ServerConnector) {
            val sslConnectionFactory: SslConnectionFactory? = child.getConnectionFactory(SslConnectionFactory::class.java)
            if (sslConnectionFactory != null) {
                sslContextFactories.add(sslConnectionFactory.sslContextFactory)
            }
        }
    }

    override fun beanRemoved(parent: Container, child: Any?) {
        if (child is ServerConnector) {
            val sslConnectionFactory: SslConnectionFactory? = child.getConnectionFactory(SslConnectionFactory::class.java)
            if (sslConnectionFactory != null) {
                sslContextFactories.remove(sslConnectionFactory.sslContextFactory)
            }
        }
    }

    fun reloadSslContextFactories() {
        sslContextFactories.forEach {
            it.reload { }
        }
    }
}
