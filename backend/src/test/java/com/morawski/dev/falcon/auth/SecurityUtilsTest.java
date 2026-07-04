package com.morawski.dev.falcon.auth;

import com.morawski.dev.falcon.user.AppUserDetails;
import com.morawski.dev.falcon.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUtilsTest {

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void returnsIdFromAuthenticatedContext() {
		User user = new User("me@example.com", "hash", Instant.now());
		ReflectionTestUtils.setField(user, "id", 42L);
		AppUserDetails principal = new AppUserDetails(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

		assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
	}

	@Test
	void throwsWhenNoAuthenticatedUser() {
		assertThatThrownBy(SecurityUtils::currentUserId).isInstanceOf(IllegalStateException.class);
	}

}
