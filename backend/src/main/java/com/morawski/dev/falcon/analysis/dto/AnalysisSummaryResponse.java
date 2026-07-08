package com.morawski.dev.falcon.analysis.dto;

import com.morawski.dev.falcon.analysis.AnalysisStatus;

import java.time.Instant;

public record AnalysisSummaryResponse(
		Long id,
		String title,
		AnalysisStatus status,
		Instant createdAt) {
}
