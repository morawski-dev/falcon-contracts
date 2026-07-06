package com.morawski.dev.falcon.analysis;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AnalysisExceptionHandler {

	@ExceptionHandler(AnalysisFailedException.class)
	public ResponseEntity<Map<String, String>> handleAnalysisFailed(AnalysisFailedException ex) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", "Failed to analyze contract. Please try again."));
	}

}
