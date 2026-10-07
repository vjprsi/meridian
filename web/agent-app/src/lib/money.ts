/**
 * Decimal-string money helpers. Amounts are never added as floats: they are scaled to integer minor units (4
 * decimals, enough for every currency the wallets use) as BigInt.
 */
const SCALE = 4;
const FACTOR = BigInt(10 ** SCALE);

export function isAmount(s: string): boolean {
  return /^\d{1,15}(\.\d{1,4})?$/.test(s.trim());
}

export function toMinor(s: string | number | null | undefined): bigint {
  if (s === null || s === undefined || s === "") return BigInt(0);
  const str = String(s).trim();
  const neg = str.startsWith("-");
  const [i, f = ""] = (neg ? str.slice(1) : str).split(".");
  const v = BigInt(i || "0") * FACTOR + BigInt((f + "0000").slice(0, SCALE));
  return neg ? -v : v;
}

export function fromMinor(v: bigint): string {
  const neg = v < BigInt(0);
  const a = neg ? -v : v;
  const i = a / FACTOR;
  const f = (a % FACTOR).toString().padStart(SCALE, "0").replace(/0+$/, "");
  return (neg ? "-" : "") + i.toString() + (f ? "." + f : "");
}

export function sum(values: (string | null | undefined)[]): string {
  return fromMinor(values.reduce((acc, v) => acc + toMinor(v), BigInt(0)));
}

export function cmp(a: string | number, b: string | number): number {
  const x = toMinor(a);
  const y = toMinor(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

/** Display formatting only (never feeds back into arithmetic). */
export function formatMoney(amount: string | number | null | undefined, currency?: string | null, locale?: string): string {
  if (amount === null || amount === undefined || amount === "") return "—";
  const n = Number(amount);
  try {
    return currency
      ? new Intl.NumberFormat(locale, { style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(n)
      : n.toLocaleString(locale);
  } catch {
    return `${currency ?? ""} ${amount}`.trim();
  }
}
