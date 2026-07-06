package com.morawski.dev.falcon.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "negotiation_points")
public class NegotiationPoint {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "analysis_id", nullable = false)
	private Analysis analysis;

	@Column(name = "clause_id")
	private Long clauseId;

	@Column(nullable = false, length = 2000)
	private String recommendation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private RiskLevel priority;

	protected NegotiationPoint() {
	}

	public NegotiationPoint(Analysis analysis, String recommendation, RiskLevel priority) {
		this.analysis = analysis;
		this.recommendation = recommendation;
		this.priority = priority;
		analysis.getNegotiationPoints().add(this);
	}

	public Long getId() {
		return id;
	}

	public Long getClauseId() {
		return clauseId;
	}

	public void setClauseId(Long clauseId) {
		this.clauseId = clauseId;
	}

	public String getRecommendation() {
		return recommendation;
	}

	public RiskLevel getPriority() {
		return priority;
	}

}
