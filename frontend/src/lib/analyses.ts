import { apiFetch } from "@/lib/api";
import { getCsrf } from "@/lib/auth";
import type { RiskLevel, RiskType } from "@/lib/risk";

export type ClauseDecision = "PENDING" | "ACCEPTED" | "TO_NEGOTIATE" | "REJECTED";

export const CLAUSE_DECISION_LABEL: Record<ClauseDecision, string> = {
  PENDING: "Bez decyzji",
  ACCEPTED: "Akceptuję",
  TO_NEGOTIATE: "Do negocjacji",
  REJECTED: "Odrzucam",
};

export type AnalysisStatus = "DRAFT" | "ANALYZED" | "REVIEWED";

export const ANALYSIS_STATUS_LABEL: Record<AnalysisStatus, string> = {
  DRAFT: "Szkic",
  ANALYZED: "Przeanalizowana",
  REVIEWED: "Sprawdzona",
};

export type Clause = {
  id: number;
  text: string;
  riskLevel: RiskLevel;
  riskType: RiskType;
  rationale: string;
  userDecision: ClauseDecision;
};

export type NegotiationPoint = {
  id: number;
  clauseId: number | null;
  recommendation: string;
  priority: RiskLevel;
};

export type Analysis = {
  id: number;
  title: string;
  status: AnalysisStatus;
  createdAt: string;
  clauses: Clause[];
  negotiationPoints: NegotiationPoint[];
};

export type AnalysisSummary = {
  id: number;
  title: string;
  status: AnalysisStatus;
  createdAt: string;
};

export async function createAnalysis(title: string, rawText: string): Promise<Analysis> {
  await getCsrf();
  const response = await apiFetch("/api/analyses", {
    method: "POST",
    body: JSON.stringify({ title, rawText }),
  });
  return response.json();
}

export async function getAnalysis(id: number | string): Promise<Analysis> {
  const response = await apiFetch(`/api/analyses/${id}`);
  return response.json();
}

export async function getAnalyses(): Promise<AnalysisSummary[]> {
  const response = await apiFetch("/api/analyses");
  return response.json();
}

export async function updateClauseDecision(
  analysisId: number,
  clauseId: number,
  decision: ClauseDecision
): Promise<Clause> {
  await getCsrf();
  const response = await apiFetch(`/api/analyses/${analysisId}/clauses/${clauseId}`, {
    method: "PATCH",
    body: JSON.stringify({ decision }),
  });
  return response.json();
}
