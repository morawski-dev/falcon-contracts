package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.llm.ClauseAnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A plain unit test — no Spring context needed. ContractAnalysisService only depends on
 * ChatClient.Builder, which can be built directly via ChatClient.builder(ChatModel) around a
 * stub lambda. This keeps the real ChatClient + BeanOutputConverter + service logic in the test
 * path (never calls OpenRouter) while letting each test supply a distinct mock response.
 */
class ContractAnalysisServiceTest {

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
			      "clauseText": "Umowa ulega automatycznemu przedłużeniu...",
			      "recommendation": "Skrócić okres wypowiedzenia do 30 dni lub usunąć automatyczne przedłużenie.",
			      "priority": "HIGH"
			    }
			  ]
			}
			""";

	private ContractAnalysisService serviceReturning(String content) {
		ChatClient.Builder builder = ChatClient.builder(
				prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
		return new ContractAnalysisService(builder);
	}

	@Test
	void mapsFixedJsonToRecords() {
		ContractAnalysisService service = serviceReturning(FIXED_JSON);

		ClauseAnalysisResult result = service.analyze("umowa najmu z automatycznym przedłużeniem...");

		assertThat(result.clauses()).hasSize(1);
		assertThat(result.clauses().get(0).riskLevel()).isEqualTo(RiskLevel.HIGH);
		assertThat(result.clauses().get(0).riskType()).isEqualTo(RiskType.AUTO_RENEWAL);
		assertThat(result.negotiationPoints()).hasSize(1);
		assertThat(result.negotiationPoints().get(0).priority()).isEqualTo(RiskLevel.HIGH);
	}

	@Test
	void malformedJsonRaisesAnalysisFailedException() {
		ContractAnalysisService service = serviceReturning("this is not JSON at all");

		assertThatThrownBy(() -> service.analyze("umowa..."))
				.isInstanceOf(AnalysisFailedException.class);
	}

	@Test
	void emptyClausesRaisesAnalysisFailedException() {
		ContractAnalysisService service = serviceReturning("""
				{ "clauses": [], "negotiationPoints": [] }
				""");

		assertThatThrownBy(() -> service.analyze("umowa..."))
				.isInstanceOf(AnalysisFailedException.class);
	}

	@Test
	void incompleteClauseFieldRaisesAnalysisFailedException() {
		ContractAnalysisService service = serviceReturning("""
				{
				  "clauses": [
				    { "text": "", "riskLevel": "HIGH", "riskType": "AUTO_RENEWAL", "rationale": "uzasadnienie" }
				  ],
				  "negotiationPoints": []
				}
				""");

		assertThatThrownBy(() -> service.analyze("umowa..."))
				.isInstanceOf(AnalysisFailedException.class);
	}

}
