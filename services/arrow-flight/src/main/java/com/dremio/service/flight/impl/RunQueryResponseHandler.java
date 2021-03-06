/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.flight.impl;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Provider;

import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import com.dremio.common.concurrent.NamedThreadFactory;
import com.dremio.common.utils.protos.QueryWritableBatch;
import com.dremio.exec.proto.GeneralRPCProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.RecordBatchDef;
import com.dremio.exec.record.RecordBatchLoader;
import com.dremio.exec.rpc.Acks;
import com.dremio.exec.rpc.RpcException;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.exec.work.protector.UserResponseHandler;
import com.dremio.exec.work.protector.UserResult;
import com.dremio.exec.work.protector.UserWorker;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.flight.error.mapping.DremioFlightErrorMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.NettyArrowBuf;

/**
 * The UserResponseHandler that streams results to a FlightProducer listener.
 */
public abstract class RunQueryResponseHandler implements UserResponseHandler {

  private final UserBitShared.ExternalId runExternalId;
  private final UserSession userSession;
  private final Provider<UserWorker> workerProvider;
  private final FlightProducer.ServerStreamListener clientListener;
  private final BufferAllocator allocator;
  private RecordBatchLoader recordBatchLoader;
  private volatile VectorSchemaRoot vectorSchemaRoot;

  private volatile boolean completed;

  /**
   * Enum to distinguish the status of the Flight client during data retrieval.
   */
  @VisibleForTesting
  enum FlightClientDataRetrievalStatus {
    /**
     * Client is ready to receive the next data buffer.
     */
    READY,

    /**
     * Client cancelled the data retrieval operation.
     */
    CANCELLED
  }

  RunQueryResponseHandler(UserBitShared.ExternalId runExternalId,
                          UserSession userSession,
                          Provider<UserWorker> workerProvider,
                          FlightProducer.ServerStreamListener clientListener,
                          BufferAllocator allocator) {
    this.runExternalId = runExternalId;
    this.userSession = userSession;
    this.workerProvider = workerProvider;
    this.clientListener = clientListener;
    this.clientListener.setOnCancelHandler(this::serverStreamListenerOnCancelledCallback);
    this.allocator = allocator;
    this.recordBatchLoader = new RecordBatchLoader(allocator);
    this.completed = false;
  }

  @Override
  public void sendData(RpcOutcomeListener<GeneralRPCProtos.Ack> outcomeListener, QueryWritableBatch result) {
    if (isCancelled()) {
      setOutcomeListenerStatusCancelled(outcomeListener);
      return;
    }

    final RecordBatchDef def = result.getHeader().getDef();

    final ByteBuf[] buffers = result.getBuffers();

    /**
     * RecordBatchLoader cannot reassemble ValueVectors for types which propagate more than one NettyArrowBuffer
     * (such as DataBuffers [1] and OffsetBuffers [2]) in calls to UserResponseHandler#sendData.
     * Because of this limitation, when {@link com.dremio.service.flight.impl.RunQueryResponseHandler#sendData}
     * receives more than 1 buffer instance, it must consolidate all buffers into a single buffer before
     * calling RecordBatchReader before returning data to the user.
     *
     * TODO: https://dremio.atlassian.net/browse/DX-25624
     * This task is to improve RecordBatchLoader or find some other way to handle more than one buffer
     * instance being provided for any given ValueVector, and then update this.
     *
     * [1] https://github.com/apache/arrow/blob/7b2d68570b4336308c52081a0349675e488caf11/java/vector/src/main/java/org/apache/arrow/vector/ValueVector.java#L197
     * [2] https://github.com/apache/arrow/blob/7b2d68570b4336308c52081a0349675e488caf11/java/vector/src/main/java/org/apache/arrow/vector/ValueVector.java#L204
     */
    if (null == buffers || buffers.length == 0) {
      loadEmptyBuffer(def, result.getByteCount());
    } else if (buffers.length > 1) {
      loadFromCopyOfEntireResult(result, def);
    } else {
      final ByteBuf byteBuf = buffers[0];
      /**
       * The most optimistic approach from a buffer copying perspective is to use buffers as they
       * are provided to this method directly. When a NettyArrowBuf is provided, the underlying
       * Arrow Buffer gets used directly. Other implementations will require copying the data into
       * a new Arrow Buffer first.
       */
      if (byteBuf instanceof NettyArrowBuf) {
        loadDirectlyFromNettyArrowBuf(def, (NettyArrowBuf) byteBuf);
      } else {
        loadFromCopyOfSingleBuffer(def, byteBuf);
      }
    }

    prepareVectorSchemaRoot(result.getHeader().getRowCount());
    putNextWhenClientReady(outcomeListener);
  }

  private void loadEmptyBuffer(RecordBatchDef def, long readableBytes) {
    try (final ArrowBuf arrowBuf = allocator.buffer(readableBytes)) {
      recordBatchLoader.load(def, arrowBuf);
    }
  }

  @VisibleForTesting
  void loadFromCopyOfEntireResult(QueryWritableBatch result, RecordBatchDef def) {
    try (final ArrowBuf arrowBuf = allocator.buffer((int) result.getByteCount())) {
      long arrowBufIndex = 0;
      for (ByteBuf byteBuf : result.getBuffers()) {
        final int readableBytes = byteBuf.readableBytes();
        arrowBuf.setBytes(arrowBufIndex, byteBuf.nioBuffer());
        arrowBufIndex += readableBytes;
        byteBuf.release();
      }
      recordBatchLoader.load(def, arrowBuf);
    }
  }

  private void loadFromCopyOfSingleBuffer(RecordBatchDef def, ByteBuf byteBuf) {
    try (final ArrowBuf arrowBuf = allocator.buffer(byteBuf.readableBytes())) {
      arrowBuf.setBytes(0, byteBuf.nioBuffer());
      recordBatchLoader.load(def, arrowBuf);
    } finally {
      byteBuf.release();
    }
  }

  @VisibleForTesting
  void loadDirectlyFromNettyArrowBuf(RecordBatchDef def, NettyArrowBuf byteBuf) {
    try {
      recordBatchLoader.load(def, byteBuf.arrowBuf());
    } finally {
      byteBuf.release();
    }
  }

  /**
   * Calls clientListener.putNext() when the client is ready, and handles error cases from these
   * interactions.
   *
   * @param outcomeListener The server outcomeListener.
   */
  @VisibleForTesting
  void putNextWhenClientReady(RpcOutcomeListener<GeneralRPCProtos.Ack> outcomeListener) {
    try {
      switch (clientIsReadyForData()) {
        case READY:
          clientListener.putNext();
          outcomeListener.success(Acks.OK, null);
          return;
        case CANCELLED:
          setOutcomeListenerStatusCancelled(outcomeListener);
          return;
        default:
          outcomeListener.failed(new RpcException("Unknown client status encountered."));
      }
    } catch (RpcException ex) {
      outcomeListener.failed(ex);
    }
  }

  /**
   * Initializes VectorSchemaRoot if needed, and populates the rowCount.
   */
  @VisibleForTesting
  void prepareVectorSchemaRoot(int rowCount) {
    if (vectorSchemaRoot == null) {
      final List<FieldVector> vectors = StreamSupport.stream(recordBatchLoader.spliterator(), false)
        .map(v -> (FieldVector) v.getValueVector())
        .collect(Collectors.toList());
      vectorSchemaRoot = new VectorSchemaRoot(vectors);
      clientListener.start(vectorSchemaRoot);
    }
    vectorSchemaRoot.setRowCount(rowCount);
  }

  @Override
  public void completed(UserResult result) {
    completed = true;

    try {
      handleUserResultState(result);
    } finally {
      try {
        if (null != recordBatchLoader) {
          recordBatchLoader.close();
          recordBatchLoader = null;
        }
      } finally {
        if (null != vectorSchemaRoot) {
          vectorSchemaRoot.close();
          vectorSchemaRoot = null;
        }
      }
    }
  }

  /**
   * Method to handle different QueryState(s) of the UserResult.
   *
   * @param result the UserResult with the state of the query when completed is called.
   */
  @VisibleForTesting
  void handleUserResultState(UserResult result) {
    switch (result.getState()) {
      case FAILED:
        if (result.hasException()) {
          clientListener.error(DremioFlightErrorMapper.toFlightRuntimeException(result.getException()));
        } else {
          clientListener.error(CallStatus.UNKNOWN.withDescription("Query failed but no exception was thrown.").toRuntimeException());
        }
        break;
      case CANCELED:
        if (result.hasException()) {
          clientListener.error(CallStatus.CANCELLED.withDescription(result.getException().getMessage()).withCause(result.getException()).toRuntimeException());
        } else if (!Strings.isNullOrEmpty(result.getCancelReason())) {
          clientListener.error(CallStatus.CANCELLED.withDescription(result.getCancelReason()).toRuntimeException());
        } else {
          clientListener.error(CallStatus.CANCELLED.withDescription("Query is cancelled by the server.").toRuntimeException());
        }
        break;
      case COMPLETED:
        clientListener.completed();
        break;
      default:
        final IllegalStateException ex = new IllegalStateException("Invalid state returned from Dremio RPC request.");
        clientListener.error(CallStatus.INTERNAL.withCause(ex).toRuntimeException());
        throw ex;
    }
  }

  /**
   * Callback for the listener to cancel the backend query request.
   */
  private void serverStreamListenerOnCancelledCallback() {
    if (!completed) {
      completed = true;
      workerProvider.get().cancelQuery(runExternalId, userSession.getTargetUserName());
    }
  }

  /**
   * Helper to set RpcOutcomeListener status to interrupted due to client cancellation.
   *
   * @param outcomeListener the RpcOutcomeListener to set status of.
   */
  protected void setOutcomeListenerStatusCancelled(RpcOutcomeListener<GeneralRPCProtos.Ack> outcomeListener) {
    outcomeListener.interrupted(new InterruptedException("Query is cancelled by the client."));
  }

  /**
   * Helper method to poll for readiness of the Flight client.
   * <p>
   * Note: Polling for client isReady will not be required if the Flight client's
   * OutboundStreamListener accepts a callback for when it is ready to receive more data buffers.
   * A Jira ticket is created to track the enhancement request https://issues.apache.org/jira/browse/ARROW-10106.
   *
   * @return {@code READY} if the Flight client is ready, {@code CANCELLED} if the request is cancelled by the client.
   * @throws RpcException if either a TimeoutException, an InterruptedException or an ExecutionException is
   *                      encountered while polling for the client's status.
   */
  @VisibleForTesting
  abstract FlightClientDataRetrievalStatus clientIsReadyForData() throws RpcException;

  protected abstract boolean isCancelled();

  /**
   * Always responds that clients are ready for data.
   */
  public static class BasicResponseHandler extends RunQueryResponseHandler {
    private final FlightProducer.ServerStreamListener clientListener;

    BasicResponseHandler(UserBitShared.ExternalId runExternalId, UserSession userSession,
                         Provider<UserWorker> workerProvider, FlightProducer.ServerStreamListener clientListener,
                         BufferAllocator allocator) {
      super(runExternalId, userSession, workerProvider, clientListener, allocator);
      this.clientListener = clientListener;
    }

    @Override
    FlightClientDataRetrievalStatus clientIsReadyForData() {
      return FlightClientDataRetrievalStatus.READY;
    }

    @Override
    protected boolean isCancelled() {
      return clientListener.isCancelled();
    }
  }

  /**
   * When clients report not ready, block while waiting for client to indicate that it is ready.
   */
  public static class BackpressureHandlingResponseHandler extends RunQueryResponseHandler {

    private static final long CANCEL_REQUEST_TIMER_DELAY_MILLIS = 0;

    @VisibleForTesting
    static final long CANCEL_REQUEST_TIMER_RATE_MILLIS = 250;

    @VisibleForTesting
    static final int CLIENT_READINESS_WAIT_MILLIS = 10;
    private static final long CLIENT_READINESS_TIMEOUT_MILLIS = 5000;

    private volatile boolean cancelled;
    private final Timer cancelRequestTimer;
    private final FlightProducer.ServerStreamListener clientListener;

    BackpressureHandlingResponseHandler(UserBitShared.ExternalId runExternalId, UserSession userSession,
                                        Provider<UserWorker> workerProvider,
                                        FlightProducer.ServerStreamListener clientListener, BufferAllocator allocator) {
      super(runExternalId, userSession, workerProvider, clientListener, allocator);
      this.cancelled = false;
      this.cancelRequestTimer = startCancelRequestTimer();
      this.clientListener = clientListener;
    }

    /**
     * Helper method to poll for readiness of the Flight client.
     * <p>
     * Note: Polling for client isReady will not be required if the Flight client's
     * OutboundStreamListener accepts a callback for when it is ready to receive more data buffers.
     * A Jira ticket is created to track the enhancement request https://issues.apache.org/jira/browse/ARROW-10106.
     *
     * @return {@code READY} if the Flight client is ready, {@code CANCELLED} if the request is cancelled by the client.
     * @throws RpcException if either a TimeoutException, an InterruptedException or an ExecutionException is
     *                      encountered while polling for the client's status.
     */
    @VisibleForTesting
    FlightClientDataRetrievalStatus clientIsReadyForData() throws RpcException {
      if (clientListener.isReady()) {
        return FlightClientDataRetrievalStatus.READY;
      }

      final ExecutorService executor =
        Executors.newSingleThreadExecutor(new NamedThreadFactory(Thread.currentThread().getName()
          + ":flight-client-readiness"));

      final Future<FlightClientDataRetrievalStatus> future = executor.submit(() ->
      {
        // Poll ServerStreamListener until the Flight client is ready to receive data.
        while (true) {
          sleepWhileWaitingForClientReadiness();
          if (clientListener.isCancelled()) {
            // serverStreamListenerOnCancelledCallback is responsible for cancelling the query in the backend.
            // Since the callback is set on the listener in the constructor, the callback is used by
            // the listener once a cancellation request is received. There is no need to cancel the backend
            // query in this class.
            onCancelled();
            return FlightClientDataRetrievalStatus.CANCELLED;
          } else if (clientListener.isReady()) {
            return FlightClientDataRetrievalStatus.READY;
          }
        }
      });

      return handleFuture(future, executor, CLIENT_READINESS_TIMEOUT_MILLIS);
    }

    @Override
    protected boolean isCancelled() {
      return cancelled;
    }

    /**
     * Handles future that polls Flight client readiness.
     *
     * @param future   the future that polls Flight client readiness.
     * @param executor the executor service the polling job runs on.
     * @param timeout  the timeout in milliseconds.
     * @return {@code READY} if the Flight client is ready, {@code CANCELLED} if the request is cancelled by the client.
     * @throws RpcException if either a TimeoutException, an InterruptedException or an ExecutionException is
     *                      encountered while polling for the client's status.
     */
    @VisibleForTesting
    FlightClientDataRetrievalStatus handleFuture(Future<FlightClientDataRetrievalStatus> future,
                                                 ExecutorService executor, long timeout) throws RpcException {
      try {
        return future.get(timeout, TimeUnit.MILLISECONDS);
      } catch (TimeoutException ex) {
        throw new RpcException("Timeout while polling for readiness of the Flight client.", ex);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new RpcException(ex);
      } catch (ExecutionException ex) {
        throw new RpcException("Encountered error while polling for readiness of the Flight client.", ex.getCause());
      } finally {
        future.cancel(true);
        executor.shutdownNow();
      }
    }

    @VisibleForTesting
    protected void sleepWhileWaitingForClientReadiness() throws InterruptedException {
      Thread.sleep(CLIENT_READINESS_WAIT_MILLIS);
    }

    @Override
    public void completed(UserResult result) {
      cancelRequestTimer.cancel();

      super.completed(result);
    }

    /**
     * Helper to start a background Timer task to check for a cancel request from the Flight client.
     * The task then delegate cancel request handling once a cancellation request is received.
     */
    private Timer startCancelRequestTimer() {
      final Timer timer = new Timer();
      timer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          if (clientListener.isCancelled()) {
            onCancelled();
          }
        }
      }, CANCEL_REQUEST_TIMER_DELAY_MILLIS, CANCEL_REQUEST_TIMER_RATE_MILLIS);
      return timer;
    }

    /**
     * Helper to set the cancelled flag to true and to stop the cancel request polling timer task
     * once a cancel request from the Flight client is received.
     */
    protected void onCancelled() {
      cancelled = true;
      cancelRequestTimer.cancel();
    }
  }
}
