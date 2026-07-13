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

/** The margin rule's ink (full-chroma `-mark` tier) — kept separate from the
 * badge/text `-ink` tier above so the two are never mixed up by accident. */
export const RISK_LEVEL_MARK_CLASS: Record<RiskLevel, string> = {
  HIGH: "bg-risk-high-mark",
  MEDIUM: "bg-risk-medium-mark",
  LOW: "bg-risk-low-mark",
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
