package com.morawski.dev.falcon.auth;

import org.springframework.dao.DataIntegrityViolationException;
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

	/**
	 * Catches the losing side of the existsByEmail()/save() race in registration: the unique
	 * constraint on users.email prevents the duplicate row, but without this handler the
	 * exception would otherwise surface as a raw 500 instead of the same 409 the pre-check gives.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Email already in use"));
	}

}
