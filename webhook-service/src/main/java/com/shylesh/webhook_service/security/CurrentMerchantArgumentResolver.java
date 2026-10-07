package com.shylesh.webhook_service.security;

import com.shylesh.webhook_service.exception.MerchantContextRequiredException;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

public class CurrentMerchantArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMerchant.class) && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public UUID resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return merchantId(SecurityContextHolder.getContext().getAuthentication());
    }

    /** The merchant_id claim of the caller's token; 403 if the token isn't bound to a merchant. */
    public static UUID merchantId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new MerchantContextRequiredException();
        }

        String merchantId = jwtAuthentication.getToken().getClaimAsString(Scopes.MERCHANT_ID_CLAIM);
        if (merchantId == null) {
            throw new MerchantContextRequiredException();
        }
        try {
            return UUID.fromString(merchantId);
        } catch (IllegalArgumentException e) {
            throw new MerchantContextRequiredException();
        }
    }
}
