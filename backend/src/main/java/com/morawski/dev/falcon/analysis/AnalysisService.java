package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.dto.AnalysisResponse;
import com.morawski.dev.falcon.analysis.dto.ClauseResponse;
import com.morawski.dev.falcon.analysis.dto.NegotiationPointResponse;
import com.morawski.dev.falcon.analysis.llm.AnalyzedClause;
import com.morawski.dev.falcon.analysis.llm.ClauseAnalysisResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class AnalysisService {

	private final ContractAnalysisService contractAnalysisService;
	private final AnalysisRepository analysisRepository;

	public AnalysisService(ContractAnalysisService contractAnalysisService, AnalysisRepository analysisRepository) {
		this.contractAnalysisService = contractAnalysisService;
		this.analysisRepository = analysisRepository;
	}

	public AnalysisResponse createAnalysis(String title, String rawText, Long ownerId) {
		// The ~15s LLM call runs before any repository call — no DB transaction/connection is
		// held across it. Each repository call below opens its own short transaction (Spring
		// Data JPA's default), so persistence stays atomic without spanning the LLM wait.
		ClauseAnalysisResult result = contractAnalysisService.analyze(rawText);

		Analysis analysis = new Analysis(ownerId, title, rawText, AnalysisStatus.ANALYZED, Instant.now());
		for (AnalyzedClause analyzedClause : result.clauses()) {
			new Clause(analysis, analyzedClause.text(), analyzedClause.riskLevel(), analyzedClause.riskType(),
					analyzedClause.rationale());
		}
		// Clauses must have generated ids before negotiation points can be matched to them.
		Analysis savedWithClauses = analysisRepository.saveAndFlush(analysis);

		List<com.morawski.dev.falcon.analysis.llm.NegotiationPoint> llmPoints = result.negotiationPoints();
		if (llmPoints != null) {
			for (com.morawski.dev.falcon.analysis.llm.NegotiationPoint llmPoint : llmPoints) {
				NegotiationPoint point = new NegotiationPoint(savedWithClauses, llmPoint.recommendation(), llmPoint.priority());
				point.setClauseId(matchClauseId(savedWithClauses, llmPoint.clauseText()));
			}
		}
		Analysis saved = analysisRepository.save(savedWithClauses);

		return toResponse(saved);
	}

	public AnalysisResponse getAnalysis(Long id, Long ownerId) {
		Analysis analysis = analysisRepository.findByIdAndOwnerId(id, ownerId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return toResponse(analysis);
	}

	private Long matchClauseId(Analysis analysis, String clauseText) {
		if (clauseText == null || clauseText.isBlank()) {
			return null;
		}
		for (Clause clause : analysis.getClauses()) {
			if (clause.getText().equals(clauseText)) {
				return clause.getId();
			}
		}
		for (Clause clause : analysis.getClauses()) {
			if (clause.getText().contains(clauseText) || clauseText.contains(clause.getText())) {
				return clause.getId();
			}
		}
		return null;
	}

	private AnalysisResponse toResponse(Analysis analysis) {
		List<ClauseResponse> clauses = analysis.getClauses().stream()
				.map(c -> new ClauseResponse(c.getId(), c.getText(), c.getRiskLevel(), c.getRiskType(), c.getRationale(),
						c.getUserDecision()))
				.toList();
		List<NegotiationPointResponse> points = analysis.getNegotiationPoints().stream()
				.map(p -> new NegotiationPointResponse(p.getId(), p.getClauseId(), p.getRecommendation(), p.getPriority()))
				.toList();
		return new AnalysisResponse(analysis.getId(), analysis.getTitle(), analysis.getStatus(), analysis.getCreatedAt(),
				clauses, points);
	}

}
