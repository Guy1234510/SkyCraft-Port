# Publish a reviewed SkyCraft snapshot

The project repository is [Guy1234510/SkyCraft-Port](https://github.com/Guy1234510/SkyCraft-Port).
Use compiled Release assets for player downloads; keep binaries out of Git history.

`python tools/prepare_github.py` prepares a separate audited source snapshot under
`dist/github/SkyCraft-<date>/`. `dist/github/latest-source.json` identifies it.
It retains necessary code, build files, English documentation and license notices.
Unused dependency fixtures/examples, old packages, game files, local notes,
screenshots, credentials, caches and machine settings are excluded.

The exporter blocks personal home/build paths, local Git identities, private-key
and credential patterns, Portuguese non-code prose and files at or above 100 MB.
Review its output before committing. Use a GitHub no-reply email and a public
GitHub handle for both author and committer; do not import the development history.

Create version-specific downloads with `python tools/package_release.py --split`.
Attach each compiled ZIP and its SHA-256 checksum to the corresponding Forge or
NeoForge release. Also attach the matching native corresponding-source archive;
see [DISTRIBUTION.md](../DISTRIBUTION.md). That archive includes dependency source
and recipes, not installed game files or local build output.

The README and installation guide disclose **100% vibe-coded** development and
**no regular updates**. The preparation and packaging scripts do not upload files
or change repository visibility automatically.
