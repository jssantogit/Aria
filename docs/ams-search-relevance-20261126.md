# AMS preliminary independent relevance review — 2026-11-26

Source: [matrix 45816332934](https://github.com/jssantogit/Aria/actions/runs/45816332934), HEAD `11eeed04`, ranked landing. All 223 returned rows reviewed manually from sanitized title/credit and identity evidence. No rows removed. `ILLIT (2)` returned zero; retain that cell. Categories are local, not Apple native genre search.

The accompanying JSON records each judgement. This is a dated preliminary editorial assessment, not a ranker, automatic gate or permanent truth. For title-only queries, same-title artists/editions can be relevant; explicit credit+work requires both; authentic variants are relevant. Exact-name artist candidates without independent identity evidence remain unresolved (including possible legitimate homonyms). Related local genres count as relevant. No claim about actual legitimacy of unverified artists.

| Category | Top 5 relevant / judged | Top 5 judged / returned | Top 10 relevant / judged | Top 10 judged / returned |
|---|---:|---:|---:|---:|
| Artists | 9/19 | 19/41 | 9/38 | 38/80 |
| Albums | 27/36 | 36/37 | 45/71 | 71/72 |
| Tracks | 30/30 | 30/30 | 49/59 | 59/60 |
| Local categories | 10/10 | 10/10 | 11/11 | 11/11 |

Target availability (strict or independently qualified) 29/30 Top 5 and Top 10. This is **not** 96.7% relevance. Goal remains 90% relevance among judged with coverage reported; low artist coverage blocks completion. Major noise: same-credit unrelated albums in compound queries; near-name artists; cross-credit covers in compound track searches. Next: verify unresolved artist identities and test conservative relevance policy without hiding excluded rows or shrinking the comparison denominator.
