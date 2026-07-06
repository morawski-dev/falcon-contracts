export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

export type RiskType =
  | "PENALTY"
  | "AUTO_RENEWAL"
  | "NO_TERMINATION"
  | "UNILATERAL_CHANGE"
  | "LIABILITY"
  | "CONFIDENTIALITY"
  | "IP_RIGHTS"
  | "PAYMENT"
  | "OTHER";

export const RISK_LEVEL_BADGE_CLASS: Record<RiskLevel, string> = {
  HIGH: "bg-destructive/10 text-destructive dark:bg-destructive/20",
  MEDIUM: "bg-amber-100 text-amber-800 dark:bg-amber-950/40 dark:text-amber-300",
  LOW: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300",
};

export const RISK_LEVEL_LABEL: Record<RiskLevel, string> = {
  HIGH: "Wysokie",
  MEDIUM: "Średnie",
  LOW: "Niskie",
};

export const RISK_TYPE_LABEL: Record<RiskType, string> = {
  PENALTY: "Kara umowna",
  AUTO_RENEWAL: "Automatyczne przedłużenie",
  NO_TERMINATION: "Brak/utrudnione wypowiedzenie",
  UNILATERAL_CHANGE: "Jednostronna zmiana warunków",
  LIABILITY: "Odpowiedzialność/odszkodowania",
  CONFIDENTIALITY: "Poufność/zakaz konkurencji",
  IP_RIGHTS: "Prawa autorskie/własność intelektualna",
  PAYMENT: "Warunki płatności",
  OTHER: "Inne",
};
