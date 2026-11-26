# AMS — Soul editorial evidence

Previous v3 live run 45844219232: 7 accepted distinct albums, three
release-room reads. Official Soul/Funk curator 976439585 is shared.
User approved contextual acceptance of the combined R&B/Soul label,
not a global taxonomy alias. Public example: the official curator
https://music.apple.com/br/curator/apple-music-soul-e-funk/976439585
and Mood 2 https://music.apple.com/br/album/mood-2/6779424635.

A verified curator identity and a linked release-room context are
required before accepting the combined label. Keep exact Soul separate
from combined labels; neither generic R&B nor Funk alone qualifies.
Do not broaden Samba, Pagode, Forró or generic search. The test now
reads up to five pages per Soul release room (maximum three rooms),
records raw source rows, unique candidates, exact and additional
combined labels, rejected distinct candidates and read failures.
Repeated cursor fails the audit instead of silently truncating.

Focused Soul workflow uses AMS_CURATOR_ONLY=soul and no other genre,
chart or fallback. Reggae remains independently validated on its own
branch; this branch descends from the Reggae corpus revision but does
not repeat its live test. Do not treat code as live-homologated yet.
