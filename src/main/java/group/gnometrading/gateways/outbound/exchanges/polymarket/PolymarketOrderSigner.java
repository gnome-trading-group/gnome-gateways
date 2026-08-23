package group.gnometrading.gateways.outbound.exchanges.polymarket;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

public final class PolymarketOrderSigner {

    // Polymarket CTF Exchange on Polygon mainnet.
    // IMPORTANT: verify this address against https://docs.polymarket.com before deploying.
    private static final String CTF_EXCHANGE_ADDRESS = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";

    private static final long CHAIN_ID = 137L; // Polygon mainnet

    private static final String DOMAIN_TYPE =
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";

    private static final String ORDER_TYPE =
            "Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,"
                    + "uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,"
                    + "uint256 feeRateBps,uint8 side,uint8 signatureType)";

    private static final byte[] DOMAIN_TYPE_HASH = keccak256(DOMAIN_TYPE.getBytes(StandardCharsets.UTF_8));
    private static final byte[] ORDER_TYPE_HASH = keccak256(ORDER_TYPE.getBytes(StandardCharsets.UTF_8));

    private static final byte[] DOMAIN_SEPARATOR;

    static {
        final byte[] nameHash = keccak256("Polymarket CTF Exchange".getBytes(StandardCharsets.UTF_8));
        final byte[] versionHash = keccak256("1".getBytes(StandardCharsets.UTF_8));
        final byte[] contractBytes = hexToBytes(CTF_EXCHANGE_ADDRESS.substring(2));
        final byte[] encoded = new byte[5 * 32];
        System.arraycopy(DOMAIN_TYPE_HASH, 0, encoded, 0, 32);
        System.arraycopy(nameHash, 0, encoded, 32, 32);
        System.arraycopy(versionHash, 0, encoded, 64, 32);
        toUint256Bytes(CHAIN_ID, encoded, 96);
        System.arraycopy(contractBytes, 0, encoded, 12 + 128, 20);
        DOMAIN_SEPARATOR = keccak256(encoded);
    }

    private final ECDSASigner signer;
    private final ECPrivateKeyParameters privateKeyParams;
    private final String makerAddress;
    private final String signerAddress;
    private final byte[] signerAddrBytes; // pre-computed 20 raw bytes, shared for maker == signer (EOA)
    private final BigInteger halfN; // N/2, pre-computed for low-s canonicalization
    private long saltCounter;

    // Reusable scratch buffers — single-threaded writer agent, safe to reuse
    private final KeccakDigest keccakDigest = new KeccakDigest(256);
    private final byte[] structEncoded = new byte[13 * 32];
    private final byte[] messageBuf = new byte[2 + 32 + 32]; // \x19\x01 || domainSep || structHash
    private final byte[] structHashBuf = new byte[32];
    private final byte[] msgHashBuf = new byte[32];
    // recoverV / decompressPoint scratch
    private final byte[] pubKeyBuf = new byte[64];
    private final byte[] addrHashBuf = new byte[32];
    private final byte[] fixedXBuf = new byte[32];
    private final byte[] compressedPointBuf = new byte[33];

    public PolymarketOrderSigner(final byte[] privateKeyBytes, final String signerAddress) {
        this.signerAddress = signerAddress;
        this.makerAddress = signerAddress; // maker = signer for EOA orders
        this.saltCounter = System.nanoTime();

        final ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        final ECDomainParameters domainParams =
                new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
        this.privateKeyParams = new ECPrivateKeyParameters(new BigInteger(1, privateKeyBytes), domainParams);
        this.signer = new ECDSASigner();
        this.halfN = domainParams.getN().shiftRight(1);

        final String addrHex = signerAddress.startsWith("0x") ? signerAddress.substring(2) : signerAddress;
        this.signerAddrBytes = hexToBytes(addrHex);
    }

    public SignedOrder signOrder(
            final BigInteger tokenId,
            final long makerAmount,
            final long takerAmount,
            final int side,
            final long feeRateBps) {
        final long salt = this.saltCounter++;

        // Zero entire struct buffer — address leading bytes and taker slot are otherwise stale
        Arrays.fill(this.structEncoded, (byte) 0);

        int offset = 0;
        System.arraycopy(ORDER_TYPE_HASH, 0, this.structEncoded, offset, 32);
        offset += 32;
        toUint256Bytes(salt, this.structEncoded, offset);
        offset += 32;
        copyAddressToSlot(this.signerAddrBytes, this.structEncoded, offset); // maker
        offset += 32;
        copyAddressToSlot(this.signerAddrBytes, this.structEncoded, offset); // signer
        offset += 32;
        offset += 32; // taker = address(0), already zero
        toUint256Bytes(tokenId, this.structEncoded, offset);
        offset += 32;
        toUint256Bytes(makerAmount, this.structEncoded, offset);
        offset += 32;
        toUint256Bytes(takerAmount, this.structEncoded, offset);
        offset += 32;
        offset += 32; // expiration = 0, already zero
        offset += 32; // nonce = 0, already zero
        toUint256Bytes(feeRateBps, this.structEncoded, offset);
        offset += 32;
        toUint256Bytes(side, this.structEncoded, offset);
        offset += 32;
        // signatureType = 0, already zero

        hashKeccak256(this.structEncoded, this.structEncoded.length, this.structHashBuf);

        this.messageBuf[0] = 0x19;
        this.messageBuf[1] = 0x01;
        System.arraycopy(DOMAIN_SEPARATOR, 0, this.messageBuf, 2, 32);
        System.arraycopy(this.structHashBuf, 0, this.messageBuf, 34, 32);
        hashKeccak256(this.messageBuf, this.messageBuf.length, this.msgHashBuf);

        this.signer.init(true, this.privateKeyParams);
        final BigInteger[] sig = this.signer.generateSignature(this.msgHashBuf);
        final BigInteger r = sig[0];
        BigInteger sigS = sig[1];

        // Enforce low-s per EIP-2 / BIP-62 — required by Polygon validators
        if (sigS.compareTo(this.halfN) > 0) {
            sigS = this.privateKeyParams.getParameters().getN().subtract(sigS);
        }

        final byte v = recoverV(r, sigS);

        return new SignedOrder(
                salt,
                this.makerAddress,
                this.signerAddress,
                tokenId,
                makerAmount,
                takerAmount,
                feeRateBps,
                side,
                r,
                sigS,
                v);
    }

    private byte recoverV(final BigInteger sigR, final BigInteger sigS) {
        final ECDomainParameters params = this.privateKeyParams.getParameters();
        final BigInteger n = params.getN();
        final ECCurve curve = params.getCurve();
        final BigInteger e = new BigInteger(1, this.msgHashBuf);
        final BigInteger rInv = sigR.modInverse(n);

        for (int recId = 0; recId < 2; recId++) {
            final ECPoint pointR = decompressPoint(curve, sigR, recId);
            if (pointR == null || !pointR.multiply(n).isInfinity()) {
                continue;
            }

            // Q = r^{-1} * (s*R - e*G)
            final ECPoint sR = pointR.multiply(sigS).normalize();
            final ECPoint eG = params.getG().multiply(e).normalize();
            final ECPoint candidate = sR.subtract(eG).multiply(rInv).normalize();

            final byte[] pubEncoded = candidate.getEncoded(false); // 0x04 || x(32) || y(32)
            System.arraycopy(pubEncoded, 1, this.pubKeyBuf, 0, 64);
            hashKeccak256(this.pubKeyBuf, this.pubKeyBuf.length, this.addrHashBuf);

            // Compare raw address bytes directly against pre-computed signer bytes
            boolean match = true;
            for (int i = 0; i < 20; i++) {
                if (this.addrHashBuf[12 + i] != this.signerAddrBytes[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return (byte) (27 + recId);
            }
        }
        throw new IllegalStateException("recoverV: no recId matched signer address " + this.signerAddress);
    }

    private ECPoint decompressPoint(final ECCurve curve, final BigInteger xCoord, final int recId) {
        final byte[] xBytes = xCoord.toByteArray();
        Arrays.fill(this.fixedXBuf, (byte) 0);
        if (xBytes.length <= 32) {
            System.arraycopy(xBytes, 0, this.fixedXBuf, 32 - xBytes.length, xBytes.length);
        } else {
            System.arraycopy(xBytes, xBytes.length - 32, this.fixedXBuf, 0, 32);
        }
        this.compressedPointBuf[0] = (byte) (0x02 | (recId & 1));
        System.arraycopy(this.fixedXBuf, 0, this.compressedPointBuf, 1, 32);
        try {
            return curve.decodePoint(this.compressedPointBuf);
        } catch (final Exception ex) {
            return null;
        }
    }

    private void hashKeccak256(final byte[] input, final int length, final byte[] output) {
        this.keccakDigest.reset();
        this.keccakDigest.update(input, 0, length);
        this.keccakDigest.doFinal(output, 0);
    }

    // Kept static for use in the static initializer and tests
    public static byte[] keccak256(final byte[] input) {
        final KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        final byte[] output = new byte[32];
        digest.doFinal(output, 0);
        return output;
    }

    private static void toUint256Bytes(final long value, final byte[] dest, final int offset) {
        for (int i = 0; i < 24; i++) {
            dest[offset + i] = 0;
        }
        dest[offset + 24] = (byte) (value >>> 56);
        dest[offset + 25] = (byte) (value >>> 48);
        dest[offset + 26] = (byte) (value >>> 40);
        dest[offset + 27] = (byte) (value >>> 32);
        dest[offset + 28] = (byte) (value >>> 24);
        dest[offset + 29] = (byte) (value >>> 16);
        dest[offset + 30] = (byte) (value >>> 8);
        dest[offset + 31] = (byte) value;
    }

    private static void toUint256Bytes(final BigInteger value, final byte[] dest, final int offset) {
        final byte[] raw = value.toByteArray();
        for (int i = 0; i < 32; i++) {
            dest[offset + i] = 0;
        }
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, dest, offset + 32 - raw.length, raw.length);
        } else {
            // Strip BigInteger sign-padding byte when value exactly fills 32 bytes
            System.arraycopy(raw, raw.length - 32, dest, offset, 32);
        }
    }

    // Copies 20-byte pre-computed address into a 32-byte ABI-encoded slot.
    // Leading 12 bytes must already be zero (guaranteed by Arrays.fill at top of signOrder).
    private static void copyAddressToSlot(final byte[] addrBytes, final byte[] dest, final int offset) {
        System.arraycopy(addrBytes, 0, dest, offset + 12, 20);
    }

    private static byte[] hexToBytes(final String hex) {
        final int len = hex.length();
        final byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    public record SignedOrder(
            long salt,
            String maker,
            String signer,
            BigInteger tokenId,
            long makerAmount,
            long takerAmount,
            long feeRateBps,
            int side,
            BigInteger r,
            BigInteger s,
            byte v) {

        private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

        public String signatureHex() {
            final byte[] rBytes = toFixed32(r);
            final byte[] sBytes = toFixed32(s);
            final StringBuilder sb = new StringBuilder(132);
            sb.append("0x");
            appendHex(sb, rBytes);
            appendHex(sb, sBytes);
            final int vInt = v & 0xFF;
            sb.append(HEX_CHARS[vInt >> 4]);
            sb.append(HEX_CHARS[vInt & 0xF]);
            return sb.toString();
        }

        private static byte[] toFixed32(final BigInteger value) {
            final byte[] bytes = value.toByteArray();
            final byte[] result = new byte[32];
            if (bytes.length <= 32) {
                System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length);
            } else {
                // Skip leading zero byte from BigInteger sign padding
                System.arraycopy(bytes, bytes.length - 32, result, 0, 32);
            }
            return result;
        }

        private static void appendHex(final StringBuilder sb, final byte[] bytes) {
            for (final byte b : bytes) {
                sb.append(HEX_CHARS[(b >> 4) & 0xF]);
                sb.append(HEX_CHARS[b & 0xF]);
            }
        }
    }
}
