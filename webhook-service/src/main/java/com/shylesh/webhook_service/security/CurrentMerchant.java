package com.shylesh.webhook_service.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the merchant id from the caller's token (merchant_id claim) into a UUID controller
 * parameter. The merchant always comes from the verified token, never from the request.
 * Requests whose token isn't bound to a merchant (operator tokens) are rejected with 403.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentMerchant {
}
