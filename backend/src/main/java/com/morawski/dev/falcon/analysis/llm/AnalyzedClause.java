package com.morawski.dev.falcon.analysis.llm;

import com.morawski.dev.falcon.analysis.RiskLevel;
import com.morawski.dev.falcon.analysis.RiskType;

public record AnalyzedClause(
		String text,
		RiskLevel riskLevel,
		RiskType riskType,
		String rationale) {
}
