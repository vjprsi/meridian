"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, me, unwrap, uploadMyDocument } from "@/api/client";
import { isNotFound, useCustomerId, useMe } from "@/api/me";
import type { KycDocument } from "@/api/types";
import { FileField, checkFile } from "@/components/file-field";
import { Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Notice, PageHeader, Select, Summary } from "@/components/ui";
import { useT } from "@/lib/i18n";
import type { MessageKey } from "@/messages/en";

type KycType = KycDocument["document_type"];
/** KYC document type → document-service type of the stored file. */
const TYPES: { kyc: KycType; doc: "ID_CARD" | "PASSPORT" | "DRIVING_LICENSE" | "RESIDENCE_PERMIT" | "PROOF_OF_ADDRESS" | "BANK_STATEMENT"; label: MessageKey }[] = [
  { kyc: "NATIONAL_ID", doc: "ID_CARD", label: "kyc.nationalId" },
  { kyc: "PASSPORT", doc: "PASSPORT", label: "kyc.passport" },
  { kyc: "DRIVING_LICENCE", doc: "DRIVING_LICENSE", label: "kyc.driving" },
  { kyc: "RESIDENCE_PERMIT", doc: "RESIDENCE_PERMIT", label: "kyc.residence" },
  { kyc: "UTILITY_BILL", doc: "PROOF_OF_ADDRESS", label: "kyc.utility" },
  { kyc: "BANK_STATEMENT", doc: "BANK_STATEMENT", label: "kyc.bankStatement" },
];

export default function Kyc() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const self = useMe();
  const profile = useQuery({
    queryKey: ["kyc-profile", c], enabled: !!c,
    queryFn: async () => {
      try {
        return unwrap(await me.GET("/v1/me/kyc/profile"));
      } catch (e) {
        if (isNotFound(e)) return null;     // no KYC profile yet
        throw e;
      }
    },
  });
  const docs = useQuery({
    queryKey: ["kyc-docs", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/kyc/documents", { params: { query: { limit: 50 } } })).data,
  });

  const [type, setType] = useState<KycType>("NATIONAL_ID");
  const [file, setFile] = useState<File | null>(null);
  const [fileErr, setFileErr] = useState<string>();
  const upload = useMutation({
    mutationFn: async () => {
      const def = TYPES.find((x) => x.kyc === type)!;
      const documentId = await uploadMyDocument(file!, def.doc);
      return unwrap(await me.POST("/v1/me/kyc/documents", {
        params: { header: idem() }, body: { document_type: type, document_id: documentId },
      }));
    },
    onSuccess: () => { setFile(null); qc.invalidateQueries({ queryKey: ["kyc-docs", c] }); },
  });

  return (
    <>
      <PageHeader title={t("nav.kyc")} subtitle={t("kyc.subtitle")} />
      <div className="grid gap-5 lg:grid-cols-2">
        <Card title={t("kyc.status")}>
          <Async q={profile} isEmpty={(p) => p === null} empty={<Empty title={t("kyc.noProfile")}>{t("kyc.noProfileHint")}</Empty>}>
            {(p) => p && (
              <Summary items={[
                { label: t("kyc.verification"), value: <Badge value={p.status} /> },
                { label: t("profile.kycTier"), value: (p.achieved_tier ?? self.data?.kyc_tier ?? "—").replace("_", " ") },
                { label: t("kyc.identity"), value: <Badge value={p.identity_verified ? "VERIFIED" : "PENDING"} /> },
                { label: t("kyc.address"), value: <Badge value={p.address_verified ? "VERIFIED" : "PENDING"} /> },
                { label: t("kyc.nextReview"), value: <DateTime value={p.next_review_date} dateOnly /> },
              ]} />
            )}
          </Async>
        </Card>

        <Card title={t("kyc.upload")}>
          {profile.data ? (
            <form noValidate className="flex flex-col gap-4" onSubmit={(e) => {
              e.preventDefault();
              const err = checkFile(file, t);
              setFileErr(err);
              if (!err) upload.mutate();
            }}>
              <Field label={t("kyc.docType")}>
                <Select value={type} onChange={(e) => setType(e.target.value as KycType)}
                  options={TYPES.map((x) => ({ value: x.kyc, label: t(x.label) }))} />
              </Field>
              <FileField label={t("kyc.file")} file={file} onChange={setFile} error={fileErr} />
              <ErrorBanner error={upload.error} />
              {upload.isSuccess && <Notice tone="good">{t("kyc.uploaded")}</Notice>}
              <Button type="submit" disabled={upload.isPending}>{upload.isPending ? t("kyc.uploading") : t("kyc.submit")}</Button>
            </form>
          ) : <p className="text-sm text-muted">{t("kyc.noProfileHint")}</p>}
        </Card>

        <Card title={t("kyc.documents")} className="lg:col-span-2">
          <Async q={docs} empty={<Empty title={t("kyc.noDocuments")} />}>
            {(rows) => (
              <ul className="divide-y divide-border">
                {rows.map((d) => (
                  <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm">
                    <span>
                      <span className="block font-medium">{t(TYPES.find((x) => x.kyc === d.document_type)?.label ?? "kyc.other")}</span>
                      <span className="block text-xs text-muted"><DateTime value={d.created_at} />{d.rejection_reason && <> · {d.rejection_reason}</>}</span>
                    </span>
                    <Badge value={d.status} />
                  </li>
                ))}
              </ul>
            )}
          </Async>
        </Card>
      </div>
    </>
  );
}
