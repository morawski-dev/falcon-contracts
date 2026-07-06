package com.morawski.dev.falcon.analysis.dto;

import com.morawski.dev.falcon.analysis.AnalysisStatus;

import java.time.Instant;
import java.util.List;

public record AnalysisResponse(
		Long id,
		String title,
		AnalysisStatus status,
		Instant createdAt,
		List<ClauseResponse> clauses,
		List<NegotiationPointResponse> negotiationPoints) {
}
