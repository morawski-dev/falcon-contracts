"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
} from "@/components/ui/card";
import { createAnalysis } from "@/lib/analyses";
import { ApiError } from "@/lib/api";

export default function NewAnalysisPage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [rawText, setRawText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    if (!rawText.trim()) {
      setError("Wklej treść umowy, aby rozpocząć analizę.");
      return;
    }

    setSubmitting(true);
    try {
      const analysis = await createAnalysis(title, rawText);
      router.push(`/analyses/${analysis.id}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 400) {
        setError("Umowa jest zbyt długa lub tytuł jest pusty. Skróć tekst i spróbuj ponownie.");
      } else if (err instanceof ApiError && err.status === 502) {
        setError("Nie udało się przeanalizować umowy. Spróbuj ponownie.");
      } else {
        setError("Wystąpił nieoczekiwany błąd. Spróbuj ponownie.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex flex-1 items-center justify-center p-6">
      <Card className="w-full max-w-2xl">
        <CardHeader>
          <CardTitle>Nowa analiza</CardTitle>
          <CardDescription>
            Wklej treść umowy, a Falcon wskaże ryzykowne klauzule i zaproponuje punkty do
            negocjacji.
          </CardDescription>
        </CardHeader>
        <form onSubmit={handleSubmit}>
          <fieldset disabled={submitting} className="contents">
            <CardContent className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <Label htmlFor="title">Tytuł</Label>
                <Input
                  id="title"
                  required
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="np. Umowa najmu lokalu"
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="rawText">Treść umowy</Label>
                <Textarea
                  id="rawText"
                  required
                  rows={14}
                  value={rawText}
                  onChange={(e) => setRawText(e.target.value)}
                  placeholder="Wklej tutaj pełną treść umowy..."
                />
              </div>
              {error && <p className="text-sm text-destructive">{error}</p>}
              {submitting && (
                <div className="flex flex-col gap-2 rounded-lg border border-border bg-muted/50 p-3">
                  <div className="flex items-center gap-2 text-sm font-medium">
                    <Loader2 className="size-4 animate-spin" />
                    Analizuję umowę…
                  </div>
                  <p className="text-xs text-muted-foreground">
                    To zwykle trwa kilkanaście sekund. Nie zamykaj tej strony.
                  </p>
                  <div className="relative h-1.5 w-full overflow-hidden rounded-full bg-border">
                    <div className="absolute inset-y-0 w-1/3 rounded-full bg-primary animate-[indeterminate-progress_1.4s_ease-in-out_infinite]" />
                  </div>
                </div>
              )}
              <p className="text-xs text-muted-foreground">
                Falcon dostarcza analizę pomocniczą, a nie poradę prawną.
              </p>
            </CardContent>
            <CardFooter>
              <Button type="submit" className="w-full" disabled={submitting}>
                {submitting ? "Analizowanie…" : "Analizuj umowę"}
              </Button>
            </CardFooter>
          </fieldset>
        </form>
      </Card>
    </div>
  );
}
