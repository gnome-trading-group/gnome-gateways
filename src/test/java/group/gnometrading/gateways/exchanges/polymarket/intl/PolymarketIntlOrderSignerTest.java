package group.gnometrading.gateways.exchanges.polymarket.intl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSignerAccess;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Expected values come from Polymarket's official py-clob-client-v2 ({@code ExchangeOrderBuilderV2},
 * commit 292c110) signing the same inputs; regenerate them with {@code scripts/pm_v2_vectors.py}.
 */
class PolymarketIntlOrderSignerTest {

    // Hardhat account #0: a well-known test key, never funded on Polygon.
    private static final byte[] PRIVATE_KEY =
            HexFormat.of().parseHex("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String EOA = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    private static final BigInteger TOKEN_ID =
            new BigInteger("71321045679252212594626385532706912750332728571942532289631379312455583992563");
    private static final long SALT = 1234567890123L;
    private static final long TIMESTAMP_MILLIS = 1759350000000L;

    static Stream<Arguments> referenceVectors() {
        return Stream.of(
                Arguments.of(
                        false,
                        0,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        5500000L,
                        10000000L,
                        "0x153806a1f446e4deeaaee1413656868b8d276a54b0515129d1a6a1028864073d33ca07a983f9c6ededd6f3e2631784be9ac910707e307903523364f2432df40e1c",
                        "0xa8e3849c1d6063743907190a82b8fa4ce2db03b0d8fce4f5c83420e21a4f20df"),
                Arguments.of(
                        false,
                        0,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        5500000L,
                        10000000L,
                        "0x14e3dde0919d350a7a2b9cb5e31bb4af178d76f56f86ab375127265ae58ecbc76adc8f6b91840eca829379c92fb95009ca82749224bbbf80c953fdc83a0e0cd21c",
                        "0x009d43373ebe77f22a0b52baa29512be85ed84975873cf996abbd682a6b0110f"),
                Arguments.of(
                        false,
                        1,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        10000000L,
                        5500000L,
                        "0x4866643ad89eb9d523285e65753d251f92f76b84bc9c0f1a28465e5d85f7768223e3113cdc802cb367d18c5b8379308f57d91e20c68b64812b13758ae0dd469f1c",
                        "0xbf8dc320c5ab1ea65631b42b5c64f4171ae465b19a6d9b83ca116b62aa1fe2cf"),
                Arguments.of(
                        false,
                        1,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        10000000L,
                        5500000L,
                        "0x83d2ab8cea798beedcd73fa62003f0c095a4839b100900c08314016c2c3f27390430a0758abbc454fea9bb577a78c52815b32d77f5c2260ea8ae809ca734eacb1c",
                        "0x46e7c3050082c80781d98c039d4e9777241121e01f152d0b68d770bc35e27ba2"),
                Arguments.of(
                        true,
                        0,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        5500000L,
                        10000000L,
                        "0xf9d65c02a719aa4de9c68d6e635b59d02bac0e5510ad8901be237847cd9f96fa725837cc1819b210b4fe202e2e6122580fede0ab43372e2071812af6a83797a01c",
                        "0x18ba148d9459257e099b9917f4b511882dd65935d00c9b62654683abee984da2"),
                Arguments.of(
                        true,
                        0,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        5500000L,
                        10000000L,
                        "0xefd9a90d17bf8b5f330645f7b09d6e70b8ddbff2b579fea4db57a7678d0b786915270b086dfe50b78c9f9588dca0453f77e0a174bd85470d3fd2d621d39cc1eb1b",
                        "0xc16f8df0e1e15a49f9abccad43a953f23b933839c895b6e4d824ce576c03977b"),
                Arguments.of(
                        true,
                        1,
                        0,
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        10000000L,
                        5500000L,
                        "0x7f51f47b5bd317b6e6dabebb5d9766391b309811be18a08f460ca3d4234f63701c13c32143fbf58786c3f3fc20de9e24390a89db34ba65e911bc79cc46d7776c1b",
                        "0x24ea501d582d11c25b8dfd769354a5b01751eefd7f90d5a9c5cb4b019c8f17b1"),
                Arguments.of(
                        true,
                        1,
                        2,
                        "0x1111111111111111111111111111111111111111",
                        10000000L,
                        5500000L,
                        "0xdef59fe69c6a5f6aac4a98bf6d4ef604bdbfbd0341dfc6eeb34057bf3345039f512dc7274f5f32cd6f9c23f9b569142ea59ef856bc33264b66bfb9c9ddf292f91c",
                        "0xa1cdea334ac7b9174013f6d135d38ded8caba0dd2263fc778e0155abc984e91c"));
    }

    @ParameterizedTest
    @MethodSource("referenceVectors")
    void signatureAndOrderHashMatchReferenceClient(
            boolean negRisk,
            int side,
            int signatureType,
            String maker,
            long makerAmount,
            long takerAmount,
            String expectedSignature,
            String expectedOrderHash) {
        final PolymarketIntlOrderSigner signer =
                PolymarketIntlOrderSignerAccess.withSalt(PRIVATE_KEY, EOA, maker, signatureType, SALT);

        final PolymarketIntlOrderSigner.SignedOrder order =
                signer.signOrder(TOKEN_ID, makerAmount, takerAmount, side, TIMESTAMP_MILLIS, negRisk);

        assertEquals(expectedSignature, order.signatureHex());
        assertEquals(expectedOrderHash, orderHashHex(order));
        assertEquals(maker, order.maker());
        assertEquals(EOA, order.signer());
        assertEquals(signatureType, order.signatureType());
        assertEquals(SALT, order.salt());
    }

    // Type 3: the deposit wallet is maker and signer, and the key behind it signs a TypedDataSign wrapper of the order.
    private static final String DEPOSIT_WALLET = "0x2222222222222222222222222222222222222222";

    static Stream<Arguments> depositWalletVectors() {
        return Stream.of(
                Arguments.of(
                        false,
                        0,
                        5500000L,
                        10000000L,
                        "0x35e9229f5074ca20bc9dfed0bc35a83e492085bbe39e9292d8d0f146006583851f0a696d09c6ddb491d216ff97fe4d6d3e12976ba9f0c1998c3ba9ba04a7015e1b3264e159346253e26a64e00b69032db0e7d32f94628de3e6eecb50304d7af3d2c946808dc89f54ec1621cbb1f78f7f7417d7042c71403c99242a9b112a1529ff4f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba",
                        "0x48abb5edd42106b656b654ee08356a0f060ec29280b801412d5d5f636fa6d410"),
                Arguments.of(
                        false,
                        1,
                        10000000L,
                        5500000L,
                        "0xa6b53763cfe9ad2e9555845c7534856dffc6651d57e40c251e569e5a2914ea4e3539f32a84c5908e83965db6c883e473c6f3a69963eccbd8c1d0c8118a1a2ff21c3264e159346253e26a64e00b69032db0e7d32f94628de3e6eecb50304d7af3d29b10111e3aed180c306ba7a2959437df3ac193dcb7fb68c8cb2e5e7e7eb0f61f4f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba",
                        "0x65431f24d58f4d62625e943546e107941f2a22b9c63b0abe2638aafaea70fb3c"),
                Arguments.of(
                        true,
                        0,
                        5500000L,
                        10000000L,
                        "0x606e5d053a54cc7406fb49aa2d3dd8540c1ecaaa3dd78906ce511ced339070467357e37081625e2754e58889df5d59cb7e520cb7efe1aa414ad7ee04454405561c9b858f53327b0bd13af8ec14cfb35234fb9eb7b0504d1a4e61f433840d30e81ac946808dc89f54ec1621cbb1f78f7f7417d7042c71403c99242a9b112a1529ff4f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba",
                        "0x32412628546cee19af643b58fe832ee70246d7985bc38ef3486d1a840887ac95"),
                Arguments.of(
                        true,
                        1,
                        10000000L,
                        5500000L,
                        "0x7b93091dc26c1f9c2a308e802803eb85e898b1799f4cfde1c2ddc455e68dcd640b7ba9da142d7bb078fb6172b101c78286f30f35c758cff6fbf2b824a645ddeb1c9b858f53327b0bd13af8ec14cfb35234fb9eb7b0504d1a4e61f433840d30e81a9b10111e3aed180c306ba7a2959437df3ac193dcb7fb68c8cb2e5e7e7eb0f61f4f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba",
                        "0x8e4d5f7821ff79026614b7dcea54db4e5951add54bb9c3478c172aa0623ed764"));
    }

    @ParameterizedTest
    @MethodSource("depositWalletVectors")
    void depositWalletSignatureAndOrderHashMatchReferenceClient(
            boolean negRisk,
            int side,
            long makerAmount,
            long takerAmount,
            String expectedSignature,
            String expectedOrderHash) {
        final PolymarketIntlOrderSigner signer = PolymarketIntlOrderSignerAccess.withSalt(
                PRIVATE_KEY, EOA, DEPOSIT_WALLET, PolymarketIntlOrderSigner.SIGNATURE_TYPE_POLY_1271, SALT);

        final PolymarketIntlOrderSigner.SignedOrder order =
                signer.signOrder(TOKEN_ID, makerAmount, takerAmount, side, TIMESTAMP_MILLIS, negRisk);

        assertEquals(expectedSignature, order.signatureHex());
        assertEquals(expectedOrderHash, orderHashHex(order), "the venue's orderID is still the plain order hash");
        assertEquals(DEPOSIT_WALLET, order.maker());
        assertEquals(DEPOSIT_WALLET, order.signer());
        assertEquals(3, order.signatureType());
    }

    @Test
    void saltAdvancesPerOrderSoIdenticalOrdersHashDifferently() {
        final PolymarketIntlOrderSigner signer = PolymarketIntlOrderSignerAccess.withSalt(
                PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, SALT);

        final PolymarketIntlOrderSigner.SignedOrder first =
                signer.signOrder(TOKEN_ID, 5_500_000L, 10_000_000L, 0, TIMESTAMP_MILLIS, false);
        final PolymarketIntlOrderSigner.SignedOrder second =
                signer.signOrder(TOKEN_ID, 5_500_000L, 10_000_000L, 0, TIMESTAMP_MILLIS, false);

        assertEquals(SALT + 1, second.salt());
        assertEquals(false, orderHashHex(first).equals(orderHashHex(second)));
    }

    @Test
    void defaultSaltStaysExactAsAJsonNumber() {
        final PolymarketIntlOrderSigner signer =
                new PolymarketIntlOrderSigner(PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA);

        final long salt =
                signer.signOrder(TOKEN_ID, 1L, 1L, 0, TIMESTAMP_MILLIS, false).salt();

        assertEquals(true, salt >= 0 && salt < (1L << 53));
    }

    @Test
    void unsupportedSignatureTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PolymarketIntlOrderSigner(PRIVATE_KEY, EOA, EOA, 4));
    }

    @Test
    void orderHashHexIsWrittenAt66Bytes() {
        final PolymarketIntlOrderSigner.SignedOrder order = PolymarketIntlOrderSignerAccess.withSalt(
                        PRIVATE_KEY, EOA, EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, SALT)
                .signOrder(TOKEN_ID, 5_500_000L, 10_000_000L, 0, TIMESTAMP_MILLIS, false);
        final byte[] dest = new byte[70];

        final int written = order.writeOrderHashHex(dest, 2);

        assertEquals(66, written);
        assertArrayEquals(
                orderHashHex(order).getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOfRange(dest, 2, 68));
    }

    @Test
    void keccak256OfEmptyInput() {
        assertEquals(
                "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                HexFormat.of().formatHex(PolymarketIntlOrderSigner.keccak256(new byte[0])));
    }

    @Test
    void keccak256OfKnownString() {
        assertEquals(
                "1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8",
                HexFormat.of()
                        .formatHex(PolymarketIntlOrderSigner.keccak256("hello".getBytes(StandardCharsets.UTF_8))));
    }

    private static String orderHashHex(final PolymarketIntlOrderSigner.SignedOrder order) {
        final byte[] buf = new byte[66];
        order.writeOrderHashHex(buf, 0);
        return new String(buf, StandardCharsets.US_ASCII);
    }
}
