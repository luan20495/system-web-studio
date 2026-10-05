-- Security review M1: a pending claim must not block the real owner. Several projects may claim a host; the first to prove ownership wins.
ALTER TABLE site_domains DROP CONSTRAINT site_domains_hostname_key;
CREATE UNIQUE INDEX site_domains_verified_unique ON site_domains (hostname) WHERE status = 'VERIFIED';
CREATE UNIQUE INDEX site_domains_project_host_unique ON site_domains (project_id, hostname);
