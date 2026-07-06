package com.morawski.dev.falcon.analysis;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "analyses")
public class Analysis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(name = "raw_text", nullable = false, length = 20000)
	private String rawText;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AnalysisStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Clause> clauses = new ArrayList<>();

	@OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<NegotiationPoint> negotiationPoints = new ArrayList<>();

	protected Analysis() {
	}

	public Analysis(Long ownerId, String title, String rawText, AnalysisStatus status, Instant createdAt) {
		this.ownerId = ownerId;
		this.title = title;
		this.rawText = rawText;
		this.status = status;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public String getTitle() {
		return title;
	}

	public String getRawText() {
		return rawText;
	}

	public AnalysisStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<Clause> getClauses() {
		return clauses;
	}

	public List<NegotiationPoint> getNegotiationPoints() {
		return negotiationPoints;
	}

}
