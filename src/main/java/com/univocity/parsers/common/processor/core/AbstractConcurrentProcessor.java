/*******************************************************************************
 * Copyright 2015 uniVocity Software Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.univocity.parsers.common.processor.core;

import com.univocity.parsers.common.*;

import java.util.concurrent.*;

/**
 * A {@link Processor} implementation to perform row processing tasks in parallel. The {@code ConcurrentRowProcessor} wraps another {@link Processor}, and collects rows read from the input.
 * The actual row processing is performed in by wrapped {@link Processor} in a separate thread.
 *
 * @author uniVocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 *
 * @see AbstractParser
 * @see Processor
 */
public abstract class AbstractConcurrentProcessor<T extends Context> implements Processor<T> {

	private final Processor processor;

	private boolean ended = false;

	private static class Node {
		public Node(String[] row) {
			this.row = row;
		}

		public final String[] row;
		public Node next;
	}

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private long rowCount;

	private Future<Void> process;

	private ParsingContext context;
	private Node inputQueue;
	private volatile Node outputQueue;
	private final int limit;
	private volatile long input;
	private volatile long output;
	private final Object lock;

	/**
	 * Creates a non-blocking {@code AbstractConcurrentProcessor}, to perform processing of rows parsed from the input in a separate thread.
	 * @param processor a regular {@link Processor} implementation which will be executed in a separate thread.
	 */
	public AbstractConcurrentProcessor(Processor<T> processor) {
		this(processor, -1);


	}

	/**
	 * Creates a blocking {@code AbstractConcurrentProcessor}, to perform processing of rows parsed from the input in a separate thread
	 * Will block if input queue has supplied number elements.
	 * @param processor a regular {@link Processor} implementation which will be executed in a separate thread.
	 * @param limit the maximum amount that the input queue before accepting more input
	 */
	public AbstractConcurrentProcessor(Processor<T> processor, int limit) {
		if (processor == null) {
			throw new IllegalArgumentException("Row processor cannot be null");
		}
		this.processor = processor;
		input = 0;
		output = 0;
		lock = new Object();
		this.limit = limit;
	}

	@Override
	public final void processStarted(T context) {
		processor.processStarted(context);

		this.context = new ParsingContextWrapper(context) {
			@Override
			public long currentRecord() {
				return rowCount;
			}
		};

		startProcess();
	}

	private void startProcess() {
		ended = false;
		rowCount = 0;

		process = executor.submit(new Callable<Void>() {

			@Override
			public Void call() {
				while (outputQueue == null && !ended) {
					Thread.yield();
				}

				while (!ended) {
					rowCount++;


					processor.rowProcessed(outputQueue.row, context);
					while (outputQueue.next == null) {
						if (ended && outputQueue.next == null) {
							return null;
						}
					}
					outputQueue = outputQueue.next;
					output++;
					if (limit > 0) {
						synchronized (lock) {
							lock.notify();
						}
					}



				}

				while (outputQueue != null) {
					rowCount++;
					processor.rowProcessed(outputQueue.row, context);
					outputQueue = outputQueue.next;
				}

				return null;
			}

		});
	}

	@Override
	public final void rowProcessed(String[] row, T context) {
		if (inputQueue == null) {
			inputQueue = new Node(row);
			outputQueue = inputQueue;
		} else {
			if (limit > 0) {
				synchronized (lock) {
					try {
						if (input - output >= limit) {
							lock.wait();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
			inputQueue.next = new Node(row);
			inputQueue = inputQueue.next;
		}
		input++;
	}

	@Override
	public final void processEnded(T context) {
		processor.processEnded(context);
		ended = true;
		synchronized (lock) {
			lock.notify();
		}

		try {
			process.get();
		} catch (ExecutionException e) {
			throw new DataProcessingException("Error executing process", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
