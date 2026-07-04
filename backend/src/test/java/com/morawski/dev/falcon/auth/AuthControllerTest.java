package com.morawski.dev.falcon.auth;

import com.morawski.dev.falcon.TestcontainersConfiguration;
import com.morawski.dev.falcon.user.AppUserDetails;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void cleanUp() {
		userRepository.deleteAll();
	}

	@Test
	void anonymousMeReturns401() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedMeReturnsCallerIdentity() throws Exception {
		User saved = userRepository.save(new User("caller@example.com", "hash", Instant.now()));
		AppUserDetails principal = new AppUserDetails(saved);

		mockMvc.perform(get("/api/auth/me").with(user(principal)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(saved.getId()))
				.andExpect(jsonPath("$.email").value("caller@example.com"));
	}

}
