package com.morawski.dev.falcon.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * AuthenticationException thrown by a manual authenticationManager.authenticate() call (login)
 * bypasses the security filter chain's ExceptionTranslationFilter, so it needs explicit mapping
 * here. The message is deliberately generic — it must not reveal whether the email exists.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<Map<String, String>> handleAuthenticationException(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password"));
	}

}
