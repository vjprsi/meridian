"use client";

import { ApiError, documents, idem, unwrap, type Document } from "@/api/client";
import { sha256Hex } from "@/lib/outbox/crypto";

export type DocType = "ID_CARD" | "PASSPORT" | "DRIVING_LICENSE" | "RESIDENCE_PERMIT";

/** Maps the KYC identity document type to the document-service type. */
export function docTypeOf(idType: string): DocType {
  return ({ PASSPORT: "PASSPORT", DRIVING_LICENCE: "DRIVING_LICENSE", RESIDENCE_PERMIT: "RESIDENCE_PERMIT" } as Record<string, DocType>)[idType] ?? "ID_CARD";
}

/**
 * Stores a captured file with document-service: register (documentsCreate), get a pre-signed URL
 * (documentsUploadUrl), PUT the bytes, confirm with size and SHA-256 (documentsCompleteUpload). Idempotency keys
 * derive from `keyPrefix`, so a retry after a lost response re-uses the same document.
 */
export async function uploadDocument(o: {
  ownerType: string; ownerId: string; bytes: Uint8Array<ArrayBuffer>; contentType: string; fileName: string;
  documentType: DocType; keyPrefix: string; documentNumber?: string;
}): Promise<Document> {
  const doc = unwrap(await documents.POST("/v1/documents", {
    params: { header: idem(`${o.keyPrefix}:doc`) },
    body: {
      owner_type: o.ownerType, owner_id: o.ownerId, document_type: o.documentType, file_name: o.fileName,
      content_type: o.contentType, classification: "CONFIDENTIAL", uploaded_via: "AGENT_APP",
      document_number: o.documentNumber,
    },
  }));
  const target = unwrap(await documents.POST("/v1/documents/{id}/upload-url", {
    params: { path: { id: doc.id }, header: idem(`${o.keyPrefix}:url`) },
  })) as { url: string; method?: string; headers?: Record<string, string> };
  const put = await fetch(target.url, { method: target.method ?? "PUT", headers: target.headers ?? { "Content-Type": o.contentType }, body: o.bytes });
  if (!put.ok) throw new ApiError({ status: put.status, title: "Upload to storage failed" });
  return unwrap(await documents.POST("/v1/documents/{id}/complete-upload", {
    params: { path: { id: doc.id }, header: idem(`${o.keyPrefix}:done`) },
    body: { size_bytes: o.bytes.length, checksum: await sha256Hex(o.bytes) },
  }));
}
