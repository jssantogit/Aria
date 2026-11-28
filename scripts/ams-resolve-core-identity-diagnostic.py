#!/usr/bin/env python3
"""Resolve reviewed hunks, not entire files, in the synthetic merge."""
from pathlib import Path
import re
import subprocess

APP="app/src/main/java/com/music/aria/"
PAT=re.compile(r"^<<<<<<< HEAD\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]+\n", re.M|re.S)

def resolve(path, decisions):
    p=Path(path)
    raw=p.read_text()
    matches=list(PAT.finditer(raw))
    assert len(matches)==len(decisions),(path,len(matches),len(decisions))
    idx=0
    def select(m):
        nonlocal idx
        expected,fn=decisions[idx]
        idx+=1
        a,b=m.groups()
        assert expected in a+b,(path,expected)
        return fn(a,b)
    result=PAT.sub(select,raw)
    assert "<<<<<<< HEAD" not in result,path
    p.write_text(result)
    subprocess.run(["git","add","--",path],check=True)
    print("RESOLVED_CORE|"+path)

ours=lambda a,b:a
theirs=lambda a,b:b
resolve("app/build.gradle.kts",[
    ("AMS_PHYSICAL_SMOKE",lambda a,b:a.replace('"BitChord Dev"','"Aria Dev"')),
])
resolve(APP+"data/sources/addon/AddonClient.kt",[
    ("awaitAddonHttp",lambda a,b:a.replace("rate limiting BitChord","rate limiting Aria")),
])
def preview(a,b):
    # Keep the complete revocable transport and preview source introduced by
    # AMS/IDM; MAIN's change here is removal of the wearable speaker bridge.
    assert "createPreviewSource" in a and "WearBridge.attachStreams" in a
    return "\n".join(l for l in a.splitlines() if "WearBridge.attachStreams" not in l and "The watch, when it is the speaker" not in l)+"\n"
resolve(APP+"playback/PlaybackService.kt",[("createPreviewSource",preview)])
resolve(APP+"ui/components/FrostedTopBar.kt",[
    ("physical hit-test host height",ours),
    ("shared resting offset",theirs),
    ("native-global-floating-chrome-host",ours),
])
resolve("app/src/test/java/com/music/aria/HomeSettingsOverflowAlignmentTest.kt",[
    ("artworkAndTitlesClearTheRestingControlsAtEveryStatusBarInset",theirs),
])
home="sharedUi/src/jvmSharedMain/kotlin/com/music/aria/ui/screens/HomeScreen.kt"
resolve(home,[("SignInBanner",lambda a,b:"")])
# MAIN removed sign-in presentation; AMS removed the feed-loading skeleton.
# Do not reintroduce either by selecting a whole file.
s=Path(home).read_text()
assert "SignInBanner(" not in s, "Unmerged sign-in call needs review"
assert "feedMoreSkeleton()" not in s, "Unmerged feed skeleton needs review"
