# Android launcher variants

All 13 launcher choices use the approved Notation 2.1 paths. The default and all twelve alternatives use the same launcher optical scale. Twelve glass alternatives retain their Apple-style rounded square, Samsung-style squircle and Pixel circle silhouette with four light/dark/clear variants. These are candidate treatments, not a separately approved redesign. All variants have an opaque monochrome themed layer. Old raster launcher assets were removed rather than left selectable in density-specific resources. Notification and shared UI logo use the same monochrome silhouette. Existing alias names are unchanged. Verify actual launcher masks and the settings preview on Android before acceptance.

## Launcher optical sizing (2026-11-26)

Following physical comparison with Apple Music, the owner approved a 15% reduction of the Notation symbol, not the icon background. All launcher foregrounds and themed monochrome layers now scale the original mark by 0.85 around the centre (54,54) of the 108dp viewport. The legacy foreground resource follows the same geometry. Paths, gradient stops, rim, background, masks, aliases and in-app/notification logos are unchanged. The structural gate checks the transform for every launcher foreground/monochrome layer and round alias. Physical rendering of this revised scale remains to be verified.
