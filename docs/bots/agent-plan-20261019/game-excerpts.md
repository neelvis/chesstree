# Recorded game excerpts

These excerpts describe observed play, not proven best-move solutions. Restore positions from `reported-game.txt` using `fixtures.json`; the snippets alone omit earlier state, rights, and history. SAN army prefixes name the original army, while `move|` rows identify the acting controller.

## Queen exposure and loss

Apply the first 138 move rows to inspect the position before exported ply 139.

```text
139: W.Qf2
140: R.Bl8
141: B.f10
142: W.Kh2
143: R.Bm7
144: B.Ng4+
145: W.Kg2
146: R.Rc8
147: B.Nxf2
148: W.Kxf2
```

## Consecutive rook moves

Apply the first 192 move rows to inspect the position before exported ply 193.

```text
193: W.Re1
194: R.Rl6
195: B.Rf11
196: W.Rea1
197: R.Rm8
198: B.Rf9
199: W.Rd1
200: R.Rn8
201: B.Rg11
202: W.Raa1
203: R.Rll8
204: B.Rff11
205: W.Ra2
```

## Three-player exchanges

Apply the first 242 move rows to inspect the position before exported ply 243.

```text
243: B.Qn6
244: W.Bd3
245: R.Nxk9+
246: B.Bxk9
247: W.Kd1
248: R.Bm7
249: B.Qxd6
250: W.Ke1
251: R.Rn8
252: B.Nl11
253: W.Be2
254: R.Qm8
255: B.Qk7
256: W.Rd2
257: R.Bl8
258: B.Qxk5
259: W.Bd1
260: R.Bd5
261: B.Ql6
262: W.cxd5
263: R.Bm7
264: B.m5
265: W.d6+
266: R.Nb7
267: B.Bxb7+
268: W.d7+
269: R.Rxb7
270: B.Nk9
271: W.Bf3
272: R.Bxl6
273: B.Nxl6
```

## First promotion and queen trade

Apply the first 288 move rows to inspect the position before exported ply 289.

```text
289: W.Rb1
290: R.Rbl8
291: B.Kf11
292: W.Rb3
293: R.Qn10
294: B.Rk7
295: W.d8=Q+
296: R.Qd7
297: B.Rkk12
298: W.Qxd7+
299: R.Kb8
300: B.Nxd7+
301: W.Rbc3+
```

## Late-game pressure

Apply the first 408 move rows to inspect the position before exported ply 409.

```text
409: W.e10
410: R.Kl7
411: B.Rhf12
412: W.Ng9+
413: R.Kl6+
414: B.Kg11
415: W.Bc6
416: R.Km6
417: B.Rm12+
418: W.e11+
419: R.Kn7
420: B.Rh12
421: W.Ne10+
422: R.Kn8+
423: B.Kg12
424: W.Nd4
425: R.Rl8
426: B.Re12
427: W.Bk9+
428: R.Rl9+
429: B.Kh11
430: W.Bd5
```

## Second promotion and final elimination

Apply the first 456 move rows to inspect the position before exported ply 457.

```text
457: W.b6
458: R.Kl10
459: B.Kh10
460: W.b7
461: R.Ra6
462: B.Rn12
463: W.b8=Q
464: R.Kl11
465: B.Rne12
466: W.Ql9+
467: R.Kk11
468: B.R12e4
469: W.Qf12+
470: R.Ra8+
471: B.Kh11
472: W.Rf11+
473: R.Kl10#
474: W.Bd5#
```

## Verified terminal facts

- After move473, Black is checkmated by White. White controls Black army; Red remains active.
- After move474, Red is checkmated by White. Ranking: White first, Red second, Black third.
- The public reducer records the eliminated player's next-turn ply (474 and475), so status `atPly` differs from the exported triggering move number.
- A `#` suffix does not identify the mate author solely from the army or controller making the last move.
