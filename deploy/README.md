# deploy/ - environments, promotion and admission control (PLT-GITOPS, GAP-05)

The desired state of every deployed Meridian environment. Argo CD syncs it (`argocd/`); nothing is deployed with
`helm upgrade` by hand any more. Every change to an environment is a pull request, and a release moves between
environments **unchanged** - same git revision, same image digests.

```
main CI green ──► promote.yml pins the signed digests ──► PR deploy/dev ──► auto-merge ──► Argo CD auto-sync (dev)
                                     │
workflow_dispatch staging ───────────┴► same pins ──► PR deploy/staging ──► 1 code-owner approval ──► auto-sync
workflow_dispatch prod (env gate) ────► same pins ──► PR deploy/prod ──► 2 approvals ──► manual sync in window
```

## Layout

| Path | What |
|---|---|
| `pipeline.yaml` | Repository / registry / signer identity and the promotion order dev -> staging -> prod |
| `base/<chart>.yaml` | Values every environment shares (digest pins required, JSON logs, migrations as hooks) |
| `<env>/images.yaml` | **The release** pinned in the environment: `release.revision` + `global.image.digests` (69 components). Written only by `tools/release.py` |
| `<env>/<chart>.yaml` | Environment values (IdP, replicas, MSK IAM, monitoring, ingress, CDE namespace) |
| `<env>/cells/<cell>/cell.yaml` | One cell: Argo CD cluster name, namespaces, role, and `revision` (git commit the charts render at) |
| `<env>/cells/<cell>/<chart>.yaml` | Cell values (`global.cell.*`, endpoints from `tofu output helm_values`) |
| `argocd/projects.yaml` | AppProjects `meridian-dev`, `-staging`, `-prod`, `-platform`: allowed repo, clusters, kinds; RBAC roles; sync windows |
| `argocd/applicationsets/` | One ApplicationSet per environment (cell x chart -> Application) and one for the policies |
| `argocd/bootstrap.yaml` | App of apps; applied once per Argo CD instance |
| `policies/` | Kyverno admission policies + offline tests (`kyverno test deploy/policies/tests`) |
| `change-records/<env>/` | One change record per release (generated) and `CHANGELOG.md` (one line per release) |

Cells: dev `aws-euw1-d01`; staging `aws-euw1-s01` (`infra/tofu/envs/staging-eu1a`); prod region pair prod-eu1:
`aws-euw1-c01` (primary) and `aws-euw2-c01` (warm standby) (`infra/tofu/envs/prod-eu1`). Endpoints in the cell values
files are placeholders until the tofu outputs exist.

Each Application has two sources: the chart at the cell's pinned `revision` (a chart change on main reaches an
environment only through a promotion) and the values from `main` with the layering
`base -> <env>/images.yaml -> <env> -> <env>/cells/<cell>`. Both charts set `requireDigests`: a component without a
digest fails to render, so an environment can never fall back to a mutable tag.

## Environments and sync policy

| Env | Gets a release | PR approvals | GitHub environment | Argo CD sync |
|---|---|---|---|---|
| dev | automatically after every green `ci` run on main | none beyond branch protection (auto-merge) | `dev` (no reviewers) | automated, prune, self-heal |
| staging | `promote` workflow, `environment=staging` (pins of dev) | 1 code owner + required checks | `staging` (optional reviewer) | automated after merge (the checks run before merge); deny window 02:00-03:00 UTC (nightly e2e) |
| prod | `promote` workflow, `environment=prod` (pins of staging) | **2**, not the author nor the requester; code owners `release-managers` | `prod`: required reviewers | **manual**, per cell, primary first; allow Mon-Thu 08:00-16:00 London; deny EOD 20:00-02:00 and the first two days of each month |

## Gates (before the PR exists, and again on the PR)

`promote.yml` (before opening the PR) and `deploy-check.yml` (required check on the PR) run, per digest:

1. `cosign verify` - keyless signature whose Fulcio certificate names `.github/workflows/ci.yml@refs/heads/main` of
   this repository (issuer `token.actions.githubusercontent.com`) **and** the pinned commit
   (`--certificate-github-workflow-sha`), logged in Rekor.
2. `cosign verify-attestation --type cyclonedx` - the SBOM attestation from the same identity.
3. Trivy - fails on fixable CRITICAL vulnerabilities (re-scanned at promotion time, not only at build time).
4. `tools/release.py render --kubeconform --policies` - every cell x chart renders, every image is
   `ghcr.io/meridian-org/<component>@sha256:...`, kubeconform -strict (CRD schemas), Kyverno policies pass.
5. `tools/release.py provenance` (PR only) - staging / prod pins existed verbatim in the previous environment
   (promotion) or in the environment's own history (rollback). A hand-edited digest fails.
6. CI succeeded on main for the revision; the change record is generated into the PR.

At admission, Kyverno repeats the supply-chain check (`policies/verify-images.yaml`) and enforces
`policies/image-references.yaml` (no `:latest`, only this registry, only digests) and
`policies/workload-security.yaml` (PSS restricted, read-only root filesystem, requests + memory limit, no
service-account token) - exactly what the charts render, so a drifted or hand-applied workload is refused.

## Change records

`python tools/release.py change-record --env <env>` compares the pins in the working tree with `HEAD` and writes
`change-records/<env>/<date>-<sha12>.md`: release and previous revision, cells, commits between the two revisions
with ticket references parsed from the messages (`GAP-05`, `PLT-GITOPS`, `CHG-123`, `#42`; commits without one are
counted), affected components, schema migrations with contract steps flagged, risk level (High: chart templates,
CDE, auth, ledger / payments / identity / audit, deploy config or policy, contract migration, rollback, first
release; Medium: code, shared libraries, expand migrations, any prod change; Low: docs / tests only), rollback plan
with the previous digests, approval requirements and the verification evidence. The record is the PR body; the
merged PR (approvals, checks) plus the Argo CD sync history (and its notifications to `meridian-deploys`) is the
deploy record per environment.

## Rollback

| Situation | Action |
|---|---|
| Bad release, no contract migration | `promote` workflow with `mode=rollback`, `environment=<env>`, `rollback_to=<commit before the bad promotion merged>` (or locally `python tools/release.py rollback --env <env> --to <commit>` + PR, or `git revert -m 1 <merge>`). Same gates, change record type rollback, prod still needs 2 approvals; then sync (prod: manual, primary first) |
| Release contained a contract step (record says so) | **Do not roll the image back.** Roll forward with a fix through the normal pipeline (expedite: dev -> staging -> prod the same day) |
| Outside a prod sync window, customer impact | Break-glass: an Argo CD admin runs `argocd proj windows enable-manual-sync meridian-prod <window id>`, a release manager syncs the reverted pins, the window is disabled again and the change record notes the exception (RUNBOOKS section 1) |

Argo CD's own "rollback to a previous sync" is not available for multi-source Applications and would drift from git
anyway: git is the only rollback path.

### Database migrations (forward-only Flyway)

- Migrations run in the `<service>-migrate` Jobs (GAP-22), Helm `pre-install` / `pre-upgrade` hooks = Argo CD
  **PreSync**: they finish before any Deployment changes; a failed Job fails the sync and the old pods keep serving.
- **Expand / contract across releases**: release N only *adds* (nullable / defaulted columns, new tables, new
  indexes created concurrently) and writes both shapes; release N+1, once N is in prod everywhere (both cells),
  stops reading the old shape; only release N+2 may drop / rename (the *contract* step). A contract step is never in
  the same release as the code change that stops using the column.
- Rolling back the image is safe after an expand migration: the previous release ignores the new objects; the
  migration stays applied (Flyway never runs down-migrations, and nobody runs them by hand).
- After a contract migration the previous image may read what was dropped: roll forward only. The change record
  detects `DROP` / `RENAME` / type changes / `SET NOT NULL` / `TRUNCATE` and says so in the rollback plan.
- Standby cells render no migration Jobs (the schema arrives through Aurora Global Database replication).

## Repository settings (cannot be set from code - configure once)

| Setting | Value |
|---|---|
| GitHub App `meridian-promotion` | Permissions: contents write, pull requests write. Variable `PROMOTION_APP_ID`, secret `PROMOTION_APP_PRIVATE_KEY`. PRs opened with `GITHUB_TOKEN` would not trigger the required checks |
| Environment `dev` | Deployment branches: `main` |
| Environment `staging` | Deployment branches: `main`; optional reviewer: `@meridian-org/sre` |
| Environment `prod` | Deployment branches: `main`; **required reviewers**: `@meridian-org/release-managers` (at least 2 people in the team); **prevent self-review** on; wait timer optional |
| Branch ruleset on `main` | Require PR; 1 approval; require review from Code Owners; dismiss stale approvals; require status checks `Spec, contracts and DDL`, `Build and test (Java 25)`, `Helm chart and container image`, `SAST ...`, `Secret scan ...`, `SBOM ...`, `deploy-check / release gates`, `deploy-check / prod approvals`; block force pushes; allow auto-merge; bypass: nobody (admins included) |
| CODEOWNERS teams | Replace the `@meridian-org/...` placeholders; `/deploy/prod/` -> `@meridian-org/release-managers` |

## Cluster prerequisites (outside this repository)

- Argo CD >= 2.13 (multi-source Applications, ApplicationSet `goTemplate`) with SSO groups = the GitHub teams above,
  `policy.default` empty or `role:readonly`, argocd-notifications with a `meridian-deploys` destination.
- Each cell cluster registered under its cell id: `argocd cluster add <context> --name aws-euw1-c01`.
- Kyverno 1.13 - 1.19 in every cell (the policies use `kyverno.io/v1 ClusterPolicy`, deprecated in 1.19 in favour of
  `policies.kyverno.io` CEL policies - migrate before it is removed), with egress to ghcr.io and rekor.sigstore.dev.
- Namespaces `meridian`, `meridian-cde` (prod), `meridian-edge` and the Secrets listed in the chart notes.
- Then `kubectl apply -n argocd -f deploy/argocd/bootstrap.yaml`.

Until the first real promotion every environment pins the all-zero bootstrap release (`revision` 000..., digests
`sha256:000...`): it renders (CI validates it) but cannot be pulled or admitted.

## Local commands

```bash
python tools/release.py check                                   # identities, cells, projects consistent
python tools/release.py render --env all --kubeconform --policies   # needs helm, kubeconform, kyverno on PATH
kyverno test deploy/policies/tests
python tools/release.py change-record --env dev --base-revision <sha> --out /tmp/record.md --no-link   # dry run
python tools/release.py --help                                  # pin / promote / rollback / verify / provenance
```
