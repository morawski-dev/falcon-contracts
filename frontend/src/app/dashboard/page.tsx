"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { me, logout, type CurrentUser } from "@/lib/auth";

export default function DashboardPage() {
  const router = useRouter();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    me()
      .then(setUser)
      .catch(() => router.push("/login"))
      .finally(() => setLoading(false));
  }, [router]);

  async function handleLogout() {
    await logout();
    router.push("/login");
  }

  if (loading) {
    return null;
  }

  return (
    <div className="flex flex-1 items-center justify-center p-6">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Falcon</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <p className="text-sm text-foreground">Zalogowano jako {user?.email}</p>
          <Button asChild>
            <Link href="/analyses/new">Nowa analiza</Link>
          </Button>
          <Button variant="outline" onClick={handleLogout}>
            Wyloguj
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
