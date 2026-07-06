package com.morawski.dev.falcon.analysis.dto;

import com.morawski.dev.falcon.analysis.ClauseDecision;
import com.morawski.dev.falcon.analysis.RiskLevel;
import com.morawski.dev.falcon.analysis.RiskType;

public record ClauseResponse(
		Long id,
		String text,
		RiskLevel riskLevel,
		RiskType riskType,
		String rationale,
		ClauseDecision userDecision) {
}
