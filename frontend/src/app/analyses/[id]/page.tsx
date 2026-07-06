"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Alert, AlertTitle, AlertDescription } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { getAnalysis, type Analysis, type NegotiationPoint } from "@/lib/analyses";
import { ApiError } from "@/lib/api";
import { RISK_LEVEL_BADGE_CLASS, RISK_LEVEL_LABEL, RISK_TYPE_LABEL } from "@/lib/risk";

export default function AnalysisResultPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);

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

  if (loading) {
    return (
      <div className="flex flex-1 justify-center p-6">
        <div className="flex w-full max-w-2xl flex-col gap-4">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
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
      <div className="flex w-full max-w-2xl flex-col gap-4">
        <Alert>
          <AlertTitle>To nie jest porada prawna</AlertTitle>
          <AlertDescription>
            Falcon wskazuje potencjalne ryzyka i sugestie do rozmowy. Wynik ma charakter pomocniczy
            i nie zastępuje konsultacji z prawnikiem.
          </AlertDescription>
        </Alert>

        <Card>
          <CardHeader>
            <CardTitle>{analysis.title}</CardTitle>
          </CardHeader>
        </Card>

        {analysis.clauses.map((clause) => (
          <Card key={clause.id}>
            <CardContent className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge className={RISK_LEVEL_BADGE_CLASS[clause.riskLevel]}>
                  {RISK_LEVEL_LABEL[clause.riskLevel]}
                </Badge>
                <Badge variant="outline">{RISK_TYPE_LABEL[clause.riskType]}</Badge>
              </div>
              <p className="text-sm text-foreground">{clause.text}</p>
              <p className="text-sm text-muted-foreground">{clause.rationale}</p>

              {(pointsByClauseId.get(clause.id) ?? []).map((point) => (
                <div key={point.id} className="rounded-lg border border-border bg-muted/50 p-3">
                  <p className="text-xs font-medium text-muted-foreground">Punkt do negocjacji</p>
                  <p className="text-sm text-foreground">{point.recommendation}</p>
                </div>
              ))}
            </CardContent>
          </Card>
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
                  <div key={point.id} className="rounded-lg border border-border bg-muted/50 p-3">
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
      </div>
    </div>
  );
}
