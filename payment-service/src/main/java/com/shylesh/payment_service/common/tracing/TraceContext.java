package com.shylesh.payment_service.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Carries a trace across asynchronous hops that Kafka and HTTP instrumentation can't see: a
 * saga step runs in a scheduled worker, and an outbox row is published by a scheduled relay,
 * long after the request that caused them. The W3C traceparent of that request is stored with
 * the saga / outbox row (current()) and continued when the work happens (continueTrace()), so a
 * payment's API call, processor calls, Kafka messages and ledger postings form one trace.
 * Without tracing (e.g. disabled in tests) this does nothing.
 */
@Component
public class TraceContext {

    private static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public TraceContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current span's traceparent, or null. */
    public String current() {
        Tracer currentTracer = tracer.getIfAvailable();
        Propagator currentPropagator = propagator.getIfAvailable();
        if (currentTracer == null || currentPropagator == null || currentTracer.currentTraceContext().context() == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        currentPropagator.inject(currentTracer.currentTraceContext().context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs work in a new span that is a child of traceParent (or a new trace if it is null). */
    public <T> T continueTrace(String traceParent, String spanName, Supplier<T> work) {
        Tracer currentTracer = tracer.getIfAvailable();
        Propagator currentPropagator = propagator.getIfAvailable();
        if (currentTracer == null || currentPropagator == null) {
            return work.get();
        }
        Span span = traceParent == null
                ? currentTracer.nextSpan().name(spanName).start()
                : currentPropagator.extract(Map.of(TRACEPARENT, traceParent), Map::get).name(spanName).start();
        try (Tracer.SpanInScope ignored = currentTracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void continueTrace(String traceParent, String spanName, Runnable work) {
        continueTrace(traceParent, spanName, () -> {
            work.run();
            return null;
        });
    }
}
