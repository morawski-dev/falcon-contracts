package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.dto.AnalysisResponse;
import com.morawski.dev.falcon.analysis.dto.CreateAnalysisRequest;
import com.morawski.dev.falcon.user.AppUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

	private final AnalysisService analysisService;

	public AnalysisController(AnalysisService analysisService) {
		this.analysisService = analysisService;
	}

	@PostMapping
	public ResponseEntity<AnalysisResponse> create(@Valid @RequestBody CreateAnalysisRequest request,
			@AuthenticationPrincipal AppUserDetails principal) {
		AnalysisResponse response = analysisService.createAnalysis(request.title(), request.rawText(), principal.getId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/{id}")
	public AnalysisResponse get(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails principal) {
		return analysisService.getAnalysis(id, principal.getId());
	}

}
