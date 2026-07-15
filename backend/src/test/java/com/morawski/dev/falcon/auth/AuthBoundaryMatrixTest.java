package com.morawski.dev.falcon.auth;

import com.morawski.dev.falcon.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthBoundaryMatrixTest {

	@Autowired
	private MockMvc mockMvc;

	private static Stream<String> protectedGetRoutes() {
		return Stream.of(
				"/api/auth/me",
				"/api/analyses",
				"/api/analyses/1",
				"/api/__does_not_exist__");
	}

	@ParameterizedTest
	@MethodSource("protectedGetRoutes")
	void anonymousGetToProtectedRouteReturns401(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousPostWithoutCsrfReturns403() throws Exception {
		mockMvc.perform(post("/api/analyses"))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousPostWithCsrfReturns401() throws Exception {
		mockMvc.perform(post("/api/analyses").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousPatchWithoutCsrfReturns403() throws Exception {
		mockMvc.perform(patch("/api/analyses/1/clauses/1"))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousPatchWithCsrfReturns401() throws Exception {
		mockMvc.perform(patch("/api/analyses/1/clauses/1").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousDeleteWithoutCsrfReturns403() throws Exception {
		mockMvc.perform(delete("/api/analyses/1"))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousDeleteWithCsrfReturns401() throws Exception {
		mockMvc.perform(delete("/api/analyses/1").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousCsrfEndpointIsReachable() throws Exception {
		mockMvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isNoContent());
	}

	@Test
	void actuatorHealthIsReachableAnonymously() throws Exception {
		// Documented public exception to the default-deny matrix: the compose
		// healthcheck polls /actuator/health without credentials, so a 401 here
		// would deadlock `depends_on: service_healthy`. Only the health endpoint
		// is opened (EndpointRequest.to(HealthEndpoint.class)) — no other actuator
		// endpoint is web-exposed.
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("UP")));
	}

	@Test
	void corsPreflightIsPermitted() throws Exception {
		mockMvc.perform(options("/api/analyses")
						.header("Origin", "http://localhost:3000")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isOk())
				.andExpect(header().exists("Access-Control-Allow-Origin"));
	}

	@Test
	void corsPreflightPermitsPatch() throws Exception {
		mockMvc.perform(options("/api/analyses/1/clauses/1")
						.header("Origin", "http://localhost:3000")
						.header("Access-Control-Request-Method", "PATCH"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
	}

	@Test
	void corsPreflightPermitsDelete() throws Exception {
		// MockMvc never sends an Origin header on ordinary requests, so nothing else in the suite
		// can catch a missing verb in SecurityConfig's CORS allowed-methods list — this preflight
		// assertion is the only automated guard on it.
		mockMvc.perform(options("/api/analyses/1")
						.header("Origin", "http://localhost:3000")
						.header("Access-Control-Request-Method", "DELETE"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));
	}

	@Test
	void logoutIsReachableAnonymouslyByDesign() throws Exception {
		// LogoutFilter runs before AuthorizationFilter -- no auth gate, by design.
		// Documented exception to the default-deny matrix, not a gap.
		mockMvc.perform(post("/api/auth/logout").with(csrf()))
				.andExpect(status().isNoContent());
	}

}
