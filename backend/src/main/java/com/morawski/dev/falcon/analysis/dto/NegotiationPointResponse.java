package com.morawski.dev.falcon.analysis.dto;

import com.morawski.dev.falcon.analysis.RiskLevel;

public record NegotiationPointResponse(
		Long id,
		Long clauseId,
		String recommendation,
		RiskLevel priority) {
}
