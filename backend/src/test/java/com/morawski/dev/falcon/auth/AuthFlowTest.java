package com.morawski.dev.falcon.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morawski.dev.falcon.TestcontainersConfiguration;
import com.morawski.dev.falcon.auth.dto.LoginRequest;
import com.morawski.dev.falcon.auth.dto.RegisterRequest;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@AfterEach
	void cleanUp() {
		userRepository.deleteAll();
	}

	@Test
	void registerLoginMeLogoutFlowSucceeds() throws Exception {
		RegisterRequest registerRequest = new RegisterRequest("flow@example.com", "correct-password");

		mockMvc.perform(post("/api/auth/register").with(csrf())
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(registerRequest)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("flow@example.com"));

		LoginRequest loginRequest = new LoginRequest("flow@example.com", "correct-password");
		MvcResult loginResult = mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(loginRequest)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("flow@example.com"))
				.andReturn();

		HttpSession session = loginResult.getRequest().getSession(false);
		assertThat(session).as("login must persist a session").isNotNull();
		MockHttpSession mockSession = (MockHttpSession) session;

		mockMvc.perform(get("/api/auth/me").session(mockSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("flow@example.com"));

		mockMvc.perform(post("/api/auth/logout").with(csrf()).session(mockSession))
				.andExpect(status().isNoContent());
	}

	@Test
	void registerWithExistingEmailReturns409() throws Exception {
		userRepository.save(new User("existing@example.com", passwordEncoder.encode("some-password"), Instant.now()));

		RegisterRequest registerRequest = new RegisterRequest("existing@example.com", "another-password");

		mockMvc.perform(post("/api/auth/register").with(csrf())
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(registerRequest)))
				.andExpect(status().isConflict());
	}

	@Test
	void loginWithWrongPasswordReturns401() throws Exception {
		userRepository.save(new User("known@example.com", passwordEncoder.encode("correct-password"), Instant.now()));

		LoginRequest loginRequest = new LoginRequest("known@example.com", "wrong-password");

		mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(loginRequest)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void registerWithInvalidPayloadReturns400() throws Exception {
		RegisterRequest invalidRequest = new RegisterRequest("not-an-email", "short");

		mockMvc.perform(post("/api/auth/register").with(csrf())
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(invalidRequest)))
				.andExpect(status().isBadRequest());
	}

}
