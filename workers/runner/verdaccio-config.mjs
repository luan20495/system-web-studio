// Package mirror configuration (ADR 0010/0013): only allowlisted names are proxied from npm; @company/* is private (published by the
// operator, never proxied); everything else is refused. Shared by scripts/build-plane-allowlist.mjs and the runner's mirror sync.
const VALID = /^(@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
export function verdaccioConfig(names) {
  const list = [...new Set(names)].filter((n) => VALID.test(n) && !n.startsWith("@company/")).sort();
  return `# GENERATED — do not edit by hand (${list.length} allowlisted packages).
storage: /verdaccio/storage/data
auth:
  htpasswd:
    file: /verdaccio/storage/htpasswd
    max_users: -1            # nobody can register; the operator account is created by scripts/publish-company-packages.sh
uplinks:
  npmjs:
    url: https://registry.npmjs.org/
    timeout: 60s
packages:
  '@company/*':
    access: $all
    publish: $authenticated   # company packages: private, never proxied
${list.map((n) => `  '${n}':\n    access: $all\n    publish: $nobody\n    proxy: npmjs`).join("\n")}
  '**':
    access: $nobody           # not on the allowlist
    publish: $nobody
server:
  keepAliveTimeout: 60
log: { type: stdout, format: pretty, level: warn }
`;
}
