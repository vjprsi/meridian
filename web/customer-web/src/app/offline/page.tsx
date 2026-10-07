import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "Offline" };

/** Served by the service worker when a page cannot be reached. Static: no customer data. */
export default function Offline() {
  return (
    <main id="main" className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-3 px-4 text-center">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/icon.svg" alt="" width={48} height={48} className="mx-auto" />
      <h1 className="text-xl font-semibold">You are offline · Huna mtandao</h1>
      <p className="text-sm text-muted">
        Meridian needs a connection to show your accounts. Your data is never stored on this device.
      </p>
      <p className="text-sm text-muted" lang="sw">
        Meridian inahitaji mtandao kuonyesha akaunti zako. Data yako haihifadhiwi kwenye kifaa hiki.
      </p>
      <Link href="/" className="prose-link">Try again · Jaribu tena</Link>
    </main>
  );
}
