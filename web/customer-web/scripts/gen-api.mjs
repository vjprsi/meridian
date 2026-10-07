// Generates one typed client definition per owning service used by the customer app
// (from generated/openapi/services/<service>.openapi.yaml) into src/api/gen/<service>.d.ts.
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const specs = join(root, "..", "..", "generated", "openapi", "services");

export const SERVICES = [
  "customer", "onboarding", "kyc", "document", "deposit", "statement", "payment", "card", "loan", "wallet",
  "insights", "rewards", "inbox", "notification",
];

const bin = join(root, "node_modules", "openapi-typescript", "bin", "cli.js");
for (const s of SERVICES) {
  const input = join(specs, `${s}-service.openapi.yaml`);
  const output = join(root, "src", "api", "gen", `${s}.d.ts`);
  execFileSync(process.execPath, [bin, input, "-o", output], { stdio: "inherit" });
}
