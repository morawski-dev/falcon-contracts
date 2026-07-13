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
  HIGH: "bg-risk-high-mark/10 text-risk-high-ink",
  MEDIUM: "bg-risk-medium-mark/10 text-risk-medium-ink",
  LOW: "bg-risk-low-mark/10 text-risk-low-ink",
};

/** The margin rule: full-chroma `-mark` tier, weighted by severity (4px/2px/1px)
 * so severity reads from thickness alone, never colour alone. Kept separate
 * from the badge/text `-ink` tier so the two tiers are never mixed up. */
export const RISK_LEVEL_RULE_CLASS: Record<RiskLevel, string> = {
  HIGH: "w-1 bg-risk-high-mark",
  MEDIUM: "w-0.5 bg-risk-medium-mark",
  LOW: "w-px bg-risk-low-mark",
};

/** The risk-level word's ink (darkened `-ink` tier, AA-safe as text). */
export const RISK_LEVEL_TEXT_CLASS: Record<RiskLevel, string> = {
  HIGH: "text-risk-high-ink",
  MEDIUM: "text-risk-medium-ink",
  LOW: "text-risk-low-ink",
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
