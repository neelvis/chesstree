package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardTopologyCompatibilityTest {
    @Test
    fun diagonalRaysPreserveEveryRouteAndBranchOrderForAllNinetySixOrigins() {
        assertEquals(96, baseline.size)
        baseline.forEachIndexed { index, expected ->
            val origin = coordinate(index)
            val expectedRoutes = expected.substringBefore(';').split('|').map(::decodeCoordinates)
            assertEquals(
                expectedRoutes,
                ThreePlayerBoardTopology.diagonalRays(origin),
                "Diagonal routes from $origin",
            )
        }
    }

    @Test
    fun diagonalNeighboursPreserveMembershipAndIterationOrderForAllNinetySixOrigins() {
        assertEquals(96, baseline.size)
        baseline.forEachIndexed { index, expected ->
            val origin = coordinate(index)
            assertEquals(
                decodeCoordinates(expected.substringAfter(';')),
                ThreePlayerBoardTopology.diagonalNeighbours(origin).toList(),
                "Diagonal neighbours from $origin",
            )
        }
    }

    private fun coordinate(index: Int): BoardCoordinate = BoardCoordinate(
        vertex = index / 16,
        column = index % 16 / 4,
        row = index % 4,
    )

    private fun decodeCoordinates(encoded: String): List<BoardCoordinate> =
        encoded.chunked(2).map { coordinate(it.toInt(36)) }

    // Captured by executing the pre-index full-scan topology implementation.
    // Each row corresponds to vertex * 16 + column * 4 + row, from 0 through 95.
    // Coordinates use that same index in two-digit base 36. '|' separates complete
    // rays (including their origin); ';' separates rays from ordered neighbours.
    // Keep these fixed expectations independent of the implementation under test.
    private val baseline = listOf(
        "00050a0f|002j2e2927;052j",
        "012n|0104|01060b0g|012f2a2326;2n04062f",
        "022j2m|020508|02070h0k|022b1z2225;2j05072b",
        "032f2i2l|0306090c|030i0l0o|030z121518|031v1y2124;2f060i0z1v",
        "04090e|04012f2a2326;0901",
        "0500|0508|050a0f|05022b1z2225;00080a02",
        "06012n|06090c|060b0g|06030z121518|06031v1y2124;01090b03",
        "07022j2m|070a0d|070h0k|070j0y1114;020a0h0j",
        "080d|0805022b1z2225;0d05",
        "0904|090c|090e|0906030z121518|0906031v1y2124;040c0e06",
        "0a0500|0a0d|0a0f|0a070j0y1114;050d0f07",
        "0b06012n|0b0e|0b0g|0b0i0n0x10;060e0g0i",
        "0c0906030z121518|0c0906031v1y2124;09",
        "0d08|0d0a070j0y1114;080a",
        "0e0904|0e0b0i0n0x10;090b",
        "0f0a0500|0f0h0m0r0w;0a0h",
        "0g0l0q0v|0g0b06012n;0l0b",
        "0h0f|0h0k|0h0m0r0w|0h07022j2m;0f0k0m07",
        "0i0b0e|0i0l0o|0i0n0x10|0i032f2i2l;0b0l0n03",
        "0j070a0d|0j0m0p0s|0j0y1114|0j1f1i1l1o|0j2b2e2h2k;070m0y1f2b",
        "0k0p0u|0k0h07022j2m;0p0h",
        "0l0g|0l0o|0l0q0v|0l0i032f2i2l;0g0o0q0i",
        "0m0h0f|0m0p0s|0m0r0w|0m0j1f1i1l1o|0m0j2b2e2h2k;0h0p0r0j",
        "0n0i0b0e|0n0q0t|0n0x10|0n0z1e1h1k;0i0q0x0z",
        "0o0t|0o0l0i032f2i2l;0t0l",
        "0p0k|0p0s|0p0u|0p0m0j1f1i1l1o|0p0m0j2b2e2h2k;0k0s0u0m",
        "0q0l0g|0q0t|0q0v|0q0n0z1e1h1k;0l0t0v0n",
        "0r0m0h0f|0r0u|0r0w|0r0y131d1g;0m0u0w0y",
        "0s0p0m0j1f1i1l1o|0s0p0m0j2b2e2h2k;0p",
        "0t0o|0t0q0n0z1e1h1k;0o0q",
        "0u0p0k|0u0r0y131d1g;0p0r",
        "0v0q0l0g|0v0x12171c;0q0x",
        "0w11161b|0w0r0m0h0f;110r",
        "0x0v|0x10|0x12171c|0x0n0i0b0e;0v10120n",
        "0y0r0u|0y1114|0y131d1g|0y0j070a0d;0r11130j",
        "0z0n0q0t|0z121518|0z1e1h1k|0z0306090c|0z1v1y2124;0n121e031v",
        "10151a|100x0n0i0b0e;150x",
        "110w|1114|11161b|110y0j070a0d;0w14160y",
        "120x0v|121518|12171c|120z0306090c|120z1v1y2124;0x15170z",
        "130y0r0u|131619|131d1g|131f1u1x20;0y161d1f",
        "1419|14110y0j070a0d;1911",
        "1510|1518|151a|15120z0306090c|15120z1v1y2124;10181a12",
        "16110w|1619|161b|16131f1u1x20;11191b13",
        "17120x0v|171a|171c|171e1j1t1w;121a1c1e",
        "1815120z0306090c|1815120z1v1y2124;15",
        "1914|1916131f1u1x20;1416",
        "1a1510|1a171e1j1t1w;1517",
        "1b16110w|1b1d1i1n1s;161d",
        "1c1h1m1r|1c17120x0v;1h17",
        "1d1b|1d1g|1d1i1n1s|1d130y0r0u;1b1g1i13",
        "1e171a|1e1h1k|1e1j1t1w|1e0z0n0q0t;171h1j0z",
        "1f131619|1f1i1l1o|1f1u1x20|1f0j0m0p0s|1f2b2e2h2k;131i1u0j2b",
        "1g1l1q|1g1d130y0r0u;1l1d",
        "1h1c|1h1k|1h1m1r|1h1e0z0n0q0t;1c1k1m1e",
        "1i1d1b|1i1l1o|1i1n1s|1i1f0j0m0p0s|1i1f2b2e2h2k;1d1l1n1f",
        "1j1e171a|1j1m1p|1j1t1w|1j1v2a2d2g;1e1m1t1v",
        "1k1p|1k1h1e0z0n0q0t;1p1h",
        "1l1g|1l1o|1l1q|1l1i1f0j0m0p0s|1l1i1f2b2e2h2k;1g1o1q1i",
        "1m1h1c|1m1p|1m1r|1m1j1v2a2d2g;1h1p1r1j",
        "1n1i1d1b|1n1q|1n1s|1n1u1z292c;1i1q1s1u",
        "1o1l1i1f0j0m0p0s|1o1l1i1f2b2e2h2k;1l",
        "1p1k|1p1m1j1v2a2d2g;1k1m",
        "1q1l1g|1q1n1u1z292c;1l1n",
        "1r1m1h1c|1r1t1y2328;1m1t",
        "1s1x2227|1s1n1i1d1b;1x1n",
        "1t1r|1t1w|1t1y2328|1t1j1e171a;1r1w1y1j",
        "1u1n1q|1u1x20|1u1z292c|1u1f131619;1n1x1z1f",
        "1v1j1m1p|1v1y2124|1v2a2d2g|1v0306090c|1v0z121518;1j1y2a030z",
        "1w2126|1w1t1j1e171a;211t",
        "1x1s|1x20|1x2227|1x1u1f131619;1s20221u",
        "1y1t1r|1y2124|1y2328|1y1v0306090c|1y1v0z121518;1t21231v",
        "1z1u1n1q|1z2225|1z292c|1z2b020508;1u22292b",
        "2025|201x1u1f131619;251x",
        "211w|2124|2126|211y1v0306090c|211y1v0z121518;1w24261y",
        "221x1s|2225|2227|221z2b020508;1x25271z",
        "231y1t1r|2326|2328|232a2f0104;1y26282a",
        "24211y1v0306090c|24211y1v0z121518;21",
        "2520|25221z2b020508;2022",
        "26211w|26232a2f0104;2123",
        "27221x1s|27292e2j00;2229",
        "282d2i2n|28231y1t1r;2d23",
        "2927|292c|292e2j00|291z1u1n1q;272c2e1z",
        "2a2326|2a2d2g|2a2f0104|2a1v1j1m1p;232d2f1v",
        "2b1z2225|2b2e2h2k|2b020508|2b0j0m0p0s|2b1f1i1l1o;1z2e020j1f",
        "2c2h2m|2c291z1u1n1q;2h29",
        "2d28|2d2g|2d2i2n|2d2a1v1j1m1p;282g2i2a",
        "2e2927|2e2h2k|2e2j00|2e2b0j0m0p0s|2e2b1f1i1l1o;292h2j2b",
        "2f2a2326|2f2i2l|2f0104|2f030i0l0o;2a2i0103",
        "2g2l|2g2d2a1v1j1m1p;2l2d",
        "2h2c|2h2k|2h2m|2h2e2b0j0m0p0s|2h2e2b1f1i1l1o;2c2k2m2e",
        "2i2d28|2i2l|2i2n|2i2f030i0l0o;2d2l2n2f",
        "2j2e2927|2j2m|2j00|2j02070h0k;2e2m0002",
        "2k2h2e2b0j0m0p0s|2k2h2e2b1f1i1l1o;2h",
        "2l2g|2l2i2f030i0l0o;2g2i",
        "2m2h2c|2m2j02070h0k;2h2j",
        "2n2i2d28|2n01060b0g;2i01",
    )
}
