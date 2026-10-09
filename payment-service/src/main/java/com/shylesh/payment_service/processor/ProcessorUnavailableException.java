package com.shylesh.payment_service.processor;

/** An UNKNOWN outcome, thrown inside the circuit breaker so it counts as a failure there. */
public class ProcessorUnavailableException extends RuntimeException {

    private final transient ProcessorResponse response;

    public ProcessorUnavailableException(ProcessorResponse response) {
        super(response.describe(), null, false, false);
        this.response = response;
    }

    public ProcessorResponse response() {
        return response;
    }
}
