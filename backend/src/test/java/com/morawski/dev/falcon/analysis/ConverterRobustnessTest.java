package com.morawski.dev.falcon.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morawski.dev.falcon.TestcontainersConfiguration;
import com.morawski.dev.falcon.analysis.dto.CreateAnalysisRequest;
import com.morawski.dev.falcon.user.AppUserDetails;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Risk #3 (test-plan.md §2): every ragged model reply must yield a controlled, user-visible
 * outcome — never a silent half-save. AnalysisFailureTest only exercises one shape
 * ("not json at all"); this matrix drives the fuller named set through the real converter.
 * Shares one Spring context across cases via a mutable @Primary ChatModel (a settable field),
 * avoiding a context-per-case boot cost.
 */
@Import({TestcontainersConfiguration.class, ConverterRobustnessTest.MutableChatModelConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class ConverterRobustnessTest {

	private static final String GENERIC_ERROR_MESSAGE = "Failed to analyze contract. Please try again.";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AnalysisRepository analysisRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private MutableChatModelConfig.MutableChatModel mutableChatModel;

	@AfterEach
	void cleanUp() {
		// None of these payloads produce a linked negotiationPoint.clauseId, so the plain
		// single-phase teardown (unlike AnalysisFlowTest's two-phase one) is enough here.
		analysisRepository.deleteAll();
		userRepository.deleteAll();
	}

	private static Stream<Arguments> raggedPayloads() {
		return Stream.of(
				Arguments.of("bad enum casing", """
						{
						  "clauses": [
						    { "text": "Płatność następuje przelewem w terminie 14 dni.", "riskLevel": "high", "riskType": "PAYMENT", "rationale": "Standardowy zapis." }
						  ],
						  "negotiationPoints": []
						}
						""", 502),
				Arguments.of("extra unexpected field", """
						{
						  "clauses": [
						    { "text": "Umowa ulega automatycznemu przedłużeniu o kolejne 12 miesięcy.", "riskLevel": "HIGH", "riskType": "AUTO_RENEWAL", "rationale": "Długi okres wypowiedzenia utrudnia rezygnację.", "confidence": 0.87 }
						  ],
						  "negotiationPoints": []
						}
						""", 201),
				Arguments.of("missing required field", """
						{
						  "clauses": [
						    { "text": "Kara umowna za opóźnienie w płatności.", "riskType": "PENALTY", "rationale": "Kara może być bardzo wysoka." }
						  ],
						  "negotiationPoints": []
						}
						""", 502),
				// Observed: Spring AI's converter strips the ```json fences and parses successfully
				// (201, clause persisted) rather than failing — pinning the actually-observed outcome
				// per the plan's Critical Implementation Details.
				Arguments.of("markdown-fenced JSON", """
						```json
						{
						  "clauses": [
						    { "text": "Zakaz konkurencji przez 5 lat po zakończeniu umowy.", "riskLevel": "HIGH", "riskType": "CONFIDENTIALITY", "rationale": "Bardzo długi okres zakazu konkurencji." }
						  ],
						  "negotiationPoints": []
						}
						```
						""", 201),
				Arguments.of("empty/blank content", " ", 502),
				// Characterizes today's conflation (test-plan.md §7): a VALID reply that legitimately
				// found no risky clauses hits the same 502 as a real model failure. Not fixed here —
				// a distinct "no risky clauses found" state is a follow-up feature, not a test gap.
				Arguments.of("zero-clause valid", """
						{
						  "clauses": [],
						  "negotiationPoints": []
						}
						""", 502));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("raggedPayloads")
	void raggedModelReplyYieldsControlledOutcome(String label, String payload, int expectedStatus) throws Exception {
		mutableChatModel.setContent(payload);
		User owner = persistUser("ragged-" + Math.abs(label.hashCode()) + "@example.com");
		CreateAnalysisRequest request = new CreateAnalysisRequest("Umowa testowa", "Tresc umowy do analizy...");

		MvcResult result = mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(owner)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andReturn();

		assertThat(result.getResponse().getStatus()).as(label).isEqualTo(expectedStatus);

		if (expectedStatus == 502) {
			assertThat(result.getResponse().getContentAsString())
					.as(label + " must show the generic controlled error — never a silent half-save")
					.contains(GENERIC_ERROR_MESSAGE);
		} else {
			JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(created.get("clauses"))
					.as(label + " must persist the clause, not silently drop it")
					.isNotEmpty();
		}
	}

	private User persistUser(String email) {
		return userRepository.save(new User(email, passwordEncoder.encode("password"), Instant.now()));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MutableChatModelConfig {

		static final class MutableChatModel implements ChatModel {

			private volatile String content = "";

			void setContent(String content) {
				this.content = content;
			}

			@Override
			public ChatResponse call(Prompt prompt) {
				return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
			}

		}

		@Bean
		@Primary
		MutableChatModel mockChatModel() {
			return new MutableChatModel();
		}

	}

}
