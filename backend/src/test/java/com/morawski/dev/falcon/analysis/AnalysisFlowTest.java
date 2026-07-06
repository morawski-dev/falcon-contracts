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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The load-bearing e2e: a logged-in user pastes a contract with an obviously risky clause, the
 * analysis is saved, the clause is flagged HIGH with a linked NegotiationPoint — plus the
 * cross-user isolation test this domain's first owned entity makes possible. The ChatModel is
 * mocked as a real lambda (@Bean @Primary), never a bare Mockito mock, to avoid the
 * getOptions()-NPE trap documented in the plan's Critical Implementation Details.
 */
@Import({TestcontainersConfiguration.class, AnalysisFlowTest.MockChatModelConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisFlowTest {

	private static final String FIXED_JSON = """
			{
			  "clauses": [
			    {
			      "text": "Umowa ulega automatycznemu przedłużeniu o kolejne 12 miesięcy, jeśli nie zostanie wypowiedziana na 90 dni przed końcem okresu.",
			      "riskLevel": "HIGH",
			      "riskType": "AUTO_RENEWAL",
			      "rationale": "Bardzo długi okres wypowiedzenia (90 dni) przy automatycznym przedłużeniu utrudnia wyjście z umowy."
			    }
			  ],
			  "negotiationPoints": [
			    {
			      "clauseText": "Umowa ulega automatycznemu przedłużeniu o kolejne 12 miesięcy, jeśli nie zostanie wypowiedziana na 90 dni przed końcem okresu.",
			      "recommendation": "Skrócić okres wypowiedzenia do 30 dni lub usunąć automatyczne przedłużenie.",
			      "priority": "HIGH"
			    }
			  ]
			}
			""";

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
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void cleanUp() {
		// Break the negotiation_points.clause_id cross-reference first — it's a plain column, not
		// a JPA relationship, so Hibernate's own cascade-delete ordering for the Analysis's two
		// sibling @OneToMany collections (clauses, negotiationPoints) can't be relied on to respect
		// the DB-level FK (negotiation_points.clause_id → clauses.id has no cascade, by design).
		// Must be a SEPARATE, already-committed transaction from the deletes below: nulling and
		// deleting the same rows in one flush lets Hibernate skip the now-pointless update.
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> {
			List<Analysis> analyses = analysisRepository.findAll();
			for (Analysis analysis : analyses) {
				for (NegotiationPoint point : analysis.getNegotiationPoints()) {
					point.setClauseId(null);
				}
			}
			analysisRepository.saveAll(analyses);
		});
		transactionTemplate.executeWithoutResult(status -> {
			analysisRepository.deleteAll();
			userRepository.deleteAll();
		});
	}

	private User persistUser(String email) {
		return userRepository.save(new User(email, passwordEncoder.encode("password"), Instant.now()));
	}

	@Test
	void pasteRiskyContractIsSavedFlaggedAndLinked() throws Exception {
		User owner = persistUser("owner@example.com");
		CreateAnalysisRequest request = new CreateAnalysisRequest("Umowa najmu",
				"Tresc umowy zawierajaca klauzule automatycznego przedluzenia...");

		MvcResult createResult = mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(owner)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ANALYZED"))
				.andExpect(jsonPath("$.clauses[0].riskLevel").value("HIGH"))
				.andExpect(jsonPath("$.clauses[0].riskType").value("AUTO_RENEWAL"))
				.andExpect(jsonPath("$.negotiationPoints[0].clauseId").isNumber())
				.andReturn();

		long id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

		mockMvc.perform(get("/api/analyses/" + id).with(user(new AppUserDetails(owner))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clauses[0].riskLevel").value("HIGH"))
				.andExpect(jsonPath("$.negotiationPoints[0].clauseId").isNumber());
	}

	@Test
	void crossUserCannotReadAnotherUsersAnalysis() throws Exception {
		User ownerA = persistUser("owner-a@example.com");
		User ownerB = persistUser("owner-b@example.com");
		CreateAnalysisRequest request = new CreateAnalysisRequest("Umowa B2B", "Tresc umowy...");

		MvcResult createResult = mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(ownerA)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andReturn();

		long id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

		mockMvc.perform(get("/api/analyses/" + id).with(user(new AppUserDetails(ownerB))))
				.andExpect(status().isNotFound());
	}

	@Test
	void blankTitleReturns400() throws Exception {
		User owner = persistUser("blank-title@example.com");
		CreateAnalysisRequest request = new CreateAnalysisRequest("", "Tresc umowy...");

		mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(owner)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void tooLongRawTextReturns400() throws Exception {
		User owner = persistUser("too-long@example.com");
		CreateAnalysisRequest request = new CreateAnalysisRequest("Tytul", "a".repeat(20001));

		mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(owner)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownIdReturns404() throws Exception {
		User owner = persistUser("unknown-id@example.com");

		mockMvc.perform(get("/api/analyses/999999999").with(user(new AppUserDetails(owner))))
				.andExpect(status().isNotFound());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MockChatModelConfig {

		@Bean
		@Primary
		ChatModel mockChatModel() {
			ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(FIXED_JSON))));
			return prompt -> response;
		}

	}

}
