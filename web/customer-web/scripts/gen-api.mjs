// Generates the typed client definitions used by the customer app (from
// generated/openapi/services/<service>.openapi.yaml) into src/api/gen/<service>.d.ts:
//   customer-channel  the customer channel BFF (/v1/me/*): every signed-in screen
//   onboarding, kyc, document  sign-up before a customer exists (tenant dev headers)
//   customer          dev-mode login only (customer search)
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const specs = join(root, "..", "..", "generated", "openapi", "services");

export const SERVICES = ["customer-channel", "customer", "onboarding", "kyc", "document"];

const bin = join(root, "node_modules", "openapi-typescript", "bin", "cli.js");
for (const s of SERVICES) {
  const input = join(specs, `${s}-service.openapi.yaml`);
  const output = join(root, "src", "api", "gen", `${s}.d.ts`);
  execFileSync(process.execPath, [bin, input, "-o", output], { stdio: "inherit" });
}
