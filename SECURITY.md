# Security policy

WrtControl has administrator access to people's routers, so security reports are taken seriously.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through GitHub:
[Security → Report a vulnerability](https://github.com/2hotluigi/WrtControl/security/advisories/new).

Include what an attacker could do, the steps to reproduce it, and the app and OpenWrt versions you
used. You'll get a first answer within a week. Once a fix is released, you'll be credited in the
advisory unless you prefer otherwise.

You can write in English or Spanish.

## Supported versions

Only the [latest release](https://github.com/2hotluigi/WrtControl/releases/latest) gets security
fixes. The project hasn't reached 1.0 yet.

## Scope

In scope: the Android app, the WireGuard tunnel library in `tunnel/`, and everything the app
writes to a router (firewall rules, dnsmasq and network settings, packages it installs).

Things that are known and documented, not vulnerabilities by themselves:

- The app signs in as `root` through the router's own API (rpcd), like LuCI. Anyone with that
  password controls the router. A limited per-phone user is planned
  (see [ROADMAP.md](ROADMAP.md), the optional router agent).
- On the home network the app talks to the router over plain HTTP, as LuCI does by default.
  Pinning the router's certificate is planned for when HTTPS is enabled.
- Family controls work at the network level (which devices get internet and which DNS answers
  they receive). A determined user with a different device, a mobile connection or a VPN app that
  the filter does not catch can get around them.
