package com.morawski.dev.falcon.analysis;

/**
 * Shared model-reply fixtures so backend contract tests and the {@code e2e} Spring profile
 * (see {@link E2eChatModelConfig}) agree on exactly one definition of "what a model reply
 * looks like" — a second copy would drift.
 */
final class ClauseAnalysisFixtures {

	/**
	 * 3 clauses with distinct risk types (HIGH/AUTO_RENEWAL, MEDIUM/PENALTY, LOW/PAYMENT) and
	 * 2 negotiation points, leaving the LOW clause unlinked. Originally
	 * {@code ClassificationContractTest.MULTI_CLAUSE_JSON}; the distinct risk types and the
	 * unlinked LOW clause are what let both the backend contract test and the browser e2e
	 * suite assert no-drop, never-downgraded, and selective-linkage in one fixture.
	 */
	static final String MULTI_CLAUSE_JSON = """
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

	private ClauseAnalysisFixtures() {
	}

}
