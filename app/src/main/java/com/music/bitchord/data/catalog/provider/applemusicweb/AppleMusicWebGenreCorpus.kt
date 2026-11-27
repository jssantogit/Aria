package com.music.bitchord.data.catalog.provider.applemusicweb

/** AMS editorial coverage v4, explicitly revised by the user.
 * Rap replaces the overlapping Hip-hop case. The previous IDM parity
 * corpus remains unchanged for cross-provider historical comparison.
 */
internal val amsEditorialGenreCorpus = listOf(
    "pop", "rock", "rap", "electronic", "jazz", "k-pop", "metal", "rnb",
    "soul", "samba", "blues", "pagode", "forro", "reggae", "indie",
)

/** Separate from the frozen 15-genre main corpus; style navigation has its
 * own source-evidence contract. A parent is a discovery anchor, not proof. */
internal val amsEditorialStyleParents = mapOf(
    "indie pop" to "pop", "dance-pop" to "pop", "art pop" to "pop", "synthpop" to "pop",
    "alternative rock" to "rock", "hard rock" to "rock", "progressive rock" to "rock", "post-rock" to "rock",
    "trap" to "rap", "drill" to "rap", "boom bap" to "rap", "conscious hip hop" to "rap",
    "house" to "electronic", "techno" to "electronic", "trance" to "electronic", "drum and bass" to "electronic",
    "neo soul" to "rnb", "contemporary r&b" to "rnb", "alternative r&b" to "rnb",
    "bebop" to "jazz", "cool jazz" to "jazz", "jazz fusion" to "jazz", "smooth jazz" to "jazz",
    "heavy metal" to "metal", "thrash metal" to "metal", "death metal" to "metal", "black metal" to "metal", "doom metal" to "metal",
    "baroque" to "classical", "chamber music" to "classical", "opera" to "classical",
    "bluegrass" to "folk", "folk rock" to "folk", "indie folk" to "folk",
    "dark ambient" to "ambient", "drone" to "ambient", "ambient techno" to "ambient",
    "hardcore punk" to "punk", "post-punk" to "punk", "pop punk" to "punk",
)
internal fun amsSupportsEditorialTag(tag: String): Boolean =
    tag in amsEditorialGenreCorpus || tag in amsEditorialStyleParents
