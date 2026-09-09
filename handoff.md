Implement a Python simulator for the `magnus-encoded/station-to-station` gossip transport design. This is exploratory/simulation code, not production mobile code.

## Goal

Model a blind-relay gossip network for music events.

Premises:

* Any STS user may act as a blind relay for gossip.
* Blind relays are not contact-gated and do not attest to message truth.
* Social/contact relationships matter only when application semantics require them, e.g. exposing a friend's gossip or issuing a witness receipt.
* BLE range is short, so blind relays bridge physically separated social clusters.
* iOS participation is constrained by foreground/background limitations and should be configurable.
* Gossip is broader than check-ins:

  * check-in claims,
  * witness receipts,
  * log observations,
  * log corrections,
  * setlist resolution,
  * arrival observations.
* Gossip objects are immutable signed events with expiry/provenance.
* Application state converges from events. Routing and merge semantics are separate concerns.
* Alice at time `t1` and Alice at `t2` may have very different information to offer. Forwarding utility must therefore depend on current inventory delta, not static peer reputation.
* Duplicate events should be deduplicated globally by event ID.
* Distinct observations about the same logical log item should coexist and later consolidate.
* Example:

  * Alice writes `Karma Police`
  * Bob writes `For a minute there I lost myself`
  * Alice later sees both observations associated with the same logical song position, with Bob's contribution retaining Bob provenance/colour.
  * Identical observations should collapse rather than render twice.

## Research context

Use these only as conceptual guidance; do not over-engineer toward academic fidelity:

* Integrated Routing Protocol for Opportunistic Networks
* Social-aware Opportunistic Routing: The New Trend
* Opportunistic Delay Tolerant Routing for LED Wristbands in Music Events
* The heterogeneity of inter-contact time distributions
* bitchat whitepaper

The simulator should make routing policy replaceable so we can compare:

1. Epidemic
2. Epidemic + jitter
3. Adaptive fanout
4. Spray-N / Spray-and-Wait
5. Spray-and-Focus
6. Later: encounter-aware/adaptive spray

Do not implement PRoPHET unless it falls out naturally after the baseline exists.

## Architecture

Use a discrete-event or timestep simulator with these major layers:

```text
venue + mobility
        ↓
physical BLE encounters
        ↓
information inventories
        ↓
relay policy
        ↓
resource accounting
        ↓
application merge / witness semantics
        ↓
metrics
```

Keep routing separate from application semantics.

## Required dataclasses

Create dataclasses equivalent to the following concepts.

### Population/device

* `Platform`

  * IOS
  * ANDROID
* `MobilityClass`

  * STATIONARY_GROUP
  * CONCERT_WATCHER
  * WANDERER
  * BAR_HOPPER
  * TOILET_RUNNER
  * SOCIAL_BUTTERFLY
* `DeviceProfile`

  * platform
  * foreground_probability
  * relay_enabled
  * receive_enabled
  * battery_capacity_j
* `Position`

  * x
  * y
  * zone
* `User`

  * id
  * device
  * mobility_class
  * position
  * velocity_x
  * velocity_y
  * battery_j
  * contacts
* `Population`

  * users

### Venue/mobility

* `VenueZone`

  * id
  * x_min
  * x_max
  * y_min
  * y_max
* `Venue`

  * zones
  * width_m
  * height_m
* `MobilityState`

  * user_id
  * position
  * velocity_x
  * velocity_y
  * destination_zone
* `MobilityModelConfig`

  * speed_mps by mobility class
  * zone_transition_probability
  * pause_probability

### Social graph

* `SocialGraphConfig`

  * cluster_count
  * cluster_size
  * friend_degree_distribution
  * cross_cluster_edge_probability
  * social_physical_correlation
* `SocialGraph`

  * contacts

The social graph and physical encounter graph must be independent.

### BLE

* `BLEConfig`

  * range_m
  * advertisement_interval
  * scan_interval
  * connection_setup_time
  * connection_probability
  * max_simultaneous_connections
* `Encounter`

  * id
  * a
  * b
  * started_at
  * ended_at
  * bytes_possible

### Gossip events

* `EventType`

  * CHECK_IN_CLAIM
  * WITNESS_RECEIPT
  * LOG_OBSERVATION
  * LOG_CORRECTION
  * SETLIST_RESOLUTION
  * ARRIVAL_OBSERVATION
* `GossipEvent`

  * id
  * gig_id
  * author_id
  * created_at
  * expires_at
  * event_type
  * payload
  * merge_key
  * priority
* `EventStreamConfig`

  * generation_rate_per_minute
  * payload_size_bytes
  * ttl

### Information state

* `InformationState`

  * events
  * `contains(event_id)`
  * `add(event)`
* `Inventory`

  * event_ids
* `InformationDelta`

  * events

The key quantity is:

```text
delta(a, b, t) = information held by A at time t
                 minus information held by B at time t
```

Forwarding utility must be based on current state, not merely whether A and B met before.

### Encounter history

* `EncounterStats`

  * encounter_count
  * total_contact_duration
  * last_encounter_at
  * derived `encounter_rate_per_hour`
* `EncounterHistory`

  * by_peer

### Resource limits/state

* `ResourceConfig`

  * scan_energy_j
  * advertise_energy_j
  * connection_energy_j
  * receive_energy_per_byte_j
  * transmit_energy_per_byte_j
  * crypto_verification_energy_j
  * storage_energy_per_byte_j
* `GossipLimits`

  * max_relay_hops
  * max_held_events
  * max_held_bytes
  * max_events_per_transfer
  * max_bytes_per_transfer
  * peer_cooldown
  * relay_jitter
* `ResourceState`

  * battery_j
  * bytes_stored
  * bytes_transmitted
  * bytes_received
  * transmissions
  * receptions
  * signature_verifications

### Held gossip

* `HeldEvent`

  * event
  * hop_count
  * received_at
  * forwarded_to
* `GossipState`

  * information
  * held
  * last_handover_at

### Witnessing

* `WitnessReceipt`

  * id
  * claim_id
  * witness_id
  * witnessed_at
  * expires_at
  * signature

Semantics:

* A check-in is a signed claim: “Alice says she is here and requests a receipt.”
* A witness receipt is stronger evidence because another user signs that they observed the claim.
* Blind relays never become witnesses merely by forwarding bytes.
* Contacts/application semantics determine who may interpret and attest.

### Log model

* `LogObservation`

  * id
  * gig_id
  * author_id
  * observed_at
  * position_hint
  * text
  * parent_event_id
* `LogEntry`

  * key
  * observations
* `ConsolidatedLog`

  * entries

Do not model the shared log as a mutable remote document. Model it as convergence over immutable observations/events.

### Relay policy

* `RelayContext`

  * now
  * sender_id
  * receiver_id
  * local_inventory
  * peer_inventory
  * candidate_events
  * local_density
  * peer_encounter_stats
  * resource_state
  * limits
* `ForwardingPlan`

  * events
  * retain
  * relay_targets
* `RelayPolicy` protocol with:

  * `plan(context) -> ForwardingPlan`

Implement policy classes/stubs for:

* `EpidemicPolicy`
* `ControlledEpidemicPolicy`
* `SprayAndWaitPolicy`
* `SprayAndFocusPolicy`
* `AdaptiveFanoutPolicy`

`AdaptiveFanoutConfig` should include:

* min_fanout
* max_fanout
* density_weight
* overlap_weight
* encounter_rate_weight
* expiry_weight
* cost_weight

The useful generalized concept is similar to the current STS storm gate, but distinguish:

* hard safety/admission rules,
* heuristic forwarding utility.

Safety rules must remain explicit and deterministic.

## Core function signatures

Implement or stub these functions with clear docstrings and tests:

```python
def build_population(
    config: PopulationConfig,
    venue: Venue,
    rng_seed: int,
) -> Population:
    ...


def build_social_graph(
    population: Population,
    config: SocialGraphConfig,
    rng_seed: int,
) -> SocialGraph:
    ...


def initialize_simulation(
    config: SimulationConfig,
    rng_seed: int,
    start_time: datetime,
) -> SimulationState:
    ...


def generate_mobility_step(
    user: User,
    state: MobilityState,
    venue: Venue,
    config: MobilityModelConfig,
    timestep: timedelta,
    rng_seed: int,
) -> MobilityState:
    ...


def update_mobility(
    state: SimulationState,
    config: SimulationConfig,
    rng_seed: int,
) -> None:
    ...


def detect_encounters(
    state: SimulationState,
    ble: BLEConfig,
) -> tuple[Encounter, ...]:
    ...


def apply_encounter(
    state: SimulationState,
    encounter: Encounter,
) -> None:
    ...


def generate_events(
    state: SimulationState,
    config: EventStreamConfig,
    rng_seed: int,
) -> tuple[GossipEvent, ...]:
    ...


def inventory(
    information: InformationState,
) -> Inventory:
    ...


def calculate_delta(
    local: InformationState,
    peer: Inventory,
) -> InformationDelta:
    ...


def calculate_local_density(
    user_id: str,
    encounters: Sequence[Encounter],
) -> int:
    ...


def record_encounter(
    history: EncounterHistory,
    peer_id: str,
    encounter: Encounter,
) -> None:
    ...


def build_forwarding_context(
    state: SimulationState,
    encounter: Encounter,
    sender_id: str,
    receiver_id: str,
    candidate_events: Sequence[GossipEvent],
) -> RelayContext:
    ...


def choose_forwarding_plan(
    policy: RelayPolicy,
    context: RelayContext,
) -> ForwardingPlan:
    ...


def execute_forwarding_plan(
    state: SimulationState,
    sender_id: str,
    receiver_id: str,
    plan: ForwardingPlan,
) -> None:
    ...


def expire_events(
    state: SimulationState,
) -> None:
    ...


def merge_log_observations(
    observations: Sequence[LogObservation],
) -> ConsolidatedLog:
    ...


def create_witness_receipt(
    claim: GossipEvent,
    witness_id: str,
    witnessed_at: datetime,
    expires_at: datetime,
    signature: bytes,
) -> WitnessReceipt:
    ...


def apply_event_to_application(
    user: User,
    event: GossipEvent,
) -> None:
    ...


def calculate_event_metrics(
    state: SimulationState,
    previous: SimulationMetrics | None,
) -> SimulationMetrics:
    ...


def calculate_gig_metrics(
    state: SimulationState,
    metrics: SimulationMetrics,
) -> GigMetrics:
    ...


def run_simulation(
    config: SimulationConfig,
    policy: RelayPolicy,
    rng_seed: int,
    start_time: datetime,
) -> tuple[SimulationState, SimulationMetrics, GigMetrics]:
    ...
```

## Simulation config/state dataclasses

Include:

* `PopulationConfig`

  * users
  * sts_adoption
  * ios_fraction
* `SimulationConfig`

  * duration
  * timestep
  * population
  * venue
  * mobility
  * social
  * ble
  * events
  * resources
  * limits
* `SimulationState`

  * now
  * population
  * mobility
  * social_graph
  * encounters
  * gossip
  * encounter_history
  * resources

## Metrics

Include:

* `EventMetrics`

  * created_at
  * first_seen_at
  * first_receipt_at
  * unique_nodes_seen
  * copies
  * transmissions
  * duplicate_transmissions
  * bytes_transmitted
  * expired
* `SimulationMetrics`

  * events
  * `event_coverage(event_id)`
* `GigMetrics`

  * coverage_percent
  * median_convergence_time
  * p95_convergence_time
  * receipt_probability
  * mean_receipts_per_user
  * mean_battery_cost_j
  * bytes_per_user
  * connections_per_user

Primary product metrics should include:

* time to first witness receipt,
* probability of at least one receipt,
* probability of multiple receipts,
* per-event convergence time,
* 50/90/95/99% coverage times,
* duplicate transmissions,
* bytes transmitted,
* connections,
* battery cost.

## Important simulation parameters

The simulator must allow varying at least:

* number of venue attendees,
* STS adoption rate,
* iOS fraction,
* foreground probability,
* BLE range,
* connection probability,
* crowd density,
* venue geometry/zones,
* mobility classes,
* movement between venue zones,
* social cluster structure,
* social/physical correlation,
* event generation rate,
* event size,
* event TTL,
* hop limit,
* storage limit,
* transfer batch limit,
* relay cooldown,
* jitter,
* fixed copy budget,
* adaptive fanout weights.

## Tests

Use `pytest`.

Write one meaningful test for each simulation function above, plus one for each relay policy.

Tests should assert behaviour, not implementation details.

At minimum cover:

* population size/platform mix,
* graph construction,
* deterministic seeded setup,
* mobility staying inside venue,
* encounter detection inside/outside BLE range,
* encounter-history updates,
* inventory construction,
* delta correctness,
* deduplication,
* expiry,
* transfer limits,
* blind relay between non-contacts,
* application access remaining contact-gated,
* witness receipt construction,
* duplicate log observations consolidating,
* distinct same-position log observations coexisting,
* epidemic forwarding all missing eligible events,
* Spray-N respecting copy budget,
* adaptive fanout decreasing with high density/overlap,
* forwarding utility changing when Alice acquires new events between `t1` and `t2`,
* metrics calculation,
* end-to-end deterministic simulation under a fixed seed.

Function-test names should roughly follow:

```python
def test_build_population() -> None: ...
def test_build_social_graph() -> None: ...
...
def test_run_simulation() -> None: ...

def test_epidemic_policy() -> None: ...
def test_controlled_epidemic_policy() -> None: ...
def test_spray_and_wait_policy() -> None: ...
def test_spray_and_focus_policy() -> None: ...
def test_adaptive_fanout_policy() -> None: ...
```

## Implementation priorities

Work in this order:

1. Dataclasses/enums/protocols.
2. Inventory/delta/deduplication/expiry.
3. Basic population + venue + encounter model.
4. Epidemic policy.
5. End-to-end deterministic simulation.
6. Metrics.
7. Controlled epidemic + jitter.
8. Spray-N.
9. Adaptive fanout.
10. Log consolidation and witness semantics if not already covered.
11. Only after that, richer mobility or encounter-aware policies.

Do not prematurely optimize.

Prefer readable Python and native Python structures. Avoid redundant comments that merely restate identifiers. Handle failure/invalid states early. Keep functions small enough that each can be tested in isolation.

The first useful milestone is not a realistic festival simulation. It is:

> Given a fixed synthetic venue and mobility trace, the same evolving gossip workload can be replayed through several relay policies and compared on convergence, receipts, duplicate traffic, and resource cost.

Once that works, realism can be added incrementally.

