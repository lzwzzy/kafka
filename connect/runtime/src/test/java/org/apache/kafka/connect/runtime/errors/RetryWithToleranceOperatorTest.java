/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.runtime.errors;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.runtime.ConnectMetrics;
import org.apache.kafka.connect.runtime.ConnectorConfig;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.runtime.isolation.Plugins;
import org.apache.kafka.connect.runtime.isolation.PluginsTest.TestConverter;
import org.apache.kafka.connect.runtime.isolation.PluginsTest.TestableWorkerConfig;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.util.ConnectorTaskId;
import org.easymock.EasyMock;
import org.easymock.Mock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.apache.kafka.common.utils.Time.SYSTEM;
import static org.apache.kafka.connect.runtime.ConnectorConfig.ERRORS_RETRY_MAX_DELAY_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.ERRORS_RETRY_MAX_DELAY_DEFAULT;
import static org.apache.kafka.connect.runtime.ConnectorConfig.ERRORS_RETRY_TIMEOUT_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.ERRORS_RETRY_TIMEOUT_DEFAULT;
import static org.apache.kafka.connect.runtime.ConnectorConfig.ERRORS_TOLERANCE_CONFIG;
import static org.apache.kafka.connect.runtime.ConnectorConfig.ERRORS_TOLERANCE_DEFAULT;
import static org.apache.kafka.connect.runtime.errors.ToleranceType.ALL;
import static org.apache.kafka.connect.runtime.errors.ToleranceType.NONE;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ProcessingContext.class})
@PowerMockIgnore("javax.management.*")
public class RetryWithToleranceOperatorTest {

    public static final RetryWithToleranceOperator NOOP_OPERATOR = new RetryWithToleranceOperator(
            ERRORS_RETRY_TIMEOUT_DEFAULT, ERRORS_RETRY_MAX_DELAY_DEFAULT, NONE, SYSTEM);
    public static final RetryWithToleranceOperator ALL_OPERATOR = new RetryWithToleranceOperator(
            ERRORS_RETRY_TIMEOUT_DEFAULT, ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, SYSTEM);
    static {
        Map<String, String> properties = new HashMap<>();
        properties.put(CommonClientConfigs.METRICS_NUM_SAMPLES_CONFIG, Objects.toString(2));
        properties.put(CommonClientConfigs.METRICS_SAMPLE_WINDOW_MS_CONFIG, Objects.toString(3000));
        properties.put(CommonClientConfigs.METRICS_RECORDING_LEVEL_CONFIG, Sensor.RecordingLevel.INFO.toString());

        // define required properties
        properties.put(WorkerConfig.KEY_CONVERTER_CLASS_CONFIG, TestConverter.class.getName());
        properties.put(WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG, TestConverter.class.getName());

        NOOP_OPERATOR.metrics(new ErrorHandlingMetrics(
            new ConnectorTaskId("noop-connector", -1),
            new ConnectMetrics("noop-worker", new TestableWorkerConfig(properties), new SystemTime(), "test-cluster"))
        );
        ALL_OPERATOR.metrics(new ErrorHandlingMetrics(
                new ConnectorTaskId("errors-all-tolerate-connector", -1),
                new ConnectMetrics("errors-all-tolerate-worker", new TestableWorkerConfig(properties),
                        new SystemTime(), "test-cluster"))
        );
    }

    @SuppressWarnings("unused")
    @Mock
    private Operation<String> mockOperation;

    @Mock
    private ConsumerRecord<byte[], byte[]> consumerRecord;

    @Mock
    ErrorHandlingMetrics errorHandlingMetrics;

    @Mock
    Plugins plugins;

    @Test
    public void testExecuteFailed() {
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(0,
            ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, SYSTEM);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);

        retryWithToleranceOperator.executeFailed(Stage.TASK_PUT,
            SinkTask.class, consumerRecord, new Throwable());
    }

    @Test
    public void testExecuteFailedNoTolerance() {
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(0,
            ERRORS_RETRY_MAX_DELAY_DEFAULT, NONE, SYSTEM);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);

        assertThrows(ConnectException.class, () -> retryWithToleranceOperator.executeFailed(Stage.TASK_PUT,
            SinkTask.class, consumerRecord, new Throwable()));
    }

    @Test
    public void testHandleExceptionInTransformations() {
        testHandleExceptionInStage(Stage.TRANSFORMATION, new Exception());
    }

    @Test
    public void testHandleExceptionInHeaderConverter() {
        testHandleExceptionInStage(Stage.HEADER_CONVERTER, new Exception());
    }

    @Test
    public void testHandleExceptionInValueConverter() {
        testHandleExceptionInStage(Stage.VALUE_CONVERTER, new Exception());
    }

    @Test
    public void testHandleExceptionInKeyConverter() {
        testHandleExceptionInStage(Stage.KEY_CONVERTER, new Exception());
    }

    @Test
    public void testHandleExceptionInTaskPut() {
        testHandleExceptionInStage(Stage.TASK_PUT, new org.apache.kafka.connect.errors.RetriableException("Test"));
    }

    @Test
    public void testHandleExceptionInTaskPoll() {
        testHandleExceptionInStage(Stage.TASK_POLL, new org.apache.kafka.connect.errors.RetriableException("Test"));
    }

    @Test
    public void testThrowExceptionInTaskPut() {
        assertThrows(ConnectException.class, () -> testHandleExceptionInStage(Stage.TASK_PUT, new Exception()));
    }

    @Test
    public void testThrowExceptionInTaskPoll() {
        assertThrows(ConnectException.class, () -> testHandleExceptionInStage(Stage.TASK_POLL, new Exception()));
    }

    @Test
    public void testThrowExceptionInKafkaConsume() {
        assertThrows(ConnectException.class, () -> testHandleExceptionInStage(Stage.KAFKA_CONSUME, new Exception()));
    }

    @Test
    public void testThrowExceptionInKafkaProduce() {
        assertThrows(ConnectException.class, () -> testHandleExceptionInStage(Stage.KAFKA_PRODUCE, new Exception()));
    }

    private void testHandleExceptionInStage(Stage type, Exception ex) {
        RetryWithToleranceOperator retryWithToleranceOperator = setupExecutor();
        retryWithToleranceOperator.execute(new ExceptionThrower(ex), type, ExceptionThrower.class);
        assertTrue(retryWithToleranceOperator.failed());
        PowerMock.verifyAll();
    }

    private RetryWithToleranceOperator setupExecutor() {
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(0, ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, SYSTEM);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);
        return retryWithToleranceOperator;
    }

    @Test
    public void testExecAndHandleRetriableErrorOnce() throws Exception {
        execAndHandleRetriableError(6000, 1, Collections.singletonList(300L), new RetriableException("Test"), true);
    }

    @Test
    public void testExecAndHandleRetriableErrorThrice() throws Exception {
        execAndHandleRetriableError(6000, 3, Arrays.asList(300L, 600L, 1200L), new RetriableException("Test"), true);
    }

    @Test
    public void testExecAndHandleRetriableErrorWithInfiniteRetries() throws Exception {
        execAndHandleRetriableError(-1, 8, Arrays.asList(300L, 600L, 1200L, 2400L, 4800L, 9600L, 19200L, 38400L), new RetriableException("Test"), true);
    }

    @Test
    public void testExecAndHandleRetriableErrorWithMaxRetriesExceeded() throws Exception {
        execAndHandleRetriableError(6000, 6, Arrays.asList(300L, 600L, 1200L, 2400L, 1500L), new RetriableException("Test"), false);
    }

    public void execAndHandleRetriableError(long errorRetryTimeout, int numRetriableExceptionsThrown, List<Long> expectedWaits, Exception e, boolean successExpected) throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        CountDownLatch exitLatch = PowerMock.createStrictMock(CountDownLatch.class);
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(errorRetryTimeout, ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, time, new ProcessingContext(), exitLatch);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);

        EasyMock.expect(mockOperation.call()).andThrow(e).times(numRetriableExceptionsThrown);

        if (successExpected) {
            EasyMock.expect(mockOperation.call()).andReturn("Success");
        }

        for (Long expectedWait : expectedWaits) {
            EasyMock.expect(exitLatch.await(expectedWait, TimeUnit.MILLISECONDS)).andAnswer(() -> {
                time.sleep(expectedWait);
                return false;
            });
        }

        replay(mockOperation, exitLatch);

        String result = retryWithToleranceOperator.execAndHandleError(mockOperation, Exception.class);

        if (successExpected) {
            assertFalse(retryWithToleranceOperator.failed());
            assertEquals("Success", result);
        } else {
            assertTrue(retryWithToleranceOperator.failed());
        }

        EasyMock.verify(mockOperation);
        PowerMock.verifyAll();
    }

    @Test
    public void testExecAndHandleNonRetriableError() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        CountDownLatch exitLatch = PowerMock.createStrictMock(CountDownLatch.class);
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(6000, ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, time, new ProcessingContext(), exitLatch);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);

        EasyMock.expect(mockOperation.call()).andThrow(new Exception("Test")).times(1);

        // expect no call to exitLatch.await() which is only called during the retry backoff

        replay(mockOperation, exitLatch);

        String result = retryWithToleranceOperator.execAndHandleError(mockOperation, Exception.class);
        assertTrue(retryWithToleranceOperator.failed());
        assertNull(result);

        EasyMock.verify(mockOperation);
        PowerMock.verifyAll();
    }

    @Test
    public void testExitLatch() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        CountDownLatch exitLatch = PowerMock.createStrictMock(CountDownLatch.class);
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(-1, ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, time, new ProcessingContext(), exitLatch);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);
        EasyMock.expect(mockOperation.call()).andThrow(new RetriableException("test")).anyTimes();
        EasyMock.expect(exitLatch.await(300, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(300);
            return false;
        });
        EasyMock.expect(exitLatch.await(600, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(600);
            return false;
        });
        EasyMock.expect(exitLatch.await(1200, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(1200);
            return false;
        });
        EasyMock.expect(exitLatch.await(2400, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(2400);
            retryWithToleranceOperator.triggerStop();
            return false;
        });

        // expect no more calls to exitLatch.await() after retryWithToleranceOperator.triggerStop() is called

        exitLatch.countDown();
        EasyMock.expectLastCall().once();

        replay(mockOperation, exitLatch);
        retryWithToleranceOperator.execAndHandleError(mockOperation, Exception.class);
        assertTrue(retryWithToleranceOperator.failed());
        assertEquals(4500L, time.milliseconds());
        PowerMock.verifyAll();
    }

    @Test
    public void testBackoffLimit() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        CountDownLatch exitLatch = PowerMock.createStrictMock(CountDownLatch.class);
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(5, 5000, NONE, time, new ProcessingContext(), exitLatch);

        EasyMock.expect(exitLatch.await(300, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(300);
            return false;
        });
        EasyMock.expect(exitLatch.await(600, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(600);
            return false;
        });
        EasyMock.expect(exitLatch.await(1200, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(1200);
            return false;
        });
        EasyMock.expect(exitLatch.await(2400, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(2400);
            return false;
        });
        EasyMock.expect(exitLatch.await(500, TimeUnit.MILLISECONDS)).andAnswer(() -> {
            time.sleep(500);
            return false;
        });
        EasyMock.expect(exitLatch.await(0, TimeUnit.MILLISECONDS)).andReturn(false);

        replay(exitLatch);

        retryWithToleranceOperator.backoff(1, 5000);
        retryWithToleranceOperator.backoff(2, 5000);
        retryWithToleranceOperator.backoff(3, 5000);
        retryWithToleranceOperator.backoff(4, 5000);
        retryWithToleranceOperator.backoff(5, 5000);
        // Simulate a small delay between calculating the deadline, and backing off
        time.sleep(1);
        // We may try to begin backing off after the deadline has already passed; make sure
        // that we don't wait with a negative timeout
        retryWithToleranceOperator.backoff(6, 5000);

        PowerMock.verifyAll();
    }

    @Test
    public void testToleranceLimit() {
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(ERRORS_RETRY_TIMEOUT_DEFAULT, ERRORS_RETRY_MAX_DELAY_DEFAULT, NONE, SYSTEM);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);
        retryWithToleranceOperator.markAsFailed();
        assertFalse("should not tolerate any errors", retryWithToleranceOperator.withinToleranceLimits());

        retryWithToleranceOperator = new RetryWithToleranceOperator(ERRORS_RETRY_TIMEOUT_DEFAULT, ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, SYSTEM);
        retryWithToleranceOperator.metrics(errorHandlingMetrics);
        retryWithToleranceOperator.markAsFailed();
        retryWithToleranceOperator.markAsFailed();
        assertTrue("should tolerate all errors", retryWithToleranceOperator.withinToleranceLimits());

        retryWithToleranceOperator = new RetryWithToleranceOperator(ERRORS_RETRY_TIMEOUT_DEFAULT, ERRORS_RETRY_MAX_DELAY_DEFAULT, NONE, SYSTEM);
        assertTrue("no tolerance is within limits if no failures", retryWithToleranceOperator.withinToleranceLimits());
    }

    @Test
    public void testDefaultConfigs() {
        ConnectorConfig configuration = config(emptyMap());
        assertEquals(configuration.errorRetryTimeout(), ERRORS_RETRY_TIMEOUT_DEFAULT);
        assertEquals(configuration.errorMaxDelayInMillis(), ERRORS_RETRY_MAX_DELAY_DEFAULT);
        assertEquals(configuration.errorToleranceType(), ERRORS_TOLERANCE_DEFAULT);

        PowerMock.verifyAll();
    }

    ConnectorConfig config(Map<String, String> connProps) {
        Map<String, String> props = new HashMap<>();
        props.put(ConnectorConfig.NAME_CONFIG, "test");
        props.put(ConnectorConfig.CONNECTOR_CLASS_CONFIG, SinkTask.class.getName());
        props.putAll(connProps);
        return new ConnectorConfig(plugins, props);
    }

    @Test
    public void testSetConfigs() {
        ConnectorConfig configuration;
        configuration = config(singletonMap(ERRORS_RETRY_TIMEOUT_CONFIG, "100"));
        assertEquals(configuration.errorRetryTimeout(), 100);

        configuration = config(singletonMap(ERRORS_RETRY_MAX_DELAY_CONFIG, "100"));
        assertEquals(configuration.errorMaxDelayInMillis(), 100);

        configuration = config(singletonMap(ERRORS_TOLERANCE_CONFIG, "none"));
        assertEquals(configuration.errorToleranceType(), ToleranceType.NONE);

        PowerMock.verifyAll();
    }

    @Test
    public void testThreadSafety() throws Throwable {
        long runtimeMs = 5_000;
        int numThreads = 10;
        // Check that multiple threads using RetryWithToleranceOperator concurrently
        // can't corrupt the state of the ProcessingContext
        AtomicReference<Throwable> failed = new AtomicReference<>(null);
        RetryWithToleranceOperator retryWithToleranceOperator = new RetryWithToleranceOperator(0,
                ERRORS_RETRY_MAX_DELAY_DEFAULT, ALL, SYSTEM, new ProcessingContext() {
                    private AtomicInteger count = new AtomicInteger();
                    private AtomicInteger attempt = new AtomicInteger();

                    @Override
                    public void error(Throwable error) {
                        if (count.getAndIncrement() > 0) {
                            failed.compareAndSet(null, new AssertionError("Concurrent call to error()"));
                        }
                        super.error(error);
                    }

                    @Override
                    public Future<Void> report() {
                        if (count.getAndSet(0) > 1) {
                            failed.compareAndSet(null, new AssertionError("Concurrent call to error() in report()"));
                        }

                        return super.report();
                    }

                    @Override
                    public void currentContext(Stage stage, Class<?> klass) {
                        this.attempt.set(0);
                        super.currentContext(stage, klass);
                    }

                    @Override
                    public void attempt(int attempt) {
                        if (!this.attempt.compareAndSet(attempt - 1, attempt)) {
                            failed.compareAndSet(null, new AssertionError(
                                    "Concurrent call to attempt(): Attempts should increase monotonically " +
                                            "within the scope of a given currentContext()"));
                        }
                        super.attempt(attempt);
                    }
                }, new CountDownLatch(1));
        retryWithToleranceOperator.metrics(errorHandlingMetrics);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        List<? extends Future<?>> futures = IntStream.range(0, numThreads).boxed()
                .map(id ->
                        pool.submit(() -> {
                            long t0 = System.currentTimeMillis();
                            long i = 0;
                            while (true) {
                                if (++i % 10000 == 0 && System.currentTimeMillis() > t0 + runtimeMs) {
                                    break;
                                }
                                if (failed.get() != null) {
                                    break;
                                }
                                try {
                                    if (id < numThreads / 2) {
                                        retryWithToleranceOperator.executeFailed(Stage.TASK_PUT,
                                                SinkTask.class, consumerRecord, new Throwable()).get();
                                    } else {
                                        retryWithToleranceOperator.execute(() -> null, Stage.TRANSFORMATION,
                                                SinkTask.class);
                                    }
                                } catch (Exception e) {
                                    failed.compareAndSet(null, e);
                                }
                            }
                        }))
                .collect(Collectors.toList());
        pool.shutdown();
        pool.awaitTermination((long) (1.5 * runtimeMs), TimeUnit.MILLISECONDS);
        futures.forEach(future -> {
            try {
                future.get();
            } catch (Exception e) {
                failed.compareAndSet(null, e);
            }
        });
        Throwable exception = failed.get();
        if (exception != null) {
            throw exception;
        }
    }


    private static class ExceptionThrower implements Operation<Object> {
        private Exception e;

        public ExceptionThrower(Exception e) {
            this.e = e;
        }

        @Override
        public Object call() throws Exception {
            throw e;
        }
    }
}
