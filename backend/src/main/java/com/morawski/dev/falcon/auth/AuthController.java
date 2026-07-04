package com.morawski.dev.falcon.auth;

import com.morawski.dev.falcon.auth.dto.LoginRequest;
import com.morawski.dev.falcon.auth.dto.RegisterRequest;
import com.morawski.dev.falcon.auth.dto.UserResponse;
import com.morawski.dev.falcon.user.AppUserDetails;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository;

	public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
	}

	@GetMapping("/csrf")
	public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
		// Touching getToken() forces the token to materialize and the XSRF-TOKEN cookie to be written.
		csrfToken.getToken();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/register")
	public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		String email = request.email().toLowerCase();
		if (userRepository.existsByEmail(email)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
		}

		User user = userRepository.save(new User(email, passwordEncoder.encode(request.password()), Instant.now()));
		return ResponseEntity.status(HttpStatus.CREATED).body(new UserResponse(user.getId(), user.getEmail()));
	}

	@PostMapping("/login")
	public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		Authentication authenticationRequest =
				new UsernamePasswordAuthenticationToken(request.email().toLowerCase(), request.password());
		Authentication authenticationResult = authenticationManager.authenticate(authenticationRequest);

		// Programmatic login does NOT auto-save the session — persist the context explicitly
		// or no session cookie is issued and subsequent requests stay anonymous.
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authenticationResult);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, httpRequest, httpResponse);

		AppUserDetails principal = (AppUserDetails) authenticationResult.getPrincipal();
		return ResponseEntity.ok(new UserResponse(principal.getId(), principal.getUsername()));
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal AppUserDetails principal) {
		return new UserResponse(principal.getId(), principal.getUsername());
	}

}
