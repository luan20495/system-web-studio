#!/bin/bash
cd /Users/hoangluan/code/xweb-c5
export CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
node tests/browser/build-harness.mjs > /tmp/g-hb.log 2>&1; HARNESS_NODE_ENV=production node tests/browser/build-harness.mjs > /tmp/g-hbp.log 2>&1; echo hbuild:$?
for s in admin aiproviders org org-hardening provisioning builder datasources release publicdata shared-ui ui-widgets ui-route ui-tokens studio-p1 studio-wave2 studio-wave3 data-binding hooks ui-route; do
  node tests/browser/harness-server.mjs run -- node tests/browser/$s.spec.mjs > /tmp/g-spec-$s.log 2>&1; echo "$s exit=$? $(tail -1 /tmp/g-spec-$s.log | cut -c1-45)"
done
node tests/browser/harness-server.mjs run --dir .test-build/browser-prod -- node tests/browser/sanity.spec.mjs > /tmp/g-spec-sanity.log 2>&1; echo "sanity exit=$? $(tail -1 /tmp/g-spec-sanity.log)"
