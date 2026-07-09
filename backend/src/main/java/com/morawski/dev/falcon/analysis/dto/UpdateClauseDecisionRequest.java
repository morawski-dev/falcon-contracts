package com.morawski.dev.falcon.analysis.dto;

import com.morawski.dev.falcon.analysis.ClauseDecision;
import jakarta.validation.constraints.NotNull;

public record UpdateClauseDecisionRequest(
		@NotNull ClauseDecision decision) {
}
