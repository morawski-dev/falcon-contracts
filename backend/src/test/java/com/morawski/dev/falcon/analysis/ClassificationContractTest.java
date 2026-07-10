package com.morawski.dev.falcon.analysis;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Risk #2 (test-plan.md §2): a multi-clause mixed-risk reply must persist with no clause
 * dropped, no risky clause silently downgraded, and every MEDIUM/HIGH clause linked to a
 * negotiation point. AnalysisFlowTest's single-clause fixture can't prove any of this — it
 * only proves the wiring works for one clause.
 */
@Import({TestcontainersConfiguration.class, ClassificationContractTest.MockChatModelConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class ClassificationContractTest {

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
		// Same two-phase teardown as AnalysisFlowTest: negotiation_points.clause_id is a plain FK
		// column (no JPA relationship), so it must be nulled in its own committed transaction
		// before the cascade-deletes below, or the DB-level FK constraint blocks the delete.
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
	void multiClauseReplyPersistsWithNoDropNoDowngradeAndSelectiveLinkage() throws Exception {
		User owner = persistUser("classification-contract@example.com");
		CreateAnalysisRequest request = new CreateAnalysisRequest("Umowa najmu",
				"Tresc umowy zawierajaca klauzule automatycznego przedluzenia, kary umownej i platnosci...");

		MvcResult createResult = mockMvc.perform(post("/api/analyses").with(csrf()).with(user(new AppUserDetails(owner)))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
		assertClassificationContract(created);

		long id = created.get("id").asLong();
		MvcResult getResult = mockMvc.perform(get("/api/analyses/" + id).with(user(new AppUserDetails(owner))))
				.andExpect(status().isOk())
				.andReturn();
		assertClassificationContract(objectMapper.readTree(getResult.getResponse().getContentAsString()));
	}

	private void assertClassificationContract(JsonNode response) {
		JsonNode clauses = response.get("clauses");
		JsonNode points = response.get("negotiationPoints");

		// No clause dropped: the model returned 3, all 3 must be persisted and returned.
		assertThat(clauses).hasSize(3);

		// Never silently downgraded — assert the negative, not just the positive. Looked up by
		// riskType rather than array index: Analysis.clauses carries no @OrderBy, so the
		// persisted/returned order is not a guaranteed contract.
		JsonNode autoRenewalClause = findClauseByRiskType(clauses, "AUTO_RENEWAL");
		assertThat(autoRenewalClause.get("riskLevel").asText()).isEqualTo("HIGH").isNotEqualTo("LOW");
		JsonNode penaltyClause = findClauseByRiskType(clauses, "PENALTY");
		assertThat(penaltyClause.get("riskLevel").asText()).isEqualTo("MEDIUM").isNotEqualTo("LOW");
		JsonNode paymentClause = findClauseByRiskType(clauses, "PAYMENT");
		assertThat(paymentClause.get("riskLevel").asText()).isEqualTo("LOW");

		// Rationale is readable (non-blank) — never assert its prose (oracle-problem guard).
		for (JsonNode clause : clauses) {
			assertThat(clause.get("rationale").asText()).isNotBlank();
		}

		Set<Long> riskyClauseIds = Set.of(autoRenewalClause.get("id").asLong(), penaltyClause.get("id").asLong());

		// Linkage: exactly the two risky clauses get a negotiation point; the LOW clause gets none.
		assertThat(points).hasSize(2);
		for (JsonNode point : points) {
			assertThat(point.get("clauseId").isNumber())
					.as("negotiation point must link to a persisted clause")
					.isTrue();
			assertThat(riskyClauseIds).contains(point.get("clauseId").asLong());
		}
	}

	private JsonNode findClauseByRiskType(JsonNode clauses, String riskType) {
		for (JsonNode clause : clauses) {
			if (clause.get("riskType").asText().equals(riskType)) {
				return clause;
			}
		}
		throw new AssertionError("No clause found with riskType " + riskType);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MockChatModelConfig {

		@Bean
		@Primary
		ChatModel mockChatModel() {
			ChatResponse response = new ChatResponse(
					List.of(new Generation(new AssistantMessage(ClauseAnalysisFixtures.MULTI_CLAUSE_JSON))));
			return prompt -> response;
		}

	}

}
