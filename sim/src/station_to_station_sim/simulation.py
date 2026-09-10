"""Replay an immutable encounter trace without sharing inventories between phones.

Resource estimates are explicit inputs, not measurements of a real BLE radio.
A trace records successful directional opportunities; platform availability belongs
in trace generation, so every policy sees the same opportunities.
"""
from dataclasses import dataclass, field
from datetime import datetime
from random import Random

from .policies import USEFULNESS_SECONDS, is_useful


@dataclass(frozen=True)
class Fact:
    id: str
    author: str
    created: int
    expires: int
    bytes: int = 600


@dataclass(frozen=True)
class Encounter:
    at: int
    sender: str
    receiver: str
    bytes: int = 4000


@dataclass(frozen=True)
class Config:
    users: tuple[str, ...]
    facts: tuple[Fact, ...]
    encounters: tuple[Encounter, ...]
    contacts: tuple[tuple[str, str], ...] = ()
    carry_seconds: int = 900
    max_held: int = 128
    max_bytes: int = 128_000
    fanout: int = 4
    connection_j: float = 0.03
    byte_j: float = 0.00001
    # None means an unlimited relay chain, which is what every earlier run assumed.
    max_hops: int | None = None
    # A witness receipt travels back to the author under its own hop budget. One
    # hop is the spec's provisional answer and means "only if you meet the author".
    receipt_max_hops: int = 1
    receipt_bytes: int = 120
    usefulness_seconds: int = USEFULNESS_SECONDS


@dataclass(eq=True)
class Result:
    received: dict[str, dict[str, int]]
    transmissions: int = 0
    duplicates: int = 0
    useful_deliveries: int = 0
    bytes: int = 0
    connections: int = 0
    energy_j: float = 0
    evictions: int = 0
    peak_outbox_bytes: int = 0
    # Hop 0 is the author's own copy, so a stranger's relay is hop 2 or more.
    hops: dict[str, dict[str, int]] = field(default_factory=dict)
    forwards_refused_by_hop_limit: int = 0
    receipts_generated: int = 0
    receipts_delivered: int = 0
    receipt_bytes: int = 0
    receipt_transmissions: int = 0
    duplicate_receipts: int = 0


def run_simulation(config: Config, policy: str, rng_seed: int, start_time: datetime) -> Result:
    if policy not in {"contact", "epidemic", "controlled", "spray", "focus", "adaptive"}:
        raise ValueError("unknown policy")
    if start_time.tzinfo is None or config.carry_seconds <= 0:
        raise ValueError("an aware start time and positive Carry window are required")
    if len(set(config.users)) != len(config.users):
        raise ValueError("duplicate user")
    if config.max_hops is not None and config.max_hops < 1:
        raise ValueError("a positive hop limit or None is required")
    if config.receipt_max_hops < 1 or config.receipt_bytes < 0:
        raise ValueError("a receipt needs at least one hop and a non-negative size")
    rng = Random(rng_seed)
    result = Result({u: {} for u in config.users})
    result.hops = {u: {} for u in config.users}
    # A witness receipt is addressed to one author and dies when it arrives.
    receipts = {u: {} for u in config.users}
    receipts_home = set()
    held = {u: {} for u in config.users}
    sent = {u: set() for u in config.users}
    contacts = {frozenset(pair) for pair in config.contacts}
    useful = {}
    copies = {}
    facts = {f.id: f for f in config.facts}
    if len(facts) != len(config.facts) or any(f.author not in held or f.expires <= f.created for f in facts.values()):
        raise ValueError("invalid Fact workload")
    authored = set()
    for encounter in sorted(config.encounters, key=lambda e: e.at):
        now, sender, receiver = encounter.at, encounter.sender, encounter.receiver
        if sender not in held or receiver not in held or sender == receiver:
            raise ValueError("invalid encounter")
        for fact in config.facts:
            if fact.created <= now and fact.id not in authored:
                authored.add(fact.id)
                held[fact.author][fact.id] = fact.created
                result.received[fact.author][fact.id] = fact.created
                result.hops[fact.author][fact.id] = 0
                copies[fact.author, fact.id] = config.fanout
        for user in config.users:
            for fid, arrival in list(held[user].items()):
                if now >= min(facts[fid].expires, arrival + config.carry_seconds):
                    del held[user][fid]
                    result.evictions += 1
        # The sender sees its own outbox and completed handoffs only.
        candidates = [fid for fid in held[sender] if (fid, receiver) not in sent[sender]]
        rng.shuffle(candidates)
        used = 0
        for fid in candidates:
            fact = facts[fid]
            if policy == "contact" and (frozenset((sender, receiver)) not in contacts or
                                        frozenset((fact.author, receiver)) not in contacts):
                continue
            budget = copies.get((sender, fid), 1)
            if policy in {"spray", "focus"} and budget <= 1:
                if not (policy == "focus" and
                        is_useful(now, useful.get(receiver), config.usefulness_seconds, rng)):
                    continue
            if policy == "controlled" and rng.random() < 0.5:
                continue
            if policy == "adaptive" and len([p for f, p in sent[sender] if f == fid]) >= config.fanout:
                continue
            if config.max_hops is not None and result.hops[sender].get(fid, 0) >= config.max_hops:
                result.forwards_refused_by_hop_limit += 1
                continue
            if used + fact.bytes > encounter.bytes:
                continue
            used += fact.bytes
            sent[sender].add((fid, receiver))
            result.transmissions += 1
            result.bytes += fact.bytes
            if fid in result.received[receiver]:
                result.duplicates += 1
                held[receiver].pop(fid, None)
                continue
            result.received[receiver][fid] = now
            held[receiver][fid] = now
            result.hops[receiver][fid] = result.hops[sender].get(fid, 0) + 1
            if policy in {"spray", "focus"}:
                transferred = max(1, budget // 2)
                copies[sender, fid] = budget - transferred
                copies[receiver, fid] = transferred
            if frozenset((fact.author, receiver)) in contacts:
                useful[sender] = now
                result.useful_deliveries += 1
                # The witness owes the author a receipt. Getting it home is a
                # separate delivery problem, and the point of the hop sweep.
                rid = (fid, receiver)
                if rid not in receipts_home and rid not in receipts[receiver]:
                    receipts[receiver][rid] = 0
                    result.receipts_generated += 1
            while len(held[receiver]) > config.max_held or sum(facts[f].bytes for f in held[receiver]) > config.max_bytes:
                oldest = min(held[receiver], key=held[receiver].get)
                del held[receiver][oldest]
                result.evictions += 1
            result.peak_outbox_bytes = max(result.peak_outbox_bytes, sum(facts[f].bytes for f in held[receiver]))
        # Receipts ride the connection the facts opened, or open one themselves.
        for rid in sorted(receipts[sender], key=lambda r: (r[0], r[1])):
            hops = receipts[sender][rid]
            author = facts[rid[0]].author
            arriving = receiver == author
            if not arriving and hops + 1 >= config.receipt_max_hops:
                continue
            if not arriving and (rid in receipts[receiver] or receiver == rid[1]):
                continue
            if used + config.receipt_bytes > encounter.bytes:
                continue
            used += config.receipt_bytes
            result.bytes += config.receipt_bytes
            result.receipt_bytes += config.receipt_bytes
            result.receipt_transmissions += 1
            if arriving:
                # It is home; nobody needs to carry it any further.
                del receipts[sender][rid]
                if rid not in receipts_home:
                    receipts_home.add(rid)
                    result.receipts_delivered += 1
                else:
                    # Copies keep arriving after the first. The author learns
                    # nothing new; the bytes were still spent.
                    result.duplicate_receipts += 1
            else:
                # A receipt spreads like any other blind-relay message: the sender
                # keeps its copy. Modelling this as a hand-off instead makes a
                # receipt a random walk, and hides whatever extra hops would buy.
                receipts[receiver][rid] = hops + 1
        if used:
            result.connections += 1
            result.energy_j += config.connection_j + used * config.byte_j
    return result
