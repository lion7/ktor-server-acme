package com.github.lion7.ktor.server.acme

import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.component.Container

object JettyAnlpRegistrar : Container.Listener {

    private const val acmeProtocol = "acme-tls/1"

    override fun beanAdded(parent: Container, child: Any?) {
        if (child is ServerConnector) {
            val sslConnectionFactory: SslConnectionFactory? = child.getConnectionFactory(SslConnectionFactory::class.java)
            if (sslConnectionFactory != null) {
                // this ensures that the ACME ANLP protocol can be negotiated by Jetty
                child.addConnectionFactory(DelegatingSslConnectionFactory(acmeProtocol, sslConnectionFactory))
            }
        }
    }

    override fun beanRemoved(parent: Container, child: Any?) {
        if (child is ServerConnector) {
            child.removeConnectionFactory(acmeProtocol)
        }
    }
}
