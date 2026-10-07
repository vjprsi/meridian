# ML fraud scoring: runtime, offline training and registration

fraud-service scores events with governed machine-learning models next to its rules. The models are trained
**outside the platform**, registered and approved in ai-platform-service (model risk management), and executed
**in-process** by fraud-service. This note describes the runtime contract, how to train a model offline, and how to
register, deploy, monitor and retrain it.

> The sample models in `services/fraud-service/src/main/resources/ml/sample/` are trained on **synthetic data**
> (`SyntheticFraudData`, seed 20261007) for tests and demonstrations. They say so in `metadata.synthetic` and must
> never decide real transactions.

## 1. Runtime in fraud-service

```
payments / cards / wallets / risk events (Kafka)        ai-platform-service
        |  ce_id de-duplicated, tenant-scoped                 | models, versions, artefacts, deployments
        v                                                     v
  feature store (PostgreSQL)  --one read-->  evaluate  <--  ModelRuntime (per tenant, checksum verified,
  counters / profiles / links               (rules +        compiled once; hot reload on deployment events)
                                             ML champion,
                                             observers)  --> fraud_model_score log --> monitoring (PSI, latency,
                                                                                         error rate, precision/recall/AUC)
```

- **Feature store.** `FeatureEventsListener` consumes `payments.payment.initiated` (outbound),
  `cards.card_authorization.approved|declined`, `wallets.wallet_transaction.initiated` and fraud-service's own
  `risk.fraud_score.evaluated` (device velocity, logins, customer-device history). Each CloudEvent id is inserted into
  `fraud_feature_event` in the same statement as the counter / profile / link updates, so a redelivery changes nothing.
  Counters are 5-minute buckets (1 h window) and hourly buckets (24 h, 7 d windows): a window covers the buckets whose
  start is at or after `floor(now) - window + bucket`. Profiles keep exponentially weighted amount mean / variance and
  time-of-day per customer and currency (`a = max(1/(n+1), 0.02)`); links remember the first time two entities were
  seen together. Retention: 5-minute buckets 2 h, hourly buckets 8 d, event ids 8 d, profiles / links 400 days after
  last use, pruned per tenant by the consumer. All tables are tenant-scoped by row-level security. Entities identified
  by personal data (beneficiary account numbers, MSISDNs) are keyed by tenant-keyed pseudonyms (section 7).
- **Features.** `FeatureCatalog` lists every feature with its exact definition (source events, entity key,
  aggregation, window semantics, currency rule, transform, missing-value rule); `POST /v1/risk/fraud/model-scores/
  register-features` writes them to ai-platform's catalogue (`feature_set = fraud-realtime-v1`, idempotent). A model
  may only use catalogued names; others are refused at load. Missing values are `NaN` (trees follow the missing branch,
  logistic regression adds nothing) unless the artefact imputes them. `POST /model-scores/preview-features` shows the
  vector the store computes for an event.
- **Models.** A decision policy whose `model_id` is an ai-platform FRAUD model (rather than a built-in id) is
  governed. `ModelRuntime` loads the tenant's ACTIVE deployments (`meridian.fraud.ml.environment`, default
  PRODUCTION) and each version's artefact (`GET /v1/ai/model-versions/{id}/artifact`), checks
  `sha256(content) == artifact_sha256` and that the version's feature list equals the artefact's, then compiles it
  (flat arrays, no allocation per node). Roles: CHAMPION decides; CANARY decides for a deterministic `traffic_percent`
  slice (SHA-256 of deployment id and subject id); CHALLENGER and SHADOW are scored and logged only, never decide.
- **Decision.** `combination` on the policy: `ADDITIVE` (ML score + rule adjustments; the built-in heuristic is
  replaced), `MAX`, `WEIGHTED` (`model_weight`), `MODEL_ONLY_ABOVE_CONFIDENCE` (`|2p-1| >= model_confidence`, else
  max) or `RULES_ONLY` (ML only logged). Rule floors, terminal rules, block lists and blocked devices apply on top in
  every mode. Thresholds: `channel_thresholds[channel]`, else the artefact's thresholds when `use_model_thresholds`
  and the ML score was used, else the policy's. Reason codes: up to three ML contributions at or above
  `reason_min_contribution` (logistic: coefficient x standardised value; trees: Saabas path attribution, so
  `bias + sum(contributions) = margin`), plus the heuristic's when it contributed.
- **Fail-safe.** No deployment, no champion, an unloadable or checksum-failing artefact, an exception, a non-finite
  output, an inference slower than `model_timeout_ms`, or an unreadable feature store: the rule-based score decides and
  the evaluation is flagged `degraded` with `degraded_reason`. A failed registry load is retried after
  `failure-backoff-ms`, keeping the last good snapshot.
- **Hot reload.** Every instance consumes `reporting.model_deployment.*` / `reporting.ml_model_version.*` with its
  own consumer group and reloads the tenant in the background; snapshots also refresh after `refresh-ttl-ms` and on
  `POST /model-scores/reload`. `GET /model-scores/runtime` shows what is loaded and why something is not.
- **API contract.** `POST /v1/risk/fraud/scores/evaluate` is unchanged; it gains optional fields
  `rules_component_score`, `combination`, `ml_model_version_id`, `ml_deployment_id`, `degraded`, `degraded_reason`
  (`model_id` / `model_version` name the ML model when it decided). `GET /scores/{id}/explanation` adds an `ml`
  section with the deciding model's contributions.
- **Latency** (`InferenceLatencyTest`, this workstation): sample 40-tree ensemble p99 0.004 ms, 300-tree ensemble
  p99 0.005 ms, logistic regression p99 0.001 ms, champion + challenger + shadow p99 0.008 ms per evaluation. The
  feature-store read is one SQL statement; the whole evaluate call measured p50 8 ms / p95 12 ms in `FraudFlowIT`.

## 2. Training data

`GET /v1/risk/fraud/feedback/training-set?from_ts=..&to_ts=..&limit=..` returns, per evaluation with an outcome label
(latest `FRAUD` / `NOT_FRAUD` from `fraud_feedback`), the features recorded at scoring time (`features.engineered` and
`features.store`), subject type, channel, score, decision and model version. Labels come from analysts resolving
alerts (`CONFIRMED_FRAUD` -> FRAUD, `FALSE_POSITIVE` -> NOT_FRAUD), chargebacks and other sources posted to
`/scores/{id}/feedback`. With `meridian.fraud.ml.feature-logging=true` (default) store features are recorded for every
evaluation, so a first model can be trained before any model is deployed.

Points to handle offline:

- **Point-in-time correctness**: use only the recorded features (they were computed before the decision), never
  recompute them from today's store.
- **Label delay and selection bias**: chargebacks arrive weeks later; blocked events rarely get labels. Train on a
  window old enough for labels to mature and consider reject inference.
- **Class imbalance**: weight or sample the negatives; calibrate probabilities afterwards (scores are `1000 * p`).
- **Leakage**: drop features derived from the outcome (e.g. merchant risk computed with the labels of the training
  window itself when back-testing).

## 3. Training a model

Any tool works if the result is exported as a `meridian-fraud-model/1` artefact (see `ModelArtifact` for the full
schema):

```json
{
  "format": "meridian-fraud-model/1",
  "algorithm": "TREE_ENSEMBLE",
  "features": ["customer_txn_count_1h", "amount_zscore", "new_payee"],
  "base_score": -1.99,
  "decision_type": "<",
  "trees": [ { "nodeid": 0, "split": "amount_zscore", "split_condition": 2.5, "yes": 1, "no": 2, "missing": 1,
               "cover": 812.4, "children": [ { "nodeid": 1, "leaf": -0.12, "cover": 700.1 },
                                             { "nodeid": 2, "leaf": 0.41, "cover": 112.3 } ] } ],
  "reason_codes": { "amount_zscore": { "code": "ML_UNUSUAL_AMOUNT", "description": "Amount far above the customer's usual" } },
  "reason_min_contribution": 0.25,
  "thresholds": { "default": { "step_up": 450, "review": 650, "block": 850 },
                  "channels": { "ECOMMERCE": { "step_up": 400, "review": 600, "block": 800 } } },
  "training_distributions": { "score": { "edges": [100, 200, 300, 400, 500, 600, 700, 800, 900],
                                         "counts": [5120, 900, 300, 150, 90, 60, 40, 30, 20, 10] } },
  "metadata": { "training_window": "2026-06-01/2026-08-31", "holdout_auc": 0.91 }
}
```

- **XGBoost**: `booster.get_dump(dump_format="json", with_stats=True)` gives one JSON tree per round in exactly this
  node structure (`with_stats` adds `cover`, used for the Saabas expectations). Features must be named (pass
  `feature_names`) or `f<index>` into `features`. `base_score` is the **margin** (`log(p/(1-p))` of XGBoost's
  `base_score` for `binary:logistic`). Missing values follow `missing`.
- **LightGBM**: `booster.dump_model()["tree_info"]` items (`tree_structure` with `split_feature`, `threshold`,
  `decision_type` `<=`, `default_left`, `left_child` / `right_child`, `leaf_value`, counts). Categorical splits are not
  supported: one-hot encode first. LightGBM's init score belongs in `base_score`.
- **Logistic regression**: `"logistic": {"intercept": b, "coefficients": [...] | {"name": w},
  "standardize": {"mean": [...], "std": [...]}}`, e.g. from scikit-learn's `StandardScaler` + `LogisticRegression`
  (`intercept_[0]`, `coef_[0]`).
- `training_distributions` (score and any feature, 2-100 bins, `edges` = inner cut points) are the PSI reference used
  by monitoring; compute them on the training or validation population.
- The reference trainer `SampleModelTrainer` (tests) shows the whole path on synthetic data: generate, train a
  logistic regression and a 40-round depth-3 boosted ensemble, compute holdout AUC and reference histograms, write
  the artefact. `SampleModelTest` keeps the shipped files byte-identical to it (`REGENERATE_SAMPLE_MODELS=true` to
  rewrite).

Validate locally before registering: parse with `ModelArtifact.parse(content, json, FeatureCatalog.names())`, score a
holdout through it and compare with the training library's predictions (they must agree to floating-point precision;
`SampleModelTest.treeInferenceReproducesTheTrainersOwnMargins` does this).

## 4. Registration and deployment (ai-platform-service)

1. `POST /v1/risk/fraud/model-scores/register-features` once per tenant (and after a feature-store change) so the
   catalogue holds the exact definitions.
2. `POST /v1/ai/models` (`model_type: FRAUD`) once.
3. `POST /v1/ai/model-versions` with `artifact_sha256 = sha256(artefact bytes, UTF-8)`, `features` (same order as the
   artefact), `metrics` (AUC, KS, ...), `bias_test_results`, explainability notes / validation report for HIGH risk.
4. `POST /v1/ai/model-versions/{id}/artifact` `{format: "meridian-fraud-model/1", content}`: refused unless the
   content hashes to `artifact_sha256`; write-once; only while the version is editable. Afterwards the checksum is
   locked.
5. `submit-for-approval`, then `approve` by a different person (model risk committee; central approvals in workflow
   mode).
6. `deploy` to PRODUCTION as `SHADOW` (scored and logged for every evaluation), `promote` to `CANARY` (decides for
   `traffic_percent` of subjects), `promote` again to `CHAMPION`; or deploy as `CHALLENGER` to compare against the
   champion without deciding. `rollback` restores the previous champion; deployments with `auto_rollback` are rolled
   back by the monitoring job when a threshold is breached.
7. Point the fraud decision policy at the model: `PATCH /v1/risk/fraud/decision-policies/{id}` with `model_id` (the
   ai-platform model id), `combination`, `model_timeout_ms`, optional `channel_thresholds`, `use_model_thresholds`.

fraud-service picks every step up through the deployment events (or `POST /model-scores/reload`).

## 5. Monitoring and retraining

- `POST /v1/risk/fraud/model-scores/publish-monitoring` `{window_start, window_end}` (schedule it per tenant, e.g.
  hourly) pushes, per deployment that scored in the window: PSI of the score and of every feature with a reference
  distribution (`POST /v1/ai/deployments/{id}/drift`), `LATENCY_P99` (ms), `ERROR_RATE`, and where labels exist
  `PRECISION` / `RECALL` (positive = would REVIEW or BLOCK) and `AUC` (`POST /v1/ai/model-metrics`). Calls are
  idempotent per deployment, metric and window. The response also counts governed and degraded evaluations.
- ai-platform's monitoring job turns breaches into `reporting.model_metric.breached` events and rolls back breaching
  canaries / challengers with `auto_rollback`.
- Retrain when PSI stays above 0.25, precision / recall drop below the deployment thresholds, or fraud patterns change:
  pull a fresh training set, train, register a new version and go through shadow / canary again.

## 6. Limits and gaps

- Time-of-day features use UTC, not the customer's time zone.
- Velocity windows are bucket-aligned (55-60 min, 23-24 h, 167-168 h), not exact sliding windows.
- Wallet events carry no customer id: wallet velocity is keyed by the payer wallet (`context.wallet_id` in the
  evaluate request) and customer features need `customer_id` in the payload or metadata.
- Pseudonyms are keyed, not anonymous: whoever holds `meridian.fraud.pseudonym-key` and the data can test guesses.
  Rotating the key changes every pseudonym (velocity history restarts; list entries must be re-added or migrated by
  re-adding their values).
- The device registry (`device_fingerprint.fingerprint_hash`) keeps the SDK's fingerprint as sent (it is the lookup
  key analysts search by, and SDKs send a hash); it is never copied into features, feature keys or model inputs.
- ai-platform lists are read in pages of 200 (newest models and deployments).
- payment-service scores every outbound and internal payment before release (subject PAYMENT, key
  `pay-fraud:<payment id>`; see SERVICE_IMPLEMENTATION_GUIDE "Fraud scoring of payments"). It sends the beneficiary
  account only as `beneficiary_account_digest` (see section 7).

## 7. Personal identifiers: tenant-keyed pseudonyms

fraud-service never stores, and never computes features on, an identifier in clear (account numbers, IBANs, card
numbers or fragments, phone numbers, e-mail addresses, device ids, national ids):

- **Key.** `meridian.fraud.pseudonym-key` (env `MERIDIAN_FRAUD_PSEUDONYM_KEY`, Helm Secret
  `meridian-fraud-service-secrets` key `pseudonym-key`) is a required secret (`meridian.required-secrets`,
  `RequiredSecrets`): outside `security-mode=dev` the service refuses to start without a real value (>= 32 characters,
  not a development default). The dev default `dev-only-fraud-pseudonym-key-...` works only in dev mode. Each tenant
  gets its own key `HMAC-SHA256(secret, "meridian-fraud-pseudonym/v1|" + tenant_id)` (`Pseudonymizer`), so two tenants
  never share a pseudonym.
- **Canonical digest.** Every pseudonym is derived from the identifier's digest `sha256(TYPE ":" normalised)` (the
  normalisation of fraud lists: e-mails lower case, phone digits and `+`, account / card numbers without spaces and
  dashes, upper case). Callers may send the digest instead of the value: a context field `<name>_digest` (64 hex) is
  treated like `<name>` (payment-service sends `beneficiary_account_digest` =
  `sha256("BENEFICIARY_ACCOUNT:" + account without spaces/dashes, upper case)`, never the account number).
- **Evaluation context.** Before rules, features or storage see it, the request `context` is rewritten
  (`ContextPseudonymisation`): identifier fields by name (`beneficiary_account`, `iban`, `account_number`,
  `card_number`, `pan`, `card_last4`, `email`, `phone`, `msisdn`, `payee_msisdn`, `device_id`, `device_fingerprint`,
  `national_id`, ...) and any other string shaped like an e-mail, IBAN, international phone number or 8+ digits become
  `psn:` + 128-bit HMAC; e-mails and 12+ digit runs inside free text are replaced inline. Ids (UUIDs), BINs, codes,
  dates and numbers are kept. Same value, same tenant -> same pseudonym, so rules comparing context fields keep
  working; rules must not compare context identifiers with literals. `fraud_score.features` (and the
  `risk.fraud_score.*` events built from it) carry `pseudonymisation: "h1"`.
- **Lists.** `fraud_list_entry.value_hash` = `h1:` + `HMAC(tenant key, "list|" + digest)`; evaluation matches the
  request's digests the same way (clear values or `*_digest` fields).
- **Feature store.** Beneficiary keys are `acct:h1:<HMAC(tenant key, "acct|" + account digest + "|" + BIC)[0..40]>`
  and `msisdn:h1:<HMAC(tenant key, "msisdn|" + digits)[0..40]>`, computed the same way from the payment / wallet events
  and from evaluate requests; payee, wallet, customer, card, account and merchant keys are internal ids. Device
  velocity is keyed by the device registry id, never the fingerprint.
- **Model inputs.** `fraud_model_score.feature_vector` and the training set hold only the numeric features (engineered
  + store), never identifiers.
- **One-off migration.** `POST /v1/risk/fraud/scores/pseudonymise-stored` (per tenant, scope `risk:*`, body
  `{"dry_run": true}` to count first, `batch_size` 1-5000) re-pseudonymises what was stored before: the `context` of
  older `fraud_score.features` (the append-only guard admits this one update of `features` only, inside a transaction
  that sets `fraud.pseudonymise`), list entries holding a plain SHA-256 (`value_hash` -> `h1:HMAC(digest)`, lists keep
  matching) and drops feature-store rows keyed by a plain SHA-256 (`acct:` / `msisdn:` + 40 hex; that beneficiary
  history restarts). It is idempotent and resumable; repeat until `complete: true`. Events already published with
  clear values (outbox, Kafka retention, audit copies) are outside its reach and age out with their retention.
