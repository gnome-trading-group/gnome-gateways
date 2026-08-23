package group.gnometrading.gateways.exchanges.polymarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOrderSigner;
import java.math.BigInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolymarketOrderSignerTest {

    // Well-known secp256k1 test key (NOT for production use)
    private static final byte[] TEST_PRIVATE_KEY =
            new BigInteger("fad9c8855b740a0b7ed4c221dbad0f33a83a49cad6b3fe8d5817ac83d38b6a19", 16).toByteArray();
    private static final String TEST_SIGNER = "0x96216849c49358B10257cb55b28eA603c874b05E";

    private static final long PRICE_SCALE = 1_000_000_000L;
    private static final long SIZE_SCALE = 1_000_000L;

    private PolymarketOrderSigner signer;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = TEST_PRIVATE_KEY;
        if (keyBytes.length == 33 && keyBytes[0] == 0) {
            byte[] trimmed = new byte[32];
            System.arraycopy(keyBytes, 1, trimmed, 0, 32);
            keyBytes = trimmed;
        }
        signer = new PolymarketOrderSigner(keyBytes, TEST_SIGNER);
    }

    @Test
    void signOrderReturnsMakerAndSigner() {
        final PolymarketOrderSigner.SignedOrder order =
                signer.signOrder(BigInteger.valueOf(12345), 500_000L, 1_000_000L, 0, 0L);

        assertEquals(TEST_SIGNER, order.maker());
        assertEquals(TEST_SIGNER, order.signer());
    }

    @Test
    void signOrderTokenIdMatchesInput() {
        final BigInteger tokenId = BigInteger.valueOf(9876543210L);
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(tokenId, 100L, 200L, 0, 0L);

        assertEquals(tokenId, order.tokenId());
    }

    @Test
    void signOrderMakerAndTakerAmountsMatchInput() {
        final long makerAmount = 500_000L;
        final long takerAmount = 1_000_000L;
        final PolymarketOrderSigner.SignedOrder order =
                signer.signOrder(BigInteger.ONE, makerAmount, takerAmount, 0, 0L);

        assertEquals(makerAmount, order.makerAmount());
        assertEquals(takerAmount, order.takerAmount());
    }

    @Test
    void signatureHexIs65BytesWithPrefix() {
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(BigInteger.ONE, 100L, 200L, 0, 0L);

        final String sig = order.signatureHex();
        assertNotNull(sig);
        assertTrue(sig.startsWith("0x"), "Signature must start with 0x");
        // 2 (prefix) + 64 (r) + 64 (s) + 2 (v) = 132 characters
        assertEquals(132, sig.length(), "Signature must be 65 bytes (130 hex chars) + 0x prefix");
    }

    @Test
    void signatureRAndSArePositive() {
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(BigInteger.ONE, 100L, 200L, 0, 0L);

        assertTrue(order.r().signum() > 0, "r must be positive");
        assertTrue(order.s().signum() > 0, "s must be positive");
    }

    @Test
    void saltIncreasesMonotonically() {
        final PolymarketOrderSigner.SignedOrder first = signer.signOrder(BigInteger.ONE, 100L, 200L, 0, 0L);
        final PolymarketOrderSigner.SignedOrder second = signer.signOrder(BigInteger.ONE, 100L, 200L, 0, 0L);

        assertTrue(second.salt() > first.salt(), "Salt must increase with each signing");
    }

    @Test
    void keccak256ProducesCorrectDigest() {
        // SHA3/Keccak256 of empty string: c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
        final byte[] empty = new byte[0];
        final byte[] digest = PolymarketOrderSigner.keccak256(empty);
        assertEquals(32, digest.length);
        assertEquals((byte) 0xc5, digest[0]);
        assertEquals((byte) 0xd2, digest[1]);
        assertEquals((byte) 0x46, digest[2]);
    }

    @Test
    void keccak256OfKnownString() {
        // Keccak256 of "hello" = 1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8
        final byte[] input = "hello".getBytes();
        final byte[] digest = PolymarketOrderSigner.keccak256(input);
        assertEquals(32, digest.length);
        assertEquals((byte) 0x1c, digest[0]);
        assertEquals((byte) 0x8a, digest[1]);
        assertEquals((byte) 0xff, digest[2]);
        assertEquals((byte) 0x95, digest[3]);
    }

    @Test
    void signOrderBuySideIsZero() {
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(BigInteger.ONE, 100L, 200L, 0, 0L);
        assertEquals(0, order.side());
    }

    @Test
    void signOrderSellSideIsOne() {
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(BigInteger.ONE, 100L, 200L, 1, 0L);
        assertEquals(1, order.side());
    }

    @Test
    void signOrderReturnsValidV() {
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(BigInteger.ONE, 100L, 200L, 0, 0L);
        assertTrue(order.v() == 27 || order.v() == 28, "v must be 27 or 28, got " + order.v());
    }

    @Test
    void signOrderVIsConsistentAcrossMultipleSignings() {
        int v27Count = 0;
        int v28Count = 0;
        for (int i = 0; i < 20; i++) {
            final PolymarketOrderSigner.SignedOrder order =
                    signer.signOrder(BigInteger.valueOf(i + 1), (long) (i + 1) * 100, (long) (i + 1) * 200, i % 2, 0L);
            if (order.v() == 27) {
                v27Count++;
            } else if (order.v() == 28) {
                v28Count++;
            } else {
                fail("v must be 27 or 28, got " + order.v());
            }
        }
        // If recoverV always returned 27, v28Count would be 0.
        assertTrue(
                v27Count > 0 && v28Count > 0,
                "Expected both v=27 and v=28 across 20 signings, got v27=" + v27Count + " v28=" + v28Count);
    }

    @Test
    void signOrderWithRealTokenIdDoesNotOverflow() {
        // Real Polymarket token ID — 77-digit uint256 that overflows long
        final BigInteger realTokenId =
                new BigInteger("21742633143463906290569050155826241533067272736897614950488156847949938836455");
        final PolymarketOrderSigner.SignedOrder order = signer.signOrder(realTokenId, 500_000L, 1_000_000L, 0, 0L);

        assertEquals(realTokenId, order.tokenId());
        assertNotNull(order.signatureHex());
        assertEquals(132, order.signatureHex().length());
    }
}
