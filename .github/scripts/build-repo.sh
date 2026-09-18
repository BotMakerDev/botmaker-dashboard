#!/usr/bin/env bash
# Builds the static dnf + apt repository published to GitHub Pages, out of the .rpm/.deb this release just
# produced. Users then get `sudo dnf install botmaker-dashboard`, and every later version arrives with the
# rest of their system updates.
#
#   build-repo.sh <artifacts-dir> <site-dir> <tag>
#
# This is botmaker-remote-server's script with the names changed, which was botmaker-cli's before it. The
# reasoning behind each decision — packages hosted here, latest release only, signed at the repository AND
# the package level, an honest unsigned branch, one repository per module — is written once, in
# botmaker-remote-server's copy, and not repeated here. What differs:
#
#   - ONE ARCHITECTURE. jpackage builds for the runner's: the deb says `Architecture: amd64` and the rpm is
#     x86_64. Listing it under arm64 as well would offer a package apt can never install there.
#   - THE RPM IS SIGNED IN THE `package` JOB with `rpm --addsign`, not by the packager: jpackage signs
#     nothing, where nfpm signs through NFPM_RPM_KEY_FILE.
#   - NOTHING RUNS AFTER INSTALL. This is a desktop window with a menu entry, not a service, so the
#     snippets end at the install.
set -euo pipefail

ARTIFACTS="${1:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
SITE="${2:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
TAG="${3:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"

PACKAGE=botmaker-dashboard
REPO_SLUG="${GITHUB_REPOSITORY:-LiQiyeDev/botmaker-dashboard}"
# Pages serves <owner>.github.io/<repo>, lowercased.
PAGES_URL="${PAGES_URL:-https://$(echo "${REPO_SLUG%%/*}" | tr '[:upper:]' '[:lower:]').github.io/${REPO_SLUG##*/}}"

shopt -s nullglob
rpms=("${ARTIFACTS}"/*.rpm)
debs=("${ARTIFACTS}"/*.deb)
[ ${#rpms[@]} -gt 0 ] || { echo "::error::no .rpm found in ${ARTIFACTS}"; exit 1; }
[ ${#debs[@]} -gt 0 ] || { echo "::error::no .deb found in ${ARTIFACTS}"; exit 1; }
RPM="${rpms[0]}"
DEB="${debs[0]}"

SIGNING=0
if [ "${BOTMAKER_SIGN:-0}" = "1" ] && [ -n "${GPG_KEY_ID:-}" ]; then
  SIGNING=1
else
  echo "::notice::publishing an unsigned repository — clients are told so (gpgcheck=0, [trusted=yes])."
fi

# gpg in batch/loopback mode, matching how ci.yml imports the key.
gpg_run() {
  gpg --batch --yes --pinentry-mode loopback --passphrase "${GPG_PASSPHRASE:-}" -u "${GPG_KEY_ID}" "$@"
}

mkdir -p "${SITE}/rpm" "${SITE}/deb/pool/main/b/${PACKAGE}"

# --- dnf --------------------------------------------------------------------------------------------
cp "${RPM}" "${SITE}/rpm/"
createrepo_c --general-compress-type gz "${SITE}/rpm"

if [ "${SIGNING}" = "1" ]; then
  gpg_run --detach-sign --armor -o "${SITE}/rpm/repodata/repomd.xml.asc" "${SITE}/rpm/repodata/repomd.xml"
fi

# --- apt --------------------------------------------------------------------------------------------
ARCHES=(amd64)
for arch in "${ARCHES[@]}"; do
  mkdir -p "${SITE}/deb/dists/stable/main/binary-${arch}"
done
cp "${DEB}" "${SITE}/deb/pool/main/b/${PACKAGE}/"
(
  cd "${SITE}/deb"
  apt-ftparchive packages pool > "${TMPDIR:-/tmp}/Packages.$$"
  for arch in "${ARCHES[@]}"; do
    cp "${TMPDIR:-/tmp}/Packages.$$" "dists/stable/main/binary-${arch}/Packages"
    gzip -9cf "dists/stable/main/binary-${arch}/Packages" \
      > "dists/stable/main/binary-${arch}/Packages.gz"
  done
  rm -f "${TMPDIR:-/tmp}/Packages.$$"

  # Written outside dists/stable and moved in: apt-ftparchive hashes that tree, including its own output.
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=BotMaker \
    -o APT::FTPArchive::Release::Label="BotMaker dashboard" \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures="${ARCHES[*]}" \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description="BotMaker dashboard release channel" \
    release dists/stable > "${TMPDIR:-/tmp}/Release.$$"
  mv "${TMPDIR:-/tmp}/Release.$$" dists/stable/Release
)
if [ "${SIGNING}" = "1" ]; then
  gpg_run --clearsign -o "${SITE}/deb/dists/stable/InRelease" "${SITE}/deb/dists/stable/Release"
  gpg_run --detach-sign --armor -o "${SITE}/deb/dists/stable/Release.gpg" "${SITE}/deb/dists/stable/Release"
fi

# --- the public key and the two snippets --------------------------------------------------------------
if [ "${SIGNING}" = "1" ]; then
  gpg --export --armor "${GPG_KEY_ID}" > "${SITE}/botmaker.asc"
  rpm_gpg=$'gpgcheck=1\nrepo_gpgcheck=1\ngpgkey='"${PAGES_URL}/botmaker.asc"
  apt_opts="[signed-by=/etc/apt/keyrings/botmaker.asc] "
  APT_KEY_STEP="sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/botmaker.asc ${PAGES_URL}/botmaker.asc
"
  TRUST_NOTE="<p>Signed: <code>dnf</code> verifies the package's own header and this index, and
<code>apt</code> verifies <code>InRelease</code>. The key is the one
<a href=\"https://liqiyedev.github.io/botmaker-cli/\">botmaker-cli's repository</a> publishes.</p>"
else
  rpm_gpg=$'gpgcheck=0\nrepo_gpgcheck=0'
  apt_opts="[trusted=yes] "
  APT_KEY_STEP=""
  TRUST_NOTE="<p><strong>This repository is unsigned.</strong> Nothing here proves a package came from this
project — HTTPS proves who served the file, not who built it — so the snippets above turn the checks off
rather than implying a verification nobody performed. If that is not a trade you want, take the
<code>.rpm</code> from the <a href=\"https://github.com/${REPO_SLUG}/releases\">Releases</a> page.</p>"
fi

cat > "${SITE}/${PACKAGE}.repo" <<EOF
[${PACKAGE}]
name=BotMaker dashboard
baseurl=${PAGES_URL}/rpm
enabled=1
${rpm_gpg}
EOF

DNF_SNIPPET="sudo curl -fsSL -o /etc/yum.repos.d/${PACKAGE}.repo ${PAGES_URL}/${PACKAGE}.repo
sudo dnf install ${PACKAGE}"

APT_SNIPPET="${APT_KEY_STEP}echo \"deb ${apt_opts}${PAGES_URL}/deb stable main\" | sudo tee /etc/apt/sources.list.d/${PACKAGE}.list
sudo apt-get update && sudo apt-get install ${PACKAGE}"

# --- landing page --------------------------------------------------------------------------------------
cat > "${SITE}/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${PACKAGE} — package repository</title>
<style>
  :root { color-scheme: light dark; --fg: #1a1a1a; --bg: #ffffff; --muted: #5f6368; --line: #e0e0e0; --code-bg: #f5f5f5; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e8e8e8; --bg: #16181c; --muted: #9aa0a6; --line: #2c2f36; --code-bg: #1f2228; }
  }
  body { margin: 0 auto; padding: 3rem 1.25rem 5rem; max-width: 46rem; color: var(--fg); background: var(--bg);
         font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
  h2 { font-size: 1.15rem; margin: 2.5rem 0 .5rem; padding-top: 1.25rem; border-top: 1px solid var(--line); }
  p.sub { color: var(--muted); margin: 0 0 2rem; }
  pre { background: var(--code-bg); border: 1px solid var(--line); border-radius: 6px;
        padding: .9rem 1rem; overflow-x: auto; font-size: .875rem; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  footer { margin-top: 3rem; color: var(--muted); font-size: .875rem; }
  a { color: inherit; }
</style>
</head>
<body>
<h1>${PACKAGE}</h1>
<p class="sub">dnf and apt repositories for <strong>${TAG}</strong> — the BotMaker maintainer's window onto
module pins, release history, the Release tab and the submission queue. Install once, then update with your
package manager.</p>

<h2>Fedora / RHEL</h2>
<pre><code>${DNF_SNIPPET}</code></pre>

<h2>Debian / Ubuntu</h2>
<pre><code>${APT_SNIPPET}</code></pre>

<p>Later updates, either way: <code>sudo dnf upgrade ${PACKAGE}</code> or
<code>sudo apt-get update &amp;&amp; sudo apt-get install --only-upgrade ${PACKAGE}</code>.</p>

<footer>
${TRUST_NOTE}
<p>The package installs a self-contained app under <code>/opt/${PACKAGE}/</code> with its own Java runtime,
and a <em>BotMaker Dashboard</em> entry in the Development menu. It needs <code>git</code>, and asks for an
umbrella checkout on first run.</p>
<p>x86-64 only. This repository carries the <strong>latest release only</strong> — it is an upgrade channel,
not an archive; every version stays on the <a href="https://github.com/${REPO_SLUG}/releases">Releases</a>
page.</p>
</footer>
</body>
</html>
EOF

echo "Site built at ${SITE} ($(du -sh "${SITE}" | cut -f1)), advertising ${TAG} at ${PAGES_URL}"
