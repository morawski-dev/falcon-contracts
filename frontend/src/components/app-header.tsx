"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { logout } from "@/lib/auth";

export function AppHeader() {
  const router = useRouter();

  async function handleLogout() {
    try {
      await logout();
    } finally {
      router.push("/login");
    }
  }

  return (
    <header className="sticky top-0 z-10 h-[var(--header-height)] border-b border-ink/10 bg-background">
      <div className="mx-auto flex h-full w-full max-w-2xl items-center justify-between px-6">
        <Link
          href="/dashboard"
          className="rounded font-display text-lg text-stamp outline-none focus-visible:ring-3 focus-visible:ring-ring/70"
        >
          Falcon
        </Link>
        <div className="flex items-center gap-2">
          <Button asChild size="sm">
            <Link href="/analyses/new">Nowa analiza</Link>
          </Button>
          <Button variant="outline" size="sm" onClick={handleLogout}>
            Wyloguj
          </Button>
        </div>
      </div>
    </header>
  );
}
