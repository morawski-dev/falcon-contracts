import { apiFetch } from "@/lib/api";
import { getCsrf } from "@/lib/auth";
import type { RiskLevel, RiskType } from "@/lib/risk";

export type ClauseDecision = "PENDING" | "ACCEPTED" | "TO_NEGOTIATE" | "REJECTED";

export type AnalysisStatus = "DRAFT" | "ANALYZED" | "REVIEWED";

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
