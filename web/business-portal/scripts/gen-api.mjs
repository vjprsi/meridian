// Generates the typed client definitions used by the business portal from the contract in
// generated/openapi/services/business-channel-service.openapi.yaml into src/api/gen/:
//   business-channel.d.ts   the business channel BFF (/v1/business/*): every signed-in screen
import { execFileSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const specs = join(root, "..", "..", "generated", "openapi", "services");
const out = (name) => join(root, "src", "api", "gen", `${name}.d.ts`);

/** Whole service contracts. */
export const SERVICES = ["business-channel"];

const bin = join(root, "node_modules", "openapi-typescript", "bin", "cli.js");
for (const s of SERVICES) {
  execFileSync(process.execPath, [bin, join(specs, `${s}-service.openapi.yaml`), "-o", out(s)], { stdio: "inherit" });
}
