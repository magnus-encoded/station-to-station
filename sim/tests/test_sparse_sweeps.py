from station_to_station_sim.mobility import venue_trace
from station_to_station_sim.sparse_sweeps import sparse_trace


def test_sparse_adoption_filters_the_full_crowd_instead_of_resampling_encounters():
    for seed in (7, 11, 23):
        full = venue_trace(users=300, facts=0, seed=seed, density=100)
        for users in (3, 10):
            sparse = sparse_trace(users, crowd=300, seed=seed)
            assert len(sparse.users) == users
            assert sparse.encounters == tuple(e for e in full.encounters
                                               if e.sender in sparse.users and e.receiver in sparse.users)
            assert len(sparse.encounters) < len(full.encounters) / 10
            assert {f.author for f in sparse.facts} == set(sparse.users)
            assert all(a != b for a, b in sparse.contacts)
            assert sparse == sparse_trace(users, crowd=300, seed=seed)
