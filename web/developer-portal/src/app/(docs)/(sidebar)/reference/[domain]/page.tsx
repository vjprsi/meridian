"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { MethodBadge } from "@/components/code";
import { OperationView } from "@/components/reference";
import { ErrorBanner, Input, Loading, cx } from "@/components/ui";
import { operationsByTag, useCatalog, useDomainSpec } from "@/lib/spec";

function DomainReference() {
  const { domain } = useParams<{ domain: string }>();
  const params = useSearchParams();
  const router = useRouter();
  const catalog = useCatalog();
  const spec = useDomainSpec(domain);
  const [filter, setFilter] = useState("");
  const groups = useMemo(() => (spec.data ? operationsByTag(spec.data) : []), [spec.data]);
  const info = catalog.data?.domains.find((d) => d.id === domain);
  const tag = params.get("tag") ?? groups[0]?.tag;
  const group = groups.find((g) => g.tag === tag) ?? groups[0];

  const f = filter.trim().toLowerCase();
  const visibleGroups = f
    ? groups.filter((g) => g.tag.toLowerCase().includes(f) || g.ops.some((o) => o.path.toLowerCase().includes(f)
      || o.operationId.toLowerCase().includes(f) || (o.summary ?? "").toLowerCase().includes(f)))
    : groups;

  // Scroll to #operationId once the operations of the tag are rendered.
  useEffect(() => {
    if (!group) return;
    const id = decodeURIComponent(window.location.hash.slice(1));
    if (id) requestAnimationFrame(() => document.getElementById(id)?.scrollIntoView({ block: "start" }));
  }, [group]);

  return (
    <>
      <div className="mb-6">
        <p className="text-xs text-muted"><Link href="/reference" className="hover:text-text">API reference</Link> / {domain}</p>
        <h1 className="mt-1 text-xl font-semibold tracking-tight">{info?.title ?? spec.data?.info.title ?? domain}</h1>
        {spec.data?.info.description && <p className="mt-1 max-w-3xl text-sm text-muted">{spec.data.info.description}</p>}
        {spec.data && (
          <p className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
            <span>version <span className="font-mono">{spec.data.info.version}</span></span>
            {spec.data.servers?.map((s) => <span key={s.url}>{s.description}: <span className="font-mono">{s.url}</span></span>)}
            {info && <span>scopes <span className="font-mono">{info.scopes.join(", ")}</span></span>}
            <a className="text-accent hover:underline" href={`/specs/openapi/${domain}.openapi.yaml`}>Download YAML</a>
          </p>
        )}
      </div>
      <ErrorBanner error={spec.error} />
      {spec.isLoading ? <Loading /> : (
        <div className="flex gap-6">
          <nav className="sticky top-[77px] hidden max-h-[calc(100vh-100px)] w-60 shrink-0 overflow-y-auto md:block" aria-label="Resources">
            <Input placeholder="Filter resources…" value={filter} onChange={(e) => setFilter(e.target.value)} className="mb-2 w-full" />
            {visibleGroups.map((g) => (
              <Link key={g.tag} href={`/reference/${domain}?tag=${encodeURIComponent(g.tag)}`} scroll={false}
                className={cx("flex items-center justify-between rounded-md px-2 py-1 text-sm",
                  g.tag === group?.tag ? "bg-accent-soft font-medium text-accent" : "hover:bg-surface-2")}>
                <span className="truncate">{g.tag}</span>
                <span className="num ml-2 text-[11px] text-muted">{g.ops.length}</span>
              </Link>
            ))}
          </nav>
          <div className="min-w-0 flex-1">
            {/* Small screens: resource picker instead of the side list. */}
            <select className="mb-4 w-full rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm md:hidden"
              value={group?.tag} onChange={(e) => router.push(`/reference/${domain}?tag=${encodeURIComponent(e.target.value)}`)}>
              {groups.map((g) => <option key={g.tag} value={g.tag}>{g.tag}</option>)}
            </select>
            {group && spec.data && (
              <>
                <h2 className="text-lg font-semibold">{group.tag}</h2>
                {group.description && <p className="mt-1 text-sm text-muted">{group.description}</p>}
                <ul className="my-4 rounded-[var(--radius)] border border-border bg-surface text-sm">
                  {group.ops.map((o) => (
                    <li key={o.operationId} className="border-b border-border last:border-0">
                      <a href={`#${o.operationId}`} className="flex items-center gap-3 px-3 py-1.5 hover:bg-surface-2">
                        <MethodBadge method={o.method} />
                        <span className="font-mono text-xs">{o.path}</span>
                        <span className="truncate text-xs text-muted">{o.summary}</span>
                      </a>
                    </li>
                  ))}
                </ul>
                <div className="flex flex-col gap-6">
                  {group.ops.map((o) => <OperationView key={o.operationId} doc={spec.data!} op={o} domain={domain} />)}
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense fallback={<Loading />}><DomainReference /></Suspense>;
}
