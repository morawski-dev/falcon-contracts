package com.morawski.dev.falcon.analysis.llm;

import java.util.List;

public record ClauseAnalysisResult(
		List<AnalyzedClause> clauses,
		List<NegotiationPoint> negotiationPoints) {
}
