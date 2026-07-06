package com.morawski.dev.falcon.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAnalysisRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 20000) String rawText) {
}
