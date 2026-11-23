# ApexClient

A client-side Fabric mod for Minecraft Java 1.21.11. The first feature is an auction scanner and user-toggleable Auto Flip mode for a private test server.

## Auction scanner and Auto Flip

The scanner reads item tooltips from visible /ah and /orders screens. It records pages and ranks positive order matches. Auto Flip is opt-in through the scanner screen's Start Auto Flip button.

When enabled, Auto Flip:

- Opens /ah and advances through visible auction pages.
- Compares per-item prices for matching item names and tooltip details.
- Requires at least three comparable listings, a minimum 20% estimated gross return, and a purchase stack total no greater than $500,000.
- Reopens and rechecks a candidate before it clicks the listing and confirms only when it finds a visible Yes button.
- Waits for the purchased stack to arrive, equips it, and sends /ah sell <average stack price>.
- Stops if the page button, confirmation button, inventory result, or sell result cannot be verified.
- Can be stopped from the scanner button. Press M to open the scanner while it is active.

The price benchmark is the arithmetic mean of the lower-bound per-item prices from captured listings with matching item identity, then multiplied by stack quantity and rounded down. The screenshot example indicated /ah prices are whole-stack totals; Auto Flip uses that convention for /ah sell <price>. The minimum return is gross and fees are unknown, so it is not a guarantee of net profit. The current purchase cap and margin are conservative fixed defaults.

Captures are limited to pages the client sees, stay in memory for the current game session, and become stale after two minutes. Auto Flip uses only fresh captures. This feature is intended for the user's own private test server.

## Use

1. Install Fabric Loader for Minecraft Java 1.21.11 and put Fabric API in the same mods folder.
2. Build with Java 21 using gradlew.bat build on Windows.
3. Copy build/libs/apexclient-0.2.0.jar into the Minecraft mods folder.
4. Join the private test server and open the scanner with M.
5. Click Start Auto Flip to scan, buy, and relist when the configured conditions pass. Click Stop Auto Flip to stop the process.

The original /orders scanner remains read-only. Auto Flip's transaction flow must be tested with the server plugin before merging.

## Build requirements

- JDK 21
- Gradle Wrapper (included)
- Minecraft Java 1.21.11, Fabric Loader, and Fabric API versions are pinned in the Gradle files.
