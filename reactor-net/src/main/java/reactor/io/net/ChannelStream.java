/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package reactor.io.net;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.Environment;
import reactor.core.Dispatcher;
import reactor.core.support.Assert;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.io.buffer.Buffer;
import reactor.io.codec.Codec;
import reactor.rx.Promise;
import reactor.rx.Promises;
import reactor.rx.Stream;
import reactor.rx.Streams;
import reactor.rx.broadcast.Broadcaster;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * An abstract {@link Channel} implementation that handles the basic interaction and behave as a {@link
 * reactor.rx.Stream}.
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public abstract class ChannelStream<IN, OUT> extends Stream<IN> implements Channel<IN, OUT> {

	protected final Logger log = LoggerFactory.getLogger(getClass());

	protected final PeerStream<IN, OUT>                   peer;
	protected final Broadcaster<IN>                   contentStream;
	private final Environment env;

	private final Dispatcher  ioDispatcher;
	private final Dispatcher  eventsDispatcher;

	private final Function<Buffer, IN>  decoder;
	private final Function<OUT, Buffer> encoder;
	private final long                  prefetch;

	protected  Broadcaster<Publisher<? extends OUT>> writerStream;

	protected ChannelStream(final @Nonnull Environment env,
	                        @Nullable Codec<Buffer, IN, OUT> codec,
	                        long prefetch,
	                        @Nonnull PeerStream<IN, OUT> peer,
	                        @Nonnull Dispatcher ioDispatcher,
	                        @Nonnull Dispatcher eventsDispatcher) {
		Assert.notNull(env, "IO Dispatcher cannot be null");
		Assert.notNull(env, "Events Reactor cannot be null");
		this.env = env;
		this.prefetch = prefetch;
		this.ioDispatcher = ioDispatcher;
		this.peer = peer;
		this.eventsDispatcher = eventsDispatcher;
		this.contentStream = Broadcaster.<IN>create(env, eventsDispatcher);

		if (null != codec) {
			this.decoder = codec.decoder(contentStream);
			this.encoder = codec.encoder();
		} else {
			this.decoder = null;
			this.encoder = null;
		}
	}

	@Override
	public void subscribe(Subscriber<? super IN> s) {
		contentStream.subscribe(s);
	}

	final public Subscriber<IN> in() {
		return contentStream;
	}

	@Override
	final public ChannelStream<IN, OUT> sink(Publisher<? extends OUT> source) {
		synchronized (this){
			if(writerStream == null){
				writerStream = Broadcaster.create(env, eventsDispatcher);
				peer.subscribeChannelHandlers(Streams.concat(writerStream), this);
			}
		}
		writerStream.onNext(source);
		return this;
	}

	final public Promise<Void> sendBuffer(Buffer data) {
		if(data == null) return Promises.success(env, eventsDispatcher, null);
		final Promise<Void> d = Promises.ready(env, eventsDispatcher);
		send(data, d);
		return d;
	}

	@Override
	final public Promise<Void> send(OUT data) {
		if(data == null) return Promises.success(env, eventsDispatcher, null);
		final Promise<Void> d = Promises.ready(env, eventsDispatcher);
		send(data, d);
		return d;
	}

	final public Promise<Void> echoBuffer(Buffer data) {
		if(data == null) return Promises.success(env, eventsDispatcher, null);
		final Promise<Void> d = Promises.ready(env, eventsDispatcher);
		ioDispatcher.dispatch(data, new BufferWriteConsumer(d, false), null);
		return d;
	}

	@Override
	final public Promise<Void> echo(OUT data) {
		if(data == null) return Promises.success(env, eventsDispatcher, null);
		Promise<Void> d = Promises.ready(env, eventsDispatcher);
		ioDispatcher.dispatch(data, new WriteConsumer(d, false), null);
		return d;
	}

	@Override
	public final Environment getEnvironment() {
		return env;
	}

	@Override
	public final Dispatcher getDispatcher() {
		return eventsDispatcher;
	}

	public final Dispatcher getIODispatcher() {
		return ioDispatcher;
	}

	public final Function<Buffer, IN> getDecoder() {
		return decoder;
	}

	public final Function<OUT, Buffer> getEncoder() {
		return encoder;
	}

	final public long getPrefetch() {
		return prefetch;
	}

	@Override
	public void close() {
		notifyClose();
	}


	Consumer<OUT> writeThrough() {
		return new WriteConsumer(null, false);
	}

	/**
	 * Send data on this connection. The current codec (if any) will be used to encode the data to a {@link
	 * reactor.io.buffer.Buffer}. The given callback will be invoked when the write has completed.
	 *
	 * @param data       The outgoing data.
	 * @param onComplete The callback to invoke when the write is complete.
	 */
	final protected void send(OUT data, final Subscriber<Void> onComplete) {
		ioDispatcher.dispatch(data, new WriteConsumer(onComplete, true), null);
	}

	final protected void send(Buffer data, final Subscriber<Void> onComplete) {
		ioDispatcher.dispatch(data, new BufferWriteConsumer(onComplete, true), null);
	}

	final protected void notifyError(Throwable throwable) {
		contentStream.onError(throwable);
	}

	final protected void notifyClose() {
		Broadcaster<Publisher<? extends OUT>> writers;
		synchronized (this){
			writers = writerStream;
		}
		if(writers != null){
			writers.onComplete();
		}
	}


	Publisher<? extends OUT> head() {
		return null;
	}

	/**
	 * Subclasses must implement this method to perform the actual IO of writing data to the connection.
	 *
	 * @param data       The data to write, as a {@link Buffer}.
	 * @param onComplete The callback to invoke when the write is complete.
	 */
	protected void write(Buffer data, Subscriber<?> onComplete, boolean flush) {
		write(data.byteBuffer(), onComplete, flush);
	}

	/**
	 * Subclasses must implement this method to perform the actual IO of writing data to the connection.
	 *
	 * @param data       The data to write.
	 * @param onComplete The callback to invoke when the write is complete.
	 * @param flush      whether to flush the underlying IO channel
	 */
	protected abstract void write(ByteBuffer data, Subscriber<?> onComplete, boolean flush);

	/**
	 * Subclasses must implement this method to perform the actual IO of writing data to the connection.
	 *
	 * @param data       The data to write.
	 * @param onComplete The callback to invoke when the write is complete.
	 * @param flush      whether to flush the underlying IO channel
	 */
	protected abstract void write(Object data, Subscriber<?> onComplete, boolean flush);

	/**
	 * Subclasses must implement this method to perform IO flushes.
	 */
	protected abstract void flush();

	final class WriteConsumer implements Consumer<OUT> {
		private final Subscriber<Void> onComplete;
		private final boolean          flush;

		@Override
		public void accept(OUT data) {
			try {
				if (null != encoder) {
					Buffer bytes = encoder.apply(data);
					if (bytes.remaining() > 0) {
						write(bytes, onComplete, flush);
					}
				} else {
					write(data, onComplete, flush);
				}
			} catch (Throwable t) {
				if (null != onComplete) {
					onComplete.onError(t);
				}
			}
		}

		WriteConsumer(Subscriber<Void> onComplete, boolean autoFlush) {
			this.onComplete = onComplete;
			this.flush = autoFlush;
		}
	}

	private final class BufferWriteConsumer implements Consumer<Buffer> {
		private final Subscriber<?> onComplete;
		private final boolean       flush;

		private BufferWriteConsumer(Subscriber<Void> onComplete, boolean autoFlush) {
			this.onComplete = onComplete;
			this.flush = autoFlush;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void accept(Buffer data) {
			try {
				if (null != encoder) {
					Buffer bytes = encoder.apply((OUT) data);
					if (bytes.remaining() > 0) {
						write(bytes, onComplete, flush);
					}
				} else {
					write(data, onComplete, flush);
				}
			} catch (Throwable t) {
				if (null != onComplete) {
					onComplete.onError(t);
				}
			}
		}
	}

}
