"""Regenerates the Polymarket CLOB V2 reference vectors used by PolymarketIntlOrderSignerTest.

Signs fixed inputs with Polymarket's official client, so the Java signer is checked against it byte
for byte. Setup:

    git clone https://github.com/Polymarket/py-clob-client-v2
    python3 -m venv venv && venv/bin/pip install -e ./py-clob-client-v2
    venv/bin/python scripts/pm_v2_vectors.py
"""
import json
from py_clob_client_v2.signer import Signer
from py_clob_client_v2.order_utils.exchange_order_builder_v2 import ExchangeOrderBuilderV2
from py_clob_client_v2.order_utils.model.order_data_v2 import OrderDataV2, order_to_json_v2
from py_clob_client_v2.order_utils.model.side import Side
from py_clob_client_v2.config import get_contract_config

KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"  # Hardhat account #0
SAFE = "0x1111111111111111111111111111111111111111"
TOKEN = "71321045679252212594626385532706912750332728571942532289631379312455583992563"
signer = Signer(private_key=KEY, chain_id=137)
cfg = get_contract_config(137)

cases = []
for neg_risk in (False, True):
    for side in (Side.BUY, Side.SELL):
        for sig_type in (0, 2):
            maker = signer.address() if sig_type == 0 else SAFE
            maker_amt, taker_amt = ("5500000", "10000000") if side == Side.BUY else ("10000000", "5500000")
            contract = cfg.neg_risk_exchange_v2 if neg_risk else cfg.exchange_v2
            b = ExchangeOrderBuilderV2(contract, 137, signer, generate_salt=lambda: "1234567890123")
            order = b.build_signed_order(OrderDataV2(
                maker=maker, tokenId=TOKEN, makerAmount=maker_amt, takerAmount=taker_amt, side=side,
                signer=signer.address(), signatureType=sig_type, timestamp="1759350000000"))
            typed = b.build_order_typed_data(b.build_order(OrderDataV2(
                maker=maker, tokenId=TOKEN, makerAmount=maker_amt, takerAmount=taker_amt, side=side,
                signer=signer.address(), signatureType=sig_type, timestamp="1759350000000")))
            body = order_to_json_v2(order, "00000000-1111-2222-3333-444444444444", "GTC", post_only=False)
            cases.append({
                "negRisk": neg_risk, "side": int(side), "signatureType": sig_type, "maker": maker,
                "signer": signer.address(), "makerAmount": maker_amt, "takerAmount": taker_amt,
                "signature": order.signature, "orderHash": b.build_order_hash(typed),
                "body": json.dumps(body, separators=(",", ":")),
            })
print(json.dumps({"signer": signer.address(), "token": TOKEN, "salt": "1234567890123",
                  "timestamp": "1759350000000", "cases": cases}, indent=1))
