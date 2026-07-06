package com.morawski.dev.falcon.analysis;

public class AnalysisFailedException extends RuntimeException {

	public AnalysisFailedException(String message) {
		super(message);
	}

	public AnalysisFailedException(String message, Throwable cause) {
		super(message, cause);
	}

}
