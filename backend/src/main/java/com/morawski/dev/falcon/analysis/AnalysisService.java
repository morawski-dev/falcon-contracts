package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.dto.AnalysisResponse;
import com.morawski.dev.falcon.analysis.dto.AnalysisSummaryResponse;
import com.morawski.dev.falcon.analysis.dto.ClauseResponse;
import com.morawski.dev.falcon.analysis.dto.NegotiationPointResponse;
import com.morawski.dev.falcon.analysis.llm.AnalyzedClause;
import com.morawski.dev.falcon.analysis.llm.ClauseAnalysisResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AnalysisService {

	private final ContractAnalysisService contractAnalysisService;
	private final AnalysisRepository analysisRepository;
	private final TransactionTemplate transactionTemplate;

	public AnalysisService(ContractAnalysisService contractAnalysisService, AnalysisRepository analysisRepository,
			PlatformTransactionManager transactionManager) {
		this.contractAnalysisService = contractAnalysisService;
		this.analysisRepository = analysisRepository;
		// A plain @Transactional on a private/self-invoked method wouldn't apply (Spring's
		// proxy-based AOP doesn't intercept calls made via `this`), so the persist+map step
		// below uses TransactionTemplate to get a real transaction boundary without a second bean.
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public AnalysisResponse createAnalysis(String title, String rawText, Long ownerId) {
		// The ~15s LLM call runs before any transaction — no DB connection is held across it.
		ClauseAnalysisResult result = contractAnalysisService.analyze(rawText);
		// Persistence + the lazy-collection response mapping run inside one explicit transaction,
		// so toResponse() never maps a detached entity (the bug getAnalysis() was fixed for).
		return transactionTemplate.execute(status -> persistAndRespond(title, rawText, ownerId, result));
	}

	private AnalysisResponse persistAndRespond(String title, String rawText, Long ownerId, ClauseAnalysisResult result) {
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

	public List<AnalysisSummaryResponse> listAnalyses(Long ownerId) {
		return analysisRepository.findSummariesByOwnerId(ownerId);
	}

	@Transactional(readOnly = true)
	public AnalysisResponse getAnalysis(Long id, Long ownerId) {
		// Without an open transaction spanning both the fetch and toResponse()'s mapping, the
		// Analysis returned by findByIdAndOwnerId is detached by the time its lazy @OneToMany
		// collections (clauses, negotiationPoints) are accessed, throwing LazyInitializationException.
		Analysis analysis = analysisRepository.findByIdAndOwnerId(id, ownerId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return toResponse(analysis);
	}

	@Transactional
	public ClauseResponse updateClauseDecision(Long analysisId, Long clauseId, ClauseDecision decision, Long ownerId) {
		Analysis analysis = analysisRepository.findByIdAndOwnerId(analysisId, ownerId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		try {
			Clause updated = analysis.decide(clauseId, decision);
			// dirty-checking flushes on commit; no save() call
			return toClauseResponse(updated);
		} catch (NoSuchElementException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
	}

	@Transactional
	public void deleteAnalysis(Long id, Long ownerId) {
		int deleted = analysisRepository.deleteOwned(id, ownerId);
		if (deleted == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
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
				.map(AnalysisService::toClauseResponse)
				.toList();
		List<NegotiationPointResponse> points = analysis.getNegotiationPoints().stream()
				.map(p -> new NegotiationPointResponse(p.getId(), p.getClauseId(), p.getRecommendation(), p.getPriority()))
				.toList();
		return new AnalysisResponse(analysis.getId(), analysis.getTitle(), analysis.getStatus(), analysis.getCreatedAt(),
				clauses, points);
	}

	private static ClauseResponse toClauseResponse(Clause c) {
		return new ClauseResponse(c.getId(), c.getText(), c.getRiskLevel(), c.getRiskType(), c.getRationale(),
				c.getUserDecision());
	}

}
