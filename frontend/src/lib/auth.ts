import { apiFetch } from "@/lib/api";

export type CurrentUser = {
  id: number;
  email: string;
};

export async function getCsrf(): Promise<void> {
  await apiFetch("/api/auth/csrf");
}

export async function register(email: string, password: string): Promise<CurrentUser> {
  await getCsrf();
  const response = await apiFetch("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  return response.json();
}

export async function login(email: string, password: string): Promise<CurrentUser> {
  await getCsrf();
  const response = await apiFetch("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  return response.json();
}

export async function logout(): Promise<void> {
  await getCsrf();
  await apiFetch("/api/auth/logout", { method: "POST" });
}

export async function me(): Promise<CurrentUser> {
  const response = await apiFetch("/api/auth/me");
  return response.json();
}
