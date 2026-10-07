"use client";

import { ApiError, api, idem, unwrap, type Document } from "@/api/client";
import { sha256Hex } from "@/lib/outbox/crypto";

export type DocType = "ID_CARD" | "PASSPORT" | "DRIVING_LICENSE" | "RESIDENCE_PERMIT";

/** Maps the KYC identity document type to the document-service type. */
export function docTypeOf(idType: string): DocType {
  return ({ PASSPORT: "PASSPORT", DRIVING_LICENCE: "DRIVING_LICENSE", RESIDENCE_PERMIT: "RESIDENCE_PERMIT" } as Record<string, DocType>)[idType] ?? "ID_CARD";
}

/**
 * Stores a file captured for one of the agent's onboarding applications through the agent channel: register
 * (agentMeApplicationsDocuments; the owner is the application, set server-side), get a pre-signed URL
 * (…UploadUrl), PUT the bytes, confirm with size and SHA-256 (…CompleteUpload). Idempotency keys derive from
 * `keyPrefix`, so a retry after a lost response re-uses the same document.
 */
export async function uploadDocument(o: {
  applicationId: string; bytes: Uint8Array<ArrayBuffer>; contentType: string; fileName: string;
  documentType: DocType; keyPrefix: string; documentNumber?: string;
}): Promise<Document> {
  const app = { application_id: o.applicationId };
  const doc = unwrap(await api.POST("/v1/agent/onboarding/applications/{application_id}/documents", {
    params: { path: app, header: idem(`${o.keyPrefix}:doc`) },
    body: { document_type: o.documentType, file_name: o.fileName, content_type: o.contentType,
      document_number: o.documentNumber },
  }));
  const path = { ...app, document_id: doc.id };
  const target = unwrap(await api.POST("/v1/agent/onboarding/applications/{application_id}/documents/{document_id}/upload-url", {
    params: { path, header: idem(`${o.keyPrefix}:url`) },
  })) as { url: string; method?: string; headers?: Record<string, string> };
  const put = await fetch(target.url, { method: target.method ?? "PUT", headers: target.headers ?? { "Content-Type": o.contentType }, body: o.bytes });
  if (!put.ok) throw new ApiError({ status: put.status, title: "Upload to storage failed" });
  return unwrap(await api.POST("/v1/agent/onboarding/applications/{application_id}/documents/{document_id}/complete-upload", {
    params: { path, header: idem(`${o.keyPrefix}:done`) },
    body: { size_bytes: o.bytes.length, checksum: await sha256Hex(o.bytes) },
  }));
}
