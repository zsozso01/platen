# Privacy

Platen is a printing tool. It is designed so that it *cannot* leak what you print.

* **No account.** There is nothing to sign up for or sign in to.
* **No telemetry, analytics or crash reporting.** Not by default, and there is no hidden switch. If a
  future version ever offers opt-in diagnostics, it will be off, local-first and documented here first.
* **No ads, no trackers, no third-party SDKs** that talk to the internet.
* **No network access except to your printer.** Platen connects only to printers on your local network
  (discovered by mDNS or typed in by you) or plugged in by USB. It does not contact any server run by
  the project.
* **Your documents stay on your device** until you send them to your printer. Temporary files used while
  printing are deleted afterwards.
* **Diagnostic reports are manual.** If you choose *Settings → Diagnostics → Export report*, the app
  writes a text report of your printer's capabilities and the protocol conversation. Serial numbers, host
  names, MAC addresses and IP addresses are removed. Nothing is sent anywhere; you decide whether to share it.

Android permissions the app asks for, and why:

| Permission | Why |
|---|---|
| Internet / network state | Talk to printers on your local network |
| Multicast (Wi-Fi) | Find printers announced by mDNS |
| USB host (optional hardware feature) | Talk to a printer plugged in with a cable |

The source is open under Apache-2.0: you can check all of this.
