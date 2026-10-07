import Link from "next/link";

export default function NotFound() {
  return (
    <main id="main" className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-3 px-4 text-center">
      <h1 className="text-xl font-semibold">Page not found · Ukurasa haukupatikana</h1>
      <Link href="/" className="prose-link">Home · Mwanzo</Link>
    </main>
  );
}
