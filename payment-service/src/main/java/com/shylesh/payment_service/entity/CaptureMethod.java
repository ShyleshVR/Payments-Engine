package com.shylesh.payment_service.entity;

public enum CaptureMethod {

    /** Captured right after authorization. */
    AUTOMATIC,
    /** Authorized only; the merchant captures (or cancels) later, e.g. when the order ships. */
    MANUAL

}
