/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.io.net.tcp.netty

import reactor.Environment
import reactor.fn.Consumer
import reactor.io.buffer.Buffer
import reactor.io.codec.PassThroughCodec
import reactor.io.codec.json.JsonCodec
import reactor.io.net.netty.tcp.NettyTcpServer
import reactor.io.net.tcp.spec.TcpServerSpec
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
class NettyTcpServerSpec extends Specification {

	static final int port = 26874
	Environment env

	def setup() {
		env = new Environment()
	}

	def "NettyTcpServer responds to requests from clients"() {
		given: "a simple TcpServer"
		def stopLatch = new CountDownLatch(1)
		def dataLatch = new CountDownLatch(1)
		def server = new TcpServerSpec<Buffer, Buffer>(NettyTcpServer).
				env(env).
				listen(port).
				codec(new PassThroughCodec<Buffer>()).
				get()

		when: "the server is started"
			server.consume{ conn ->
				conn.consume{ data ->
					conn.echoBuffer Buffer.wrap("Hello World!")
				}
			}

		then: "the server was started"
		server.start().awaitSuccess()

		when: "data is sent"
		def client = new SimpleClient(port, dataLatch, Buffer.wrap("Hello World!"))
		client.start()
		dataLatch.await(5, TimeUnit.SECONDS)

		then: "data was recieved"
		client.data?.remaining() == 12
		new Buffer(client.data).asString() == "Hello World!"
		dataLatch.count == 0

		when: "the server is stopped"
		server.shutdown().onSuccess({
			stopLatch.countDown()
		} as Consumer<Void>)
		stopLatch.await(5, TimeUnit.SECONDS)

		then: "the server was stopped"
		stopLatch.count == 0
	}

	def "NettyTcpServer can encode and decode JSON"() {
		given: "a TcpServer with JSON defaultCodec"
		def stopLatch = new CountDownLatch(1)
		def dataLatch = new CountDownLatch(1)
		def server = new TcpServerSpec<Pojo, Pojo>(NettyTcpServer).
				env(env).
				listen(port).
				codec(new JsonCodec<Pojo, Pojo>(Pojo)).
				get()

				when: "the server is started"
					server.consume{ conn ->
						conn.consume{ pojo ->
							assert pojo.name == "John Doe"
							conn.echo new Pojo(name: "Jane Doe")
						}
					}

		then: "the server was started"
			server.start().awaitSuccess(5, TimeUnit.SECONDS)

		when: "a pojo is written"
		def client = new SimpleClient(port, dataLatch, Buffer.wrap("{\"name\":\"John Doe\"}"))
		client.start()
		dataLatch.await(5, TimeUnit.SECONDS)

		then: "data was recieved"
		client.data?.remaining() == 19
		new Buffer(client.data).asString() == "{\"name\":\"Jane Doe\"}"
		dataLatch.count == 0

		when: "the server is stopped"
		server.shutdown().onSuccess({
			stopLatch.countDown()
		} as Consumer<Void>)
		stopLatch.await(5, TimeUnit.SECONDS)

		then: "the server was stopped"
		stopLatch.count == 0
	}

	static class SimpleClient extends Thread {
		final int port
		final CountDownLatch latch
		final Buffer output
		ByteBuffer data

		SimpleClient(int port, CountDownLatch latch, Buffer output) {
			this.port = port
			this.latch = latch
			this.output = output
		}

		@Override
		void run() {
			def ch = SocketChannel.open(new InetSocketAddress(port))
			def len = ch.write(output.byteBuffer())
			assert ch.connected
			data = ByteBuffer.allocate(len)
			int read = ch.read(data)
			assert read > 0
			data.flip()
			latch.countDown()
		}
	}

	static class Pojo {
		String name
	}

}
