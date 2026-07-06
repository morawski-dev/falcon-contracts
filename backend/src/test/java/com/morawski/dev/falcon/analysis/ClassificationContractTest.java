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

	private static final String MULTI_CLAUSE_JSON = """
			{
			  "clauses": [
			    {
			      "text": "Umowa ulega automatycznemu przedłużeniu o kolejne 12 miesięcy, jeśli nie zostanie wypowiedziana na 90 dni przed końcem okresu.",
			      "riskLevel": "HIGH",
			      "riskType": "AUTO_RENEWAL",
			      "rationale": "Bardzo długi okres wypowiedzenia przy automatycznym przedłużeniu utrudnia wyjście z umowy."
			    },
			    {
			      "text": "W razie opóźnienia płatności naliczana jest kara umowna w wysokości 2 procent wartości umowy za każdy dzień zwłoki.",
			      "riskLevel": "MEDIUM",
			      "riskType": "PENALTY",
			      "rationale": "Kara naliczana codziennie może szybko przewyższyć wartość świadczenia."
			    },
			    {
			      "text": "Płatność następuje przelewem w terminie 14 dni od daty wystawienia faktury.",
			      "riskLevel": "LOW",
			      "riskType": "PAYMENT",
			      "rationale": "Standardowy termin płatności bez istotnego ryzyka."
			    }
			  ],
			  "negotiationPoints": [
			    {
			      "clauseText": "Umowa ulega automatycznemu przedłużeniu",
			      "recommendation": "Skrócić okres wypowiedzenia do 30 dni lub usunąć automatyczne przedłużenie.",
			      "priority": "HIGH"
			    },
			    {
			      "clauseText": "W razie opóźnienia płatności",
			      "recommendation": "Obniżyć karę umowną lub wprowadzić łączny limit.",
			      "priority": "MEDIUM"
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

		// Never silently downgraded — assert the negative, not just the positive.
		assertThat(clauses.get(0).get("riskLevel").asText()).isEqualTo("HIGH").isNotEqualTo("LOW");
		assertThat(clauses.get(0).get("riskType").asText()).isEqualTo("AUTO_RENEWAL");
		assertThat(clauses.get(1).get("riskLevel").asText()).isEqualTo("MEDIUM").isNotEqualTo("LOW");
		assertThat(clauses.get(1).get("riskType").asText()).isEqualTo("PENALTY");
		assertThat(clauses.get(2).get("riskLevel").asText()).isEqualTo("LOW");
		assertThat(clauses.get(2).get("riskType").asText()).isEqualTo("PAYMENT");

		// Rationale is readable (non-blank) — never assert its prose (oracle-problem guard).
		for (JsonNode clause : clauses) {
			assertThat(clause.get("rationale").asText()).isNotBlank();
		}

		long highClauseId = clauses.get(0).get("id").asLong();
		long mediumClauseId = clauses.get(1).get("id").asLong();
		Set<Long> riskyClauseIds = Set.of(highClauseId, mediumClauseId);

		// Linkage: exactly the two risky clauses get a negotiation point; the LOW clause gets none.
		assertThat(points).hasSize(2);
		for (JsonNode point : points) {
			assertThat(point.get("clauseId").isNumber())
					.as("negotiation point must link to a persisted clause")
					.isTrue();
			assertThat(riskyClauseIds).contains(point.get("clauseId").asLong());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MockChatModelConfig {

		@Bean
		@Primary
		ChatModel mockChatModel() {
			ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(MULTI_CLAUSE_JSON))));
			return prompt -> response;
		}

	}

}
