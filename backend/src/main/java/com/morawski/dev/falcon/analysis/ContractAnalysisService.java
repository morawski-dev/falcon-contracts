package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.llm.AnalyzedClause;
import com.morawski.dev.falcon.analysis.llm.ClauseAnalysisResult;
import com.morawski.dev.falcon.analysis.llm.NegotiationPoint;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class ContractAnalysisService {

	private static final int MAX_CLAUSE_TEXT_LENGTH = 10000;
	private static final int MAX_RATIONALE_LENGTH = 2000;
	private static final int MAX_RECOMMENDATION_LENGTH = 2000;

	private static final String SYSTEM_PROMPT = """
			Jesteś asystentem prawnym wspierającym osobę, która ma podpisać umowę.
			Twoim zadaniem jest analiza klauzul umowy pod kątem ryzyka dla TEJ osoby
			(strony słabszej / podpisującej), a nie dla drugiej strony.

			Zasady:
			- Analizuj wyłącznie treść przekazanej umowy. Nie dopowiadaj zapisów,
			  których nie ma w tekście.
			- Dla każdej klauzuli oceń poziom ryzyka, przypisz typ ryzyka i podaj
			  zwięzłe uzasadnienie po polsku (1-2 zdania).
			- Dla klauzul o ryzyku MEDIUM lub HIGH zaproponuj konkretny punkt do
			  negocjacji: co zmienić i dlaczego.
			- Nie udzielasz porady prawnej. Wskazujesz ryzyka i sugestie do rozmowy.
			- Odpowiadasz WYŁĄCZNIE w formacie JSON zgodnym z podanym schematem.
			  Nie dodawaj komentarzy, wstępu ani znaczników markdown.

			Poziomy ryzyka:
			- LOW: standardowy, typowy zapis bez istotnych zagrożeń.
			- MEDIUM: zapis wymagający uwagi, potencjalnie niekorzystny.
			- HIGH: zapis poważnie niekorzystny lub jednostronny.

			Typy ryzyka (riskType) wybierz jeden z:
			PENALTY (kara umowna), AUTO_RENEWAL (automatyczne przedłużenie),
			NO_TERMINATION (brak/utrudnione wypowiedzenie),
			UNILATERAL_CHANGE (jednostronna zmiana warunków),
			LIABILITY (odpowiedzialność/odszkodowania),
			CONFIDENTIALITY (poufność/zakaz konkurencji),
			IP_RIGHTS (prawa autorskie/własność intelektualna),
			PAYMENT (warunki płatności), OTHER (inne).
			""";

	private static final String USER_PROMPT = """
			Przeanalizuj poniższą umowę. Podziel ją na klauzule i dla każdej wykonaj klasyfikację.

			UMOWA:
			---
			{contractText}
			---
			""";

	private final ChatClient chatClient;

	public ContractAnalysisService(ChatClient.Builder builder) {
		this.chatClient = builder.build();
	}

	public ClauseAnalysisResult analyze(String contractText) {
		ClauseAnalysisResult result;
		try {
			result = chatClient.prompt()
					.system(SYSTEM_PROMPT)
					.user(u -> u.text(USER_PROMPT).param("contractText", contractText))
					.call()
					.entity(ClauseAnalysisResult.class);
		} catch (RuntimeException e) {
			// Covers BeanOutputConverter's out-of-enum/schema-mismatch failures (IllegalStateException),
			// raw parse failures like non-JSON content (tools.jackson.core.JacksonException, unwrapped),
			// and any transport/API failure from the model call itself (timeouts, 4xx/5xx from OpenRouter) —
			// every failure mode of this one external-call boundary funnels through the same controlled error.
			throw new AnalysisFailedException("Failed to analyze contract", e);
		}

		if (result == null || result.clauses() == null || result.clauses().isEmpty()) {
			throw new AnalysisFailedException("Model returned no clauses");
		}
		validateFieldsPresent(result);
		return result;
	}

	private void validateFieldsPresent(ClauseAnalysisResult result) {
		for (AnalyzedClause clause : result.clauses()) {
			if (!StringUtils.hasText(clause.text()) || !StringUtils.hasText(clause.rationale())
					|| clause.riskLevel() == null || clause.riskType() == null) {
				throw new AnalysisFailedException("Model returned an incomplete clause");
			}
			if (clause.text().length() > MAX_CLAUSE_TEXT_LENGTH || clause.rationale().length() > MAX_RATIONALE_LENGTH) {
				// The persisted Clause columns are sized to these same limits (ddl-auto=validate) —
				// reject here so an oversized field never reaches a DB constraint violation.
				throw new AnalysisFailedException("Model returned a clause exceeding the allowed length");
			}
		}
		List<NegotiationPoint> points = result.negotiationPoints();
		if (points != null) {
			for (NegotiationPoint point : points) {
				if (!StringUtils.hasText(point.recommendation()) || point.priority() == null) {
					throw new AnalysisFailedException("Model returned an incomplete negotiation point");
				}
				if (point.recommendation().length() > MAX_RECOMMENDATION_LENGTH) {
					throw new AnalysisFailedException("Model returned a negotiation point exceeding the allowed length");
				}
			}
		}
	}

}
