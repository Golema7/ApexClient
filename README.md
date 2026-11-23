# ApexClient

A client-side Fabric mod for Minecraft Java 1.21.11. The first feature is a read-only auction flip scanner for a private test server.

## Auction scanner

The scanner observes item tooltips in visible marketplace screens. It records pages while `/ah` or `/orders` is open, then compares auction stack prices with open order prices.

- `/ah` prices are treated as the total price for the displayed stack.
- `/orders` prices marked `each` are treated as per-item prices; remaining order quantity is read from the Delivered progress line.
- Suggestions are ranked by estimated gross profit and return.
- Rounded `K/M/B/T` prices are marked approximate and use conservative bounds.
- A capture older than two minutes is considered stale.
- Only captured visible pages are considered. Open more pages in both menus to expand the comparison.
- Results are estimates before any server fees or other costs. Item matching uses the visible item name and tooltip text, so unusual plugin lore can affect matching.
- This feature does not buy, list, deliver, click, send commands, or otherwise interact with the market.

## Use

1. Install Fabric Loader for Minecraft 1.21.11 and put Fabric API in the same `mods` folder.
2. Build this project with Java 21 using `gradlew.bat build` on Windows.
3. Copy `build/libs/apexclient-0.1.0.jar` into your Minecraft `mods` folder.
4. Join the test server and open `/ah`. Visit the pages you want included.
5. Open `/orders` and visit its pages.
6. Close the menu and press **M** to open the scanner. The key can be changed in Options > Controls > Key Binds.

The scanner keeps captures in memory for the current game session only. If the list is empty, visit the marketplace pages and reopen the scanner.

## Build requirements

- JDK 21
- Gradle Wrapper (included)
- Minecraft Java 1.21.11, Fabric Loader, and Fabric API versions are pinned in the Gradle files.
