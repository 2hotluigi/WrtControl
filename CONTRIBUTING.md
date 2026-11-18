# Contributing to WrtControl

Thanks for your interest. You can write in **English or Spanish**; the code, comments and
technical docs are in Spanish, but nobody needs to write Spanish to contribute.

*En español: los issues y los PR pueden ir en español o en inglés. Abajo está todo lo que hace
falta saber; el detalle técnico está en [docs/desarrollo.md](docs/desarrollo.md).*

## The most useful contribution right now: your router

WrtControl is tested nightly against OpenWrt in virtual machines and every day on one physical
router. Every other router is new territory. If you run OpenWrt 24.10 or newer, please
[open a router report](https://github.com/2hotluigi/WrtControl/issues/new?template=router_report.yml):
model, OpenWrt version, and what worked or didn't. Even "everything worked" helps.

Please **don't paste** MAC addresses, public IP addresses, passwords, keys or Wi-Fi names in
issues. Screenshots are welcome once those are hidden.

## Reporting a bug

Use the [bug report form](https://github.com/2hotluigi/WrtControl/issues/new?template=bug_report.yml).
Say what you did, what you expected and what happened, plus your router model, OpenWrt version,
app version (Android Settings → Apps → WrtControl) and Android version.

If the app left your router in a strange state, LuCI still works: changes the app makes are
marked with `wrtc` in their names (firewall, dhcp, network, wireless) so you can tell them apart
from yours.

Security issues go through [SECURITY.md](SECURITY.md), not public issues.

## Changing code

1. **Open an issue first** for anything bigger than a small fix, so we agree on the approach.
2. **Build and test** (JDK 25 like CI, Android SDK; no Go needed unless you change `tunnel/`):

   ```sh
   ./gradlew testDebugUnitTest      # unit tests, no emulator
   ./gradlew detekt lintDebug       # static analysis and style
   ./gradlew verifyRoborazziDebug   # screenshot tests
   ./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
   ```

   Tests against a real OpenWrt (a VM or your own router) are described in
   [docs/desarrollo.md](docs/desarrollo.md).
3. **Keep pull requests small**, one topic each. The PR template lists the definition of done:
   - tests for the change (and VM tests if it touches the router: `parental/`, `addons/`,
     `openwrt/`, `ubus/`);
   - CI green;
   - README, ROADMAP and docs up to date;
   - a line in `CHANGELOG.md` under "Sin publicar" if users will notice it;
   - a screenshot if a screen changed;
   - no personal data (MACs, public IPs, names, keys, passwords).

### Principles a change must keep

- **Never leave the user without internet or without their router.** Router changes go through
  `applySafely`: apply with rollback, verify on the router, then confirm.
- **Start without installing anything.** The LuCI ACL is enough for the basics; anything else is
  a package the app installs and explains.
- **Plain language.** Every option says what it achieves. Technical details go last, folded.
  User-facing text lives in `strings.xml` (Spanish in `values/`, English in `values-en/`).
- **Nothing hardcoded about a specific router.** Interfaces, radios and zones are read from the
  router.

## Licensing

By contributing, you agree that your contribution is licensed under the project's license,
[GPL-3.0-or-later](LICENSE).

Participation is governed by our [Code of Conduct](CODE_OF_CONDUCT.md).
