package com.morawski.dev.falcon.auth;

import com.morawski.dev.falcon.auth.dto.UserResponse;
import com.morawski.dev.falcon.user.AppUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal AppUserDetails principal) {
		return new UserResponse(principal.getId(), principal.getUsername());
	}

}
