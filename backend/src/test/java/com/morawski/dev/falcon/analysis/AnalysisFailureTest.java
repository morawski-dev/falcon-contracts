package com.morawski.dev.falcon.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morawski.dev.falcon.TestcontainersConfiguration;
import com.morawski.dev.falcon.analysis.dto.CreateAnalysisRequest;
import com.morawski.dev.falcon.user.AppUserDetails;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A separate test class (and Spring context) from AnalysisFlowTest because it needs a distinct
 * @Primary ChatModel mock — one that returns malformed content to induce the AnalysisFailedException
 * -> 502 path, rather than the happy-path fixed JSON.
 */
@Import({TestcontainersConfiguration.class, AnalysisFailureTest.MockBadChatModelConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisFailureTest {

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
	void llmFailureReturns502() throws Exception {
		User owner = userRepository.save(new User("failure@example.com", passwordEncoder.encode("password"), Instant.now()));
		CreateAnalysisRequest request = new CreateAnalysisRequest("Umowa", "Tresc umowy...");

		mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(owner)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadGateway());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MockBadChatModelConfig {

		@Bean
		@Primary
		ChatModel mockBadChatModel() {
			ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("not json at all"))));
			return prompt -> response;
		}

	}

}
