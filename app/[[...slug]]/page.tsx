import AppEntry from "@/components/app/AppEntry";

// One client-side router serves /login, /auth/*, /admin/* and /studio/*. The static export (mock mode / GitHub Pages) only
// needs the root; with the server (http mode) every path renders this page and the router picks the screen.
export function generateStaticParams() { return [{ slug: [] as string[] }]; }

export default function Page() { return <AppEntry/>; }
