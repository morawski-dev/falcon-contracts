package com.morawski.dev.falcon.analysis.llm;

import com.morawski.dev.falcon.analysis.RiskLevel;

public record NegotiationPoint(
		String clauseText,
		String recommendation,
		RiskLevel priority) {
}
