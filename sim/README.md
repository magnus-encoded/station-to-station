# Station to Station gossip simulator

Exploratory Python code for comparing blind-relay gossip policies under a shared,
deterministic venue workload. This is separate from the production Android and iOS apps.

The implementation brief is [`../handoff.md`](../handoff.md). Keep routing policy,
application semantics, mobility, resource accounting, and metrics in separate modules so
the same workload can be replayed through different policies.

## Setup

```sh
cd sim
python -m venv .venv
. .venv/bin/activate
python -m pip install -e '.[dev]'
pytest
```

## Layout

| Path | Responsibility |
| --- | --- |
| `models.py` | Shared enums and dataclasses |
| `information.py` | Inventories, deltas, deduplication, and expiry |
| `population.py` | Population and social-graph construction |
| `mobility.py` | Venue movement and physical encounters |
| `policies.py` | Replaceable relay policies |
| `application.py` | Witness and convergent-log semantics |
| `metrics.py` | Event, simulation, and gig metrics |
| `simulation.py` | Simulation state transitions and runner |

