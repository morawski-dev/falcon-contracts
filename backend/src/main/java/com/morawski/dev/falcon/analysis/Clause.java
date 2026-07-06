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
@Table(name = "clauses")
public class Clause {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "analysis_id", nullable = false)
	private Analysis analysis;

	@Column(nullable = false, length = 10000)
	private String text;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_level", nullable = false, length = 10)
	private RiskLevel riskLevel;

	@Enumerated(EnumType.STRING)
	@Column(name = "risk_type", nullable = false, length = 30)
	private RiskType riskType;

	@Column(nullable = false, length = 2000)
	private String rationale;

	@Enumerated(EnumType.STRING)
	@Column(name = "user_decision", nullable = false, length = 20)
	private ClauseDecision userDecision;

	protected Clause() {
	}

	public Clause(Analysis analysis, String text, RiskLevel riskLevel, RiskType riskType, String rationale) {
		this.analysis = analysis;
		this.text = text;
		this.riskLevel = riskLevel;
		this.riskType = riskType;
		this.rationale = rationale;
		this.userDecision = ClauseDecision.PENDING;
		analysis.getClauses().add(this);
	}

	public Long getId() {
		return id;
	}

	public String getText() {
		return text;
	}

	public RiskLevel getRiskLevel() {
		return riskLevel;
	}

	public RiskType getRiskType() {
		return riskType;
	}

	public String getRationale() {
		return rationale;
	}

	public ClauseDecision getUserDecision() {
		return userDecision;
	}

}
