package com.shylesh.webhook_service.security;

import com.shylesh.webhook_service.config.WebhookProperties;
import com.shylesh.webhook_service.exception.InvalidWebhookUrlException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Guards against SSRF: the subscription API is unauthenticated (until the auth phase), and
 * this service will POST to whatever URL it is given, so without this check anyone could make
 * it call internal services or cloud metadata endpoints.
 *
 * Checked twice: when the subscription is created, and again right before every send, since
 * the merchant's DNS record can change after subscribing (DNS rebinding). There is still a
 * small window between our lookup and the HTTP client's own lookup; closing it fully needs a
 * resolver hook in the HTTP client or an egress proxy, which belongs with the K8s phase.
 */
@Component
@RequiredArgsConstructor
public class WebhookTargetValidator {

    public enum TargetCheck { ALLOWED, BLOCKED, UNRESOLVABLE }

    public static final int MAX_URL_LENGTH = 2048;

    private final WebhookProperties properties;

    /** For subscription creation: throws with a merchant-readable reason unless the URL is usable. */
    public URI validateForSubscription(String url) {
        URI uri = parse(url);

        switch (checkTarget(uri)) {
            case BLOCKED -> throw new InvalidWebhookUrlException(
                    "Webhook URL must not point to a private, loopback or link-local address");
            case UNRESOLVABLE -> throw new InvalidWebhookUrlException(
                    "Webhook URL host '" + uri.getHost() + "' does not resolve");
            case ALLOWED -> { }
        }
        return uri;
    }

    public URI parse(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new InvalidWebhookUrlException("Webhook URL must be 1-" + MAX_URL_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new InvalidWebhookUrlException("Webhook URL is not a valid URI");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new InvalidWebhookUrlException("Webhook URL must use http or https");
        }
        if (scheme.equals("http") && properties.security().requireHttps()) {
            throw new InvalidWebhookUrlException("Webhook URL must use https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidWebhookUrlException("Webhook URL must include a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain a fragment");
        }
        return uri;
    }

    public TargetCheck checkTarget(URI uri) {
        if (properties.security().allowPrivateTargets()) {
            return TargetCheck.ALLOWED;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            return TargetCheck.UNRESOLVABLE;
        }

        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return TargetCheck.BLOCKED;
            }
        }
        return TargetCheck.ALLOWED;
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10 carrier-grade NAT, 0.0.0.0/8 "this network"
            return (first == 100 && second >= 64 && second <= 127) || first == 0;
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 unique local addresses
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
