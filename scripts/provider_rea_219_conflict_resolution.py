#!/usr/bin/env python3
"""Explicit, fail-closed #219 x frozen #212 *disposable CI* reconciliation.

Runs ONLY after a normal no-commit merge reports the four reviewed conflicts.
This is neither a branch merge nor permission to rewrite or bypass REA UI.
No ours/theirs blanket preference is applied: all 18 conflict blocks are
identified by expected old/new context and reconciled per semantic contract.
An unrecognized change halts the CI so a newer REA cannot silently disappear.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = "app/src/main/java/com/music/bitchord/MainActivity.kt"
HELD = "app/src/main/java/com/music/bitchord/ui/components/HeldContextMenu.kt"
DETAIL = "app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt"
SEARCH = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt"
EXPECTED = {
    MAIN: [
        ("canonicalAppleArtworkPromotion", "selectedSearchProviderKey"),
        ("targetState = when", "targetState = pageRouteKey"),
        ("communityMetadataRecording = recording", "LongPressOrigin.consume()"),
        ("refreshCanonicalGenreEditorial", "viewModel.loadCanonicalGenre"),
        ("val visualDetail = ", "toolbarOwner == NativeTopBarRouteOwner.CATALOG_DETAIL"),
        ("openedPublicPlaylist != null", "NativeTopBarRouteOwner.PUBLIC_PLAYLIST"),
        ("unifiedLibraryCategory != null", "NativeTopBarRouteOwner.UNIFIED_LIBRARY"),
        ("appleNativeKind == AppleNativeDetailKind.ALBUM", "appleExploreGenreChrome"),
        ("accessory = if (", "accessory = if (appleSearchRootChrome)"),
        ("if (appleSharedVisual != null", "toolbarOwner != NativeTopBarRouteOwner.PLAYLIST_LIBRARY"),
        ("!appleNativeDetailOpen", "!appleSearchRootChrome"),
        ("!isReleaseDetail && !appleNativeDetailOpen", "!appleSearchRootChrome && !isReleaseDetail"),
    ],
    HELD: [("Color(0xFF29292D)", "nativeCompactPlateColor")],
    DETAIL: [("Same shared lifted metadata action", "Full-screen lifted metadata menu")],
    SEARCH: [
        ("LocalUriHandler", "testTag"),
        ("appleResults: UiState", "searchCommitted: Boolean"),
        ("searchingCanonical -> when", "canonicalSearchEditing"),
        ("else -> canonicalMusicBrainzResults(", "searchingAddons && serverSearchEditing"),
    ],
}
PATTERN = re.compile(
    r"(?m)^<<<<<<< HEAD\r?\n(?P<ours>.*?)^=======\r?\n"
    r"(?P<rea>.*?)^>>>>>>> refs/remotes/origin/rea-212\r?\n",
    re.S | re.M,
)


def glue(*parts: str) -> str:
    return "\n".join(x.rstrip("\n") for x in parts if x.strip()) + "\n"


def replace_once(text: str, old: str, new: str, where: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError(f"{where}: expected one anchor {old!r}, got {text.count(old)}")
    return text.replace(old, new, 1)


def replace_main(i: int, ours: str, rea: str) -> str:
    if i == 1:
        # Independently owned states: source-native Apple AND REA search tabs.
        return glue(ours, rea)
    if i == 2:
        # REA owns tab animation, top-bar route identity and page transitions.
        # Apple-native route is added into pageRouteKey, not a second when.
        return rea
    if i == 3:
        # REA owns the held action; metadata-only tap without a registered
        # long-press still opens its non-playable detail, never Media3.
        return glue(
            "                            onCanonicalTrackHold = { recording ->",
            "                                val origin = LongPressOrigin.consume()",
            "                                if (origin != null) {",
            "                                    heldCatalogSearchOrigin = origin",
            "                                    heldCatalogSearchRow = recording",
            "                                } else {",
            "                                    communityMetadataRecording = recording",
            "                                }",
        )
    if i == 4:
        # No unguarded legacy genre retry; the policy-aware editorial path wins.
        return ours
    if i == 5:
        # Keep REA route ownership but admit source-native Apple detail ONLY
        # while the Apple route is really open.
        return glue(
            "                val visualDetail = detail ?: appleSharedVisual?.page",
            "                val isDetailVisible =",
            "                    ((toolbarOwner == NativeTopBarRouteOwner.CATALOG_DETAIL && detail != null) ||",
            "                        (appleNativeDetailOpen && appleSharedVisual != null)) &&",
            "                    (visualDetail?.type == BrowseType.ALBUM ||",
            "                        visualDetail?.type == BrowseType.PLAYLIST ||",
            "                        visualDetail?.type == BrowseType.ARTIST) &&",
        )
    if i == 6:
        return glue(
            "                        toolbarOwner == NativeTopBarRouteOwner.PUBLIC_PLAYLIST ->",
            "                            openedPublicPlaylist?.title.orEmpty()",
            "                        visualDetail != null && detailActiveShelf != null ->",
            "                            detailActiveShelf?.title.orEmpty()",
        )
    if i == 7:
        return glue(
            "                            toolbarOwner == NativeTopBarRouteOwner.UNIFIED_LIBRARY ||",
            "                            (toolbarOwner == NativeTopBarRouteOwner.CATALOG_DETAIL &&",
            "                                detail != null && detailActiveShelf != null) ||",
            "                            (appleNativeDetailOpen && visualDetail != null &&",
            "                                detailActiveShelf != null) -> true",
            "                        toolbarOwner == NativeTopBarRouteOwner.PLAYLIST_LIBRARY ->",
        )
    if i == 8:
        # Different destinations: Apple native back and REA Explore back.
        return glue(ours, rea)
    if i == 9:
        return rea
    if i == 10:
        # Native Apple actions are intentionally additive. REA owns the
        # visible root toolbar and must prevent duplicated playlist controls.
        ours = replace_once(
            ours,
            "if (!showSettings && !showAccountScrobbling && !showPlaylistLibrary) {",
            "if (!showSettings && !showAccountScrobbling &&\n"
            "                            toolbarOwner != NativeTopBarRouteOwner.PLAYLIST_LIBRARY &&\n"
            "                            toolbarOwner != NativeTopBarRouteOwner.PUBLIC_PLAYLIST) {",
            "MainActivity:toolbar",
        )
        return ours
    if i == 11:
        return ours.replace(
            "unifiedLibraryCategory == null && !appleNativeDetailOpen",
            "unifiedLibraryCategory == null && !appleSearchRootChrome && !appleNativeDetailOpen",
        )
    if i == 12:
        return rea.replace(
            "if (!appleSearchRootChrome && !isReleaseDetail &&",
            "if (!appleSearchRootChrome && !appleNativeDetailOpen && !isReleaseDetail &&",
        )
    raise RuntimeError(f"MainActivity unexpected conflict #{i}")


def apple_filtered_rows() -> str:
    # Called only after the provider-policy planner chose the Apple source.
    # The same native REA Search list/compositor remains in use.
    return """                } else if (!catalogSearchMusicBrainzVisible && canonicalDetail == null &&
                    (appleResults != null || appleArtistResults != null || appleTrackResults != null)
                ) {
                    when (filter) {
                        SearchFilter.ARTISTS -> appleEditorialArtistRows(
                            appleArtistResults, onAppleOpen,
                            paging = applePaging[AppleSearchSection.ARTISTS],
                            onLoadMore = { onAppleLoadMore(AppleSearchSection.ARTISTS) },
                        )
                        SearchFilter.ALBUMS -> appleEditorialAlbumRows(
                            appleResults, onAppleOpen,
                            paging = applePaging[AppleSearchSection.ALBUMS],
                            onLoadMore = { onAppleLoadMore(AppleSearchSection.ALBUMS) },
                        )
                        SearchFilter.SONGS -> appleEditorialTrackRows(
                            appleTrackResults, onAppleTrackOpen,
                            paging = applePaging[AppleSearchSection.TRACKS],
                            onLoadMore = { onAppleLoadMore(AppleSearchSection.TRACKS) },
                        )
                        SearchFilter.ALL -> {
                            appleEditorialArtistRows(
                                appleArtistResults, onAppleOpen, showHeading = true,
                                paging = applePaging[AppleSearchSection.ARTISTS],
                                onLoadMore = { onAppleLoadMore(AppleSearchSection.ARTISTS) },
                            )
                            appleEditorialTrackRows(
                                appleTrackResults, onAppleTrackOpen, showHeading = true,
                                paging = applePaging[AppleSearchSection.TRACKS],
                                onLoadMore = { onAppleLoadMore(AppleSearchSection.TRACKS) },
                            )
                            appleEditorialAlbumRows(
                                appleResults, onAppleOpen, showHeading = true,
                                paging = applePaging[AppleSearchSection.ALBUMS],
                                onLoadMore = { onAppleLoadMore(AppleSearchSection.ALBUMS) },
                            )
                        }
                        else -> {}
                    }
"""


def replace_search(i: int, ours: str, rea: str) -> str:
    if i in (1, 2):
        # Import addition and parameter addition; both independent interfaces.
        return glue(ours, rea)
    if i == 3:
        # REA's real edit/submit suggestions and native long-press dominate.
        return rea
    if i == 4:
        anchor = "                } else canonicalMusicBrainzResults(\n"
        # Renderer remains provider-specific only as a data source; the
        # precedence comes from gate.ordered(CATALOG_SEARCH) in ViewModel.
        return replace_once(rea, anchor, apple_filtered_rows() + anchor, "SearchScreen:renderer")
    raise RuntimeError(f"SearchScreen unexpected conflict #{i}")


def resolve_block(path: str, i: int, ours: str, rea: str) -> str:
    expected_ours, expected_rea = EXPECTED[path][i - 1]
    if expected_ours not in ours or expected_rea not in rea:
        raise RuntimeError(
            f"{path}: conflict #{i} changed; expected {expected_ours!r} / {expected_rea!r}"
        )
    if path == MAIN:
        return replace_main(i, ours, rea)
    if path == SEARCH:
        return replace_search(i, ours, rea)
    if path in (HELD, DETAIL):
        # REA is sole authority for this visual statement; source data
        # contracts live in adjacent code, not this conflicting line.
        return rea
    raise RuntimeError(f"Unreviewed path: {path}")


def resolve(path: str, data: str) -> tuple[str, int]:
    matches = list(PATTERN.finditer(data))
    if len(matches) != len(EXPECTED[path]):
        raise RuntimeError(
            f"{path}: expected {len(EXPECTED[path])} reviewed conflicts, got {len(matches)}"
        )
    index = [0]
    def substitute(match: re.Match) -> str:
        index[0] += 1
        return resolve_block(path, index[0], match.group("ours"), match.group("rea"))
    result = PATTERN.sub(substitute, data)
    if "<<<<<<<" in result or ">>>>>>>" in result or re.search(r"(?m)^=======\s*$", result):
        raise RuntimeError(f"{path}: leftover merge marker")
    return result, len(matches)


def reconcile_combined_routes(main: str) -> str:
    # The REA uses one top-bar/AnimatedContent route authority. Add the Apple
    # provider's already-implemented detail underneath it, never reinstate
    # the separate old targetState branch that caused tab ghosts.
    anchor = (
        "                 detail != null -> detail.browseId\n"
        "                 rediscoveryRecordingId != null ->"
    )
    replacement = (
        "                 detail != null -> detail.browseId\n"
        "                 appleNativeDetailOpen -> if (appleNativeKind == AppleNativeDetailKind.ALBUM)\n"
        "                     \"apple_native_album\" else \"apple_native_artist\"\n"
        "                 rediscoveryRecordingId != null ->"
    )
    # Normalize indentation to the existing REA code, preserving local style.
    if anchor not in main:
        match = re.search(
            r"(?m)^(\s*)detail != null -> detail\.browseId\n"
            r"\1rediscoveryRecordingId != null ->",
            main,
        )
        if not match or main.count("detail != null -> detail.browseId") != 1:
            raise RuntimeError("MainActivity: cannot uniquely locate REA pageRouteKey")
        indent = match.group(1)
        anchor = (
            f"{indent}detail != null -> detail.browseId\n"
            f"{indent}rediscoveryRecordingId != null ->"
        )
        replacement = (
            f"{indent}detail != null -> detail.browseId\n"
            f"{indent}appleNativeDetailOpen -> if (appleNativeKind == AppleNativeDetailKind.ALBUM)\n"
            f"{indent}    \"apple_native_album\" else \"apple_native_artist\"\n"
            f"{indent}rediscoveryRecordingId != null ->"
        )
    return replace_once(main, anchor, replacement, "MainActivity:pageRouteKey")


def verify_contracts(main: str, search: str, held: str) -> None:
    for needle in [
        "targetState = pageRouteKey", "appleNativeDetailOpen -> if",
        "canonicalAppleArtworkPromotion", "visibleSearchSources",
        "refreshCanonicalGenreEditorial", "heldCatalogSearchOrigin = origin",
        "toolbarOwner == NativeTopBarRouteOwner.PUBLIC_PLAYLIST",
        "NativeCollectionSearchAction", "NativeHistoryTopBarAction",
    ]:
        if needle not in main:
            raise RuntimeError(f"Combined MainActivity lost {needle!r}")
    for needle in [
        "searchCommitted", "appleResultTabs",
        "canonicalSearchEditing", "onCanonicalLongPress",
        "catalogSearchMusicBrainzVisible", "appleEditorialArtistRows(",
        "appleEditorialAlbumRows(", "appleEditorialTrackRows(",
        "onAppleLoadMore", "onCanonicalRecordingInfo",
        "serverSearchEditing",
    ]:
        if needle not in search:
            raise RuntimeError(f"Combined SearchScreen lost {needle!r}")
    if "heldSpotlightCutout(" not in held or "nativeCompactPlateColor(" not in held:
        raise RuntimeError("Combined hold menu lost frozen source spotlight or REA palette")


def run() -> int:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=U"],
        cwd=ROOT, check=True, stdout=subprocess.PIPE, text=True,
    )
    paths = set(result.stdout.splitlines())
    if paths != set(EXPECTED):
        raise RuntimeError(f"Unknown integration conflicts: {sorted(paths)}; expected {sorted(EXPECTED)}")

    changed = {}
    for path in EXPECTED:
        file = ROOT / path
        data = file.read_text(encoding="utf-8")
        combined, n = resolve(path, data)
        if path == MAIN:
            combined = reconcile_combined_routes(combined)
        if path == SEARCH:
            # The REA deleted this import, but the #219 Apple pagination
            # still uses OutlinedButton in its metadata-only result rows.
            if "OutlinedButton(" in combined and (
                "import androidx.compose.material3.OutlinedButton\\n" not in combined
            ):
                combined = replace_once(
                    combined,
                    "import androidx.compose.material3.MaterialTheme\\n",
                    "import androidx.compose.material3.MaterialTheme\\n"
                    "import androidx.compose.material3.OutlinedButton\\n",
                    "SearchScreen:Apple pagination import",
                )
        changed[path] = (combined, n)
    verify_contracts(changed[MAIN][0], changed[SEARCH][0], changed[HELD][0])

    # No edits until ALL conflict signatures and invariants have passed.
    for path, (content, count) in changed.items():
        (ROOT / path).write_text(content, encoding="utf-8")
        print(f"Explicitly reconciled {path} ({count} verified conflicts)")
        if path == SEARCH:
            # Provenance of the exact CI-generated source, not the unmerged
            # PR file. Include only the currently failing Kotlin section.
            for line_no, line in enumerate(content.splitlines(), 1):
                if 450 <= line_no <= 525:
                    print(f"COMBINED_SEARCH {line_no}: {line}")
    subprocess.run(["git", "add", "--", *EXPECTED], cwd=ROOT, check=True)
    pending = subprocess.check_output(
        ["git", "diff", "--name-only", "--diff-filter=U"],
        cwd=ROOT, text=True,
    )
    if pending.strip():
        raise RuntimeError(f"Unresolved files after reconciliation: {pending}")
    print("PROVISIONAL COMBINED TREE ONLY; original branches remain unmodified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except (RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"FAIL-CLOSED provider/REA reconciliation: {exc}", file=sys.stderr)
        raise SystemExit(1)
