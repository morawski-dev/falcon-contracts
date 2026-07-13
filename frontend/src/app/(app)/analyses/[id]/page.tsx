"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Alert, AlertTitle, AlertDescription } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import {
  getAnalysis,
  updateClauseDecision,
  deleteAnalysis,
  CLAUSE_DECISION_LABEL,
  type Analysis,
  type ClauseDecision,
  type NegotiationPoint,
} from "@/lib/analyses";
import { ApiError } from "@/lib/api";
import {
  RISK_LEVEL_BADGE_CLASS,
  RISK_LEVEL_LABEL,
  RISK_LEVEL_RULE_CLASS,
  RISK_LEVEL_TEXT_CLASS,
  RISK_TYPE_LABEL,
} from "@/lib/risk";
import { cn } from "@/lib/utils";

const DECISION_OPTIONS: ClauseDecision[] = ["ACCEPTED", "TO_NEGOTIATE", "REJECTED"];

export default function AnalysisResultPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [decisionErrors, setDecisionErrors] = useState<Record<number, string>>({});
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    getAnalysis(params.id)
      .then(setAnalysis)
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          router.push("/login");
        } else {
          setNotFound(true);
        }
      })
      .finally(() => setLoading(false));
  }, [params.id, router]);

  async function handleDecide(clauseId: number, decision: ClauseDecision) {
    if (!analysis) {
      return;
    }
    const clause = analysis.clauses.find((c) => c.id === clauseId);
    if (!clause) {
      return;
    }
    const previousDecision = clause.userDecision;
    const nextDecision = previousDecision === decision ? "PENDING" : decision;

    setAnalysis({
      ...analysis,
      clauses: analysis.clauses.map((c) =>
        c.id === clauseId ? { ...c, userDecision: nextDecision } : c
      ),
    });
    setDecisionErrors((prev) => {
      const next = { ...prev };
      delete next[clauseId];
      return next;
    });

    try {
      await updateClauseDecision(analysis.id, clauseId, nextDecision);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/login");
        return;
      }
      setAnalysis((current) =>
        current
          ? {
              ...current,
              clauses: current.clauses.map((c) =>
                c.id === clauseId ? { ...c, userDecision: previousDecision } : c
              ),
            }
          : current
      );
      setDecisionErrors((prev) => ({
        ...prev,
        [clauseId]: "Nie udało się zapisać decyzji. Spróbuj ponownie.",
      }));
    }
  }

  async function handleDeleteAnalysis() {
    if (!analysis) {
      return;
    }
    setDeleteError(null);
    try {
      await deleteAnalysis(analysis.id);
      router.push("/dashboard");
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/login");
        return;
      }
      setDeleteError("Nie udało się usunąć analizy. Spróbuj ponownie.");
    }
  }

  if (loading) {
    return (
      <div className="flex flex-1 justify-center p-6">
        <div className="flex w-full max-w-2xl flex-col gap-6">
          <Skeleton className="h-9 w-2/3" />
          {[0, 1, 2].map((i) => (
            <div key={i} className="grid grid-cols-[3.25rem_1fr] gap-x-4 border-b border-border py-6">
              <Skeleton className="h-full w-0.5 justify-self-center" />
              <div className="flex flex-col gap-3">
                <Skeleton className="h-4 w-24" />
                <Skeleton className="h-16 w-full" />
                <Skeleton className="h-4 w-1/2" />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (notFound || !analysis) {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <Alert variant="destructive" className="w-full max-w-md">
          <AlertTitle>Nie znaleziono analizy</AlertTitle>
          <AlertDescription>
            Ta analiza nie istnieje albo nie masz do niej dostępu.
          </AlertDescription>
        </Alert>
      </div>
    );
  }

  const pointsByClauseId = new Map<number, NegotiationPoint[]>();
  const unlinkedPoints: NegotiationPoint[] = [];
  for (const point of analysis.negotiationPoints) {
    if (point.clauseId === null) {
      unlinkedPoints.push(point);
    } else {
      const existing = pointsByClauseId.get(point.clauseId) ?? [];
      existing.push(point);
      pointsByClauseId.set(point.clauseId, existing);
    }
  }

  return (
    <div className="flex flex-1 justify-center p-6">
      <div className="flex w-full max-w-2xl flex-col gap-6">
        <Alert>
          <AlertTitle>To nie jest porada prawna</AlertTitle>
          <AlertDescription>
            Falcon wskazuje potencjalne ryzyka i sugestie do rozmowy. Wynik ma charakter pomocniczy
            i nie zastępuje konsultacji z prawnikiem.
          </AlertDescription>
        </Alert>

        <div className="flex items-start justify-between gap-4 border-b border-border pb-4">
          <h1 className="font-display text-2xl font-medium text-balance text-foreground">
            {analysis.title}
          </h1>
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button type="button" variant="destructive" size="sm">
                Usuń
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Usunąć analizę?</AlertDialogTitle>
                <AlertDialogDescription>Tej operacji nie można cofnąć.</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Anuluj</AlertDialogCancel>
                <AlertDialogAction variant="destructive" onClick={handleDeleteAnalysis}>
                  Usuń
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
        {deleteError && <p className="text-xs text-destructive">{deleteError}</p>}

        {analysis.clauses.map((clause, index) => (
          <div
            key={clause.id}
            data-testid={`clause-${clause.id}`}
            className="grid grid-cols-[3.25rem_1fr] gap-x-4 border-b border-border py-6 last:border-b-0"
          >
            <div className="flex flex-col items-center gap-2 text-center">
              <span className="font-mono text-xs text-stamp">§{index + 1}</span>
              <div
                className={cn("rule-draw min-h-10 flex-1 rounded-full", RISK_LEVEL_RULE_CLASS[clause.riskLevel])}
                style={{ animationDelay: `${index * 80}ms` }}
              />
              <span
                className={cn(
                  "font-mono text-[0.625rem] leading-tight tracking-wide uppercase",
                  RISK_LEVEL_TEXT_CLASS[clause.riskLevel]
                )}
              >
                {RISK_LEVEL_LABEL[clause.riskLevel]}
              </span>
            </div>

            <div className="flex flex-col gap-3 pb-1">
              <span className="font-mono text-xs text-muted-foreground">
                {RISK_TYPE_LABEL[clause.riskType]}
              </span>
              <p className="text-[0.9375rem] leading-relaxed text-foreground">{clause.text}</p>
              <p className="text-sm text-muted-foreground">{clause.rationale}</p>

              <div
                role="group"
                aria-label={`Decyzja: klauzula ${index + 1} (${RISK_TYPE_LABEL[clause.riskType]})`}
                className="flex flex-wrap gap-2"
              >
                {DECISION_OPTIONS.map((decision) => {
                  const isActive = clause.userDecision === decision;
                  return (
                    <Button
                      key={decision}
                      type="button"
                      size="sm"
                      variant={isActive ? "default" : "outline"}
                      aria-pressed={isActive}
                      onClick={() => handleDecide(clause.id, decision)}
                    >
                      {CLAUSE_DECISION_LABEL[decision]}
                    </Button>
                  );
                })}
              </div>
              {decisionErrors[clause.id] && (
                <p className="text-xs text-destructive">{decisionErrors[clause.id]}</p>
              )}

              {(pointsByClauseId.get(clause.id) ?? []).map((point) => (
                <div key={point.id} className="rounded-r-sm border-l border-border bg-muted/50 p-3">
                  <p className="font-mono text-[0.6875rem] tracking-wide text-muted-foreground uppercase">
                    Punkt do negocjacji
                  </p>
                  <p className="text-sm text-foreground">{point.recommendation}</p>
                </div>
              ))}
            </div>
          </div>
        ))}

        {unlinkedPoints.length > 0 && (
          <>
            <Separator />
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Pozostałe uwagi</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                {unlinkedPoints.map((point) => (
                  <div key={point.id} className="rounded-r-sm border-l border-border bg-muted/50 p-3">
                    <Badge className={RISK_LEVEL_BADGE_CLASS[point.priority]}>
                      {RISK_LEVEL_LABEL[point.priority]}
                    </Badge>
                    <p className="mt-2 text-sm text-foreground">{point.recommendation}</p>
                  </div>
                ))}
              </CardContent>
            </Card>
          </>
        )}

        <div className="flex justify-center border-t border-border pt-6">
          <Link
            href="/dashboard"
            className="rounded text-sm font-medium text-stamp underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/70"
          >
            Wróć do moich analiz
          </Link>
        </div>
      </div>
    </div>
  );
}
