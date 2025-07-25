/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.operators.window.tvf.common;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.MeterView;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.operators.InternalTimer;
import org.apache.flink.streaming.api.operators.InternalTimerService;
import org.apache.flink.streaming.api.operators.KeyContext;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.TimestampedCollector;
import org.apache.flink.streaming.api.operators.Triggerable;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.operators.TableStreamOperator;
import org.apache.flink.table.runtime.operators.window.tvf.slicing.SlicingSyncStateWindowProcessor;
import org.apache.flink.table.runtime.operators.window.tvf.unslicing.UnslicingSyncStateWindowProcessor;

import java.util.Collections;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * We divide windows into 2 categories: Aligned Windows and Unaligned Windows.
 *
 * <h3>Concept of Aligned Window and Unaligned Window</h3>
 *
 * <p>Aligned Windows are windows have predetermined window boundaries and windows can be divided
 * into finite number of non-overlapping chunks. The boundary of an aligned window is determined
 * independently from the time characteristic of the data stream, or messages it receives. For
 * example, hopping (sliding) window is an aligned window as the window boundaries are predetermined
 * based on the window size and slide. Aligned windows include tumbling, hopping, cumulative
 * windows.
 *
 * <p>Unaligned Windows are windows determined dynamically based on elements. For example, session
 * window is an unaligned window as the window boundaries are determined based on the messages
 * timestamps and their correlations. Currently, unaligned windows include session window only.
 *
 * <p>Because aligned windows can be divided into finite number of non-overlapping chunks (a.k.a.
 * slices), which can apply efficient processing to share intermediate results.
 *
 * <pre>
 * Window
 * |
 * +-- Aligned Window (Slicing Window)
 * |    |
 * |    +-- Tumble (Slice Unshared Window)
 * |    |
 * |    +-- Hop (Slice Shared Window)
 * |    |
 * |    +-- Cumulate (Slice Shared Window)
 * |
 * +-- Unaligned Window (Unslice Window)
 *      |
 *      +-- Session
 *
 * </pre>
 *
 * <p>Note: currently, {@link WindowAggOperator} doesn't support early-fire and late-arrival. Thus
 * late elements (elements belong to emitted windows) will be simply dropped.
 *
 * <p>See more in {@link SlicingSyncStateWindowProcessor} and {@link
 * UnslicingSyncStateWindowProcessor}.
 */
@Internal
public final class WindowAggOperator<K, W> extends TableStreamOperator<RowData>
        implements OneInputStreamOperator<RowData, RowData>, Triggerable<K, W>, KeyContext {

    private static final long serialVersionUID = 1L;

    private static final String LATE_ELEMENTS_DROPPED_METRIC_NAME = "numLateRecordsDropped";
    private static final String LATE_ELEMENTS_DROPPED_RATE_METRIC_NAME = "lateRecordsDroppedRate";
    private static final String WATERMARK_LATENCY_METRIC_NAME = "watermarkLatency";

    /** The concrete window operator implementation. */
    private final SyncStateWindowProcessor<W> windowProcessor;

    private final boolean isEventTime;

    // ------------------------------------------------------------------------

    /** This is used for emitting elements with a given timestamp. */
    private transient TimestampedCollector<RowData> collector;

    /** The service to register timers. */
    private transient InternalTimerService<W> internalTimerService;

    /** The tracked processing time triggered last time. */
    private transient long lastTriggeredProcessingTime;

    /** The operator state to store watermark. */
    private transient ListState<Long> watermarkState;

    // ------------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------------

    private transient Counter numLateRecordsDropped;
    private transient Meter lateRecordsDroppedRate;
    private transient Gauge<Long> watermarkLatency;

    public WindowAggOperator(SyncStateWindowProcessor<W> windowProcessor, boolean isEventTime) {
        this.windowProcessor = windowProcessor;
        this.isEventTime = isEventTime;
    }

    @Override
    public void open() throws Exception {
        super.open();

        lastTriggeredProcessingTime = Long.MIN_VALUE;
        collector = new TimestampedCollector<>(output);
        collector.eraseTimestamp();

        internalTimerService =
                getInternalTimerService(
                        "window-timers", windowProcessor.createWindowSerializer(), this);
        // Restore the watermark of timerService to prevent expired data from being treated as
        // not expired when flushWindowBuffer is executed.
        internalTimerService.initializeWatermark(currentWatermark);
        windowProcessor.open(
                new WindowProcessorSyncStateContext<>(
                        getContainingTask(),
                        getContainingTask().getEnvironment().getMemoryManager(),
                        computeMemorySize(),
                        internalTimerService,
                        getKeyedStateBackend(),
                        collector,
                        getRuntimeContext()));
        // initialize watermark
        windowProcessor.initializeWatermark(currentWatermark);

        // metrics
        this.numLateRecordsDropped = metrics.counter(LATE_ELEMENTS_DROPPED_METRIC_NAME);
        this.lateRecordsDroppedRate =
                metrics.meter(
                        LATE_ELEMENTS_DROPPED_RATE_METRIC_NAME,
                        new MeterView(numLateRecordsDropped));
        this.watermarkLatency =
                metrics.gauge(
                        WATERMARK_LATENCY_METRIC_NAME,
                        () -> {
                            long watermark = internalTimerService.currentWatermark();
                            if (watermark < 0) {
                                return 0L;
                            } else {
                                return internalTimerService.currentProcessingTime() - watermark;
                            }
                        });
    }

    @Override
    public void initializeState(StateInitializationContext context) throws Exception {
        super.initializeState(context);
        ListStateDescriptor<Long> watermarkStateDesc =
                new ListStateDescriptor<>("watermark", LongSerializer.INSTANCE);
        this.watermarkState = context.getOperatorStateStore().getUnionListState(watermarkStateDesc);
        if (context.isRestored()) {
            Iterable<Long> watermarks = watermarkState.get();
            if (watermarks != null) {
                long minWatermark = Long.MAX_VALUE;
                for (Long watermark : watermarks) {
                    minWatermark = Math.min(watermark, minWatermark);
                }
                if (minWatermark != Long.MAX_VALUE) {
                    this.currentWatermark = minWatermark;
                }
            }
        }
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        super.snapshotState(context);
        this.watermarkState.update(Collections.singletonList(currentWatermark));
    }

    @Override
    public void close() throws Exception {
        super.close();
        collector = null;
        windowProcessor.close();
    }

    @Override
    public void processElement(StreamRecord<RowData> element) throws Exception {
        RowData inputRow = element.getValue();
        RowData currentKey = (RowData) getCurrentKey();
        boolean isElementDropped = windowProcessor.processElement(currentKey, inputRow);
        if (isElementDropped) {
            // markEvent will increase numLateRecordsDropped
            lateRecordsDroppedRate.markEvent();
        }
    }

    @Override
    public void processWatermark(Watermark mark) throws Exception {
        if (mark.getTimestamp() > currentWatermark) {
            // If this is a proctime window, progress should not be advanced by watermark, or it'll
            // disturb timer-based processing
            if (isEventTime) {
                windowProcessor.advanceProgress(mark.getTimestamp());
            }
            super.processWatermark(mark);
        } else {
            super.processWatermark(new Watermark(currentWatermark));
        }
    }

    @Override
    public void onEventTime(InternalTimer<K, W> timer) throws Exception {
        onTimer(timer);
    }

    @Override
    public void onProcessingTime(InternalTimer<K, W> timer) throws Exception {
        if (timer.getTimestamp() > lastTriggeredProcessingTime) {
            // similar to the watermark advance,
            // we need to notify WindowProcessor first to flush buffer into state
            lastTriggeredProcessingTime = timer.getTimestamp();
            windowProcessor.advanceProgress(timer.getTimestamp());
            // timers registered in advanceProgress() should always be smaller than current timer
            // so, it should be safe to trigger current timer straightforwards.
        }
        onTimer(timer);
    }

    private void onTimer(InternalTimer<K, W> timer) throws Exception {
        setCurrentKey(timer.getKey());
        W window = timer.getNamespace();
        windowProcessor.fireWindow(timer.getTimestamp(), window);
        windowProcessor.clearWindow(timer.getTimestamp(), window);
        // we don't need to clear window timers,
        // because there should only be one timer for each window now, which is current timer.
    }

    @Override
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        windowProcessor.prepareCheckpoint();
    }

    /** Context implementation for {@link SyncStateWindowProcessor.SyncStateContext}. */
    private static final class WindowProcessorSyncStateContext<W>
            implements SyncStateWindowProcessor.SyncStateContext<W> {

        private final Object operatorOwner;
        private final MemoryManager memoryManager;
        private final long memorySize;
        private final InternalTimerService<W> timerService;
        private final KeyedStateBackend<RowData> keyedStateBackend;
        private final Output<RowData> collector;
        private final RuntimeContext runtimeContext;

        private WindowProcessorSyncStateContext(
                Object operatorOwner,
                MemoryManager memoryManager,
                long memorySize,
                InternalTimerService<W> timerService,
                KeyedStateBackend<RowData> keyedStateBackend,
                Output<RowData> collector,
                RuntimeContext runtimeContext) {
            this.operatorOwner = operatorOwner;
            this.memoryManager = memoryManager;
            this.memorySize = memorySize;
            this.timerService = timerService;
            this.keyedStateBackend = checkNotNull(keyedStateBackend);
            this.collector = checkNotNull(collector);
            this.runtimeContext = checkNotNull(runtimeContext);
        }

        @Override
        public Object getOperatorOwner() {
            return operatorOwner;
        }

        @Override
        public MemoryManager getMemoryManager() {
            return memoryManager;
        }

        @Override
        public long getMemorySize() {
            return memorySize;
        }

        @Override
        public KeyedStateBackend<RowData> getKeyedStateBackend() {
            return keyedStateBackend;
        }

        @Override
        public InternalTimerService<W> getTimerService() {
            return timerService;
        }

        @Override
        public void output(RowData result) {
            collector.collect(result);
        }

        @Override
        public RuntimeContext getRuntimeContext() {
            return runtimeContext;
        }
    }

    // ------------------------------------------------------------------------------
    // Visible For Testing
    // ------------------------------------------------------------------------------

    @VisibleForTesting
    public Counter getNumLateRecordsDropped() {
        return numLateRecordsDropped;
    }

    @VisibleForTesting
    public Gauge<Long> getWatermarkLatency() {
        return watermarkLatency;
    }
}
