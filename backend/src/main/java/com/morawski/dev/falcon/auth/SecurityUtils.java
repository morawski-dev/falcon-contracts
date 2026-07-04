package com.morawski.dev.falcon.auth;

import com.morawski.dev.falcon.user.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The single source of truth for "who is the current user?". Every repository method
 * returning owner-scoped data (Analysis, Clause, NegotiationPoint in later slices) takes
 * an {@code ownerId} parameter; callers pass {@link #currentUserId()} — there is no bare
 * {@code findById} for owned entities.
 */
public final class SecurityUtils {

	private SecurityUtils() {
	}

	public static Long currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserDetails principal) {
			return principal.getId();
		}
		throw new IllegalStateException("No authenticated user in security context");
	}

}
