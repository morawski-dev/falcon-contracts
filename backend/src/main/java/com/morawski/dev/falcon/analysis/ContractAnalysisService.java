package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.llm.AnalyzedClause;
import com.morawski.dev.falcon.analysis.llm.ClauseAnalysisResult;
import com.morawski.dev.falcon.analysis.llm.NegotiationPoint;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;

import java.util.List;

@Service
public class ContractAnalysisService {

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
		} catch (IllegalStateException | JacksonException e) {
			// IllegalStateException: BeanOutputConverter wraps out-of-enum/schema-mismatch failures.
			// JacksonException: raw parse failures (e.g. non-JSON content) surface unwrapped.
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
		}
		List<NegotiationPoint> points = result.negotiationPoints();
		if (points != null) {
			for (NegotiationPoint point : points) {
				if (!StringUtils.hasText(point.recommendation()) || point.priority() == null) {
					throw new AnalysisFailedException("Model returned an incomplete negotiation point");
				}
			}
		}
	}

}
