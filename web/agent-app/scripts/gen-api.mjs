// Regenerates the typed API clients from the owning services' contracts (generated/openapi/services).
// Only the services the agent app calls are generated; types only, so nothing here reaches the bundle.
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const specs = join(root, "..", "..", "generated", "openapi", "services");
const services = ["wallet", "agent-network", "commission", "biller", "onboarding", "document"];
const bin = join(root, "node_modules", "openapi-typescript", "bin", "cli.js");

for (const s of services) {
  const out = join(root, "src", "api", `${s}.d.ts`);
  execFileSync(process.execPath, [bin, join(specs, `${s}-service.openapi.yaml`), "-o", out], { stdio: "inherit" });
}
