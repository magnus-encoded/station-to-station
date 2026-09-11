# Public Gossip wire vectors

`signed-pass/` contains fixed P-256 signatures generated independently with OpenSSL.
The private keys were discarded. Both native test suites must verify every Envelope,
verify the relay proof, and reproduce the exact stored bytes when encoding the Pass.
Fixtures are required, never skipped if missing.

The relay and author keys differ. The batch covers UTF-8 text, a blank replacement,
former Gig IDs, absent optional fields, epoch milliseconds and the `-1` request line.
`nonce.bin` is bytes 0 through 31; `proof-payload.txt` uses the v2 authentication domain.
Neither text file has a final newline. `.gitattributes` prevents checkout conversion
from changing the bytes being tested.
