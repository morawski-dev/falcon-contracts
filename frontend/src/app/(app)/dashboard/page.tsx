"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
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
import { getAnalyses, deleteAnalysis, ANALYSIS_STATUS_LABEL, type AnalysisSummary } from "@/lib/analyses";
import { ApiError } from "@/lib/api";

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("pl-PL", { year: "numeric", month: "long", day: "numeric" });
}

export default function DashboardPage() {
  const router = useRouter();
  const [analyses, setAnalyses] = useState<AnalysisSummary[] | null>(null);
  const [analysesLoading, setAnalysesLoading] = useState(true);
  const [deleteErrors, setDeleteErrors] = useState<Record<number, string>>({});

  useEffect(() => {
    getAnalyses()
      .then(setAnalyses)
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          router.push("/login");
        }
      })
      .finally(() => setAnalysesLoading(false));
  }, [router]);

  async function handleDelete(id: number) {
    setDeleteErrors((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    try {
      await deleteAnalysis(id);
      setAnalyses((prev) => prev?.filter((a) => a.id !== id) ?? prev);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/login");
        return;
      }
      setDeleteErrors((prev) => ({
        ...prev,
        [id]: "Nie udało się usunąć analizy. Spróbuj ponownie.",
      }));
    }
  }

  return (
    <div className="flex flex-1 justify-center p-6">
      <div className="flex w-full max-w-2xl flex-col gap-4">
        {analysesLoading && (
          <div className="flex flex-col gap-3">
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-20 w-full" />
          </div>
        )}

        {!analysesLoading && analyses !== null && analyses.length === 0 && (
          <div className="flex flex-col items-center gap-4 border border-dashed border-border py-16 text-center">
            <p className="max-w-xs text-sm text-muted-foreground">
              Nie masz jeszcze żadnej analizy.
            </p>
            <Button asChild size="sm">
              <Link href="/analyses/new">Wklej pierwszą umowę</Link>
            </Button>
          </div>
        )}

        {!analysesLoading && analyses !== null && analyses.length > 0 && (
          <div className="flex flex-col">
            {analyses.map((analysis) => (
              <div key={analysis.id} className="flex flex-col gap-2 border-b border-border py-4 last:border-b-0">
                <div className="flex items-center justify-between gap-4">
                  <Link
                    href={`/analyses/${analysis.id}`}
                    className="flex flex-1 flex-col gap-1 rounded outline-none focus-visible:ring-3 focus-visible:ring-ring/70"
                  >
                    <span className="text-[0.9375rem] font-medium text-foreground">{analysis.title}</span>
                    <span className="font-mono text-xs text-muted-foreground">{formatDate(analysis.createdAt)}</span>
                  </Link>
                  <div className="flex items-center gap-2">
                    <Badge variant="outline">{ANALYSIS_STATUS_LABEL[analysis.status]}</Badge>
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button
                          type="button"
                          variant="destructive"
                          size="sm"
                          aria-label={`Usuń analizę: ${analysis.title}`}
                        >
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
                          <AlertDialogAction variant="destructive" onClick={() => handleDelete(analysis.id)}>
                            Usuń
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  </div>
                </div>
                {deleteErrors[analysis.id] && (
                  <p className="text-xs text-destructive">{deleteErrors[analysis.id]}</p>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
