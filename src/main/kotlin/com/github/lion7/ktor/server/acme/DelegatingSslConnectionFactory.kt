package com.github.lion7.ktor.server.acme

import org.eclipse.jetty.io.Connection
import org.eclipse.jetty.io.EndPoint
import org.eclipse.jetty.server.AbstractConnectionFactory
import org.eclipse.jetty.server.Connector
import org.eclipse.jetty.server.SslConnectionFactory

class DelegatingSslConnectionFactory(protocol: String, private val delegate: SslConnectionFactory) : AbstractConnectionFactory(protocol) {

    override fun newConnection(connector: Connector?, endPoint: EndPoint?): Connection =
        delegate.newConnection(connector, endPoint)

}
